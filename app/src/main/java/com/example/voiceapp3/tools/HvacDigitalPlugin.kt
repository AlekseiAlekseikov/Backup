package com.example.voiceapp3.tools

import android.content.Context
import android.util.Log
import com.example.voiceapp3.R
import java.io.File
import java.io.FileOutputStream

/**
 * Установщик цифрового оверлея SystemUI (hvac_digital).
 * Команды через AdbClient (su-fallback с SELinux sandboxing).
 * Все ключевые шаги пишутся в AppFileLogger.
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
            AppFileLogger.d(TAG, "checkStatus pm path rc=$rc out=${out.take(200)}")
            if (rc == 0) {
                val m = Regex("package:(\\S+)").find(out.trim())
                if (m != null) oemPath = m.groupValues[1]
            }
        } catch (t: Throwable) { AppFileLogger.e(TAG, "checkStatus pm path failed", t) }
        try {
            val (_, out2) = AdbClient.execute("grep -F GeelyAutoSystemUIPlugin /proc/mounts || echo NOMOUNT", 4000)
            val t = out2.trim()
            if (t.isNotEmpty() && t != "NOMOUNT" && !t.contains("NOMOUNT")) {
                mounted = true
                mountLine = t.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
            }
            AppFileLogger.d(TAG, "checkStatus mounts mounted=$mounted line=${mountLine.take(200)}")
        } catch (t: Throwable) { AppFileLogger.e(TAG, "checkStatus mounts failed", t) }
        val oemInstalled = try { ctx.packageManager.getPackageInfo(OEM_PKG, 0); true } catch (_: Exception) { false }
        return Status(oemInstalled, mounted, mountLine, oemPath)
    }

    fun enable(ctx: Context): ToolsResult {
        AppFileLogger.i(TAG, "enable called")
        val steps = ArrayList<ToolsStep>()
        val status0 = checkStatus(ctx)
        steps += stepOf("check", "pm path", if (status0.oemPluginInstalled) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
            expected = "oem installed",
            actual = "oem=${status0.oemPluginInstalled} path=${status0.oemPkgPath} mounted=${status0.overlayMounted}",
            stdout = status0.mountLine)
        if (!status0.oemPluginInstalled || status0.oemPkgPath == null) {
            AppFileLogger.e(TAG, "enable aborted: OEM pkg not found")
            return ToolsResult("hvacdigital.plugin.enable", "Цифровой климат", steps)
        }
        val dst = status0.oemPkgPath
        val tmpFile = File(ctx.cacheDir, "sysui_staging.apk")
        var extracted = false; var copyErr = ""
        try {
            ctx.resources.openRawResource(R.raw.geely_auto_systemui_plugin_digital).use { inp ->
                FileOutputStream(tmpFile).use { out -> inp.copyTo(out) }
            }
            extracted = tmpFile.length() > 1_000_000
            AppFileLogger.i(TAG, "stage APK extracted: ${tmpFile.length()} bytes")
        } catch (t: Throwable) { copyErr = "${t.javaClass.simpleName}: ${t.message}"; AppFileLogger.e(TAG, "stage extract failed", t) }
        steps += stepOf("stage_extract", "extract APK",
            if (extracted) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
            expected = ">=1MB", actual = if (extracted) "${tmpFile.length()} bytes" else copyErr,
            stdout = "extracted")
        if (!extracted) return ToolsResult("hvacdigital.plugin.enable", "Цифровой климат", steps)

        val tmp = tmpFile.absolutePath.replace("'", "'\\''")
        BgWorker.assertNotMain("HvacDigitalPlugin.enable")
        val script = """
            id
            SELINUX_BEFORE=${'$'}(getenforce 2>/dev/null)
            # P5: setenforce 0 удалён — на user-билде отклоняется и маскирует ошибку mount.
            mkdir -p $STAGE_DIR
            cp -f '$tmp' $STAGE_APK
            chown root:root $STAGE_APK 2>/dev/null || echo CHOWN_FAIL
            chmod 644 $STAGE_APK
            chcon u:object_r:system_app_file:s0 $STAGE_APK 2>/dev/null || chcon u:object_r:vendor_file:s0 $STAGE_APK 2>/dev/null || true
            DST="$dst"
            umount ${'$'}DST 2>/dev/null || true
            SYSUI_PID_BEFORE=${'$'}(pidof com.android.systemui)
            mount --bind $STAGE_APK ${'$'}DST 2>&1 || echo MOUNT_FAIL
            restorecon -v ${'$'}DST 2>/dev/null || true
            rm -rf /data/dalvik-cache/*/*GeelyAutoSystemUIPlugin* 2>/dev/null || true
            rm -rf /data/dalvik-cache/*/*systemuiplugin* 2>/dev/null || true
            kill ${'$'}SYSUI_PID_BEFORE 2>/dev/null || true
            sleep 3
            SYSUI_PID_AFTER=${'$'}(pidof com.android.systemui)
            SELINUX_AFTER=${'$'}(getenforce 2>/dev/null)
            echo SELINUX_BEFORE=${'$'}SELINUX_BEFORE SELINUX_AFTER=${'$'}SELINUX_AFTER SYSUI_PID_BEFORE=${'$'}SYSUI_PID_BEFORE SYSUI_PID_AFTER=${'$'}SYSUI_PID_AFTER
            SRC_INODE=${'$'}(stat -c '%i' $STAGE_APK 2>/dev/null)
            TGT_INODE=${'$'}(stat -c '%i' ${'$'}DST 2>/dev/null)
            echo SRC_INODE=${'$'}SRC_INODE TGT_INODE=${'$'}TGT_INODE
            if grep -F " $STAGE_APK " /proc/mounts >/dev/null 2>&1 || grep -F GeelyAutoSystemUIPlugin /proc/mounts >/dev/null 2>&1; then echo HVAC_DIGITAL_OK; else echo HVAC_DIGITAL_FALHOU; fi
        """.trimIndent()
        val (rc, out) = AdbClient.execute(script, 40000)
        AppFileLogger.i(TAG, "enable script rc=$rc out=${out.take(1000)}")
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
        AppFileLogger.i(TAG, "disable called")
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
        AppFileLogger.i(TAG, "disable script rc=$rc out=${out.take(500)}")
        val ok = rc == 0 && out.contains("REVERTED_OK")
        AppLog.i(TAG, "VERIFY hvacdigital.disable: expected REVERTED_OK got rc=$rc → ${if(ok)"SUCCESS"else"FAILED"}")
        steps += stepOf("revert", "umount", if (ok) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL,
            expected = "REVERTED_OK", actual = "rc=$rc", stdout = out)
        val post = checkStatus(ctx)
        steps += stepOf("post_check", "grep mounts", if (!post.overlayMounted) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
            expected = "unmounted", actual = "mounted=${post.overlayMounted}", stdout = post.mountLine)
        return ToolsResult("hvacdigital.plugin.disable", "Откат к OEM", steps)
    }

    private fun stepOf(step: String, command: String, status: ToolsStep.Status,
                       expected: String = "", actual: String = "", stdout: String = ""): ToolsStep {
        val t = android.os.SystemClock.elapsedRealtime()
        return ToolsStep(step=step, command=command, startedAtMs=t, finishedAtMs=t,
            exitCode = if (status==ToolsStep.Status.OK) 0 else 1,
            stdout=stdout, stderr="", expected=expected, actual=actual, status=status)
    }
}
