package com.example.voiceapp3

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.example.voiceapp3.car.CarAudioPlayer
import com.example.voiceapp3.car.MediaCenterBridge
import com.example.voiceapp3.car.VehiclePropertyDumper
import com.example.voiceapp3.car.VehiclePropertyHelper
import com.example.voiceapp3.handlers.AcControlHandler
import com.example.voiceapp3.media.MediaSessionCoordinator
import com.example.voiceapp3.tools.AppLog
import com.example.voiceapp3.tools.AppPerformance
import com.example.voiceapp3.tools.BgWorker
import com.example.voiceapp3.tools.HuNightMode
import com.example.voiceapp3.tools.LockSound
import com.example.voiceapp3.tools.NetworkTtl
import com.example.voiceapp3.tools.ScreenBrightness
import com.example.voiceapp3.tools.StatusIcons
import com.example.voiceapp3.tools.StatusOverlay
import com.example.voiceapp3.tools.WindowDoors
import com.example.voiceapp3.ui.UiBridge
import org.vosk.Model
import org.vosk.android.StorageService

/**
 * Голосовой ассистент.
 *
 * Архитектура звука: MicPump — единственный владелец AudioRecord.
 * Он открывает вход один раз и меняет только потребителя:
 *   WakeWordDetector -> (услышал «привет джили») -> CommandRecognizer -> обратно.
 *
 * ЧТО ИСПРАВЛЕНО В ЭТОЙ ВЕРСИИ
 *
 * 1. ГЛАВНОЕ. toggleRecognition() требовал micPump.isRunning == true,
 *    но при выключенной голосовой активации насос намеренно не запущен.
 *    Кнопка на руле и кнопка в интерфейсе выдавали «Голосовая модель ещё
 *    не загружена», хотя модель была загружена. Теперь кнопка сама
 *    поднимает микрофон через ensurePumpRunning().
 *
 * 2. Сообщения об ошибке разделены: «модель ещё грузится» и «нет доступа
 *    к микрофону» — это разные причины, лечатся по-разному.
 *
 * 3. DEBUG_ACCEPT_UNVERIFIED = false. В машине свойства реальные:
 *    если обработчик вернул false, значит команда действительно не прошла,
 *    и врать пользователю успешным звуком нельзя.
 *
 * 4. cleanup(): каждый шаг в своём runCatching. Раньше падение на
 *    unregisterReceiver отменяло всё остальное — вход оставался занятым.
 *    join() без таймаута заменён на join(500).
 *
 * 5. Убраны поля от старой архитектуры: audioRecord, recognizer,
 *    recognitionThread, shouldContinueRecording, isPaused, BUFFER_SIZE.
 *    Вместе с ними — дубль поля WakeWordDetector с заглавной буквы,
 *    который совпадал с именем класса и никогда не использовался.
 *
 * 6. carAudioPlayer инициализируется в фоновом потоке. Обращение к нему
 *    до этого момента роняло сервис с UninitializedPropertyAccessException.
 *    Везде проверка isInitialized.
 */
class VoiceAssistantService : Service() {

    companion object {
        const val ACTION_SERVICE_READY = "com.example.voiceapp3.SERVICE_READY"

        /** Управление из MainActivity и из adb. */
        const val ACTION_WAKE_WORD = "com.example.voiceapp3.WAKE_WORD"
        const val EXTRA_ENABLED = "enabled"

        /** Сервис сообщает наружу, что состояние изменилось. */
        const val ACTION_WAKE_WORD_STATE = "com.example.voiceapp3.WAKE_WORD_STATE"

        /** Хаб: режим / рекуп / дамп / пересоздать микрофон. */
        const val ACTION_HUB = "com.example.voiceapp3.HUB"
        const val EXTRA_HUB = "hub"
        const val EXTRA_MODE = "mode"
        /** Boot-ветка запуска сервиса (BOOT_COMPLETED): вернуть сохранённую
         *  яркость — порт w0.m.w с события r1 case 4. */
        const val EXTRA_BOOT_RESTORE = "boot_restore"
        const val HUB_DRIVE = "drive"
        const val HUB_RECUP = "recup"
        const val HUB_DUMP = "dump"
        const val HUB_RESTART_MIC = "restart_mic"

        /* Stage QA (быстрые действия): панель «Быстрый доступ» из UI.
           HUB_INTENT — универсальная команда: intent + текст + entities,
           проходит тот же IntentHandlerRegistry, что и голос. */
        const val HUB_LISTEN = "listen"              // кнопка микрофона UI: прослушивание
        const val HUB_AC_DIRECTION = "ac_direction"  // направление обдува (кнопки)
        const val HUB_INTENT = "intent"
        /** Тап по фразе в каталоге вкладки «Голос»: та же обработка, что распознанная речь. */
        const val HUB_PHRASE = "phrase"

        /* Навигация (navigator-app-7): кнопка 🎙 в поиске — это ГОЛОСОВОЙ
           ВВОД АДРЕСА в поле поиска (по явному решению пользователя 2026-10-05),
           а НЕ вызов ассистента. Отдельная короткая диктовка той же Vosk-
           моделью: распознанный текст уходит broadcast'ом, экран поиска
           подставляет его в поле. Отмена: тот же hub с EXTRA_MODE="cancel".
           Результаты: ACTION_NAV_DICTATION { EXTRA_STATE: start|partial|
           final|error|cancel, EXTRA_TEXT — текст (для error — причина) }.
           Командная логика ассистента этим пайплайном НЕ затрагивается. */
        const val HUB_DICTATE = "dictate"
        const val ACTION_NAV_DICTATION = "com.example.voiceapp3.NAV_DICTATION"
        const val EXTRA_STATE = "dictation_state"
        const val EXTRA_INTENT = "intent_name"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ACTION = "action"
        const val EXTRA_VALUE = "value"
        const val EXTRA_DIRECTION = "direction"

        private const val TAG = "CENTRAL_VoiceAssistantService"
        private const val MAIN_CHANNEL_ID = "voice_assistant_channel"
        private const val PERMISSION_CHANNEL_ID = "permission_channel"
        private const val PERMISSION_NOTIFICATION_ID = 1001
        private const val VOICE_MODEL_DIR = "voice-model"
        private const val VOICE_MODEL_SOURCE = "model-ru"
        private const val ACTION_ECARX_KEY = "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT"
        private const val RECOGNITION_TIMEOUT = 14000L
        private const val PREF_WAKE_WORD = "wake_word_enabled"

        /**
         * Считать команду выполненной, даже если обработчик вернул false.
         *
         * На эмуляторе вендорных свойств нет и false означает лишь
         * отсутствие железа. В машине false означает настоящую неудачу —
         * поэтому здесь строго false.
         */
        private const val DEBUG_ACCEPT_UNVERIFIED = false

        private var isServiceInitialized = false
        fun isServiceReady(): Boolean = isServiceInitialized
    }

    // ------------------------------------------------------------------
    //  Состояние
    // ------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private val uiHandler = Handler(Looper.getMainLooper())
    private val commandHandler = Handler(Looper.getMainLooper())
    private var pendingCommand: Runnable? = null

    private val timeoutRunnable: Runnable = Runnable {
        Log.w(TAG, "Recognition timeout reached")
        stopRecognition()
    }

