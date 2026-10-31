package com.example.voiceapp3.tools

import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.SharedPreferences
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
    private val activeVentWindows = hashSetOf<Int>()

    private var screenWakeReceiver: android.content.BroadcastReceiver? = null
    private var lastSleepTimeMs: Long = SystemClock.elapsedRealtime()

    /**
     * Дебаунс unlock-звука: не повторять чаще чем раз в UNLOCK_DEBOUNCE_MS,
     * даже если VHAL пришлёт несколько событий (RKE+кнопка+дверь одновременно).
     */
    private var lastUnlockSoundAtMs: Long = 0L
    private const val UNLOCK_DEBOUNCE_MS = 5000L

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
            AppLog.w(appCtx, TAG, "onErrorEvent prop=0x${Integer.toHexString(propId)} area=0x${Integer.toHexString(zone)}")
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        // iter16: при буте N direct-boot используем device-protected storage —
        // туда же пишутся переключатели через Settings.
        return try {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun isWindowDriverOnDoor(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_WINDOW_DRIVER_ON_DOOR, false)
    }

    fun isWindowOppositeOnDoor(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_WINDOW_OPPOSITE_ON_DOOR, false)
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
        val prefOwn = getPrefs(ctx).getBoolean(PREF_WINDOW_DRIVER_ON_DOOR, false)
        val prefOpp = getPrefs(ctx).getBoolean(PREF_WINDOW_OPPOSITE_ON_DOOR, false)

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

        if (targets.isEmpty()) return

        for ((target, oposta) in targets) {
            applyFilete(cpm, target, doorAreaId, isOpen, oposta)
        }
    }

    /**
     * Применить filete de ar для одного окна — дословный перенос кода k0().
     *
     * Багфикс двух дверей (b3/auditr3): activeVentWindows — МНОЖЕСТВО, а не одно поле.
     * При открытии нескольких дверей КАЖДОЕ соотв. окно добавляется в набор;
     * при закрытии — удаляется и закрывается. Это соответствует независимому
     * вызову k0 для каждой двери в s2.onChangeEvent в OEM.
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
                activeVentWindows.add(windowAreaId)
                AppLog.i(appCtx, TAG, "janela ${tag}0x${Integer.toHexString(windowAreaId)} → POS $WINDOW_OPEN_POS (дверь 0x${Integer.toHexString(doorAreaId)} открыта); activeSet=$activeVentWindows")
            } else {
                AppLog.w(appCtx, TAG, "windowDriver set ${tag}0x${Integer.toHexString(windowAreaId)} не удался")
            }
            return ok
        } else {
            // k0(z2=false): закрываем только если окно всё ещё около POS 10.
            if (kotlin.math.abs(curPos - WINDOW_OPEN_POS) > WINDOW_CLOSE_TOLERANCE) {
                AppLog.i(appCtx, TAG, "janela ${tag}0x${Integer.toHexString(windowAreaId)} не около POS $WINDOW_OPEN_POS (pos=$curPos) — дверь 0x${Integer.toHexString(doorAreaId)} закрылась, не мешаю")
                activeVentWindows.remove(windowAreaId)
                return false
            }
            if (windowAreaId !in activeVentWindows) {
                AppLog.i(appCtx, TAG, "janela ${tag}0x${Integer.toHexString(windowAreaId)} не была открыта нами в этом цикле — не закрываю")
                return false
            }
            val ok = writeWindowPos(cpm, windowAreaId, WINDOW_CLOSED_POS)
            if (ok) {
                activeVentWindows.remove(windowAreaId)
                AppLog.i(appCtx, TAG, "janela ${tag}0x${Integer.toHexString(windowAreaId)} → POS $WINDOW_CLOSED_POS (дверь 0x${Integer.toHexString(doorAreaId)} закрыта); activeSet=$activeVentWindows")
            } else {
                AppLog.w(appCtx, TAG, "windowDriver set ${tag}0x${Integer.toHexString(windowAreaId)} не удался")
            }
            return ok
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
        getPrefs(context).edit().putBoolean(PREF_WINDOW_DRIVER_ON_DOOR, enabled).commit()
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
        getPrefs(context).edit().putBoolean(PREF_WINDOW_OPPOSITE_ON_DOOR, enabled).commit()
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
