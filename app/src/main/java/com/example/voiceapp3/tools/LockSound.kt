package com.example.voiceapp3.tools

import com.example.voiceapp3.tools.Prefs2

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Stage CXT · срез «Звуки замка (открытие и закрытие)»:
 *
 * Точный перенос логики CentralEXAuto (w0.m, w0.p, AVAS exterior):
 * 1. Копирование звуковых файлов с подключенных USB-накопителей в постоянную папку
 *    приложения (`filesDir/carlock_sounds/`).
 * 2. Выбор кастомного звука закрытия и звука открытия из списка скопированных файлов.
 * 3. Подавление дублирования: при активном кастомном звуке штатный звук ГУ
 *    отключается через Settings.System.putInt("lock_car_audio_enable", 0).
 * 4. Воспроизведение через наружный динамик автомобиля (AudioAttributes usage 29 / AVAS).
 */
object LockSound {
    private const val TAG = "CENTRAL_LockSound"

    const val PREF_LOCK_SOUND_ENABLED = "lock_car_audio_enable"
    const val PREF_LOCK_SOUND_PATH = "lock_car_audio_path"
    const val PREF_LOCK_SOUND_NAME = "lock_car_audio_display_name"
    const val PREF_LOCK_SOUND_VOLUME = "lock_car_audio_volume"
    const val PREF_LOCK_SOUND_DELAY = "lock_car_audio_delay"

    const val PREF_UNLOCK_SOUND_ENABLED = "unlock_car_audio_enable"
    const val PREF_UNLOCK_SOUND_PATH = "unlock_car_audio_path"
    const val PREF_UNLOCK_SOUND_NAME = "unlock_car_audio_display_name"

    const val SETTINGS_LOCK_CAR_AUDIO_ENABLE = "lock_car_audio_enable"
    const val SETTINGS_LOCK_CAR_AUDIO_PATH = "lock_car_audio_path"
    const val SETTINGS_UNLOCK_CAR_AUDIO_ENABLE = "unlock_car_audio_enable"
    const val SETTINGS_UNLOCK_CAR_AUDIO_PATH = "unlock_car_audio_path"

    const val OEM_CARLOCK_DEFAULT_WAV = "/vendor/etc/carlock/xingguangshanyao.wav"

    /**
     * §6 iter45: ФАКТ применения кастомного звука — отдельно от намерения владельца.
     *   `applied` — файл на месте и собственный bind подтверждён;
     *   `pending` — намерение ON, файл выбран, но применить сейчас нельзя
     *               (нет root-канала, временно недоступен каталог, идёт копирование);
     *   `off`     — владелец выключил/сбросил к заводскому.
     */
    const val PREF_LOCK_SOUND_APPLIED = "lock_sound_applied"
    const val PREF_LOCK_SOUND_PENDING_REASON = "lock_sound_pending_reason"

    fun appliedState(ctx: Context): String =
        Prefs2.getString(ctx, PREF_LOCK_SOUND_APPLIED, if (isEnabled(ctx)) "pending" else "off")
            ?: (if (isEnabled(ctx)) "pending" else "off")

    fun pendingReason(ctx: Context): String =
        Prefs2.getString(ctx, PREF_LOCK_SOUND_PENDING_REASON, "") ?: ""

    /**
     * iter55 §9/§7.1: сохранить «применение неизвестно» вместе с причиной, НЕ меняя намерение
     * владельца. Раньше в такой ситуации писался `pending` («ждёт готовности») либо вообще ничего,
     * и UI показывал состояние, которого на устройстве не доказано.
     */
    private fun markUnknown(ctx: Context, reason: String) {
        Prefs2.putString(ctx, PREF_LOCK_SOUND_APPLIED, Toggles45.APPLIED_UNKNOWN)
        Prefs2.putString(ctx, PREF_LOCK_SOUND_PENDING_REASON, reason)
        AppLog.w(ctx, TAG, "LOCK_SOUND_STATE desired=${isEnabled(ctx)} applied=${appliedState(ctx)} " +
            "reason=«$reason» (исход не доказан; намерение владельца сохранено)")
    }

    private fun markApplied(ctx: Context, applied: Boolean, reason: String) {
        Prefs2.putString(ctx, PREF_LOCK_SOUND_APPLIED,
            Toggles45.appliedOutcome(isEnabled(ctx), applied))
        Prefs2.putString(ctx, PREF_LOCK_SOUND_PENDING_REASON, if (applied) "" else reason)
        AppLog.i(ctx, TAG, "LOCK_SOUND_STATE desired=${isEnabled(ctx)} applied=${appliedState(ctx)} " +
            "reason=«${pendingReason(ctx)}» (намерение владельца автоматикой не меняется)")
    }

    const val DEFAULT_VOLUME = 80
    const val DEFAULT_DELAY_MS = 0
    val DELAY_CHOICES = setOf("0", "150", "300")

    private val soundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // P7: счётчик неудачных попыток bind-mount'а звука. При ≥3 детерминированных
    // ошибок (Permission/ENOENT/ERR_ROOT_CHANNEL_UNAVAILABLE) — отключаем тумблер
    // и перестаём штурмовать каждые 60 секунд watchdog'ом.
    private const val MAX_BIND_RETRIES = 3
    @Volatile private var bindFailCount = 0
    @Volatile private var bindDisabledUntilBoot = false
    /**
     * §6.3 iter46: поколение операций звука. OFF или смена файла увеличивают его, поэтому
     * запланированный повтор/копирование старого поколения НЕ смонтирует звук после OFF.
     */
    @Volatile private var bindGen = 0L

    /**
     * iter52 §5.3 — ОДИН шлюз на все операции со слоями.
     *
     * bind идёт на `BgWorker`, снятие — на `soundExecutor`, сверка — на обоих. Это РАЗНЫЕ
     * потоки: без общей блокировки bind мог ставиться одновременно со снятием, и тогда
     * «снято» и «установлено» описывали бы один и тот же момент, а реестр — уже нет.
     * Блокировка честная: ждём столько, сколько нужно (без таймаута-догадки), но пишем в лог,
     * если ждали долго.
     */
    private val ioGate = java.util.concurrent.locks.ReentrantLock()

    private fun gateEnter(what: String) {
        val t0 = SystemClock.elapsedRealtime()
        ioGate.lock()
        val waited = SystemClock.elapsedRealtime() - t0
        if (waited > 500) AppLog.i(TAG, "LOCKSOUND_SERIALIZE what=$what waited_ms=$waited " +
            "(bind/restore/reconcile идут строго по одному)")
    }

    private fun gateExit() {
        if (ioGate.isHeldByCurrentThread) ioGate.unlock()
    }
    @Volatile private var lastBindFailReason: String? = null
    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    /** Дебаунс: не играть повторный звук того же типа чаще чем раз в DEBOUNCE_MS
     *  (защита от двойных событий от двери/экрана). */
    private const val DEBOUNCE_MS = 800L
    @Volatile private var lastPlayAtMs: Long = 0L
    @Volatile private var lastPlayKind: String = ""

    fun getSoundsDir(ctx: Context): File {
        return File(ctx.filesDir, "carlock_sounds").apply { if (!exists()) mkdirs() }
    }

    // ──────────────────────────────────────────────────────────────
    // Состояние блокировки (Lock / Закрытие)
    // ──────────────────────────────────────────────────────────────

    fun isEnabled(ctx: Context): Boolean {
        // iter17: prefs via Prefs2
        return Prefs2.getBoolean(ctx, PREF_LOCK_SOUND_ENABLED, false)
    }

    fun getSelectedPath(ctx: Context): String {
        // iter17: prefs via Prefs2
        val path = Prefs2.getString(ctx, PREF_LOCK_SOUND_PATH, "") ?: ""
        if (path.isNotEmpty() && File(path).exists()) return path
        return ""
    }

    fun getDisplayName(ctx: Context): String {
        // iter17: prefs via Prefs2
        val name = Prefs2.getString(ctx, PREF_LOCK_SOUND_NAME, "") ?: ""
        if (name.isNotEmpty()) return name
        val path = getSelectedPath(ctx)
        return if (path.isNotEmpty()) File(path).name else "Заводской (Geely OEM)"
    }

    fun getVolume(ctx: Context): Int {
        // iter17: prefs via Prefs2
        return Prefs2.cps(ctx).getInt(PREF_LOCK_SOUND_VOLUME, DEFAULT_VOLUME)
    }

    fun getDelayMs(ctx: Context): Int {
        // iter17: prefs via Prefs2
        return Prefs2.cps(ctx).getInt(PREF_LOCK_SOUND_DELAY, DEFAULT_DELAY_MS)
    }

    fun setVolume(ctx: Context, volume: Int): ToolsResult {
        val clamped = volume.coerceIn(5, 100)
        Prefs2.putInt(ctx, PREF_LOCK_SOUND_VOLUME, clamped)
        return ToolsResult(
            action = "settings.lockSoundVol",
            title = "Громкость звука замка",
            steps = listOf(
                ToolsStep(
                    step = "persist",
                    command = "SharedPreferences.putInt($PREF_LOCK_SOUND_VOLUME, $clamped)",
                    startedAtMs = SystemClock.elapsedRealtime(),
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    exitCode = 0,
                    status = ToolsStep.Status.OK,
                    actual = "$clamped%"
                )
            )
        )
    }

    fun setDelay(ctx: Context, delayMs: Int): ToolsResult {
        val validDelay = if (delayMs.toString() in DELAY_CHOICES) delayMs else DEFAULT_DELAY_MS
        Prefs2.putInt(ctx, PREF_LOCK_SOUND_DELAY, validDelay)
        return ToolsResult(
            action = "settings.lockSoundDelay",
            title = "Задержка звука замка",
            steps = listOf(
                ToolsStep(
                    step = "persist",
                    command = "SharedPreferences.putInt($PREF_LOCK_SOUND_DELAY, $validDelay)",
                    startedAtMs = SystemClock.elapsedRealtime(),
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    exitCode = 0,
                    status = ToolsStep.Status.OK,
                    actual = "$validDelay мс"
                )
            )
        )
    }