    private lateinit var localBroadcastManager: LocalBroadcastManager
    private lateinit var vehiclePropertyHelper: VehiclePropertyHelper
    private lateinit var carAudioPlayer: CarAudioPlayer
    private lateinit var audioManager: AudioManager
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var audioFocusRequest: AudioFocusRequest
    private lateinit var mediaCenterBridge: MediaCenterBridge

    private var intentModel: ActionModel? = null

    // Звуковой конвейер
    private var micPump: MicPump? = null
    private var wakeWordDetector: WakeWordDetector? = null
    private var commandRecognizer: CommandRecognizer? = null

    /** Нав-диктовка адреса (navigator-app-7): своя короткая сессия Vosk,
     *  первичный признак занятости конвейера диктовкой. */
    private var navDictation: CommandRecognizer? = null
    @Volatile private var dictating = false

    /**
     * Stage CPv2: гейт wake-word на время projection-сессии (CarPlay/AA).
     * Причина мгновенной паузы музыки была здесь: музыка из CarPlay попадала
     * в микрофон, Vosk ложно срабатывала, а greetThenListen() забирал
     * Exclusive audio focus — OEM CarPlay ставил воспроизведение на паузу.
     */
    private var projectionGate: com.example.voiceapp3.projection.ProjectionVoiceGate? = null
    private var model: Model? = null

    private var isRecognizing = false

    /** Команду поняли (интент не unknown). «Готово» говорим только если ещё и выполнили. */
    @Volatile private var lastCommandUnderstood = false

    /** Сказали «Привет», команду ещё не слушаем — ждём конца TTS. */
    @Volatile private var greetingPending = false

    /**
     * Stage VO3: сессия голоса ведётся В ИНТЕРФЕЙСЕ (пользователь на вкладке
     * «Голос» — анимирует сам микрофон, плашка не нужна). false → OverlayActivity.
     */
    @Volatile private var voiceInApp = false

    // Оверлей
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    /**
     * true — системного оверлея нет, показываем запасной экран
     * OverlayActivity. Разрешений он не требует.
     */
    private var usingFallbackOverlay = false

    private var wakeWordSwitchReceiver: BroadcastReceiver? = null
    private var hubReceiver: BroadcastReceiver? = null

    private val prefs by lazy {
        getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
    }

    private var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(PREF_WAKE_WORD, true)
        set(value) = prefs.edit().putBoolean(PREF_WAKE_WORD, value).apply()

    private val intentRegistry: IntentHandlerRegistry by lazy {
        IntentHandlerRegistry(vehiclePropertyHelper, carAudioPlayer, applicationContext)
    }

    // ------------------------------------------------------------------
    //  Жизненный цикл
    // ------------------------------------------------------------------

    override fun onCreate() {
        System.setProperty("java.regex.implementation", "com.ibm.icu.impl.regex.RE2Impl")
        System.setProperty("java.util.regex.Pattern.impl", "com.ibm.icu.impl.regex.PatternImpl")

        localBroadcastManager = LocalBroadcastManager.getInstance(this)

        createNotificationChannel()
        OverlayPermission.ensure(this)
        checkAndRequestPermissions()
        startForegroundService()
        registerKeyReceiver()
        registerWakeWordSwitchReceiver()
        registerHubReceiver()
        registerScreenWakeReceiver()
        startBrightnessSync()
        startProjectionGate()
        handler.postDelayed(micWatchdog, 8_000)
        Log.i(TAG, "Service creating")

        mediaCenterBridge = MediaCenterBridge(applicationContext, this)
        mediaCenterBridge.start()

        val sessionListener = MediaSessionCoordinator(this, mediaCenterBridge)
        sessionListener.cleanExpiredCache()
        mediaCenterBridge.setMediaSessionCoordinator(sessionListener)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        vehiclePropertyHelper = VehiclePropertyHelper(applicationContext)

        initMediaPlayer()
        initAudioFocusRequest()
        preloadSounds()

        Thread({
            try {
                initializeInBackground()
                isServiceInitialized = true
                Log.i(TAG, "Service fully initialized in background")
            } catch (t: Throwable) {
                // Throwable: нативные библиотеки бросают Error, а не Exception
                Log.e(TAG, "Error initializing service", t)
                isServiceInitialized = false
            }
        }, "ServiceInit").start()

        super.onCreate()
    }

    private fun initializeInBackground() {
        carAudioPlayer = CarAudioPlayer(applicationContext)
        carAudioPlayer.onSpeechStarted = {
            // TTS сам себя будит через микрофон → PROCESS ENDED
            micPump?.setConsumer(null)
        }
        carAudioPlayer.onSpeechFinished = {
            uiHandler.post {
                if (greetingPending) {
                    greetingPending = false
                    handler.removeCallbacks(greetingFallback)
                    // Не beginListening сразу: колонки ещё отдают «Привет».
                    afterEcho { beginListening() }
                    return@post
                }
                if (!isRecognizing && wakeWordEnabled) {
                    afterEcho {
                        if (!isRecognizing && wakeWordEnabled) {
                            micPump?.setConsumer(wakeWordDetector)
                        }
                    }
                }
            }
        }
        carAudioPlayer.initialize()
        Log.i(TAG, "TTS ready=${carAudioPlayer.isReady}")
        initModels()

        sendServiceReadyBroadcast()
        isServiceInitialized = true
    }

    private fun sendServiceReadyBroadcast() {
        localBroadcastManager.sendBroadcast(Intent(ACTION_SERVICE_READY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        /* Boot-ветка: BootReceiver помечает запуск — возвращаем сохранённую
           яркость (порт w() с boot-события; при обычном старте из приложения
           шаг не выполняется, чтобы не перебивать яркость штатной панели). */
        if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) == true) {
            // P1: bootRestore тяжёлый (iptables/mount/adb) — ВСЕГДА на BgWorker, не на main.
            // Регистрация наблюдателя HuNightMode лёгкая — остаётся на месте.
            val app = this
            BgWorker.post("VoiceAssistant.bootRestore") {
                runCatching { ScreenBrightness.restoreIfEnabled(app) }
                runCatching { StatusIcons.syncAll(app) }
                runCatching { StatusIcons.startPeriodicSync(app) }
                runCatching { NetworkTtl.bootRestore(app) }
                runCatching { LockSound.onBootOrWatchdog(app) }
                runCatching {
                    val prefs = try {
                        app.createDeviceProtectedStorageContext()
                            .getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
                    } catch (_: Throwable) {
                        app.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
                    }
                    val driverOn = prefs.getBoolean(WindowDoors.PREF_WINDOW_DRIVER_ON_DOOR, false)
                    val oppOn = prefs.getBoolean(WindowDoors.PREF_WINDOW_OPPOSITE_ON_DOOR, false)
                    val unlockOn = prefs.getBoolean(LockSound.PREF_UNLOCK_SOUND_ENABLED, false)
                    if (driverOn || oppOn || unlockOn) {
                        WindowDoors.initCarService(app)
                    }
                }
                runCatching { StatusIcons.bootRestoreHvacDigital(app) }
                runCatching {
                    if (AppPerformance.isVideo60FpsEnabled(app)) {
                        AppPerformance.setVideo60Fps(app, true)
                    }
                }
                runCatching { NetworkTtl.registerNetworkReceiver(app) }
                AppLog.i(TAG, "bootRestore complete (BgWorker)")
            }
            HuNightMode.observe(this) {
                BgWorker.post("VoiceAssistant.nightMode") {
                    runCatching { ScreenBrightness.restoreIfEnabled(app) }
                    runCatching { StatusIcons.syncAll(app) }
                }
            }
            // Watchdog-цикл раз в 60 с — постим на BgWorker, не выполняем на main.
            handler.postDelayed(settingsWatchdog, SETTINGS_WATCHDOG_MS)
        }
        // Регистрируем сетевой ресивер всегда (не только при boot-ветке) — чтобы
        // правило TTL подхватывалось и при обычном старте приложения.
        if (intent?.getBooleanExtra(EXTRA_BOOT_RESTORE, false) != true) {
            val app = this
            BgWorker.post("NetworkTtl.registerReceiver") {
                runCatching { NetworkTtl.registerNetworkReceiver(app) }
            }
        }
        return START_STICKY
    }

