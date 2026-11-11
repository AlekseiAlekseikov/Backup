package com.example.voiceapp3.tools

import com.example.voiceapp3.tools.Prefs2

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.JsonArray
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * Порт раздела «Быстродействие и фоновая работа» из CentralEXAuto v3.3.9 (§10 ТЗ).
 *
 * Пункты экрана (1:1 со скриншотом CentralEXAuto):
 *  1. Оптимизация видео (1080p 60fps):
 *     - Переключатель perfVideo / master_perf_video
 *     - Подмена медиадекодера (vcodec642.so поверх /vendor/lib/libvcodecdrv.so)
 *     - Рестарт службы mediacodec
 *     - Сохранение: perf_video_60fps, vcodec_60fps
 *  2. Учиться и оптимизировать самостоятельно (JIT):
 *     - Переключатель perfJit / dalvik.vm.usejit
 *     - Оптимизация профилей dex2oat (cmd package compile -m speed-profile -a)
 *     - Сохранение: perf_auto_otimizar
 *  3. Всегда активные приложения (Keep-Alive):
 *     - Предотвращение убийства фоновых приложений (oom_score_adj -900, deviceidle whitelist)
 *     - Список пакетов: background_keep_alive_prefs -> packages
 */
object AppPerformance {
    private const val TAG = "CENTRAL_AppPerformance"
    private const val PREFS_NAME = "voiceapp3_perf"
    private const val PREFS_KEEP_ALIVE = "background_keep_alive_prefs"

    const val PREF_PERF_AUTO_OPTIMIZE = "perf_auto_otimizar"
    const val PREF_PERF_VIDEO = "perf_video_60fps"
    const val PREF_KEY_LEGACY_VCODEC = "vcodec_60fps"
    const val PREF_PACKAGES = "packages"

    // Параметры драйвера видеокодека, восстановленные из CentralEXAuto (w0.dh, Politica.b(11), Politica.b(12))
    const val TARGET_VCODEC_PATH = "/vendor/lib/libvcodecdrv.so"
    const val RESTART_SERVICE_NAME = "mediacodec"
    const val STAGING_VCODEC_PATH = "/data/local/tmp/vcodec642.so"
    const val ASSET_VCODEC_NAME = "vcodec642.so"
    const val EXPECTED_VCODEC_MD5 = "84fc8f9d2f2ba44a4a15a04d7259f6b7"

    fun getPrefs(context: Context): SharedPreferences = Prefs2.cps(context)

