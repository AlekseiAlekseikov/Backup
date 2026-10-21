package com.example.voiceapp3.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream

/**
 * Срез «Звук блокировки и разблокировки замка» (Автомобиль → Аудио).
 *
 * ## Как это сделано в референсе (CentralEXAuto v3.3.9, по факту кода)
 *
 * CentralEXAuto звук замка **не воспроизводит**. В движке его плейбуков
 * (`smali/w0/ie.smali`, метод `c`) набор операций такой: `settingsPut`,
 * `copyFile`, `rmFile`, `chmod/chown/chconFile`, `restorecon`, `mountBind`,
 * `umountBind`, `setprop`, `getprop`, `pmInstall`, `amStart`, `broadcast`,
 * `serviceCall`, `shellTemplate`, `sleep`, `waitFor`, `setVar/getVar`,
 * `bind`, `initsvc`, `logcat`, `md5` — операции «играть звук» там нет вообще.
 * Механизм штатный: **записать ключ и файл, играет система ГУ**.
 *
 * Ключи (единственные в allowlist `settingsPut` у референса, `w0/p.java:30`,
 * домены `system|global|secure` — `w0/p.java:28`):
 *  - `settings system lock_car_audio_path`  — путь к wav;
 *  - `settings system lock_car_audio_enable` — флаг; при откате к заводскому
 *    состоянию референс пишет `settings put system lock_car_audio_enable 0`
 *    (`w0/u5.java:142`).
 *
 * Файл: `/data/local/tmp/sons/<имя>.wav`, каталог `chmod 755`, файл
 * `chown root:root` + `chmod 644` + `restorecon -v`
 * (`com/ex/auto/PhoneSyncService.java:861-873`, `w0/t8.java:148-160`,
 * `w0/y7.java:905-940`). Лимит размера 25 МБ (`PhoneSyncService.java:838`).
 *
 * Привилегии: shell-команды референс гоняет через собственный adb-клиент на
 * `127.0.0.1:5555` (`kotlinx/coroutines/scheduling/h.java:99`, banner
 * `host::central-geely`, лимит payload 4096 байт). У GeelyTools тот же канал —
 * [AdbClient]; `sharedUserId="android.uid.system"` нам для этого не нужен.
 *
 * ## Осознанные отклонения от референса
 *
 * 1. **Никаких bind-монтирований поверх заводских wav в разделах вендора,
 *    перевода SELinux в разрешительный режим, смены контекста на
 *    `vendor_configs_file` и убийства `audioserver`.** Референс это прямо
 *    запрещает: `w0/ie.b()` бросает `RECUSADO: toca em partição de sistema`
 *    на любую запись в `/system|/vendor|/product|/system_ext|/odm`
 *    (regex `w0/ie.java:17`), а разрешённые SELinux-контексты применяются
 *    только к файлам в `/data/local/tmp` (`w0/p.java:33`).
 * 2. **Режим один — «persistente».** У референса два действия:
 *    `audio.aplicarSomTrava` («до перезагрузки») и
 *    `audio.aplicarSomTravaPersistente`. Значения `settings` на Android сами
 *    переживают перезагрузку, а boot-приёмник плюс watchdog повторно
 *    применяют их ([reapplyAfterBoot], [ensureApplied]), поэтому отдельный
 *    «временный» режим не нужен: он отличался бы только откатом на boot.
 * 3. **Полярность `lock_car_audio_enable` проверяется на стенде.** Дамп
 *    прошивки получить не удалось (gofile: 401 error-notPremium), поэтому
 *    фактическое заводское значение читается ДО записи и кладётся в шаг
 *    `factory_state` и в logcat: первый же прогон на ГУ показывает семантику.
 *    Пишем `1` при включённом кастоме, `0` при сбросе — по смыслу ключа и по
 *    тому, что откат референса пишет `0`.
 * 4. **Звук РАЗблокировки.** В референсе его нет (ни ключа `unlock_*`, ни
 *    подписки на состояние замка — проверено по всем VHAL prop-id приложения).
 *    Поэтому открытие/закрытие играет приложение по событию замка:
 *    [LockStateTrigger] + [LockTransition] + [LockSoundPlayer].
 */
object LockSound {
    const val TAG = "GeelyTools"

    /** Каталог референса: его читает проигрывающий процесс ГУ. */
    const val SOUNDS_DIR = "/data/local/tmp/sons"

    /** Ключи реестра ГУ (домен `system`) — те же, что у референса. */
    const val KEY_LOCK_PATH = "lock_car_audio_path"
    const val KEY_LOCK_ENABLE = "lock_car_audio_enable"

    /**
     * Ключи звука разблокировки. В прошивке их нет (референс их не знает) —
     * это СОСТОЯНИЕ ПРИЛОЖЕНИЯ в домене `system`, а не контракт ГУ: чтение
     * обратно всегда даёт фактическое значение, поэтому «сохранение» честное.
     */
    const val KEY_UNLOCK_PATH = "geelytools_unlock_audio_path"
    const val KEY_UNLOCK_ENABLE = "geelytools_unlock_audio_enable"

    /** Локальные ключи выбора (имя файла, громкость, задержка) — SharedPreferences. */
    const val PREF_FILE = "voiceapp3"
    const val PREF_LOCK_NAME = "lock_car_audio_display_name"
    const val PREF_UNLOCK_NAME = "unlock_car_audio_display_name"
    const val PREF_VOLUME = "lock_car_audio_volume"
    const val PREF_DELAY = "lock_car_audio_delay"
    const val PREF_APPLY_MODE = "master_carlock_apply_mode"

    /**
     * Ключи СОСТОЯНИЯ ТУМБЛЕРОВ в SharedPreferences — их использует
     * [ToolsBackend.Toggle] (`LOCK_SOUND`, `UNLOCK_SOUND`). Значения совпадают
     * с именами ключей реестра намеренно и повторяют базовую версию файла:
     * тогда уже сохранённое состояние тумблеров переживает обновление.
     *
     * Это разные хранилища и путать их нельзя: тумблер живёт в файле
     * [PREF_FILE] приложения, а фактическое применение — в `Settings.System`
     * под ключами [KEY_LOCK_ENABLE] и [KEY_UNLOCK_ENABLE]. Чтение обратно
     * всегда идёт из реестра, поэтому подпись под строкой в UI честная.
     */
    const val PREF_LOCK_SOUND_ENABLED = "lock_car_audio_enable"
    const val PREF_UNLOCK_SOUND_ENABLED = "unlock_car_audio_enable"

    const val APPLY_MODE_PERSISTENT = "persistent"

    /** Лимит референса: 25 МБ (`PhoneSyncService.java:838`). */
    const val MAX_SIZE_BYTES = 25L * 1024 * 1024
    const val MIN_SIZE_BYTES = 100L

