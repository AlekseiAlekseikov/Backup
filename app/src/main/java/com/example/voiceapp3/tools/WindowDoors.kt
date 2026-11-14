package com.example.voiceapp3.tools

import com.example.voiceapp3.tools.Prefs2

import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Stage CXT · срез №5: Автоматизация окон и дверей (§9 ТЗ).
 *
 * Точный перенос логики CentralEXAuto (com.ex.auto.CentralWatchdogService.k0()
 * и w0.s2 case 4 = windowDriver observer):
 *
 * 1. window_driver_on_door (case 15 в w0.kc): при открытии СВОЕЙ двери
 *    приоткрыть СВОЁ стекло на POS 10 (≈10%) — "filete de ar" для сброса
 *    избыточного давления воздуха при захлопывании. При закрытии двери —
 *    вернуть в POS 0 (закрыто).
 * 2. window_opposite_on_door (case 16 в w0.kc): то же самое, но вентилируется
 *    ПРОТИВОПОЛОЖНОЕ окно того же ряда (передний пассажир ↔ водитель,
 *    задний левый ↔ задний правый).
 *
 * Пороги OEM k0() (точное соответствие):
 *   - Открытие: читать текущую позицию целевого окна; если pos > 7 — НЕ МЕШАТЬ
 *     (стекло уже приоткрыто вручную); иначе set POS 10.
 *   - Закрытие: если |pos - 10| > 3 — НЕ МЕШАТЬ (пользователь сдвинул стекло
 *     сам после открытия двери); иначе set POS 0.
 *
 * Точные VHAL свойства Geely EX2 (IHU629G) из smali CentralEXAuto:
 * - DOOR_POS:  373295872 (0x16400b00)  — w0.q1.java:218
 * - WINDOW_POS: 322964416 (0x13400bc0) — g1.c/g1.l в k0()
 *
 * rawVal DOOR_POS: 1 = открыта, 2 = закрыта (из w0.s2 case 4).
 */
object WindowDoors {
    private const val TAG = "CENTRAL_WindowDoors"
    private const val PREFS_NAME = "voiceapp3"

    const val PREF_WINDOW_DRIVER_ON_DOOR = "window_driver_on_door"
    const val PREF_WINDOW_OPPOSITE_ON_DOOR = "window_opposite_on_door"
    /** iter23 P3 / iter32: СТАРЫЙ ключ — только для миграции (читается, больше не пишется). */
    const val PREF_VENT_WINDOWS = "vent_windows"
    /**
     * iter32 §3.2: КАРТА ВЛАДЕЛЬЦЕВ «окно → набор дверей, по чьей причине оно приоткрыто»
     * в формате `10=1,4;40=4` (hex через `;` и `,`; окно без владельцев — `100=`).
     * Плоский набор окон (`vent_windows`) не хранил, КАКАЯ дверь открыла окно, поэтому закрытие
     * одной двери закрывало окна обеих. Теперь владелец известен.
     */
    const val PREF_VENT_OWNERS = "vent_owners"

    // VHAL VehiclePropertyIds (IHU629G Geely EX2) из ex2_carprops.csv:
    const val PROP_DOOR_POS = 373295872     // 0x16400b00 — позиция двери (1=открыта,2=закрыта)
    const val PROP_WINDOW_POS = 322964416   // 0x13400bc0 — позиция СТЕКЛА (НЕ смешивать с дверью)
    // События ЗАМКА (реальный lock/unlock по RKE или кнопке на двери)
    const val PROP_DOOR_LOCK_STATE = 0x214020bc.toInt()   // 557918524 — BODY_DOOR_LOCK_STATE
    const val PROP_DOOR_UNLOCK_STATE = 0x214020bd.toInt() // 557918525 — BODY_DOOR_UNLOCK_STATE

    // Пороги из CentralWatchdogService.k0():
    private const val WINDOW_OPEN_THRESHOLD = 7   // pos > 7 → уже приоткрыто
    private const val WINDOW_OPEN_POS = 10        // позиция "filete de ar"
    private const val WINDOW_CLOSE_TOLERANCE = 3  // |pos-10| > 3 → не трогать
    private const val WINDOW_CLOSED_POS = 0

    // Зоны дверей -> Зоны стекол (из w0.u.f4341t CentralEXAuto):
    //   дверь водителя      (1=0x1)   -> стекло водителя      (16=0x10)
    //   дверь пер.пассажира (4=0x4)   -> стекло пер.пассажира (64=0x40)
    //   дверь зад.левого    (16=0x10) -> стекло зад.левого    (256=0x100)
    //   дверь зад.правого   (64=0x40) -> стекло зад.правого   (1024=0x400)
    val DOOR_TO_WINDOW = mapOf(1 to 16, 4 to 64, 16 to 256, 64 to 1024)

    // Противоположные окна того же ряда (w0.u.f4342u):
    val OPPOSITE_WINDOW = mapOf(16 to 64, 64 to 16, 256 to 1024, 1024 to 256)

    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null
    private var isRegistered = false
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val doorStates = HashMap<Int, Boolean>()

    /**
     * Множество окон, которые мы сами приоткрыли в этом цикле открытия двери.
     * Аналог поля f864o в CentralWatchdogService, но на МНОЖЕСТВО: при
     * одновременном открытии двух дверей каждая добавляет своё окно в набор,
     * и при закрытии соответствующей двери удаляет его. Если при закрытии
     * последней из всех открытых дверей окно всё ещё в POS ~10 — закрывает его
     * (метод T() в CentralWatchdogService).
     */
    private val ventOwners = HashMap<Int, MutableSet<Int>>()

    /**
     * iter49 §9 — ПОКОЛЕНИЕ набора владельцев (ABA-защита).
     *
     * Прежняя проверка позднего readback сравнивала только СОДЕРЖИМОЕ набора
     * (`now == ownersAtAttempt`). Этого мало: набор мог измениться A → B → A (за это время
     * другая дверь успела открыться и закрыться, либо запись пересоздана), и сравнение
     * «равно» пропустило бы удаление ЖИВОЙ записи. Счётчик растёт при каждом изменении
     * набора, поэтому совпадение содержимого при разных поколениях означает «было изменение».
     */
    private val ventGen = java.util.concurrent.atomic.AtomicLong(0)

