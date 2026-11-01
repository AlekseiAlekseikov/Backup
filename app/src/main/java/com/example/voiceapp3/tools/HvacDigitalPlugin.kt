package com.example.voiceapp3.tools

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Установщик цифрового оверлея SystemUI (hvac_digital).
 * Команды через AdbClient (su-fallback с SELinux sandboxing).
 * Все ключевые шаги пишутся в AppLog (единый файл лога приложения).
 */
object HvacDigitalPlugin {
    private const val TAG = "CENTRAL_HvacDigitalPlugin"
    private const val STAGE_DIR = "/data/local/hvac-digital"
    private const val STAGE_APK = "$STAGE_DIR/sysui.apk"
    private const val OEM_PKG = "com.flyme.auto.systemuiplugin"

    data class Status(
        val oemPluginInstalled: Boolean,
        val overlayMounted: Boolean,
        val mountLine: String,
        val oemPkgPath: String?
    )

    fun checkStatus(ctx: Context): Status {
        var oemPath: String? = null; var mounted = false; var mountLine = ""
        try {
            val (rc, out) = AdbClient.execute("pm path $OEM_PKG", 4000)
            AppLog.d(TAG, "checkStatus pm path rc=$rc out=${out.take(200)}")
            if (rc == 0) {
                val m = Regex("package:(\\S+)").find(out.trim())
                if (m != null) oemPath = m.groupValues[1]
            }
        } catch (t: Throwable) { AppLog.e(TAG, "checkStatus pm path failed", t) }
        try {
            val (_, out2) = AdbClient.execute("grep -F GeelyAutoSystemUIPlugin /proc/mounts || echo NOMOUNT", 4000)
            val t = out2.trim()
            if (t.isNotEmpty() && t != "NOMOUNT" && !t.contains("NOMOUNT")) {
                mounted = true
                mountLine = t.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
            }
            AppLog.d(TAG, "checkStatus mounts mounted=$mounted line=${mountLine.take(200)}")
        } catch (t: Throwable) { AppLog.e(TAG, "checkStatus mounts failed", t) }
        val oemInstalled = try { ctx.packageManager.getPackageInfo(OEM_PKG, 0); true } catch (_: Exception) { false }
        return Status(oemInstalled, mounted, mountLine, oemPath)
    }