    val ALLOWED_EXT = setOf("wav", "mp3", "ogg", "flac", "m4a", "aac")

    const val DEFAULT_VOLUME = 80
    const val DEFAULT_DELAY_MS = 0
    val DELAY_CHOICES = setOf("0", "150", "300")

    /**
     * Литеральный `$` для shell-скриптов: в Kotlin-строке его пришлось бы
     * экранировать на каждом вхождении, а скрипт копии USB состоит из них
     * целиком (`$root`, `$f`, `$base`, `$copied`).
     */
    private val DOLLAR: String = "${'$'}"

    /** Лимит adb-payload референса (`h.java:92`): `shell:` + скрипт + NUL. */
    private const val ADB_PAYLOAD_LIMIT = 4096

    /** Через сколько кеш списка звуков считается устаревшим. */
    private const val SOUNDS_CACHE_TTL_MS = 3000L

    /** Канал выполнения привилегированных команд. Заменяется в тестах. */
    var shell: (String, Int) -> Pair<Int, String> = { cmd, ms -> AdbClient.execute(cmd, ms) }

    private var previewPlayer: MediaPlayer? = null

    // ──────────────────────────────────────────────────────────────
    // Реестр: запись только через shell, чтение — из реестра
    // ──────────────────────────────────────────────────────────────

    /** Фактическое значение ключа из реестра ГУ (не из состояния WebView). */
    fun registryString(ctx: Context, key: String): String? {
        val viaApi = try {
            Settings.System.getString(ctx.contentResolver, key)
        } catch (t: Throwable) {
            log(ctx, "registryString($key) Settings.System: ${describeThrowable(t)}")
            null
        }
        if (viaApi != null) return viaApi.trim().ifEmpty { null }
        val (code, out) = shell("settings get system '${quote(key)}'", 8000)
        val v = out.trim()
        return if (code == 0 && v.isNotEmpty() && v != "null") v else null
    }

    fun registryInt(ctx: Context, key: String, fallback: Int): Int =
        registryString(ctx, key)?.toIntOrNull() ?: fallback

    /** Запись ключа реестра. Возвращает пару «код → вывод». */
    private fun putSystem(ctx: Context, key: String, value: String): Pair<Int, String> =
        shell("settings put system '${quote(key)}' '${quote(value)}'", 8000)

    private fun deleteSystem(ctx: Context, key: String): Pair<Int, String> =
        shell("settings delete system '${quote(key)}'", 8000)

    // ──────────────────────────────────────────────────────────────
    // Состояние для tools.state — всё из реестра
    // ──────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** Включён ли кастомный звук закрытия — по фактическому значению ключа ГУ. */
    fun isEnabled(ctx: Context): Boolean = registryInt(ctx, KEY_LOCK_ENABLE, 0) == 1

    /** Путь, который сейчас записан в реестре ГУ (источник истины для UI). */
    fun getSelectedPath(ctx: Context): String = registryString(ctx, KEY_LOCK_PATH) ?: ""

    /** Имя выбранного файла закрытия — из пути в реестре, а не из локального кэша. */
    fun getDisplayName(ctx: Context): String {
        val p = getSelectedPath(ctx)
        if (p.isNotEmpty()) return p.substringAfterLast('/')
        return prefs(ctx).getString(PREF_LOCK_NAME, "") ?: ""
    }

    fun isUnlockSoundEnabled(ctx: Context): Boolean = registryInt(ctx, KEY_UNLOCK_ENABLE, 0) == 1

    /**
     * Включить/выключить звук разблокировки.
     *
     * Отличие от звука закрытия: этот звук играет приложение по событию замка
     * ([LockStateTrigger]), а не прошивка. Ключи поэтому наши
     * (`geelytools_unlock_audio_*`), но канал тот же — запись через shell,
     * readback из реестра, откат при FAILED.
     */
    fun setUnlockSoundEnabled(ctx: Context, enabled: Boolean): ToolsResult {
        val steps = ArrayList<ToolsStep>(4)
        if (!enabled) {
            val (c, o) = putSystem(ctx, KEY_UNLOCK_ENABLE, "0")
            val rb = registryString(ctx, KEY_UNLOCK_ENABLE)
            steps += step("write_registry", "settings put system $KEY_UNLOCK_ENABLE 0", c,
                stdout = o.trim(), stderr = if (rb == "0") "" else "после записи в реестре $KEY_UNLOCK_ENABLE=$rb",
                expected = "0", actual = rb ?: "null", ok = rb == "0",
                status = if (rb == "0") ToolsStep.Status.OK else ToolsStep.Status.FAILED)
            val res = result("settings.unlockSound", "Звук разблокировки", steps)
            AppLog.logToggle(ctx, "unlockSound", false, res)
            return res
        }
        val path = getUnlockSelectedPath(ctx)
        if (path.isEmpty()) {
            steps += step("resolve_file", "settings get system $KEY_UNLOCK_PATH", 1,
                stderr = "файл разблокировки не выбран: ключ $KEY_UNLOCK_PATH пуст",
                status = ToolsStep.Status.FAILED)
            val res = result("settings.unlockSound", "Звук разблокировки", steps)
            AppLog.logToggle(ctx, "unlockSound", true, res)
            return res
        }
        steps += stageFileStep(ctx, path)
        steps += writeKeysStep(ctx, KEY_UNLOCK_PATH, path, KEY_UNLOCK_ENABLE, "1")
        val rbPath = registryString(ctx, KEY_UNLOCK_PATH)
        val rbEn = registryString(ctx, KEY_UNLOCK_ENABLE)
        val ok = rbPath == path && rbEn == "1"
        steps += step("readback", "Settings.System.getString($KEY_UNLOCK_PATH)", 0,
            stdout = "$KEY_UNLOCK_PATH=$rbPath; $KEY_UNLOCK_ENABLE=$rbEn",
            stderr = if (ok) "" else "readback не совпал с записанным ($path / 1)",
            expected = "$path|1", actual = "$rbPath|$rbEn", ok = ok,
            status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED)
        val prop = LockStateTrigger.lockPropId(ctx)
        steps += step("trigger_source", "settings get system ${LockStateTrigger.KEY_LOCK_PROP}", 0,
            stdout = if (prop != 0)
                "свойство замка 0x" + Integer.toHexString(prop) + " ($prop)" +
                    if (prop == LockStateTrigger.DEFAULT_LOCK_PROP_BODY_DOOR_LOCK_STATE)
                        " — BODY_DOOR_LOCK_STATE из дампа прошивки (car_service_dump.txt), " +
                            "полярность не проверена: подтвердите подбором сигнала"
                    else " — задано вручную"
                     else "свойство замка не задано — подписки нет, звука не будет",
            stderr = if (prop != 0) "" else
                "не задан источник события: выполните подбор сигнала в этой же строке и запишите id " +
                    "(settings put system ${LockStateTrigger.KEY_LOCK_PROP} <id>). Состояние дверей " +
                    "(DOOR_POS 0x16400B00) источником быть не может",
            ok = true,
            status = if (prop != 0) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL)
        val res = finish(ctx, "settings.unlockSound", "Звук разблокировки", steps, null, null)
        AppLog.logToggle(ctx, "unlockSound", true, res)
        return res
    }

