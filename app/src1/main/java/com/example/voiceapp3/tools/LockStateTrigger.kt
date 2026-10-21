package com.example.voiceapp3.tools

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.lang.reflect.Proxy

/**
 * Источник события замка + сторож применения.
 *
 * ## Почему подписка делается через отражение
 *
 * Прошивка ГУ IHU629G — Android 9 (см. `app/build.gradle.kts`: «ставится на ГУ
 * Geely (Android 9)»), где менеджер свойств лежит в
 * `android.car.hardware.CarPropertyManager`. Предыдущая реализация
 * (`WindowDoors.kt`) импортировала `android.car.hardware.property.CarPropertyManager`
 * — это путь класса из API 30+, и на Android 9 такой import даёт
 * `NoClassDefFoundError` в рантайме при первом обращении. Здесь класс
 * резолвится в рантайме в том порядке, в каком он существует на устройстве,
 * поэтому подписка работает и на 9, и на 13.
 *
 * ## Какой prop-id считать замком
 *
 * В CentralEXAuto состояния замка нет вообще: проверены все целочисленные
 * идентификаторы свойств, которые приложение читает/пишет
 * (`w0/o7.java`, `w0/g1.java`, пакет `a1`, `w0/q1.java`, `w0/c0.java`) — это
 * HVAC (0x15200507/50A/510/514, 0x15400500/501/50B), окна/двери
 * (0x11400400/401/50D, 0x16400B00), руль (0x15600099), AEB (0x11400400),
 * вендорские 0x20020500-503 и 0x22010100-103. Ни один из них не является
 * состоянием центрального замка, и референс на него не подписывается: звук
 * закрытия играет сама прошивка из ключа `lock_car_audio_path`.
 *
 * Поэтому идентификатор свойства замка НЕ выдуман, а определяется на стенде и
 * записывается в реестр ключом [KEY_LOCK_PROP]. Пока он не задан, триггер не
 * подписывается ни на что и честно сообщает об этом в UI и logcat. Для подбора
 * есть [probeLockSignal]: он перечисляет все доступные свойства и находит те,
 * что меняются во время нажатия кнопки брелока.
 *
 * ## Фильтрация
 *
 * Решение «играть/не играть» принимает чистый класс [LockTransition]
 * (базовое значение при подписке не играет, повтор состояния не играет,
 * переход — один раз за окно дребезга). Здесь только доставка значений:
 * и из callback, и из опроса (вендорские свойства не всегда шлют onChangeEvent).
 */
object LockStateTrigger {
    const val TAG = "GeelyTools"

    /** Ключ реестра (домен system): int-id свойства состояния центрального замка. */
    const val KEY_LOCK_PROP = "geelytools_lock_prop"

    /**
     * Свойство центрального замка по умолчанию: `BODY_DOOR_LOCK_STATE`.
     *
     * Взято не из догадки, а из дампа прошивки ГУ (geely_dump, ECARX):
     * `car_service_dump.txt` — строка
     * `Property:0x214020bc,Property name:BODY_DOOR_LOCK_STATE,access:0x3,changeMode:0x1`
     * и `CarPropertyConfig{mPropertyId=557850812, mAccess=3, mAreaType=0,
     * mChangeMode=1, mSupportedAreas={}, mType=class java.lang.Integer}`.
     * То есть: VENDOR-свойство, INT32, зона GLOBAL, доступ чтение+запись,
     * режим ON_CHANGE — ровно то, что нужно для подписки и фильтра по фронту.
     *
     * Соседние свойства той же группы (кандидаты, если первое не подойдёт):
     * 557850813 = 0x214020bd `BODY_DOOR_UNLOCK_STATE`,
     * 557884229 = 0x2140a345 `BCM_ATWS_STS` (состояние охраны),
     * 557887522 = 0x2140b022 `VCU_LOCK_VEH_STS`.
     *
     * Значения свойств в дампе отсутствуют, поэтому полярность (какое число
     * означает «закрыт») по-прежнему определяется подбором на стенде —
     * [KEY_LOCK_POLARITY] и `startProbe`.
     */
    const val DEFAULT_LOCK_PROP_BODY_DOOR_LOCK_STATE = 557850812