    fun enable(ctx: Context): ToolsResult {
        AppLog.i(TAG, "enable called")
        val steps = ArrayList<ToolsStep>()
        val status0 = checkStatus(ctx)
        steps += stepOf("check", "pm path", if (status0.oemPluginInstalled) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
            expected = "oem installed",
            actual = "oem=${status0.oemPluginInstalled} path=${status0.oemPkgPath} mounted=${status0.overlayMounted}",
            stdout = status0.mountLine)
        if (!status0.oemPluginInstalled || status0.oemPkgPath == null) {
            AppLog.e(TAG, "enable aborted: OEM pkg not found")
            return ToolsResult("hvacdigital.plugin.enable", "Цифровой климат", steps)
        }
        val dst = status0.oemPkgPath
        val tmpFile = File(ctx.cacheDir, "sysui_staging.apk")
        var extracted = false; var copyErr = ""
        // Итерация 10 fix: ищем ресурс по имени, чтобы сборка не падала при
        // отсутствии APK в res/raw/ (файл не попадал в репозиторий из-за *.apk
        // в .gitignore; на машине владельца он есть локально, в CI — нет).
        // Если ресурса нет — честно рапортуем FAILED на шаге stage_extract,
        // сборка при этом собирается (compile-time ссылки на R.raw.* нет).
        val resId = ctx.resources.getIdentifier(
            "geely_auto_systemui_plugin_digital", "raw", ctx.packageName)
        if (resId == 0) {
            copyErr = "R.raw.geely_auto_systemui_plugin_digital отсутствует в сборке (нужен app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk)"
            AppLog.e(TAG, "stage extract: $copyErr")
        } else try {
            ctx.resources.openRawResource(resId).use { inp ->
                FileOutputStream(tmpFile).use { out -> inp.copyTo(out) }
            }
            extracted = tmpFile.length() > 1_000_000
            AppLog.i(TAG, "stage APK extracted: ${tmpFile.length()} bytes")
        } catch (t: Throwable) { copyErr = "${t.javaClass.simpleName}: ${t.message}"; AppLog.e(TAG, "stage extract failed", t) }
        steps += stepOf("stage_extract", "extract APK",
            if (extracted) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
            expected = ">=1MB / resource present",
            actual = if (extracted) "${tmpFile.length()} bytes" else copyErr,
            stdout = "extracted")
        if (!extracted) return ToolsResult("hvacdigital.plugin.enable", "Цифровой климат", steps)

        val tmp = tmpFile.absolutePath.replace("'", "'\\''")
        val D = "$" // shell dollar escape (${D}NAME → $NAME)
        BgWorker.assertNotMain("HvacDigitalPlugin.enable")
        // iter21 §2: idempotent enable — если уже смонтировано и inode совпадает — пропускаем mount/kill.
        val script = """
            id
            SELINUX_BEFORE=${D}(getenforce 2>/dev/null)
            mkdir -p $STAGE_DIR
            cp -f '$tmp' $STAGE_APK
            chown root:root $STAGE_APK 2>/dev/null || echo CHOWN_FAIL
            chmod 644 $STAGE_APK
            chcon u:object_r:system_app_file:s0 $STAGE_APK 2>/dev/null || chcon u:object_r:vendor_file:s0 $STAGE_APK 2>/dev/null || true
            DST="$dst"
            NEED_RESTART=0
            SRC_INODE_PRE=${D}(stat -c '%i' $STAGE_APK 2>/dev/null)
            TGT_INODE_PRE=${D}(stat -c '%i' ${D}DST 2>/dev/null)
            echo "pre-check: DST=${D}DST SRC_INODE=${D}SRC_INODE_PRE TGT_INODE=${D}TGT_INODE_PRE"
            if [ "${D}SRC_INODE_PRE" != "${D}TGT_INODE_PRE" ] || ! grep -F " ${D}DST " /proc/mounts >/dev/null 2>&1; then
              umount ${D}DST 2>/dev/null || true
              mount --bind $STAGE_APK ${D}DST 2>&1 || echo MOUNT_FAIL
              NEED_RESTART=1
            else
              echo "ALREADY_MOUNTED (idempotent)"
            fi
            restorecon -v ${D}DST 2>/dev/null || true
            rm -rf /data/dalvik-cache/*/*GeelyAutoSystemUIPlugin* 2>/dev/null || true
            rm -rf /data/dalvik-cache/*/*systemuiplugin* 2>/dev/null || true
            SYSUI_PID_BEFORE=${D}(pidof com.android.systemui)
            if [ "${D}NEED_RESTART" = "1" ]; then
              kill ${D}SYSUI_PID_BEFORE 2>/dev/null || true
              sleep 3
            fi
            SYSUI_PID_AFTER=${D}(pidof com.android.systemui)
            SELINUX_AFTER=${D}(getenforce 2>/dev/null)
            echo SELINUX_BEFORE=${D}SELINUX_BEFORE SELINUX_AFTER=${D}SELINUX_AFTER SYSUI_PID_BEFORE=${D}SYSUI_PID_BEFORE SYSUI_PID_AFTER=${D}SYSUI_PID_AFTER NEED_RESTART=${D}NEED_RESTART
            SRC_INODE=${D}(stat -c '%i' $STAGE_APK 2>/dev/null)
            TGT_INODE=${D}(stat -c '%i' ${D}DST 2>/dev/null)
            echo SRC_INODE=${D}SRC_INODE TGT_INODE=${D}TGT_INODE
            # iter21 §2: проверка монтирования ТОЧНО по grep -F " ${D}DST " /proc/mounts
            if grep -F " ${D}DST " /proc/mounts >/dev/null 2>&1; then echo HVAC_DIGITAL_OK; else echo HVAC_DIGITAL_FALHOU; fi
        """.trimIndent()
        val (rc, out) = AdbClient.execute(script, 40000)
        AppLog.i(TAG, "enable script rc=$rc out=${out.take(1000)}")
        val mountOk = out.contains("HVAC_DIGITAL_OK") && !out.contains("MOUNT_FAIL")
        val inodeMatch = Regex("SRC_INODE=(\\d+) TGT_INODE=(\\d+)").find(out)?.let {
            it.groupValues[1] == it.groupValues[2] && it.groupValues[1].isNotEmpty() && it.groupValues[1] != "0"
        } ?: false
        val activateStatus = when {
            out.contains("ERR_ROOT_CHANNEL_UNAVAILABLE") -> ToolsStep.Status.BLOCKED
            out.contains("Permission denied") || out.contains("avc: denied") -> ToolsStep.Status.FAILED
            mountOk && inodeMatch -> ToolsStep.Status.OK
            mountOk -> ToolsStep.Status.PARTIAL
            else -> ToolsStep.Status.FAILED
        }
        val reason = "rc=$rc inode=$inodeMatch mount=$mountOk"
        AppLog.i(TAG, "VERIFY hvacdigital.enable: $activateStatus ($reason)")
        steps += stepOf("activate", "cp+chcon+mount", activateStatus,
            expected = "HVAC_DIGITAL_OK + inode match", actual = reason, stdout = out)
        val post = checkStatus(ctx)
        steps += stepOf("post_check", "grep mounts", if (post.overlayMounted) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL,
            expected = "mounted", actual = "mounted=${post.overlayMounted}", stdout = post.mountLine)
        return ToolsResult("hvacdigital.plugin.enable", "Цифровой климат", steps)
    }