    /** Текущее поколение набора владельцев (для сверки поздних ответов). */
    internal fun ventOwnersGeneration(): Long = ventGen.get()

    /** Набор дверей-владельцев окна (создаётся по требованию). ТОЛЬКО для чтения под lock. */
    private fun ownersOf(w: Int): MutableSet<Int> = synchronized(ventOwners) { ventOwners.getOrPut(w) { HashSet() } }

    /**
     * §9.1 iter50 — ЕДИНАЯ точка мутации набора владельцев.
     *
     * Дефект: поколение увеличивалось только в `closeVentWindow`, а `owners.add(...)` при
     * ОТКРЫТИИ и восстановление из prefs меняли набор БЕЗ нового поколения. Тогда поздний
     * readback закрытия видел «содержимое и поколение те же» и снимал живую запись (ABA).
     * Теперь любая мутация идёт через этот метод и всегда двигает поколение.
     */
    private fun mutateOwners(block: (MutableMap<Int, MutableSet<Int>>) -> Unit) {
        synchronized(ventOwners) {
            block(ventOwners)
            ventGen.incrementAndGet()
        }
    }

    /**
     * §9.1 iter50 — АТОМАРНЫЙ снимок «набор владельцев + поколение» одним lock.
     *
     * Раньше набор и `ventGen` читались двумя разными синхронизациями: между ними могла
     * пройти мутация, и решение позднего readback принималось по несогласованным данным.
     */
    private fun ownersSnapshotWithGen(): Pair<Map<Int, Set<Int>>, Long> = synchronized(ventOwners) {
        ventOwners.mapValues { it.value.toSet() } to ventGen.get()
    }

    /** Окно вообще наше (есть запись владельцев — возможно пустая, это legacy-окно). */
    private fun isOurs(w: Int): Boolean = synchronized(ventOwners) { ventOwners.containsKey(w) }

    /** Снимок карты владельцев для логов и чистой логики. */
    private fun ownersSnapshot(): Map<Int, Set<Int>> =
        synchronized(ventOwners) { ventOwners.mapValues { it.value.toSet() } }

    /** Снимок состояний дверей (для чистой логики; обновляется ДО вычисления владельцев). */
    private fun doorStatesSnapshot(): Map<Int, Boolean> = synchronized(doorStates) { HashMap(doorStates) }
    /** iter23 P3: prefs уже прочитаны в набор (reseed делается один раз за процесс). */
    @Volatile private var ventReseeded = false

    private var screenWakeReceiver: android.content.BroadcastReceiver? = null
    private var lastSleepTimeMs: Long = SystemClock.elapsedRealtime()

    /**
     * Дебаунс unlock-звука: не повторять чаще чем раз в UNLOCK_DEBOUNCE_MS,
     * даже если VHAL пришлёт несколько событий (RKE+кнопка+дверь одновременно).
     */
    private var lastUnlockSoundAtMs: Long = 0L
    private const val UNLOCK_DEBOUNCE_MS = 5000L

    /**
     * iter23 P3: набор окон хранится в SharedPreferences (`vent_windows`, hex через запятую),
     * иначе рестарт процесса/приложения терял «наши» окна и дверь закрывалась без закрытия стекла.
     */
    private fun persistVentOwners(ctx: Context) {
        runCatching {
            val v = synchronized(ventOwners) {
                ventOwners.entries.sortedBy { it.key }.joinToString(";") { (w, ds) ->
                    Integer.toHexString(w) + "=" + ds.sorted().joinToString(",") { Integer.toHexString(it) }
                }
            }
            Prefs2.putString(ctx, PREF_VENT_OWNERS, v)
            // iter32: старый ключ `vent_windows` больше НЕ пишем (пусть остаётся следом).
            AppLog.i(ctx, TAG, "VENT_SAVE owners=[$v]")
        }
    }

    /** Чтение набора из prefs; вызывается один раз за процесс. */
    private fun reseedVentOwners(ctx: Context) {
        if (ventReseeded) return
        ventReseeded = true
        val raw = Prefs2.getString(ctx, PREF_VENT_OWNERS, "") ?: ""
        if (raw.isNotBlank()) {
            for (part in raw.split(';')) {
                val p = part.trim()
                if (p.isEmpty() || !p.contains('=')) continue
                val winHex = p.substringBefore('=').trim()
                val doors = p.substringAfter('=')
                val w = runCatching { winHex.toInt(16) }.getOrNull() ?: continue
                // §9.1 iter50: восстановление — тоже МУТАЦИЯ набора: идёт через единую точку,
                // иначе поколение не двигалось и поздний readback считал набор «неизменным».
                mutateOwners { map ->
                    val set = map.getOrPut(w) { HashSet() }
                    for (d in doors.split(',')) {
                        val dv = d.trim().takeIf { it.isNotEmpty() }
                            ?.let { runCatching { it.toInt(16) }.getOrNull() } ?: continue
                        set.add(dv)
                    }
                }
                val set = synchronized(ventOwners) { ventOwners[w]?.toSortedSet()?.toList() ?: emptyList() }
                AppLog.i(ctx, TAG, "VENT_RESEED win=0x${Integer.toHexString(w)} owners=${set.joinToString(",") { Integer.toHexString(it) }}")
            }
            AppLog.i(ctx, TAG, "VENT_RESEED: восстановлено окон=${ventOwners.size}")
            return
        }
        // Миграция со старого ключа: окна «наши», но чья дверь — неизвестно (пустой набор владельцев).
        val legacy = Prefs2.getString(ctx, PREF_VENT_WINDOWS, "") ?: ""
        if (legacy.isBlank()) {
            AppLog.i(ctx, TAG, "VENT_RESEED: пусто (нет приоткрытых нами окон)")
            return
        }
        for (part in legacy.split(',')) {
            val a = part.trim().takeIf { it.isNotEmpty() }?.let { runCatching { it.toInt(16) }.getOrNull() } ?: continue
            mutateOwners { map -> map.getOrPut(a) { HashSet() } }   // ключ без владельцев = «наше окно, дверь неизвестна»
            AppLog.i(ctx, TAG, "VENT_LEGACY_MIGRATE win=0x${Integer.toHexString(a)} (owners неизвестны)")
        }
        AppLog.i(ctx, TAG, "VENT_RESEED: миграция со старого ключа, окон=${ventOwners.size}")
    }