    /**
     * Ключ реестра: играть ли звук ЗАКРЫТИЯ самим приложением.
     * По умолчанию `0` — закрытие играет прошивка ГУ из `lock_car_audio_path`
     * (механизм референса), и второй звук от приложения дал бы дубль.
     * Ставится в `1` через `adb shell settings put system geelytools_lock_audio_app_play 1`,
     * если диагностика на ГУ показала, что система закрытие не озвучивает.
     */
    const val KEY_LOCK_APP_PLAY = "geelytools_lock_audio_app_play"

    /** Полярность целочисленного значения свойства (0 = закрыт по умолчанию). */
    const val KEY_LOCK_POLARITY = "geelytools_lock_polarity"

    /** Период опроса свойства, мс. */
    const val POLL_INTERVAL_MS = 300L

    /** Период сторожа применения ключей, мс. */
    const val WATCHDOG_INTERVAL_MS = 30_000L

    /** Сколько последних решений события держим для диагностики. */
    private const val HISTORY_LIMIT = 200

    private val candidatesCpm = listOf(
        "android.car.hardware.property.CarPropertyManager",
        "android.car.hardware.CarPropertyManager"
    )

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var car: Any? = null
    private var cpm: Any? = null
    private var cpmClass: Class<*>? = null
    private var subscribedProp = 0
    private var callbackProxy: Any? = null
    private var appContext: Context? = null

    private var transition = LockTransition()
    private val history = ArrayDeque<String>()

    @Volatile
    private var running = false

    /** Последнее агрегированное состояние замка: true = открыт, false = закрыт, null = неизвестно. */
    val lockState: Boolean? get() = transition.state

    /** Статус-строка для UI: что подписано, сколько событий принято/отклонено. */
    fun statusLine(ctx: Context): String {
        val prop = lockPropId(ctx)
        val carReady = cpm != null
        return "prop=${if (prop == 0) "не задан" else "0x" + Integer.toHexString(prop) + " ($prop)"} " +
            "car=${if (carReady) "подключён" else "нет"} " +
            "подписка=${if (subscribedProp != 0) "0x" + Integer.toHexString(subscribedProp) else "нет"} " +
            "событий=${transition.totalEvents} отклонено=${transition.rejectedEvents} " +
            "состояние=${stateName(transition.state)}"
    }

    /** Лог перехвата: каждое событие — значение, previous, принято/отклонено и почему. */
    fun lockLog(): List<String> = synchronized(history) { history.toList() }

    fun stateName(v: Boolean?): String = when (v) {
        null -> "неизвестно"
        true -> "открыт"
        false -> "закрыт"
    }

    // ──────────────────────────────────────────────────────────────
    // Реестр
    // ──────────────────────────────────────────────────────────────

    fun lockPropId(ctx: Context): Int =
        LockSound.registryInt(ctx, KEY_LOCK_PROP, DEFAULT_LOCK_PROP_BODY_DOOR_LOCK_STATE)

    fun lockAppPlaysClose(ctx: Context): Boolean = LockSound.registryInt(ctx, KEY_LOCK_APP_PLAY, 0) == 1

    fun zeroMeansLocked(ctx: Context): Boolean = LockSound.registryInt(ctx, KEY_LOCK_POLARITY, 0) == 0

    // ──────────────────────────────────────────────────────────────
    // Жизненный цикл
    // ──────────────────────────────────────────────────────────────

