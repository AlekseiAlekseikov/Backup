package com.example.voiceapp3.tools

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
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_LOCK_SOUND_ENABLED, false)
    }

    fun getSelectedPath(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        val path = prefs.getString(PREF_LOCK_SOUND_PATH, "") ?: ""
        if (path.isNotEmpty() && File(path).exists()) return path
        return ""
    }

    fun getDisplayName(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        val name = prefs.getString(PREF_LOCK_SOUND_NAME, "") ?: ""
        if (name.isNotEmpty()) return name
        val path = getSelectedPath(ctx)
        return if (path.isNotEmpty()) File(path).name else "Заводской (Geely OEM)"
    }

    fun getVolume(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        return prefs.getInt(PREF_LOCK_SOUND_VOLUME, DEFAULT_VOLUME)
    }

    fun getDelayMs(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        return prefs.getInt(PREF_LOCK_SOUND_DELAY, DEFAULT_DELAY_MS)
    }

    fun setVolume(ctx: Context, volume: Int): ToolsResult {
        val clamped = volume.coerceIn(5, 100)
        ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
            .edit().putInt(PREF_LOCK_SOUND_VOLUME, clamped).commit()
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
        ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
            .edit().putInt(PREF_LOCK_SOUND_DELAY, validDelay).commit()
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
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)

        prefs.edit().putBoolean(PREF_LOCK_SOUND_ENABLED, enabled).commit()
        val prefReadback = prefs.getBoolean(PREF_LOCK_SOUND_ENABLED, false)
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
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_UNLOCK_SOUND_ENABLED, false)
    }

    fun getUnlockSelectedPath(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        val path = prefs.getString(PREF_UNLOCK_SOUND_PATH, "") ?: ""
        if (path.isNotEmpty() && File(path).exists()) return path
        return ""
    }

    fun getUnlockDisplayName(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        val name = prefs.getString(PREF_UNLOCK_SOUND_NAME, "") ?: ""
        if (name.isNotEmpty()) return name
        val path = getUnlockSelectedPath(ctx)
        return if (path.isNotEmpty()) File(path).name else "Не выбран (отключен)"
    }

    fun setUnlockSoundEnabled(ctx: Context, enabled: Boolean): ToolsResult {
        val t0 = SystemClock.elapsedRealtime()
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_UNLOCK_SOUND_ENABLED, enabled).commit()

        val readback = prefs.getBoolean(PREF_UNLOCK_SOUND_ENABLED, false)
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
            restoreSystemLockSoundBind(ctx)
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

        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        val pathKey = if (isLock) PREF_LOCK_SOUND_PATH else PREF_UNLOCK_SOUND_PATH
        val nameKey = if (isLock) PREF_LOCK_SOUND_NAME else PREF_UNLOCK_SOUND_NAME
        val enableKey = if (isLock) PREF_LOCK_SOUND_ENABLED else PREF_UNLOCK_SOUND_ENABLED

        prefs.edit()
            .putString(pathKey, targetFile.absolutePath)
            .putString(nameKey, fileName)
            .putBoolean(enableKey, true)
            .commit()

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
    // Воспроизведение через наружный динамик (AVAS usage 29)
    // ──────────────────────────────────────────────────────────────

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
            playExternalSound(appCtx, file, volume)
            // Предпрослушка также в салоне для unlock (на закрытии это делает фреймворк Geely сам).
            if (!isLock) {
                playCabinSound(appCtx, file)
            }
            AppLog.i(ctx, TAG, "playSound[$kind] started Δt=${delta}ms (target delay=${delayMs}ms)")
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
        val prefs = ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
        val pathKey = if (isLock) PREF_LOCK_SOUND_PATH else PREF_UNLOCK_SOUND_PATH
        val nameKey = if (isLock) PREF_LOCK_SOUND_NAME else PREF_UNLOCK_SOUND_NAME
        val enableKey = if (isLock) PREF_LOCK_SOUND_ENABLED else PREF_UNLOCK_SOUND_ENABLED

        prefs.edit()
            .remove(pathKey)
            .remove(nameKey)
            .putBoolean(enableKey, false)
            .commit()

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
        return try {
            mediaPlayer?.release()
            val mp = MediaPlayer()

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) // AVAS usage exterior speaker
                .build()

            mp.setAudioAttributes(audioAttributes)
            val vol = (volumePercent.coerceIn(5, 100) / 100f)
            mp.setVolume(vol, vol)

            if (soundFile != null && soundFile.exists()) {
                mp.setDataSource(soundFile.absolutePath)
            } else {
                val oemWav = File(OEM_CARLOCK_DEFAULT_WAV)
                if (oemWav.exists()) {
                    mp.setDataSource(oemWav.absolutePath)
                } else {
                    AppLog.w(ctx, TAG, "playExternalSound: OEM wav $OEM_CARLOCK_DEFAULT_WAV not found")
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
            AppLog.i(ctx, TAG, "playExternalSound: воспроизводится ${soundFile?.name ?: "OEM"} (громкость $volumePercent%)")
            true
        } catch (t: Throwable) {
            AppLog.e(ctx, TAG, "playExternalSound failed: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        }
    }

    fun onBootOrWatchdog(ctx: Context) {
        val lockEnabled = isEnabled(ctx)
        val unlockEnabled = isUnlockSoundEnabled(ctx)
        AppLog.i(ctx, TAG, "onBootOrWatchdog: проверка звуков замка (lock=$lockEnabled unlock=$unlockEnabled) vol=${getVolume(ctx)}% delay=${getDelayMs(ctx)}ms")
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
            // Если выключено — на всякий случай убираем чужой bind mount.
            runCatching { restoreSystemLockSoundBind(ctx) }
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
    private fun applySystemLockSoundBind(ctx: Context, soundFile: File) {
        // Если ранее тумблер был отключён из-за детерминированной ошибки — не штурмим
        if (bindDisabledUntilBoot) {
            AppLog.i(ctx, TAG, "VERIFY lockSound: skip (disabled until boot, reason=$lastBindFailReason)")
            return
        }
        val appCtx = ctx.applicationContext
        // P1: всё что ниже — ADB/mount, на BgWorker а не на main.
        BgWorker.post("LockSound.bind") {
            try {
                val stagePath = "/data/local/tmp/carlock_custom.wav"
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
                val copyRes = AdbClient.execute(copyScript, 30000)
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

                val bindScript = """
                    SELINUX_BEFORE=${'$'}(getenforce 2>/dev/null)
                    AUDIOSRV_PID_BEFORE=${'$'}(pidof audioserver 2>/dev/null)
                    MOUNTED_COUNT=0
                    if [ -s '$stagePath' ]; then
                        STAGE_INODE=${'$'}(stat -c '%i' '$stagePath' 2>/dev/null)
                        for f in /vendor/etc/carlock/*.wav /system/etc/avas/sound_type_*/*.wav; do
                            if [ -f "${"$"}f" ]; then
                                for _i in 1 2 3 4; do
                                    grep -F " ${"$"}f " /proc/mounts >/dev/null 2>&1 || break
                                    umount "${"$"}f" 2>/dev/null || umount -l "${"$"}f" 2>/dev/null || true
                                done
                                mount -o bind '$stagePath' "${"$"}f" 2>&1 || echo MOUNT_FAIL:${"$"}f
                                TGT_INODE=${'$'}(stat -c '%i' "${"$"}f" 2>/dev/null)
                                if [ "${'$'}STAGE_INODE" = "${'$'}TGT_INODE" ] && grep -qF " ${"$"}f " /proc/mounts; then
                                    echo MOUNTED_OK:${"$"}f
                                    MOUNTED_COUNT=${'$'}((MOUNTED_COUNT+1))
                                else
                                    echo MOUNT_VERIFY_FAIL:${"$"}f inode=${'$'}TGT_INODE
                                fi
                                restorecon -v "${"$"}f" 2>/dev/null || true
                            fi
                        done
                    fi
                    killall -9 audioserver 2>/dev/null || true
                    pkill -9 -f avas 2>/dev/null || true
                    sleep 2
                    AUDIOSRV_PID_AFTER=${'$'}(pidof audioserver 2>/dev/null)
                    SELINUX_AFTER=${'$'}(getenforce 2>/dev/null)
                    echo SELINUX_BEFORE=${'$'}SELINUX_BEFORE SELINUX_AFTER=${'$'}SELINUX_AFTER
                    echo AUDIOSRV_PID_BEFORE=${'$'}AUDIOSRV_PID_BEFORE AUDIOSRV_PID_AFTER=${'$'}AUDIOSRV_PID_AFTER
                    echo MOUNTS_COUNT=${'$'}(grep carlock /proc/mounts | wc -l)
                    echo MOUNTED_OK_COUNT=${'$'}MOUNTED_COUNT
                """.trimIndent()
                val bindRes = AdbClient.execute(bindScript, 20000)
                AppLog.i(ctx, TAG, "lockSound bind rc=${bindRes.first}: ${bindRes.second.replace('\n', ' ').take(500)}")

                val failDeterm = bindRes.second.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")
                    || bindRes.second.contains("Permission denied")
                    || bindRes.second.contains("avc: denied")
                    || bindRes.second.contains("No such file")
                    || bindRes.second.contains("MOUNT_VERIFY_FAIL") && bindRes.first == 0 && !bindRes.second.contains("MOUNTED_OK:")
                val ok = bindRes.first == 0 && bindRes.second.contains("MOUNTED_OK:") && !failDeterm

                if (ok) {
                    bindFailCount = 0
                    lastBindFailReason = null
                    AppLog.i(ctx, TAG, "VERIFY lockSound: SUCCESS (MOUNTED_OK_COUNT present)")
                } else {
                    val reason = if (bindRes.second.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")) "root unavailable"
                                 else if (bindRes.second.contains("Permission denied")) "Permission denied"
                                 else "rc=${bindRes.first}: ${bindRes.second.take(200)}"
                    recordBindFailure(reason, appCtx, deterministic = failDeterm)
                }
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "applySystemLockSoundBind error: ${t.message}", t)
                recordBindFailure("exception: ${t.message}", appCtx, deterministic = false)
            }
        }
    }

    private fun recordBindFailure(reason: String, ctx: Context, deterministic: Boolean) {
        lastBindFailReason = reason
        AppLog.e(ctx, TAG, "VERIFY lockSound: FAILED ($reason), deterministic=$deterministic")
        if (!deterministic) {
            // Временная ошибка (таймаут, рестарт adbd) — увеличиваем счётчик, но не отключаем.
            bindFailCount += 1
            if (bindFailCount < MAX_BIND_RETRIES) {
                AppLog.i(ctx, TAG, "lockSound retry scheduled (attempt ${bindFailCount+1}/$MAX_BIND_RETRIES)")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val path = getSelectedPath(ctx)
                    if (path.isNotEmpty() && File(path).exists()) applySystemLockSoundBind(ctx, File(path))
                }, (5000L * (1 shl (bindFailCount - 1).coerceAtMost(3)))) // exponential backoff 5/10/20s
                return
            }
        }
        // Детерминированная ошибка или кончились повторы — отключаем тумблер,
        // пишем причину и больше не пытаемся до ребута.
        bindDisabledUntilBoot = true
        AppLog.e(ctx, TAG, "lockSound отключён до следующей загрузки: $reason")
        try {
            ctx.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_LOCK_SOUND_ENABLED, false).apply()
        } catch (_: Throwable) {}
        // TODO: показать нотификацию/Toast пользователю с причиной (след. итерация)
    }

    private fun restoreSystemLockSoundBind(ctx: Context) {
        soundExecutor.execute {
            try {
                val script = """
                    for f in /vendor/etc/carlock/*.wav /system/etc/avas/sound_type_*/*.wav; do
                        for _i in 1 2 3 4; do
                            grep -F " ${"$" + "f"} " /proc/mounts >/dev/null 2>&1 || break
                            umount "${"$" + "f"}" 2>/dev/null || umount -l "${"$" + "f"}" 2>/dev/null || true
                        done
                    done
                    killall -9 audioserver 2>/dev/null || true
                    pkill -9 -f avas 2>/dev/null || true
                    echo "MOUNTS_LEFT=${'$'}(grep carlock /proc/mounts | wc -l)"
                """.trimIndent()
                val res = AdbClient.execute(script, 20000)
                AppLog.i(ctx, TAG, "restoreSystemLockSoundBind [exitCode=${res.first}]: ${res.second.trim()}")
            } catch (t: Throwable) {
                AppLog.w(ctx, TAG, "restoreSystemLockSoundBind error: ${t.message}")
            }
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
