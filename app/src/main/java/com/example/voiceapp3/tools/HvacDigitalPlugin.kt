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
    /** iter33b A1: ключ префа с uptime прошлой boot-попытки (защита от повтора при рестарте процесса). */
    private const val STAGE_DIR = "/data/local/hvac-digital"
    private const val STAGE_APK = "$STAGE_DIR/sysui.apk"
    private const val OEM_PKG = "com.flyme.auto.systemuiplugin"

    /**
     * iter33h D1.1 — ЕДИНСТВЕННЫЙ предикат «маунт плагина есть»: без учёта регистра, обе формы
     * имени. Используется в checkStatus (pre/post_check), в скриптах umount и в HVAC_PROBE.
     *
     * Почему: прежний `grep -F systemuiplugin` регистрозависим, а путь в /proc/mounts —
     * `GeelyAutoSystemUIPlugin`. Итог: umount не выполнялся ни разу, RESTORE_OK печатался
     * всегда, а checkStatus (искал без учёта регистра) находил маунт и ронял post_check.
     */
    internal const val MOUNT_GREP = "grep -i -E 'systemuiplugin|GeelyAutoSystemUIPlugin'"

    /** iter33 C3: последнее известное состояние монтирования (для свёртки одинаковых строк). */
    @Volatile private var lastMountState: Boolean? = null

    /** iter33 A3.4: SystemUI перезапускается не чаще одного раза за процесс приложения. */
    @Volatile private var sysuiKillDone = false
    /** iter33h B2: префы гейта по bootTag и его результат. */
    private const val PREF_BOOT_INSTALL_BOOTTAG = "hvac_boot_tag_v2"
    private const val PREF_BOOT_INSTALL_RESULT = "hvac_boot_result_v2"
    private const val RESULT_ROOT_FAIL = "ROOT_FAIL"

    /** iter33h B2: решение boot-гейта (чистая функция, покрыта тестом). */
    enum class BootGate { RUN, SKIP }

    /**
     * iter33h B2.3 — «одна попытка на загрузку» по тегу загрузки.
     *
     * • другая загрузка ⇒ `RUN` (в т.ч. пустой тег в файле — первая загрузка с этой версией);
     * • та же загрузка + прошлая попытка `OK`/`FAILED` ⇒ `SKIP`;
     * • та же загрузка + провал по отсутствию root (`ROOT_FAIL`) ⇒ `RUN` (после возврата root).
     */
    fun bootGateDecision(storedTag: String, currentTag: String, lastResult: String): BootGate {
        if (storedTag.isBlank() || currentTag.isBlank()) return BootGate.RUN
        if (storedTag.trim() != currentTag.trim()) return BootGate.RUN
        if (lastResult.trim() == RESULT_ROOT_FAIL) return BootGate.RUN
        if (lastResult.isBlank()) return BootGate.RUN
        return BootGate.SKIP
    }

    /** iter33h B2.1: тег текущей загрузки (кэш на процесс, как в JIT). */
    @Volatile private var bootTagCache: String? = null

    fun currentBootTag(): String {
        bootTagCache?.let { return it }
        val id = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrDefault("")
        val tag = if (id.isNotEmpty()) id
                  else JitBootMark.epochTag(System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime())
        bootTagCache = tag
        return tag
    }

    /** iter33b A1: сколько раз за процесс перезапускали SystemUI — для поля `kill=` в `HVAC_BOOT`. */
    @Volatile private var sysuiKillCount = 0

    /** iter33 B2: enable() — только по клику и один раз за процесс (watchdog сюда не ходит). */
    @Volatile private var enableDoneThisProcess = false
    /** iter33b A1: одна boot-попытка на процесс. */
    private val bootInstallDone = java.util.concurrent.atomic.AtomicBoolean(false)

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
            AdbClient.shellLocal("$MOUNT_GREP /proc/mounts 2>&1; echo RC=\$?", 6000)
        } catch (t: Throwable) { Pair(1, "EXC:${t.message}") }
        val mountCandidates = mOut.lineSequence()
            .filter { isOurMountLine(it) }
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
        // iter33 C3: 35 одинаковых строк за 10 минут не нужны — пишем при смене состояния
        // (это факт), остальное считаем и показываем в HEARTBEAT/ADB_TRACE_SUPPRESSED.
        if (lastMountState != mounted) {
            lastMountState = mounted
            AppLog.i(TAG, "checkStatus mounts mounted=$mounted reason=$mountReason line=${mountLine.take(160)} (изменилось)")
        } else {
            LogKit.trace("HVAC_MOUNT") { "checkStatus mounts mounted=$mounted reason=$mountReason" }
        }

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

        // iter33 A3/B2: повторный enable в том же процессе — только чтение состояния.
        if (enableDoneThisProcess) {
            val st = checkStatus(ctx)
            if (st.overlayMounted) {
                AppLog.i(TAG, "HVAC_PROBE pm_path=${st.oemPkgPath ?: "?"} mounted=true src_inode=- tgt_inode=- " +
                    "selinux=${selinux()} via=none rc=0 props=${propsLine()} (enable уже выполнен в этом процессе)")
                AppLog.i(TAG, "HVAC_RESULT: OK reason=already_enabled_this_process")
                return ToolsResult("hvacdigital.plugin.enable", "Цифровой климат", steps)
            }
        }
        val killAllowed = if (sysuiKillDone) 0 else 1
        val script = """
            id
            echo "###STAGE name=check status=BEGIN"
            SELINUX=@@(getenforce 2>/dev/null)
            echo "SELINUX=@@SELINUX"
            PM_PATH=@@(pm path $OEM_PKG 2>/dev/null | sed 's/^package://' | head -n1)
            echo "PM_PATH=@@PM_PATH"
            mkdir -p $STAGE_DIR 2>/dev/null
            cp -f '%APKSRC%' $STAGE_APK 2>/dev/null || echo CP_FAILED
            chown root:root $STAGE_APK 2>/dev/null || true
            chmod 644 $STAGE_APK 2>/dev/null || true
            chcon u:object_r:system_app_file:s0 $STAGE_APK 2>/dev/null || echo CHCON_FAILED
            SRC_INODE=@@(stat -c '%i' $STAGE_APK 2>/dev/null)
            echo "SRC_INODE=@@SRC_INODE"
            if [ -s $STAGE_APK ]; then
              echo "###STAGE name=check status=OK reason=pm_path:@@PM_PATH"
            else
              echo "###STAGE name=check status=FAILED reason=sem_fonte"
              echo "VIA=nenhum"
              echo "HVAC_DIGITAL_FALHOU"
              exit 1
            fi

            # ---------- A3: снять «затмение» установкой (/data/app → /system) ----------
            VIA_UNSHADOW=none
            case "@@PM_PATH" in
              /data/app/*)
                echo "###STAGE name=unshadow status=BEGIN"
                U1=@@(pm uninstall $OEM_PKG 2>&1)
                echo "UNSHADOW_TRY1=@@U1"
                PM2=@@(pm path $OEM_PKG 2>/dev/null | sed 's/^package://' | head -n1)
                PM_AFTER=@@PM2
                VIA_UNSHADOW=pm_uninstall
                case "@@PM2" in
                  /data/app/*)
                    U2=@@(pm uninstall-system-updates $OEM_PKG 2>&1 || cmd package uninstall-system-updates $OEM_PKG 2>&1)
                    echo "UNSHADOW_TRY2=@@U2"
                    PM_AFTER=@@(pm path $OEM_PKG 2>/dev/null | sed 's/^package://' | head -n1)
                    VIA_UNSHADOW=uninstall_system_updates
                    ;;
                esac
                case "@@PM_AFTER" in
                  /data/app/*)
                    echo "HVAC_UNSHADOW: before=@@PM_PATH after=@@PM_AFTER unshadow_via=@@VIA_UNSHADOW result=FAILED"
                    echo "VIA=install"
                    echo "###STAGE name=unshadow status=FAILED reason=pm_path:@@PM_AFTER"
                    echo "HVAC_DIGITAL_FALHOU"
                    exit 2
                    ;;
                  *)
                    echo "HVAC_UNSHADOW: before=@@PM_PATH after=@@PM_AFTER unshadow_via=@@VIA_UNSHADOW result=OK"
                    echo "###STAGE name=unshadow status=OK reason=pm_path:@@PM_AFTER"
                    ;;
                esac
                ;;
              *)
                echo "###STAGE name=unshadow status=SKIP reason=not_shadowed"
                PM_AFTER=@@PM_PATH
                ;;
            esac

            # ---------- bind-mount (идемпотентно) ----------
            TGT=@@(pm path $OEM_PKG 2>/dev/null | sed 's/^package://' | head -n1)
            [ -z "@@TGT" ] && TGT=@@PM_AFTER
            TGT_INODE=@@(stat -c '%i' "@@TGT" 2>/dev/null)
            # iter33c A1: раньше TGT_INODE существовал ТОЛЬКО внутри reason у ###STAGE, отдельного
            # echo не было — поэтому `HVAC_PROBE … tgt_inode=-`, а разбор inode не срабатывал.
            echo "TGT_INODE=@@TGT_INODE"
            MOUNTED=0
            grep -F " @@TGT " /proc/mounts >/dev/null 2>&1 && MOUNTED=1
            APK_REPLACED=0
            if [ "@@MOUNTED" = "1" ] && [ -n "@@SRC_INODE" ] && [ "@@SRC_INODE" = "@@TGT_INODE" ]; then
              echo "###STAGE name=bind status=SKIP reason=already_mounted_inode_match"
            else
              echo "###STAGE name=bind status=BEGIN"
              _i=0
              while grep -F " @@TGT " /proc/mounts >/dev/null 2>&1 && [ @@_i -lt 6 ]; do
                umount "@@TGT" 2>/dev/null || umount -l "@@TGT" 2>/dev/null || break
                _i=@@((_i+1))
              done
              mount --bind $STAGE_APK "@@TGT" 2>/dev/null || echo BIND_FAILED
              restorecon -v "@@TGT" 2>/dev/null || true
              TGT_INODE=@@(stat -c '%i' "@@TGT" 2>/dev/null)
              echo "TGT_INODE=@@TGT_INODE"
              MOUNTED=0
              grep -F " @@TGT " /proc/mounts >/dev/null 2>&1 && MOUNTED=1
              if [ "@@MOUNTED" = "1" ] && [ "@@SRC_INODE" = "@@TGT_INODE" ]; then
                APK_REPLACED=1
                echo "###STAGE name=bind status=OK reason=inode_match:@@SRC_INODE"
                setprop persist.sys.hvacdigital.via bind 2>/dev/null || true
              else
                echo "###STAGE name=bind status=FAILED reason=mounted:@@MOUNTED inode:@@SRC_INODE/@@TGT_INODE"
                setprop persist.sys.hvacdigital.via nenhum 2>/dev/null || true
                echo "VIA=nenhum"
                echo "HVAC_DIGITAL_FALHOU"
                exit 3
              fi
            fi

            # ---------- SystemUI: только если APK реально заменён и не чаще раза за процесс ----------
            if [ "@@APK_REPLACED" = "1" ] && [ "$killAllowed" = "1" ]; then
              echo "###STAGE name=sysui status=BEGIN"
              SYSUI_PID_BEFORE=@@(pidof com.android.systemui)
              echo "SYSUI_PID_BEFORE=@@SYSUI_PID_BEFORE"
              kill @@SYSUI_PID_BEFORE 2>/dev/null || true
              _k=0
              while [ -n "@@(pidof com.android.systemui)" ] && [ @@_k -lt 10 ]; do sleep 0.5; _k=@@((_k+1)); done
              echo "SYSUI_PID_AFTER=@@(pidof com.android.systemui) waited_ticks=@@_k"
              echo "###STAGE name=sysui status=OK reason=restarted"
            else
              echo "SYSUI_RESTART_SKIPPED (APK_REPLACED=@@APK_REPLACED killAllowed=$killAllowed)"
              echo "###STAGE name=sysui status=SKIP reason=no_apk_change"
            fi

            PROPS="batt:@@(getprop persist.sys.hvacdigital.batt),exttemp:@@(getprop persist.sys.hvacdigital.exttemp)"
            echo "PROPS=@@PROPS"
            # iter33b B5: итоговый VIA — ЯВНО, одной строкой. Раньше такого echo не было вовсе,
            # и `HVAC_PROBE … via=?` врал: регулярка в Kotlin ждала "VIA=", которого скрипт не печатал.
            case "@@PM_AFTER" in
              /data/app/*) echo "VIA=install" ;;
              *) echo "VIA=bind" ;;
            esac
            echo "HVAC_DIGITAL_OK"
            [ "@@MOUNTED" = "1" ] || exit 4
            exit 0
        """.trimIndent().replace("@@", "$").replace("%APKSRC%", tmpFile.absolutePath)
        val (rc, out) = AdbClient.executeScript("hvac_enable", script, 40000)
        AppLog.i(TAG, "enable script rc=$rc out=${out.take(1500)}")
        // iter33 C6: разбор шагов скрипта (###STAGE) — видно, ГДЕ встало, без чтения простыни.
        LogKit.scriptTrace("hvac_enable", out)
        // Мандат iter22 §4: вердикт VIA/PM_PATH/SELINUX — ОБЯЗАТЕЛЬНО в лог,
        // иначе по логу сборки не видно, почему панель не встала.
        // iter33c A1/A2: единственный источник истины — вердикт самого скрипта
        // (`###STAGE name=bind status=… reason=inode_match:…` + итоговая строка `VIA=…`).
        // Разбор вынесен в чистую функцию [HvacVerdict] и покрыт JVM-тестом HvacVerdictTest:
        //  • A1 — `inodeMatch` больше не зависит от несуществующей строки "SRC_INODE=n TGT_INODE=n";
        //  • A2 — `Via` читается только с начала строки (`^VIA=…$`), служебное поле стало `unshadow_via=`.
        val verdict = HvacVerdict.parse(out)
        val via = verdict.via
        val viaPrev = Regex("VIA_PREV=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        val pmPath = Regex("PM_PATH=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        val selinuxPair = Regex("SELINUX_BEFORE=([^ ]*) SELINUX_AFTER=([^\\n]*)").find(out)
        val pmLine = Regex("PM=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        val semFonte = out.contains("SEM_FONTE")
        val semAlvo = out.contains("SEM_ALVO")
        AppLog.i(TAG, "HVAC VERDICT: VIA=$via unshadow_via=${verdict.unshadowVia} bind_stage=${verdict.bindStage} " +
                "VIA_PREV=$viaPrev PM=$pmLine PM_PATH=$pmPath " +
                "SELINUX_BEFORE=${selinuxPair?.groupValues?.get(1)} SELINUX_AFTER=${selinuxPair?.groupValues?.get(2)} " +
                "SEM_FONTE=$semFonte SEM_ALVO=$semAlvo")
        val mountOk = out.contains("HVAC_DIGITAL_OK") && !out.contains("MOUNT_FAIL")
        val inodeMatch = verdict.inodeMatch
        // A1 (третье следствие): при `mountOk == true` и bind-стадии OK|SKIP шаг обязан быть OK,
        // даже если inode посчитать не удалось. PARTIAL — только когда mount есть, а вердикта по bind нет.
        val activateStatus = when (
            HvacVerdict.statusOf(
                verdict,
                mountOk = mountOk,
                rc = rc,
                permDenied = out.contains("Permission denied") || out.contains("avc: denied"),
                rootUnavailable = out.contains("ERR_ROOT_CHANNEL_UNAVAILABLE"),
                naoVingou = out.contains("NAO_VINGOU")
            )
        ) {
            "OK" -> ToolsStep.Status.OK
            "PARTIAL" -> ToolsStep.Status.PARTIAL
            "BLOCKED" -> ToolsStep.Status.BLOCKED
            else -> ToolsStep.Status.FAILED
        }
        val reason = "rc=$rc VIA=$via PM_PATH=$pmPath inode=$inodeMatch mount=$mountOk"
        AppLog.i(TAG, "VERIFY hvacdigital.enable: $activateStatus ($reason)")
        // iter33 A5: одна строка на попытку — по ней владелец сразу видит, где стоит.
        val unshadowLine = Regex("HVAC_UNSHADOW: ([^\\n]*)").find(out)?.groupValues?.get(1)?.trim()
        val probeSrc = verdict.srcInode
        val probeTgt = verdict.tgtInode
        val probeSel = Regex("SELINUX=([A-Za-z]+)").find(out)?.groupValues?.get(1) ?: "?"
        val probeProps = Regex("PROPS=([^\\n]*)").find(out)?.groupValues?.get(1)?.trim() ?: "-"
        val probePath = pmPath.ifEmpty { status0.oemPkgPath ?: "?" }
        val probeMounted = out.contains("mounted=true") || postMountedHint(out)
        AppLog.i(TAG, "HVAC_PROBE pm_path=$probePath mounted=$probeMounted src_inode=$probeSrc tgt_inode=$probeTgt " +
            "selinux=$probeSel via=$via rc=$rc props=$probeProps")
        if (unshadowLine != null) AppLog.i(TAG, "HVAC_UNSHADOW_DETAIL: $unshadowLine")
        LogKit.stateHvac(if (probeMounted) "mounted" else "not")
        val hvacResult = when (activateStatus) {
            ToolsStep.Status.OK -> "OK"
            ToolsStep.Status.PARTIAL -> "PARTIAL"
            else -> "FAILED"
        }
        AppLog.i(TAG, "HVAC_RESULT: $hvacResult reason=via:$via mount:$mountOk inode:$inodeMatch rc:$rc")
        if (hvacResult == "OK") enableDoneThisProcess = true
        if (out.contains("SYSUI_PID_BEFORE=")) { sysuiKillDone = true; sysuiKillCount++; LogKit.countSysuiKill() }
        steps += stepOf("activate", "cp+chcon+mount", activateStatus,
            expected = "HVAC_DIGITAL_OK + inode match", actual = reason, stdout = out)
        val post = checkStatus(ctx)
        steps += stepOf("post_check", "grep mounts", if (post.overlayMounted) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL,
            expected = "mounted", actual = "mounted=${post.overlayMounted}", stdout = post.mountLine)
        return ToolsResult("hvacdigital.plugin.enable", "Цифровой климат", steps)
    }

    /**
     * iter33b A1 — ОДНА попытка установки плагина на загрузку ГУ.
     *
     * Почему вообще: `mount --bind` ребут не переживает, а `enable` вызывался только по клику
     * (`StatusIcons.setHvacDigital`), boot-ветка после iter33 B2 только читала ⇒ после каждой
     * перезагрузки цифры в статус-баре отсутствовали до первого щелчка тумблером.
     *
     * Предохранители (все три обязательны):
     *  1. один раз за загрузку — атомарный флаг [bootInstallDone] + метка uptime в префах:
     *     если с прошлой попытки прошло < 5 минут по uptime, это рестарт процесса, а не ребут;
     *  2. только если реально нужно — сначала [checkStatus]; смонтировано и исходник/цель
     *     совпали по inode ⇒ выход БЕЗ единой пишущей команды;
     *  3. никаких таймеров — вызывается только из boot-ветки ([EXTRA_BOOT_RESTORE]), не из watchdog.
     *
     * Результат — ровно одна строка `HVAC_BOOT: attempt=1 reason=… kill=0|1 result=… ms=…`.
     * При `result=FAILED` повторов нет ни в этом процессе, ни по расписанию: владелец повторит кликом.
     */
    fun bootInstall(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (!bootInstallDone.compareAndSet(false, true)) {
            AppLog.i(TAG, "HVAC_BOOT: skip (attempt already made this boot)")
            return false
        }
        // iter33h B2: гейт «одна попытка на загрузку» — по bootTag, НЕ по uptime.
        // Прежний `prev_uptime_ms` застревал и любая загрузка считалась рестартом процесса.
        val bootTag = currentBootTag()
        val storedTag = Prefs2.getString(app, PREF_BOOT_INSTALL_BOOTTAG, "") ?: ""
        val lastResult = Prefs2.getString(app, PREF_BOOT_INSTALL_RESULT, "") ?: ""
        if (bootGateDecision(storedTag, bootTag, lastResult) == BootGate.SKIP) {
            AppLog.i(TAG, "HVAC_BOOT: skip reason=same_boot_done boot_tag=$bootTag last=$lastResult")
            return false
        }
        if (storedTag.isNotEmpty() && storedTag == bootTag && lastResult == RESULT_ROOT_FAIL) {
            AppLog.i(TAG, "HVAC_BOOT: retry reason=root_back boot_tag=$bootTag")
        }
        // «Попытка сделана» ставится ПОСЛЕ реального выполнения (B2.2), см. ниже.

        val st = runCatching { checkStatus(app) }.getOrNull()
        if (st == null) {
            AppLog.w(TAG, "HVAC_BOOT: skip (checkStatus threw — состояние неизвестно, ничего не пишем)")
            return false
        }
        if (st.overlayMounted && inodeMatchesStage(st.oemPkgPath)) {
            LogKit.stateHvac("mounted")
            AppLog.i(TAG, "HVAC_BOOT: skip (already mounted) pm_path=${st.oemPkgPath ?: "?"} inode=match")
            return false
        }

        val reason = if (storedTag.isEmpty()) "first_boot" else "not_mounted"
        val killsBefore = sysuiKillCount
        val t0 = android.os.SystemClock.uptimeMillis()
        val r = runCatching { enable(app) }.getOrNull()
        val dt = android.os.SystemClock.uptimeMillis() - t0
        val ok = r != null && r.steps.isNotEmpty() &&
            r.steps.none { it.status == ToolsStep.Status.FAILED || it.status == ToolsStep.Status.BLOCKED }
        val kill = if (sysuiKillCount > killsBefore) 1 else 0
        // B2.2: результат фиксируется после выполнения; провал по root попыткой не считается.
        val rootFail = !com.example.voiceapp3.tools.AdbClient.isPrivilegedAllowed() ||
            (r?.steps?.any { it.actual?.contains("ERR_ROOT_CHANNEL_UNAVAILABLE") == true } == true)
        val storedResult = when {
            ok -> "OK"
            rootFail -> RESULT_ROOT_FAIL
            else -> "FAILED"
        }
        Prefs2.putString(app, PREF_BOOT_INSTALL_BOOTTAG, bootTag)
        Prefs2.putString(app, PREF_BOOT_INSTALL_RESULT, storedResult)
        AppLog.i(TAG, "HVAC_BOOT: attempt=1 reason=$reason kill=$kill result=${if (ok) "OK" else "FAILED"} " +
            "stored=$storedResult boot_tag=$bootTag ms=$dt")
        if (!ok) AppLog.i(TAG, "HVAC_BOOT: повторов в этой загрузке нет — включите тумблер вручную (это ожидаемо)")
        if (rootFail) bootInstallDone.set(false)   // после возврата root — повтор разрешён (B2.2)
        return ok
    }

    /**
     * D1.5 — чистые функции разбора `/proc/mounts` (покрыты тестом на реальной строке из лога).
     * Строка маунта: `<dev> <mountpoint> <fstype> <opts> 0 0`.
     */
    internal fun isOurMountLine(line: String): Boolean {
        val parts = line.trim().split(" ")
        if (parts.size < 3) return false
        val mp = parts[1]
        return mp.contains("/system/") &&
            (mp.contains("systemuiplugin", true) || mp.contains("GeelyAutoSystemUIPlugin", true))
    }

    /** Первая подходящая строка маунта (или пустая) — то, что печатает checkStatus. */
    internal fun firstOurMountLine(mounts: String): String =
        mounts.lineSequence().firstOrNull { isOurMountLine(it) }?.trim().orEmpty()

    /** Сколько маунтов плагина осталось в выводе /proc/mounts. */
    internal fun countOurMounts(mounts: String): Int = mounts.lineSequence().count { isOurMountLine(it) }

    /** `UMOUNT_COUNT=N` из вывода скрипта. */
    internal fun umountCountOf(out: String): Int =
        Regex("UMOUNT_COUNT=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Read-only сверка inode: staging-файл (источник bind) и цель монтирования. */
    private fun inodeMatchesStage(targetPath: String?): Boolean {
        if (targetPath.isNullOrEmpty()) return false
        val out = runCatching {
            AdbClient.shellLocal(
                "echo SRC=\$(stat -c '%i' $STAGE_APK 2>/dev/null); " +
                    "echo TGT=\$(stat -c '%i' $targetPath 2>/dev/null)", 5000).second
        }.getOrDefault("")
        val src = Regex("SRC=([0-9]+)").find(out)?.groupValues?.get(1)
        val tgt = Regex("TGT=([0-9]+)").find(out)?.groupValues?.get(1)
        return src != null && src == tgt && src != "0"
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
            _k=0
            while [ -n "${'$'}(pidof com.android.systemui)" ] && [ ${'$'}_k -lt 10 ]; do sleep 0.5; _k=${'$'}((${'$'}_k+1)); done
            echo "SYSUI_WAIT_TICKS=${'$'}_k"
            SYSUI_PID_AFTER=${'$'}(pidof com.android.systemui)
            SELINUX_AFTER=${'$'}(getenforce 2>/dev/null)
            echo SELINUX_BEFORE=${'$'}SELINUX_BEFORE SELINUX_AFTER=${'$'}SELINUX_AFTER SYSUI_PID_BEFORE=${'$'}SYSUI_PID_BEFORE SYSUI_PID_AFTER=${'$'}SYSUI_PID_AFTER
            if $MOUNT_GREP /proc/mounts >/dev/null 2>&1; then echo REVERTED_FAIL; else echo REVERTED_OK; fi
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
            echo "MOUNTS=$($MOUNT_GREP /proc/mounts 2>/dev/null | tr '\n' ' ' || echo NOMOUNT)"
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
        // iter27 D2: «есть root» = ROOT_5555 ИЛИ ROOT_UNIX (единый хелпер, чтобы UI и логика
        // не расходились). Второго TCP-порта по-прежнему нет.
        val haveRoot = AdbClient.hasRoot()
        var restoreOk = false
        var restoreOut = ""
        if (!haveRoot) {
            AppLog.e(TAG, "ROOT_UNAVAILABLE reason=restoreSystemUi needs root (state=$rootState port=${AdbClient.currentPort()})")
            // iter23 P2.6: это ОШИБКА шага (не «пропущено»): панель останется смонтированной.
            steps += stepOf("restore", "umount + kill systemui (нужен root-канал: 5555 или unix-adbd)", ToolsStep.Status.FAILED,
                expected = "RESTORE_OK", actual = "root_channel_unavailable state=$rootState port=${AdbClient.currentPort()}",
                stdout = "Нет root-канала: 5555 не отвечает (uid=1000) — умонтировать bind и перезапустить SystemUI нельзя.")
        } else {
            val D = "$"
            val script = """
id
UMOUNT_COUNT=0
for MNT in ${D}($MOUNT_GREP /proc/mounts 2>/dev/null | awk '{print ${D}2}'); do
  echo "UMOUNT stale: ${D}MNT"
  if umount "${D}MNT" 2>&1 || umount -l "${D}MNT" 2>&1; then UMOUNT_COUNT=${D}((UMOUNT_COUNT+1)); echo "UMOUNT_OK ${D}MNT"; else echo "UMOUNT_FAIL ${D}MNT"; fi
done
echo "UMOUNT_COUNT=${D}UMOUNT_COUNT"
SYSUI_PID_BEFORE=${D}(pidof com.android.systemui)
echo "SYSUI_PID_BEFORE=${D}SYSUI_PID_BEFORE"
kill ${D}SYSUI_PID_BEFORE 2>/dev/null || true
_k=0
while [ -n "${D}(pidof com.android.systemui)" ] && [ ${D}_k -lt 10 ]; do sleep 0.5; _k=${D}((${D}_k+1)); done
echo "SYSUI_WAIT_TICKS=${D}_k"
echo "SYSUI_PID_AFTER=${D}(pidof com.android.systemui)"
echo "PM_PATH_AFTER=${D}(pm path $OEM_PKG 2>/dev/null | sed 's/^package://' | tr '
' ' ')"
if $MOUNT_GREP /proc/mounts >/dev/null 2>&1; then echo RESTORE_STALE_MOUNT; else echo RESTORE_OK; fi
""".trimIndent()
            val (rRc, rOut) = AdbClient.execute(script, 25000)
            restoreOut = rOut
            AppLog.i(TAG, "restoreSystemUi script rc=$rRc out=${rOut.take(800)}")
            steps += stepOf("restore", "umount + kill systemui",
                if (rRc == 0) ToolsStep.Status.OK else ToolsStep.Status.FAILED,
                expected = "rc=0",
                actual = "rc=$rRc umount_count=" + umountCountOf(rOut), stdout = rOut)
        }

        // 4) post_check: BLOCKED, если root не было (не FAILED — мы просто не проверяли).
        val post = checkStatus(ctx)
        // D1.2: единственный источник истины — фактический маунт ПОСЛЕ восстановления.
        restoreOk = haveRoot && !post.overlayMounted
        AppLog.i(TAG, "RESTORE_RESULT mount_before=${if (pre.overlayMounted) "есть" else "нет"} " +
            "mount_after=${if (post.overlayMounted) "есть" else "нет"} " +
            "umount_count=${umountCountOf(restoreOut)} root=$haveRoot")
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

    /** Монтирование по выводу скрипта: `###STAGE name=bind status=OK|SKIP reason=already…`. */
    private fun postMountedHint(out: String): Boolean =
        out.contains("###STAGE name=bind status=OK") || out.contains("already_mounted_inode_match")

    private fun selinux(): String = runCatching {
        val (_, o) = AdbClient.shellLocal("getenforce 2>/dev/null", 3000)
        o.trim().ifEmpty { "?" }
    }.getOrDefault("?")

    private fun propsLine(): String = runCatching {
        val (_, o) = AdbClient.shellLocal(
            "echo batt:$(getprop persist.sys.hvacdigital.batt)," +
                "exttemp:$(getprop persist.sys.hvacdigital.exttemp)", 3000)
        o.trim().ifEmpty { "-" }
    }.getOrDefault("-")

    private fun stepOf(step: String, command: String, status: ToolsStep.Status,
                       expected: String = "", actual: String = "", stdout: String = ""): ToolsStep {
        val t = android.os.SystemClock.elapsedRealtime()
        return ToolsStep(step=step, command=command, startedAtMs=t, finishedAtMs=t,
            exitCode = if (status==ToolsStep.Status.OK) 0 else 1,
            stdout=stdout, stderr="", expected=expected, actual=actual, status=status)
    }
}
