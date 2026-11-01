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
        // РЕФЕРЕНС: w0/o#c (case 9) — скрипт применения плагина из CentralWatchdogService.
        // Порядок эталона: SEM_FONTE → SEM_ALVO → pm install -r -g (план А) →
        // при неудаче bind-mount (план Б, «Plano B: sobrepor o APK de fábrica») →
        // вердикт по факту `pm path`: */data/app/* ⇒ VIA=install, иначе bind-проверка
        // `grep -F " $_P " /proc/mounts` + совпадение `stat -c '%d:%i'` ⇒ VIA=bind,
        // иначе NAO_VINGOU / VIA=nenhum. Маркер /data/local/tmp/fixes/hvac_em_bind.
        // РЕФЕРЕНС: setenforce 0 перед работами, chcon u:object_r:system_app_file:s0,
        // umount в цикле `for _i in 1 2 3 4 5 6` с `umount || umount -l`.
        // Наше дополнение (iter21): идемпотентность — если bind уже стоит и inode
        // совпадает, ничего не переустанавливаем и SystemUI не перезапускаем.
        val script = """
            id
            SELINUX_BEFORE=${D}(getenforce 2>/dev/null)
            setenforce 0 2>/dev/null || true
            _RC=1
            NEED_RESTART=0
            mkdir -p $STAGE_DIR
            cp -f '$tmp' $STAGE_APK 2>/dev/null || echo CP_FALHOU
            chown root:root $STAGE_APK 2>/dev/null || true
            chmod 644 $STAGE_APK 2>/dev/null || true
            if [ ! -f $STAGE_APK ]; then
              echo SEM_FONTE
            else
              _DST=${D}(pm path $OEM_PKG 2>/dev/null | sed 's/^package://')
              if [ -z "${D}_DST" ]; then
                echo SEM_ALVO
              else
                echo "DST=${D}_DST"
                SRC_INODE_PRE=${D}(stat -c '%i' $STAGE_APK 2>/dev/null)
                TGT_INODE_PRE=${D}(stat -c '%i' ${D}_DST 2>/dev/null)
                if [ "${D}SRC_INODE_PRE" = "${D}TGT_INODE_PRE" ] && grep -F " ${D}_DST " /proc/mounts >/dev/null 2>&1; then
                  echo "ALREADY_MOUNTED (idempotent)"
                  VIA=bind
                  _RC=0
                else
                  # --- РЕФЕРЕНС w0/o#c: VIA_FORCADA — если в прошлый раз сработал bind,
                  # pm install ПРОПУСКАЕТСЯ (иначе установка «затенит» /system и плагин
                  # перестанет подхватываться SystemUI после перезагрузки).
                  _VIA_PREV=${D}(getprop persist.sys.hvacdigital.via 2>/dev/null)
                  echo "VIA_PREV=${D}_VIA_PREV"
                  if [ "${D}_VIA_PREV" = "bind" ]; then
                    echo "VIA_FORCADA=bind — pulando o pm install"
                    _FALHOU=1
                  else
                    # --- План А эталона: штатная установка APK ---
                    _OUT=${D}(pm install -r -g $STAGE_APK 2>&1 | tail -n1)
                    echo "PM=${D}_OUT"
                    case "${D}_OUT" in
                      *Success*) _FALHOU=0 ;;
                      *)         _FALHOU=1 ;;
                    esac
                  fi
                  # --- План Б эталона: bind поверх заводского APK ---
                  if [ "${D}_FALHOU" != "0" ]; then
                    chown root:root $STAGE_APK 2>/dev/null || true
                    chmod 644 $STAGE_APK 2>/dev/null || true
                    chcon u:object_r:system_app_file:s0 $STAGE_APK 2>/dev/null || echo CHCON_FALHOU
                    for _i in 1 2 3 4 5 6; do
                      grep -F " ${D}_DST " /proc/mounts >/dev/null 2>&1 || break
                      umount "${D}_DST" 2>/dev/null || umount -l "${D}_DST" 2>/dev/null || true
                    done
                    mount --bind $STAGE_APK "${D}_DST" 2>/dev/null || echo BIND_FALHOU
                    restorecon -v "${D}_DST" 2>/dev/null || true
                    NEED_RESTART=1
                  fi
                  # --- Вердикт по фактическому pm path (как в эталоне) ---
                  _P=${D}(pm path $OEM_PKG 2>/dev/null | sed 's/^package://')
                  echo "PM_PATH=${D}_P"
                  case "${D}_P" in
                    */data/app/*)
                      for _i in 1 2 3 4 5 6; do
                        grep -F " ${D}_DST " /proc/mounts >/dev/null 2>&1 || break
                        umount "${D}_DST" 2>/dev/null || umount -l "${D}_DST" 2>/dev/null || true
                      done
                      setprop persist.sys.hvacdigital.via install 2>/dev/null || true
                      rm -f /data/local/tmp/fixes/hvac_em_bind 2>/dev/null || true
                      echo VIA=install
                      NEED_RESTART=1
                      _RC=0
                      ;;
                    *)
                      PK_STAT=${D}(stat -c '%d:%i' "${D}_P" 2>/dev/null)
                      SRC_STAT=${D}(stat -c '%d:%i' $STAGE_APK 2>/dev/null)
                      MOUNTED=0
                      grep -F " ${D}_P " /proc/mounts >/dev/null 2>&1 && MOUNTED=1
                      if [ -n "${D}_P" ] && [ "${D}MOUNTED" = "1" ] && [ "${D}PK_STAT" = "${D}SRC_STAT" ]; then
                        mkdir -p /data/local/tmp/fixes 2>/dev/null
                        touch /data/local/tmp/fixes/hvac_em_bind 2>/dev/null || echo MARCADOR_HVAC_FALHOU
                        setprop persist.sys.hvacdigital.via bind 2>/dev/null || true
                        echo VIA=bind
                        _RC=0
                      else
                        setprop persist.sys.hvacdigital.via nenhum 2>/dev/null || true
                        echo NAO_VINGOU
                        echo VIA=nenhum
                      fi
                      ;;
                  esac
                fi
              fi
            fi
            rm -rf /data/dalvik-cache/*/*GeelyAutoSystemUIPlugin* 2>/dev/null || true
            rm -rf /data/dalvik-cache/*/*systemuiplugin* 2>/dev/null || true
            if [ "${D}NEED_RESTART" = "1" ]; then
              SYSUI_PID_BEFORE=${D}(pidof com.android.systemui)
              echo "SYSUI_PID_BEFORE=${D}SYSUI_PID_BEFORE"
              kill ${D}SYSUI_PID_BEFORE 2>/dev/null || true
              sleep 3
              echo "SYSUI_PID_AFTER=${D}(pidof com.android.systemui)"
            else
              echo "SYSUI_RESTART_SKIPPED (no change)"
            fi
            SELINUX_AFTER=${D}(getenforce 2>/dev/null)
            echo "SELINUX_BEFORE=${D}SELINUX_BEFORE SELINUX_AFTER=${D}SELINUX_AFTER"
            SRC_INODE=${D}(stat -c '%i' $STAGE_APK 2>/dev/null)
            _DSTP=${D}(pm path $OEM_PKG 2>/dev/null | sed 's/^package://')
            TGT_INODE=${D}(stat -c '%i' "${D}_DSTP" 2>/dev/null)
            echo "SRC_INODE=${D}SRC_INODE TGT_INODE=${D}TGT_INODE"
            echo "VIA_VERDICT=${D}VIA NEED_RESTART=${D}NEED_RESTART"
            if [ "${D}_RC" = "0" ]; then echo HVAC_DIGITAL_OK; else echo HVAC_DIGITAL_FALHOU; fi
            [ "${D}_RC" = "0" ]
        """.trimIndent()
        val (rc, out) = AdbClient.executeScript("hvac_enable", script, 40000)
        AppLog.i(TAG, "enable script rc=$rc out=${out.take(1500)}")
        // Мандат iter22 §4: вердикт VIA/PM_PATH/SELINUX — ОБЯЗАТЕЛЬНО в лог,
        // иначе по логу сборки не видно, почему панель не встала.
        val via = Regex("VIA=([a-z]+)").find(out)?.groupValues?.get(1) ?: "?"
        val viaPrev = Regex("VIA_PREV=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        val pmPath = Regex("PM_PATH=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        val selinuxPair = Regex("SELINUX_BEFORE=([^ ]*) SELINUX_AFTER=([^\\n]*)").find(out)
        val pmLine = Regex("PM=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        val semFonte = out.contains("SEM_FONTE")
        val semAlvo = out.contains("SEM_ALVO")
        AppLog.i(TAG, "HVAC VERDICT: VIA=$via VIA_PREV=$viaPrev PM=$pmLine PM_PATH=$pmPath " +
                "SELINUX_BEFORE=${selinuxPair?.groupValues?.get(1)} SELINUX_AFTER=${selinuxPair?.groupValues?.get(2)} " +
                "SEM_FONTE=$semFonte SEM_ALVO=$semAlvo")
        val mountOk = out.contains("HVAC_DIGITAL_OK") && !out.contains("MOUNT_FAIL")
        val inodeMatch = Regex("SRC_INODE=(\\d+) TGT_INODE=(\\d+)").find(out)?.let {
            it.groupValues[1] == it.groupValues[2] && it.groupValues[1].isNotEmpty() && it.groupValues[1] != "0"
        } ?: false
        val activateStatus = when {
            out.contains("ERR_ROOT_CHANNEL_UNAVAILABLE") -> ToolsStep.Status.BLOCKED
            out.contains("Permission denied") || out.contains("avc: denied") -> ToolsStep.Status.FAILED
            via == "install" && pmPath.contains("/data/app/") -> ToolsStep.Status.OK
            via == "bind" && (mountOk || inodeMatch) -> ToolsStep.Status.OK
            via == "nenhum" || out.contains("NAO_VINGOU") -> ToolsStep.Status.FAILED
            mountOk && inodeMatch -> ToolsStep.Status.OK
            mountOk -> ToolsStep.Status.PARTIAL
            else -> ToolsStep.Status.FAILED
        }
        val reason = "rc=$rc VIA=$via PM_PATH=$pmPath inode=$inodeMatch mount=$mountOk"
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