    private fun fireUnlockSound(ctx: Context, reason: String) {
        if (!LockSound.isUnlockSoundEnabled(ctx)) {
            AppLog.i(ctx, TAG, "UNLOCK sound: disabled, skip (reason=$reason)")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnlockSoundAtMs < UNLOCK_DEBOUNCE_MS) {
            AppLog.i(ctx, TAG, "UNLOCK sound: debounce Δ=${now - lastUnlockSoundAtMs}ms, skip (reason=$reason)")
            return
        }
        lastUnlockSoundAtMs = now
        val soundPath = LockSound.getUnlockSelectedPath(ctx)
        val soundFile = if (soundPath.isNotEmpty()) java.io.File(soundPath) else java.io.File(LockSound.OEM_CARLOCK_DEFAULT_WAV)
        if (soundFile.exists()) {
            AppLog.i(ctx, TAG, "UNLOCK sound: firing (reason=$reason, file=${soundFile.name})")
            LockSound.playUnlockSound(ctx, soundFile)
        } else {
            AppLog.w(ctx, TAG, "UNLOCK sound: file not found ${soundFile.path}, skip (reason=$reason)")
        }
    }

    fun registerWakeReceiver(context: Context) {
        if (screenWakeReceiver != null) return
        val appCtx = appContext ?: context.applicationContext
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
            addAction("android.intent.action.QUICKBOOT_POWERON")
        }
        screenWakeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: android.content.Intent?) {
                val now = SystemClock.elapsedRealtime()
                val idleSeconds = (now - lastSleepTimeMs) / 1000
                lastSleepTimeMs = now
                AppLog.i(ctx, TAG, "ГУ проснулось (${intent?.action}), простой = ${idleSeconds} с — unlock звук ЗДЕСЬ НЕ ИГРАЕТ (играется только на реальный unlock по VHAL)")
                // P4 фикс: SCREEN_ON ≠ открытие замка. Звук играется только из
                // carCallback.onChangeEvent при приходе PROP_DOOR_UNLOCK_STATE.
            }
        }
        try {
            context.applicationContext.registerReceiver(screenWakeReceiver, filter)
        } catch (e: Throwable) {
            AppLog.w(appCtx, TAG, "registerWakeReceiver failed", e)
        }
    }

    private val carCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val ctx = appContext ?: return
            val propId = value.propertyId
            when (propId) {
                PROP_DOOR_POS -> {
                    val areaId = value.areaId
                    val rawVal = (value.value as? Number)?.toInt() ?: return
                    val isOpen = (rawVal == 1)
                    AppLog.i(ctx, TAG,
                        "DOOR_POS area=0x${Integer.toHexString(areaId)} raw=$rawVal open=$isOpen")
                    onDoorChanged(areaId, isOpen)
                }
                PROP_DOOR_UNLOCK_STATE -> {
                    val rawVal = (value.value as? Number)?.toInt() ?: -1
                    val areaId = value.areaId
                    AppLog.i(ctx, TAG,
                        "DOOR_UNLOCK_STATE area=0x${Integer.toHexString(areaId)} raw=$rawVal")
                    fireUnlockSound(ctx, "PROP_DOOR_UNLOCK_STATE(0x214020bd) raw=$rawVal area=0x${Integer.toHexString(areaId)}")
                }
                PROP_DOOR_LOCK_STATE -> {
                    val rawVal = (value.value as? Number)?.toInt() ?: -1
                    AppLog.i(ctx, TAG,
                        "DOOR_LOCK_STATE area=0x${Integer.toHexString(value.areaId)} raw=$rawVal (lock sound via Geely FW, not us)")
                }
            }
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            val ctx = appContext ?: return
            AppLog.w(ctx, TAG, "onErrorEvent prop=0x${Integer.toHexString(propId)} area=0x${Integer.toHexString(zone)}")
        }
    }

    fun isWindowDriverOnDoor(context: Context): Boolean {
        return Prefs2.getBoolean(context, PREF_WINDOW_DRIVER_ON_DOOR, false)
    }

    fun isWindowOppositeOnDoor(context: Context): Boolean {
        return Prefs2.getBoolean(context, PREF_WINDOW_OPPOSITE_ON_DOOR, false)
    }

    /** iter16: вызывать из App.onCreate/VoiceAssistantService.onCreate ВНЕ
     *  boot-ветки — раньше initCarService вызывался только при EXTRA_BOOT_RESTORE,
     *  из-за чего при обычном запуске слушатель дверей не регистрировался. */
    @JvmStatic fun maybeInit(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val driverOn = isWindowDriverOnDoor(appCtx)
        val oppOn = isWindowOppositeOnDoor(appCtx)
        val unlockOn = LockSound.isUnlockSoundEnabled(appCtx)
        AppLog.i(appCtx, TAG, "maybeInit driverOn=$driverOn oppOn=$oppOn unlockOn=$unlockOn isRegistered=$isRegistered")
        // iter32 §3.4: самопроверка чистой логики закрытия окон (6 кейсов мандата) — один раз при старте.
        runCatching { ventLogicSelfTest() }.onFailure { AppLog.w(appCtx, TAG, "VENT_SELFTEST failed: ${it.message}") }
        if (driverOn || oppOn || unlockOn) {
            initCarService(appCtx)
        }
    }

    fun initCarService(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (carPropertyManager != null || isRegistered) {
            AppLog.i(appCtx, TAG, "initCarService: already registered, skip")
            return
        }

        try {
            car = Car.createCar(appCtx, null, 0L) { carObj, ready ->
                if (ready) {
                    try {
                        val cpm = carObj.getCarManager("property") as? CarPropertyManager
                        if (cpm != null) {
                            carPropertyManager = cpm
                            registerDoorListener()
                            registerWakeReceiver(context)
                        }
                    } catch (e: Throwable) {
                        AppLog.w(appCtx, TAG, "Ошибка получения CarPropertyManager", e)
                    }
                } else {
                    carPropertyManager = null
                    isRegistered = false
                }
            }
        } catch (e: Throwable) {
            AppLog.w(appCtx, TAG, "Car.createCar failed", e)
        }
    }

    private fun registerDoorListener() {
        val cpm = carPropertyManager ?: return
        if (isRegistered) return
        val appCtx = appContext ?: return
        // Подписка на позицию двери (для режима «опустить стекло при открытии двери»).
        try {
            cpm.registerCallback(carCallback, PROP_DOOR_POS, 0.0f)
            AppLog.i(appCtx, TAG, "registerCallback DOOR_POS=0x16400b00 OK")
        } catch (e: Throwable) {
            AppLog.w(appCtx, TAG, "registerCallback DOOR_POS failed: ${e.message}")
        }
        // Подписка на СОБЫТИЯ ЗАМКА (P4): звук открытия привязан к unlock-state.
        try {
            cpm.registerCallback(carCallback, PROP_DOOR_UNLOCK_STATE, 0.0f)
            AppLog.i(appCtx, TAG, "registerCallback DOOR_UNLOCK_STATE=0x214020bd OK → unlock sound bound here")
        } catch (e: Throwable) {
            AppLog.w(appCtx, TAG, "registerCallback DOOR_UNLOCK_STATE failed: ${e.message}")
        }
        try {
            cpm.registerCallback(carCallback, PROP_DOOR_LOCK_STATE, 0.0f)
            AppLog.i(appCtx, TAG, "registerCallback DOOR_LOCK_STATE=0x214020bc OK (logging only, OEM plays close sound)")
        } catch (e: Throwable) {
            AppLog.w(appCtx, TAG, "registerCallback DOOR_LOCK_STATE failed: ${e.message}")
        }
        isRegistered = true
        AppLog.i(appCtx, TAG, "WindowDoors listener registered — DOOR_TO_WINDOW=$DOOR_TO_WINDOW OPPOSITE=$OPPOSITE_WINDOW")
    }

    /**
     * Обработчик события двери — точный перенос k0() CentralWatchdogService.
     *
     * @param doorAreaId  зона двери (1/4/16/64 = 0x1/0x4/0x10/0x40)
     * @param isOpen      true = дверь открылась, false = закрылась
     */
    private fun onDoorChanged(doorAreaId: Int, isOpen: Boolean) {
        val lastState = doorStates[doorAreaId]
        doorStates[doorAreaId] = isOpen
        if (lastState == isOpen) return

        val cpm = carPropertyManager ?: return
        val ctx = appContext ?: return
        val ownWindow = DOOR_TO_WINDOW[doorAreaId] ?: return

        // Выбираем целевое окно по настройке. В отличие от ранней версии,
        // режимы работают НЕЗАВИСИМО: если включены ОБА — вентилируются оба
        // окна (своё + противоположное). Это соответствует k0()-семантике,
        // где z3=true помечает вторую подряд итерацию для oposta.
        reseedVentOwners(ctx)
        val prefOwn = Prefs2.getBoolean(ctx, PREF_WINDOW_DRIVER_ON_DOOR, false)
        val prefOpp = Prefs2.getBoolean(ctx, PREF_WINDOW_OPPOSITE_ON_DOOR, false)

        // P4: unlock-звук здесь БОЛЬШЕ НЕ ИГРАЕТ. Он привязан к событию
        // PROP_DOOR_UNLOCK_STATE (VHAL-ивент разблокировки замка), а не к факту
        // открытия двери. Благодаря этому:
        //   • при открытых стёклах (PROP_WINDOW_POS) звук не срабатывает — это вообще
        //     другое VHAL-свойство;
        //   • при открытии двери изнутри при уже снятом замке звук не дублируется;
        //   • при открытии второй двери подряд — дебаунс в fireUnlockSound().
        // Продолжаем логику стеклоподъёмников ("filete de ar"):
        // Список окон для вентиляции: own и/или opposite в зависимости от префов.
        val targets = mutableListOf<Pair<Int, /*oposta*/ Boolean>>()
        if (prefOwn) targets.add(ownWindow to false)
        val opp = OPPOSITE_WINDOW[ownWindow]
        if (prefOpp && opp != null) targets.add(opp to true)

        // iter32 §3.2: при ЗАКРЫТИИ двери кандидатов даёт КАРТА ВЛАДЕЛЬЦЕВ, а не «своё + противоположное».
        // Именно старый код `listOfNotNull(ownWindow, OPPOSITE_WINDOW[ownWindow])` закрывал оба окна,
        // когда были открыты две двери (окно соседней двери попадало в плоский набор).
        if (!isOpen) {
            val candidates = closeCandidates(doorAreaId, ownWindow)
            if (candidates.isEmpty()) {
                AppLog.i(ctx, TAG, "VENT_CLOSE door=0x${Integer.toHexString(doorAreaId)} candidates=(none) — закрывать нечего")
                return
            }
            for (w in candidates) closeVentWindow(cpm, w, doorAreaId)
            return
        }

        if (targets.isEmpty()) return

        for ((target, oposta) in targets) {
            applyFilete(cpm, target, doorAreaId, true, oposta)
        }
    }

    /**
     * Применить filete de ar для одного окна — дословный перенос кода k0().
     *
     * iter32 §3.2: раньше здесь был плоский набор `activeVentWindows` (iter23 P3) — он не хранил,
     * КАКАЯ дверь открыла окно, из-за чего закрытие одной двери закрывало окна обеих.
     * Теперь окно помнит владельцев (`ventOwners`), а закрытие решает [ventCloseDecision].
     * Открытие по-прежнему соответствует независимому вызову k0() для каждой двери в OEM.
     *
     * @return true если команду в VHAL удалось отправить
     */
    private fun applyFilete(
        cpm: CarPropertyManager,
        windowAreaId: Int,
        doorAreaId: Int,
        doorOpened: Boolean,
        oposta: Boolean
    ): Boolean {
        val appCtx = appContext ?: return false
        val tag = if (oposta) "oposta " else ""
        val curPos = readWindowPos(cpm, windowAreaId)
        if (curPos == null) {
            AppLog.w(appCtx, TAG, "windowDriver чтение ${tag}0x${Integer.toHexString(windowAreaId)}: таймаут/ошибка VHAL")
            return false
        }

        if (doorOpened) {
            // k0(z2=true): приоткрываем только если окно фактически закрыто (pos ≤ 7).
            if (curPos > WINDOW_OPEN_THRESHOLD) {
                AppLog.i(appCtx, TAG, "janela ${tag}0x${Integer.toHexString(windowAreaId)} не закрыта (pos=$curPos) — дверь 0x${Integer.toHexString(doorAreaId)} открылась, не мешаю")
                return false
            }
            val ok = writeWindowPos(cpm, windowAreaId, WINDOW_OPEN_POS)
            if (ok) {
                // iter32 §3.2: владельцем окна становится ИМЕННО эта дверь (и для «своего» окна,
                // и для «противоположного» — это требование §3.3 п.2).
                mutateOwners { map -> map.getOrPut(windowAreaId) { HashSet() }.add(doorAreaId) }
                persistVentOwners(appCtx)
                val owners = synchronized(ventOwners) { ventOwners[windowAreaId]?.toSortedSet()?.toList() ?: emptyList() }
                AppLog.i(appCtx, TAG, "VENT_OPEN door=0x${Integer.toHexString(doorAreaId)} win=0x${Integer.toHexString(windowAreaId)} pos=$curPos owners=${owners.joinToString(",") { Integer.toHexString(it) }} gen=${ventGen.get()}")
                AppLog.i(appCtx, TAG, "janela ${tag}0x${Integer.toHexString(windowAreaId)} → POS $WINDOW_OPEN_POS (дверь 0x${Integer.toHexString(doorAreaId)} открыта)")
            } else {
                AppLog.w(appCtx, TAG, "windowDriver set ${tag}0x${Integer.toHexString(windowAreaId)} не удался")
            }
            return ok
        }
        // iter32 §3.2: ветка ЗАКРЫТИЯ больше не живёт здесь — закрытие решает closeVentWindow()
        // по карте владельцев (раньше здесь закрывалось «своё + противоположное» окно).
        return false
    }

    /**
     * iter32 §3.2: кандидаты на закрытие при закрытии двери `door`.
     * 1) все окна, у которых `door` есть в наборе владельцев;
     * 2) legacy-окна (запись есть, набор владельцев пуст) — только своё и противоположное этой двери.
     * Никакого `OPPOSITE_WINDOW` для «настоящих» окон здесь нет.
     */
    private fun closeCandidates(door: Int, ownWindow: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        synchronized(ventOwners) {
            for ((w, ds) in ventOwners) if (door in ds) out.add(w)
            for (w in listOfNotNull(ownWindow, OPPOSITE_WINDOW[ownWindow])) {
                if (ventOwners.containsKey(w) && ventOwners[w]!!.isEmpty()) out.add(w)
            }
        }
        return out.toList()
    }

    /**
     * iter32 §3.2: закрытие одного окна по решению чистой функции [ventCloseDecision].
     * Порядок: снять «эту дверь» из владельцев → решить → (если закрываем) проверить POS и записать 0.
     */
    private fun closeVentWindow(cpm: CarPropertyManager, windowAreaId: Int, doorAreaId: Int) {
        val appCtx = appContext ?: return
        val posBefore = readWindowPos(cpm, windowAreaId)
        val before = ownersSnapshot()[windowAreaId]?.toSet() ?: emptySet()
        val wasOurs = isOurs(windowAreaId)
        val states = doorStatesSnapshot()

        // ── iter33h C1.1/C1.2: владение фиксируется при ОТКРЫТИИ ──────────────────────────
        // Шаг 0 (вывод владельца из `DOOR_TO_WINDOW`) применяется ТОЛЬКО к legacy-окнам —
        // тем, что не наши или вообще без владельцев. Для «настоящих» окон владелец — ровно та
        // дверь, которая окно открыла. Прежнее поведение объявляло открытую дверь 0x4 владельцем
        // окна 0x40, которое открыла дверь 0x1, и закрытие двери 0x1 запрещалось
        // (`skip reason=needed_by_door_0x4`) — окно оставалось приоткрытым.
        val legacy = !wasOurs || before.isEmpty()
        val ownersAfter = ventOwnersAfterClose(
            door = doorAreaId, window = windowAreaId, owners = before,
            doorStates = states, doorToWindow = DOOR_TO_WINDOW, legacy = legacy,
        )
        val added = ownersAfter - (before - doorAreaId)
        mutateOwners { map ->
            val set = map.getOrPut(windowAreaId) { HashSet() }
            set.clear()
            set.addAll(ownersAfter)
            if (set.isEmpty() && legacy) map.remove(windowAreaId)
        }
        val after = synchronized(ventOwners) { ventOwners[windowAreaId]?.toSet() ?: emptySet() }
        val pos = posBefore ?: -1
        val decision = ventCloseDecision(
            door = doorAreaId, window = windowAreaId,
            doorStates = states, owners = ownersSnapshot(),
            doorToWindow = DOOR_TO_WINDOW, pos = pos,
            openPos = WINDOW_OPEN_POS, tolerance = WINDOW_CLOSE_TOLERANCE,
            legacy = legacy,
        ).let { if (it == "close" && !wasOurs) "skip:not_ours" else it }
        val hex = { v: Int -> "0x" + Integer.toHexString(v) }
        val ownersHex = { vs: Set<Int> -> "[" + vs.sorted().joinToString(",") { hex(it) } + "]" }

        // ── iter33h C1.4: одно событие — одна строка WINDOW_EVENT ────────────────────────
        if (decision != "close") {
            persistVentOwners(appCtx)
            AppLog.i(appCtx, TAG, "WINDOW_EVENT door=${hex(doorAreaId)} action=close window=${hex(windowAreaId)} " +
                "candidates=[${hex(windowAreaId)}] legacy=$legacy added=${ownersHex(added)} " +
                "owners_before=${ownersHex(before)} owners_after=${ownersHex(after)} pos=$pos " +
                "result=skipped reason=${decision.removePrefix("skip:")}")
            return
        }

        val written = writeWindowPos(cpm, windowAreaId, WINDOW_CLOSED_POS)
        // ── C1.3: POS 0 подтверждается ЧТЕНИЕМ; закрытым без подтверждения не считаем ──
        val posAfter = if (written) readWindowPos(cpm, windowAreaId) else null
        // §9.1 iter50: `posAfter == null` больше НЕ считается закрытием. Отсутствие readback —
        // это «команда принята, факт неизвестен», а не «VHAL подтвердил»: прежнее выражение
        // попадало в ветку `closed` именно тогда, когда подтверждения не было вовсе.
        val outcome = ventCloseOutcome(written, posAfter, WINDOW_CLOSED_POS, WINDOW_CLOSE_TOLERANCE)
        val closed = outcome == "closed"
        if (closed) {
            synchronized(ventOwners) { if (ventOwners[windowAreaId]?.isEmpty() == true) ventOwners.remove(windowAreaId) }
            persistVentOwners(appCtx)
            AppLog.i(appCtx, TAG, "WINDOW_EVENT door=${hex(doorAreaId)} action=close window=${hex(windowAreaId)} " +
                "candidates=[${hex(windowAreaId)}] legacy=$legacy added=${ownersHex(added)} " +
                "owners_before=${ownersHex(before)} owners_after=${ownersHex(after)} pos=$pos " +
                "result=closed pos_after=${posAfter ?: "?"}")
        } else {
            // Владельцев не удаляем: окно не закрылось, значит дверь всё ещё «держит» его.
            //
            // §8.1 iter51: откат к `before` идёт через ЕДИНУЮ точку мутации и только пока набор
            // не изменился. Раньше набор переписывался напрямую в обход `mutateOwners`: (а) без
            // увеличения поколения, (б) поверх ЛЮБОГО изменения, сделанного за время команды
            // VHAL, — владелец, добавленный другой дверью, молча стирался, и его окно больше
            // никто не закрывал. Теперь: содержимое равно тому, что мы сами записали ⇒ откат;
            // иначе запись живёт и не трогается.
            var rolledBack = false
            mutateOwners { map ->
                val now = map[windowAreaId]?.toSet() ?: emptySet()
                val restore = ventRollbackOwners(now, ownersAfter, before)
                if (restore != null) {
                    val set = map.getOrPut(windowAreaId) { HashSet() }
                    set.clear()
                    set.addAll(restore)         // вернуть как было до попытки
                    if (set.isEmpty() && legacy) map.remove(windowAreaId)
                    rolledBack = true                // иначе запись живёт: не трогаем
                }
            }
            persistVentOwners(appCtx)
            // §9 iter46: `getIntProperty` возвращает ПОСЛЕДНЕЕ известное значение VHAL, а
            // применение команды может прийти позже (асинхронный стек). Поэтому расхождение —
            // это «ещё не подтверждено», а НЕ «окно физически не закрылось». Повторных
            // воздействий не делаем (никаких «дожать», повторных setIntProperty и т.п.).
            AppLog.w(appCtx, TAG, "VENT_CLOSE_UNCONFIRMED win=${hex(windowAreaId)} door=${hex(doorAreaId)} " +
                "pos=$pos pos_after=${posAfter ?: "?"} written=$written owners_after=${ownersHex(after)} " +
                "rolled_back=$rolledBack " +
                "reason=$outcome " +
                "physical_failure=not_claimed action=none " +
                "(значение VHAL могло не обновиться на момент чтения; один отложенный readback " +
                "только уточнит факт в журнале и не трогает окно)")
            // Разовый поздний readback (не цикл): если VHAL подтвердит закрытие — снимаем
            // владельцев и фиксируем факт. Ничего не двигаем и не «лечим».
            val retryWin = windowAreaId
            // §9 iter47: снимаем запись владельцев только если набор НЕ ИЗМЕНИЛСЯ с момента
            // попытки. Иначе повторное открытие (или новая дверь) уже добавило владельца, и
            // поздний readback «прошлого» закрытия выкинул бы живую запись — окно осталось бы
            // без владельца и его больше никто не закрыл бы.
            // §9.1 iter50: снимок «набор + поколение» берётся ОДНИМ lock (раньше две отдельные
            // синхронизации могли увидеть набор до мутации, а поколение — после).
            val (snapAtAttempt, genAtAttempt) = ownersSnapshotWithGen()
            val ownersAtAttempt = snapAtAttempt[retryWin]?.toSortedSet()?.toList() ?: emptyList()
            com.example.voiceapp3.tools.BgWorker.postDelayed("ventLateReadback", 1_500L) {
                runCatching {
                    val late = readWindowPos(cpm, retryWin)
                    if (late != null && kotlin.math.abs(late - WINDOW_CLOSED_POS) <= WINDOW_CLOSE_TOLERANCE) {
                        // §9.1 iter50: сверка и снятие — в ОДНОМ lock через единую точку мутации:
                        // проверка «не изменилось» и удаление больше не разнесены по времени.
                        var removed = false
                        mutateOwners { map ->
                            val now = map[retryWin]?.toSortedSet()?.toList() ?: emptyList()
                            // Сверяются ОБА факта: содержимое И поколение. A→B→A даёт равное
                            // содержимое, но другое поколение — значит запись живёт, её не трогаем.
                            if (ventLateReadbackMayDropOwners(
                                    now, ownersAtAttempt, ventGen.get(), genAtAttempt
                                )
                            ) {
                                map.remove(retryWin)
                                removed = true
                            }
                        }
                        val unchanged = removed
                        if (!unchanged) {
                            AppLog.i(appCtx, TAG, "VENT_CLOSE_LATE_KEPT win=${hex(retryWin)} " +
                                "pos_after=$late reason=owners_changed_since_attempt " +
                                "gen_attempt=$genAtAttempt gen_now=${ventGen.get()} aba_checked=yes " +
                                "(запись владельцев изменена позже — поздний readback её не трогает)")
                        }
                        persistVentOwners(appCtx)
                        AppLog.i(appCtx, TAG, "VENT_CLOSE_CONFIRMED_LATE win=${hex(retryWin)} " +
                            "pos_after=$late (поздний readback подтвердил закрытие; это НЕ отменяет " +
                            "того, что первый readback был устаревшим)")
                    } else {
                        AppLog.w(appCtx, TAG, "VENT_CLOSE_STILL_UNCONFIRMED win=${hex(retryWin)} " +
                            "pos_after=${late ?: "?"} physical_failure=not_claimed action=none")
                    }
                }
            }
        }
    }

    /** Чтение позиции окна (POS 0..100+) из VHAL; null при ошибке/таймауте. */
    private fun readWindowPos(cpm: CarPropertyManager, areaId: Int): Int? = runCatching {
        cpm.getIntProperty(PROP_WINDOW_POS, areaId)
    }.getOrNull()

    /** Установка позиции окна; возвращает true если setIntProperty не выбросил. */
    private fun writeWindowPos(cpm: CarPropertyManager, areaId: Int, pos: Int): Boolean = runCatching {
        cpm.setIntProperty(PROP_WINDOW_POS, areaId, pos)
        true
    }.getOrDefault(false)

    // P4: OEM wav-путь берём из LockSound (он же для звука открытия), чтобы не разъехаться.
    // Используем LockSound.OEM_CARLOCK_DEFAULT_WAV напрямую.

    fun setWindowDriverOnDoor(context: Context, enabled: Boolean): ToolsResult {
        val t0 = SystemClock.uptimeMillis()
        Prefs2.putBoolean(context, PREF_WINDOW_DRIVER_ON_DOOR, enabled)
        if (enabled) {
            initCarService(context)
        }
        val readback = isWindowDriverOnDoor(context)
        val step = ToolsStep(
            step = "settings.windowDriverOnDoor",
            command = "prefs.putBoolean($PREF_WINDOW_DRIVER_ON_DOOR, $enabled)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = 0,
            stdout = "windowDriverOnDoor=$readback",
            stderr = "",
            expected = enabled.toString(),
            actual = readback.toString(),
            status = ToolsStep.Status.OK
        )
        return ToolsResult(
            action = "settings.windowDriverOnDoor",
            title = "Стекло своей двери при открытии (filete de ar)",
            steps = listOf(step)
        )
    }

    fun setWindowOppositeOnDoor(context: Context, enabled: Boolean): ToolsResult {
        val t0 = SystemClock.uptimeMillis()
        Prefs2.putBoolean(context, PREF_WINDOW_OPPOSITE_ON_DOOR, enabled)
        if (enabled) {
            initCarService(context)
        }
        val readback = isWindowOppositeOnDoor(context)
        val step = ToolsStep(
            step = "settings.windowOppositeOnDoor",
            command = "prefs.putBoolean($PREF_WINDOW_OPPOSITE_ON_DOOR, $enabled)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.uptimeMillis(),
            exitCode = 0,
            stdout = "windowOppositeOnDoor=$readback",
            stderr = "",
            expected = enabled.toString(),
            actual = readback.toString(),
            status = ToolsStep.Status.OK
        )
        return ToolsResult(
            action = "settings.windowOppositeOnDoor",
            title = "Противоположное стекло при открытии двери",
            steps = listOf(step)
        )
    }
}