    fun setEnabled(ctx: Context, enabled: Boolean): ToolsResult {
        val t0 = SystemClock.elapsedRealtime()
        val steps = ArrayList<ToolsStep>(3)
        // iter17: prefs via Prefs2

        Prefs2.putBoolean(ctx, PREF_LOCK_SOUND_ENABLED, enabled)
        if (enabled) Prefs2.putString(ctx, PREF_LOCK_SOUND_APPLIED, "pending")
        else Prefs2.putString(ctx, PREF_LOCK_SOUND_APPLIED, "off")
        // §6.3 iter46: любое решение владельца — новая генерация: старые отложенные задачи
        // (копирование/bind/повтор) не имеют права применить звук после OFF.
        bindGen++
        bindFailCount = 0
        bindDisabledUntilBoot = false
        AppLog.i(ctx, TAG, "LOCK_SOUND_INTENT desired=$enabled source=user_click gen=$bindGen")
        // iter52 §5.1(3): смена намерения — реальная точка сверки журнала. OFF после потерянного
        // ответа bind должен СНЯТЬ наш слой (иначе он остаётся висеть без учёта), а ON — довести
        // до выбранного файла. Сверка идёт до применения, в фоне (ADB внутри).
        BgWorker.post("LockSound.reconcileIntent") {
            runCatching { reconcilePendingOps(ctx.applicationContext, "intent_desired=$enabled") }
        }
        // iter52 §5.3: OFF — это НЕ только «запись preferences». Раньше снятие своего слоя
        // происходило лишь после следующей удачной операции или на boot: владелец выключал
        // звук, а система продолжала играть наш файл. Теперь OFF снимает ТОЛЬКО свои слои
        // (чужое не трогается) и делает это сразу, под тем же шлюзом, что bind.
        if (!enabled) {
            AppLog.i(ctx, TAG, "LOCK_SOUND_OFF action=restore_own_layers " +
                "(снимаем только свои слои по реестру; чужие/стоковые не трогаем)")
            convergeAfterBind(ctx, true)
        }
        val prefReadback = Prefs2.getBoolean(ctx, PREF_LOCK_SOUND_ENABLED, false)
        steps += ToolsStep(
            step = "persist_pref",
            command = "prefs.putBoolean($PREF_LOCK_SOUND_ENABLED, $enabled)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.elapsedRealtime(),
            exitCode = if (prefReadback == enabled) 0 else 1,
            stdout = "enabled=$prefReadback",
            expected = enabled.toString(),
            actual = prefReadback.toString(),
            status = if (prefReadback == enabled) ToolsStep.Status.OK else ToolsStep.Status.FAILED
        )

        // Предотвращение дублирования:
        // OEM-логика (w0/qd.smali:1183-1200): Settings.System.putString("lock_car_audio_path", path)
        //   + putString("lock_car_audio_enable", "1") — фреймворк Geely играет звук ПО ЭТОМУ ПУТИ
        //   вместо заводского, если enable=1. Ранее я ошибочно ставил enable=0
        //   (это полностью отключает звук блокировки, из-за чего слышен только
        //   системный fallback или тишина). Правильная последовательность:
        //     enabled=true  → lock_car_audio_path = <кастомный путь>
        //                     lock_car_audio_enable = "1" (включить кастомный)
        //     enabled=false → lock_car_audio_path = "" (сбросить путь)
        //                     lock_car_audio_enable = "1" (вернуть заводской)
        val customPath = if (enabled) getSelectedPath(ctx) else ""
        var sysPathErr = ""
        var sysEnableErr = ""
        var pathActual = ""
        var enableActual = -1

        if (customPath.isNotEmpty() && File(customPath).exists()) {
            try {
                Settings.System.putString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_PATH, customPath)
                pathActual = Settings.System.getString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_PATH) ?: ""
            } catch (t: Throwable) {
                sysPathErr = "${t.javaClass.simpleName}: ${t.message ?: "path-denied"}"
                AppLog.w(ctx, TAG, "Settings.System.putString(lock_car_audio_path) failed: $sysPathErr")
            }
        } else if (!enabled) {
            try {
                Settings.System.putString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_PATH, "")
                pathActual = ""
            } catch (t: Throwable) {
                sysPathErr = "${t.javaClass.simpleName}: ${t.message ?: "path-denied"}"
            }
        }

        val oemSysVal = if (enabled) "1" else "1" // enable=1 — звук ВКЛЮЧЁН: кастомный или заводской
        try {
            Settings.System.putString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_ENABLE, oemSysVal)
            val sVal = Settings.System.getString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_ENABLE) ?: ""
            enableActual = if (sVal == "1") 1 else if (sVal == "0") 0 else -1
        } catch (t: Throwable) {
            sysEnableErr = "${t.javaClass.simpleName}: ${t.message ?: "Permission denied"}"
            AppLog.w(ctx, TAG, "Settings.System write failed: $sysEnableErr")
        }

        steps += ToolsStep(
            step = "system_path",
            command = "Settings.System.putString($SETTINGS_LOCK_CAR_AUDIO_PATH, $customPath)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.elapsedRealtime(),
            exitCode = if (pathActual == customPath) 0 else 1,
            stdout = "lock_car_audio_path=$pathActual",
            stderr = sysPathErr,
            expected = customPath,
            actual = pathActual,
            status = if (pathActual == customPath) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
        )

        steps += ToolsStep(
            step = "system_enable",
            command = "Settings.System.putString($SETTINGS_LOCK_CAR_AUDIO_ENABLE, $oemSysVal)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.elapsedRealtime(),
            exitCode = if (enableActual == oemSysVal.toInt()) 0 else 1,
            stdout = "lock_car_audio_enable=$enableActual (1=sound on)",
            stderr = sysEnableErr,
            expected = oemSysVal,
            actual = enableActual.toString(),
            status = if (enableActual == oemSysVal.toInt()) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
        )

        val fileValid = if (enabled) {
            val sp = getSelectedPath(ctx); if (sp.isNotEmpty() && File(sp).exists()) applySystemLockSoundBind(ctx, File(sp))
            customPath.isNotEmpty() && File(customPath).exists()
        } else true

        val fileStatusMsg = when {
            !enabled -> "Кастомный звук выключен (активен заводской)"
            customPath.isEmpty() -> "Включено, ожидается выбор аудиофайла"
            fileValid -> "Кастомный звук активен: ${File(customPath).name}"
            else -> "Файл не найден по пути $customPath"
        }

        steps += ToolsStep(
            step = "verify_custom_sound_file",
            command = "verifyFile($customPath)",
            startedAtMs = t0,
            finishedAtMs = SystemClock.elapsedRealtime(),
            exitCode = if (fileValid) 0 else 1,
            stdout = fileStatusMsg,
            stderr = if (!fileValid) "Файл отсутствует на диске: $customPath" else "",
            expected = "valid",
            actual = if (fileValid) "valid" else "missing",
            status = if (fileValid) ToolsStep.Status.OK else ToolsStep.Status.FAILED
        )

        val res = ToolsResult(
            action = "settings.lockSound",
            title = "Звук блокировки замка",
            steps = steps
        )
        AppLog.logToggle(ctx, "lockSound", enabled, res)
        return res
    }

    // ──────────────────────────────────────────────────────────────
    // Состояние разблокировки (Unlock / Открытие)
    // ──────────────────────────────────────────────────────────────

    fun isUnlockSoundEnabled(ctx: Context): Boolean {
        // iter17: prefs via Prefs2
        return Prefs2.getBoolean(ctx, PREF_UNLOCK_SOUND_ENABLED, false)
    }

    fun getUnlockSelectedPath(ctx: Context): String {
        // iter17: prefs via Prefs2
        val path = Prefs2.getString(ctx, PREF_UNLOCK_SOUND_PATH, "") ?: ""
        if (path.isNotEmpty() && File(path).exists()) return path
        return ""
    }

    fun getUnlockDisplayName(ctx: Context): String {
        // iter17: prefs via Prefs2
        val name = Prefs2.getString(ctx, PREF_UNLOCK_SOUND_NAME, "") ?: ""
        if (name.isNotEmpty()) return name
        val path = getUnlockSelectedPath(ctx)
        return if (path.isNotEmpty()) File(path).name else "Не выбран (отключен)"
    }

    fun setUnlockSoundEnabled(ctx: Context, enabled: Boolean): ToolsResult {
        val t0 = SystemClock.elapsedRealtime()
        // iter17: prefs via Prefs2
        Prefs2.putBoolean(ctx, PREF_UNLOCK_SOUND_ENABLED, enabled)

        val readback = Prefs2.getBoolean(ctx, PREF_UNLOCK_SOUND_ENABLED, false)
        val path = getUnlockSelectedPath(ctx)
        val hasFile = path.isNotEmpty() && File(path).exists()

        val msg = when {
            !enabled -> "Звук разблокировки выключен"
            hasFile -> "Звук разблокировки активен: ${File(path).name}"
            else -> "Включено, выберите аудиофайл для воспроизведения"
        }

        val res = ToolsResult(
            action = "settings.unlockSound",
            title = "Звук разблокировки замка",
            steps = listOf(
                ToolsStep(
                    step = "persist",
                    command = "prefs.putBoolean($PREF_UNLOCK_SOUND_ENABLED, $enabled)",
                    startedAtMs = t0,
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    exitCode = if (readback == enabled) 0 else 1,
                    stdout = msg,
                    status = if (readback == enabled) ToolsStep.Status.OK else ToolsStep.Status.FAILED
                )
            )
        )
        AppLog.logToggle(ctx, "unlockSound", enabled, res)
        return res
    }

    // ──────────────────────────────────────────────────────────────
    // Копирование аудио с подключенного USB-накопителя (как в CentralEXAuto)
    // ──────────────────────────────────────────────────────────────

    fun copyFromUsb(ctx: Context): ToolsResult {
        val t0 = SystemClock.elapsedRealtime()
        val targetDir = getSoundsDir(ctx)
        var copiedCount = 0
        val copiedNames = ArrayList<String>()

        // Скан всех точек монтирования внешних USB накопителей
        val roots = listOf(
            File("/storage"),
            File("/mnt/media_rw"),
            File("/mnt/usb"),
            File("/mnt/media_rw/usb"),
            File("/mnt/usbhost")
        )

        val searchedDirs = ArrayList<File>()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            root.listFiles()?.forEach { sub ->
                if (sub.isDirectory && !sub.name.equals("emulated", ignoreCase = true) && !sub.name.equals("self", ignoreCase = true)) {
                    searchedDirs.add(sub)
                }
            }
        }
        // Также проверяем папки Music и Download на случай сохранения с браузера
        searchedDirs.add(File("/sdcard/Music"))
        searchedDirs.add(File("/sdcard/Download"))

        val allowedExts = setOf("wav", "mp3", "ogg", "flac", "m4a", "aac")

        for (dir in searchedDirs) {
            scanAndCopy(dir, targetDir, allowedExts, copiedNames, depth = 0)
        }
        copiedCount = copiedNames.size

        val summary = if (copiedCount > 0) {
            "Скопировано звуков: $copiedCount"
        } else {
            // §6 iter45: НЕ снимаем собственный bind, пока владелец не выключил функцию.
            // Раньше неудачный поиск на USB («накопитель не найден») снимал уже работающий
            // кастомный звук — при сохранённом намерении ON это выглядело как «звук отключился».
            if (!isEnabled(ctx)) restoreSystemLockSoundBind(ctx)
            else AppLog.i(ctx, TAG, "copyFromUsb: ничего не найдено, намерение ON — " +
                "существующий кастомный звук НЕ снимаем (applied=${appliedState(ctx)})")
            "USB-накопитель не найден или на нём нет аудиофайлов (WAV/MP3/OGG)"
        }

        AppLog.i(ctx, TAG, "copyFromUsb: $summary, files: $copiedNames")

        val res = ToolsResult(
            action = "audio.copyFromUsb",
            title = "Копирование аудио с USB",
            steps = listOf(
                ToolsStep(
                    step = "copy_usb_sounds",
                    command = "scan_usb_storage",
                    startedAtMs = t0,
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    exitCode = if (copiedCount > 0) 0 else 1,
                    stdout = summary,
                    stderr = if (copiedCount == 0) "Подключите USB-флешку с файлами .wav или .mp3" else "",
                    status = if (copiedCount > 0) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
                )
            )
        )
        AppLog.logAction(ctx, "audio.copyFromUsb", res)
        return res
    }

    private fun scanAndCopy(dir: File, targetDir: File, exts: Set<String>, list: ArrayList<String>, depth: Int) {
        if (depth > 3 || !dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                scanAndCopy(f, targetDir, exts, list, depth + 1)
            } else if (f.isFile) {
                val ext = f.extension.lowercase()
                if (ext in exts && f.length() in 100..(15 * 1024 * 1024)) {
                    val cleanName = f.name.replace(" ", "_")
                    val dest = File(targetDir, cleanName)
                    try {
                        FileInputStream(f).use { inp ->
                            FileOutputStream(dest).use { out ->
                                inp.copyTo(out)
                            }
                        }
                        dest.setReadable(true, false)
                        list.add(dest.name)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to copy ${f.absolutePath}: ${t.message}")
                    }
                }
            }
        }
    }

    /**
     * Получить список скопированных звуковых файлов, доступных для выбора.
     */
    fun getAvailableSoundList(ctx: Context): List<String> {
        val dir = getSoundsDir(ctx)
        val files = dir.listFiles { f -> f.isFile && f.length() > 0 } ?: return emptyList()
        return files.map { it.name }.sorted()
    }

    /**
     * Установка файла из списка доступных скопированных звуков.
     */
    fun selectFileByName(ctx: Context, fileName: String, isLock: Boolean): ToolsResult {
        val t0 = SystemClock.elapsedRealtime()
        val targetFile = File(getSoundsDir(ctx), fileName)
        val actionName = if (isLock) "audio.selectFile" else "audio.selectUnlockFile"
        val title = if (isLock) "Звук блокировки" else "Звук разблокировки"

        if (!targetFile.exists()) {
            val errRes = ToolsResult(
                action = actionName,
                title = title,
                steps = listOf(
                    ToolsStep(
                        step = "find_file",
                        command = "File($fileName).exists()",
                        startedAtMs = t0,
                        finishedAtMs = SystemClock.elapsedRealtime(),
                        exitCode = 1,
                        stderr = "Файл $fileName не найден в списке",
                        status = ToolsStep.Status.FAILED
                    )
                )
            )
            AppLog.logAction(ctx, actionName, errRes)
            return errRes
        }

        // iter17: prefs via Prefs2
        val pathKey = if (isLock) PREF_LOCK_SOUND_PATH else PREF_UNLOCK_SOUND_PATH
        val nameKey = if (isLock) PREF_LOCK_SOUND_NAME else PREF_UNLOCK_SOUND_NAME
        val enableKey = if (isLock) PREF_LOCK_SOUND_ENABLED else PREF_UNLOCK_SOUND_ENABLED

        Prefs2.putString(ctx, pathKey, targetFile.absolutePath)
        Prefs2.putString(ctx, nameKey, fileName)
        Prefs2.putBoolean(ctx, enableKey, true)
        // §6.3 iter46: смена файла — новая генерация: задача, начатая со СТАРЫМ файлом, не должна
        // ни смонтировать старый звук, ни перезаписать новый выбор.
        bindGen++
        bindFailCount = 0
        bindDisabledUntilBoot = false
        markApplied(ctx, false, "selected_pending_apply")
        AppLog.i(ctx, TAG, "LOCK_SOUND_SELECT file=$fileName gen=$bindGen action=apply_pending")

        if (isLock) {
            // Выбор кастомного файла → пишем путь и enable=1.
            try {
                Settings.System.putString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_PATH, targetFile.absolutePath)
                Settings.System.putString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_ENABLE, "1")
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "Cannot write Settings.System lock path: ${t.message}")
            }
        }

        val res = ToolsResult(
            action = actionName,
            title = title,
            steps = listOf(
                ToolsStep(
                    step = "select",
                    command = "selectSound($fileName)",
                    startedAtMs = t0,
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    exitCode = 0,
                    stdout = "$fileName (${targetFile.length() / 1024} КБ)",
                    actual = fileName,
                    status = ToolsStep.Status.OK
                )
            )
        )
        AppLog.logAction(ctx, actionName, res)
        return res
    }

    fun selectFile(ctx: Context, filePath: String): ToolsResult {
        return selectFileByName(ctx, File(filePath).name, isLock = true)
    }

    fun selectUnlockFile(ctx: Context, filePath: String): ToolsResult {
        return selectFileByName(ctx, File(filePath).name, isLock = false)
    }

    // ──────────────────────────────────────────────────────────────
    // Воспроизведение через наружный динамик (AVAS). Важно:
    //   - на Geely IHU629G внешний динамик AVAS цепляется за USAGE=29
    //     (vendor STARSHIP, не Android SDK константа).
    //   - если 29 не сработал (ошибка setDataSource/prepare) — пробуем 73 (E5),
    //     затем 13 (assistance_sonification) — только в исключительном случае.
    //   - НЕ играем unlock-приветствие в кабину через USAGE_MEDIA: на EX2 это
    //     означает «звук в салоне вместо улицы», что владелец и слышит.
    // ──────────────────────────────────────────────────────────────
    private const val CAR_EXTERNAL_USAGE_STARSHIP = 29
    private const val CAR_EXTERNAL_USAGE_E5 = 73

    fun preview(ctx: Context): ToolsResult = playPreview(ctx, isLock = true)

    fun restoreFactory(ctx: Context): ToolsResult = restoreFactorySound(ctx, isLock = true)

    /**
     * Общий проигрыватель звука замка (lock + unlock общий, с задержкой и громкостью).
     * Задержка берётся из настроек; при delayMs=0 — играем сразу.
     * Событие и старт логируются с таймстампом для замера «событие→звук» на ГУ.
     * @param explicitFile если не null — использовать именно этот файл (вызывающий уже
     *                     определил путь/существование), иначе взять из настроек.
     */
    fun playSound(ctx: Context, isLock: Boolean, explicitFile: File? = null) {
        val enabled = if (isLock) isEnabled(ctx) else isUnlockSoundEnabled(ctx)
        if (!enabled) return

        val kind = if (isLock) "lock" else "unlock"
        // Дебаунс: не играть тот же вид звука чаще чем раз в DEBOUNCE_MS
        // (защита от дублей событий от разных датчиков/экранов).
        val now = SystemClock.elapsedRealtime()
        if (lastPlayKind == kind && (now - lastPlayAtMs) < DEBOUNCE_MS) {
            AppLog.i(ctx, TAG, "playSound[$kind] debounce (Δ=${now - lastPlayAtMs}ms < ${DEBOUNCE_MS}ms) — skip")
            return
        }
        lastPlayKind = kind
        lastPlayAtMs = now

        val file = when {
            explicitFile != null && explicitFile.exists() -> explicitFile
            else -> {
                val soundPath = if (isLock) getSelectedPath(ctx) else getUnlockSelectedPath(ctx)
                if (soundPath.isNotEmpty()) File(soundPath).takeIf { it.exists() } else null
            }
        }
        val volume = getVolume(ctx)
        val delayMs = getDelayMs(ctx).coerceAtLeast(0)
        val eventTs = now

        AppLog.i(ctx, TAG, "playSound[$kind] event at t=$eventTs, file=${file?.name ?: "OEM"}, vol=$volume%, delay=${delayMs}ms")

        val appCtx = ctx.applicationContext
        val runnable = Runnable {
            val startTs = SystemClock.elapsedRealtime()
            val delta = startTs - eventTs
            // Звук lock/unlock ИДЁТ ВО ВНЕШНИЙ AVAS-ДИНАМИК. НЕ дублировать в салон:
            // это вызывало «звук играет внутри при открытии окна/двери».
            // Звук ЗАКРЫТИЯ играется самим фреймворком Geely по lock_car_audio_path,
            // а наш вызов ниже — только для ОТКРЫТИЯ (isLock=false), но ТОЛЬКО через
            // внешний usage (29/73/13 fallback), не USAGE_MEDIA.
            val ok = playExternalSound(appCtx, file, volume)
            AppLog.i(ctx, TAG, "playSound[$kind] started Δt=${delta}ms (target delay=${delayMs}ms, external=$ok)")
        }
        if (delayMs == 0) {
            soundExecutor.execute(runnable)
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(runnable, delayMs.toLong())
        }
    }

    /** Совместимость: воспроизведение unlock-звука с явно переданным файлом
     *  (вызывается из WindowDoors.onDoorChanged и screenWakeReceiver). */
    fun playUnlockSound(ctx: Context, soundFile: File?) {
        playSound(ctx, isLock = false, explicitFile = soundFile)
    }

    fun playPreview(ctx: Context, isLock: Boolean): ToolsResult {
        val soundPath = if (isLock) getSelectedPath(ctx) else getUnlockSelectedPath(ctx)
        val file = if (soundPath.isNotEmpty()) File(soundPath) else null
        val ok = playCabinSound(ctx, file)

        return ToolsResult(
            action = if (isLock) "audio.preview" else "audio.previewUnlock",
            title = "Прослушивание звука",
            steps = listOf(
                ToolsStep(
                    step = "playback",
                    command = "playCabinSound(${file?.name ?: "OEM"})",
                    startedAtMs = SystemClock.elapsedRealtime(),
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    exitCode = if (ok) 0 else 1,
                    stdout = if (ok) "Воспроизведено в салоне" else "",
                    stderr = if (!ok) "Ошибка воспроизведения в салоне" else "",
                    status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
                    actual = if (ok) "Воспроизведено" else "Ошибка"
                )
            )
        )
    }

    /**
     * Воспроизведение звука в салонные динамики с текущей системной громкостью автомобиля.
     */
    fun playCabinSound(ctx: Context, soundFile: File?): Boolean {
        return try {
            mediaPlayer?.release()
            val mp = MediaPlayer()
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            mp.setAudioAttributes(audioAttributes)
            mp.setVolume(1.0f, 1.0f)

            if (soundFile != null && soundFile.exists()) {
                mp.setDataSource(soundFile.absolutePath)
            } else {
                val oemWav = File(OEM_CARLOCK_DEFAULT_WAV)
                if (oemWav.exists()) {
                    mp.setDataSource(oemWav.absolutePath)
                } else {
                    AppLog.w(ctx, TAG, "playCabinSound: файл звука не найден")
                    return false
                }
            }

            mp.prepare()
            mp.start()
            mp.setOnCompletionListener {
                it.release()
                if (mediaPlayer === it) mediaPlayer = null
            }
            mediaPlayer = mp
            AppLog.i(ctx, TAG, "playCabinSound: воспроизведение в салоне (${soundFile?.name ?: "OEM"})")
            true
        } catch (t: Throwable) {
            AppLog.e(ctx, TAG, "playCabinSound failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    fun restoreFactorySound(ctx: Context, isLock: Boolean): ToolsResult {
        // iter17: prefs via Prefs2
        val pathKey = if (isLock) PREF_LOCK_SOUND_PATH else PREF_UNLOCK_SOUND_PATH
        val nameKey = if (isLock) PREF_LOCK_SOUND_NAME else PREF_UNLOCK_SOUND_NAME
        val enableKey = if (isLock) PREF_LOCK_SOUND_ENABLED else PREF_UNLOCK_SOUND_ENABLED

        Prefs2.putString(ctx, pathKey, null)
        Prefs2.putString(ctx, nameKey, null)
        Prefs2.putBoolean(ctx, enableKey, false)

        if (isLock) {
            try {
                Settings.System.putString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_PATH, "")
                Settings.System.putString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_ENABLE, "1")
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "Cannot restore Settings.System: ${t.message}")
            }
        }

        val res = ToolsResult(
            action = if (isLock) "audio.restaurarSomTravaPersistente" else "audio.restoreFactoryUnlock",
            title = "Сброс к заводскому звуку",
            steps = listOf(
                ToolsStep(
                    step = "restore_oem",
                    command = "reset to factory OEM",
                    startedAtMs = SystemClock.elapsedRealtime(),
                    finishedAtMs = SystemClock.elapsedRealtime(),
                    exitCode = 0,
                    status = ToolsStep.Status.OK,
                    actual = "Восстановлен заводской звук"
                )
            )
        )
        AppLog.logAction(ctx, if (isLock) "audio.restaurarSomTravaPersistente" else "audio.restoreFactoryUnlock", res)
        return res
    }

    fun playExternalSound(ctx: Context, soundFile: File?, volumePercent: Int = DEFAULT_VOLUME): Boolean {
        // Решаем файл (явный → OEM)
        val target: File = when {
            soundFile != null && soundFile.exists() -> soundFile
            else -> {
                val oemWav = File(OEM_CARLOCK_DEFAULT_WAV)
                if (!oemWav.exists()) {
                    AppLog.w(ctx, TAG, "playExternalSound: OEM wav $OEM_CARLOCK_DEFAULT_WAV not found")
                    return false
                }
                oemWav
            }
        }
        val vol = (volumePercent.coerceIn(5, 100) / 100f)
        // Порядок попыток: 29 (Geely STARSHIP = AVAS внешний) → 73 (E5 запас) →
        // 13 (USAGE_ASSISTANCE_SONIFICATION — бипы, обычно тоже наружу).
        val usages = intArrayOf(
            CAR_EXTERNAL_USAGE_STARSHIP,
            CAR_EXTERNAL_USAGE_E5,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        )
        var lastErr: Throwable? = null
        for (usage in usages) {
            val mp = MediaPlayer()
            try {
                val attrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(usage)
                    .build()
                mp.setAudioAttributes(attrs)
                mp.setVolume(vol, vol)
                mp.setDataSource(target.absolutePath)
                mp.prepare()
                // Если дошли до start() без исключения — считаем успешным.
                mediaPlayer?.release()
                mp.start()
                mp.setOnCompletionListener {
                    it.release()
                    if (mediaPlayer === it) mediaPlayer = null
                }
                mediaPlayer = mp
                AppLog.i(ctx, TAG, "playExternalSound: playing ${target.name} vol=$volumePercent% usage=$usage (AVAS external)")
                return true
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "playExternalSound: usage=$usage failed: ${t.javaClass.simpleName}: ${t.message}")
                lastErr = t
                runCatching { mp.release() }
            }
        }
        AppLog.e(ctx, TAG, "playExternalSound: all usages failed for ${target.name}: ${lastErr?.message}", lastErr)
        return false
    }

    fun onBootOrWatchdog(ctx: Context) {
        // §6.2 iter46: boot — подходящее событие, сбрасываем «до boot»-блокировку и счётчик.
        bindDisabledUntilBoot = false
        bindFailCount = 0
        bindGen++
        val lockEnabled = isEnabled(ctx)
        val unlockEnabled = isUnlockSoundEnabled(ctx)
        AppLog.i(ctx, TAG, "onBootOrWatchdog: проверка звуков замка (lock=$lockEnabled unlock=$unlockEnabled) vol=${getVolume(ctx)}% delay=${getDelayMs(ctx)}ms")
        // iter52 §5.1(3): сначала СВЕРКА незавершённых операций из журнала (после падения/
        // потери ответа на устройстве мог остаться наш слой), потом обычное применение.
        runCatching { reconcilePendingOps(ctx, "boot_or_watchdog") }
        if (lockEnabled) {
            val path = getSelectedPath(ctx)
            // OEM-логика при boot: пишем путь в lock_car_audio_path и enable=1
            // (чтобы фреймворк играл кастомный звук вместо заводского).
            try {
                if (path.isNotEmpty() && File(path).exists()) {
                    Settings.System.putString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_PATH, path)
                }
                Settings.System.putString(ctx.contentResolver, SETTINGS_LOCK_CAR_AUDIO_ENABLE, "1")
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "onBootOrWatchdog write settings failed: ${t.message}")
            }
            // Bind-mount сбрасывается при перезагрузке — переставляем его заново
            // на сохранённый кастомный файл (как CentralWatchdogService.v()).
            if (path.isNotEmpty()) {
                val f = File(path)
                if (f.exists()) applySystemLockSoundBind(ctx, f)
            }
        } else {
            // iter33h A1.4: при выключенном тумблере приложение не трогает систему.
            // Единственное исключение — снять СВОИ маунты, список которых оно само записало
            // в files/locksound_mounts.txt, и только в /vendor/etc/carlock/.
            AppLog.i(ctx, TAG, "LOCKSOUND_BOOT enabled=false action=none")
            if (readOwnMounts(ctx).isNotEmpty()) runCatching { restoreSystemLockSoundBind(ctx) }
        }
        if (unlockEnabled) {
            // Звук открытия — наш проигрыватель (не OEM-механизм), просто пишем путь
            // в Settings.System для симметрии и консистентности.
            val uPath = getUnlockSelectedPath(ctx)
            try {
                if (uPath.isNotEmpty() && File(uPath).exists()) {
                    Settings.System.putString(ctx.contentResolver, SETTINGS_UNLOCK_CAR_AUDIO_PATH, uPath)
                }
                Settings.System.putString(ctx.contentResolver, SETTINGS_UNLOCK_CAR_AUDIO_ENABLE, "1")
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "onBootOrWatchdog unlock write failed: ${t.message}")
            }
        }
        // ВАЖНО: подписка на события дверей (для звука открытия + filete de ar)
        // инициируется из VoiceAssistantService.onCreate ТОЛЬКО если включены
        // тумблеры окон или звук открытия — см. EXTRA_BOOT_RESTORE в сервисе.
        // Не вызываем initCarService здесь безусловно, чтобы не открывать окна
        // при выключенных тумблерах (баг 5.1).
    }

    /**
     * P7 — bind-mount кастомного звука замка в /vendor/etc/carlock.
     *
     * Исправления по логу:
     *  1. Копирование wav в /data/local/tmp — ЧЕРЕЗ ADB (base64 pipe), а не Java
     *     copyTo от uid=1000 (на что было Permission denied).
     *  2. Нет бесконечных ретраев: после MAX_BIND_RETRIES=3 детерминированных
     *     ошибок (Permission/ENOENT/ERR_ROOT_CHANNEL_UNAVAILABLE) — отключаем
     *     тумблер и больше не штурмим до ребута.
     *  3. setenforce 0 убран (P5).
     *  4. VERIFY по SRC_INODE/TGT_INODE и по /proc/mounts.
     */
    /**
     * iter55 §7.1: причина, по которой последний bind не был отправлен (сверка не разрешила).
     * Показывается владельцу как нейтральный статус «ждёт сверки», без красного и без ложного ON.
     */
    @Volatile
    internal var lastReconcilePendingReason: String? = null

    private fun applySystemLockSoundBind(ctx: Context, soundFile: File, reconcileFirst: Boolean = true) {
        // §6.2 iter46: «до boot» больше не блокирует бессрочно — только до подходящего события;
        // флаг оставлен для совместимости и снимается в onConditionsRestored/смене выбора.
        if (bindDisabledUntilBoot) {
            AppLog.i(ctx, TAG, "VERIFY lockSound: skip (ждёт подходящего события, reason=$lastBindFailReason)")
            return
        }
        // §6.3: применение только для актуальной генерации и при сохранённом ON.
        if (!isEnabled(ctx)) {
            AppLog.i(ctx, TAG, "VERIFY lockSound: skip reason=desired_off (владелец выключил)")
            return
        }
        val gen = bindGen
        val appCtx = ctx.applicationContext
        // P1: всё что ниже — ADB/mount, на BgWorker а не на main.
        BgWorker.post("LockSound.bind") {
            // iter52 §5.1(2): незавершённую операцию нельзя перетирать новой. Сверяем журнал
            // ДО записи собственного PLANNED; если сверка показала чужой/недоказанный слой —
            // он останется в журнале как UNDEFINED, и мы это увидим в логе, а не потеряем.
            // reconcileFirst=false — это вызов ИЗ сверки: иначе сверка → применение → сверка
            // зацикливается на записи, которую снять нельзя (чужой слой).
            gateEnter("bind")
            try {
                if (reconcileFirst) {
                    // iter55 §7.1: результат сверки УПРАВЛЯЕТ отправкой. Раньше он (и исключение)
                    // игнорировался `runCatching`: bind уходил даже при нечитаемом/повреждённом
                    // журнале и недоказанном исходе прошлой операции, а старый shell мог
                    // выполниться позже и наложить свой слой поверх нового.
                    val recovery = runCatching { reconcilePendingOps(appCtx, "before_bind") }
                        .getOrElse { t ->
                            LockOp.SoundRecovery.Conflict("reconcile_threw:" + t.javaClass.simpleName)
                        }
                    when (recovery) {
                        is LockOp.SoundRecovery.Ready -> Unit
                        is LockOp.SoundRecovery.Pending -> {
                            AppLog.w(appCtx, TAG, "LOCKSOUND_BIND DEFERRED op=… ${recovery.wire()} " +
                                "action=no_send (исход прошлой операции не доказан: " +
                                "свидетельство ${recovery.evidence.ifEmpty { "none" }}, повтор после сверки)")
                            recordBindFailure("reconcile_pending:" + recovery.reason, appCtx, deterministic = false)
                            lastReconcilePendingReason = recovery.reason
                            return@post
                        }
                        is LockOp.SoundRecovery.Conflict -> {
                            AppLog.e(appCtx, TAG, "LOCKSOUND_BIND BLOCKED ${recovery.wire()} " +
                                "action=no_send (журнал/слой не разрешён: намерение сохранено, " +
                                "новый bind не отправляется)")
                            recordBindFailure("reconcile_conflict:" + recovery.reason, appCtx, deterministic = false)
                            markUnknown(appCtx, "recovery_conflict:" + recovery.reason)
                            lastReconcilePendingReason = recovery.reason
                            return@post
                        }
                    }
                }
                // §5.2 iter47: staging-файл УНИКАЛЕН для (поколение + содержимое). Раньше все
                // задачи писали в один и тот же путь: параллельная задача могла подменить файл
                // между копированием и bind, и на цель монтировался ЧУЖОЙ звук.
                val ourMountsBefore = readOwnMounts(ctx)
                val bytes0 = runCatching { soundFile.readBytes() }.getOrNull()
                if (bytes0 == null) {
                    recordBindFailure("source file unreadable: ${soundFile.name}", appCtx, deterministic = true)
                    return@post
                }
                val contentHash = String.format("%08x",
                    java.util.zip.CRC32().apply { update(bytes0) }.value)
                val stagePath = "/data/local/tmp/carlock_custom_g${gen}_$contentHash.wav"
                if (!soundFile.exists() || !soundFile.canRead()) {
                    recordBindFailure("source file unreadable: ${soundFile.absolutePath}", appCtx, deterministic = true)
                    return@post
                }
                AppLog.i(ctx, TAG, "lockSound bind: copying ${soundFile.name} via ADB root channel to $stagePath...")

                // P7: копируем через ADB (base64). Java uid=1000 прав на /data/local/tmp нет.
                val bytes = soundFile.readBytes()
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val copyScript = """
                    cat > '$stagePath'.b64 <<'___B64_EOF___'
                    $b64
                    ___B64_EOF___
                    base64 -d '$stagePath'.b64 > '$stagePath' 2>&1 || echo B64_DECODE_FAIL
                    rm -f '$stagePath'.b64
                    chown 0:0 '$stagePath' 2>&1 || echo CHOWN_FAIL
                    chmod 644 '$stagePath' 2>&1 || true
                    chcon u:object_r:vendor_configs_file:s0 '$stagePath' 2>/dev/null || chcon u:object_r:vendor_file:s0 '$stagePath' 2>/dev/null || true
                    echo STAGE_SIZE=${'$'}(stat -c '%s' '$stagePath' 2>/dev/null)
                    echo STAGE_INODE=${'$'}(stat -c '%i' '$stagePath' 2>/dev/null)
                """.trimIndent()
                // iter33 B3: 286 КБ base64 одной командой не лезли в лимит payload 4096
                // (`ERR_ADB_TOO_LONG: cmd_len=286595 > 4096`) и звук не вставал никогда.
                // Теперь — тот же исправленный executeScript (base64-чанки, A0).
                val copyRes = if (copyScript.toByteArray(Charsets.UTF_8).size > 3500)
                    AdbClient.executeScript("lock_wav", copyScript, 40000)
                else AdbClient.execute(copyScript, 30000)
                AppLog.i(ctx, TAG, "lockSound copy rc=${copyRes.first}: ${copyRes.second.replace('\n', ' ').take(400)}")
                if (copyRes.first != 0) {
                    recordBindFailure("copy failed rc=${copyRes.first} out=${copyRes.second.take(200)}", appCtx,
                        deterministic = copyRes.second.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")
                                    || copyRes.second.contains("Permission denied"))
                    return@post
                }

                val stageSizeOk = copyRes.second.contains("STAGE_SIZE=") &&
                    !copyRes.second.contains("STAGE_SIZE=0") &&
                    !copyRes.second.contains("B64_DECODE_FAIL")
                if (!stageSizeOk) {
                    recordBindFailure("stage file empty/b64 decode fail", appCtx, deterministic = false)
                    return@post
                }

                // iter48 §В: скрипт вынесен в чистый [LockMountScripts] — владение слоем
                // доказывается по /proc/self/mountinfo (mount ID + root), а не по первому полю
                // /proc/mounts (там устройство ФС). `umount … || true` внутри больше нет:
                // rc фиксируется, результат перечитывается, «снято» заявляется только по факту.
                val bindScript = LockMountScripts.bindScript(stagePath)
                // iter33h A1.5: перед любой записью маунта — проверка пути. Скрипт трогает только
                // /vendor/etc/carlock/*.wav; если глоб вдруг вернёт что-то другое, это отсекается здесь.
                // iter51 §3.2: список целей читается ДО монтирования и с проверкой rc. Раньше
                // любой отказ `ls` давал пустой список, из которого нельзя ни доказать полноту,
                // ни отличить «целей нет» от «прочитать нельзя» — bind продолжался «вслепую».
                val lsRes = runCatching {
                    AdbClient.execute("ls -1 /vendor/etc/carlock/*.wav 2>/dev/null", 6000, expectRoot = false)
                }.getOrNull()
                val glideTargets = lsRes?.second.orEmpty().lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() && !it.contains(": ") }.toList()
                val targetsReadable = lsRes != null && lsRes.first == 0 && glideTargets.isNotEmpty()
                if (!targetsReadable) {
                    AppLog.w(ctx, TAG, "LOCKSOUND_BIND SKIP reason=targets_unreadable " +
                        "rc=${lsRes?.first ?: "none"} parsed=${glideTargets.size} action=none " +
                        "(набор целей не доказан — монтировать вслепую нельзя)")
                    recordBindFailure("targets_unreadable rc=${lsRes?.first ?: "none"}", appCtx,
                        deterministic = false)
                    return@post
                }
                val notOurs = glideTargets.filter { !isOurLockPath(it) }
                if (notOurs.isNotEmpty()) {
                    AppLog.i(ctx, TAG, "LOCKSOUND_BIND SKIP reason=path_not_ours paths=${notOurs.joinToString(",")}")
                    recordBindFailure("path_not_ours: ${notOurs.first()}", appCtx, deterministic = true)
                    return@post
                }
                // §6.3 iter46: пока шло копирование, владелец мог выключить звук или выбрать
                // другой файл — тогда старый bind НЕ выполняется (иначе OFF «отменялся» бы
                // сам собой, а новый выбор затирался старым).
                if (gen != bindGen || !isEnabled(appCtx)) {
                    AppLog.i(ctx, TAG, "LOCKSOUND_BIND_CANCELLED gen=$gen now=$bindGen " +
                        "desired=${isEnabled(appCtx)} action=none " +
                        "(решение владельца изменилось во время копирования)")
                    return@post
                }
                // iter51 §3.1: bind-скрипт ~8.9 КБ. Прежний `AdbClient.execute(bindScript, …)` в
                // ветке ROOT_5555 отклонял payload > 4096 (`ERR_ADB_TOO_LONG`) ДО отправки —
                // звук не мог включиться в принципе, а снаружи это выглядело как «mount не удался».
                // Теперь скрипт доставляется через executeScript (чанки + сверка размера + `sh`),
                // и при неудачной заливке mount не запускается вовсе.
                // iter51 §3.3: ЖУРНАЛ ОПЕРАЦИИ ДО side effect. Раньше запись появлялась только
                // после ответа: потеря ответа/смерть процесса оставляла реально созданные слои
                // без учёта, и снять их было нечем. Если запись не подтверждена чтением —
                // bind не отправляется (иначе возможно необратимое неучтённое монтирование).
                val opId = "bind_" + gen + "_" + Stage0.hash(stagePath)
                // iter57 §5.1: IDENTITY И СВИДЕТЕЛЬСТВО — ДО ЖУРНАЛА И ДО ОТПРАВКИ.
                // Раньше запись была `PLANNED` без пути, путь появлялся только из результата
                // доставки: смерть процесса между отправкой и возвратом оставляла возможный
                // старый shell без адреса, по которому его terminal можно дочитать.
                val prepared = ScriptDelivery.prepare("lock_bind", bindScript)
                val op = LockOp.BindOp(
                    id = opId, gen = gen, desired = true, stage = stagePath,
                    targets = glideTargets, state = LockOp.State.PLANNED,
                    evidence = prepared.evidencePath
                )
                val journalOk = writeOpJournal(appCtx, op)
                if (!journalOk) {
                    AppLog.e(ctx, TAG, "LOCKSOUND_BIND SKIP reason=journal_not_confirmed " +
                        "op=$opId action=none (учёт не записан — необратимый bind не отправляем)")
                    recordBindFailure("journal_not_confirmed", appCtx, deterministic = false)
                    return@post
                }
                AppLog.i(ctx, TAG, "LOCKSOUND_OP planned ${op.wire()} evidence=${op.evidence} " +
                    "(запись журнала подтверждена чтением ДО mount; путь-свидетельство известен " +
                    "ДО отправки; fsync не заявляется)")
                val bindPlan = ScriptDelivery.plan("lock_bind", bindScript)
                AppLog.i(ctx, TAG, "LOCKSOUND_BIND TRANSPORT ${bindPlan.wire()} " +
                    "(длина измерена по фактическим UTF-8 байтам, не по оценке)")
                // iter54 §5: bind — операция с НЕОБРАТИМЫМ side effect (mount), поэтому её
                // исход обязан доходить сюда тремя различимыми состояниями, а не `rc`.
                // Раньше `RUN_UNKNOWN` терялся: «ответ потерян» и «не выполнено» выглядели
                // одинаково, и вызывающий код мог либо повторить bind, либо заявить успех.
                // Отправляется РОВНО подготовленный nonce: свидетельство в журнале и адрес на
                // устройстве совпадают, повторной «скрытой» identity не возникает.
                val bindDetailed = AdbClient.executePrepared(prepared, 30000)
                val bindRes = Pair(bindDetailed.rc, bindDetailed.out)
                val bindOutcome = ScriptDelivery.outcome(bindDetailed, opId)
                AppLog.i(ctx, TAG, "lockSound bind rc=${bindRes.first}: ${bindRes.second.replace('\n', ' ').take(500)}")
                AppLog.i(ctx, TAG, "LOCKSOUND_BIND_OUTCOME op=$opId ${bindOutcome.wire()} " +
                    "(finished ≠ «слой встал»: факт даёт MOUNT_FACT/MOUNTED_OK, а не ответ канала)")
                when (bindOutcome) {
                    is ScriptDelivery.DeliveryOutcome.NotSent -> {
                        // Доказано: ни один байт скрипта не исполнялся. Учёт операции закрываем
                        // как НЕ применённый (это не «ошибка mount»), и только здесь retry
                        // безопасен — но и он решается владельцем/следующим проходом.
                        finishOpJournal(ctx, op, LockOp.State.FAILED,
                            "bind_not_sent:" + bindOutcome.why)
                        AppLog.w(ctx, TAG, "LOCKSOUND_BIND NOT_SENT op=$opId why=${bindOutcome.why} " +
                            "action=no_effect_retry_safe (слои не создавались)")
                        recordBindFailure("not_sent:" + bindOutcome.why, appCtx, deterministic = false)
                        return@post
                    }
                    is ScriptDelivery.DeliveryOutcome.Unknown -> {
                        // Могло исполниться: НЕ заявляем ни успех, ни «не выполнено», НЕ
                        // повторяем вслепую. Свидетельство (путь скрипта и .started/.done)
                        // сохраняется в журнале, поздняя сверка дочитает факт.
                        // Состояние UNDEFINED — «исход не установлен»: сверка по свидетельству
                        // (evidence) дочитает факт, а не «не выполнено».
                        // iter55 §5.2: путь-свидетельство СОХРАНЯЕТСЯ в журнале (durable), а не
                        // только в строке лога: после рестарта процесса именно по нему идёт
                        // read-only сверка, а не «забыли про операцию».
                        val ev = (bindOutcome as ScriptDelivery.DeliveryOutcome.Unknown)
                            .evidence.path.ifEmpty { op.evidence }
                        finishOpJournal(ctx, op.copy(evidence = ev), LockOp.State.UNDEFINED,
                            "bind_outcome_unknown:" + bindOutcome.why + " ev=" + ev.ifEmpty { "none" })
                        AppLog.w(ctx, TAG, "LOCKSOUND_BIND UNKNOWN op=$opId ${bindOutcome.wire()} " +
                            "action=pending_no_retry (исход станет известен из свидетельства)")
                        recordBindFailure("outcome_unknown:" + bindOutcome.why, appCtx, deterministic = false)
                        return@post
                    }
                    is ScriptDelivery.DeliveryOutcome.Finished -> Unit
                }

                // §6.2 iter46: `ERR_ROOT_CHANNEL_UNAVAILABLE` и `Permission denied` — ВРЕМЕННЫЕ
                // (канал/права могут вернуться без перезагрузки: в логе владельца 13.11 это
                // `copy failed rc=1 out=ERR_SCRIPT_UPLOAD_CLEA…`, а root позже снова работал).
                // Неисправимыми считаем только отсутствие самого файла и провал проверки
                // монтирования при успешном rc.
                val tempRootGone = bindRes.second.contains("ERR_ROOT_CHANNEL_UNAVAILABLE") ||
                    bindRes.second.contains("Permission denied") || bindRes.second.contains("avc: denied")
                val mountNsMismatch = bindRes.second.contains("MOUNT_NS_MISMATCH")
                val failDeterm = (!tempRootGone && bindRes.second.contains("No such file"))
                    || (bindRes.second.contains("MOUNT_VERIFY_FAIL") && bindRes.first == 0 &&
                        !bindRes.second.contains("MOUNTED_OK:"))
                // §В iter48: «MOUNTED_OK» печатается ТОЛЬКО когда mountinfo подтвердил корень
                // нашего staging-файла и inode цели; само наличие строки успехом не является.
                // Несовпадение namespace монтирования означает, что система нашего bind не
                // увидит — «применено» в этом случае не заявляется.
                val ok = bindRes.first == 0 && bindRes.second.contains("MOUNTED_OK:") &&
                    !failDeterm && !mountNsMismatch
                if (mountNsMismatch) {
                    AppLog.w(ctx, TAG, "LOCKSOUND_BIND namespace init ≠ namespace shell: " +
                        "bind не виден системе — applied не заявляется")
                }

                // §5.1 iter47: скрипт уже УШЁЛ, и во время его выполнения владелец мог выключить
                // звук или выбрать другой WAV. Проверка флага ДО вызова такой случай не ловит,
                // а «отменить отправленную команду» невозможно. Поэтому после ответа
                // перепроверяем намерение и поколение, и доводим систему до АКТУАЛЬНОГО
                // состояния: OFF → снимаем только свои слои, другой файл → новый проход.
                val genChanged = bindGen != gen
                val desiredOff = !isEnabled(appCtx)
                // §В iter48: ФАКТЫ созданных слоёв пишутся в реестр ДО любой сходимости.
                // Раньше реестр заполнялся только в «счастливой» ветке: если во время bind
                // владелец выключил звук, слои уже стояли, а реестр о них не знал — снять их
                // потом было нечем (и «своё» оставалось висеть до перезагрузки).
                // iter50 §4.3: реестр пополняется ФАКТАМИ `MOUNT_FACT` (печатаются после
                // КАЖДОГО успешного mount), а не только строками `MOUNTED_OK`. Раньше отказ
                // проверки (в т.ч. дефект разбора, найденный в этой итерации) оставлял реально
                // созданный слой без учёта — снять его потом было нечем.
                val allFacts = MountInfo.parseMountFacts(bindRes.second)
                val mountedFacts = allFacts
                    .filter { isOurLockPath(it.target) }
                    .map { f -> OwnMount(f.target, f.source.ifEmpty { stagePath }, gen) }
                if (allFacts.isNotEmpty()) {
                    val merged = mergeOwnMounts(readOwnMounts(ctx), mountedFacts)
                    writeOwnMounts(ctx, merged)
                    AppLog.i(ctx, TAG, "LOCKSOUND_REGISTRY write_before_converge gen=$gen " +
                        "facts=${allFacts.size} verified=${allFacts.count { it.state == MountInfo.FactState.VERIFIED }} " +
                        "unverified=${allFacts.count { it.state == MountInfo.FactState.UNVERIFIED }} " +
                        "unknown=${allFacts.count { it.state == MountInfo.FactState.UNKNOWN }} " +
                        "total=${merged.size} " +
                        "(факт слоя записан до любых дальнейших действий; pending не выдаётся за успех)")
                    for (f in allFacts) {
                        AppLog.i(ctx, TAG, "LOCKSOUND_MOUNT_FACT target_is_ours=${isOurLockPath(f.target)} " +
                            f.wire())
                    }
                }
                // iter51 §3.3(3): сходимость выполняется и при НЕИЗВЕСТНОМ/частичном результате
                // bind, если владелец за это время изменил решение. Раньше она была только при
                // `ok`: выключение звука во время неудачного/неизвестного прохода могло оставить
                // включённым то, что владелец уже выключил.
                if (genChanged || desiredOff) {
                    // Факт для ЭТОГО поколения не публикуем: он относится к устаревшему выбору.
                    markApplied(appCtx, false, if (desiredOff) "desired_off_after_bind" else "superseded")
                    finishOpJournal(appCtx, op, LockOp.State.UNDEFINED,
                        if (desiredOff) "desired_off_during_run" else "superseded_during_run")
                    AppLog.i(ctx, TAG, "LOCKSOUND_BIND_STALE gen=$gen now=$bindGen desired=${!desiredOff} " +
                        "own_state=${if (ok) "ok" else "unknown"} " +
                        "result=converge (намерение изменилось, пока скрипт выполнялся; applied для " +
                        "устаревшего выбора не заявляется; реестр пополнен фактами)")
                    cleanupStage(ctx, stagePath)
                    convergeAfterBind(appCtx, desiredOff)
                    return@post
                }
                if (ok) {
                    bindFailCount = 0
                    lastBindFailReason = null
                    val mounted = mountedFacts
                    writeOwnMounts(ctx, mergeOwnMounts(readOwnMounts(ctx), mounted))
                    // iter51 §3.2: доказательство ПО КАЖДОЙ цели. Раньше `complete` сравнивал
                    // число ФАКТОВ (в т.ч. unverified/unknown) с числом целей, поэтому
                    // «одна verified + одна unknown» давала applied=true. Теперь applied только
                    // когда на каждую ожидаемую цель есть ровно один VERIFIED-факт, а лишних и
                    // дублированных целей нет.
                    val ev = LockOp.evidence(glideTargets, allFacts, targetsReadable)
                    AppLog.i(ctx, TAG, LockOp.evidenceLine("bind", ev))
                    val complete = ev.complete
                    finishOpJournal(appCtx, op,
                        if (complete) LockOp.State.DONE else LockOp.State.UNDEFINED,
                        if (complete) "" else ev.reason)
                    markApplied(ctx, complete, if (complete) "" else
                        "partial:${ev.reason} verified=${ev.verified}/${ev.expected}")
                    AppLog.i(ctx, TAG, "VERIFY lockSound: ${if (complete) "SUCCESS" else "PARTIAL"} " +
                        "verified=${ev.verified}/${ev.expected} unverified=${ev.unverified} " +
                        "unknown=${ev.unknown} missing=${ev.missing.size} extra=${ev.extra.size} " +
                        "paths=${mounted.joinToString(",") { it.target }} " +
                        "(наличие файла, Settings и mount readback — разные факты; слышимость " +
                        "подтверждает только владелец)")
                } else {
                    val reason = if (bindRes.second.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")) "root unavailable"
                                 else if (bindRes.second.contains("Permission denied")) "Permission denied"
                                 else "rc=${bindRes.first}: ${bindRes.second.take(200)}"
                    // iter51 §3.1/§3.3: если скрипт НЕ доехал (ошибка заливки) — операции не было;
                    // если доехал, но ответ неуспешный/потерян — состояние НЕИЗВЕСТНО, а не «не
                    // выполнялось»: часть слоёв могла встать, и они уже записаны фактами выше.
                    val uploadFailed = bindRes.second.contains("ERR_SCRIPT_UPLOAD") ||
                        bindRes.second.contains("ERR_SCRIPT_TOO_LARGE") ||
                        bindRes.second.contains("ERR_ADB_TOO_LONG")
                    finishOpJournal(appCtx, op,
                        if (uploadFailed) LockOp.State.FAILED else LockOp.State.UNDEFINED,
                        if (uploadFailed) "upload_failed" else "outcome_unknown")
                    recordBindFailure(reason, appCtx, deterministic = failDeterm)
                }
                cleanupStage(ctx, stagePath)
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "applySystemLockSoundBind error: ${t.message}", t)
                recordBindFailure("exception: ${t.message}", appCtx, deterministic = false)
            } finally {
                gateExit()
            }
        }
    }

    /**
     * §5.2 iter47: staging-файл этого поколения удаляется после использования — иначе
     * `/data/local/tmp` копил бы копии WAV, а «старый» файл мог быть смонтирован вместо нового.
     */
    private fun cleanupStage(ctx: Context, stagePath: String) {
        runCatching {
            BgWorker.post("LockSound.cleanupStage") {
                runCatching { AdbClient.execute("rm -f '$stagePath' 2>/dev/null || true", 8000) }
            }
        }
    }

    /**
     * §5.1 iter47: привести систему к АКТУАЛЬНОМУ намерению после того, как уже отправленный
     * bind завершился. Ровно один проход: OFF → снять свои слои; другой файл → новый bind
     * (у него будет своё поколение). Рекурсии нет — второй проход видит стабильное поколение.
     */
    private fun convergeAfterBind(ctx: Context, desiredOff: Boolean) {
        if (convergePending) {
            AppLog.i(ctx, TAG, "LOCKSOUND_CONVERGE skip reason=already_pending " +
                "(сходимость уже запланирована: без лавины задач)")
            return
        }
        convergePending = true
        BgWorker.post("LockSound.converge") {
            try {
                if (desiredOff || !isEnabled(ctx)) {
                    AppLog.i(ctx, TAG, "LOCKSOUND_CONVERGE action=restore_stock " +
                        "(звук выключен владельцем: снимаем ТОЛЬКО свои слои)")
                    restoreSystemLockSoundBind(ctx)
                    markApplied(ctx, false, "off")
                } else {
                    val path = getSelectedPath(ctx)
                    if (path.isNotEmpty() && File(path).exists()) {
                        AppLog.i(ctx, TAG, "LOCKSOUND_CONVERGE action=apply_new_selection file=${File(path).name}")
                        applySystemLockSoundBind(ctx, File(path))
                    } else {
                        AppLog.i(ctx, TAG, "LOCKSOUND_CONVERGE action=none reason=no_file_selected")
                        markApplied(ctx, false, "no_file_selected")
                    }
                }
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "LOCKSOUND_CONVERGE error: ${t.message}", t)
            } finally {
                convergePending = false
            }
        }
    }

    @Volatile private var convergePending = false

    private fun recordBindFailure(reason: String, ctx: Context, deterministic: Boolean) {
        lastBindFailReason = reason
        AppLog.e(ctx, TAG, "VERIFY lockSound: FAILED ($reason), deterministic=$deterministic")
        bindFailCount += 1
        markApplied(ctx, false, reason)
        // §6.2 iter46: ОГРАНИЧЕННЫЙ повтор с backoff. Раньше здесь ставился «замок до boot»,
        // который никто не снимал: один временный отказ root-канала блокировал звук до
        // перезагрузки (и в логе владельца именно так и выглядело). Теперь:
        //  • каждая неудача планирует ОДИН повтор (не чаще, чем раз в [BACKOFF_MS][bindFailCount]);
        //  • после [MAX_BIND_RETRIES] попыток остаётся честный `pending` с причиной — но НЕ
        //    бессрочная блокировка: подходящее событие (включение функции, смена файла,
        //    возврат канала, boot) сбрасывает счётчик и пробует снова;
        //  • OFF владельца отменяет всё: [bindGen] меняется и запланированный повтор не сработает.
        if (bindFailCount <= MAX_BIND_RETRIES) {
            val attempt = bindFailCount
            val delay = backoffMs(attempt)
            val gen = bindGen
            AppLog.i(ctx, TAG, "lockSound retry scheduled attempt=$attempt/$MAX_BIND_RETRIES delay_ms=$delay " +
                "reason=«$reason» (намерение владельца сохраняется)")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Повтор только если владелец всё ещё хочет звук и выбор не изменился.
                if (gen != bindGen) {
                    AppLog.i(ctx, TAG, "lockSound retry cancelled generation_changed " +
                        "(OFF/смена файла были позже — старую задачу не выполняем)")
                } else if (!isEnabled(ctx)) {
                    AppLog.i(ctx, TAG, "lockSound retry cancelled desired=false")
                } else {
                    val path = getSelectedPath(ctx)
                    if (path.isNotEmpty() && File(path).exists()) applySystemLockSoundBind(ctx, File(path))
                    else AppLog.i(ctx, TAG, "lockSound retry skipped reason=no_file_selected")
                }
            }, delay)
            // §6.2: подписка на возврат канала — без перезагрузки ГУ.
            armRetryOnChannelReady(ctx)
        } else {
            armRetryOnChannelReady(ctx)
            AppLog.e(ctx, TAG, "lockSound применение=pending после $MAX_BIND_RETRIES попыток: $reason " +
                "(не блокируем до boot: повтор — по событию «канал готов»/включение/смена файла/boot; " +
                "намерение владельца сохранено)")
        }
    }

    /** Пауза перед повтором: 5/10/20/40 с, без бесконечного роста. */
    internal fun backoffMs(attempt: Int): Long =
        5_000L * (1 shl (attempt - 1).coerceIn(0, 3))

    /**
     * §6.2 iter46: подписка на ОДНО существующее событие «канал root готов».
     *
     * Это не новый сторож и не цикл: обработчик снимается при первом срабатывании. Нужен,
     * чтобы временный отказ root-канала не превращался в ожидание до перезагрузки ГУ
     * (в логе владельца 13.11 root позже снова работал без ребута).
     */
    fun armRetryOnChannelReady(ctx: Context) {
        if (!isEnabled(ctx)) return
        val app = ctx.applicationContext
        runCatching {
            AdbClient.onChannelReadyOnce {
                BgWorker.post("LockSound.channelReady") {
                    if (isEnabled(app) && appliedState(app) != "applied") {
                        onConditionsRestored(app, "channel_ready")
                    }
                }
            }
            AppLog.i(app, TAG, "LOCKSOUND_RETRY armed on=channel_ready desired=true " +
                "applied=${appliedState(app)}")
        }
    }

    /**
     * §6.2 iter46: «условия вернулись» — сбрасываем счётчик и блокировку и пробуем применить
     * ещё раз (без нового штурма: одна попытка на событие).
     */
    fun onConditionsRestored(ctx: Context, source: String) {
        if (!isEnabled(ctx)) return
        // iter52 §5.1(3): «условия вернулись» — тоже точка сверки незавершённых операций.
        runCatching { reconcilePendingOps(ctx.applicationContext, "conditions_restored:$source") }
        bindFailCount = 0
        bindDisabledUntilBoot = false
        bindGen++
        val path = getSelectedPath(ctx)
        AppLog.i(ctx, TAG, "LOCKSOUND_RETRY source=$source desired=true applied=${appliedState(ctx)} " +
            "file=${if (path.isEmpty()) "none" else "selected"}")
        if (path.isNotEmpty() && File(path).exists()) applySystemLockSoundBind(ctx, File(path))
    }

    /**
     * iter33h A1.4 — снятие ТОЛЬКО своих маунтов.
     *
     * Список путей, которые приложение само смонтировало, лежит в `files/locksound_mounts.txt`
     * (по строке на путь). Снимаем ровно то, что в нём, и только если путь проходит [isOurLockPath].
     * Чужие маунты и /system/etc/avas/… не трогаем никогда: при выключенном тумблере приложение
     * не делает с системой ничего.
     */
    private fun restoreSystemLockSoundBind(ctx: Context) {
        soundExecutor.execute {
            gateEnter("restore")
            try {
                val ours = readOwnMounts(ctx)
                val entries = ours.filter { isOurLockPath(it.target) }
                val skipped = ours.filterNot { isOurLockPath(it.target) }.map { it.target }
                if (skipped.isNotEmpty()) {
                    AppLog.i(ctx, TAG, "LOCKSOUND_UNMOUNT SKIP reason=path_not_ours paths=" + skipped.joinToString(","))
                }
                if (entries.isEmpty()) {
                    AppLog.i(ctx, TAG, "LOCKSOUND_UNMOUNT action=none reason=registry_empty " +
                        "(своих слоёв в реестре нет; чужие не трогаем)")
                    return@execute
                }
                // §В iter48: скрипт снятия вынесен и проверяется тестами. Внутри:
                //  • владение слоем доказывается по mountinfo (root = наш staging), не по /proc/mounts;
                //  • чужой верхний слой не снимается;
                //  • каждый umount проверяется перечитыванием mountinfo; `|| true` больше нет.
                // iter49 §4: проверка пространства монтирования — ДО любого действия.
                // Раньше namespace выяснялся уже ПОСЛЕ выполнения скрипта: снятие в чужом
                // пространстве имён выглядело как «команда выполнена», а система слой не теряла.
                val nsPre = runCatching {
                    AdbClient.execute(
                        "echo NS_PRE self=\$(readlink /proc/self/ns/mnt 2>/dev/null) " +
                            "init=\$(readlink /proc/1/ns/mnt 2>/dev/null)", 6000
                    ).second
                }.getOrDefault("")
                val nsPreMatch = Regex("NS_PRE self=(\\S+) init=(\\S+)").find(nsPre)
                val nsSame = nsPreMatch != null &&
                    nsPreMatch.groupValues[1] == nsPreMatch.groupValues[2]
                AppLog.i(ctx, TAG, "LOCKSOUND_UNMOUNT NS_PRE ${nsPre.trim().take(160)} " +
                    "same=$nsSame checked_before_action=yes")
                if (!nsSame) {
                    // Никаких umount: слой в нашем пространстве имён системе не виден, а «снято»
                    // заявлять нельзя. Реестр сохраняется целиком.
                    markApplied(ctx, false, "unmount_namespace_differs_pre")
                    AppLog.w(ctx, TAG, "LOCKSOUND_UNMOUNT action=none reason=namespace_differs " +
                        "checked_before_action=yes registry_kept=${entries.size} " +
                        "(снятие не начиналось: root не обязан давать namespace init)")
                    return@execute
                }
                val script = LockMountScripts.restoreScript(entries.map { it.target to it.source })
                // iter51 §3.1: restore тоже длиннее 4096 (5593 байта уже на ОДНУ цель) —
                // отправляем через executeScript, иначе на ROOT_5555 он не доезжает.
                val restorePlan = ScriptDelivery.plan("lock_restore", script)
                AppLog.i(ctx, TAG, "LOCKSOUND_UNMOUNT TRANSPORT ${restorePlan.wire()} targets=${entries.size}")
                val res = AdbClient.executeScript("lock_restore", script, 30000)
                AppLog.i(ctx, TAG, "LOCKSOUND_UNMOUNT [exitCode=${res.first}]: " + res.second.trim().take(800))
                val parsed = MountInfo.parseRestore(res.second)
                val nsMismatch = res.second.contains("MOUNT_NS_MISMATCH") ||
                    run {
                        val m = Regex("MOUNT_NS self=(\\S+) init=(\\S+)").find(res.second)
                        m != null && m.groupValues[1] != m.groupValues[2]
                    }
                if (nsMismatch) {
                    // Наш umount произошёл в другом пространстве имён: система слой не потеряла.
                    // Реестр НЕ очищаем и «к стоку» не заявляем.
                    markApplied(ctx, false, "unmount_namespace_differs")
                    AppLog.w(ctx, TAG, "LOCKSOUND_UNMOUNT namespace differs: снятие не подтверждено, " +
                        "реестр сохранён (${entries.size} записей)")
                    return@execute
                }
                val provenTargets = parsed.proven.toSet()
                val foreignLeft = parsed.foreignLeft.toSet()
                // iter49 §4: «сверху чужой слой» — это НЕ «нашего слоя нет». Под чужим слоем мог
                // остаться наш, поэтому такая запись остаётся PENDING (реестр не чистится):
                // раньше `foreignLeft` исключался из `unproven`, и учёт нашего слоя терялся.
                val unproven = entries.filter { it.target !in provenTargets }
                val kept = unproven
                val pendingOwned = kept.size
                // Реестр чистим ТОЛЬКО по доказанным записям. Недоказанные остаются — иначе
                // приложение «забыло» бы свои слои и снять их потом было бы нечем.
                if (kept.isEmpty()) writeOwnMounts(ctx, emptyList())
                else writeOwnMounts(ctx, kept)
                val allProven = unproven.isEmpty()
                val ownLayersLeft = Regex("OWN_LAYERS_TOTAL=(\\d+)").find(res.second)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: -1
                val clean = allProven && ownLayersLeft == 0
                markApplied(
                    ctx, false,
                    when {
                        clean -> "off"
                        foreignLeft.isNotEmpty() && !allProven ->
                            "pending_foreign_layer_above:${pendingOwned}"
                        !allProven -> "unmount_unproven:${unproven.joinToString(",") { it.target }}"
                        else -> "own_layers_left:$ownLayersLeft"
                    }
                )
                AppLog.i(ctx, TAG, "LOCKSOUND_UNMOUNT_RESULT proven=${parsed.proven.size} " +
                    "foreign_left=${parsed.foreignLeft.size} unproven=${unproven.size} " +
                    "own_layers_total=$ownLayersLeft registry_kept=${kept.size} " +
                    "(к стоку не заявляем, если остались чужие слои: ${foreignLeft.size})")
                if (parsed.foreignLeft.isNotEmpty()) {
                    AppLog.i(ctx, TAG, "LOCKSOUND_UNMOUNT FOREIGN_LAYER_LEFT paths=" +
                        parsed.foreignLeft.joinToString(",") +
                        " action=keep (чужой слой — не наш, не снимаем)")
                }
                // staging-файлы удаляем только когда наших слоёв не осталось вовсе.
                if (ownLayersLeft == 0) {
                    AdbClient.execute("rm -f /data/local/tmp/carlock_custom*.wav 2>/dev/null", 8000)
                } else {
                    AppLog.i(ctx, TAG, "LOCKSOUND_UNMOUNT stage kept reason=own_layers_left=$ownLayersLeft")
                }
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "restoreSystemLockSoundBind error: ${t.message}")
                markApplied(ctx, false, "unmount_exception:${t.javaClass.simpleName}")
            } finally {
                gateExit()
            }
        }
    }

    /**
     * iter33h A1.5 — единственный разрешённый путь: ровно `/vendor/etc/carlock/<файл>.wav`.
     * Всё остальное (в том числе `/system/etc/avas/…`) — `SKIP reason=path_not_ours`.
     */
    internal fun isOurLockPath(path: String): Boolean {
        if (!path.startsWith("/vendor/etc/carlock/")) return false
        val name = path.removePrefix("/vendor/etc/carlock/")
        if (name.isEmpty() || name.contains('/')) return false
        if (!name.endsWith(".wav")) return false
        return name.length > 4
    }

    private fun mountsFile(ctx: Context): File = File(ctx.applicationContext.filesDir, "locksound_mounts.txt")

    /** Файл журнала операций звука (iter51 §3.3). */
    /**
     * iter52 §5.1: журнал операций — APPEND-логи (переживает несколько операций и перезапуск).
     *
     * Раньше один файл `locksound_op.txt` перезаписывался каждой операцией, поэтому
     * незавершённая запись исчезала при следующем запуске, а `readOpJournal` вообще не
     * вызывался production-кодом. Теперь:
     *  • каждая запись — отдельная строка (см. [LockOp.parseJournal]);
     *  • запись ПРОВЕРЯЕТСЯ чтением (и это видно в логе, а не заявляется);
     *  • чтение используется в [reconcilePendingOps] из реальных событий.
     */
    private fun opFile(ctx: Context): File = File(ctx.applicationContext.filesDir, "locksound_ops.log")

    /** Дописать состояние операции и ПОДТВЕРДИТЬ его чтением. */
    internal fun writeOpJournal(ctx: Context, op: LockOp.BindOp): Boolean =
        appendOp(ctx, op, "planned")

    /**
     * Добавить запись и проверить, что именно она прочиталась обратно.
     *
     * @return true — запись подтверждена чтением; false — писать в журнал нельзя (и side effect
     *   тогда не выполняется вызывающим).
     */
    internal fun appendOp(ctx: Context, op: LockOp.BindOp, tag: String): Boolean {
        val file = opFile(ctx)
        val line = LockOp.journalLine(op, System.currentTimeMillis())
        val written = runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
        }.isSuccess
        // Читаем ВСЕГДА: подтверждение — это факт чтения, а не отсутствие исключения.
        val back = LockOp.parseJournal(runCatching { file.readText() }.getOrNull())
        // iter59 §3.2.3: подтверждение — это совпадение ВСЕЙ значимой записи, а не только
        // `state`+`gen`. Иначе копия без новой identity попытки (`restoreEv`) считалась
        // записанной, а её строка затирала сохранённый адрес свидетельства (last wins).
        val backOp = back.ops[op.id]
        val confirmed = backOp != null && LockOp.sameIdentity(op, backOp)
        if (!written || !confirmed) {
            AppLog.e(ctx, TAG, "LOCKSOUND_OP write=unconfirmed tag=$tag ${op.wire()} " +
                "written=$written journal=«${back.wire()}» (журнал не подтверждён: неизвестно, " +
                "а не «операции не было»)")
            return false
        }
        AppLog.i(ctx, TAG, "LOCKSOUND_OP tag=$tag ${op.wire()} journal=«${back.wire()}»")
        return true
    }

    /** Обновить состояние операции (append новой строки) и подтвердить запись. */
    internal fun finishOpJournal(ctx: Context, op: LockOp.BindOp, state: LockOp.State,
                                 reason: String): Boolean {
        val now = op.copy(state = state, reason = reason)
        // iter52 §5.1(4): результат записи проверяется; DONE без подтверждения не заявляется.
        // iter60 §5: функция возвращает РЕЗУЛЬТАТ, а не `Unit`. Раньше вызывающий не мог узнать,
        // что DONE не закреплён на диске, и продолжал как при успехе — «успех» жил только в логе.
        if (!appendOp(ctx, now, "finish_${state.name.lowercase()}")) {
            AppLog.e(ctx, TAG, "LOCKSOUND_OP DONE_NOT_PERSISTED op=${op.id} state=${state.name.lowercase()} " +
                "reason=$reason (состояние операции не зафиксировано на диске)")
            return false
        }
        return true
    }

    /** Старая запись (iter50 и раньше): одна операция в одном файле. */
    private fun legacyOpFile(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "locksound_op.txt")

    /**
     * iter53 §7.1 — ПЕРЕНОС СТАРОЙ ЗАПИСИ В ЖУРНАЛ.
     *
     * Почему это нужно: до iter52 состояние операции жило в одном файле `locksound_op.txt`, а
     * production читает только `locksound_ops.log`. Значит после обновления незавершённая
     * операция прошлой версии становилась невидимой — и созданный ею слой никто не сверял.
     *
     * Правила (идемпотентно и crash-safe):
     *  1. файла нет → ничего не делаем;
     *  2. запись разобрана → ДОБАВЛЯЕМ её в журнал и ПОДТВЕРЖДАЕМ чтением; только после этого
     *     переименовываем оригинал (`*.migrated`), чтобы сбой не потерял данные;
     *  3. запись есть, но не разобрана → добавляем операцию `UNDEFINED reason=legacy_corrupt`,
     *     которая попадает в сверку как «нужен ручной разбор» (НЕ «операций не было»);
     *  4. append не подтверждён → оригинал не переименовываем и не удаляем.
     *
     * Повторный перенос безопасен: журнал хранит последнюю строку по `id`.
     */
    internal fun migrateLegacyOp(ctx: Context): String? {
        val legacy = legacyOpFile(ctx)
        // `exists()==false` не доказывает отсутствие при проблеме доступа: различаем явно.
        if (!legacy.exists()) {
            return if (legacy.parentFile?.canRead() != true) "legacy_dir_unreadable" else null
        }
        val raw = runCatching { legacy.readText() }.getOrNull()
        if (raw == null) {
            // iter55 §7.2: ошибка чтения старого журнала БОЛЬШЕ НЕ теряется в логе. Раньше
            // метод возвращал Unit, и вызывающий читал новый журнал как будто старого нет.
            AppLog.w(ctx, TAG, "LOCKSOUND_MIGRATE file=present_read=none action=unknown " +
                "(файл есть, но прочитать его не удалось: это НЕ «записи не было»)")
            return "legacy_unreadable"
        }
        val read = LockOp.parseLegacyOp(raw)
        val op: LockOp.BindOp = when (read) {
            is LockOp.LegacyRead.Absent -> {
                AppLog.w(ctx, TAG, "LOCKSOUND_MIGRATE file=present_read=none action=unknown")
                return "legacy_unreadable"
            }
            is LockOp.LegacyRead.Parsed -> read.op
            is LockOp.LegacyRead.Unknown -> LockOp.BindOp(
                // iter55 §7.2: id ДЕТЕРМИНИРОВАН содержимым. Прежний `legacy-<currentTimeMillis>`
                // при повторе после сбоя давал новую операцию на каждый перенос.
                id = "legacy-corrupt-" + Integer.toHexString(raw.hashCode()),
                gen = 0L,
                desired = false,
                stage = "",
                targets = emptyList(),
                state = LockOp.State.UNDEFINED,
                reason = "legacy_corrupt:${read.why}"
            )
        }
        // iter54 §7.3: ИДЕМПОТЕНТНОСТЬ. Если такая же запись уже перенесена (сбой между
        // append и переименованием), повтор НЕ добавляет вторую строку: журнал не «пухнет» и
        // сверка не получает дубль операции.
        val alreadyThere = when (val cur = LockOp.readJournal(opFile(ctx))) {
            is LockOp.JournalRead.Readable -> cur.value.ops[op.id]?.encode() == op.encode()
            else -> false
        }
        if (alreadyThere) {
            AppLog.i(ctx, TAG, "LOCKSOUND_MIGRATE ${op.wire()} appended=0 action=rename_only " +
                "(запись уже перенесена ранее: перенос идемпотентен)")
            val dst = File(legacy.parentFile, legacy.name + ".migrated")
            val ok = runCatching { legacy.renameTo(dst) }.getOrDefault(false)
            AppLog.i(ctx, TAG, "LOCKSOUND_MIGRATE rename_only dst=${dst.name} ok=$ok " +
                "(повторного добавления в журнал не было)")
            return null
        }
        val appended = appendOp(ctx, op, "migrate_legacy")
        when (LockOp.migrationStep(read, appended)) {
            LockOp.MigrationAction.RenameLegacy -> {
                val dst = File(legacy.parentFile, legacy.name + ".migrated")
                val ok = runCatching { legacy.renameTo(dst) }.getOrDefault(false)
                AppLog.i(ctx, TAG, "LOCKSOUND_MIGRATE ${op.wire()} appended=1 " +
                    "renamed=$ok dst=${dst.name} " +
                    "(старая запись перенесена в журнал; оригинал сохранён как ${
                        if (ok) "*.migrated" else "исходный файл (переименование не удалось)"
                    })")
            }
            LockOp.MigrationAction.KeepLegacy -> {
                AppLog.e(ctx, TAG, "LOCKSOUND_MIGRATE ${op.wire()} appended=0 action=keep " +
                    "(журнал не подтвердил запись: оригинал НЕ переименован и НЕ удалён — " +
                    "повторная попытка на следующем событии)")
                return "legacy_migration_unconfirmed"
            }
            LockOp.MigrationAction.Nothing -> Unit
        }
        return null
    }

    /**
     * Текущий журнал (все операции с последним состоянием).
     *
     * iter54 §7.2: результат ТРЁХЗНАЧНЫЙ ([LockOp.JournalRead]). Раньше любая ошибка чтения
     * превращалась в пустой журнал, и «прочитать нельзя» было неотличимо от «операций не было»:
     * при недоступном файле сверка молча объявляла «всё в порядке».
     */
    internal fun readOpJournal(ctx: Context): LockOp.JournalRead {
        // Читаем журнал ТОЛЬКО после попытки переноса старой записи: иначе незавершённая операция
        // прошлой версии останется невидимой для сверки.
        val migration = migrateLegacyOp(ctx)
        if (migration != null) {
            // Старый журнал не прочитан/не перенесён: его содержимое неизвестно, поэтому и новый
            // журнал нельзя объявлять полным описанием истории.
            AppLog.e(ctx, TAG, "LOCKSOUND_JOURNAL legacy=$migration action=unknown " +
                "(старая запись не разрешена: сверка не заявляет «всё снято»)")
            return LockOp.JournalRead.Unknown("legacy:$migration")
        }
        val r = LockOp.readJournal(opFile(ctx))
        when (r) {
            is LockOp.JournalRead.Unknown ->
                AppLog.w(ctx, TAG, "LOCKSOUND_JOURNAL ${r.wire()} action=none " +
                    "(журнал НЕ пуст и НЕ пуст-доказанно: сверка по нему не объявляет «всё снято»)")
            is LockOp.JournalRead.Absent ->
                AppLog.i(ctx, TAG, "LOCKSOUND_JOURNAL ${r.wire()}")
            is LockOp.JournalRead.Readable -> if (r.value.broken > 0) {
                AppLog.w(ctx, TAG, "LOCKSOUND_JOURNAL ${r.wire()} " +
                    "(повреждённые строки = «неизвестно», а не «операции не было»)")
            }
        }
        return r
    }

    /**
     * iter55 §5.2: РАЗРЕШЕНИЕ `Unknown` ПО СВИДЕТЕЛЬСТВУ, а не по догадке.
     *
     * Читает `<path>`, `<path>.started`, `<path>.done` через [ScriptDelivery.evidenceCommand]
     * (только чтение). Возвращает `null`, если свидетельства нет в записи или прочитать его не
     * удалось — тогда исход остаётся `Unknown`, а не «всё в порядке».
     */
    internal fun readEvidence(ctx: Context, op: LockOp.BindOp): LockOp.EvidenceFacts? =
        readEvidencePath(ctx, op.id, op.evidence)

    /** То же чтение по явному пути: нужно и для bind-, и для restore-свидетельства. */
    internal fun readEvidencePath(ctx: Context, opId: String, path: String): LockOp.EvidenceFacts? {
        if (path.isEmpty()) return null
        val r = runCatching {
            AdbClient.execute(ScriptDelivery.evidenceCommand(path), 8000, expectRoot = false)
        }.getOrNull()
        if (r == null) {
            AppLog.w(ctx, TAG, "LOCKSOUND_EVIDENCE op=$opId path=$path action=unreadable " +
                "(прочитать свидетельство не удалось: исход остаётся неизвестным)")
            return null
        }
        val facts = LockOp.parseEvidence(r.second)
        if (facts == null) {
            AppLog.w(ctx, TAG, "LOCKSOUND_EVIDENCE op=$opId path=$path rc=${r.first} " +
                "action=no_fields out=${r.second.replace('\n', ' ').take(200)}")
        }
        return facts
    }

    /**
     * iter52 §5.1(3): СВЕРКА НЕЗАВЕРШЁННЫХ ОПЕРАЦИЙ ИЗ ЖУРНАЛА — на реальном пути.
     *
     * Вызывается из событий: старт/сторожевая проверка, возврат root-канала, смена намерения
     * (ON/OFF/смена файла), перед новой отправкой bind. Для каждой незавершённой операции:
     *  1. отправляется restore-скрипт по ЕЁ целям и ЕЁ staging-источнику — скрипт снимает только
     *     слой, чей root совпадает с ожидаемым, и отказывается трогать чужой;
     *  2. по фактическому выводу операция переводится в DONE (снято/слоя нет) либо остаётся
     *     UNDEFINED с причиной (`foreign_above`/`unproven`) — чужое не трогаем;
     *  3. после сверки состояние доводится до ПОСЛЕДНЕГО сохранённого намерения владельца.
     *
     * @return [LockOp.SoundRecovery]: можно ли отправлять новый bind. iter55 §7.1 — раньше здесь
     *   был `Boolean`, и production его ИГНОРИРОВАЛ: несовместимый bind уходил даже при
     *   неизвестном/повреждённом журнале.
     */
    internal fun reconcilePendingOps(
        ctx: Context,
        source: String,
        /**
         * iter58 §3.2: отложенный проход после «позднего shell» — ТОЛЬКО чтение. Он может
         * закрыть операцию, если terminal уже читается, но не имеет права отправить новый
         * restore/umount: иначе «read-only reread» снова запускал side effect.
         */
        allowRestore: Boolean = true
    ): LockOp.SoundRecovery {
        gateEnter("reconcile")
        try {
            return reconcileLocked(ctx, source, allowRestore)
        } finally {
            gateExit()
        }
    }

    /** Тело сверки — выполняется под [ioGate] (см. [reconcilePendingOps]). */
    private fun reconcileLocked(
        ctx: Context,
        source: String,
        allowRestore: Boolean = true
    ): LockOp.SoundRecovery {
        val read = readOpJournal(ctx)
        if (read is LockOp.JournalRead.Unknown) {
            // «Прочитать нельзя» — это НЕ «операций не было»: объявлять «всё снято» и
            // сходиться к намерению нельзя, пока неизвестно, что в журнале.
            AppLog.e(ctx, TAG, "LOCKSOUND_RECONCILE source=$source action=stop ${read.wire()} " +
                "(сверка невозможна: неизвестно, есть ли незавершённые операции)")
            return LockOp.SoundRecovery.Conflict("journal_unknown:" + read.wire())
        }
        val journal = read.journal
        if (journal.broken > 0) {
            // iter55 §7.2: повреждённая строка — это возможная НЕИЗВЕСТНАЯ операция. Пустой
            // `unresolved()` после этого не доказывает сток.
            AppLog.e(ctx, TAG, "LOCKSOUND_RECONCILE source=$source action=stop " +
                "journal_broken=${journal.broken} journal=«${journal.wire()}» " +
                "(часть журнала не разобрана: неизвестные операции не исключены)")
            return LockOp.SoundRecovery.Conflict("journal_corrupt:broken=${journal.broken}")
        }
        val pending = journal.unresolved()
        if (pending.isEmpty()) {
            AppLog.i(ctx, TAG, "LOCKSOUND_RECONCILE source=$source action=none journal=«${journal.wire()}» " +
                "read=${if (read is LockOp.JournalRead.Absent) "absent" else "readable"}")
            return LockOp.SoundRecovery.Ready
        }
        val unresolvedNow = ArrayList<String>()
        val lateShell = ArrayList<String>()
        var evidencePath = ""
        var conflictReason: String? = null
        var needReread = false
        val journalUnconfirmed = ArrayList<String>()
        val rereadOps = ArrayList<String>()
        AppLog.i(ctx, TAG, "LOCKSOUND_RECONCILE source=$source pending=${pending.size} " +
            "journal=«${journal.wire()}» ops=${pending.joinToString(",") { it.id }} " +
            "(незавершённые операции будут сверены по фактической таблице монтирований)")
        val appCtx = ctx.applicationContext
        // iter57 §5.2/§5.3: решение по КАЖДОЙ операции принимает [LockOp.reconStep] — тот же
        // код, который гоняют тесты. Здесь только порты устройства и запись результата.
        // iter58 §3.2: подготовленные restore по операции. Identity (nonce/path) создаётся
        // ОДИН раз в [prepareRestore] и ДО отправки сохраняется в журнал; сама отправка берёт
        // ровно её, поэтому crash между send и записью результата не оставляет поздний umount
        // без адреса свидетельства, а повторный restore не подменяет identity.
        // prepared по ПУТИ свидетельства: отправка берёт ровно ту попытку, чья identity уже
        // записана, поэтому подменить путь в момент отправки нельзя (iter59 §3.2.2).
        val preparedRestore = HashMap<String, ScriptDelivery.Prepared>()
        val readOnlyPass = !allowRestore
        val ports = object : LockOp.ReconPorts {
            override fun readOnly(): Boolean = readOnlyPass

            override fun evidence(op: LockOp.BindOp, path: String): LockOp.EvidenceFacts? =
                readEvidencePath(appCtx, op.id, path)

            /**
             * iter59 §3.2.7 — факт состояния целей БЕЗ изменений на устройстве.
             * Пустая/нечитаемая таблица даёт «неизвестно», а не «сток».
             */
            override fun observe(op: LockOp.BindOp): LockOp.Reconcile? {
                val script = LockMountScripts.observeScript(op.targets.map { it to op.stage })
                val r = runCatching { AdbClient.execute(script, 15000, expectRoot = false) }.getOrNull()
                if (r == null) {
                    AppLog.w(appCtx, TAG, "LOCKSOUND_OBSERVE op=${op.id} action=unreadable " +
                        "(состояние целей не прочитано: остаётся неизвестным)")
                    return null
                }
                val detail = LockOp.reconcileDetail(r.first, r.second, op.targets)
                AppLog.i(appCtx, TAG, "LOCKSOUND_OBSERVE op=${op.id} rc=${r.first} " +
                    "verdict=${detail.verdict.name.lowercase()} proven=${detail.proven.size} " +
                    "missing=${detail.missing.size} action=none")
                return detail.verdict
            }

            override fun prepareRestore(op: LockOp.BindOp): String {
                if (readOnlyPass) return ""
                val script = LockMountScripts.restoreScript(op.targets.map { it to op.stage })
                val prep = ScriptDelivery.prepare("lock_restore", script)
                // Durable ЗАПИСЬ identity ДО отправки: appendOp подтверждает её ПОЛНЫМ
                // сравнением записи (iter59 §3.2.3). Не подтвердили — отправки нет вовсе.
                val candidate = op.copy(restoreEv = prep.evidencePath, reason = "restore_prepared")
                val persisted = runCatching {
                    appendOp(appCtx, candidate, "restore_prepared")
                }.getOrDefault(false)
                if (!persisted) {
                    AppLog.e(appCtx, TAG, "LOCKSOUND_RESTORE_PREPARE op=${op.id} " +
                        "persisted=no action=no_send reason=restore_prepare_not_persisted " +
                        "evidence=${prep.evidencePath.ifEmpty { "none" }}")
                    return ""
                }
                preparedRestore[prep.evidencePath] = prep
                return prep.evidencePath
            }

            /**
             * iter59 §3.2.1/§3.2.2: отправляется РОВНО [attempt] — путь, чья identity уже в
             * журнале. Нет такой подготовки ⇒ доказанно ничего не отправлено (NotSent), а не
             * «попробуем другой путь».
             */
            override fun restore(op: LockOp.BindOp, attempt: String): LockOp.RestoreOutcome {
                if (readOnlyPass) {
                    return LockOp.RestoreOutcome(false, false, -1, "", "", "read_only_pass")
                }
                if (attempt.isEmpty()) {
                    return LockOp.RestoreOutcome(false, false, -1, "", "", "attempt_identity_empty")
                }
                val prep = preparedRestore.remove(attempt)
                    ?: return LockOp.RestoreOutcome(false, false, -1, "", "", "attempt_not_prepared")
                // iter61 §5.2: durable-фаза ПЕРЕДАЧИ транспорту пишется ДО вызова. Это и есть
                // доказательство «отправка начиналась»: без неё запись `restore_prepared`
                // означает «до транспорта не дошли» (crash до send) и повтор безопасен. Не
                // подтвердили запись — не отправляем (`handoff_not_persisted`), иначе
                // последующая сверка не отличит нашу же отправку от чужой.
                val handoffOk = runCatching {
                    appendOp(appCtx, op.copy(restoreEv = attempt, reason = "restore_handoff:$attempt"),
                        "restore_handoff")
                }.getOrDefault(false)
                if (!handoffOk) {
                    AppLog.e(appCtx, TAG, "LOCKSOUND_RESTORE_HANDOFF op=${op.id} " +
                        "persisted=no action=no_send reason=handoff_not_persisted evidence=$attempt")
                    return LockOp.RestoreOutcome(false, false, -1, "", attempt, "handoff_not_persisted")
                }
                val res = AdbClient.executePrepared(prep, 30000)
                val outcome = ScriptDelivery.outcome(res, op.id)
                // iter59 §7: наружу отдаётся код PAYLOAD. Транспортный rc (-1/1 при потере
                // ответа) за код скрипта не принимается: иначе «umount не выполнен» и «umount
                // выполнен, ответ потерян» выглядели бы одинаково.
                val payloadRc = if (res.payloadRc != ScriptDelivery.RC_UNKNOWN) res.payloadRc else -1
                AppLog.i(appCtx, TAG, "LOCKSOUND_RESTORE_RESULT op=${op.id} state=${res.state.name} " +
                    "payload_rc=$payloadRc transport_rc=${res.transportRc} " +
                    "rc_from_done=${res.rcFromDone} evidence=${attempt}")
                return when (outcome) {
                    is ScriptDelivery.DeliveryOutcome.NotSent ->
                        LockOp.RestoreOutcome(false, false, payloadRc, res.out,
                            attempt, outcome.why)
                    is ScriptDelivery.DeliveryOutcome.Unknown ->
                        LockOp.RestoreOutcome(true, true, payloadRc, res.out,
                            outcome.evidence.path.ifEmpty { attempt }, outcome.why)
                    is ScriptDelivery.DeliveryOutcome.Finished ->
                        LockOp.RestoreOutcome(true, false, payloadRc, res.out, attempt, "")
                }
            }
        }
        for (op in pending) {
            val step = runCatching { LockOp.reconStep(op, ports) }.getOrElse { t ->
                LockOp.ReconStep(
                    op.id, LockOp.State.UNDEFINED, "reconcile_threw:" + t.javaClass.simpleName,
                    conflict = "reconcile_threw:" + op.id, unresolved = true
                )
            }
            // Одна запись на проход: состояние + обновлённый адрес свидетельства restore.
            // iter60 §5: без durable DONE операция НЕ объявляется закрытой — она остаётся
            // незавершённой, а проход сообщает «журнал не закреплён» вместо «готово».
            val journalOk = finishOpJournal(appCtx, step.persist ?: op, step.newState, step.reason)
            if (!journalOk) {
                journalUnconfirmed += op.id
                unresolvedNow += op.id
                conflictReason = conflictReason ?: ("journal_not_persisted:" + op.id)
            }
            AppLog.i(appCtx, TAG, "LOCKSOUND_RECONCILE op=${op.id} ${step.wire()} " +
                "evidence=${op.evidence.ifEmpty { "none" }} restore_ev=${op.restoreEv.ifEmpty { "none" }} " +
                "note=«${step.note}»")
            if (step.lateShell) lateShell.add(op.id)
            if (step.unresolved) {
                unresolvedNow += op.id
                if (evidencePath.isEmpty()) evidencePath = op.evidence.ifEmpty { op.restoreEv }
            }
            if (step.deferReread) {
                needReread = true
                rereadOps.add(op.id)
            }
            step.conflict?.let { conflictReason = conflictReason ?: it }
        }
        if (needReread) {
            // Один отложенный read-only проход (без горячей петли): terminal позднего исполнения
            // появляется через доли секунды, а следующий проход уже сможет закрыть операцию.
            scheduleReread(appCtx, source, rereadOps)
        }
        if (lateShell.isNotEmpty()) {
            AppLog.e(appCtx, TAG, "LOCKSOUND_RECONCILE source=$source action=stop " +
                "late_shell_possible=${lateShell.joinToString(",")} " +
                "(terminal исполнения не доказан: операция НЕ закрывается, bind не отправляется)")
            conflictReason = conflictReason ?: ("late_shell_possible:" + lateShell.first())
        }
        // ── iter58 §3.1: РЕШЕНИЕ АГРЕГАТОРА ────────────────────────────────────────────────
        // Дефект iter57: unresolved/conflict/lateShell считались, логировались — и затем
        // игнорировались: при ON всё равно планировался новый bind (с `reconcileFirst=false`,
        // то есть с обходом проверки), при OFF ставилась отметка «применено OFF», а функция
        // всегда возвращала Ready. Правильные решения модели `LockOp.reconStep` до вызывающих
        // мест не доходили.
        //
        // Теперь, пока хоть одна операция не разрешена, агрегатор:
        //  • НЕ отправляет bind и НЕ сходится к намерению;
        //  • НЕ объявляет «применено OFF»/«сток доказан»;
        //  • возвращает Pending (нужен ещё проход) или Conflict (нужно решение владельца).
        val gate = LockOp.recoveryGate(unresolvedNow, conflictReason, lateShell, readOnlyPass)
        if (gate != null) {
            AppLog.e(appCtx, TAG, "LOCKSOUND_RECONCILE source=$source action=blocked " +
                "unresolved=${unresolvedNow.joinToString(",").ifEmpty { "none" }} " +
                "conflict=${conflictReason ?: "none"} " +
                "late_shell=${lateShell.joinToString(",").ifEmpty { "none" }} " +
                "${gate.wire()} bind_sent=no off_applied=no " +
                "desired=${if (isEnabled(appCtx)) "on" else "off"} " +
                "(операция не разрешена: новый bind и отметка OFF запрещены)")
            return gate
        }
        // Сходимость к последнему сохранённому намерению (не к «состоянию до»).
        if (isEnabled(appCtx)) {
            val path = getSelectedPath(appCtx)
            if (path.isEmpty() || !File(path).exists()) {
                // Включено, но файл недоступен: это НЕ «сток доказан» и не повод объявлять
                // готовность — выбор владельца сохранён, применение не подтверждено.
                AppLog.e(appCtx, TAG, "LOCKSOUND_RECONCILE action=blocked desired=true " +
                    "reason=selected_file_missing path=${path.ifEmpty { "none" }} " +
                    "(настройка сохранена, применение не подтверждено)")
                return LockOp.SoundRecovery.Pending("selected_file_missing")
            }
            AppLog.i(appCtx, TAG, "LOCKSOUND_RECONCILE action=converge_to_intent desired=true " +
                "(после сверки возвращаем звук к последнему выбору владельца)")
            // iter58 §3.1: `applySystemLockSoundBind` НЕ возвращает результат — работа уходит в
            // BgWorker. Значит, «применено» здесь заявить нельзя: возвращаем Pending и говорим,
            // что bind только ЗАПЛАНИРОВАН. Готовность объявит проход, который прочитает факт.
            applySystemLockSoundBind(appCtx, File(path), reconcileFirst = false)
            AppLog.i(appCtx, TAG, "LOCKSOUND_RECONCILE action=bind_scheduled desired=true " +
                "path=$path (отправка запланирована в BgWorker; применение ещё НЕ подтверждено)")
            return LockOp.SoundRecovery.Pending("bind_scheduled", path)
        } else {
            markApplied(appCtx, false, "reconciled_off")
        }
        return LockOp.SoundRecovery.Ready
    }

    /**
     * iter57 §5.3 — ОДИН отложенный read-only проход сверки (без горячей петли).
     *
     * Нужен там, где terminal позднего исполнения ещё не успел появиться: через несколько секунд
     * свидетельство уже читается, и операция закрывается сама. Повтор не запускает никаких
     * side effect — только чтение и, при доказанном terminal, безопасное снятие СВОИХ слоёв.
     */
    private fun scheduleReread(ctx: Context, source: String, opIds: Collection<String> = emptyList()) {
        val appCtx = ctx.applicationContext
        // iter60 §5: reread ОГРАНИЧЕН и ДЕДУПЛИЦИРОВАН. Дефект iter59: каждый проход с
        // `deferReread` ставил новую задачу, поэтому при непрогрессирующей операции получалась
        // самоподдерживающаяся цепочка чтений (и дубли задач на одну и ту же операцию).
        val scheduled: List<String>
        synchronized(rereadState) {
            if (rereadScheduled) {
                AppLog.i(appCtx, TAG, "LOCKSOUND_REREAD action=dedup source=$source " +
                    "pending=${rereadState.keys.joinToString(",").ifEmpty { "none" }} " +
                    "(отложенный проход уже запланирован)")
                return
            }
            scheduled = opIds.filter { (rereadState[it] ?: 0) < REREAD_MAX_PER_OP }
            if (scheduled.isEmpty()) {
                AppLog.w(appCtx, TAG, "LOCKSOUND_REREAD action=exhausted source=$source " +
                    "ops=${opIds.joinToString(",").ifEmpty { "none" }} limit=$REREAD_MAX_PER_OP " +
                    "(предел отложенных чтений исчерпан: нужен обычный проход по событию)")
                return
            }
            scheduled.forEach { rereadState[it] = (rereadState[it] ?: 0) + 1 }
            rereadScheduled = true
        }
        val attempt = scheduled.maxOf { rereadState[it] ?: 1 }
        // Линейная пауза по номеру попытки: чтения не идут плотной серией.
        val delay = REREAD_DELAY_MS * attempt
        runCatching {
            BgWorker.postDelayed("LockSound.reread", delay) {
                try {
                    // iter58 §3.2: отложенный проход — ТОЛЬКО чтение свидетельств.
                    runCatching {
                        reconcilePendingOps(appCtx, "reread_after:" + source, allowRestore = false)
                    }
                } finally {
                    synchronized(rereadState) { rereadScheduled = false }
                }
            }
        }.onFailure {
            synchronized(rereadState) { rereadScheduled = false }
        }
        AppLog.i(appCtx, TAG, "LOCKSOUND_REREAD scheduled=1 source=$source delay_ms=$delay " +
            "ops=${scheduled.joinToString(",")} attempt=$attempt limit=$REREAD_MAX_PER_OP")
    }

    /** Предел отложенных read-only проходов на одну операцию (iter60 §5). */
    internal var REREAD_MAX_PER_OP: Int = 3

    private val rereadState = HashMap<String, Int>()
    private var rereadScheduled = false

    /**
     * iter57 §5.3 — ЯВНОЕ разрешение операции, которую нельзя доказать (легаси-запись без
     * свидетельства, убитый канал). Вызывается только по решению владельца; оставляет след
     * `owner_resolved_unprovable` и НЕ заявляет сток.
     */
    fun resolveUnprovableOps(ctx: Context, reason: String): ToolsResult {
        gateEnter("resolve")
        try {
            val t0 = SystemClock.elapsedRealtime()
            val read = readOpJournal(ctx)
            if (read !is LockOp.JournalRead.Readable) {
                return ToolsResult(
                    action = "locksound.resolveUnprovable", title = "Разрешение записей звука",
                    steps = listOf(ToolsStep(
                        step = "read_journal", command = "readOpJournal()", startedAtMs = t0,
                        finishedAtMs = SystemClock.elapsedRealtime(), exitCode = 1,
                        stderr = "журнал операций недоступен: " + read.wire(),
                        status = ToolsStep.Status.FAILED
                    ))
                )
            }
            val ops = read.journal.unresolved()
            var n = 0
            for (op in ops) {
                val resolved = LockOp.ownerResolve(op, reason)
                if (appendOp(ctx, resolved, "owner_resolve")) n++
                AppLog.w(ctx, TAG, "LOCKSOUND_OP owner_resolved op=${op.id} reason=$reason " +
                    "(разрешено владельцем; сток по этой записи НЕ доказан и не заявляется)")
            }
            return ToolsResult(
                action = "locksound.resolveUnprovable", title = "Разрешение записей звука",
                steps = listOf(ToolsStep(
                    step = "owner_resolve",
                    command = "LockOp.ownerResolve(ops=$n, reason=$reason)",
                    startedAtMs = t0, finishedAtMs = SystemClock.elapsedRealtime(),
                    exitCode = 0, actual = "$n",
                    stdout = "разрешено записей: $n (это НЕ доказательство стока)",
                    status = if (ops.isEmpty()) ToolsStep.Status.PARTIAL else ToolsStep.Status.OK
                ))
            )
        } finally {
            gateExit()
        }
    }

    /** Пауза перед отложенным read-only проходом сверки. */
    internal var REREAD_DELAY_MS: Long = 3000L

    /** Хэш пути staging: короткая детерминированная метка, без содержимого WAV. */
    internal object Stage0 {
        fun hash(s: String): String =
            String.format("%08x", s.hashCode().toLong() and 0xffffffffL)
    }

    /**
     * §5.3 iter47 — слой владения: ЦЕЛЬ + ИСТОЧНИК + поколение, которым он смонтирован.
     *
     * Одного пути недостаточно: на той же цели мог оказаться чужой bind, и снимать его нельзя.
     * Формат строки реестра: `target|source|gen`. Старые записи (только путь) читаются как
     * `target||gen=-1` и снимаются лишь при совпадении источника с нашим staging-префиксом.
     */
    internal data class OwnMount(val target: String, val source: String, val gen: Long)

    /** Наш staging-префикс — единственный источник, который мы вправе снимать. */
    internal fun isOurStageSource(src: String): Boolean =
        src.startsWith("/data/local/tmp/carlock_custom")

    internal fun parseOwnMount(line: String): OwnMount? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val parts = t.split('|')
        val target = parts[0].trim()
        if (target.isEmpty()) return null
        val source = parts.getOrElse(1) { "" }.trim()
        val gen = parts.getOrElse(2) { "-1" }.trim().toLongOrNull() ?: -1L
        return OwnMount(target, source, gen)
    }

    private fun readOwnMounts(ctx: Context): List<OwnMount> =
        runCatching { mountsFile(ctx).readLines() }.getOrDefault(emptyList())
            .mapNotNull { parseOwnMount(it) }

    /**
     * Слияние реестра: новые факты заменяют запись по той же цели, остальные сохраняются.
     * Реестр не «перезаписывается с нуля» — иначе один частичный проход стирал бы знание о
     * слоях, которые остались смонтированными.
     */
    private fun mergeOwnMounts(old: List<OwnMount>, facts: List<OwnMount>): List<OwnMount> {
        val byTarget = LinkedHashMap<String, OwnMount>()
        for (o in old) byTarget[o.target] = o
        for (f in facts) byTarget[f.target] = f
        return byTarget.values.toList()
    }

    private fun writeOwnMounts(ctx: Context, mounts: List<OwnMount>) {
        runCatching {
            mountsFile(ctx).writeText(
                mounts.joinToString("\n") { o -> o.target + "|" + o.source + "|" + o.gen }
            )
        }
    }

    fun discoverWavFiles(): List<File> {
        val results = ArrayList<File>()
        val dirs = listOf(
            File("/sdcard/Music"),
            File("/sdcard/Download"),
            File("/sdcard/Notifications"),
            File(android.os.Environment.getExternalStorageDirectory(), "Music"),
            File(android.os.Environment.getExternalStorageDirectory(), "Download")
        )
        for (dir in dirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f -> f.isFile && (f.name.endsWith(".wav", true) || f.name.endsWith(".mp3", true) || f.name.endsWith(".ogg", true)) }
                    ?.let { results.addAll(it) }
            }
        }
        return results
    }

}
