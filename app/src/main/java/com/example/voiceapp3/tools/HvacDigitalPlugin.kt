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
        val oemPkgPath: String?,
        /** iter23 P2: ok|err|nomount — почему именно такой вердикт по mount. */
        val mountReason: String = "nomount",
        /** iter23 P2: команда pm path не выполнилась (канал) — это НЕ «пакет не установлен». */
        val pmCommandFailed: Boolean = false,
        /** iter23 P2: путь pm path указывает в /data/app → установка «затенила» /system. */
        val shadowedByInstall: Boolean = false
    )

    /**
     * iter23 P2: checkStatus БЕЗ root-канала.
     * `pm path` и `grep /proc/mounts` — дешёвые команды, они идут через локальный sh
     * (наше приложение работает как uid=1000(system)), а не через adb: раньше при
     * мёртвом канале они падали, и результат трактовался как «пакет не установлен»
     * и «смонтировано=true» одновременно.
     *
     * Строгий разбор /proc/mounts (iter23 P2.3): монтированием считается ТОЛЬКО строка
     * формата `<dev> <mountpoint> <fstype> ...`, где mountpoint содержит
     * GeelyAutoSystemUIPlugin. Любой `ERR_*`, пустой вывод и `NOMOUNT` — «не смонтировано».
     */
    fun checkStatus(ctx: Context): Status {
        var oemPath: String? = null
        var mounted = false
        var mountLine = ""
        var mountReason = "nomount"
        var pmFailed = false

        val (pmRc, pmOut) = try {
            AdbClient.shellLocal("pm path $OEM_PKG 2>&1", 6000)
        } catch (t: Throwable) {
            AppLog.e(TAG, "checkStatus pm path threw", t); Pair(1, "EXC:${t.message}")
        }
        AppLog.d(TAG, "checkStatus pm path rc=$pmRc out=${pmOut.take(200)}")
        val pmLine = pmOut.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("package:") }
        if (pmRc == 0 && pmLine != null) {
            oemPath = pmLine.removePrefix("package:").trim()
        } else if (pmOut.contains("ERR_") || pmOut.contains("Error") || pmOut.contains("Exception") || pmOut.isBlank()) {
            pmFailed = true
            AppLog.w(TAG, "checkStatus: pm path НЕ выполнился (это не «не установлен»): rc=$pmRc out=${pmOut.take(160)}")
        }

        val (mRc, mOut) = try {
            AdbClient.shellLocal("grep -F GeelyAutoSystemUIPlugin /proc/mounts 2>&1; echo RC=\$?", 6000)
        } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
        val mountCandidates = mOut.lineSequence()
            .filter { it.contains("GeelyAutoSystemUIPlugin") && it.contains("/system/") && it.split(" ").size >= 3 }
            .toList()
        if (mountCandidates.isNotEmpty()) {
            mounted = true
            mountReason = "ok"
            mountLine = mountCandidates.first()
        } else if (mOut.contains("ERR_") || mRc != 0) {
            mountReason = "err"
        } else {
            mountReason = "nomount"
        }
        AppLog.i(TAG, "checkStatus mounts mounted=$mounted reason=$mountReason line=${mountLine.take(160)}")

        val oemInstalled = try { ctx.packageManager.getPackageInfo(OEM_PKG, 0); true } catch (_: Exception) { false }
        val shadowed = oemPath?.contains("/data/app/") == true
        if (shadowed) AppLog.w(TAG, "checkStatus: ПЛАГИН ЗАТЕНЁН установкой — pm path=$oemPath (ожидался /system/...)")
        return Status(oemInstalled, mounted, mountLine, oemPath, mountReason, pmFailed, shadowed)
    }

    /** iter23 P2: путь заводского плагина без опоры на pm — простой ls по /system. */
    private fun systemPluginPathFallback(): String? {
        val (rc, out) = try {
            AdbClient.shellLocal("ls -1 /system/app/*/*.apk /system/priv-app/*/*.apk /system/app/*/*/*.apk 2>/dev/null | grep -i -E 'systemuiplugin|GeelyAutoSystemUIPlugin'", 6000)
        } catch (_: Throwable) { Pair(1, "") }
        val first = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/system/") && it.endsWith(".apk") }
        AppLog.i(TAG, "systemPluginPathFallback rc=$rc → ${first ?: "(none)"}")
        return first
    }

    fun enable(ctx: Context): ToolsResult {
        AppLog.i(TAG, "enable called")
        val steps = ArrayList<ToolsStep>()
        val status0 = checkStatus(ctx)
        steps += stepOf("check", "pm path", if (status0.oemPluginInstalled) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
            expected = "oem installed",
            actual = "oem=${status0.oemPluginInstalled} path=${status0.oemPkgPath} mounted=${status0.overlayMounted}",
            stdout = status0.mountLine)
        // iter23 P2.4: «пакет отсутствует» и «команда не выполнилась» — РАЗНЫЕ вещи.
        // Раньше при мёртвом канале мы писали «OEM pkg not found» и выходили, хотя
        // пакет на месте (pm path просто не выполнился).
        if (!status0.oemPluginInstalled || (status0.oemPkgPath == null && status0.pmCommandFailed)) {
            if (status0.pmCommandFailed && status0.oemPluginInstalled) {
                AppLog.w(TAG, "enable: pm path не выполнился, но пакет установлен (реeстрация в PM есть) — пробуем получить путь напрямую")
            } else {
                AppLog.e(TAG, "enable aborted: OEM pkg not found (installed=${status0.oemPluginInstalled} pmFailed=${status0.pmCommandFailed})")
                return ToolsResult("hvacdigital.plugin.enable", "Цифровой климат", steps)
            }
        }
        // Путь цели: pm path → иначе поиск по /system (при ls-совпадении с plugin).
        val dst = status0.oemPkgPath ?: systemPluginPathFallback()
        if (dst == null) {
            AppLog.e(TAG, "enable aborted: не удалось определить путь плагина (pm path и /system-поиск пусты)")
            return ToolsResult("hvacdigital.plugin.enable", "Цифровой климат", steps)
        }
        AppLog.i(TAG, "enable: dst=$dst (из pm path=${status0.oemPkgPath != null})")
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
    /**
     * iter23 P2: РЕСТАВРАЦИЯ ПАНЕЛИ.
     *
     * Проблема 1.0.44: `persist.sys.hvacdigital.via = install` — плагин «затенил» систему
     * установленной копией в /data/app, а restore умел только `umount` bind-точек, поэтому
     * кнопка не помогала.
     *
     * Порядок: диагностика (без root) → `pm uninstall` теневой копии → umount bind-точек →
     * один `kill $(pidof com.android.systemui)` → readback `pm path` (ожидается /system/...).
     *
     * РЕФЕРЕНС: w0/o#c — вердикт `VIA` считается по факту `pm path`; ветка
     * ветка «путь в /data/app» там же снимает legado-bind и ставит `persist.sys.hvacdigital.via install`.
     * Снятие установленной копии — наше дополнение (в эталоне этот случай не разбирается).
     */
    fun restoreSystemUi(ctx: Context): ToolsResult {
        AppLog.i(TAG, "restoreSystemUi called (iter23 P2)")
        val steps = ArrayList<ToolsStep>()
        val pre = checkStatus(ctx)

        // 1) Диагностика без root — одной командой, в лог целиком.
        val diagCmd = """
            echo "PM_PATH=$(pm path $OEM_PKG 2>&1 | tr '\n' ' ')"
            echo "PM_LIST=$(pm list packages -f 2>/dev/null | grep -i flyme | tr '\n' ' ')"
            echo "SYS_APK=$(ls -1 /system/app/*/*.apk /system/priv-app/*/*.apk 2>/dev/null | grep -i -E 'systemuiplugin|GeelyAutoSystemUIPlugin' | tr '\n' ' ')"
            echo "MOUNTS=$(grep -F systemuiplugin /proc/mounts 2>/dev/null | tr '\n' ' ' || echo NOMOUNT)"
            echo "VIA=$(getprop persist.sys.hvacdigital.via)"
        """.trimIndent()
        val (dRc, dOut) = try { AdbClient.shellLocal(diagCmd, 8000) } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
        AppLog.i(TAG, "RESTORE_DIAG rc=$dRc: " + dOut.replace("\n", " | "))

        steps += stepOf("pre_check", "pm path + /proc/mounts + getprop via",
            if (pre.overlayMounted || pre.shadowedByInstall) ToolsStep.Status.PARTIAL else ToolsStep.Status.OK,
            expected = "no stale mount, system path",
            actual = "mounted=${pre.overlayMounted}(${pre.mountReason}) path=${pre.oemPkgPath} shadowed=${pre.shadowedByInstall}",
            stdout = dOut)

        BgWorker.assertNotMain("HvacDigitalPlugin.restoreSystemUi")

        // 2) Снять «тень» установки: если pm path в /data/app — uninstall.
        var uninstallRc = -1
        if (pre.shadowedByInstall) {
            val (uRc, uOut) = try {
                AdbClient.shellLocal("pm uninstall $OEM_PKG 2>&1 || pm uninstall -k --user 0 $OEM_PKG 2>&1", 30000)
            } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
            uninstallRc = uRc
            AppLog.i(TAG, "HVAC VERDICT: UNINSTALL=$uRc out=${uOut.replace('\n',' ').take(200)}")
            steps += stepOf("uninstall_shadow", "pm uninstall $OEM_PKG",
                if (uRc == 0) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
                expected = "Success", actual = "rc=$uRc ${uOut.replace('\n',' ').take(160)}", stdout = uOut)
        } else {
            AppLog.i(TAG, "HVAC VERDICT: UNINSTALL=skip (pm path=${pre.oemPkgPath ?: "unknown"}, тени нет)")
        }

        // 3) umount bind-точек + один перезапуск SystemUI. Нужен root-канал.
        val rootState = AdbClient.currentState()
        val haveRoot = rootState == AdbClient.ChannelState.ROOT_5555 || rootState == AdbClient.ChannelState.ROOT_65010
        var restoreOk = false
        var restoreOut = ""
        if (!haveRoot) {
            AppLog.e(TAG, "ROOT_UNAVAILABLE reason=restoreSystemUi needs root (state=$rootState port=${AdbClient.currentPort()})")
            // iter23 P2.6: это ОШИБКА шага (не «пропущено»): панель останется смонтированной.
            steps += stepOf("restore", "umount + kill systemui (нужен root-канал 5555)", ToolsStep.Status.FAILED,
                expected = "RESTORE_OK", actual = "root_channel_unavailable state=$rootState port=${AdbClient.currentPort()}",
                stdout = "Нет root-канала: 5555 не отвечает (uid=1000) — умонтировать bind и перезапустить SystemUI нельзя.")
        } else {
            val D = "$"
            val script = """
id
for MNT in ${D}(grep -F systemuiplugin /proc/mounts 2>/dev/null | awk '{print ${D}2}'); do
  echo "UMOUNT stale: ${D}MNT"
  umount "${D}MNT" 2>&1 || umount -l "${D}MNT" 2>&1 || echo "UMOUNT_FAIL ${D}MNT"
done
SYSUI_PID_BEFORE=${D}(pidof com.android.systemui)
echo "SYSUI_PID_BEFORE=${D}SYSUI_PID_BEFORE"
kill ${D}SYSUI_PID_BEFORE 2>/dev/null || true
sleep 4
echo "SYSUI_PID_AFTER=${D}(pidof com.android.systemui)"
echo "PM_PATH_AFTER=${D}(pm path $OEM_PKG 2>/dev/null | sed 's/^package://' | tr '
' ' ')"
if grep -F systemuiplugin /proc/mounts >/dev/null 2>&1; then echo RESTORE_STALE_MOUNT; else echo RESTORE_OK; fi
""".trimIndent()
            val (rRc, rOut) = AdbClient.execute(script, 25000)
            restoreOut = rOut
            restoreOk = rRc == 0 && rOut.contains("RESTORE_OK") && !rOut.contains("RESTORE_STALE_MOUNT")
            AppLog.i(TAG, "restoreSystemUi script rc=$rRc out=${rOut.take(800)}")
            steps += stepOf("restore", "umount + kill systemui",
                if (restoreOk) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
                expected = "RESTORE_OK", actual = "rc=$rRc last=${rOut.lineSequence().lastOrNull()}", stdout = rOut)
        }

        // 4) post_check: BLOCKED, если root не было (не FAILED — мы просто не проверяли).
        val post = checkStatus(ctx)
        val postStatus = when {
            !haveRoot -> ToolsStep.Status.BLOCKED
            !post.overlayMounted && post.mountReason != "err" -> ToolsStep.Status.OK
            else -> ToolsStep.Status.FAILED
        }
        steps += stepOf("post_check", "pm path + grep mounts", postStatus,
            expected = "unmounted, /system/... path",
            actual = "mounted=${post.overlayMounted}(${post.mountReason}) path=${post.oemPkgPath} shadowed=${post.shadowedByInstall}",
            stdout = post.mountLine)
        AppLog.i(TAG, "VERIFY hvacdigital.restore: restoreOk=$restoreOk uninstallRc=$uninstallRc postMounted=${post.overlayMounted}")
        return ToolsResult("hvacdigital.plugin.restore", "Вернуть панель SystemUI", steps)
    }

    private fun stepOf(step: String, command: String, status: ToolsStep.Status,
                       expected: String = "", actual: String = "", stdout: String = ""): ToolsStep {
        val t = android.os.SystemClock.elapsedRealtime()
        return ToolsStep(step=step, command=command, startedAtMs=t, finishedAtMs=t,
            exitCode = if (status==ToolsStep.Status.OK) 0 else 1,
            stdout=stdout, stderr="", expected=expected, actual=actual, status=status)
    }
}