// ─────────── iter32 §3.2/§3.4: ЧИСТАЯ логика закрытия окна (без Android/VHAL) ───────────

/**
 * Решение «закрывать ли окно после закрытия двери» (iter32 §3.4).
 *
 * Правила:
 *  - окно не наше (`window` отсутствует в карте) → не закрывать никогда (открыто вручную);
 *  - после снятия `door` из владельцев набор не пуст → окно нужно другой открытой двери;
 *  - есть другая ОТКРЫТАЯ дверь, которой это окно принадлежит напрямую (`doorToWindow[d] == window`) → не закрывать.
 * Проверка позиции (`|pos − 10| ≤ 3`) — отдельно, в [ventCloseDecision], т.к. требует VHAL.
 */
internal fun shouldCloseAfterDoorClose(
    door: Int,
    window: Int,
    doorStates: Map<Int, Boolean>,
    owners: Map<Int, Set<Int>>,
    doorToWindow: Map<Int, Int>,
): Boolean {
    if (!owners.containsKey(window)) return false
    val after = (owners[window] ?: emptySet()) - door
    if (after.isNotEmpty()) return false
    if (doorStates.any { (d, open) -> open && d != door && doorToWindow[d] == window }) return false
    return true
}

/**
 * iter33h C1.5 — владельцы окна ПОСЛЕ закрытия двери (чистая функция, покрыта тестом).
 *
 * `legacy=true` (окно не наше или владельцев не было) — сохраняем прежнюю синхронизацию по
 * `DOOR_TO_WINDOW`: открытая дверь, которой это окно соответствует, становится владельцем.
 * `legacy=false` — владение не выводится из карты: снимаем только закрывшуюся дверь.
 */
