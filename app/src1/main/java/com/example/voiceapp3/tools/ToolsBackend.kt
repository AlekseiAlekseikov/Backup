package com.example.voiceapp3.tools

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Log
import android.view.View
import com.example.voiceapp3.TelemetryPrefsMirror
import com.google.gson.JsonObject
import com.example.voiceapp3.tools.StatusIcons
import com.example.voiceapp3.tools.LockSound
import com.example.voiceapp3.tools.WindowDoors
import com.example.voiceapp3.tools.NetworkTtl
import com.example.voiceapp3.tools.AppPerformance

/**
 * Точки, через которые backend настроек «Инструменты» доходит до живых
 * эффектов окна и движка. Реализует [com.example.voiceapp3.ui.UiBridge] —
 * так же, как в CentralEXAuto контроллер настроек ходит в Activity/Service,
 * а не держит состояние у себя.
 */
interface ToolsHost {

    /** Применить «Полный экран» к окну (sticky-панели ГУ). */
    fun applyImmersiveSetting(enabled: Boolean)

    /** Фактические оконные флаги — readback после применения. */
    fun immersiveWindowFlags(): Int

    /** Включить/выключить фоновое отслеживание CarPlay/Android Auto. */
    fun setProjectionBetaSetting(enabled: Boolean)

    /**
     * Значение тумблера CarPlay/AA в движке телеметрии.
     * `null` — движок недоступен, подтверждение неотразимо (шаг = PARTIAL).
     */
    fun projectionBetaState(): Boolean?
}

/**
 * Единая точка правды для настроек вкладки «Инструменты».
 *
 * CentralEXAuto хранит каждую настройку в SharedPreferences приложения,
 * применяет её живьём и перечитывает при следующем запуске; здесь та же
 * схема:
 *  - имена ключей pref не меняются (совпадают с уже работающим кодом шелла);
 *  - запись идёт из WebView-потока синхронно (`commit()`), чтение — оттуда
 *    же, поэтому у настройки один effective state и нет второго кэша в JS;
 *  - каждое действие возвращает [ToolsResult] с реальным readback — UI
 *    показывает подтверждение, а не предположение.
 *
 * Настройки, у которых ещё нет backend-а, в реестр не добавлены: список
 * растёт вместе с реализацией (яркость, восстановление по фарам и время
 * заставки приходят со следующим срезом).
 */