    /**
     * Периодическое восстановление настроек Инструментов. Если после рестарта
     * процесса или длительной стоянки какой-то флаг остался включённым в prefs,
     * но эффект не применён в системе — переприменяем. Не выдаёт ошибки пользователю.
     */
    private val settingsWatchdog = object : Runnable {
        override fun run() {
            val app = this@VoiceAssistantService
            // P1: тик watchdog'а только планируется с main, сама работа — на BgWorker.
            BgWorker.post("VoiceAssistant.watchdog") {
                runCatching { NetworkTtl.bootRestore(app) }
                runCatching { LockSound.onBootOrWatchdog(app) }
                runCatching { StatusIcons.bootRestoreHvacDigital(app) }
                runCatching { ScreenBrightness.restoreIfEnabled(app) }
                runCatching {
                    if (AppPerformance.isVideo60FpsEnabled(app)) {
                        AppPerformance.setVideo60Fps(app, true)
                    }
                }
            }
            handler.postDelayed(this, SETTINGS_WATCHDOG_MS)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        cleanup()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "MAIN Service Binded")
        return null
    }

    // ------------------------------------------------------------------
    //  Разрешения
    // ------------------------------------------------------------------

    private fun checkAndRequestPermissions(): Boolean {
        val permitted = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!permitted) {
            Log.e(TAG, "НЕТ РАЗРЕШЕНИЯ RECORD_AUDIO — микрофон не откроется. " +
                    "Откройте приложение и разрешите микрофон в диалоге")
            showPermissionNotification()
        }
        return permitted
    }

    private fun showPermissionNotification() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, PERMISSION_CHANNEL_ID)
            .setContentTitle("Нужен доступ к микрофону")
            .setContentText("Нажмите, чтобы разрешить запись звука")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()

        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(PERMISSION_NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------
    //  Приёмники
    // ------------------------------------------------------------------

    private val keyEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_ECARX_KEY) {
                Log.i(TAG, "ECARX key event received")
                toggleRecognition()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerKeyReceiver() {
        val filter = IntentFilter(ACTION_ECARX_KEY).apply {
            priority = 999
            addCategory("android.intent.category.DEFAULT")
        }
        registerReceiver(keyEventReceiver, filter)
        Log.i(TAG, "Registered ECARX key receiver")
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerWakeWordSwitchReceiver() {
        wakeWordSwitchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Без extra — переключаем на противоположное
                val enabled = if (intent.hasExtra(EXTRA_ENABLED)) {
                    intent.getBooleanExtra(EXTRA_ENABLED, true)
                } else {
                    !wakeWordEnabled
                }
                setWakeWordEnabled(enabled)
            }
        }
        registerReceiver(wakeWordSwitchReceiver, IntentFilter(ACTION_WAKE_WORD))
        Log.i(TAG, "Registered wake word switch receiver")
    }

    /**
     * Кнопки хаба (режим, рекуп, дамп) и аварийный перезапуск микрофона.
     * Тумблер распознавания помогает, потому что закрывает и открывает вход —
     * HUB_RESTART_MIC делает то же, не выключая активацию.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    /**
     * Stage SCR: запуск приложения при пробуждении ГУ.
     *
     * ГУ «спит» с погашенным экраном, не перезагружаясь; при пробуждении
     * система рассылает ACTION_SCREEN_ON (а USER_PRESENT — если был keyguard).
     * SCREEN_ON нельзя принять из манифеста — только динамической подпиской,
     * поэтому приёмник живёт в этом сервисе: BootReceiver поднимает его
     * при загрузке ГУ, и с этого момента пробуждения слышны.
     *
     * Если тумблер выключен — событие просто игнорируется (настройка читается
     * в момент события, смена тумблера действует сразу, без перезапуска).
     */
    private var screenWakeReceiver: BroadcastReceiver? = null

    /** Каденция синхронизации яркости (аналог watchdog-цикла APK, ~2.5 c). */
    private val BRIGHTNESS_SYNC_MS = 60_000L
    private val SETTINGS_WATCHDOG_MS = 60_000L

    private fun registerScreenWakeReceiver() {
        if (screenWakeReceiver != null) return
        screenWakeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        launchIfWakeEnabled()
                        /* Stage CXT-2: восстановление яркости после
                           пробуждения — порт w0.m.w: применяется корзина
                           текущего режима (день/ночь). До включённого
                           «Запоминать яркость по фарам» — no-op. */
                        runCatching { ScreenBrightness.restoreIfEnabled(this@VoiceAssistantService) }
                        /* Срез №3: синхронизация статус-иконок (батарея/темп/wifi)
                           при пробуждении — как watchdog-цикл APK (r2 case 10). */
                        runCatching { StatusIcons.syncAll(this@VoiceAssistantService) }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        /* Порт w0.m.z: перед уходом экрана в сон снимаем
                           текущий уровень в корзину режима. */
                        runCatching { ScreenBrightness.captureLevel(this@VoiceAssistantService) }
                    }
                    "com.example.voiceapp3.ACTION_APPLY_STATUS_WIFI" -> {
                        /* Нажатие на иконку Wi‑Fi на панели уведомлений —
                           открываем системные настройки Wi‑Fi поверх всего. */
                        runCatching {
                            val wifiIntent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(wifiIntent)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("com.example.voiceapp3.ACTION_APPLY_STATUS_WIFI")
        }
        runCatching {
            registerReceiver(screenWakeReceiver, filter)
            Log.i(TAG, "Screen wake receiver registered")
        }.onFailure { Log.w(TAG, "Screen wake receiver failed", it) }
    }

    /**
     * Stage CXT-2: фоновая синхронизация яркости раз в минуту — порт
     * watchdog-шага CentralEXAuto (w0.t1 case 17): сменился день/ночь (фары)
     * → применить сохранённую яркость нового режима, иначе — снять текущий
     * уровень в корзину («яркость из штатной панели тоже учитывается»).
     * До включённого тумблера — no-op.
     */
    private val brightnessSync = object : Runnable {
        override fun run() {
            runCatching { ScreenBrightness.syncOnContext(this@VoiceAssistantService) }
            handler.postDelayed(this, BRIGHTNESS_SYNC_MS)
        }
    }

    private fun startBrightnessSync() {
        handler.removeCallbacks(brightnessSync)
        handler.postDelayed(brightnessSync, BRIGHTNESS_SYNC_MS)
    }

    /** Открыть приложение, если тумблер включён и оно не на переднем плане. */
    private fun launchIfWakeEnabled() {
        val enabled = getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
            .getBoolean("app_launch_on_wake_enabled", false)
        if (!enabled) return
        if (MainActivity.isResumed) return
        Log.i(TAG, "HU woke up — opening the app (launch-on-wake)")
        runCatching {
            val i = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(i)
        }.onFailure { Log.w(TAG, "launch-on-wake failed", it) }
    }

    private fun registerHubReceiver() {
        hubReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val kind = intent.getStringExtra(EXTRA_HUB) ?: return
                val mode = intent.getStringExtra(EXTRA_MODE)
                Log.i(TAG, "HUB kind=$kind mode=$mode")
                when (kind) {
                    HUB_RESTART_MIC -> restartMic()
                    /* Stage QA: быстрые действия из UI */
                    HUB_LISTEN -> toggleRecognition()
                    HUB_DICTATE -> {
                        if (mode == "cancel") stopNavDictation("cancel")
                        else startNavDictation()
                    }
                    HUB_AC_DIRECTION -> hubAcDirection(mode ?: return)
                    HUB_PHRASE -> runCatalogPhrase(intent.getStringExtra(EXTRA_TEXT))
                    HUB_INTENT -> {
                        val intentName = intent.getStringExtra(EXTRA_INTENT) ?: return
                        val text = intent.getStringExtra(EXTRA_TEXT) ?: intentName
                        val entities = HashMap<String, Any>()
                        intent.getStringExtra(EXTRA_MODE)?.let { entities["mode"] = it }
                        intent.getStringExtra(EXTRA_ACTION)?.let { entities["action"] = it }
                        val v = intent.getIntExtra(EXTRA_VALUE, -1)
                        if (v >= 0) entities["value"] = v
                        intent.getStringExtra(EXTRA_DIRECTION)?.let { entities["direction"] = it }
                        runHub(intentName, text, entities)
                    }
                    HUB_DUMP -> {
                        if (!::vehiclePropertyHelper.isInitialized) return
                        val file = VehiclePropertyDumper.dump(applicationContext, vehiclePropertyHelper)
                        toast(if (file != null) "Дамп записан" else "Дамп не записался")
                    }
                    HUB_DRIVE -> {
                        val m = mode ?: return
                        runHub("drive_mode", "режим $m", mapOf("mode" to m))
                    }
                    HUB_RECUP -> {
                        val m = mode ?: return
                        runHub("recup_mode", "рекуперация $m", mapOf("mode" to m))
                    }
                }
            }
        }
        registerReceiver(hubReceiver, IntentFilter(ACTION_HUB))
        Log.i(TAG, "Registered hub receiver")
    }

    /**
     * Фраза из каталога вкладки «Голос» (тап). Идёт тем же путём, что распознанная
     * речь: разбор команд, ответ голосом, карточка результата, возврат микрофона.
     * Выполняется на главном потоке — как handleCommandResult из распознавания.
     */
    private fun runCatalogPhrase(raw: String?) {
        val phrase = raw?.trim().orEmpty()
        if (phrase.isEmpty()) return
        if (isRecognizing) {
            Log.i(TAG, "Фраза из каталога пропущена: идёт распознавание")
            toast("Ассистент занят")
            return
        }
        // Карточка результата — в интерфейс, если вкладка «Голос» на экране.
        voiceInApp = UiBridge.companionInstance?.isVoiceTabActive == true
        Log.i(TAG, "Фраза из каталога: '$phrase' (в интерфейсе=$voiceInApp)")
        handleCommandResult(phrase)
    }

    private fun runHub(intentName: String, text: String, entities: Map<String, Any>) {
        try {
            val ok = intentRegistry.handle(
                PredictionResult(
                    text = text,
                    normalizedText = text,
                    intent = intentName,
                    confidence = 1f,
                    entities = entities,
                )
            )
            Log.i(TAG, "HUB $intentName -> $ok")
        } catch (t: Throwable) {
            Log.e(TAG, "HUB $intentName", t)
        }
    }

    /**
     * Stage QA: направление обдува из панели «Быстрый доступ».
     * Биты AOSP HVAC_FAN_DIRECTION (как DIR_FACE/DIR_FLOOR/DIR_DEFROST
     * в ChangeFanSpeedHandler): лицо=1, ноги=2, стекло=4.
     */
    private val hubAcControl by lazy {
        AcControlHandler(vehiclePropertyHelper, applicationContext)
    }

    private fun hubAcDirection(mode: String) {
        val dir = when (mode) {
            "face" -> 1
            "floor" -> 2
            "face_floor" -> 3
            "windshield" -> 4
            "floor_windshield" -> 6
            else -> {
                Log.w(TAG, "HUB ac_direction: неизвестное направление '$mode'")
                return
            }
        }
        runCatching { hubAcControl.setAcDirection(dir) }
            .onFailure { Log.e(TAG, "HUB ac_direction failed", it) }
    }

    private fun restartMic() {
        uiHandler.post {
            val pump = micPump
            if (pump == null) {
                Log.w(TAG, "restartMic: насоса нет")
                return@post
            }
            val ok = if (pump.isRunning) pump.restart() else ensurePumpRunning()
            if (ok && wakeWordEnabled && !isRecognizing) {
                pump.setConsumer(wakeWordDetector)
            }
            Log.i(TAG, "restartMic ok=$ok")
        }
    }

    /** Раз в 8 с: если активация включена, а вход умер — поднять, как тумблер. */
    private val micWatchdog = object : Runnable {
        override fun run() {
            try {
                val speaking = ::carAudioPlayer.isInitialized && carAudioPlayer.isSpeaking
                if (wakeWordEnabled && !isRecognizing && !greetingPending && !speaking) {
                    val pump = micPump
                    if (pump != null) {
                        if (!pump.isRunning) {
                            Log.w(TAG, "watchdog: насос стоит, поднимаю")
                            if (ensurePumpRunning()) pump.setConsumer(wakeWordDetector)
                        } else if (!pump.isHealthy) {
                            Log.w(TAG, "watchdog: вход мёртв, recover")
                            if (pump.recoverIfDead() && wakeWordEnabled && !isRecognizing) {
                                pump.setConsumer(wakeWordDetector)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "watchdog", t)
            }
            handler.postDelayed(this, 8_000)
        }
    }

    // ------------------------------------------------------------------
    //  Звук
    // ------------------------------------------------------------------

    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .build()
            )
            setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                runCatching { mp.reset() }
                true
            }
        }
    }

    private fun initAudioFocusRequest() {
        // Stage CPv2: раньше запрашивался Transient-Exclusive фокус — он
        // заставлял OEM CarPlay/AA полностью останавливать музыку на каждое
        // приветствие ассистента. MAY_DUCK — поведение штатных ассистентов
        // (Siri): музыка приглушается на время речи и слушания.
        audioFocusRequest = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        stopRecognition()
                        runCatching { mediaPlayer.reset() }
                    }
                    // Вход больше не закрывается: MicPump живёт всё время
                    // работы сервиса, поэтому пауза и возобновление не нужны
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {}
                    AudioManager.AUDIOFOCUS_GAIN -> {}
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
                }
            }
            .build()
    }

    private fun requestAudioFocus(): Boolean =
        audioManager.requestAudioFocus(audioFocusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        Log.i(TAG, "Audio focus abandoned")
    }

    private fun preloadSounds() {
        listOf(R.raw.overlay_start, R.raw.overlay_stop, R.raw.success).forEach { resId ->
            runCatching { resources.openRawResourceFd(resId).close() }
                .onFailure { Log.e(TAG, "Error preloading sound $resId", it) }
        }
    }

    private fun playSound(resourceId: Int) {
        try {
            mediaPlayer.reset()

            val afd = resources.openRawResourceFd(resourceId) ?: run {
                Log.e(TAG, "Resource not found: $resourceId")
                return
            }

            try {
                mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                mediaPlayer.prepareAsync()
                mediaPlayer.setOnPreparedListener { mp ->
                    runCatching {
                        mp.setVolume(0.8f, 0.8f)
                        mp.start()
                    }.onFailure { Log.e(TAG, "Error starting MediaPlayer", it) }
                }
                mediaPlayer.setOnCompletionListener { runCatching { afd.close() } }
            } catch (e: Exception) {
                runCatching { afd.close() }
                Log.e(TAG, "Error setting data source", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "General playSound error", e)
        }
    }

    // ------------------------------------------------------------------
    //  Оверлей
    // ------------------------------------------------------------------

    private fun checkOverlayPermission(): Boolean = OverlayPermission.ensure(this)

    private fun initOverlay() {
        /* Stage VO2: системный оверлей (SYSTEM_ALERT_WINDOW + Lottie) ПОЛНОСТЬЮ
           отключён — на эмуляторах с выданным appops он показывал статичное
           окно без анимации поверх OverlayActivity. Единственный экран «Слушаю»
           теперь OverlayActivity — точная копия voiceHero из HTML-концепта. */
        Log.i(TAG, "Системный оверлей отключён (Stage VO2), экран «Слушаю» = OverlayActivity")
    }

    private fun showOverlay() {
        /* Stage VO3: на вкладке «Голос» плашка не вылезает — анимацию ведёт
           сам микрофон интерфейса (voice.ui). Иначе — плашка посреди экрана
           (OverlayActivity — копия voiceHero с той же анимацией). */
        val bridge = UiBridge.companionInstance
        if (bridge != null && bridge.isVoiceTabActive) {
            voiceInApp = true
            Log.i(TAG, "Вкладка «Голос» активна — анимирую микрофон интерфейса")
            bridge.voiceUi("listen", null, null, null)
            return
        }
        voiceInApp = false
        showFallbackOverlay()
    }

    /** Экран «Слушаю» без SYSTEM_ALERT_WINDOW. Для обычного пользователя. */
    private fun showFallbackOverlay() {
        usingFallbackOverlay = true
        Log.i(TAG, "Показываю OverlayActivity — разрешения на оверлей не нужны")

        val intent = Intent(this, OverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(OverlayActivity.EXTRA_TEXT, "...")
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "OverlayActivity не открылась", it) }
    }

    private fun hideOverlay() {
        if (voiceInApp) {
            UiBridge.companionInstance?.voiceUi("idle")
            return
        }
        overlayView?.visibility = View.GONE

        if (usingFallbackOverlay) {
            localBroadcastManager.sendBroadcast(
                Intent(OverlayActivity.ACTION_OVERLAY_HIDE)
            )
            usingFallbackOverlay = false
        }
    }

    private fun updateStatusText(text: String) {
        if (voiceInApp) {
            UiBridge.companionInstance?.voiceUi("status", text)
            return
        }
        uiHandler.post {
            runCatching {
                overlayView?.findViewById<TextView>(R.id.listening_status_text)?.text = text
            }
        }

        if (usingFallbackOverlay && text.isNotBlank()) {
            localBroadcastManager.sendBroadcast(
                Intent(OverlayActivity.ACTION_OVERLAY_UPDATE)
                    .putExtra(OverlayActivity.EXTRA_TEXT, text)
            )
        }
    }

    private fun logOverlayState() {
        Log.d(TAG, "Overlay state - View: ${overlayView != null}, " +
                "WindowManager: ${windowManager != null}, " +
                "Visible: ${overlayView?.visibility == View.VISIBLE}")
    }

    // ------------------------------------------------------------------
    //  Модели и конвейер
    // ------------------------------------------------------------------

    private fun initModels() {
        Log.i(TAG, "Initializing VOSK model")

        StorageService.unpack(
            this,
            VOICE_MODEL_SOURCE,
            VOICE_MODEL_DIR,
            { unpackedModel ->
                // --- модель распакована, мы в главном потоке ---
                model = unpackedModel

                try {
                    // 1. Насос: единственный AudioRecord на всё время жизни сервиса
                    val pump = MicPump(audioManager, debug = true)

                    // 2. Детектор слова пробуждения. Своего микрофона не имеет.
                    val detector = WakeWordDetector(unpackedModel, debug = true) {
                        onWakeWordDetected()
                    }

                    // 3. Распознаватель команды. Тоже без своего микрофона.
                    val commands = CommandRecognizer(
                        model = unpackedModel,
                        onPartial = { partial -> handlePartial(partial) },
                        onResult = { text -> handleCommandResult(text) }
                    )

                    // 4. Нав-диктовка (navigator-app-7): та же модель, своя
                    //    сессия; текст уходит broadcast'ом экрану поиска.
                    val dictation = CommandRecognizer(
                        model = unpackedModel,
                        onPartial = { partial -> broadcastDictation("partial", partial) },
                        onResult = { text ->
                            broadcastDictation("final", text)
                            finishNavDictation()
                        }
                    )

                    if (!detector.prepare()) {
                        Log.e(TAG, "Детектор слова пробуждения не создался")
                        return@unpack
                    }
                    if (!commands.prepare()) {
                        Log.e(TAG, "Распознаватель команд не создался")
                        detector.release()
                        return@unpack
                    }
                    if (!dictation.prepare()) {
                        // Диктовка не поднялась — ассистенту это не мешает,
                        // но честно логируем: 🎙 в поиске ответит error'ом.
                        Log.e(TAG, "Распознаватель нав-диктовки не создался")
                    } else {
                        navDictation = dictation
                    }

                    micPump = pump
                    wakeWordDetector = detector
                    commandRecognizer = commands

                    if (!wakeWordEnabled) {
                        // Пользователь выключил активацию. Микрофон не открываем —
                        // ждём кнопку, она поднимет насос сама.
                        Log.i(TAG, "Голосовая активация выключена в настройках, жду кнопку")
                    } else if (pump.start()) {
                        pump.setConsumer(detector)
                        Log.i(TAG, "Voice model initialized successfully")
                    } else {
                        Log.e(TAG, "Микрофон открыть не удалось — проверьте RECORD_AUDIO")
                        // Детектор и распознаватель НЕ освобождаем: кнопка
                        // сможет поднять микрофон позже, когда разрешение выдадут
                    }
                } catch (t: Throwable) {
                    // Throwable: Error от нативных библиотек Exception не ловится
                    Log.e(TAG, "Не удалось поднять звуковой конвейер", t)
                }
            },
            { exception ->
                Log.e(TAG, "Failed to unpack the model", exception)
            }
        )
    }

    /**
     * Поднимает насос, если он остановлен. true — микрофон готов.
     * Вызывается и при включении активации, и при нажатии кнопки.
     */
    private fun ensurePumpRunning(): Boolean {
        val pump = micPump ?: run {
            Log.e(TAG, "MicPump не создан — модель ещё не загрузилась")
            return false
        }
        if (pump.isRunning) return true

        val started = pump.start()
        if (!started) {
            Log.e(TAG, "MicPump не стартовал. Причины: нет RECORD_AUDIO " +
                    "или вход занят другим приложением")
        }
        return started
    }

    // ------------------------------------------------------------------
    //  Сессия распознавания
    // ------------------------------------------------------------------

    /**
     * Кнопка на руле и кнопка в интерфейсе.
     *
     * Раньше здесь стояла проверка micPump?.isRunning != true, и при
     * выключенной голосовой активации кнопка всегда отвечала «модель не
     * загружена»: насос в этом режиме намеренно не запущен. Теперь кнопка
     * поднимает микрофон сама.
     */
    private fun toggleRecognition() {
        logOverlayState()

        if (model == null || commandRecognizer == null) {
            Log.w(TAG, "Модель ещё не готова")
            toast("Голосовая модель ещё загружается")
            return
        }

        if (commandRecognizer?.isActive == true) {
            stopRecognition()
            return
        }

        if (!ensurePumpRunning()) {
            toast(
                if (checkAndRequestPermissions()) "Микрофон занят другим приложением"
                else "Нет доступа к микрофону"
            )
            return
        }

        startRecognition()
    }

    private fun startRecognition() {
        if (commandRecognizer?.isActive == true) return

        if (!ensurePumpRunning()) {
            Log.e(TAG, "Микрофон не поднялся, сессию не начинаю")
            return
        }

        uiHandler.post {
            isRecognizing = true
            greetThenListen()
            Log.i(TAG, "Recognition started (единый микрофон)")
        }
    }

    private fun stopRecognition() {
        if (commandRecognizer?.isActive != true && !isRecognizing) return

        pendingCommand?.let { commandHandler.removeCallbacks(it) }
        pendingCommand = null
        greetingPending = false
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(greetingFallback)

        uiHandler.post {
            runCatching { updateStatusText("") }
            runCatching { hideOverlay() }
            runCatching { playSound(R.raw.overlay_stop) }
            runCatching { abandonAudioFocus() }

            isRecognizing = false
            // Микрофон НЕ закрываем, просто возвращаем звук детектору
            releaseMicAfterSession()
            Log.i(TAG, "Recognition stopped (отмена)")
        }
    }

    private fun startTimeout(timeout: Long = RECOGNITION_TIMEOUT) {
        handler.postDelayed(timeoutRunnable, timeout)
    }

    private val greetingFallback: Runnable = object : Runnable {
        override fun run() {
            if (!greetingPending) return
            if (::carAudioPlayer.isInitialized && carAudioPlayer.isSpeaking) {
                handler.postDelayed(this, 400)
                return
            }
            Log.w(TAG, "Привет не дождались — слушаю команду")
            greetingPending = false
            afterEcho { beginListening() }
        }
    }

    /**
     * Вместо бипа — голос «Привет». Микрофон в это время отключён,
     * иначе ассистент слышит сам себя. После TTS — beginListening().
     */
    private fun greetThenListen() {
        requestAudioFocus()
        showOverlay()
        updateStatusText("Привет")
        micPump?.setConsumer(null)
        if (::carAudioPlayer.isInitialized) {
            greetingPending = true
            handler.removeCallbacks(greetingFallback)
            handler.postDelayed(greetingFallback, 5000)
            say("Привет")
        } else {
            beginListening()
        }
    }

    /**
     * После TTS колонки ещё звучат, AudioRecord полный эха.
     * Ждём, пока уровень упадёт (или 700 мс), и только тогда слушаем.
     * Иначе «Привет» распознаётся как «свет» / «печь».
     */
    private fun afterEcho(then: () -> Unit) {
        val minWait = 280L
        val maxWait = 800L
        val start = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - start
                val level = micPump?.lastLevelPct ?: 0
                if ((elapsed >= minWait && level < 14) || elapsed >= maxWait) {
                    Log.i(TAG, "После TTS: ${elapsed}мс уровень=$level% — слушаю")
                    then()
                } else {
                    handler.postDelayed(this, 40)
                }
            }
        }
        handler.postDelayed(poll, minWait)
    }

    private fun beginListening() {
        if (commandRecognizer == null) return
        isRecognizing = true
        updateStatusText("Слушаю...")
        startTimeout()
        micPump?.setConsumer(commandRecognizer)
        Log.i(TAG, "Слушаю команду")
    }

    private fun startProjectionGate() {
        if (projectionGate != null) return
        val gate = com.example.voiceapp3.projection.ProjectionVoiceGate(this) { connected ->
            uiHandler.post {
                if (connected) {
                    // Проекция пошла: микрофон слушает музыку телефона —
                    // детектор выключаем, иначе ложные срабатывания будут
                    // вырывать audio focus у CarPlay/AA.
                    if (!isRecognizing) micPump?.setConsumer(null)
                } else {
                    restoreWakeWordConsumer()
                }
            }
        }
        projectionGate = gate
        gate.start()
    }

    /** Вернуть детектор в микрофон, если wake word включён и сессии нет. */
    private fun restoreWakeWordConsumer() {
        if (isRecognizing) return
        val speaking = ::carAudioPlayer.isInitialized && carAudioPlayer.isSpeaking
        if (wakeWordEnabled && !greetingPending && !speaking) {
            micPump?.takeIf { it.isRunning }?.setConsumer(wakeWordDetector)
        }
    }

    private fun onWakeWordDetected() {
        uiHandler.post {
            // Stage CPv2: во время projection-сессии wake word не стартует
            // сессию — это и была причина мгновенной паузы музыки CarPlay.
            if (projectionGate?.isProjectionConnected() == true) {
                Log.i(TAG, "Игнорирую wake word: идёт projection-сессия (CarPlay/AA)")
                return@post
            }
            if (::carAudioPlayer.isInitialized && carAudioPlayer.isSpeaking) {
                Log.i(TAG, "Игнорирую wake word: идёт озвучка")
                return@post
            }
            Log.i(TAG, "Wake word — открываю сессию команды")
            isRecognizing = true
            greetThenListen()
        }
    }

    /** Промежуточный текст команды. Приходит уже в главном потоке. */
    private fun handlePartial(partial: String) {
        Log.i(TAG, "Partial: $partial")
        updateStatusText(partial)
    }

    /**
     * Команда распознана, либо сессия закрылась по тишине.
     *
     * Свой таймаут тут не нужен: CommandRecognizer сам закрывает сессию
     * через 1200 мс после того, как человек замолчал.
     */
    private fun handleCommandResult(text: String) {
        handler.removeCallbacks(timeoutRunnable)
        // Пока разбираем результат — в Vosk ничего не льём.
        // Иначе «Привет»/«Не понял» из колонок становится следующей командой.
        micPump?.setConsumer(null)

        if (isOwnVoiceEcho(text)) {
            Log.i(TAG, "Эхо TTS, не команда: '$text'")
            afterEcho { beginListening() }
            return
        }

        isRecognizing = false
        var uiReply: String? = null

        try {
            if (text.isBlank()) {
                Log.i(TAG, "Result is empty")
                uiReply = "Не понял"
                say("Не понял")
            } else {
                runCatching { abandonAudioFocus() }
                lastCommandUnderstood = false
                val handled = processCommand(text)
                val speaking = ::carAudioPlayer.isInitialized && carAudioPlayer.isSpeaking
                Log.i(TAG, "Команда обработана: $handled understood=$lastCommandUnderstood speaking=$speaking")
                // Три разных ответа — чтобы было понятно, что случилось.
                // Не распознал     → «Не понял»
                // Понял и сделал   → «Готово»
                // Понял, но железо → «Не получилось» (эмулятор без окон, нет права)
                val reply = when {
                    !lastCommandUnderstood -> "Не понял"
                    handled -> "Готово"
                    else -> "Не получилось"
                }
                uiReply = reply
                if (!speaking) say(reply)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Ошибка обработки команды", t)
            runCatching { playSound(R.raw.overlay_stop) }
        } finally {
            // Stage VO2: финал как в концепте — «Жду обращения» и карточка
            // «фраза / ответ» в оверлее; OverlayActivity закроется сам (~2,4 с).
            if (uiReply != null) {
                runCatching {
                    showOverlayResult(text.takeIf { it.isNotBlank() }, uiReply)
                }
            } else {
                runCatching { updateStatusText("") }
                runCatching { hideOverlay() }
            }
            val speaking = ::carAudioPlayer.isInitialized && carAudioPlayer.isSpeaking
            if (!speaking) releaseMicAfterSession()
            Log.i(TAG, "Recognition stopped")
        }
    }

    /** Stage VO3: финал — в интерфейс (voice.ui) или в плашку (броадкаст). */
    private fun showOverlayResult(phrase: String?, reply: String) {
        if (voiceInApp) {
            UiBridge.companionInstance?.voiceUi("result", null, phrase, reply)
            return
        }
        if (usingFallbackOverlay) {
            localBroadcastManager.sendBroadcast(
                Intent(OverlayActivity.ACTION_OVERLAY_UPDATE)
                    .putExtra(OverlayActivity.EXTRA_PHRASE, phrase)
                    .putExtra(OverlayActivity.EXTRA_REPLY, reply)
            )
        } else {
            hideOverlay()
        }
    }

    private fun releaseMicAfterSession() {
        if (wakeWordEnabled) {
            micPump?.setConsumer(wakeWordDetector)
        } else {
            micPump?.setConsumer(null)
            micPump?.stop()
            Log.i(TAG, "Активация выключена — микрофон отпущен до следующей кнопки")
        }
    }

    // ------------------------------------------------------------------
    //  Нав-диктовка адреса (navigator-app-7): короткая сессия Vosk, текст
    //  уходит broadcast'ом экрану поиска; ни overlay, ни TTS, ни команд.
    // ------------------------------------------------------------------

    /** Короткая диктовка в поле поиска. Конфликты: гасят активную сессию
     *  команды (наоборот — командная панель перезатребует микрофон позже). */
    private fun startNavDictation() {
        val dict = navDictation
        if (dictating) { Log.d(TAG, "Диктовка уже идёт — повторный старт игнорим"); return }
        if (dict == null) {
            Log.e(TAG, "Нав-диктовка: распознаватель не готов (модель?)")
            broadcastDictation("error", "голосовая модель не готова")
            return
        }
        if (commandRecognizer?.isActive == true || isRecognizing) {
            Log.i(TAG, "Нав-диктовка: идёт сессия команды — прерываю для диктовки")
            stopRecognition()
        }
        if (!ensurePumpRunning()) {
            Log.e(TAG, "Нав-диктовка: микрофон не поднялся")
            broadcastDictation(
                "error",
                if (checkAndRequestPermissions()) "микрофон занят другим приложением"
                else "нет доступа к микрофону"
            )
            return
        }
        dictating = true
        micPump?.setConsumer(dict)   // сессия самозавершится по тишине (SILENCE_END_MS)
        broadcastDictation("start", null)
        Log.i(TAG, "Нав-диктовка: сессия начата")
    }

    /** Отмена снаружи (повторный тап по 🎙, уход из поиска). */
    private fun stopNavDictation(state: String) {
        if (!dictating) return
        completeNavDictation(state)
    }

    /** Завершение после onResult распознавателя: consumer возвращается
     *  штатному слушателю (детектору wake-word), мост — как у команд. */
    private fun finishNavDictation() {
        if (!dictating) return
        uiHandler.post { completeNavDictation(null) }
    }

    private fun completeNavDictation(state: String?) {
        if (!dictating) return
        dictating = false
        state?.let { broadcastDictation(it, null) }
        runCatching { releaseMicAfterSession() }
        Log.i(TAG, "Нав-диктовка: сессия завершена (${state ?: "final"})")
    }

    private fun broadcastDictation(state: String, text: String?) {
        val i = Intent(ACTION_NAV_DICTATION)
            .setPackage(applicationContext.packageName)   // только наши приёмники
            .putExtra(EXTRA_STATE, state)
        text?.let { i.putExtra(EXTRA_TEXT, it) }
        runCatching { sendBroadcast(i) }
            .onFailure { Log.e(TAG, "dictation broadcast '$state' failed", it) }
    }

    // ------------------------------------------------------------------
    //  Разбор и выполнение команды
    // ------------------------------------------------------------------

    /** Свои же фразы из колонок. Не выполняем и не отвечаем «Не понял». */
    private fun isOwnVoiceEcho(raw: String): Boolean {
        val t = raw.lowercase().replace('ё', 'е').trim()
        if (t.isEmpty()) return false
        val echo = setOf(
            "привет", "джили", "жили", "джиле", "жиле",
            "привет джили", "привет жили", "привет джиле",
            "не понял", "готово", "не получилось", "слушаю",
        )
        return t in echo
    }

    private fun processCommand(text: String?): Boolean {
        if (handleWakeWordCommand(text)) return true
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "Received null or empty command")
            return false
        }

        val commands = RuleBasedIntentParser.splitCommands(text)
        if (commands.size > 1) {
            Log.i(TAG, "Detected multiple commands: ${commands.size} $commands")
            return processCommandSequence(commands)
        }
        return processSingleCommand(text)
    }

    private fun processCommandSequence(commands: List<String>): Boolean {
        var anySuccess = false
        var anyUnderstood = false
        commands.forEachIndexed { index, command ->
            Log.i(TAG, "Processing command ${index + 1}/${commands.size}: '$command'")
            lastCommandUnderstood = false
            if (processSingleCommand(command)) {
                anySuccess = true
            } else {
                Log.w(TAG, "Command $index failed: '$command'")
            }
            if (lastCommandUnderstood) anyUnderstood = true
        }
        lastCommandUnderstood = anyUnderstood
        return anySuccess
    }

    private fun processSingleCommand(text: String): Boolean {
        // Правила первыми: в ONNX-модели нет слова «окно» (только «стекло»),
        // и при загруженном ActionModel команды стёкол уходили в мусор.
        val rule = RuleBasedIntentParser.predict(text)
        val prediction = if (rule.intent != "unknown") {
            rule
        } else {
            intentModel?.predict(text) ?: rule
        }

        Log.d(TAG, """
            Original: ${prediction.text}
            Normalized: ${prediction.normalizedText}
            Intent: ${prediction.intent} (${(prediction.confidence.times(100)).toInt()}% confidence)
            Entities: ${prediction.entities}
        """.trimIndent())

        lastCommandUnderstood = prediction.intent != "unknown" && prediction.intent.isNotBlank()

        if (prediction.intent == "unknown") {
            Log.w(TAG, "Интент не распознан для '$text'")
            return false
        }

        // Обработчик может бросить что угодно — в том числе NoClassDefFoundError
        // при обращении к android.car на обычном образе. Ловим Throwable,
        // иначе сервис снова начнёт перезапускаться после каждой команды.
        val handled = try {
            intentRegistry.handle(prediction)
        } catch (t: Throwable) {
            Log.e(TAG, "Обработчик '${prediction.intent}' упал", t)
            return false
        }

        Log.i(TAG, "Handler result: $handled (intent='${prediction.intent}')")

        if (!handled && prediction.confidence >= 0.9f) {
            Log.w(TAG, "Интент уверенный, но свойство не приняло значение")
            return DEBUG_ACCEPT_UNVERIFIED
        }

        return handled
    }

    // ------------------------------------------------------------------
    //  Управление голосовой активацией
    // ------------------------------------------------------------------

    /** Текущее состояние — для галочки в UI. */
    fun isWakeWordEnabled(): Boolean = wakeWordEnabled

    private fun handleWakeWordCommand(raw: String?): Boolean {
        val text = raw?.lowercase()?.replace('ё', 'е')?.trim() ?: return false

        val offPhrases = listOf(
            "выключи голосовую активацию", "отключи голосовую активацию",
            "выключи активацию", "отключи активацию",
            "перестань слушать", "не слушай меня", "хватит слушать",
            "выключи микрофон", "отключи микрофон",
        )
        val onPhrases = listOf(
            "включи голосовую активацию", "включи активацию",
            "слушай меня", "начни слушать", "включи микрофон",
        )

        return when {
            offPhrases.any { text.contains(it) } -> {
                Log.i(TAG, "Голосовая команда: выключить активацию")
                say("Больше не слушаю. Включите кнопкой.")
                setWakeWordEnabled(false)
                true
            }
            onPhrases.any { text.contains(it) } -> {
                Log.i(TAG, "Голосовая команда: включить активацию")
                setWakeWordEnabled(true)
                say("Слушаю")
                true
            }
            else -> false
        }
    }

    /**
     * Включает или выключает голосовую активацию.
     *
     * @param enabled нужное состояние
     * @param persist сохранять ли выбор. false — временное отключение,
     *                например на время разговора по телефону.
     */
    fun setWakeWordEnabled(enabled: Boolean, persist: Boolean = true) {
        uiHandler.post {
            if (persist) wakeWordEnabled = enabled

            if (enabled) {
                if (!ensurePumpRunning()) {
                    Log.e(TAG, "Активация не включена: микрофон не открылся")
                    sendBroadcast(Intent(ACTION_WAKE_WORD_STATE).putExtra(EXTRA_ENABLED, false))
                    return@post
                }
                micPump?.setConsumer(wakeWordDetector)
                Log.i(TAG, "Голосовая активация ВКЛЮЧЕНА")
            } else {
                micPump?.setConsumer(null)
                // Сессия команды может идти прямо сейчас — тогда микрофон
                // отпустит handleCommandResult, когда закончит
                if (commandRecognizer?.isActive != true) {
                    micPump?.stop()
                }
                Log.i(TAG, "Голосовая активация ВЫКЛЮЧЕНА, микрофон отпущен")
            }

            sendBroadcast(Intent(ACTION_WAKE_WORD_STATE).putExtra(EXTRA_ENABLED, enabled))
        }
    }

    // ------------------------------------------------------------------
    //  Вспомогательное
    // ------------------------------------------------------------------

    /** Озвучка. Только в фоновом потоке: playText синхронный. */
    private fun say(text: String) {
        if (!::carAudioPlayer.isInitialized) {
            Log.w(TAG, "TTS ещё не готов, пропускаю: $text")
            return
        }
        Thread({
            runCatching { carAudioPlayer.playText(text) }
                .onFailure { Log.w(TAG, "TTS не сработал: $text", it) }
        }, "TtsSpeak").start()
    }

    private fun toast(message: String) {
        uiHandler.post {
            runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }
    }

    // ------------------------------------------------------------------
    //  Уведомления
    // ------------------------------------------------------------------

    private fun startForegroundService() {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, MAIN_CHANNEL_ID)
            .setContentTitle("Голосовой ассистент")
            .setContentText("Сервис запущен")
            .setSmallIcon(R.drawable.ic_mic)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    private fun createNotificationChannel() {
        val mainChannel = NotificationChannel(
            MAIN_CHANNEL_ID, "Голосовой ассистент", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Уведомления сервиса распознавания" }

        val permissionChannel = NotificationChannel(
            PERMISSION_CHANNEL_ID, "Запросы разрешений", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Важные запросы разрешений" }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(mainChannel)
        nm.createNotificationChannel(permissionChannel)
    }

    // ------------------------------------------------------------------
    //  Освобождение ресурсов
    // ------------------------------------------------------------------

    /**
     * Каждый шаг в своём runCatching.
     *
     * Раньше всё стояло в одном try: падение на unregisterReceiver
     * отменяло закрытие микрофона, и вход оставался занятым до
     * перезагрузки. На Android 9 AudioRecord.stop() без release()
     * вход не отпускает — за это отвечает MicPump.stop().
     */
    private fun cleanup() {
        runCatching { projectionGate?.stop() }
        projectionGate = null

        runCatching { micPump?.stop() }
        micPump = null

        runCatching { wakeWordDetector?.release() }
        wakeWordDetector = null

        runCatching { commandRecognizer?.release() }
        commandRecognizer = null

        runCatching { navDictation?.release() }
        navDictation = null
        dictating = false

        runCatching { wakeWordSwitchReceiver?.let { unregisterReceiver(it) } }
        wakeWordSwitchReceiver = null

        runCatching { hubReceiver?.let { unregisterReceiver(it) } }
        hubReceiver = null

        // Stage SCR: подписка на пробуждение ГУ живёт вместе с сервисом
        runCatching { screenWakeReceiver?.let { unregisterReceiver(it) } }
        screenWakeReceiver = null
        handler.removeCallbacks(brightnessSync)
        /* Срез №3: останавливаем периодическую синхронизацию статус-иконок. */
        runCatching { StatusIcons.stopPeriodicSync() }
        runCatching { NetworkTtl.unregisterNetworkReceiver(this) }
        runCatching { StatusOverlay.hide(this) }

        runCatching { unregisterReceiver(keyEventReceiver) }
        runCatching { mediaCenterBridge.stop() }

        isRecognizing = false

        runCatching { handler.removeCallbacksAndMessages(null) }
        runCatching { uiHandler.removeCallbacksAndMessages(null) }
        runCatching { commandHandler.removeCallbacksAndMessages(null) }

        // VOSK: recognizer'ы живут внутри детектора и распознавателя,
        // они уже освобождены выше. Модель закрываем строго после них,
        // иначе на API 28 нативный слой падает
        runCatching { model?.close() }
        model = null

        runCatching { intentModel?.cleanup() }
        intentModel = null

        runCatching { mediaPlayer.release() }

        if (::carAudioPlayer.isInitialized) {
            runCatching { carAudioPlayer.release() }
        }
        if (::vehiclePropertyHelper.isInitialized) {
            runCatching { vehiclePropertyHelper.disconnect() }
        }

        runCatching { hideOverlay() }
        runCatching { windowManager?.removeView(overlayView) }
        overlayView = null
        windowManager = null

        isServiceInitialized = false
        Log.i(TAG, "All resources cleaned up")
    }
}