internal fun ventOwnersAfterClose(
    door: Int,
    window: Int,
    owners: Set<Int>,
    doorStates: Map<Int, Boolean>,
    doorToWindow: Map<Int, Int>,
    legacy: Boolean,
): Set<Int> {
    val result = owners.toMutableSet()
    if (legacy) {
        for ((d, open) in doorStates) {
            if (open && d != door && doorToWindow[d] == window) result.add(d)
        }
    }
    result.remove(door)
    return result
}

/**
 * §9.1 iter50 — можно ли позднему readback снимать запись владельцев (чистая функция).
 *
 * Условия ОДНОВРЕМЕННО: набор не изменился с момента попытки (содержимое), владельцев не
 * осталось, и ПОКОЛЕНИЕ то же (иначе A→B→A дал бы равное содержимое при живой записи).
 */
internal fun ventLateReadbackMayDropOwners(
    now: List<Int>,
    atAttempt: List<Int>,
    genNow: Long,
    genAtAttempt: Long,
): Boolean = now == atAttempt && now.isEmpty() && genNow == genAtAttempt

/**
 * §9.1 iter50 — исход попытки закрытия по фактам: команда записана, что прочиталось.
 *
 * Возвращает `closed` ТОЛЬКО при подтверждённом чтении. Отсутствие readback — `readback_missing`
 * (два разных факта: «команда принята» и «VHAL подтвердил», их нельзя смешивать).
 */