    fun disable(ctx: Context): ToolsResult {
        AppLog.i(TAG, "disable called")
        val steps = ArrayList<ToolsStep>()
        val status0 = checkStatus(ctx)
        steps += stepOf("pre_check", "grep mounts", if (status0.overlayMounted) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL,
            expected = "mounted", actual = "mounted=${status0.overlayMounted}", stdout = status0.mountLine)
        val dst = status0.oemPkgPath
        if (dst == null) return ToolsResult("hvacdigital.plugin.disable", "Откат к OEM", steps)
        BgWorker.assertNotMain("HvacDigitalPlugin.disable")
        val script = """
            id
            SELINUX_BEFORE=${'$'}(getenforce 2>/dev/null)
            umount '$dst' 2>/dev/null || true
            SYSUI_PID_BEFORE=${'$'}(pidof com.android.systemui)
            kill ${'$'}SYSUI_PID_BEFORE 2>/dev/null || true
            sleep 3
            SYSUI_PID_AFTER=${'$'}(pidof com.android.systemui)
            SELINUX_AFTER=${'$'}(getenforce 2>/dev/null)
            echo SELINUX_BEFORE=${'$'}SELINUX_BEFORE SELINUX_AFTER=${'$'}SELINUX_AFTER SYSUI_PID_BEFORE=${'$'}SYSUI_PID_BEFORE SYSUI_PID_AFTER=${'$'}SYSUI_PID_AFTER
            if grep -F GeelyAutoSystemUIPlugin /proc/mounts >/dev/null 2>&1; then echo REVERTED_FAIL; else echo REVERTED_OK; fi
        """.trimIndent()
        val (rc, out) = AdbClient.execute(script, 25000)
        AppLog.i(TAG, "disable script rc=$rc out=${out.take(500)}")
        val ok = rc == 0 && out.contains("REVERTED_OK")
        AppLog.i(TAG, "VERIFY hvacdigital.disable: expected REVERTED_OK got rc=$rc → ${if(ok)"SUCCESS"else"FAILED"}")
        steps += stepOf("revert", "umount", if (ok) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL,
            expected = "REVERTED_OK", actual = "rc=$rc", stdout = out)
        val post = checkStatus(ctx)
        steps += stepOf("post_check", "grep mounts", if (!post.overlayMounted) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
            expected = "unmounted", actual = "mounted=${post.overlayMounted}", stdout = post.mountLine)
        return ToolsResult("hvacdigital.plugin.disable", "Откат к OEM", steps)
    }