    /** Запустить триггер. Безопасно вызывать повторно. */
    @Synchronized
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appContext = app
        if (running) {
            resubscribeIfPropChanged(app)
            return
        }
        running = true
        val t = HandlerThread("GeelyToolsLockState")
        t.start()
        thread = t
        handler = Handler(t.looper)
        handler?.post { loop(app) }
        AppLog.i(app, TAG, "LockStateTrigger: запущен (${LockSound.isEnabled(app)})")
    }

    @Synchronized
    fun stop(ctx: Context) {
        running = false
        try {
            if (subscribedProp != 0 && cpm != null) unregister(subscribedProp)
        } catch (t: Throwable) {
            AppLog.w(ctx, TAG, "unregisterCallback → ${describeThrowable(t)}", t)
        }
        subscribedProp = 0
        thread?.quitSafely()
        thread = null
        handler = null
        try {
            carClass()?.getMethod("disconnect")?.invoke(car)
        } catch (t: Throwable) {
            AppLog.w(ctx, TAG, "Car.disconnect → ${describeThrowable(t)}", t)
        }
        car = null
        cpm = null
        AppLog.i(ctx, TAG, "LockStateTrigger: остановлен")
    }

    private fun resubscribeIfPropChanged(ctx: Context) {
        handler?.post {
            val want = lockPropId(ctx)
            if (want != subscribedProp) {
                AppLog.i(ctx, TAG, "свойство замка сменилось: $subscribedProp → $want, переподписываюсь")
                subscribe(ctx, want)
            }
        }
    }

    /** Рабочий цикл: подключение к Car, подписка, опрос, сторож применения. */
    private fun loop(ctx: Context) {
        if (!running) return
        try {
            ensureCar(ctx)
            val prop = lockPropId(ctx)
            if (prop != 0) {
                if (subscribedProp != prop) subscribe(ctx, prop)
                pollOnce(ctx, prop)
            } else if (subscribedProp != 0) {
                unregister(subscribedProp)
                subscribedProp = 0
            }
            if (SystemClock.elapsedRealtime() - lastWatchdogAt > WATCHDOG_INTERVAL_MS) {
                lastWatchdogAt = SystemClock.elapsedRealtime()
                watchdog(ctx)
            }
        } catch (t: Throwable) {
            AppLog.e(ctx, TAG, "LockStateTrigger.loop → ${describeThrowable(t)}", t)
            record("loop", "-", t.javaClass.simpleName, false, describeThrowable(t))
        }
        handler?.postDelayed({ loop(ctx) }, POLL_INTERVAL_MS)
    }

    private var lastWatchdogAt = 0L
    private var pollFailures = 0

    /** Сторож: ключи ГУ могли быть сброшены прошивкой — переприменяем. */
    private fun watchdog(ctx: Context) {
        try {
            LockSound.ensureApplied(ctx)
        } catch (t: Throwable) {
            AppLog.w(ctx, TAG, "ensureApplied → ${describeThrowable(t)}", t)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Car / CarPropertyManager через отражение
    // ──────────────────────────────────────────────────────────────

    private fun carClass(): Class<*>? = try {
        Class.forName("android.car.Car")
    } catch (t: Throwable) {
        null
    }

    private fun ensureCar(ctx: Context) {
        if (cpm != null) return
        val cls = carClass() ?: run {
            record("car", "-", "нет класса", false, "android.car.Car отсутствует: библиотека android.car не доступна процессу")
            return
        }
        try {
            if (car == null) {
                car = cls.getMethod("createCar", Context::class.java).invoke(null, ctx)
            }
            val c = car ?: return
            val connected = cls.getMethod("isConnected").invoke(c) as? Boolean ?: false
            if (!connected) {
                record("car", "-", "не подключён", false, "Car.isConnected=false — сервис автомобиля ещё не готов")
                return
            }
            val mgr = cls.getMethod("getCarManager", String::class.java).invoke(c, "property")
            if (mgr == null) {
                record("car", "-", "нет менеджера", false, "Car.getCarManager(\"property\") вернул null")
                return
            }
            cpm = mgr
            cpmClass = candidatesCpm.mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
                .firstOrNull { it.isInstance(mgr) } ?: mgr.javaClass
            AppLog.i(ctx, TAG, "CarPropertyManager получен: ${cpmClass?.name}")
        } catch (t: Throwable) {
            record("car", "-", "ошибка", false, describeThrowable(t))
            AppLog.e(ctx, TAG, "ensureCar → ${describeThrowable(t)}", t)
        }
    }

    private fun subscribe(ctx: Context, prop: Int) {
        val mgr = cpm ?: return
        val cls = cpmClass ?: return
        try {
            if (subscribedProp != 0) runCatching { unregister(subscribedProp) }
            val cbClass = candidatesCpm.mapNotNull { runCatching { Class.forName("$it\$CarPropertyEventCallback") }.getOrNull() }
                .firstOrNull() ?: run {
                record("subscribe", "0x" + Integer.toHexString(prop), "нет интерфейса", false,
                    "CarPropertyEventCallback не найден ни в android.car.hardware.property, ни в android.car.hardware")
                return
            }
            val proxy = Proxy.newProxyInstance(
                cbClass.classLoader, arrayOf(cbClass)
            ) { _, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        try {
                            onPropertyEvent(ctx, args?.getOrNull(0))
                        } catch (t: Throwable) {
                            AppLog.e(ctx, TAG, "onChangeEvent → ${describeThrowable(t)}", t)
                        }
                        null
                    }
                    "onErrorEvent" -> {
                        val p = args?.getOrNull(0)
                        val a = args?.getOrNull(1)
                        AppLog.w(ctx, TAG, "CarProperty onErrorEvent prop=$p area=$a")
                        record("onErrorEvent", p?.toString() ?: "-", "ошибка свойства", false, "area=$a")
                        null
                    }
                    "toString" -> "LockStateTriggerCallback"
                    /* Прокси обязан отвечать на Object-методы: внутри invoke()
                       ссылка на сам прокси ещё не присвоена, поэтому идентификатор
                       берём у стабильного объекта-держателя. */
                    "hashCode" -> System.identityHashCode(proxyHolder)
                    "equals" -> args?.getOrNull(0) === proxyHolder ||
                        args?.getOrNull(0) === callbackProxy
                    else -> null
                }
            }
            callbackProxy = proxy
            cls.getMethod("registerCallback", cbClass, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .invoke(mgr, proxy, prop, 0.0f)
            subscribedProp = prop
            pollFailures = 0
            transition = LockTransition(zeroMeansLocked = zeroMeansLocked(ctx))
            record("subscribe", "0x" + Integer.toHexString(prop), "подписка принята", true,
                "registerCallback($prop, SENSOR_RATE_ONCHANGE=0.0f); первое значение будет базовым")
            AppLog.i(ctx, TAG, "registerCallback(0x${Integer.toHexString(prop)}) OK")
        } catch (t: Throwable) {
            record("subscribe", "0x" + Integer.toHexString(prop), "отказ", false, describeThrowable(t))
            AppLog.e(ctx, TAG, "subscribe(0x${Integer.toHexString(prop)}) → ${describeThrowable(t)}", t)
        }
    }

    private val proxyHolder = Any()

    private fun unregister(prop: Int) {
        val mgr = cpm ?: return
        val cls = cpmClass ?: return
        val proxy = callbackProxy ?: return
        val cbClass = proxy.javaClass.interfaces.firstOrNull() ?: return
        runCatching {
            cls.getMethod("unregisterCallback", cbClass).invoke(mgr, proxy)
        }
    }

    private fun onPropertyEvent(ctx: Context, value: Any?) {
        if (value == null) return
        val cls = value.javaClass
        val prop = runCatching { cls.getMethod("getPropertyId").invoke(value) as Int }.getOrNull() ?: return
        val area = runCatching { cls.getMethod("getAreaId").invoke(value) as Int }.getOrNull() ?: 0
        val v = runCatching { cls.getMethod("getValue").invoke(value) }.getOrNull()
        handleValue(ctx, prop, area, v, "onChangeEvent")
    }

    /** Опрос свойства: вендорские свойства не всегда шлют onChangeEvent. */
    private fun pollOnce(ctx: Context, prop: Int) {
        val mgr = cpm ?: return
        val cls = cpmClass ?: return
        try {
            val m = cls.methods.firstOrNull {
                it.name == "getProperty" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: return
            val value = m.invoke(mgr, prop, 0) ?: return
            val area = runCatching { value.javaClass.getMethod("getAreaId").invoke(value) as Int }.getOrNull() ?: 0
            val v = runCatching { value.javaClass.getMethod("getValue").invoke(value) }.getOrNull()
            handleValue(ctx, prop, area, v, "poll")
        } catch (t: Throwable) {
            /* Опрос не является ошибкой подписки. Пишем только ПЕРВУЮ неудачу:
               иначе история забьётся одним и тем же сообщением каждые 300 мс. */
            if (pollFailures == 0) {
                record("poll", "0x" + Integer.toHexString(prop), "не прочитано", false, describeThrowable(t))
            }
            pollFailures++
        }
    }

    /** Единая точка решения: значение → [LockTransition] → играем или нет. */
    private fun handleValue(ctx: Context, prop: Int, area: Int, raw: Any?, source: String) {
        val v = transition.onLockEvent(area, raw, SystemClock.elapsedRealtime())
        record(
            source, "0x" + Integer.toHexString(prop) + " area=$area значение=" + describeValue(raw),
            "previous=" + stateName(v.previous) + " → " + stateName(v.state),
            v.play, v.reason
        )
        if (!v.play) return
        if (v.playUnlock) playUnlock(ctx, v.reason)
        if (v.playLock) onLockTransition(ctx, v.reason)
    }

    private fun playUnlock(ctx: Context, reason: String) {
        val path = LockSound.getUnlockSelectedPath(ctx)
        if (path.isEmpty()) {
            record("playUnlock", "-", "не сыграно", false, "ключ ${LockSound.KEY_UNLOCK_PATH} пуст — файл разблокировки не выбран")
            return
        }
        if (!LockSound.isUnlockSoundEnabled(ctx)) {
            record("playUnlock", path, "не сыграно", false, "тумблер звука разблокировки выключен")
            return
        }
        val f = File(path)
        if (!f.exists()) {
            record("playUnlock", path, "не сыграно", false, "файла нет на диске (проверьте $path)")
            return
        }
        val report = LockSoundPlayer.play(ctx, f, LockSound.getVolume(ctx), LockSound.getDelayMs(ctx))
        record("playUnlock", path, "СЫГРАНО открытие", true, "$reason | $report")
    }

    private fun onLockTransition(ctx: Context, reason: String) {
        if (!lockAppPlaysClose(ctx)) {
            record(
                "playLock", LockSound.getSelectedPath(ctx).ifEmpty { "-" }, "не играем", true,
                "$reason | закрытие играет прошивка ГУ из ${LockSound.KEY_LOCK_PATH} (дубль не нужен). " +
                    "Если на стенде звука нет — поставьте $KEY_LOCK_APP_PLAY=1"
            )
            return
        }
        val path = LockSound.getSelectedPath(ctx)
        if (path.isEmpty() || !File(path).exists()) {
            record("playLock", path.ifEmpty { "-" }, "не сыграно", false,
                "файл закрытия не выбран или отсутствует на диске")
            return
        }
        val report = LockSoundPlayer.play(ctx, File(path), LockSound.getVolume(ctx), LockSound.getDelayMs(ctx))
        record("playLock", path, "СЫГРАНО закрытие", true, "$reason | $report")
    }

    private fun record(source: String, what: String, decision: String, accepted: Boolean, reason: String) {
        val line = "${SystemClock.elapsedRealtime()} $source $what → ${if (accepted) "ПРИНЯТО" else "ОТКЛОНЕНО"} " +
            "[${"$"}decision] $reason"
        synchronized(history) {
            history.addLast(line)
            while (history.size > HISTORY_LIMIT) history.removeFirst()
        }
        val ctx = appContext ?: return
        AppLog.i(ctx, TAG, "LockState: $line")
    }

    private fun describeValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        is Collection<*> -> "[${v.joinToString(",")}]"
        else -> "${v.javaClass.simpleName}=$v"
    }

    // ──────────────────────────────────────────────────────────────
    // Проверка фильтра по фронту без железа
    // ──────────────────────────────────────────────────────────────

    /**
     * Подать значение в ТОТ ЖЕ тракт, которым приходит реальное событие
     * свойства ([handleValue]): решение принимает [LockTransition], звук идёт
     * через [LockSoundPlayer]. Нужно, чтобы сценарий «два одинаковых значения
     * подряд» и «подписка при уже открытой машине» проверялись на стенде без
     * брелока, и чтобы заказчик видел в логе перехвата принятое решение.
     *
     * @param value значение свойства: "0"/"1"/"true"/"false" или произвольное —
     *        неразобранное значение тоже полезно прогнать (оно не играет).
     */
    fun injectForTest(ctx: Context, value: String): String {
        appContext = ctx.applicationContext
        if (transition.state == null) {
            /* Детектор ещё не получал значений: это первое — базовое. */
            handleValue(ctx, subscribedProp, 0, value, "inject")
            return "значение \"$value\" принято как БАЗОВОЕ (первое после подписки) — звук не запланирован. " +
                "Подайте второе значение, чтобы проверить переход"
        }
        handleValue(ctx, subscribedProp, 0, value, "inject")
        return lockLog().lastOrNull() ?: "нет записи"
    }

    // ──────────────────────────────────────────────────────────────
    // Подбор свойства замка на стенде
    // ──────────────────────────────────────────────────────────────

    /**
     * Найти свойство, которое меняется при нажатии кнопки брелока.
     *
     * Работает без заранее известного prop-id: перечисляет все свойства,
     * которые процессу разрешено читать, снимает значения, ждёт [windowMs]
     * (в это время нужно закрыть/открыть машину с брелока) и возвращает те,
     * чьё значение изменилось, вместе с парой «до → после».
     *
     * Результат — не догадка, а наблюдение. Дальше заказчик пишет найденный id:
     * `adb shell settings put system geelytools_lock_prop <id>`.
     */
    @Volatile
    private var probeRunning = false

    @Volatile
    private var probeResult: List<String> = emptyList()

    /** Идёт ли сейчас подбор сигнала (окно ожидания нажатий брелока). */
    fun isProbing(): Boolean = probeRunning

    /** Результат последнего подбора; пусто, если подбора ещё не было. */
    fun probeResult(): List<String> = probeResult

    /**
     * Запустить подбор асинхронно. Мост WebView не блокируется: результат
     * забирается [probeResult] после того, как [isProbing] снова станет false.
     */
    fun startProbe(ctx: Context, windowMs: Long = 15_000): String {
        if (probeRunning) return "подбор уже идёт — дождитесь окончания (${windowMs / 1000} с)"
        probeRunning = true
        probeResult = listOf("подбор запущен")
        probeExecutor.execute {
            try {
                probeResult = probeLockSignal(ctx, windowMs)
            } catch (t: Throwable) {
                probeResult = listOf("подбор не удался: ${describeThrowable(t)}")
                AppLog.e(ctx, TAG, "probe → ${describeThrowable(t)}", t)
            } finally {
                probeRunning = false
            }
        }
        return "подбор запущен: ${windowMs / 1000} с. Нажимайте брелок (закрыть/открыть) во время окна, затем «Показать результат»"
    }

    private val probeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun probeLockSignal(ctx: Context, windowMs: Long = 12_000): List<String> {
        val out = ArrayList<String>()
        if (handler == null) {
            start(ctx)
            out.add("триггер не был запущен — запущен сейчас, повторите подбор")
            return out
        }
        ensureCar(ctx)
        val mgr = cpm
        val cls = cpmClass
        if (mgr == null || cls == null) {
            out.add("CarPropertyManager недоступен: ${history.lastOrNull() ?: "нет диагностики"}")
            return out
        }
        val props = ArrayList<Int>()
        try {
            val list = cls.getMethod("getPropertyList").invoke(mgr) as? List<*>
            list?.forEach { v ->
                if (v == null) return@forEach
                val id = runCatching { v.javaClass.getMethod("getPropertyId").invoke(v) as Int }.getOrNull()
                if (id != null) props.add(id)
            }
        } catch (t: Throwable) {
            out.add("getPropertyList → ${describeThrowable(t)}")
        }
        if (props.isEmpty()) {
            out.add("список свойств пуст — чтение недоступно процессу приложения")
            return out
        }
        out.add("прочитать разрешено ${props.size} свойств; жду ${windowMs / 1000} с — закройте и откройте машину с брелока")
        val getter = cls.methods.firstOrNull {
            it.name == "getProperty" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (getter == null) {
            out.add("нет метода getProperty(int,int) — подбор невозможен")
            return out
        }
        val before = HashMap<Int, String>()
        for (p in props.distinct()) {
            val s = readOne(mgr, getter, p)
            if (s != null) before[p] = s
        }
        out.add("снято ${before.size}/${props.size} значений")
        try {
            Thread.sleep(windowMs)
        } catch (_: InterruptedException) {
            out.add("ожидание прервано")
            return out
        }
        var changed = 0
        for ((p, b) in before) {
            val a = readOne(mgr, getter, p) ?: continue
            if (a != b) {
                changed++
                out.add("ИЗМЕНИЛОСЬ 0x" + Integer.toHexString(p) + " ($p): $b → $a")
            }
        }
        if (changed == 0) out.add("за окно ничего не изменилось: повторите, нажимая брелок во время ожидания")
        out.add("запись найденного id: adb shell settings put system $KEY_LOCK_PROP <id>")
        record("probe", "${props.size} свойств", "изменилось $changed", changed > 0, out.joinToString(" | "))
        return out
    }

    private fun readOne(mgr: Any, getter: java.lang.reflect.Method, prop: Int): String? {
        return try {
            val v = getter.invoke(mgr, prop, 0) ?: return null
            val area = runCatching { v.javaClass.getMethod("getAreaId").invoke(v) as Int }.getOrNull() ?: 0
            val value = runCatching { v.javaClass.getMethod("getValue").invoke(v) }.getOrNull()
            "area=$area ${describeValue(value)}"
        } catch (t: Throwable) {
            null
        }
    }

}