internal fun ventCloseOutcome(
    written: Boolean,
    posAfter: Int?,
    closedPos: Int,
    tolerance: Int,
): String = when {
    !written -> "write_rejected"
    posAfter == null -> "readback_missing"
    kotlin.math.abs(posAfter - closedPos) <= tolerance -> "closed"
    else -> "late_readback"
}

/**
 * §8.1 iter51: можно ли ОТКАТИТЬ набор владельцев окна к состоянию до попытки закрытия.
 *
 * Попытка закрытия сначала записала `installed` (обычно `before + дверь`). Пока команда VHAL
 * летела, другая дверь могла добавить владельца. Прямое восстановление `before` в обход
 * [mutateOwners] затирало такую запись: окно оставалось без владельца и его больше никто не
 * закрывал. Поэтому откат разрешён ровно тогда, когда текущий набор — это то, что записали МЫ
 * (никто не менял), и запрещён, если набор уже другой.
 *
 * @return набор для записи, либо `null` — «не трогать» (запись живёт своей жизнью).
 */
internal fun ventRollbackOwners(
    current: Set<Int>,
    installed: Set<Int>,
    before: Set<Int>,
): Set<Int>? = when {
    // Никто не менял после нас — честный откат своей же записи.
    current == installed -> before
    // Набор уже другой: чужая (более новая) запись важнее нашего отката.
    else -> null
}