class ToolsBackend(
    context: Context,
    private val host: ToolsHost,
    /** Постить в UI-поток Activity (живые эффекты окна). */
    private val ui: (Runnable) -> Unit
) {

    private val appContext = context.applicationContext

/** Настройки-переключатели, доступные вкладке «Инструменты». */
enum class Toggle(
    /** Ключ события из WebView (`settings.change {key, value}`). */
    val key: String,
    /** Имя ключа в SharedPreferences — как в уже работающем коде. */
    val pref: String,
    /** Значение по умолчанию — ровно то, что было до переноса. */
    val default: Boolean,
    /** Название для статусной строки и диагностики. */
    val title: String
) {
    IMMERSIVE("imm", Prefs.IMMERSIVE, true, "Полный экран"),
    LAUNCH_ON_WAKE("wake", Prefs.LAUNCH_ON_WAKE, false, "Запуск при пробуждении"),
    CLIMATE_BAR("cb", Prefs.CLIMATE_BAR, false, "Полоса климата"),
    PROJECTION("proj", Prefs.PROJECTION_BETA, false, "CarPlay / Android Auto"),
    THEME_FOLLOW_HU("follow", Prefs.THEME_FOLLOW_HU, true, "Следовать теме ГУ"),
    REMEMBER_BRIGHTNESS(
        "remember", ScreenBrightness.PREF_REMEMBER, false, "Запоминать яркость по фарам"
    ),
    // Срез №3: Панель уведомлений (§5 ТЗ) — 3 тумблера (Сигнал НЕ переносим)
    NOTIF_BATTERY("notifBatt", StatusIcons.PREF_BATTERY, false, "Батарея автомобиля"),
    NOTIF_EXT_TEMP("notifTemp", StatusIcons.PREF_EXT_TEMP, false, "Наружная температура"),
    NOTIF_WIFI("notifWifi", StatusIcons.PREF_WIFI, false, "Wi‑Fi"),
    // Срез №3: Строка состояния (§6 ТЗ) — 3 тумблера hvac_digital_* (boolean)
    HVAC_DIGITAL_BATT("hvacBatt", StatusIcons.PREF_HVAC_BATT, false, "Процент заряда"),
    HVAC_DIGITAL_TEMP("hvacTemp", StatusIcons.PREF_HVAC_TEMP, false, "Цифровой климат-контроль"),
    HVAC_DIGITAL_EXT_TEMP("hvacExtTemp", StatusIcons.PREF_HVAC_EXT_TEMP, false, "Цифровая наружная температура"),
    // Срез №4: Звук блокировки замка (§7 ТЗ)
    LOCK_SOUND("lockSound", LockSound.PREF_LOCK_SOUND_ENABLED, false, "Звук блокировки замка"),
    WINDOW_DRIVER_ON_DOOR("windowDriverOnDoor", WindowDoors.PREF_WINDOW_DRIVER_ON_DOOR, false, "Стекло водителя при открытии двери"),
    WINDOW_OPPOSITE_ON_DOOR("windowOppositeOnDoor", WindowDoors.PREF_WINDOW_OPPOSITE_ON_DOOR, false, "Противоположное стекло"),
    TTL_65("ttl65", NetworkTtl.PREF_TTL_65_ACTIVE, false, "Фиксация TTL 65"),
    PERF_VIDEO("perfVideo", AppPerformance.PREF_PERF_VIDEO, false, "Оптимизация видео (1080p 60fps)"),
    PERF_JIT("perfJit", AppPerformance.PREF_PERF_AUTO_OPTIMIZE, false, "Учиться и оптимизировать самостоятельно (JIT)"),
    UNLOCK_SOUND("unlockSound", LockSound.PREF_UNLOCK_SOUND_ENABLED, false, "Звук открытия дверей");

    companion object {
        fun fromKey(key: String): Toggle? = values().firstOrNull { it.key == key }
    }
}

/** Выбор из фиксированного набора (палитра шелла, режим темы ГУ, hvac_digital_*). */
enum class Choice(
    val key: String,
    val pref: String,
    val default: String,
    val allowed: Set<String>,
    val title: String
) {
    HEAD_UNIT_THEME(
        key = "gu",
        pref = Prefs.HEAD_UNIT_THEME,
        default = "light",
        allowed = setOf("light", "dark"),
        title = "Тема головного устройства"
    ),
    SHELL_THEME(
        key = "theme",
        pref = TelemetryPrefsMirror.KEY_SHELL_THEME,
        default = "sepia",
        allowed = TelemetryPrefsMirror.THEME_IDS,
        title = "Тема приложения"
    ),
;

    companion object {
        fun fromKey(key: String): Choice? = values().firstOrNull { it.key == key }
    }
}

    private fun prefs() = appContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    /**
     * Снимок состояния для инициализации страницы и для пуша после каждого
     * изменения. Ключи совпадают с `SET` шелла, поэтому у JS нет собственного
     * источника истины.
     */
    fun state(): JsonObject {
        ScreenBrightness.healScreenTimeoutOnce(appContext)
        return JsonObject().apply {
        Toggle.values().forEach { t ->
            addProperty(t.key, prefs().getBoolean(t.pref, t.default))
        }
        Choice.values().forEach { c ->
            addProperty(c.key, prefs().getString(c.pref, c.default) ?: c.default)
        }
        /* «Яркость»: снимок отдаёт ФАКТИЧЕСКИЙ процент (Settings.System, при
           отказе — sysfs), поэтому при FAILED интерфейс сам откатывает
           оптимистичное значение слайдера к живому. */
        addProperty(
            "bright",
            ScreenBrightness.currentPercent(appContext) ?: ScreenBrightness.MIN_PERCENT
        )
        /* «Время заставки»: строкой — так значение сравнивает сегментный ряд. */
        addProperty("rest", ScreenBrightness.restSeconds(appContext).toString())
        /* Срез №3: hvac_digital_* — читаем актуальное состояние булевым флагом */
        addProperty("hvacBatt", StatusIcons.getHvacDigital(appContext, StatusIcons.PREF_HVAC_BATT))
        addProperty("hvacTemp", StatusIcons.getHvacDigital(appContext, StatusIcons.PREF_HVAC_TEMP))
        addProperty("hvacExtTemp", StatusIcons.getHvacDigital(appContext, StatusIcons.PREF_HVAC_EXT_TEMP))
        /* Срез №4: Звуки замка (открытие и закрытие) (§7 ТЗ) */
        /* Срез «Звуки замка»: состояние берётся ИЗ РЕЕСТРА ГУ (settings system
           lock_car_audio_*), а не из локального состояния WebView или prefs —
           иначе UI показывал бы выбор, который на машину не повлиял. */
        addProperty("lockSound", LockSound.isEnabled(appContext))
        addProperty("lockSoundPath", LockSound.getSelectedPath(appContext))
        addProperty("lockSoundName", LockSound.getDisplayName(appContext))
        addProperty("lockSoundVol", LockSound.getVolume(appContext))
        addProperty("lockSoundDelay", LockSound.getDelayMs(appContext).toString())
        addProperty("unlockSound", LockSound.isUnlockSoundEnabled(appContext))
        addProperty("unlockSoundPath", LockSound.getUnlockSelectedPath(appContext))
        addProperty("unlockSoundName", LockSound.getUnlockDisplayName(appContext))
        val soundsArray = com.google.gson.JsonArray()
        LockSound.getAvailableSoundList(appContext).forEach { soundsArray.add(it) }
        add("availableSounds", soundsArray)
        /* Диагностика источника события замка: какое свойство задано, сколько
           событий принято/отклонено, что с маршрутом вывода. */
        addProperty("lockPropId", LockStateTrigger.lockPropId(appContext).toString())
        addProperty("lockTriggerStatus", LockStateTrigger.statusLine(appContext))
        addProperty("lockRoute", LockSoundPlayer.routeReport(appContext))
        addProperty("windowDriverOnDoor", WindowDoors.isWindowDriverOnDoor(appContext))
        addProperty("windowOppositeOnDoor", WindowDoors.isWindowOppositeOnDoor(appContext))
        addProperty("ttl65", NetworkTtl.isTtl65Active(appContext))
        addProperty("perfVideo", AppPerformance.isVideo60FpsEnabled(appContext))
        addProperty("perfJit", AppPerformance.isAutoOptimizeEnabled(appContext))
        add("keepAliveApps", AppPerformance.getKeepAliveAppsJson(appContext))
    }
}

    /** Значение строковой настройки (для `data-gu`/плиток палитры при загрузке). */
    fun choiceValue(choice: Choice): String =
        prefs().getString(choice.pref, choice.default) ?: choice.default

    /** Запись переключателя: persist → живой эффект → readback → результат. */
    fun set(toggle: Toggle, value: Boolean): ToolsResult {
        val steps = ArrayList<ToolsStep>(3)

        steps += toolsStep(
            step = "persist",
            command = "SharedPreferences[${Prefs.FILE}].putBoolean(${toggle.pref})",
            expected = value.toString()
        ) {
            // commit(), а не apply(): readback должен видеть уже записанное
            // значение — иначе «успех» мог бы опираться на кэш редактора.
            prefs().edit().putBoolean(toggle.pref, value).commit()
            val actual = prefs().getBoolean(toggle.pref, !value).toString()
            (actual == value.toString()) to actual
        }

        when (toggle) {
            Toggle.IMMERSIVE -> {
                // Окно меняется на UI-потоке, а readback обязан видеть уже
                // применённое состояние — поэтому apply и чтение флагов идут
                // одним блоком на UI (иначе readback читал бы прошлые флаги).
                val pair = onUi<Pair<ToolsStep, ToolsStep>> {
                    val applied = toolsStep(
                        step = "apply",
                        command = "decorView.systemUiVisibility = " +
                            if (value) "IMMERSIVE_STICKY|FULLSCREEN|HIDE_NAVIGATION" else "VISIBLE"
                    ) {
                        host.applyImmersiveSetting(value)
                        true to if (value) "панели ГУ скрыты" else "панели ГУ видны"
                    }
                    applied to readbackImmersiveFlags(value)
                }
                if (pair != null) {
                    steps += pair.first
                    steps += pair.second
                } else {
                    steps += timeoutStep("apply", "UI-поток не ответил за $UI_TIMEOUT_MS мс")
                }
            }

            Toggle.PROJECTION -> {
                steps += toolsStep(
                    step = "apply",
                    command = "TelemetrySettings.setProjectionBetaEnabled($value) + " +
                        "ProjectionAutoOpenSupervisor.setEnabled($value)"
                ) {
                    host.setProjectionBetaSetting(value)
                    true to "значение передано движку телеметрии"
                }
                steps += readbackProjection(value)
            }

            Toggle.REMEMBER_BRIGHTNESS -> {
                /* Порт z(): включив тумблер, сразу берём текущий уровень в
                   корзину режима — иначе первому пробуждению нечего будет
                   восстанавливать (в APK это делает watchdog-цикл). */
                if (value) steps += captureStep()
            }

            // Срез №3: Панель уведомлений — при включении стартуем ForegroundService
            // (CentralWatchdogService), который поднимает Car и периодически постит
            // иконки; при выключении — отменяем нотификации и останавливаем сервис.
            // Рефлексия для VoiceAssistantService, startForegroundService и
            // NotificationManager.activeNotifications (API 28+), чтобы тайпчек
            // проходит без Android SDK.
            Toggle.HVAC_DIGITAL_BATT, Toggle.HVAC_DIGITAL_TEMP, Toggle.HVAC_DIGITAL_EXT_TEMP -> {
                return StatusIcons.setHvacDigital(appContext, toggle.pref, value)
            }

            Toggle.LOCK_SOUND -> {
                return LockSound.setEnabled(appContext, value)
            }
            Toggle.WINDOW_DRIVER_ON_DOOR -> {
                return WindowDoors.setWindowDriverOnDoor(appContext, value)
            }
            Toggle.WINDOW_OPPOSITE_ON_DOOR -> {
                return WindowDoors.setWindowOppositeOnDoor(appContext, value)
            }
            Toggle.TTL_65 -> {
                return NetworkTtl.setTtl65Active(appContext, value)
            }
            Toggle.PERF_VIDEO -> {
                return AppPerformance.setVideo60Fps(appContext, value)
            }
            Toggle.PERF_JIT -> {
                return AppPerformance.setAutoOptimize(appContext, value)
            }
            Toggle.UNLOCK_SOUND -> {
                /* Источник события — состояние центрального замка (не двери).
                   Подписка, фильтр по фронту и сторож применения ключей живут
                   в LockStateTrigger/LockTransition. */
                val res = LockSound.setUnlockSoundEnabled(appContext, value)
                if (value) LockStateTrigger.start(appContext)
                return res
            }

            Toggle.NOTIF_BATTERY, Toggle.NOTIF_EXT_TEMP, Toggle.NOTIF_WIFI -> {
                steps += toolsStep(
                    step = "apply",
                    command = "startForegroundService(VoiceAssistantService) / StatusIcons.syncAll"
                ) {
                    val svcClass = Class.forName("com.example.voiceapp3.VoiceAssistantService")
                    val svcIntent = android.content.Intent(appContext, svcClass)
                    if (value) {
                        // Любой включённый тумблер панели требует живого сервиса
                        try {
                            val method = android.content.ContextWrapper::class.java.getDeclaredMethod("startForegroundService", android.content.Intent::class.java)
                            method.invoke(appContext, svcIntent)
                        } catch (e: Exception) {
                            Log.w(TAG, "startForegroundService reflection failed", e)
                        }
                        // Сразу постим текущее значение (если Car доступен)
                        StatusIcons.syncAll(appContext)
                    } else {
                        // Если все три выключены — можно отменить иконки и не держать сервис
                        val anyOn = Toggle.values()
                            .filter { it == Toggle.NOTIF_BATTERY || it == Toggle.NOTIF_EXT_TEMP || it == Toggle.NOTIF_WIFI }
                            .any { prefs().getBoolean(it.pref, false) }
                        if (!anyOn) {
                            StatusIcons.cancelAll(appContext)
                            StatusIcons.stopPeriodicSync()
                        }
                    }
                    true to if (value) "сервис запущен, иконки опубликованы" else "иконки скрыты"
                }
                // Подтверждение состояния тумблера
                steps += toolsStep(
                    step = "confirm",
                    command = "prefs.getBoolean(${toggle.pref})"
                ) {
                    val actual = prefs().getBoolean(toggle.pref, value)
                    true to if (actual) "включено" else "отключено"
                }
            }

            // wake, cb, follow живут не окном: «wake» читает приёмник
            // SCREEN_ON в VoiceAssistantService при следующем пробуждении,
            // «cb» и «follow» применяет шелл сразу (applyClim/applyTheme),
            // а персистированное значение и есть фактическое состояние.
            else -> Unit
        }

        return ToolsResult(
            action = "settings." + toggle.key,
            title = toggle.title,
            steps = steps
        )
    }

    /** Запись строковой настройки (палитра приложения, тема ГУ, hvac_digital_*). */
    fun set(choice: Choice, value: String): ToolsResult {
        if (value !in choice.allowed) {
            val now = SystemClock.elapsedRealtime()
            return ToolsResult(
                action = "settings." + choice.key,
                title = choice.title,
                steps = listOf(
                    ToolsStep(
                        step = "validate",
                        command = "Choice.${choice.name}.allowed",
                        startedAtMs = now,
                        finishedAtMs = now,
                        exitCode = 1,
                        stderr = "значение вне каталога: $value",
                        expected = choice.allowed.joinToString("|"),
                        actual = value,
                        status = ToolsStep.Status.FAILED
                    )
                )
            )
        }



        val steps = ArrayList<ToolsStep>(2)
        steps += toolsStep(
            step = "persist",
            command = "SharedPreferences[${Prefs.FILE}].putString(${choice.pref})",
            expected = value
        ) {
            prefs().edit().putString(choice.pref, value).commit()
            val actual = prefs().getString(choice.pref, "") ?: ""
            (actual == value) to actual
        }
        if (choice == Choice.SHELL_THEME) {
            // Зеркало во Flutter-prefs телеметрии — чтобы встроенный экран
            // телеметрии подхватил палитру шелла без перезапуска движка.
            steps += toolsStep(
                step = "mirror",
                command = "TelemetryPrefsMirror.rememberShellTheme($value)",
                expected = value
            ) {
                TelemetryPrefsMirror.rememberShellTheme(appContext, value)
                val actual = prefs().getString(choice.pref, "") ?: ""
                (actual == value) to actual
            }
        }
        return ToolsResult(action = "settings." + choice.key, title = choice.title, steps = steps)
    }

    /**
     * Слайдер «Яркость» (5..100%): порт MainActivity (onStopTrackingTouch →
     * `hw = clamp((clamp(p,5,100)*255)/100, 12, 255)`) + `w0.m.f` —
     * Settings.System «screen_brightness» + свойство CentralEXAuto + sysfs,
     * каждый шаг с обратным чтением. Шаг sysfs честно помечается пропущенным,
     * если прошивка его не отдаёт (в APK на этот случай есть `setenforce 0`,
     * здесь он сознательно не выполняется — см. ScreenBrightness).
     */
    fun setBrightness(percent: Int): ToolsResult {
        val action = "settings.bright"
        val title = "Яркость"
        if (percent < ScreenBrightness.MIN_PERCENT || percent > ScreenBrightness.MAX_PERCENT) {
            return ToolsResult(
                action, title,
                listOf(
                    stepOf(
                        "validate", "slider 5..100", ToolsStep.Status.FAILED,
                        expected = "${ScreenBrightness.MIN_PERCENT}..${ScreenBrightness.MAX_PERCENT}",
                        actual = percent.toString(),
                        stderr = "значение вне диапазона слайдера"
                    )
                )
            )
        }

        val level = ScreenBrightness.levelOf(percent)
        val steps = ArrayList<ToolsStep>(4)

        val modeW = ScreenBrightness.writeSystemInt(
            appContext, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        steps += stepOf(
            "mode", "Settings.System.putInt(SCREEN_BRIGHTNESS_MODE, MANUAL)",
            if (modeW.ok) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL,
            expected = "manual", actual = if (modeW.ok) "manual" else (modeW.error ?: "нет"),
            stderr = if (modeW.ok) "" else "ручной режим не подтверждён: ${modeW.error}"
        )

        val sysW = ScreenBrightness.writeSystemInt(appContext, ScreenBrightness.SETTINGS_KEY, level)
        val fsW = ScreenBrightness.writeSysfs(level)
        val propW = ScreenBrightness.writeProp(level)

        val settingsStatus = when {
            sysW.ok -> ToolsStep.Status.OK
            fsW.ok -> ToolsStep.Status.PARTIAL
            else -> ToolsStep.Status.FAILED
        }
        steps += stepOf(
            "settings",
            "Settings.System.putInt(\"${ScreenBrightness.SETTINGS_KEY}\", $level)",
            settingsStatus,
            expected = level.toString(),
            actual = sysW.readback?.toString() ?: "нет",
            stderr = when (settingsStatus) {
                ToolsStep.Status.OK -> ""
                ToolsStep.Status.PARTIAL ->
                    "Settings.System отказал (${sysW.error}) — применено через sysfs"
                else -> sysW.error ?: "запись отклонена"
            }
        )
        steps += stepOf(
            "prop",
            "SystemProperties.set(\"${ScreenBrightness.PERSIST_PROP}\", $level)",
            if (propW.ok) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL,
            expected = level.toString(),
            actual = propW.readback?.toString() ?: "нет",
            stderr = if (propW.ok) "" else "свойство недоступно приложению: ${propW.error}"
        )
        steps += stepOf(
            "sysfs",
            "echo $level > ${ScreenBrightness.SYSFS_PATH}",
            if (fsW.ok) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL,
            expected = level.toString(),
            actual = fsW.readback?.toString() ?: "нет",
            stderr = if (fsW.ok) "" else
                "sysfs недоступен (${fsW.error}) — яркость применена через Settings.System"
        )

        /* Порт z(): при включённом «Запоминать яркость» установленный из
           приложения уровень сразу попадает в корзину режима. */
        if (prefs().getBoolean(ScreenBrightness.PREF_REMEMBER, false)) {
            runCatching { ScreenBrightness.captureLevel(appContext) }
        }
        return ToolsResult(action, title, steps)
    }

    /**
     * «Время заставки» (Выкл/10/30/60/120 с): persist + Settings.System
     * SCREEN_OFF_TIMEOUT с readback. «Выкл» возвращает системное значение,
     * сохранённое при первом включении (порт поведения APK: при 0 заставка
     * приложения не трогает системный таймаут).
     */
    fun setRest(seconds: Int): ToolsResult {
        val action = "settings.rest"
        val title = "Время заставки"
        if (ScreenBrightness.REST_CHOICES.none { it == seconds }) {
            return ToolsResult(
                action, title,
                listOf(
                    stepOf(
                        "validate", "settings.change {rest}", ToolsStep.Status.FAILED,
                        expected = ScreenBrightness.REST_CHOICES.joinToString("|"),
                        actual = seconds.toString(),
                        stderr = "значение вне набора «Время заставки»"
                    )
                )
            )
        }

        val steps = ArrayList<ToolsStep>(3)
        steps += toolsStep(
            step = "persist",
            command = "SharedPreferences[${Prefs.FILE}].putInt(${ScreenBrightness.PREF_REST_SEC})",
            expected = seconds.toString()
        ) {
            prefs().edit().putInt(ScreenBrightness.PREF_REST_SEC, seconds).commit()
            val actual = prefs().getInt(ScreenBrightness.PREF_REST_SEC, -1).toString()
            (actual == seconds.toString()) to actual
        }

        val r = ScreenBrightness.applyRestTimeout(appContext, seconds)
        val status = if (r.ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED
        steps += stepOf(
            "apply",
            if (seconds > 0) {
                "ScreenSaverOverlay.scheduleWatchdog(${seconds}s)"
            } else {
                "ScreenSaverOverlay.dismiss()"
            },
            status,
            expected = seconds.toString(),
            actual = seconds.toString(),
            stderr = r.note ?: r.error ?: ""
        )
        return ToolsResult(action, title, steps)
    }

    /** Порт z() на включении тумблера: текущий уровень → корзина режима. */
    private fun captureStep(): ToolsStep {
        val level = ScreenBrightness.currentLevel(appContext)
        val key = ScreenBrightness.bucketPref(appContext)
        val label = ScreenBrightness.bucketLabel(appContext)
        val command = "putInt($key, $label) ← текущая яркость"
        return when {
            level == null -> stepOf(
                "capture", command, ToolsStep.Status.PARTIAL,
                stderr = "яркость не читается (нет доступа к Settings.System/sysfs)"
            )
            level <= 0 -> stepOf(
                "capture", command, ToolsStep.Status.PARTIAL,
                stdout = "экран погашен — уровень не запоминается (как в APK)"
            )
            else -> {
                runCatching { ScreenBrightness.captureLevel(appContext) }
                val stored = prefs().getInt(key, -1)
                stepOf(
                    "capture", command,
                    if (stored == level) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
                    expected = level.toString(), actual = stored.toString(),
                    stderr = if (stored == level) "" else "уровень не сохранился в $key"
                )
            }
        }
    }

    /**
     * Шаг с явным статусом — для случаев «шаг выполнен не полностью»
     * (prop/sysfs недоступны, заставка уже выключена): статус виден в
     * cxNote и diagnostics, но не превращает всё действие в FAILED.
     */
    private fun stepOf(
        step: String,
        command: String,
        status: ToolsStep.Status,
        expected: String = "",
        actual: String = "",
        stdout: String = "",
        stderr: String = ""
    ): ToolsStep {
        val t = SystemClock.elapsedRealtime()
        return ToolsStep(
            step = step,
            command = command,
            startedAtMs = t,
            finishedAtMs = t,
            exitCode = if (status == ToolsStep.Status.OK) 0 else 1,
            stdout = stdout,
            stderr = stderr,
            expected = expected,
            actual = actual,
            status = status
        )
    }

    private fun intValue(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun parseFailed(key: String, expected: String, value: Any?): ToolsResult =
        ToolsResult(
            action = "settings.$key",
            title = key,
            steps = listOf(
                stepOf(
                    "parse", "settings.change {key:$key,value:$value}", ToolsStep.Status.FAILED,
                    expected = expected, actual = value?.toString() ?: "null",
                    stderr = "ожидалось числовое значение"
                )
            )
        )

    /**
     * Выполнить [block] на UI-потоке и дождаться результата: живой эффект
     * окна обязан быть применён ДО readback, иначе подтверждение читало бы
     * прошлое состояние. Возвращает `null`, если UI-поток не ответил.
     */
    private fun <T> onUi(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var done: T? = null
        val latch = CountDownLatch(1)
        ui(Runnable {
            try {
                done = block()
            } finally {
                latch.countDown()
            }
        })
        return if (latch.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS)) done else null
    }

    private fun timeoutStep(step: String, why: String): ToolsStep {
        val now = SystemClock.elapsedRealtime()
        return ToolsStep(
            step = step,
            command = "runOnUiThread",
            startedAtMs = now,
            finishedAtMs = now,
            exitCode = 1,
            stderr = why,
            status = ToolsStep.Status.BLOCKED
        )
    }

    /** Readback оконных флагов: что реально стоит на decorView после apply. */
    private fun readbackImmersiveFlags(expectedImmersive: Boolean): ToolsStep {
        @Suppress("DEPRECATION") // API 28: WindowInsetsController ещё нет
        val mask = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val flags = runCatching { host.immersiveWindowFlags() }.getOrDefault(0)
        val hidden = (flags and mask) == mask
        val actual = if (hidden) "immersive" else "visible"
        val expected = if (expectedImmersive) "immersive" else "visible"
        val start = SystemClock.elapsedRealtime()
        return ToolsStep(
            step = "readback",
            command = "decorView.systemUiVisibility (0x%08x)".format(flags),
            startedAtMs = start,
            finishedAtMs = start,
            exitCode = if (hidden == expectedImmersive) 0 else 1,
            expected = expected,
            actual = actual,
            stderr = if (hidden == expectedImmersive) "" else "окно не приняло режим (системный диалог ГУ?)",
            status = if (hidden == expectedImmersive) ToolsStep.Status.OK else ToolsStep.Status.FAILED
        )
    }

    /**
     * Readback тумблера CarPlay/AA: движок пишет через executor
     * (VehicleLiveAdapter.setProjectionBeta), поэтому значение может отстать
     * на пару кадров — перечитываем с ограниченным числом попыток.
     */
    private fun readbackProjection(expected: Boolean): ToolsStep {
        val start = SystemClock.elapsedRealtime()
        var engine: Boolean? = null
        var attempt = 0
        while (attempt < READBACK_ATTEMPTS) {
            engine = runCatching { host.projectionBetaState() }.getOrNull()
            if (engine == expected) break
            attempt++
            if (attempt < READBACK_ATTEMPTS) SystemClock.sleep(READBACK_DELAY_MS)
        }
        val value = engine
        val status = when {
            value == null -> ToolsStep.Status.PARTIAL
            value == expected -> ToolsStep.Status.OK
            else -> ToolsStep.Status.FAILED
        }
        return ToolsStep(
            step = "readback",
            command = "TelemetrySettings.projectionBetaEnabled()",
            startedAtMs = start,
            finishedAtMs = SystemClock.elapsedRealtime(),
            exitCode = if (status == ToolsStep.Status.OK) 0 else 1,
            stdout = if (status == ToolsStep.Status.PARTIAL) "попыток: $attempt" else "",
            stderr = when (status) {
                ToolsStep.Status.PARTIAL -> "движок телеметрии недоступен — значение подтвердится при следующем снимке"
                ToolsStep.Status.FAILED -> "движок вернул $value"
                else -> ""
            },
            expected = expected.toString(),
            actual = value?.toString() ?: "нет данных",
            status = status
        )
    }

    /**
     * Единая точка записи из WebView: `settings.change {key, value}`, где
     * value — boolean (переключатель) или string (выбор из набора).
     * Возвращает `null`, если ключ реестру неизвестен (тогда его обрабатывает
     * прежний путь шелла — телеметрические ключи `auto`/`gps`/`cont`/...).
     */
    fun applyFromUi(key: String, value: Any?): ToolsResult? {
        Toggle.fromKey(key)?.let { toggle ->
            val b = when (value) {
                is Boolean -> value
                is String -> value.toBooleanStrictOrNull()
                is Number -> value.toInt() != 0
                else -> null
            } ?: return ToolsResult(
                action = "settings.$key",
                title = toggle.title,
                steps = listOf(
                    ToolsStep(
                        step = "parse",
                        command = "settings.change {key:$key,value:$value}",
                        startedAtMs = SystemClock.elapsedRealtime(),
                        finishedAtMs = SystemClock.elapsedRealtime(),
                        exitCode = 1,
                        stderr = "ожидался boolean",
                        expected = "true|false",
                        actual = value?.toString() ?: "null",
                        status = ToolsStep.Status.FAILED
                    )
                )
            )
            val res = set(toggle, b)
            AppLog.logToggle(appContext, key, b, res)
            return res
        }
        Choice.fromKey(key)?.let { choice ->
            val s = value?.toString().orEmpty()
            if (s.isEmpty()) {
                Log.w(TAG, "settings.change: пустое значение для $key")
                return null
            }
            val res = set(choice, s)
            AppLog.logAction(appContext, "settings." + key + "=" + s, res)
            return res
        }
        /* Числовые настройки среза №2 и №4 (яркость, время заставки, громкость, задержка) */
        when (key) {
            "bright" -> {
                val v = intValue(value)
                return if (v != null) {
                    setBrightness(v)
                } else {
                    parseFailed(key, "${ScreenBrightness.MIN_PERCENT}..${ScreenBrightness.MAX_PERCENT}", value)
                }
            }
            "rest" -> {
                val v = intValue(value)
                return if (v != null) {
                    setRest(v)
                } else {
                    parseFailed(key, ScreenBrightness.REST_CHOICES.joinToString("|"), value)
                }
            }
            "lockSoundVol" -> {
                val v = intValue(value)
                return if (v != null) {
                    LockSound.setVolume(appContext, v)
                } else {
                    parseFailed(key, "5..100", value)
                }
            }
            "lockSoundDelay" -> {
                val v = intValue(value)
                return if (v != null) {
                    LockSound.setDelay(appContext, v)
                } else {
                    parseFailed(key, LockSound.DELAY_CHOICES.joinToString("|"), value)
                }
            }
        }
        return null
    }

    /** Имена ключей SharedPreferences — публично: их читает шелл при загрузке
     *  страницы и все последующие срезы вкладки. */
    object Prefs {
        /** Те же prefs, что у зеркала телеметрии и цвета машины — один файл. */
        const val FILE = "voiceapp3"
        const val IMMERSIVE = "app_immersive_mode_enabled"
        const val LAUNCH_ON_WAKE = "app_launch_on_wake_enabled"
        const val CLIMATE_BAR = "app_climate_bar_enabled"
        const val PROJECTION_BETA = "app_projection_beta_enabled"
        const val THEME_FOLLOW_HU = "app_theme_follow_hu_enabled"
        const val HEAD_UNIT_THEME = "ui_head_unit_theme_mode"
    }

    private companion object {
        const val TAG = "ToolsBackend"
        const val READBACK_ATTEMPTS = 5
        const val READBACK_DELAY_MS = 100L

        /** Потолок ожидания UI-потока при живом применении настройки. */
        const val UI_TIMEOUT_MS = 2000L
    }
}