    /** Сброс звука разблокировки к заводскому (удаление нашего ключа). */
    fun restoreFactoryUnlock(ctx: Context): ToolsResult {
        val (c1, o1) = deleteSystem(ctx, KEY_UNLOCK_PATH)
        val (c2, o2) = putSystem(ctx, KEY_UNLOCK_ENABLE, "0")
        val rbPath = registryString(ctx, KEY_UNLOCK_PATH)
        val rbEn = registryString(ctx, KEY_UNLOCK_ENABLE)
        val ok = rbPath == null && rbEn == "0"
        prefs(ctx).edit().remove(PREF_UNLOCK_NAME).commit()
        val res = result("audio.restoreFactoryUnlock", "Сброс звука разблокировки", listOf(
            step("restore_factory",
                "settings delete system $KEY_UNLOCK_PATH && settings put system $KEY_UNLOCK_ENABLE 0",
                if (c1 == 0 && c2 == 0) 0 else 1,
                stdout = (o1 + "\n" + o2).trim(),
                stderr = if (ok) "" else "после сброса $KEY_UNLOCK_PATH=$rbPath, $KEY_UNLOCK_ENABLE=$rbEn",
                expected = "null|0", actual = "$rbPath|$rbEn", ok = ok,
                status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED)
        ))
        AppLog.logAction(ctx, "audio.restoreFactoryUnlock", res)
        return res
    }

    fun getUnlockSelectedPath(ctx: Context): String = registryString(ctx, KEY_UNLOCK_PATH) ?: ""

    fun getUnlockDisplayName(ctx: Context): String {
        val p = getUnlockSelectedPath(ctx)
        if (p.isNotEmpty()) return p.substringAfterLast('/')
        return prefs(ctx).getString(PREF_UNLOCK_NAME, "") ?: ""
    }

    fun getVolume(ctx: Context): Int =
        prefs(ctx).getInt(PREF_VOLUME, DEFAULT_VOLUME).coerceIn(5, 100)

    fun getDelayMs(ctx: Context): Int = prefs(ctx).getInt(PREF_DELAY, DEFAULT_DELAY_MS)

    /** Файлы в стабильном каталоге — их читает проигрывающий процесс ГУ. */
    /**
     * Кеш списка звуков.
     *
     * `ToolsBackend.state()` вызывается после КАЖДОГО действия интерфейса и из
     * `@JavascriptInterface appState()` при загрузке страницы. Shell-вызов в
     * этом пути блокировал JS-поток WebView на 8 секунд (плюс 3 секунды на
     * попытку соединения с 127.0.0.1:5555), а интерфейс приложения целиком
     * живёт в WebView — поэтому страница висла при любом нажатии. В базовой
     * версии здесь был `File.listFiles()`, то есть микросекунды; на shell
     * пришлось перейти из-за переноса каталога в [SOUNDS_DIR], куда обычное
     * приложение через `File` не смотрит.
     *
     * Теперь: мгновенный возврат кеша, обновление в фоне или явно из
     * обработчика, который сам уже работает в фоновом потоке.
     */
    @Volatile private var soundsCache: List<String> = emptyList()
    @Volatile private var soundsCacheAtMs: Long = 0L
    @Volatile private var soundsRefreshRunning = false