/**
 * Полное решение с причиной — то, что печатается в `VENT_CLOSE … reason=…`.
 * Возвращает `close` либо `skip:<причина>`.
 */
internal fun ventCloseDecision(
    door: Int,
    window: Int,
    doorStates: Map<Int, Boolean>,
    owners: Map<Int, Set<Int>>,
    doorToWindow: Map<Int, Int>,
    pos: Int,
    openPos: Int,
    tolerance: Int,
    legacy: Boolean = false,
): String {
    if (!owners.containsKey(window)) return "skip:not_ours"
    val after = (owners[window] ?: emptySet()) - door
    if (after.isNotEmpty()) {
        val holder = after.sorted().first()
        return "skip:needed_by_door_0x" + Integer.toHexString(holder)
    }
    // iter33h C1.1: карту `DOOR_TO_WINDOW` спрашиваем только для legacy-окон.
    // Иначе окно, открытое дверью 0x1, «держалось» бы открытой дверью 0x4.
    if (legacy) {
        val openOwner = doorStates.entries.firstOrNull { (d, open) -> open && d != door && doorToWindow[d] == window }
        if (openOwner != null) return "skip:needed_by_door_0x" + Integer.toHexString(openOwner.key)
    }
    if (pos >= 0 && kotlin.math.abs(pos - openPos) > tolerance) return "skip:not_near_$openPos"
    return "close"
}