    /**
     * iter21 §2: RESTORE панели SystemUI — umount любого кривого bind-mount
     * плагина, перезапуск SystemUI и проверка, что mount НЕ висит.
     * Нужен на случай, если сломанный/неполный mount оставил шторку чёрной.
     * Команды не зависят от OEM-пути (umount по имени плагина в /proc/mounts).
     */
    fun restoreSystemUi(ctx: Context): ToolsResult {
        AppLog.i(TAG, "restoreSystemUi called (iter21 §2)")
        val steps = ArrayList<ToolsStep>()
        val pre = checkStatus(ctx)
        steps += stepOf("pre_check", "grep mounts",
            if (pre.overlayMounted) ToolsStep.Status.PARTIAL else ToolsStep.Status.OK,
            expected = "no stale mount",
            actual = "mounted=${pre.overlayMounted} line=${pre.mountLine.take(120)}",
            stdout = pre.mountLine)
        BgWorker.assertNotMain("HvacDigitalPlugin.restoreSystemUi")
        val D = "$"
        val script = """
echo '=== HVAC_DIGITAL_RESTORE ==='
id
# Найдём все bind-mount, указывающие на GeelyAutoSystemUIPlugin, и отмонтируем
for MNT in ${D}(grep -F GeelyAutoSystemUIPlugin /proc/mounts | awk '{print ${D}2}'); do
  echo "UMOUNT stale: ${D}MNT"
  umount "${D}MNT" 2>&1 || echo "UMOUNT_FAIL ${D}MNT"
done
SYSUI_PID_BEFORE=${D}(pidof com.android.systemui)
echo "SYSUI_PID_BEFORE=${D}SYSUI_PID_BEFORE"
kill ${D}SYSUI_PID_BEFORE 2>/dev/null || true
sleep 4
SYSUI_PID_AFTER=${D}(pidof com.android.systemui)
echo "SYSUI_PID_AFTER=${D}SYSUI_PID_AFTER"
if grep -F GeelyAutoSystemUIPlugin /proc/mounts >/dev/null 2>&1; then
  echo RESTORE_STALE_MOUNT
else
  echo RESTORE_OK
fi
""".trimIndent()
        val (rc, out) = AdbClient.execute(script, 25000)
        AppLog.i(TAG, "restoreSystemUi script rc=$rc out=${out.take(800)}")
        val ok = rc == 0 && out.contains("RESTORE_OK") && !out.contains("UMOUNT_FAIL") && !out.contains("RESTORE_STALE_MOUNT")
        steps += stepOf("restore", "umount+kill systemui",
            if (ok) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
            expected = "RESTORE_OK", actual = "rc=$rc out_last=${out.lineSequence().lastOrNull()}", stdout = out)
        val post = checkStatus(ctx)
        steps += stepOf("post_check", "grep mounts + sysui pid",
            if (!post.overlayMounted) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
            expected = "unmounted, sysui alive",
            actual = "mounted=${post.overlayMounted}", stdout = post.mountLine)
        AppLog.i(TAG, "VERIFY hvacdigital.restore: ${if (ok) "OK" else "FAILED"}")
        return ToolsResult("hvacdigital.plugin.restore", "Восстановить шторку", steps)
    }

    private fun stepOf(step: String, command: String, status: ToolsStep.Status,
                       expected: String = "", actual: String = "", stdout: String = ""): ToolsStep {
        val t = android.os.SystemClock.elapsedRealtime()
        return ToolsStep(step=step, command=command, startedAtMs=t, finishedAtMs=t,
            exitCode = if (status==ToolsStep.Status.OK) 0 else 1,
            stdout=stdout, stderr="", expected=expected, actual=actual, status=status)
    }
}