    private val soundsExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "geelytools-sounds").apply { isDaemon = true }
        }

    /** Список звуков из кеша. Вызывающий поток НЕ блокирует никогда. */
    fun getAvailableSoundList(ctx: Context): List<String> {
        val age = android.os.SystemClock.elapsedRealtime() - soundsCacheAtMs
        if (soundsCacheAtMs == 0L || age > SOUNDS_CACHE_TTL_MS) refreshSoundsAsync(ctx)
        return soundsCache
    }

    /** Фоновое обновление кеша; повторные запросы схлопываются. */
    fun refreshSoundsAsync(ctx: Context) {
        if (soundsRefreshRunning) return
        soundsRefreshRunning = true
        soundsExecutor.execute {
            try {
                refreshSoundsBlocking(ctx)
            } catch (t: Throwable) {
                log(ctx, "refreshSoundsAsync: " + describeThrowable(t))
            } finally {
                soundsRefreshRunning = false
            }
        }
    }

    /**
     * Обновление кеша синхронно. Вызывать ТОЛЬКО из фонового потока: после
     * переноса `UiBridge.onEvent` на исполнитель обработчики событий фоновые,
     * поэтому внутри действия кеш можно обновить до `pushToolsState()`.
     */
    fun refreshSoundsBlocking(ctx: Context) {
        val (code, out) = shell("ls -1 '$SOUNDS_DIR' 2>/dev/null", 8000)
        soundsCache = if (code == 0 && out.isNotBlank()) {
            out.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("No such file") }
                .distinct().sorted().toList()
        } else {
            log(ctx, "refreshSounds: shell недоступен (code=$code, " + out.trim() + "), список пуст")
            emptyList()
        }
        soundsCacheAtMs = android.os.SystemClock.elapsedRealtime()
    }

    // ──────────────────────────────────────────────────────────────
    // Громкость и задержка (действуют на звук, который играет приложение)
    // ──────────────────────────────────────────────────────────────

    fun setVolume(ctx: Context, volume: Int): ToolsResult {
        val clamped = volume.coerceIn(5, 100)
        prefs(ctx).edit().putInt(PREF_VOLUME, clamped).commit()
        val actual = getVolume(ctx)
        return result(
            "settings.lockSoundVol", "Громкость звука замка",
            listOf(
                step("persist", "SharedPreferences[${"$"}PREF_FILE].putInt(${"$"}PREF_VOLUME, $clamped)", 0,
                    stdout = "громкость=$actual%", expected = clamped.toString(), actual = actual.toString(),
                    ok = actual == clamped)
            )
        )
    }

    fun setDelay(ctx: Context, delayMs: Int): ToolsResult {
        val valid = if (delayMs.toString() in DELAY_CHOICES) delayMs else DEFAULT_DELAY_MS
        prefs(ctx).edit().putInt(PREF_DELAY, valid).commit()
        val actual = getDelayMs(ctx)
        return result(
            "settings.lockSoundDelay", "Задержка звука замка",
            listOf(
                step("persist", "SharedPreferences[${"$"}PREF_FILE].putInt(${"$"}PREF_DELAY, $valid)", 0,
                    stdout = "задержка=${actual} мс", expected = valid.toString(), actual = actual.toString(),
                    ok = actual == valid)
            )
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Применение звука закрытия: ключ + файл, играет система
    // ──────────────────────────────────────────────────────────────

    /**
     * Включить/выключить кастомный звук закрытия.
     *
     * Порядок шагов повторяет плейбук референса: подготовка каталога →
     * файл в стабильном пути с правами и контекстом → запись ключей →
     * readback из реестра. При любом FAILED ранее записанное откатывается.
     */
    fun setEnabled(ctx: Context, enabled: Boolean): ToolsResult {
        val steps = ArrayList<ToolsStep>(6)
        val beforeEnable = registryString(ctx, KEY_LOCK_ENABLE)
        val beforePath = registryString(ctx, KEY_LOCK_PATH)

        if (!enabled) {
            steps += restoreFactorySteps(ctx)
            return finish(ctx, "settings.lockSound", "Звук блокировки замка", steps, beforeEnable, beforePath)
        }

        val path = getSelectedPath(ctx).ifEmpty { prefs(ctx).getString(PREF_LOCK_NAME, "")?.let { n ->
            if (n.isNotEmpty()) "$SOUNDS_DIR/$n" else ""
        } ?: "" }
        if (path.isEmpty()) {
            steps += step("resolve_file", "settings get system $KEY_LOCK_PATH", 1,
                stderr = "файл не выбран: ключ $KEY_LOCK_PATH пуст. Скопируйте аудио с USB и выберите файл",
                status = ToolsStep.Status.FAILED)
            return finish(ctx, "settings.lockSound", "Звук блокировки замка", steps, beforeEnable, beforePath)
        }

        steps += stageFileStep(ctx, path)
        if (steps.last().status == ToolsStep.Status.FAILED) {
            return finish(ctx, "settings.lockSound", "Звук блокировки замка", steps, beforeEnable, beforePath)
        }

        steps += factoryStateStep(ctx)
        steps += writeKeysStep(ctx, KEY_LOCK_PATH, path, KEY_LOCK_ENABLE, "1")

        val rbPath = registryString(ctx, KEY_LOCK_PATH)
        val rbEnable = registryString(ctx, KEY_LOCK_ENABLE)
        val okPath = rbPath == path
        val okEnable = rbEnable == "1"
        steps += step(
            "readback", "Settings.System.getString($KEY_LOCK_PATH) / getString($KEY_LOCK_ENABLE)", 0,
            stdout = "$KEY_LOCK_PATH=$rbPath; $KEY_LOCK_ENABLE=$rbEnable",
            stderr = if (okPath && okEnable) "" else
                "readback не совпал: ожидалось $KEY_LOCK_PATH=$path, $KEY_LOCK_ENABLE=1",
            expected = "$path|1", actual = "$rbPath|$rbEnable",
            ok = okPath && okEnable,
            status = if (okPath && okEnable) ToolsStep.Status.OK else ToolsStep.Status.FAILED
        )

        if (!(okPath && okEnable)) {
            prefs(ctx).edit().remove(PREF_LOCK_NAME).commit()
        } else {
            prefs(ctx).edit()
                .putString(PREF_LOCK_NAME, path.substringAfterLast('/'))
                .putString(PREF_APPLY_MODE, APPLY_MODE_PERSISTENT)
                .commit()
        }
        return finish(ctx, "settings.lockSound", "Звук блокировки замка", steps, beforeEnable, beforePath)
    }

    /** Сброс к заводскому: ключ пути удаляется, флаг — 0 (как в откате референса). */
    fun restoreFactory(ctx: Context): ToolsResult {
        val beforeEnable = registryString(ctx, KEY_LOCK_ENABLE)
        val beforePath = registryString(ctx, KEY_LOCK_PATH)
        val steps = restoreFactorySteps(ctx)
        prefs(ctx).edit().remove(PREF_LOCK_NAME).commit()
        return finish(ctx, "audio.restaurarSomTravaPersistente", "Сброс к заводскому звуку", steps, beforeEnable, beforePath)
    }

    private fun restoreFactorySteps(ctx: Context): List<ToolsStep> {
        val out = ArrayList<ToolsStep>(3)
        val (codeDel, outDel) = deleteSystem(ctx, KEY_LOCK_PATH)
        val (codeEn, outEn) = putSystem(ctx, KEY_LOCK_ENABLE, "0")
        val rbPath = registryString(ctx, KEY_LOCK_PATH)
        val rbEnable = registryString(ctx, KEY_LOCK_ENABLE)
        val ok = rbPath == null && rbEnable == "0"
        out += step(
            "restore_factory",
            "settings delete system $KEY_LOCK_PATH && settings put system $KEY_LOCK_ENABLE 0",
            if (codeDel == 0 && codeEn == 0) 0 else 1,
            stdout = (outDel + "\n" + outEn).trim(),
            stderr = if (ok) "" else "после сброса в реестре $KEY_LOCK_PATH=$rbPath, $KEY_LOCK_ENABLE=$rbEnable",
            expected = "null|0", actual = "$rbPath|$rbEnable",
            ok = ok, status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED
        )
        return out
    }

    /** Шаг: файл уже в стабильном каталоге, с правами и контекстом как у референса. */
    private fun stageFileStep(ctx: Context, path: String): ToolsStep {
        val name = path.substringAfterLast('/')
        val script = listOf(
            "mkdir -p '$SOUNDS_DIR' 2>/dev/null || exit 20",
            "chmod 755 '$SOUNDS_DIR' 2>/dev/null || true",
            "[ -f '$path' ] || { echo \"NO_FILE $path\"; exit 21; }",
            "sz=" + DOLLAR + "(stat -c %s '$path' 2>/dev/null || echo 0)",
            "[ \"" + DOLLAR + "sz\" -ge $MIN_SIZE_BYTES ] || { echo \"TOO_SMALL " + DOLLAR + "sz\"; exit 22; }",
            "[ \"" + DOLLAR + "sz\" -le $MAX_SIZE_BYTES ] || { echo \"TOO_BIG " + DOLLAR + "sz\"; exit 23; }",
            "chown root:root '$path' 2>/dev/null || true",
            "chmod 644 '$path' 2>/dev/null || true",
            "restorecon -v '$path' 2>/dev/null || true",
            "echo \"SIZE=" + DOLLAR + "sz\"",
            "ls -l '$SOUNDS_DIR/$name' 2>&1"
        ).joinToString("\n")
        val (code, out) = shell(script, 20000)
        val sizeOk = Regex("SIZE=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val ok = code == 0 && sizeOk in MIN_SIZE_BYTES..MAX_SIZE_BYTES && out.contains("$SOUNDS_DIR/$name")
        return step(
            "stage_file", script.lineSequence().take(3).joinToString(" && ") + " …", code,
            stdout = out.trim(),
            stderr = when {
                ok -> ""
                sizeOk < 0 -> "размер файла не прочитан (stat недоступен или файла нет): ${out.trim()}"
                else -> "файл не подтверждён в $SOUNDS_DIR: code=$code, ${out.trim()}"
            },
            expected = "файл $name, $MIN_SIZE_BYTES..$MAX_SIZE_BYTES байт",
            actual = if (sizeOk >= 0) "файл $name, $sizeOk байт" else "нет файла",
            ok = ok, status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED
        )
    }

    /** Диагностический шаг: что в реестре было ДО записи (проверка полярности на стенде). */
    private fun factoryStateStep(ctx: Context): ToolsStep {
        val en = registryString(ctx, KEY_LOCK_ENABLE) ?: "null"
        val pa = registryString(ctx, KEY_LOCK_PATH) ?: "null"
        val msg = "до записи: $KEY_LOCK_ENABLE=$en, $KEY_LOCK_PATH=$pa"
        log(ctx, "factoryState: $msg")
        return step("factory_state", "settings get system $KEY_LOCK_ENABLE; settings get system $KEY_LOCK_PATH", 0,
            stdout = msg, expected = msg, actual = msg, ok = true)
    }

    private fun writeKeysStep(ctx: Context, pathKey: String, path: String, enableKey: String, enable: String): ToolsStep {
        val (c1, o1) = putSystem(ctx, pathKey, path)
        val (c2, o2) = putSystem(ctx, enableKey, enable)
        val ok = c1 == 0 && c2 == 0
        return step(
            "write_registry",
            "settings put system $pathKey '$path' && settings put system $enableKey $enable",
            if (ok) 0 else 1,
            stdout = (o1 + "\n" + o2).trim(),
            stderr = if (ok) "" else
                "запись ключей вернула code=$c1/$c2. Нужен adbd на 127.0.0.1:5555 " +
                    "(у референса тот же канал). Вывод: ${(o1 + " " + o2).trim()}",
            expected = "$path|$enable", actual = "$c1|$c2", ok = ok,
            status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED
        )
    }

    /**
     * Единая точка завершения: при FAILED откатывает записанное и пишет в logcat
     * полный текст каждого неуспешного шага.
     */
    private fun finish(
        ctx: Context,
        action: String,
        title: String,
        steps: List<ToolsStep>,
        beforeEnable: String?,
        beforePath: String?
    ): ToolsResult {
        val failed = steps.filter { it.status == ToolsStep.Status.FAILED }
        if (failed.isNotEmpty()) {
            failed.forEach {
                log(ctx, "FAILED $action/${it.step}: exit=${it.exitCode} expected=${it.expected} " +
                    "actual=${it.actual} stderr=${it.stderr} stdout=${it.stdout}")
            }
            // Откат: не оставляем ГУ в полусостоянии, если применение не подтвердилось.
            if (beforePath != null) putSystem(ctx, KEY_LOCK_PATH, beforePath) else deleteSystem(ctx, KEY_LOCK_PATH)
            if (beforeEnable != null) putSystem(ctx, KEY_LOCK_ENABLE, beforeEnable)
        }
        val res = result(action, title, steps)
        AppLog.logAction(ctx, action, res)
        return res
    }

    // ──────────────────────────────────────────────────────────────
    // Переприменение после перезагрузки
    // ──────────────────────────────────────────────────────────────

    /**
     * Boot-ветка: повторно применить то, что было выбрано. Значения `settings`
     * переживают перезагрузку, но прошивка ГУ может сбрасывать свой ключ —
     * поэтому пишем снова и сверяем readback.
     */
    fun reapplyAfterBoot(ctx: Context) {
        val name = prefs(ctx).getString(PREF_LOCK_NAME, "") ?: ""
        val path = registryString(ctx, KEY_LOCK_PATH)
        val target = path ?: if (name.isNotEmpty()) "$SOUNDS_DIR/$name" else ""
        if (target.isEmpty()) {
            log(ctx, "reapplyAfterBoot: кастомный звук не выбран — нечего переприменять")
            return
        }
        val (code, out) = shell(
            "[ -f '$target' ] && echo PRESENT || echo MISSING", 8000
        )
        log(ctx, "reapplyAfterBoot: файл $target → ${out.trim()} (code=$code)")
        if (!out.contains("PRESENT")) {
            log(ctx, "reapplyAfterBoot: файла нет в $SOUNDS_DIR — кастомный звук не восстановлен, " +
                "требуется повторное копирование с USB")
            return
        }
        val res = setEnabled(ctx, true)
        log(ctx, "reapplyAfterBoot: применение после перезагрузки → ${res.summary}")
    }

    /**
     * Watchdog: если выбранный звук есть, а ключи ГУ разошлись — переприменить.
     * Вызывается из [LockStateTrigger] в его собственном цикле, отдельно от UI.
     */
    fun ensureApplied(ctx: Context): Boolean {
        val name = prefs(ctx).getString(PREF_LOCK_NAME, "") ?: ""
        if (name.isEmpty()) return true
        val want = "$SOUNDS_DIR/$name"
        val path = registryString(ctx, KEY_LOCK_PATH)
        val enable = registryString(ctx, KEY_LOCK_ENABLE)
        if (path == want && enable == "1") return true
        log(ctx, "ensureApplied: расхождение (path=$path, enable=$enable, ожидалось $want/1) — переприменяю")
        return setEnabled(ctx, true).ok
    }

    // ──────────────────────────────────────────────────────────────
    // Выбор файла
    // ──────────────────────────────────────────────────────────────

    /** Выбор из списка уже скопированных в [SOUNDS_DIR] файлов. */
    fun selectFileByName(ctx: Context, fileName: String, isLock: Boolean): ToolsResult {
        val action = if (isLock) "audio.selectSoundName" else "audio.selectUnlockSoundName"
        val title = if (isLock) "Звук блокировки" else "Звук разблокировки"
        val safe = sanitizeName(fileName)
        val path = "$SOUNDS_DIR/$safe"
        val (code, out) = shell("[ -f '$path' ] && stat -c %s '$path' 2>/dev/null || echo 0", 8000)
        val size = out.trim().substringAfterLast('\n').trim().toLongOrNull() ?: 0L
        if (code != 0 || size < MIN_SIZE_BYTES) {
            val res = result(action, title, listOf(
                step("find_file", "[ -f '$path' ] && stat -c %s '$path'", code,
                    stdout = out.trim(),
                    stderr = "файла $safe нет в $SOUNDS_DIR (размер=$size). Сначала «Копировать с USB»",
                    status = ToolsStep.Status.FAILED)
            ))
            AppLog.logAction(ctx, action, res)
            return res
        }
        val keyPath = if (isLock) KEY_LOCK_PATH else KEY_UNLOCK_PATH
        val keyEnable = if (isLock) KEY_LOCK_ENABLE else KEY_UNLOCK_ENABLE
        val nameKey = if (isLock) PREF_LOCK_NAME else PREF_UNLOCK_NAME
        prefs(ctx).edit().putString(nameKey, safe).commit()

        val steps = ArrayList<ToolsStep>(3)
        steps += writeKeysStep(ctx, keyPath, path, keyEnable, "1")
        val rb = registryString(ctx, keyPath)
        val rbEn = registryString(ctx, keyEnable)
        val ok = rb == path && rbEn == "1"
        steps += step("readback", "Settings.System.getString($keyPath)", 0,
            stdout = "$keyPath=$rb; $keyEnable=$rbEn",
            stderr = if (ok) "" else "readback не совпал с записанным ($path / 1)",
            expected = "$path|1", actual = "$rb|$rbEn", ok = ok,
            status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED)
        if (ok) steps += step("file_info", "stat -c %s '$path'", 0,
            stdout = "$safe, ${size / 1024} КБ", expected = "$safe", actual = "$safe", ok = true)
        val res = finish(ctx, action, title, steps, null, null)
        return res
    }

    fun selectFile(ctx: Context, filePath: String): ToolsResult =
        selectFileByName(ctx, File(filePath).name, isLock = true)

    fun selectUnlockFile(ctx: Context, filePath: String): ToolsResult =
        selectFileByName(ctx, File(filePath).name, isLock = false)

    /**
     * Выбор через системный `ACTION_OPEN_DOCUMENT`: копируем содержимое URI в
     * стабильный каталог [SOUNDS_DIR] и пишем те же ключи.
     *
     * `takePersistableUriPermission` нужен, чтобы файл можно было перечитать
     * после перезагрузки (boot-переприменение). Само воспроизведение делает
     * процесс ГУ из [SOUNDS_DIR], поэтому копия в закрытом каталоге приложения
     * не используется: туда проигрывающий процесс не дотянется.
     */
    fun selectFromUri(ctx: Context, uri: Uri, isLock: Boolean): ToolsResult {
        val action = if (isLock) "audio.selectFile" else "audio.selectUnlockFile"
        val title = if (isLock) "Звук блокировки" else "Звук разблокировки"
        val steps = ArrayList<ToolsStep>(5)

        var displayName = ""
        var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (i >= 0) displayName = c.getString(i) ?: ""
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (t: Throwable) {
            log(ctx, "selectFromUri: query(DISPLAY_NAME/SIZE) → ${describeThrowable(t)}")
        }
        if (displayName.isEmpty()) displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "sound.wav"
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext !in ALLOWED_EXT) {
            return failed(ctx, action, title, "resolve_uri",
                "ContentResolver.query($uri, DISPLAY_NAME)",
                "тип .$ext не поддерживается: нужен ${ALLOWED_EXT.sorted().joinToString("/")} (имя: $displayName)")
        }
        if (size in 0 until MIN_SIZE_BYTES || size > MAX_SIZE_BYTES) {
            return failed(ctx, action, title, "resolve_uri",
                "ContentResolver.query($uri, SIZE)",
                "размер $size байт вне диапазона $MIN_SIZE_BYTES..$MAX_SIZE_BYTES")
        }

        // Постоянное право чтения — иначе после перезагрузки URI не прочитать.
        var grant = "не выдавалось"
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            grant = "FLAG_GRANT_READ_URI_PERMISSION получен"
        } catch (t: Throwable) {
            grant = "takePersistableUriPermission: ${describeThrowable(t)}"
            log(ctx, "selectFromUri: $grant")
        }
        steps += step("grant_uri", "takePersistableUriPermission($uri, FLAG_GRANT_READ_URI_PERMISSION)", 0,
            stdout = grant, expected = "", actual = grant, ok = true)

        val safe = sanitizeName(displayName)
        val cache = File(ctx.cacheDir, "pick_$safe")
        var copied = -1L
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cache).use { output -> input.copyTo(output) }
            }
            copied = cache.length()
        } catch (t: Throwable) {
            return failed(ctx, action, title, "copy_to_cache",
                "ContentResolver.openInputStream($uri) → ${cache.absolutePath}", describeThrowable(t), steps)
        }
        if (copied < MIN_SIZE_BYTES || copied > MAX_SIZE_BYTES) {
            cache.delete()
            return failed(ctx, action, title, "copy_to_cache",
                "copy($uri → ${cache.absolutePath})",
                "скопировано $copied байт — вне диапазона $MIN_SIZE_BYTES..$MAX_SIZE_BYTES", steps)
        }
        steps += step("copy_to_cache", "openInputStream($uri) → ${cache.absolutePath}", 0,
            stdout = "скопировано $copied байт", expected = "1..$MAX_SIZE_BYTES", actual = copied.toString(), ok = true)

        val target = "$SOUNDS_DIR/$safe"
        val script = listOf(
            "mkdir -p '$SOUNDS_DIR' 2>/dev/null || exit 20",
            "chmod 755 '$SOUNDS_DIR' 2>/dev/null || true",
            "cp -f '${cache.absolutePath}' '$target' 2>&1 || exit 24",
            "chown root:root '$target' 2>/dev/null || true",
            "chmod 644 '$target' 2>/dev/null || true",
            "restorecon -v '$target' 2>/dev/null || true",
            "rm -f '${cache.absolutePath}' 2>/dev/null || true",
            "sz=$(stat -c %s '$target' 2>/dev/null || echo 0)",
            "echo \"SIZE=" + DOLLAR + "sz\"",
            "ls -l '$target' 2>&1"
        ).joinToString("\n")
        val (code, out) = shell(script, 25000)
        val staged = Regex("SIZE=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val okStage = code == 0 && staged == copied
        steps += step("stage_file", "cp '${cache.absolutePath}' '$target' + chown/chmod 644 + restorecon", code,
            stdout = out.trim(),
            stderr = if (okStage) "" else
                "файл не лёг в $SOUNDS_DIR: code=$code, на диске $staged байт из $copied. ${out.trim()}",
            expected = copied.toString(), actual = staged.toString(), ok = okStage,
            status = if (okStage) ToolsStep.Status.OK else ToolsStep.Status.FAILED)
        if (!okStage) {
            cache.delete()
            return finish(ctx, action, title, steps, null, null)
        }
        cache.delete()

        val keyPath = if (isLock) KEY_LOCK_PATH else KEY_UNLOCK_PATH
        val keyEnable = if (isLock) KEY_LOCK_ENABLE else KEY_UNLOCK_ENABLE
        steps += writeKeysStep(ctx, keyPath, target, keyEnable, "1")
        val rb = registryString(ctx, keyPath)
        val rbEn = registryString(ctx, keyEnable)
        val okRb = rb == target && rbEn == "1"
        steps += step("readback", "Settings.System.getString($keyPath)", 0,
            stdout = "$keyPath=$rb; $keyEnable=$rbEn",
            stderr = if (okRb) "" else "readback не совпал с записанным ($target / 1)",
            expected = "$target|1", actual = "$rb|$rbEn", ok = okRb,
            status = if (okRb) ToolsStep.Status.OK else ToolsStep.Status.FAILED)
        if (okRb) {
            prefs(ctx).edit()
                .putString(if (isLock) PREF_LOCK_NAME else PREF_UNLOCK_NAME, safe)
                .putString(PREF_APPLY_MODE, APPLY_MODE_PERSISTENT)
                .commit()
        }
        return finish(ctx, action, title, steps, null, null)
    }

    /**
     * Копирование аудио с USB — порт скрипта референса `w0/t8.java:148-160`:
     * те же корни, тот же набор расширений, те же `chown root:root` / `chmod 644`
     * / `restorecon -v`, каталог `chmod 755`.
     */
    fun copyFromUsb(ctx: Context): ToolsResult {
        val exts = ALLOWED_EXT.sorted()
        // -iname покрывает и верхний, и нижний регистр расширения одной веткой,
        // поэтому отдельный список для .WAV больше не нужен.
        val inames = StringBuilder()
        for (e in exts) {
            if (inames.isNotEmpty()) inames.append(" -o ")
            inames.append("-iname '*.").append(e).append("'")
        }
        /*
         * Поиск РЕКУРСИВНЫЙ, до 3 уровней вглубь. Прежняя версия перебирала
         * только маску "$root" + "*.ext", то есть файлы исключительно в корне
         * точки монтирования: звук, лежащий на флешке в папке, не находился
         * никогда, и интерфейс сообщал «USB-накопитель не найден или на нём
         * нет файлов».
         *
         * ВАЖНО: в этом комментарии намеренно нет последовательности «слэш +
         * звёздочка». Kotlin, в отличие от Java, поддерживает ВЛОЖЕННЫЕ блочные
         * комментарии, поэтому такая последовательность внутри комментария
         * открывает новый уровень, закрывающий «звёздочка + слэш» снимает
         * только его, а внешний остаётся незакрытым до конца файла. Один раз
         * это уже стоило сборки: компилятор сообщил «Unclosed comment» в конце
         * файла и «Unresolved reference» на все приватные члены объекта.
         *
         * Список находок пишется во временный файл и читается циклом
         * `while IFS= read -r` с перенаправлением, а НЕ конвейером: конвейер
         * создал бы подоболочку, и счётчик `copied` не вернулся бы наружу.
         * `read -d ''` в /system/bin/sh (mksh) отсутствует, поэтому разделитель
         * — перевод строки, а имена с переводами строк отсекает sanitizeName.
         */
        val script = listOf(
            "mkdir -p '$SOUNDS_DIR' 2>&1 || exit 20",
            "chmod 755 '$SOUNDS_DIR' 2>&1 || true",
            "L='$SOUNDS_DIR/.usblist'; : > \"" + DOLLAR + "L\" 2>/dev/null || L=/data/local/tmp/.usblist",
            "copied=0",
            "echo '--- просмотрено точек монтирования ---'",
            "for root in /storage/* /mnt/media_rw/* /mnt/usb* /mnt/usb/* " +
                "/mnt/external* /mnt/external/* /data/usb* /storage/*/usb*; do",
            "  [ -d \"" + DOLLAR + "root\" ] || continue",
            "  case \"" + DOLLAR + "root\" in /storage/emulated|/storage/self|/storage/emulated/*) continue ;; esac",
            "  echo \"  root=" + DOLLAR + "root\"",
            "  find \"" + DOLLAR + "root\" -maxdepth 3 -type f \\( " + inames.toString() +
                " \\) >> \"" + DOLLAR + "L\" 2>/dev/null || true",
            "done",
            "found=" + DOLLAR + "(wc -l < \"" + DOLLAR + "L\" 2>/dev/null || echo 0)",
            "echo \"found=" + DOLLAR + "found\"",
            "while IFS= read -r f; do",
            "  [ -f \"" + DOLLAR + "f\" ] || continue",
            "  base=" + DOLLAR + "{f##*/}; base=" + DOLLAR + "(echo \"" + DOLLAR + "base\" | tr ' ' '_')",
            "  cp -f \"" + DOLLAR + "f\" '$SOUNDS_DIR/'\"" + DOLLAR + "base\" 2>&1 || continue",
            "  chown root:root '$SOUNDS_DIR/'\"" + DOLLAR + "base\" 2>/dev/null || true",
            "  chmod 644 '$SOUNDS_DIR/'\"" + DOLLAR + "base\" 2>/dev/null || true",
            "  restorecon -v '$SOUNDS_DIR/'\"" + DOLLAR + "base\" 2>/dev/null || true",
            "  copied=" + DOLLAR + "((copied + 1))",
            "done < \"" + DOLLAR + "L\"",
            "rm -f \"" + DOLLAR + "L\" 2>/dev/null || true",
            "echo \"copied=" + DOLLAR + "copied\"",
            "ls -l '$SOUNDS_DIR' 2>&1 || true"
        ).joinToString("\n")
        if (script.length + 8 > ADB_PAYLOAD_LIMIT) {
            return failed(ctx, "audio.copyFromUsb", "Копирование аудио с USB", "build_script",
                "adb payload limit $ADB_PAYLOAD_LIMIT",
                "скрипт копирования получился ${script.length} байт — больше лимита adb (${ADB_PAYLOAD_LIMIT}); " +
                    "референс на этот случай предусматривал отдельный канал AdbBigScript, которого в v3.3.9 нет")
        }
        val (code, out) = shell(script, 60000)
        val n = Regex("copied=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val ok = code == 0 && n > 0
        val res = result(
            "audio.copyFromUsb", "Копирование аудио с USB",
            listOf(
                step("copy_usb_sounds", "cp /storage/* /mnt/media_rw/* → $SOUNDS_DIR (порт w0/t8.java)", code,
                    stdout = out.trim(),
                    stderr = when {
                        ok -> ""
                        n == 0 -> "USB-накопитель не найден или на нём нет " +
                            exts.joinToString("/").uppercase() +
                            " (просмотрены /storage/*, /mnt/media_rw/*, /mnt/usb*, /mnt/external*, " +
                            "/data/usb* на глубину до 3 уровней; список просмотренных точек — в выводе выше)"
                        else -> "shell вернул code=$code: ${out.trim()}"
                    },
                    expected = "copied>0", actual = "copied=$n", ok = ok,
                    status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED)
            )
        )
        refreshSoundsBlocking(ctx)
        AppLog.logAction(ctx, "audio.copyFromUsb", res)
        log(ctx, "copyFromUsb: copied=$n, code=$code")
        return res
    }

    // ──────────────────────────────────────────────────────────────
    // Предпрослушивание (в салоне — как у референса)
    // ──────────────────────────────────────────────────────────────

    /**
     * Псевдоним базовой версии: `preview(ctx)` = предпросмотр звука
     * БЛОКИРОВКИ. Сохранён, потому что на него ссылается `UiBridge`
     * (ветка `"audio.preview"`), а убирать работающий вызов из шелла смысла
     * нет — поведение то же, что у `playPreview(ctx, isLock = true)`.
     */
    fun preview(ctx: Context): ToolsResult = playPreview(ctx, isLock = true)

    /**
     * Прослушать выбранный файл в салоне.
     *
     * Референс делает ровно это: `MainActivity.Z0()` — обычный `MediaPlayer`
     * без `setAudioAttributes` и без выбора устройства
     * (`com/ex/auto/MainActivity.java:2627-2668`), а сам файл берёт из
     * `/system/etc/avas/sound_type_N/`, скопировав его в
     * `/data/local/tmp/avas_preview/preview.wav` (`w0/u8.java:67`).
     * Наружный динамик для предпросмотра референс НЕ использует.
     */
    fun playPreview(ctx: Context, isLock: Boolean): ToolsResult {
        val path = if (isLock) getSelectedPath(ctx) else getUnlockSelectedPath(ctx)
        val action = if (isLock) "audio.preview" else "audio.previewUnlock"
        if (path.isEmpty()) {
            return failed(ctx, action, "Прослушивание", "resolve_file",
                "settings get system ${if (isLock) KEY_LOCK_PATH else KEY_UNLOCK_PATH}",
                "файл не выбран — нечего воспроизводить")
        }
        /* Сначала пробуем сам файл (он уже world-readable 644), и только если
           процесс приложения его не смог прочитать — копию в preview-каталоге,
           как делает референс (w0/u8.java:67). */
        var playedFrom = path
        var ok = playInCabin(path)
        val staged = stagePreview(ctx, path)
        if (!ok && staged.second == 0) {
            playedFrom = PREVIEW_WAV
            ok = playInCabin(PREVIEW_WAV)
        }
        val res = result(action, "Прослушивание звука", listOf(
            step("stage_preview", "cp '$path' '$PREVIEW_WAV' + chmod 644 (порт w0/u8.java)", staged.second,
                stdout = staged.third.trim(),
                stderr = if (staged.second == 0) "" else "preview-копия не подготовлена: ${staged.third.trim()}",
                ok = staged.second == 0,
                status = if (staged.second == 0) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL),
            step("playback", "MediaPlayer.setDataSource($playedFrom).start()", if (ok) 0 else 1,
                stdout = if (ok) "воспроизведено в салоне из $playedFrom" else "",
                stderr = if (ok) "" else
                    "MediaPlayer не смог воспроизвести ни $path, ни $PREVIEW_WAV (см. logcat тег $TAG)",
                ok = ok, status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED)
        ))
        AppLog.logAction(ctx, action, res)
        return res
    }

    const val PREVIEW_DIR = "/data/local/tmp/avas_preview"
    const val PREVIEW_WAV = "$PREVIEW_DIR/preview.wav"

    private fun stagePreview(ctx: Context, path: String): Triple<String, Int, String> {
        val script = "mkdir -p '$PREVIEW_DIR'\n" +
            "cp -f '$path' '$PREVIEW_WAV' 2>&1\n" +
            "chmod 644 '$PREVIEW_WAV' 2>/dev/null || true\n" +
            "echo OK"
        val (code, out) = shell(script, 15000)
        return Triple(PREVIEW_WAV, if (out.contains("OK") && code == 0) 0 else 1, out)
    }

    /** Салонное воспроизведение: обычный MediaPlayer, как `MainActivity.Z0()`. */
    fun playInCabin(path: String): Boolean {
        return try {
            previewPlayer?.release()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(path)
            mp.setOnCompletionListener { it.release(); if (previewPlayer === it) previewPlayer = null }
            mp.setOnErrorListener { it, what, extra ->
                it.release(); if (previewPlayer === it) previewPlayer = null
                android.util.Log.w(TAG, "playInCabin error what=$what extra=$extra ($path)")
                true
            }
            mp.prepare()
            mp.start()
            previewPlayer = mp
            true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "playInCabin($path) → ${describeThrowable(t)}", t)
            false
        }
    }

    fun stopPreview() {
        try { previewPlayer?.release() } catch (_: Throwable) {}
        previewPlayer = null
    }

    // ──────────────────────────────────────────────────────────────
    // Служебное
    // ──────────────────────────────────────────────────────────────

    /** Имя файла: пробелы в `_` (как у референса), кавычки и `/` убираем. */
    fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').trim().replace(' ', '_')
        val clean = base.map { c ->
            if (c == '\'' || c == '"' || c == '\\' || c == '/' || c == '`' || c == '$' ||
                c == ';' || c == '&' || c == '|' || c == '<' || c == '>' || c == '\n') '_' else c
        }.joinToString("").trim('_')
        val dotted = if (clean.isEmpty()) "sound.wav" else clean
        if (dotted.length <= 96) return dotted
        val tail = dotted.substringAfterLast('.', "")
        return dotted.take(80) + "_" + dotted.hashCode().toUInt().toString(16) +
            (if (tail.isEmpty()) "" else ".$tail")
    }

    /** Одинарная кавычка для sh: `'` → `'\''`. */
    private fun quote(s: String): String = s.replace("'", "'\\''")

    private fun log(ctx: Context, msg: String) {
        AppLog.i(ctx, TAG, msg)
    }


    private fun step(
        name: String,
        command: String,
        exit: Int,
        stdout: String = "",
        stderr: String = "",
        expected: String = "",
        actual: String = "",
        ok: Boolean = exit == 0,
        status: ToolsStep.Status = ToolsStep.Status.of(ok, expected.isEmpty() || actual == expected)
    ): ToolsStep {
        val t = SystemClock.elapsedRealtime()
        return ToolsStep(
            step = name, command = command, startedAtMs = t, finishedAtMs = t,
            exitCode = exit, stdout = stdout, stderr = stderr,
            expected = expected, actual = actual.ifEmpty { stdout }, status = status
        )
    }

    private fun result(action: String, title: String, steps: List<ToolsStep>) =
        ToolsResult(action = action, title = title, steps = steps)

    private fun failed(
        ctx: Context,
        action: String,
        title: String,
        stepName: String,
        command: String,
        why: String,
        extra: List<ToolsStep> = emptyList()
    ): ToolsResult {
        val res = result(action, title, extra + step(stepName, command, 1, stderr = why,
            status = ToolsStep.Status.FAILED))
        AppLog.logAction(ctx, action, res)
        log(ctx, "FAILED $action/$stepName: $why")
        return res
    }
}