private data class VentCase(
    val n: Int,
    val door: Int,
    val win: Int,
    val states: Map<Int, Boolean>,
    val owners: Map<Int, Set<Int>>,
    val pos: Int,
    val expect: String,
)

/**
 * iter32 §3.4: самопроверка чистой логики шестью кейсами из мандата.
 * В проекте нет юнит-тест-инфраструктуры (нет `app/src/test`, нет junit), поэтому тест —
 * обычная функция, которая вызывается при старте и печатает результат в лог.
 */
internal fun ventLogicSelfTest(): Boolean {
    val d2w = mapOf(1 to 16, 4 to 64, 16 to 256, 64 to 1024)
    val cases = listOf(
        VentCase(1, 1, 16, mapOf(1 to true, 4 to true), mapOf(16 to setOf(1), 64 to setOf(4)), 10, "close"),
        // iter33h C1.5: окно 0x40 открыла дверь 0x1; открытая дверь 0x4 его НЕ держит ⇒ close.
        VentCase(2, 1, 64, mapOf(1 to true, 4 to true), mapOf(16 to setOf(4), 64 to setOf(1)), 10, "close"),
        VentCase(3, 4, 16, mapOf(4 to true), mapOf(16 to setOf(4)), 10, "close"),
        VentCase(4, 1, 16, mapOf(1 to true), mapOf<Int, Set<Int>>(), 10, "skip:not_ours"),
        VentCase(5, 1, 16, mapOf(1 to true), mapOf(16 to setOf(1)), 40, "skip:not_near_10"),
        VentCase(6, 1, 256, mapOf(1 to true), mapOf(256 to emptySet()), 10, "close"),
    )
    var allOk = true
    for (c in cases) {
        val got = ventCloseDecision(c.door, c.win, c.states - c.door, c.owners, d2w, c.pos, 10, 3)
        val decision = if (got == "close" && !c.owners.containsKey(c.win)) "skip:not_ours" else got
        val ok = decision == c.expect
        if (!ok) allOk = false
        Log.i("CENTRAL_WindowDoors",
            "VENT_SELFTEST n=${c.n} door=0x${Integer.toHexString(c.door)} win=0x${Integer.toHexString(c.win)} " +
                "pos=${c.pos} decision=$decision expect=${c.expect} ok=$ok")
    }
    Log.i("CENTRAL_WindowDoors", "VENT_SELFTEST all=${if (allOk) "ok" else "FAIL"} n=${cases.size}")
    return allOk
}