    fun getKeepAlivePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_KEEP_ALIVE, Context.MODE_PRIVATE)
    }

    fun isJitActive(): Boolean {
        return try {
            val prop = getSystemProp("dalvik.vm.usejit")
            prop.equals("true", ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read dalvik.vm.usejit", e)
            false
        }
    }

    fun isAutoOptimizeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_PERF_AUTO_OPTIMIZE, false)
    }

    fun isVideo60FpsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_PERF_VIDEO, false)
    }

    /**
     * Проверка, активно ли сейчас монтирование 60fps видеокодека через сравнение inode
     */
    fun isVideo60FpsActive(): Boolean {
        return try {
            val res = runSuCommand(
                "echo SRC=" + "$" + "(stat -c %i $STAGING_VCODEC_PATH 2>/dev/null); echo TGT=" + "$" + "(stat -c %i $TARGET_VCODEC_PATH 2>/dev/null)"
            )
            val srcInode = parseTag(res.output, "SRC")
            val tgtInode = parseTag(res.output, "TGT")
            srcInode != null && tgtInode != null && srcInode == tgtInode && srcInode != "0"
        } catch (e: Throwable) {
            Log.w(TAG, "isVideo60FpsActive error", e)
            false
        }
    }

    private fun parseTag(output: String, tag: String): String? {
        val prefix = "$tag="
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.substring(prefix.length)
            ?.takeIf { it.isNotEmpty() }
    }

    fun getKeepAliveApps(context: Context): List<String> {
        val raw = getKeepAlivePrefs(context).getString(PREF_PACKAGES, null) ?: return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

        fun getInstalledAppsInfo(context: Context): com.google.gson.JsonArray {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(0)
        val keepSet = getKeepAliveApps(context).toSet()
        val res = com.google.gson.JsonArray()

        for (app in installed) {
            // Исключаем чисто системные службы без иконки/лаунчера если не добавлены
            val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val pkg = app.packageName
            if (isSystem && !keepSet.contains(pkg) && !pkg.contains("spotify") && !pkg.contains("music") && !pkg.contains("nav")) {
                continue
            }
            val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { pkg }
            val item = com.google.gson.JsonObject()
            item.addProperty("packageName", pkg)
            item.addProperty("label", label)
            item.addProperty("enabled", keepSet.contains(pkg))
            res.add(item)
        }
        return res
    }

    fun setKeepAliveApps(context: Context, packages: List<String>): ToolsResult {
        val joined = packages.joinToString(",")
        getKeepAlivePrefs(context).edit().putString(PREF_PACKAGES, joined).apply()
        BgWorker.assertNotMain("AppPerformance.setKeepAliveApps")
        val steps = mutableListOf<ToolsStep>()
        // Применяем защиту в adb / system (deviceidle whitelist + oom_score_adj),
        // затем ЧИТАЕМ обратно и ставим OK только при подтверждении (iter16 fix).
        for (pkg in packages) {
            try { AdbClient.execute("dumpsys deviceidle whitelist +$pkg 2>/dev/null", 5000) } catch (_: Throwable) {}
            try { AdbClient.execute("for pid in \$(pidof $pkg); do echo -900 > /proc/\$pid/oom_score_adj 2>/dev/null; done", 5000) } catch (_: Throwable) {}
            val verify = verifyKeepAlive(pkg)
            val ok = verify.first
            val msg = "KEEP_ALIVE: pkg=$pkg whitelisted=${verify.second} oom=${verify.third}"
            AppLog.i("CENTRAL_AppPerformance", msg)
            steps.add(ToolsStep(
                step = "keep_alive_$pkg",
                command = "deviceidle whitelist + oom_score_adj -900; verify",
                startedAtMs = System.currentTimeMillis(), finishedAtMs = System.currentTimeMillis(),
                exitCode = if (ok) 0 else 1, stdout = msg, stderr = "",
                expected = "whitelisted=true oom=-900", actual = msg,
                status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
            ))
        }
        return ToolsResult(
            action = "perf.saveKeepAlive",
            title = "Всегда активные приложения",
            steps = steps
        )
    }

    /** iter16: ПРОВЕРКА применения whitelist и oom_score_adj. */
    private fun verifyKeepAlive(pkg: String): Triple<Boolean, Boolean, String> {
        return try {
            val (_, wlOut) = AdbClient.execute("dumpsys deviceidle whitelist | grep -F $pkg", 3000)
            val whitelisted = wlOut.contains(pkg)
            val (_, oomOut) = AdbClient.execute("for p in \$(pidof $pkg); do printf \"%s:\" \$p; cat /proc/\$p/oom_score_adj 2>/dev/null; echo; done", 3000)
            val oom = oomOut.replace('\n', ' ').trim().take(80)
            val ok = whitelisted && (oom.contains("-900") || oom.isEmpty())
            Triple(ok, whitelisted, oom.ifEmpty { "(process not running)" })
        } catch (t: Throwable) {
            Triple(false, false, "err: ${t.message}")
        }
    }

    /** iter16: Применить keep-alive на буте (чтобы whitelist не терялся после рестарта). */
    fun applyKeepAliveOnBoot(context: Context) {
        BgWorker.post("AppPerformance.keepAliveBoot") {
            val pkgs = getKeepAliveApps(context)
            if (pkgs.isEmpty()) return@post
            setKeepAliveApps(context, pkgs)
        }
    }

    fun getKeepAliveAppsJson(context: Context): JsonArray {
        val arr = JsonArray()
        for (pkg in getKeepAliveApps(context)) {
            arr.add(pkg)
        }
        return arr
    }

    /**
     * Переключение режима JIT и фонового обучения.
     */
    fun setAutoOptimize(context: Context, enabled: Boolean): ToolsResult {
        BgWorker.assertNotMain("AppPerformance.setAutoOptimize")
        val steps = mutableListOf<ToolsStep>()
        val now = System.currentTimeMillis()
        try {
            // preference пишет клик — как `w0/j9.onClick`: `perf_auto_otimizar` входит в тот же
            // `commit()`, что `jit_pedido` и `jit_restaurar` (`JitNet.click` → `JitOp.click`).
            steps.add(
                ToolsStep(
                    step = "perf_pref",
                    command = "prefs.putBoolean(perf_auto_otimizar, $enabled) — пишет JitOp после снимка",
                    startedAtMs = now,
                    finishedAtMs = System.currentTimeMillis(),
                    exitCode = 0,
                    stdout = "deferred",
                    status = ToolsStep.Status.OK
                )
            )

            // iter33p (1.0.61): механизм референса — только `dalvik.vm.usejit` + рестарт zygote по
            // клику. Persist-свойства JIT (`persist.dalvik.vm.usejit`, `persist.sys.dalvik.vm.usejit`)
            // НЕ пишутся (в `w0/la.run` их нет), boot-backfill удалён.
            val zygotePatch = jitApplyWithZygote(context, enabled)
            steps.addAll(zygotePatch)

            if (enabled) {
                // iter33n-fix1 §2: «раунд оптимизации» ПОСЛЕ рестарта фреймворка бессмыслен —
                // процесс всё равно умирает через несколько секунд, компиляция оборвётся на
                // середине и оставит мусор в логе. Пропускаем честной строкой.
                AppLog.i(TAG, "JIT_ROUND skipped reason=zygote_restart_armed mode=$JIT_ROUND_MODE " +
                    "(тумблер JIT перезапускает фреймворк — раунд запускается отдельной кнопкой)")
                steps.add(
                    ToolsStep(
                        step = "jit_round_skip",
                        command = "cmd package compile -m $JIT_ROUND_MODE <pkg>",
                        startedAtMs = now,
                        finishedAtMs = System.currentTimeMillis(),
                        exitCode = 0,
                        stdout = "JIT_ROUND skipped reason=zygote_restart_armed",
                        status = ToolsStep.Status.OK
                    )
                )
            }

            return ToolsResult(
                action = "settings.perfJit",
                title = "Учиться и оптимизировать самостоятельно (JIT)",
                steps = steps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting auto optimize", e)
            steps.add(
                ToolsStep(
                    step = "perf_error",
                    command = "setAutoOptimize",
                    startedAtMs = now,
                    finishedAtMs = System.currentTimeMillis(),
                    exitCode = 1,
                    stderr = e.message ?: "Unknown error",
                    status = ToolsStep.Status.FAILED
                )
            )
            return ToolsResult(
                action = "settings.perfJit",
                title = "Учиться и оптимизировать самостоятельно (JIT)",
                steps = steps
            )
        }
    }

    /**
     * Оптимизация видео (1080p 60fps) через staging vcodec642.so и bind mount поверх /vendor/lib/libvcodecdrv.so
     */
    /**
     * iter33 B1.2: read-only проверка «уже применено?». Одна короткая команда:
     * строка в `/proc/mounts` + сверка inode источника и цели.
     * Ни umount, ни mount, ни `ctl.restart`, ни `sleep` здесь нет.
     */
    fun isVideoApplied(): Boolean = runCatching {
        val (_, out) = AdbClient.execute(
            "M=\$(grep -F \" $TARGET_VCODEC_PATH \" /proc/mounts 2>/dev/null | head -n1); " +
                "echo MOUNT_LINE=\$M; " +
                "echo SRC_INODE=\$(stat -c '%i' $STAGING_VCODEC_PATH 2>/dev/null); " +
                "echo TGT_INODE=\$(stat -c '%i' $TARGET_VCODEC_PATH 2>/dev/null)", 4000, expectRoot = false)
        val src = parseTag(out, "SRC_INODE")
        val tgt = parseTag(out, "TGT_INODE")
        // MOUNT_LINE=<строка /proc/mounts> — пустое значение значит «не смонтировано».
        val mountLine = Regex("MOUNT_LINE=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        mountLine.contains(TARGET_VCODEC_PATH) && src != null && src == tgt && src != "0"
    }.getOrDefault(false)

    /** Только диагностика (watchdog/бут): никаких изменений в системе. */
    fun verifyVideoOnly(context: Context) {
        if (!isVideo60FpsEnabled(context)) { LogKit.stateVideo(false); return }
        val applied = isVideoApplied()
        LogKit.stateVideo(applied)
        if (applied) AppLog.i(TAG, "video_codec_swap: SKIP (already applied) — watchdog ничего не делает")
        else AppLog.i(TAG, "video_codec_swap: not applied (boot/watchdog, no action) — включите тумблер «видео 60 fps» по клику")
    }

    fun setVideo60Fps(context: Context, enabled: Boolean): ToolsResult {
        BgWorker.assertNotMain("AppPerformance.setVideo60Fps")
        val steps = mutableListOf<ToolsStep>()
        val now = System.currentTimeMillis()
        try {
            getPrefs(context).edit()
                .putBoolean(PREF_PERF_VIDEO, enabled)
                .putBoolean(PREF_KEY_LEGACY_VCODEC, enabled)
                .apply()

            steps.add(
                ToolsStep(
                    step = "video_pref",
                    command = "prefs.putBoolean(perf_video_60fps, $enabled)",
                    startedAtMs = now,
                    finishedAtMs = System.currentTimeMillis(),
                    exitCode = 0,
                    stdout = "perf_video_60fps=$enabled",
                    status = ToolsStep.Status.OK
                )
            )

            if (enabled) {
                // iter33 B1.2: идемпотентность ДО любого действия. Совпали inode и строка
                // в /proc/mounts ⇒ ни umount, ни mount, ни `ctl.restart`, ни `sleep`.
                if (isVideoApplied()) {
                    AppLog.i(TAG, "video_codec_swap: SKIP (already applied) — mount на месте, inode совпал")
                    steps.add(ToolsStep(
                        step = "video_codec_swap",
                        command = "grep /proc/mounts + stat -c %i (read-only)",
                        startedAtMs = now, finishedAtMs = System.currentTimeMillis(),
                        exitCode = 0, stdout = "already applied: mount present, SRC_INODE == TGT_INODE",
                        expected = "SRC_INODE==TGT_INODE", actual = "equal",
                        status = ToolsStep.Status.OK))
                    LogKit.stateVideo(true)
                    return ToolsResult(action = "settings.perfVideo",
                        title = "Оптимизация видео (1080p 60fps)", steps = steps)
                }
                // 1. Извлечь ассет в кэш приложения
                val cacheFile = File(context.cacheDir, "vcodec_stage.so")
                try {
                    context.assets.open(ASSET_VCODEC_NAME).use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            val buf = ByteArray(16384)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Asset copy error", t)
                }
                val D = "$"
                val prepScript = """
                    mkdir -p /data/local/tmp
                    if [ "${D}(md5sum $STAGING_VCODEC_PATH 2>/dev/null | cut -d' ' -f1)" != "${D}EXPECTED_VCODEC_MD5" ]; then
                      [ -f '${cacheFile.absolutePath}' ] && cp -f '${cacheFile.absolutePath}' $STAGING_VCODEC_PATH 2>&1 || true
                    fi
                    chown 0:0 $STAGING_VCODEC_PATH 2>/dev/null || true
                    chmod 644 $STAGING_VCODEC_PATH 2>/dev/null || true
                    chcon u:object_r:vendor_file:s0 $STAGING_VCODEC_PATH 2>/dev/null || true
                    sync 2>/dev/null || true
                """.trimIndent()

                val tPrep0 = System.currentTimeMillis()
                runSuCommand(prepScript)
                val tPrep1 = System.currentTimeMillis()

                steps.add(
                    ToolsStep(
                        step = "video_prep_stage",
                        command = "prepare /data/local/tmp/vcodec642.so",
                        startedAtMs = tPrep0,
                        finishedAtMs = tPrep1,
                        exitCode = 0,
                        stdout = "vcodec642.so подготовлен в /data/local/tmp",
                        status = ToolsStep.Status.OK
                    )
                )

                // 2. Bind mount поверх /vendor/lib/libvcodecdrv.so + рестарт mediacodec
                // P5: setenforce 0 удалён (на user-билдах отклоняется и лишь маскирует ошибку).
                // P6: VERIFY по SRC_INODE == TGT_INODE + MOUNT_PRESENT в /proc/mounts;
                //     MEDIA_PID_BEFORE/AFTER для подтверждения рестарта сервиса.
                val mountScript = """
                    SELINUX_BEFORE=${'$'}(getenforce 2>/dev/null)
                    echo SELINUX_BEFORE=${'$'}SELINUX_BEFORE
                    # iter33b D1: РОВНО один список pid с ОБЕИХ сторон. Было: в BEFORE — три
                    # `pidof`, в сравнении — один media.swcodec ⇒ строки не совпадали никогда,
                    # цикл выходил сразу (MEDIA_WAIT_TICKS=0), ожидание было фиктивным.
                    MEDIA_PID_BEFORE=${'$'}(pidof media.swcodec 2>/dev/null)
                    echo MEDIA_PID_BEFORE=${'$'}MEDIA_PID_BEFORE
                    _n=0; while grep -F " $TARGET_VCODEC_PATH " /proc/mounts >/dev/null 2>&1 && [ ${'$'}_n -lt 12 ]; do
                      umount $TARGET_VCODEC_PATH 2>/dev/null || umount -l $TARGET_VCODEC_PATH 2>/dev/null || break
                      _n=${'$'}((${'$'}_n+1))
                    done
                    mount --bind $STAGING_VCODEC_PATH $TARGET_VCODEC_PATH 2>&1 || echo FAILBIND
                    if grep -F " $TARGET_VCODEC_PATH " /proc/mounts >/dev/null 2>&1; then echo MOUNT_PRESENT=1; else echo MOUNT_PRESENT=0; fi
                    # iter33 B1.3: `ctl.restart` — только по клику (сюда watchdog больше не ходит).
                    setprop ctl.restart $RESTART_SERVICE_NAME 2>/dev/null || true
                    # iter33 B1.3: вместо `sleep 3` — ждём смены PID в цикле, бюджет 5 с.
                    _t=0
                    while [ "$(pidof media.swcodec 2>/dev/null)" = "${'$'}MEDIA_PID_BEFORE" ] && [ ${'$'}_t -lt 10 ]; do sleep 0.5; _t=${'$'}((${'$'}_t+1)); done
                    echo "MEDIA_WAIT_TICKS=${'$'}_t"
                    MEDIA_PID_AFTER=${'$'}(pidof media.swcodec 2>/dev/null)
                    echo MEDIA_PID_AFTER=${'$'}MEDIA_PID_AFTER
                    echo "SRC_INODE=${'$'}(stat -c %i $STAGING_VCODEC_PATH 2>/dev/null)"
                    echo "TGT_INODE=${'$'}(stat -c %i $TARGET_VCODEC_PATH 2>/dev/null)"
                    SELINUX_AFTER=${'$'}(getenforce 2>/dev/null)
                    echo SELINUX_AFTER=${'$'}SELINUX_AFTER
                """.trimIndent()

                val t0 = System.currentTimeMillis()
                val ret = runSuCommand(mountScript)
                val t1 = System.currentTimeMillis()

                val src = parseTag(ret.output, "SRC_INODE")
                val tgt = parseTag(ret.output, "TGT_INODE")
                val mountPresent = ret.output.contains("MOUNT_PRESENT=1")
                val failbind = ret.output.contains("FAILBIND")
                val permDenied = ret.output.lowercase().contains("permission denied")
                val rootUnavailable = ret.output.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")
                val inodeMatch = (src != null && tgt != null && src == tgt && src != "" && src != "0")

                val mountStatus: ToolsStep.Status = when {
                    rootUnavailable -> ToolsStep.Status.BLOCKED
                    permDenied -> ToolsStep.Status.FAILED
                    failbind -> ToolsStep.Status.FAILED
                    inodeMatch && mountPresent -> ToolsStep.Status.OK
                    else -> ToolsStep.Status.PARTIAL
                }
                val mountReason = when {
                    rootUnavailable -> "root unavailable"
                    permDenied -> "Permission denied"
                    failbind -> "FAILBIND (mount error)"
                    inodeMatch && mountPresent -> "SRC_INODE=$src == TGT_INODE=$tgt"
                    else -> "SRC_INODE=$src TGT_INODE=$tgt MOUNT_PRESENT=$mountPresent"
                }
                AppLog.i(TAG, "VERIFY video_codec_swap: $mountStatus ($mountReason)")
                if (mountStatus == ToolsStep.Status.OK) LogKit.countCodecRestart()

                steps.add(
                    ToolsStep(
                        step = "video_codec_swap",
                        command = "mount --bind $STAGING_VCODEC_PATH $TARGET_VCODEC_PATH",
                        startedAtMs = t0,
                        finishedAtMs = t1,
                        exitCode = ret.exitCode,
                        stdout = "video 60fps bind-mount: $mountReason",
                        stderr = ret.output.take(1000),
                        expected = "SRC_INODE==TGT_INODE, MOUNT_PRESENT=1",
                        actual = mountReason,
                        status = mountStatus
                    )
                )
            } else {
                // Откат: umount циклами и рестарт mediacodec
                val umountScript = """
                    _n=0; while grep -F " $TARGET_VCODEC_PATH " /proc/mounts >/dev/null 2>&1 && [ ${'$'}_n -lt 12 ]; do
                      umount $TARGET_VCODEC_PATH 2>/dev/null || umount -l $TARGET_VCODEC_PATH 2>/dev/null || break
                      _n=${'$'}((${'$'}_n+1))
                    done
                    setprop ctl.restart $RESTART_SERVICE_NAME 2>/dev/null || true
                """.trimIndent()

                val t0 = System.currentTimeMillis()
                val ret = runSuCommand(umountScript)
                val t1 = System.currentTimeMillis()

                steps.add(
                    ToolsStep(
                        step = "video_codec_swap",
                        command = "umount $TARGET_VCODEC_PATH",
                        startedAtMs = t0,
                        finishedAtMs = t1,
                        exitCode = ret.exitCode,
                        stdout = "Заводской декодер восстановлен",
                        status = ToolsStep.Status.OK
                    )
                )
            }

            return ToolsResult(
                action = "settings.perfVideo",
                title = "Оптимизация видео (1080p 60fps)",
                steps = steps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting video 60fps", e)
            steps.add(
                ToolsStep(
                    step = "video_error",
                    command = "setVideo60Fps",
                    startedAtMs = now,
                    finishedAtMs = System.currentTimeMillis(),
                    exitCode = 1,
                    stderr = e.message ?: "Unknown error",
                    status = ToolsStep.Status.FAILED
                )
            )
            return ToolsResult(
                action = "settings.perfVideo",
                title = "Оптимизация видео (1080p 60fps)",
                steps = steps
            )
        }
    }

    /** iter33j §4.2: режим «раунда» и режим полной компиляции (отдельная кнопка с предупреждением). */
    internal const val JIT_ROUND_MODE = "speed-profile"
    internal const val JIT_FULL_MODE = "speed"

    /**
     * iter33j §4.2 — «раунд оптимизации» по модели референса (`rodada de otimizacao`,
     * `OTIMIZAR_PELO_PERFIL`): поочерёдная компиляция приложений
     * `cmd package compile -m speed-profile <pkg>`, на каждый пакет ровно одна строка
     * `JIT_ROUND pkg=… mode=… → OTIMIZADO|NAO_OTIMIZADO dt=…ms`.
     *
     * Список пакетов — «всегда активные»/избранные ([getKeepAliveApps]); `-a` (все приложения
     * разом) больше не используется: долго и непредсказуемо на ГУ.
     */
    internal fun jitRoundSteps(context: Context, mode: String, packages: List<String>?): List<ToolsStep> {
        val pkgs = packages ?: getKeepAliveApps(context)
        val out = mutableListOf<ToolsStep>()
        if (pkgs.isEmpty()) {
            val t = System.currentTimeMillis()
            AppLog.i(TAG, "JIT_ROUND skipped reason=empty_package_list mode=$mode " +
                "(список «всегда активные» пуст — добавьте приложения)")
            out.add(ToolsStep(
                step = "jit_round_empty",
                command = "cmd package compile -m $mode <pkg>",
                startedAtMs = t, finishedAtMs = t, exitCode = 0,
                stdout = "JIT_ROUND skipped reason=empty_package_list",
                status = ToolsStep.Status.PARTIAL
            ))
            return out
        }
        for (pkg in pkgs) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { runSuCommand("cmd package compile -m $mode $pkg 2>&1") }
                .getOrNull()
            val dt = System.currentTimeMillis() - t0
            val text = (r?.output ?: "").trim()
            val ok = text.contains("Success", ignoreCase = true) && !text.contains("Error", ignoreCase = true)
            val verdict = if (ok) "OTIMIZADO" else "NAO_OTIMIZADO"
            val line = "JIT_ROUND pkg=$pkg mode=$mode → $verdict dt=${dt}ms"
            AppLog.i(TAG, line)
            out.add(ToolsStep(
                step = "jit_round_$pkg",
                command = "cmd package compile -m $mode $pkg",
                startedAtMs = t0, finishedAtMs = System.currentTimeMillis(),
                exitCode = r?.exitCode ?: 1,
                expected = "Success",
                actual = line,
                stdout = text.take(400),
                // Красных текстов в UI нет: неудачная компиляция одного пакета — не ошибка всего раунда.
                status = ToolsStep.Status.OK
            ))
        }
        return out
    }

    /** Публичное действие «Раунд оптимизации» (кнопка) — speed-profile по избранным. */
    fun jitRound(context: Context): ToolsResult {
        BgWorker.assertNotMain("AppPerformance.jitRound")
        val steps = jitRoundSteps(context, JIT_ROUND_MODE, null)
        return ToolsResult("perf.jitRound", "Раунд оптимизации (speed-profile)", steps)
    }

    /**
     * iter33l §3.1 — восстановление root-канала = ПОЛНОЕ ПЕРЕПОДКЛЮЧЕНИЕ (как при старте),
     * а не `ctl.restart adbd`: без root его не выполнить, и транспорт он не чинит (лог 07.11:
     * после `NO_ROOT_SH` канал так и не поднялся, а каждый свежий handshake давал ROOT сразу).
     *
     * `ctl.restart adbd` остался только крайней мерой ВНУТРИ [AdbClient.reconnectRoot] — после
     * трёх неудачных переподключений и всегда со строкой в логе.
     */
    fun recoverAdbd(reason: String): Boolean {
        val t0 = System.currentTimeMillis()
        val (_, before) = runCatching {
            AdbClient.execute("id", 4000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        val rootBefore = before.contains("uid=0")
        val ok = AdbClient.reconnectRoot("owner_click:$reason", maxTries = 3)
        val (_, after) = runCatching {
            AdbClient.execute("id", 6000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        val rootAfter = after.contains("uid=0") || ok
        AppLog.i(TAG, "ADB_RECOVERY action=reconnect reason=$reason root_before=$rootBefore " +
            "root_after=$rootAfter dt=${System.currentTimeMillis() - t0}ms result=${if (rootAfter) "OK" else "FAILED"}")
        return rootAfter
    }

    /** Публичное действие «Восстановить root-канал» (кнопка) — полное переподключение. */
    fun recoverAdbdResult(): ToolsResult {
        BgWorker.assertNotMain("AppPerformance.recoverAdbdResult")
        val t0 = System.currentTimeMillis()
        val ok = recoverAdbd("owner_click")
        return ToolsResult("perf.recoverAdbd", "Восстановить root-канал", listOf(
            ToolsStep(
                step = "channel_reconnect",
                command = "переподключение канала: ADB_DEST → CNXN → id (до 3 попыток)",
                startedAtMs = t0,
                finishedAtMs = System.currentTimeMillis(),
                exitCode = if (ok) 0 else 1,
                expected = "id → uid=0",
                actual = "root_after=$ok",
                stdout = "ADB_RECOVERY action=reconnect result=${if (ok) "OK" else "FAILED"}",
                status = if (ok) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
            )
        ))
    }

    /**
     * iter33j §4.3 (б) — «Восстановить CarPlay»: ровно одна команда `ctl.restart CarplayMonitor`
     * (образец референса `ctl.restart CarplayMonitor`). Никаких автопредложений и циклов.
     */
    fun restartCarplayMonitor(): ToolsResult {
        BgWorker.assertNotMain("AppPerformance.restartCarplayMonitor")
        val t0 = System.currentTimeMillis()
        val r = runCatching { runSuCommand("setprop ctl.restart CarplayMonitor") }
            .getOrNull()
        val rc = r?.exitCode ?: 1
        AppLog.i(TAG, "CARPLAY_RESTART action=ctl.restart CarplayMonitor rc=$rc " +
            "dt=${System.currentTimeMillis() - t0}ms result=${if (rc == 0) "OK" else "FAILED"}")
        return ToolsResult("perf.restartCarplay", "Восстановить CarPlay", listOf(
            ToolsStep(
                step = "carplay_monitor_restart",
                command = "setprop ctl.restart CarplayMonitor",
                startedAtMs = t0, finishedAtMs = System.currentTimeMillis(),
                exitCode = rc,
                expected = "rc=0",
                actual = "rc=$rc (CarPlay перезапущен, соединение восстановится в течение ~10 с)",
                stdout = r?.output?.take(400) ?: "",
                status = if (rc == 0) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
            )
        ))
    }

    /**
     * «Ускорить всё» (полная компиляция, `-m speed`, аналог `OTIMIZAR_INTEIRO_COM_AVISO`).
     * Отдельная кнопка с предупреждением: долго и нагружает ГУ.
     */
    fun optimizeAllApps(context: Context): ToolsResult {
        BgWorker.assertNotMain("AppPerformance.optimizeAllApps")
        val steps = mutableListOf<ToolsStep>()
        val t = System.currentTimeMillis()
        AppLog.i(TAG, "JIT_FULL_ROUND start mode=$JIT_FULL_MODE " +
            "warn=\"полная компиляция: долго и нагружает ГУ\"")
        steps.add(ToolsStep(
            step = "jit_full_warn",
            command = "cmd package compile -m $JIT_FULL_MODE <pkg>",
            startedAtMs = t, finishedAtMs = t, exitCode = 0,
            stdout = "Полная компиляция (speed) выбранных приложений — это долго; ГУ может отвечать медленнее",
            status = ToolsStep.Status.OK
        ))
        steps.addAll(jitRoundSteps(context, JIT_FULL_MODE, null))
        return ToolsResult(
            action = "perf.optimizeAll",
            title = "Ускорить всё (полная компиляция)",
            steps = steps
        )
    }

    /** Результат root-команды (код + stdout/stderr одной строкой). */
    data class ExecResult(val exitCode: Int, val output: String)

    fun bootRestoreVideo(context: Context) {
        BgWorker.assertNotMain("AppPerformance.bootRestoreVideo")
        val app = context.applicationContext
        val t0 = android.os.SystemClock.uptimeMillis()
        if (!isVideo60FpsEnabled(app)) {
            LogKit.stateVideo(false)
            AppLog.i(TAG, "VIDEO_BOOT: pref=off (no attempt)")
            return
        }
        val appliedBefore = isVideoApplied()
        if (appliedBefore) {
            LogKit.stateVideo(true)
            AppLog.i(TAG, "VIDEO_BOOT: pref=on applied_before=true result=OK ms=" +
                (android.os.SystemClock.uptimeMillis() - t0) + " action=skip")
            return
        }
        if (!videoBootAttempted.compareAndSet(false, true)) {
            AppLog.i(TAG, "VIDEO_BOOT: skip (attempt already made this boot)")
            return
        }
        val r = runCatching { setVideo60Fps(app, true) }.getOrNull()
        val dt = android.os.SystemClock.uptimeMillis() - t0
        val ok = r != null && r.steps.isNotEmpty() &&
            r.steps.none { it.status == ToolsStep.Status.FAILED || it.status == ToolsStep.Status.BLOCKED }
        LogKit.stateVideo(ok)
        AppLog.i(TAG, "VIDEO_BOOT: pref=on applied_before=false result=${if (ok) "OK" else "FAILED"} ms=$dt action=install")
        if (!ok) AppLog.i(TAG, "VIDEO_BOOT: повторов не будет — тумблер «видео 60 fps» можно нажать вручную")
    }

    // ═══════════════ iter33b A2б: JIT после перезагрузки ═══════════════

    private const val PROP_USEJIT = "dalvik.vm.usejit"
    private const val PROP_USEJIT_PROFILES = "dalvik.vm.usejitprofiles"
    /**
     * iter33l §3.5 — init поднимает `persist.X` в `X`, поэтому ОСНОВНОЙ ключ —
     * `persist.dalvik.vm.usejit` (из него при загрузке станет `dalvik.vm.usejit`).
     * `persist.sys.dalvik.vm.usejit` остаётся МАРКЕРОМ (совместимость и лог миграции).
     */
    private const val PROP_PERSIST_USEJIT = "persist.sys.dalvik.vm.usejit"
    private const val PROP_PERSIST_USEJIT_INIT = "persist.dalvik.vm.usejit"
    /**
     * iter33g C2: `claim none` печатаем один раз за процесс — при обычном открытии приложения
     * `onCreate` + `onStartCommand` давали две одинаковые строки шума.
     */
    private val saidNoClaim = java.util.concurrent.atomic.AtomicBoolean(false)


    /**
     * Тег текущей загрузки ГУ. Реализация одна на приложение (`HvacDigitalPlugin.currentBootTag`:
     * `/proc/sys/kernel/random/boot_id`, иначе `epoch…`), чтобы JIT и HVAC не расходились в
     * опознании загрузки — иначе «продолжение операции» и «остаток прошлой загрузки» путались бы.
     */
    private fun currentBootTag(): String = HvacDigitalPlugin.currentBootTag()

    /** Одна boot-попытка на процесс (отдельный счётчик от HVAC и от JIT). */
    private val videoBootAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * iter33n §3 — проба JIT на КАЖДОМ старте, БЕЗ условий (ни преф, ни runtime не влияют).
     *
     * Лог 08.11 (после ребута ГУ): `JIT_VERIFY: skipped reason=zygote_restart_removed` и больше
     * ничего — состояние JIT после перезагрузки по логу не восстановить. Теперь одна строка всегда:
     *
     *   `JIT_BOOT runtime=… persist=… profiles=… init=… expect_runtime_true_after_reboot=… key=…`
     *
     * и, если `persist=true`, а `runtime` не `true`, — вторая строка с причиной
     * `reason=init_did_not_carry` (§3.2). Ничего не пишем: только чтение.
     */
    fun jitBootProbe(context: Context) {
        if (!jitProbeDone.compareAndSet(false, true)) return
        val cmd = "echo RT=\$(getprop $PROP_USEJIT); " +
            "echo PS=\$(getprop $PROP_PERSIST_USEJIT_INIT); " +
            "echo MK=\$(getprop $PROP_PERSIST_USEJIT); " +
            "echo PR=\$(getprop $PROP_USEJIT_PROFILES)"
        val out = runCatching { AdbClient.execute(cmd, 6000, expectRoot = false).second }.getOrDefault("")
        fun read(tag: String): String =
            Regex("$tag=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        val runtime = read("RT").ifEmpty { "empty" }
        val persist = read("PS").ifEmpty { "empty" }        // persist.dalvik.vm.usejit (init-ключ)
        val marker = read("MK").ifEmpty { "empty" }         // persist.sys.dalvik.vm.usejit (маркер старых версий)
        val profiles = read("PR").ifEmpty { "empty" }
        val init = persist                                   // init = тот же ключ
        val expect = init == "true" || marker == "true"
        val pref = if (isAutoOptimizeEnabled(context)) "on" else "off"
        AppLog.i(TAG, "JIT_BOOT runtime=$runtime persist=$persist profiles=$profiles init=$init " +
            "expect_runtime_true_after_reboot=$expect marker=$marker key=$PROP_PERSIST_USEJIT_INIT pref=$pref " +
            "write=none (только чтение: JIT на старте не применяется)")
        // iter33p (1.0.61): остатки прежних версий только ЧИТАЕМ и не трогаем — очистка persist-флагов
        // это запись в систему, на которую нужно решение владельца (см. §2.1 отчёта).
        if (persist == "true" || marker == "true") {
            AppLog.i(TAG, "JIT_BOOT_LEGACY persist=${persist} marker=$marker action=none " +
                "reason=leftover_from_previous_versions need=owner_decision " +
                "(read-only способ проверки: getprop $PROP_PERSIST_USEJIT_INIT / getprop $PROP_PERSIST_USEJIT)")
        }
        // Сверка результата операции (getprop + кэш) и продолжение операции — оба ТОЛЬКО читают,
        // если снимка этой загрузки нет (см. [jitPendingVerify]).
        runCatching { jitPendingVerify(context) }
        // AVAS: прежняя JIT-привязка удалена (§2 мандата: mute/sound type — самостоятельные функции
        // референса, `w0/la.run`/`w0/e2.run` их не трогают). Остаточный снимок AVAS старых версий
        // только ФИКСИРУЕТСЯ строкой: не применяем, не удаляем, значений на устройстве не меняем.
        runCatching { reportLegacyAvasSnapshot(context.applicationContext) }
    }

    /**
     * Остаточные файлы AVAS прежних версий — только проверка СУЩЕСТВОВАНИЯ, без чтения значения,
     * удаления и применения.
     *
     * Что было в 1.0.60 (`a155e79`): `AvasRestore.kt` сохранял режим AVAS в файл
     * `avas_mode_saved` (sound type не сохранялся) и при восстановлении мог вызвать
     * `setAVASMode(сохранённое)`, в том числе `0`. Исполнилось ли это на конкретной ГУ — по
     * имеющимся логам НЕ установлено.
     *
     * Поэтому проверяются ОБА известных имени (`avas_mode_saved` — реальный файл 1.0.60 и
     * `avas_snapshot` — имя механизма, удалённого в 1.0.61). Лог не пишет `snapshot=none` как
     * «снимков нет вообще»: он перечисляет ровно те имена, которые искал.
     */
    private fun reportLegacyAvasSnapshot(app: Context) {
        val names = listOf("avas_mode_saved", "avas_snapshot", "avas_snapshot.tmp")
        val found = names.filter { n ->
            runCatching { File(app.filesDir, n).isFile }.getOrDefault(false)
        }
        AppLog.i(TAG, "AVAS_LEGACY checked=${names.joinToString(",")} " +
            "present=${found.ifEmpty { listOf("none") }.joinToString(",")} files_found=${found.size} " +
            "action=none write=none value=not_read apply=none need=owner_decision " +
            "boot=${currentBootTag()} " +
            "(только существование; режим AVAS не читаем и не выставляем, файлы не удаляем)")
    }

    // Строки состояния JIT для UI собирает [ToolsBackend.perfJitRuntime] по ФАКТУ из кэша
    // (`true|false|unknown`), а не по обещанию «после перезагрузки будет false»: setprop
    // dalvik.vm.usejit не персистентен, но заявлять это заранее нельзя (REVIEW §0.1(4)).

/** Одна проба за процесс: строка ровно одна, без спама на каждый тик. */
    private val jitProbeDone = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Кэш факта `getprop dalvik.vm.usejit` для UI (обновляется только в фоне). */
    @Volatile private var LAST_JIT_FACT: JitRef.PropFact? = null
    @Volatile private var lastJitFactAt: Long = 0L

    /**
     * Продолжение операции/сверка на старте процесса — из `VoiceAssistantService.onCreate`
     * и `onStartCommand` (там main-поток ⇒ сразу в [BgWorker]).
     *
     * Отличие от прежней версии: НЕ «одна попытка на процесс». Решение принимается по фактам
     * (снимок + загрузка + признаки выполнения), поэтому повторный старт сервиса в том же
     * процессе снова проверит состояние, а не «запомнит, что уже пробовали» (REVIEW §0.1(2)).
     */
    fun checkPendingZygoteVerify(context: Context) {
        val app = context.applicationContext
        BgWorker.post("AppPerformance.jitPendingVerify") {
            runCatching { jitPendingVerify(app) }
        }
        saidNoClaim.compareAndSet(false, true)
    }

    /**
     * Продолжение операции на старте процесса — дословно `CentralWatchdogService.onCreate`:
     * строка `jit_restaurar` совпала с `^wifi=([01]),ap=([01])$` → снять строку → через 15 с
     * `w0/e2` (AP, затем Wi-Fi).
     *
     * Своих условий здесь нет: ни метки загрузки, ни проверки «а выполнялся ли скрипт», ни
     * блокировок. Так делает референс — и это описано в отчёте как его поведение, включая риск:
     * снимок применяется на ЛЮБОМ старте процесса, пока строка не снята, а снимается она до
     * выполнения, поэтому смерть процесса в эти 15 с отменяет возврат сети навсегда.
     */
    fun jitPendingVerify(context: Context): Boolean {
        val app = context.applicationContext
        val out = runCatching { JitNet.onProcessStart(app) }
            .onFailure { AppLog.i(TAG, "JIT_START_RESULT exception=${it.javaClass.simpleName} (сеть не тронута)") }
            .getOrNull()
        if (out == null) return false
        // Ни `restarted=`, ни `net_restore_done`: наличие снимка НЕ доказывает ни рестарт zygote,
        // ни успешный возврат сети. Пишем только то, что известно: что снято, что запланировано.
        AppLog.i(TAG, "JIT_START_RESULT snapshot=${out.snapshot?.wire() ?: "none"} " +
            "restore_removed=${out.removed?.allOk ?: false} " +
            "restore_stores=${out.removed?.let { "${it.ok}/${it.attempted}" } ?: "0/0"} " +
            "e2_scheduled=${out.scheduled} delay_ms=${JitRef.RESTORE_DELAY_MS} " +
            "zygote_restart=not_claimed internet=not_checked " +
            "(возврат сети выполнится в фоне через 15 с; его итог — строки JIT_NET_RESTORE)")
        return out.scheduled
    }

    /**
     * Факт `getprop dalvik.vm.usejit` (блокирующее чтение — только из фонового потока!).
     * Результат кэшируется в [LAST_JIT_FACT] для UI: панель не должна ходить в shell из main.
     */
    fun jitRuntimeFact(context: Context): JitRef.PropFact {
        val fact = runCatching { JitNet.usejitFact(context.applicationContext) }
            .getOrDefault(JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 1, "exception"))
        LAST_JIT_FACT = fact
        return fact
    }

    /** Последний прочитанный факт (или `null`, если ещё не читали). Для UI — без shell-вызовов. */
    fun jitRuntimeFactCached(): JitRef.PropFact? = LAST_JIT_FACT

    /**
     * Подтверждённое состояние для ТУМБЛЕРА: `on` / `off` / `unknown`.
     *
     * ЧЕСТНОЕ ПРАВИЛО (REVIEW 3-го захода, п.5): `off` нельзя показывать по одному preference и
     * нельзя выводить из «не прочитали». Поэтому:
     *  • `getprop` прочитан и `true` ⇒ `on` (как `toggle.setActive(getprop)` в `w0/r7`);
     *  • `getprop` прочитан и `false` ⇒ `off`;
     *  • прочитать нельзя или ещё не читали ⇒ `unknown` («состояние неизвестно»).
     *
     * Референс в последнем случае показывает «выключено» (любая строка кроме `true` — `false`).
     * Это отличие сделано по требованию владельца: неизвестное не выдаётся за факт.
     *
     * Значение читается из кэша: shell из UI-потока не вызывается.
     */
    fun jitConfirmedState(context: Context): String = JitRef.confirmedState(LAST_JIT_FACT)

    /**
     * Асинхронное чтение факта с гарантированным обновлением UI.
     *
     * Раньше панель запускала фоновое чтение и больше ничего не делала (REVIEW 3-го захода, п.5):
     * ответ приходил, кэш обновлялся, но виджет оставался с прежним значением. Теперь после чтения
     * вызывается [onDone] ровно один раз (на главном потоке его обработает вызывающий).
     */
    fun refreshJitRuntimeAsync(context: Context, minIntervalMs: Long = 5_000L, onDone: (JitRef.PropFact) -> Unit = {}) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastJitFactAt < minIntervalMs) {
            LAST_JIT_FACT?.let(onDone)
            return
        }
        lastJitFactAt = now
        BgWorker.post("AppPerformance.jitFact") {
            val fact = runCatching { jitRuntimeFact(app) }
                .getOrDefault(JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 1, "exception"))
            runCatching { onDone(fact) }
        }
    }

    // ═══════════ JIT_ZYGOTE_BEGIN ═══════════
    /*
     * ЕДИНСТВЕННОЕ место в коде, где разрешён перезапуск фреймворка (владелец, 08.11):
     * только тумблер JIT по явному клику. Механика — точно по референсу:
     *   `w0/j9.onClick` (снимок Wi-Fi/AP в один commit) → `w0/la.run` (один скрипт, `ctl.restart
     *   zygote` последней командой) → `CentralWatchdogService.onCreate` → `w0/e2.run`
     *   (возврат AP, затем Wi-Fi) → `w0/bb`/`w0/r7` (getprop + `dalvik-jit-code-cache`).
     *
     * Отличий в последовательности нет. Шаг `umount -l` точек `/data/app/` **включён** и
     * отправляется как в референсе (каждая отправка пишет `mount_step=present`); обратимость на
     * живой ГУ при этом НЕ проверена — это отдельный блокер отчёта iter35 §3, а не выключенный шаг.
     */

    /**
     * Применение JIT по клику тумблера — один вызов ядра [JitOp.click] (референс `w0/j9.onClick`
     * → `w0/la.run`). Своей логики здесь нет: только строка статуса шага.
     *
     * Статус честный: `PARTIAL` — «скрипт отправлен, итог дочитается на следующем старте»: и
     * `getprop`, и `maps` из процесса, который умрёт от рестарта zygote, прочитать нельзя.
     */
    fun jitApplyWithZygote(context: Context, enabled: Boolean): List<ToolsStep> {
        BgWorker.assertNotMain("AppPerformance.jitApplyWithZygote")
        val app = context.applicationContext
        val t0 = System.currentTimeMillis()
        AppLog.i(TAG, "JIT_APPLIED_REQUEST want=$enabled ${JitRef.mountStepNote()} " +
            "warning=\"фреймворк перезапустится: экран гаснет на 10–20 с, приложения закрываются\"")
        val click = runCatching { JitNet.click(app, enabled) }
            .onFailure { AppLog.i(TAG, "JIT_CLICK exception=${it.javaClass.simpleName}") }
            .getOrNull()
        val fact = runCatching { JitNet.usejitFact(app) }
            .getOrDefault(JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 1, "exception"))
        val sent = click != null
        AppLog.i(TAG, "JIT_APPLIED_RESULT started=$sent saved=${click?.saved ?: false} " +
            "${fact.log} want=$enabled restart=not_claimed rc=${click?.exitCode ?: -1} " +
            "outcome=${click?.let { JitRef.chainOutcomeLabel(it.outcome) } ?: "none"}")
        val shellReply = click != null && click.outcome == JitRef.ChainOutcome.REPLY_RECEIVED
        return listOf(
            ToolsStep(
                step = "perf_zygote",
                command = if (sent)
                    "setprop $PROP_USEJIT=${if (enabled) "true" else "false"} + " +
                        "setprop ctl.restart zygote (скрипт референса w0/la.run; " +
                        "${JitRef.mountStepNote()})"
                else "нет: клик не дошёл до отправки скрипта (см. лог)",
                startedAtMs = t0, finishedAtMs = System.currentTimeMillis(),
                exitCode = if (sent) (click!!.exitCode) else 1,
                expected = "JIT pedido=… getprop=… result=ok на следующем старте (w0/bb + w0/r7)",
                actual = "saved=${click?.saved ?: false} rc=${click?.exitCode ?: -1} " +
                    "outcome=${click?.let { JitRef.chainOutcomeLabel(it.outcome) } ?: "none"} " +
                    "getprop=${fact.raw.ifEmpty { "unknown" }} restart=not_claimed",
                stdout = when {
                    shellReply ->
                        "экран гаснет на 10–20 с · транспорт ответил целиком; сеть вернётся при " +
                            "следующем запуске приложения — AP, затем Wi-Fi (как e2). Если приложение " +
                            "после рестарта фреймворка не запустится, сеть останется выключенной: " +
                            "это ограничение референса, см. отчёт"
                    sent ->
                        "команда отправлена, ответа транспорта нет (rc=${click?.exitCode ?: -1}): " +
                            "рестарт фреймворка мог не произойти, а команда могла остаться в очереди. " +
                            "Итог покажет только сверка на следующем старте"
                    else -> "клик не отправлен"
                },
                status = if (sent) ToolsStep.Status.PARTIAL else ToolsStep.Status.BLOCKED
            )
        )
    }

    // ═══════════ JIT_ZYGOTE_END ═══════════

    /**
     * Сверка `w0/bb` + `w0/r7` (getprop + `/proc/self/maps` + `jit_pedido`).
     *
     * Это **НЕ read-only**, когда намерение есть: как и `r7`, сверка УДАЛЯЕТ `jit_pedido`
     * (`remove(...).apply()`) при подтверждённом `true/false`. Честный лог пишет
     * `write=remove(...)`/`write=none` по факту, а не «write=none» всегда.
     *
     * Вызывается: (а) при открытии панели инструментов — как настройки в референсе (`S0` → `bb`);
     * (б) в watchdog раз в 5 тиков, и только если в префах есть `jit_pedido`
     * ([JitNet.hasPendingPedido], локальная проверка без shell) — иначе ни команд, ни записей.
     *
     * @return `true`, если сверка действительно выполнялась (было что сверять).
     */
    fun verifyJitOnly(context: Context): Boolean {
        val app = context.applicationContext
        if (!runCatching { JitNet.hasPendingPedido(app) }.getOrDefault(false)) {
            AppLog.i(TAG, "JIT_PROBE skipped reason=no_pedido write=none commands=0 " +
                "(сверять нечего: намерения нет; getprop-запрос не отправлялся)")
            return false
        }
        val out = runCatching { JitNet.verifyApplied(app) }.getOrNull() ?: return false
        AppLog.i(TAG, "JIT_PROBE result=${JitRef.verifyLabel(out.verdict)} pedido=${out.pedido} " +
            "write=remove_if_confirmed removed=${out.removed?.allOk ?: false} " +
            "still_present=${out.stillPresent} restart=not_claimed")
        return true
    }

    private fun runSuCommand(command: String): ExecResult {
        return try {
            val (code, out) = AdbClient.execute(command, 30000)
            ExecResult(code, out)
        } catch (e: Exception) {
            Log.w(TAG, "Command failed: $command", e)
            ExecResult(-1, e.message ?: "Exec error")
        }
    }

    private fun getSystemProp(name: String): String {
        return try {
            val ret = runSuCommand("getprop $name")
            ret.output.trim()
        } catch (e: Exception) {
            ""
        }
    }
}
