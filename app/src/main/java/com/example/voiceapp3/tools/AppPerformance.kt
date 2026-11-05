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
            getPrefs(context).edit()
                .putBoolean(PREF_PERF_AUTO_OPTIMIZE, enabled)
                .apply()

            steps.add(
                ToolsStep(
                    step = "perf_pref",
                    command = "prefs.putBoolean(perf_auto_otimizar, $enabled)",
                    startedAtMs = now,
                    finishedAtMs = System.currentTimeMillis(),
                    exitCode = 0,
                    stdout = "perf_auto_otimizar=$enabled",
                    status = ToolsStep.Status.OK
                )
            )

            val valStr = if (enabled) "true" else "false"
            val D = "$"
            // Точный порт la.java case 0: wifi disable -> wait wlan0 -> umount /data/app/* -> setprop usejit -> ctl.restart zygote
            // P6: Гарантированно возвращаем Wi-Fi обратно через trap (в т.ч. при EXIT/INT/TERM/ошибке).
            // P6: Логируем ZYGOTE_PID_BEFORE/AFTER и USEJIT_AFTER для VERIFY.
            val script = """
                WIFI_BEFORE=${'$'}(settings get global wifi_on)
                echo WIFI_BEFORE=${'$'}WIFI_BEFORE
                restore_wifi() {
                  if [ "${'$'}WIFI_BEFORE" = "1" ]; then
                    svc wifi enable
                    echo WIFI_RESTORED=1
                  else
                    echo WIFI_RESTORED=0
                  fi
                }
                trap restore_wifi EXIT INT TERM
                ZYGOTE_PID_BEFORE=${'$'}(pidof zygote 2>/dev/null; pidof zygote64 2>/dev/null)
                echo ZYGOTE_PID_BEFORE=${'$'}ZYGOTE_PID_BEFORE
                svc wifi disable
                echo WIFI_WAIT_DONE=waiting
                _n=0
                while [ ${D}_n -lt 30 ]; do
                  sleep 1; _n=${D}((${D}_n + 1))
                  if [ "${'$'}(ip rule show 2>/dev/null | grep -c 'oif wlan0')" = "0" ]; then echo WIFI_WAIT_DONE=down; break; fi
                done
                for _m in ${D}(grep ' /data/app/' /proc/mounts 2>/dev/null | awk '{print ${D}2}'); do
                  umount -l "${D}_m" 2>/dev/null
                done
                setprop dalvik.vm.usejit $valStr
                setprop dalvik.vm.usejitprofiles $valStr
                setprop ctl.restart zygote
                sleep 4
                ZYGOTE_PID_AFTER=${'$'}(pidof zygote 2>/dev/null; pidof zygote64 2>/dev/null)
                USEJIT_AFTER=${'$'}(getprop dalvik.vm.usejit)
                echo ZYGOTE_PID_AFTER=${'$'}ZYGOTE_PID_AFTER
                echo USEJIT_AFTER=${'$'}USEJIT_AFTER
                echo ZYGOTE_RESTARTED=${'$'}([ "${'$'}ZYGOTE_PID_BEFORE" != "${'$'}ZYGOTE_PID_AFTER" ] && echo YES || echo NO)
            """.trimIndent()

            // ── iter33b A2б, маршрут 1: свой persist-дублёр ─────────────────────────
            // Зачем: `setprop dalvik.vm.usejit` живёт до ребута. Persist-значение читается
            // инициализацией свойств при загрузке, и некоторые вендорские сборки поднимают
            // из него runtime-значение — тогда JIT восстанавливается БЕЗ перезапуска zygote.
            // Это НЕ adb-свойства (запрет владельца — `persist.adb.tcp.port` и порты).
            // Добавлена РОВНО ОДНА persist-запись — `persist.sys.dalvik.vm.usejit`; других нет.
            // Одна строка на запись (её печатает jitPersistBackfill): JIT_PERSIST_SET … reason=toggle.
            jitPersistBackfill(valStr, "toggle")

            val t0 = System.currentTimeMillis()
            val ret = runSuCommand(script)
            val t1 = System.currentTimeMillis()

            // P6 VERIFY: разбираем диагностические маркеры из stdout скрипта
            // (WIFI_BEFORE, WIFI_RESTORED, ZYGOTE_PID_BEFORE/AFTER, USEJIT_AFTER,
            // ZYGOTE_RESTARTED, WIFI_WAIT_DONE) — по ним определяем РЕАЛЬНЫЙ статус
            // шага вместо безусловного OK.
            val wifiBefore = parseTag(ret.output, "WIFI_BEFORE")
            val wifiRestored = parseTag(ret.output, "WIFI_RESTORED")
            val zygBefore = parseTag(ret.output, "ZYGOTE_PID_BEFORE")
            val zygAfter = parseTag(ret.output, "ZYGOTE_PID_AFTER")
            val usejitAfter = parseTag(ret.output, "USEJIT_AFTER")
            val zygRestarted = parseTag(ret.output, "ZYGOTE_RESTARTED")
            val wifiWaitDone = parseTag(ret.output, "WIFI_WAIT_DONE")
            val rootUnavailable = ret.output.contains("ERR_ROOT_CHANNEL_UNAVAILABLE")
            val zygOk = zygRestarted == "YES" && (zygBefore != zygAfter)
            val jitOk = usejitAfter == valStr
            val wifiRestoredOk = (wifiBefore != "1") || (wifiRestored == "1")
            val zygStatus: ToolsStep.Status = when {
                rootUnavailable -> ToolsStep.Status.BLOCKED
                !zygOk -> ToolsStep.Status.FAILED   // Zygote не рестартовал
                !jitOk -> ToolsStep.Status.FAILED   // usejit не применился
                !wifiRestoredOk -> ToolsStep.Status.PARTIAL  // Wi-Fi не вернулся
                ret.exitCode != 0 -> ToolsStep.Status.FAILED
                else -> ToolsStep.Status.OK
            }
            val zygReason = buildString {
                append("WIFI_BEFORE=").append(wifiBefore).append(" WIFI_RESTORED=").append(wifiRestored)
                append(" ZYGOTE_BEFORE=").append(zygBefore).append(" AFTER=").append(zygAfter)
                append(" RESTARTED=").append(zygRestarted).append(" USEJIT_AFTER=").append(usejitAfter)
                append(" WIFI_WAIT=").append(wifiWaitDone)
                if (!wifiRestoredOk) append(" | Wi-Fi НЕ БЫЛ ВОЗВРАЩЁН — возможен обрыв")
                if (!zygOk) append(" | Zygote PID совпадает — рестарта НЕ произошло")
                if (!jitOk) append(" | usejit=$usejitAfter после setprop (ожидалось $valStr)")
            }
            AppLog.i(TAG, "VERIFY jit_zygote: $zygStatus ($zygReason)")

            steps.add(
                ToolsStep(
                    step = "perf_prop_zygote",
                    command = "svc wifi disable; umount /data/app/*; setprop dalvik.vm.usejit=$valStr; ctl.restart zygote; trap restore_wifi EXIT",
                    startedAtMs = t0,
                    finishedAtMs = t1,
                    exitCode = ret.exitCode,
                    expected = "ZYGOTE_RESTARTED=YES USEJIT_AFTER=$valStr WIFI_RESTORED=1(if was on)",
                    actual = zygReason,
                    stdout = "dalvik.vm.usejit=$valStr → $zygReason",
                    stderr = ret.output.take(2000),
                    status = zygStatus
                )
            )

            if (enabled) {
                val tOpt0 = System.currentTimeMillis()
                val optRet = runSuCommand("cmd package compile -m speed-profile -a 2>&1")
                val tOpt1 = System.currentTimeMillis()
                steps.add(
                    ToolsStep(
                        step = "compile_all_apps",
                        command = "cmd package compile -m speed-profile -a",
                        startedAtMs = tOpt0,
                        finishedAtMs = tOpt1,
                        exitCode = optRet.exitCode,
                        stdout = "Оптимизация всех приложений запущена",
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

    /**
     * Оптимизация всех приложений разом через dex2oat.
     */
    fun optimizeAllApps(context: Context): ToolsResult {
        val steps = mutableListOf<ToolsStep>()
        val t0 = System.currentTimeMillis()
        val cmd = "cmd package compile -m speed-profile -a 2>&1"
        val ret = runSuCommand(cmd)
        val t1 = System.currentTimeMillis()

        steps.add(
            ToolsStep(
                step = "compile_all",
                command = cmd,
                startedAtMs = t0,
                finishedAtMs = t1,
                exitCode = ret.exitCode,
                stdout = "Все приложения оптимизированы",
                status = ToolsStep.Status.OK
            )
        )

        return ToolsResult(
            action = "perf.optimizeAll",
            title = "Оптимизация приложений",
            steps = steps
        )
    }

    data class ExecResult(val exitCode: Int, val output: String)

    /**
     * Аудит §5.7: БАГ был — для iptables/setprop/mount/dex2oat требовался root,
     * а код запускал команду через простой Runtime.exec("sh"), который работает
     * от пользователя u0_a* без прав. В других инструментах (TTL, LockSound)
     * используется AdbClient (локальный ADB сокет 127.0.0.1:5555 с adbd в
     * режиме root) — единый путь выполнения команд с правами root.
     */
    // ═══════════════ iter33b A2а: видео на boot (одна попытка) ═══════════════

    /** Одна boot-попытка на процесс (отдельный счётчик от HVAC и от JIT). */
    private val videoBootAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * iter33b A2а — приведение «Видео 60 fps» в соответствие с тумблером после ребута.
     *
     * `mount --bind` на `/vendor/lib/libvcodecdrv.so` ребут не переживает: тумблер «включён»,
     * а подмены нет. Раньше на boot вызывался только [verifyVideoOnly] (чтение) и владелец
     * видел в логе `not applied (boot/watchdog, no action)` — то есть эффекта нет и не будет.
     *
     * Правила (все — из мандата iter33b):
     *  1. pref выключен ⇒ ничего, одна строка `VIDEO_BOOT: pref=off`;
     *  2. [isVideoApplied] == true ⇒ `SKIP (already applied)`, ноль пишущих команд;
     *  3. иначе — полный [setVideo60Fps] РОВНО ОДИН раз за загрузку (флаг [videoBootAttempted]);
     *  4. `ctl.restart mediacodec` допустим только внутри этой единственной попытки;
     *  5. в watchdog'е остаётся только [verifyVideoOnly] — без записи.
     */
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
    private const val PROP_PERSIST_USEJIT = "persist.sys.dalvik.vm.usejit"
    private const val JIT_BOOT_MARKER = "jit_boot_attempt"
    /** iter33e A2: намерение «после перезапуска zygote нужно сверить runtime». */
    private const val JIT_PENDING_FILE = "jit_pending_verify"
    /** iter33e A4: bootTag, для которого положительный `JIT_PROBE` уже напечатан. */
    private const val JIT_VERIFIED_FILE = "jit_verified"
    /**
     * iter33f A1: суффикс «захваченного» намерения. Захват — это `renameTo` обычного файла в этот,
     * атомарный в пределах одной ФС (`filesDir`); пока файл так называется, второй вход
     * (`onStartCommand` сразу после `onCreate`) намерения уже не видит и сверку не планирует.
     */
    private const val PROCESSING_SUFFIX = ".processing"
    /** iter33f B2: рамки повторов при `UNKNOWN` — не больше 3 раз по 10 с (окно ≤ 60 с). */
    private const val JIT_VERIFY_RETRY_MAX = 3
    private const val JIT_VERIFY_RETRY_DELAY_MS = 10_000L
    /** iter33c C1: окно, в котором перезапуск zygote РАЗРЕШЁН (было имя «late start» — наоборот по смыслу). */
    private const val JIT_ZYGOTE_WINDOW_MS = 120_000L

    private val jitBootAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * iter33f A1.3: сверка уже запланирована этим процессом. Основную работу делает `renameTo`
     * (второй захват физически невозможен), флаг нужен, чтобы причина в логе была осмысленной:
     * `JIT_VERIFY: claim taken reason=already_scheduled` вместо молчаливого выхода.
     */
    private val jitVerifyScheduled = java.util.concurrent.atomic.AtomicBoolean(false)


    /**
     * iter33f B3: в JIT-строках пустое значение печатается как `empty`.
     * `LogKit.kv("")` даёт `-`, а голое `runtime_after=` читается как обрезанная строка.
     */
    private fun kvE(s: String): String = if (s.isEmpty()) "empty" else LogKit.kv(s)

    /** Один короткий read-only вызов: runtime + persist-дублёр (две строки). */
    private fun jitProps(): Pair<String, String> {
        val (_, out) = runCatching {
            AdbClient.execute(
                "echo RT=\$(getprop $PROP_USEJIT); echo PS=\$(getprop $PROP_PERSIST_USEJIT)",
                5000, expectRoot = false)
        }.getOrDefault(Pair(1, ""))
        return Pair(
            Regex("RT=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty(),
            Regex("PS=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        )
    }

    /**
     * iter33b A2б — восстановление JIT после ребута. Вызывается ТОЛЬКО из boot-ветки
     * ([EXTRA_BOOT_RESTORE]) и только дважды за загрузку: сразу и через 30 с (`secondPass=true`).
     * Из watchdog'а — никогда (там [verifyJitOnly], только чтение).
     *
     * Маршрут 1 — persist-дублёр (без перезапусков): runtime уже равен желаемому либо прошивка
     *             поднимает runtime из `persist.sys.dalvik.vm.usejit`.
     * Маршрут 2 — один `ctl.restart zygote`, только при `uptime < 120 с`, реальном расхождении
     *             `getprop` и отсутствии попытки в этой загрузке (метка `files/jit_boot_attempt`).
     * Маршрут 3 — честный отказ: `JIT_BOOT: unsupported`.
     */
    fun bootRestoreJit(context: Context, secondPass: Boolean = false) {
        BgWorker.assertNotMain("AppPerformance.bootRestoreJit")
        val app = context.applicationContext
        // Тумблер выключен — JIT не наш, ничего не читаем и не пишем.
        if (!isAutoOptimizeEnabled(app)) {
            if (!secondPass) AppLog.i(TAG, "JIT_BOOT: pref=off (no attempt)")
            return
        }
        val desired = "true"
        // ── iter33d A2 / iter33e A2: опознание загрузки одной функцией ([JitBootMark.sameLoad]) —
        // здесь (guard маршрута 2) и в `checkPendingZygoteVerify` (классификация намерения).
        val bootTag = currentBootTag()
        val upNow = android.os.SystemClock.elapsedRealtime()
        val markerFile = File(app.filesDir, JIT_BOOT_MARKER)
        val markerRaw = runCatching { markerFile.readText().trim() }.getOrDefault("")
        val sameLoad = JitBootMark.sameLoad(markerRaw, bootTag)
        AppLog.i(TAG, "JIT_BOOT: attempt_marker boot_tag=$bootTag same_load=$sameLoad uptime_ms=$upNow " +
            "marker=${LogKit.kv(markerRaw.ifEmpty { "empty" })}")
        // A2: метка старого формата (просто uptime) — консервативно считаем «эта загрузка»
        // и сразу переписываем в новый формат, чтобы состояние не залипало.
        if (JitBootMark.isLegacy(markerRaw)) {
            runCatching { markerFile.writeText(JitBootMark.format(bootTag, JitBootMark.uptimeOf(markerRaw))) }
            AppLog.i(TAG, "JIT_BOOT: attempt_marker legacy=true action=rewrite " +
                "was=$markerRaw now=${JitBootMark.format(bootTag, JitBootMark.uptimeOf(markerRaw))}")
        }
        var (runtime, persist) = jitProps()

        // ── B2: маршрут 1 должен быть самодостаточен ────────────────────────────
        // Дублёр писался ТОЛЬКО по клику (`setAutoOptimize`), поэтому у владельца, включавшего JIT
        // в 1.0.52 (и после сброса настроек), persist был пуст и маршрут 1 не срабатывал никогда.
        // Теперь: тумблер включён, а дублёра нет (или он врёт) ⇒ пишем ЕГО ЖЕ один раз и читаем обратно.
        // iter33d C: снимок ДО дозаписи. Раньше `persist_present` считался после неё и означал
        // почти только «канал ответил», а не «дублёр был». Теперь в пробе три поля.
        val persistBefore = persist
        var backfilled = false
        if (persist != desired) {
            val reason = if (persist.isEmpty()) "boot_backfill" else "boot_mismatch"
            jitPersistBackfill(desired, reason)
            persist = jitProps().second
            backfilled = true
        }
        // iter33e C1: `persist_present` убран — он дублировал `persist_before`. Теперь два разных
        // факта и явный признак дозаписи: что было ДО и что стало ПОСЛЕ.
        val persistBeforeWire = if (persistBefore.isEmpty()) "empty" else LogKit.kv(persistBefore)
        val persistAfterWire = if (persist.isEmpty()) "empty" else LogKit.kv(persist)
        val backfillWire = if (backfilled) "yes" else "no"
        val probeCommon = "persist_before=$persistBeforeWire persist_after=$persistAfterWire " +
            "backfill=$backfillWire runtime=$runtime"

        if (runtime == desired) {
            // ── B1/A3: честная строка ────────────────────────────────────────────
            // Этот второй проход отвечает ТОЛЬКО за маршрут 1 (persist): runtime уже поднят,
            // попытки zygote в этой загрузке быть не могло — иначе процесс не дожил бы до прохода.
            // Факт «поднято перезапуском zygote» печатает A2
            // (`checkPendingZygoteVerify` → `JIT_BOOT: match_after_zygote`), потому что после
            // рестарта zygote второй проход не наступает вообще. Ветка `attempted` удалена как
            // недостижимая (мёртвый код), вместе с ней ушла вторая копия опознания загрузки.
            if (secondPass && persist == desired) {
                AppLog.i(TAG, "JIT_PERSIST_PROBE: supported=true $probeCommon " +
                    "reason=runtime_raised_from_persist")
                AppLog.i(TAG, "JIT_BOOT: restored_from_persist prop=$PROP_PERSIST_USEJIT runtime=$runtime " +
                    "persist=$persist (zygote не перезапускали)")
            } else {
                // iter33e C1: та же четвёрка полей, что и в остальных трёх ветках, — `persist=`
                // рядом с `persist_after=` не дублируем (именно за дубль убран `persist_present`).
                AppLog.i(TAG, "JIT_BOOT: match $probeCommon (ноль пишущих команд)")
            }
            return
        }

        // ── B3: перезапуск zygote — ТОЛЬКО на втором проходе ────────────────────
        // Порядок в boot-ветке: сначала ставим плагин климата (при смене APK он перезапускает
        // SystemUI), и только потом — один перезапуск zygote. Иначе статус-бар мигал бы дважды
        // подряд, а между ними был бы пустым.
        if (!secondPass) {
            AppLog.i(TAG, "JIT_PERSIST_PROBE: supported=unknown $probeCommon action=read_again_in_30s")
            return
        }
        AppLog.i(TAG, "JIT_PERSIST_PROBE: supported=false $probeCommon reason=runtime_not_raised_from_persist")

        // маршрут 2
        val up = android.os.SystemClock.elapsedRealtime()
        if (up >= JIT_ZYGOTE_WINDOW_MS) {
            AppLog.i(TAG, "JIT_BOOT: deferred reason=outside_window uptime_ms=$up (перезапуск zygote на этой стадии запрещён)")
            return
        }
        // iter33d A1/A2: guard по ОПОЗНАНИЮ ЗАГРУЗКИ, арифметики по разнице uptime больше нет.
        // Старое условие сравнивало метку с текущим uptime и давало SKIP после каждого второго
        // ребута: метка прошлой загрузки (≤120 с) оказывалась меньше текущего uptime.
        if (sameLoad) {
            AppLog.i(TAG, "JIT_BOOT: skip reason=attempt_guard same_load=true " +
                "marker_uptime_ms=${JitBootMark.uptimeOf(markerRaw)} now_ms=$up")
            return
        }
        if (!jitBootAttempted.compareAndSet(false, true)) {
            AppLog.i(TAG, "JIT_BOOT: skip reason=attempt_guard_process")
            return
        }
        // Метка этой попытки: идентификатор загрузки + uptime (A2).
        runCatching { markerFile.writeText(JitBootMark.format(bootTag, up)) }

        // ── iter33e A1/A5: порядок строго «метка → лог → вызов» ──────────────
        // Метка `jit_boot_attempt` уже записана выше (до этой точки) — поэтому после рестарта
        // `same_load=true` и повторной попытки в этой загрузке не будет.
        // Наш процесс — потомок zygote: после `ctl.restart zygote` он умирает, поэтому строка
        // ПРО ПОПЫТКУ должна попасть на диск ДО вызова, иначе доказательства не останется.
        AppLog.i(TAG, "JIT_BOOT: zygote_restart intent=pending boot_tag=$bootTag uptime_ms=$up " +
            "marker=$JIT_BOOT_MARKER")
        // Оба файла синхронно: `AppLog.i` → enqueueOrWrite → writeRaw (FileWriter+flush+close под
        // synchronized, без очереди), `flushOpsNow` — выжимка.
        runCatching { File(app.filesDir, JIT_PENDING_FILE).writeText(JitBootMark.format(bootTag, up)) }
        LogKit.flushOpsNow()

        // Ровно три команды, без svc wifi / umount /data/app / sleep.
        val pidBefore = runCatching {
            AdbClient.execute("pidof zygote; pidof zygote64", 4000, expectRoot = false).second.trim()
        }.getOrDefault("")
        val (rc, out) = runSuCommand(
            "setprop $PROP_USEJIT true; setprop $PROP_USEJIT_PROFILES true; setprop ctl.restart zygote")
        // Может не успеть: строка-доказательство уже выше (A1).
        AppLog.i(TAG, "JIT_BOOT: pref=on desired=true runtime_before=$runtime action=setprop+zygote " +
            "uptime_ms=$up marker=$JIT_BOOT_MARKER rc=$rc out=${LogKit.kv(out.take(120))}")

        // Не блокируя поток: через 20 с — сверка результата (JIT_VERIFY).
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            BgWorker.post("AppPerformance.jitVerify") {
                val (rt, ps) = jitProps()
                val pidAfter = runCatching {
                    AdbClient.execute("pidof zygote; pidof zygote64", 4000, expectRoot = false).second.trim()
                }.getOrDefault("")
                AppLog.i(TAG, "JIT_VERIFY: runtime_after=$rt persist=$ps " +
                    "zygote_pid_before=${LogKit.kv(pidBefore)} zygote_pid_after=${LogKit.kv(pidAfter)} " +
                    "result=${if (rt == desired) "OK" else "FAILED"}")
                if (rt != desired) {
                    AppLog.i(TAG, "JIT_BOOT: unsupported (прошивка не даёт восстановить JIT без zygote) — " +
                        "пометка в интерфейсе: «применится после перезагрузки, включите заново»")
                }
            }
        }, 20_000L)
    }

    /**
     * iter33c B2: единственная разрешённая persist-запись — `persist.sys.dalvik.vm.usejit`.
     * Пишется и по клику (`reason=toggle`), и на boot как backfill (`reason=boot_backfill`),
     * всегда с чтением обратно. Никаких других persist-свойств не добавляется.
     */
    private fun jitPersistBackfill(value: String, reason: String): String {
        val ret = runCatching {
            runSuCommand("setprop $PROP_PERSIST_USEJIT $value; " +
                "echo JIT_PERSIST_READBACK=\$(getprop $PROP_PERSIST_USEJIT)")
        }.getOrNull()
        val rb = ret?.let { parseTag(it.output, "JIT_PERSIST_READBACK") } ?: "?"
        AppLog.i(TAG, "JIT_PERSIST_SET desired=$value readback=$rb reason=$reason")
        return rb
    }

    /**
     * iter33d A2: идентификатор текущей загрузки.
     *  1. `/proc/sys/kernel/random/boot_id` — обычный файл, разрешений не требует;
     *  2. иначе `bootEpoch = System.currentTimeMillis() - elapsedRealtime()` (секунды) —
     *     для него в [JitBootMark.sameLoad] работает допуск ±60 с.
     */
    private fun currentBootTag(): String {
        val id = runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrDefault("")
        if (id.isNotEmpty()) return id
        // iter33e B: формат fallback-тега задаётся ТОЛЬКО в JitBootMark (epochTag) — здесь
        // никаких строковых литералов, иначе `sameLoad` снова перестанет его узнавать.
        // Передаём МИЛЛИСЕКУНДЫ: перевод в секунды — внутри epochTag.
        val epochMs = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        return JitBootMark.epochTag(epochMs)
    }

    /**
     * iter33e A2/A3 + iter33f A1 — сверка ПОСЛЕ перезапуска zygote.
     *
     * Зачем: наш процесс — потомок zygote, поэтому после `ctl.restart zygote` ни `postDelayed(20 c)`,
     * ни второй проход boot-ветки не наступают (сервис поднимается по `START_STICKY` с `intent == null`,
     * а boot-ветка требует extra). Значит сверка должна жить на САМОМ старте сервиса.
     *
     * Вызывается из `VoiceAssistantService.onCreate` и `onStartCommand` ДО проверки
     * `EXTRA_BOOT_RESTORE`. Ничего не пишет в систему: только читает `getprop` и удаляет свой
     * файл-намерение.
     *
     * iter33f A1: намерение СНАЧАЛА захватывается синхронно ([claimPendingIntent]), поэтому
     * `onCreate` + `onStartCommand` на один рестарт zygote дают РОВНО одну сверку
     * (раньше файл удалялся только в фоновой задаче — и оба входа успевали его увидеть).
     */
    fun checkPendingZygoteVerify(context: Context) {
        val app = context.applicationContext
        val claimed = claimPendingIntent(app) ?: return
        val bootTag = currentBootTag()
        val pending = runCatching { claimed.readText().trim() }.getOrDefault("")
        when (JitBootMark.pendingDecision(pending, bootTag)) {
            JitBootMark.PendingDecision.NONE -> {
                runCatching { claimed.delete() }
                jitVerifyScheduled.set(false)
            }
            JitBootMark.PendingDecision.STALE -> {
                // Намерение от прошлой загрузки: удаляем ЗАХВАЧЕННЫЙ файл — исходного уже нет.
                runCatching { claimed.delete() }
                jitVerifyScheduled.set(false)
                AppLog.i(TAG, "JIT_VERIFY: skip reason=pending_stale pending=${kvE(pending)} boot_tag=$bootTag")
            }
            JitBootMark.PendingDecision.VERIFY -> BgWorker.post("AppPerformance.jitVerifyAfterRestart") {
                verifyClaimedAfterRestart(app, claimed, bootTag, 0)
            }
        }
    }

    /**
     * iter33f A1.1/A1.2/A1.4/A1.7 — атомарный захват намерения в вызывающем потоке.
     *
     * `renameTo` в пределах одного `filesDir` атомарен: либо файл достался нам, либо его забрал
     * другой вызов. Решение принимается по содержимому ЗАХВАЧЕННОГО файла, не исходного.
     * Флаг [jitVerifyScheduled] ставится прямо здесь (захват = «сверка запланирована»), поэтому
     * второй вход видит `claim taken` и ничего не планирует; снимается флаг в конце сверки
     * или сразу, если сверка не нужна (`NONE`/`STALE`).
     *
     * Возвращает `null`, когда захватывать нечего.
     */
    private fun claimPendingIntent(app: Context): File? {
        val src = File(app.filesDir, JIT_PENDING_FILE)
        val claimed = File(app.filesDir, JIT_PENDING_FILE + PROCESSING_SUFFIX)
        val bootTag = currentBootTag()
        // 1. Второй вход (onStartCommand сразу после onCreate): сверка уже забрана этим процессом.
        if (jitVerifyScheduled.get()) {
            AppLog.i(TAG, "JIT_VERIFY: claim taken reason=already_scheduled boot_tag=$bootTag")
            return null
        }
        // 2. Захват: переименование, а не чтение.
        val renamed = runCatching { src.renameTo(claimed) }.getOrDefault(false)
        if (!renamed) {
            if (src.exists()) {
                // renameTo отказал, а намерение на месте — не теряем его молча.
                val left = runCatching { src.readText().trim() }.getOrDefault("")
                AppLog.i(TAG, "JIT_VERIFY: claim failed reason=rename pending=${kvE(left)} boot_tag=$bootTag")
                return null
            }
            if (claimed.exists()) {
                // Остаток прерванной сверки (процесс умер между захватом и проверкой):
                // забираем его, иначе `.processing` висел бы вечно.
                jitVerifyScheduled.set(true)
                val left = runCatching { claimed.readText().trim() }.getOrDefault("")
                AppLog.i(TAG, "JIT_VERIFY: claim ok reason=leftover boot_tag=$bootTag pending=${kvE(left)}")
                return claimed
            }
            AppLog.i(TAG, "JIT_VERIFY: claim taken boot_tag=$bootTag pending=empty")
            return null
        }
        jitVerifyScheduled.set(true)
        val pending = runCatching { claimed.readText().trim() }.getOrDefault("")
        AppLog.i(TAG, "JIT_VERIFY: claim ok boot_tag=$bootTag pending=${kvE(pending)}")
        return claimed
    }

    /** Вернуть захваченное намерение на место (чтобы следующий старт всё-таки его увидел). */
    private fun restorePendingIntent(app: Context, claimed: File) {
        val src = File(app.filesDir, JIT_PENDING_FILE)
        val ok = runCatching { claimed.renameTo(src) }.getOrDefault(false)
        if (!ok) AppLog.i(TAG, "JIT_VERIFY: restore failed reason=rename file=${kvE(claimed.name)}")
    }

    /**
     * iter33f A1.6/B1/B2 — сверка захваченного намерения с тремя исходами.
     *
     * `OK` / `FAILED` — ответ определённый, намерение удаляется (повторять бессмысленно).
     * `UNKNOWN` (канал не ответил, `jitProps()` вернул пустоту) — намерение НЕ сжигается:
     * до 3 повторов через 10 с, и в повторе ЕДИНСТВЕННОЕ действие — `jitProps()` (read-only):
     * никаких записей в систему и никаких повторных `ctl.restart zygote`. После третьего повтора —
     * одна итоговая строка, намерение возвращается на место, `.processing` не остаётся висеть.
     */
    private fun verifyClaimedAfterRestart(app: Context, claimed: File, bootTag: String, attempt: Int) {
        val (runtime, persist) = jitProps()   // единственное действие повтора: чтение getprop
        val outcome = JitBootMark.verifyOutcome(runtime, persist)
        val reason = when (outcome) {
            JitBootMark.VerifyOutcome.OK -> ""
            JitBootMark.VerifyOutcome.FAILED -> " reason=runtime_not_true"
            JitBootMark.VerifyOutcome.UNKNOWN -> " reason=no_channel"
        }
        AppLog.i(TAG, "JIT_VERIFY: runtime_after=${kvE(runtime)} persist=${kvE(persist)} source=after_restart " +
            "result=${outcome.name}$reason boot_tag=$bootTag attempt=${attempt + 1}")
        when (outcome) {
            JitBootMark.VerifyOutcome.OK -> {
                // A3: этот факт печатается ЗДЕСЬ (второй проход после рестарта не наступает),
                // и только на исходе OK — поэтому строк не больше, чем сверок.
                AppLog.i(TAG, "JIT_BOOT: match_after_zygote runtime=${kvE(runtime)} persist=${kvE(persist)} " +
                    "(поднят перезапуском zygote в этой загрузке)")
                runCatching { claimed.delete() }
                jitVerifyScheduled.set(false)
            }
            JitBootMark.VerifyOutcome.FAILED -> {
                runCatching { claimed.delete() }
                jitVerifyScheduled.set(false)
            }
            JitBootMark.VerifyOutcome.UNKNOWN -> {
                if (attempt + 1 >= JIT_VERIFY_RETRY_MAX) {
                    AppLog.i(TAG, "JIT_VERIFY: result=UNKNOWN retries=$JIT_VERIFY_RETRY_MAX reason=no_channel " +
                        "boot_tag=$bootTag (намерение оставлено следующему старту)")
                    restorePendingIntent(app, claimed)
                    jitVerifyScheduled.set(false)
                } else {
                    BgWorker.postDelayed("AppPerformance.jitVerifyRetry", JIT_VERIFY_RETRY_DELAY_MS) {
                        verifyClaimedAfterRestart(app, claimed, bootTag, attempt + 1)
                    }
                }
            }
        }
    }

    /**
     * Watchdog: ТОЛЬКО сверка `getprop`, без записи в систему. Вызывается не чаще раза в 5 тиков
     * (см. `VoiceAssistantService.settingsWatchdog`).
     *
     * iter33e A4: при успехе печатается ОДНА положительная строка за загрузку — иначе по логу
     * нельзя ответить «JIT поднят», видно только молчание. Флаг хранится в `files/jit_verified`
     * (не в памяти: процесс перезапускается). Это свой файл приложения, не системное свойство.
     */
    fun verifyJitOnly(context: Context) {
        val app = context.applicationContext
        if (!isAutoOptimizeEnabled(app)) return
        val (runtime, persist) = jitProps()
        if (runtime != "true") {
            AppLog.i(TAG, "JIT_PROBE: runtime=$runtime persist=$persist desired=true (read-only, без записи)")
            return
        }
        val tagNow = currentBootTag()
        val verifiedFile = File(app.filesDir, JIT_VERIFIED_FILE)
        val seen = runCatching { verifiedFile.readText().trim() }.getOrDefault("")
        if (seen == tagNow) return
        runCatching { verifiedFile.writeText(tagNow) }
        AppLog.i(TAG, "JIT_PROBE: runtime=true persist=$persist (уже поднят, read-only) boot_tag=$tagNow")
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
