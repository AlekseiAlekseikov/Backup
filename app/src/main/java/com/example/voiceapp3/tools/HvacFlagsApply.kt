package com.example.voiceapp3.tools

import android.content.Context

/**
 * iter33k fix1 §1 — ЕДИНЫЙ путь тумблеров слотов, ТОЧНО как в референсе CentralEXAuto 3.3.9
 * (`Lw0/x7;->j(Context, Z, Z, Z, Lw0/y8;)Z`, вызывается из обёрток `w0/ab`, `w0/ib`, `w0/ua`).
 *
 * Референс делает ровно две вещи на клик (третья, `x7.b`, — их «Ar Digital»-валидация, у нас её нет):
 *
 *  1. `x7.i(temp,batt,ext)` — одна команда `setprop persist.sys.hvacdigital.temp <0|1> &&
 *     … batt … && … exttemp … && echo FLAGS_OK` (+ наш `via`);
 *  2. `x7.g("tela")` — **recarga панели**: `p=$(pidof com.android.systemui); [ -z "$p" ] || kill $p`,
 *     затем до 20 попыток по 500 мс (общий таймаут 20 с) с проверкой, что процесс поднялся
 *     и pid сменился. Их слова о механизме: `única (bind + reinício)`.
 *
 * Процесс ПЛАГИНА референс не убивает никогда — в его дексе нет pidof этого пакета. Мягкий kill
 * процесса плагина (наша выдумка из iter19) удалён полностью.
 * Отдельной кнопки применения тоже нет: интерфейс мигает на несколько секунд — это штатное
 * поведение референса, а не дефект.
 *
 * Одна строка лога на клик:
 *   `TOGGLE [hvacBatt] → ON | flags=temp:0 batt:1 ext:0 | via=bind | apply=bar_recarga result=ok | ✓`
 *   `FLAG_APPLY method=bar_recarga tries=3 result=ok dt=1620ms`
 */
internal object HvacFlagsApply {
    private const val TAG = "CENTRAL_HvacFlagsApply"

    /** Панель (SystemUI) — единственный процесс, который перезапускает recarga (как в референсе). */
    internal const val SYSUI_PKG = "com.android.systemui"

    /** iter33l §1.2: легаси-имя наружней из рабочего лога iter19 (`temp_ext`), не чистим до решения. */
    internal const val PROP_EXT_LEGACY = "persist.sys.hvacdigital.temp_ext"

    /** iter33l §1.1: до 3 повторных push после recarga (БЕЗ новых kill). */
    internal const val FLAG_VERIFY_PUSH_TRIES = 3

    /** Референс `x7.g("tela")`: не более 20 попыток × 500 мс, общий таймаут 20 с. */
    internal const val RECARGA_TRIES = 20
    internal const val RECARGA_STEP_MS = 500L
    internal const val RECARGA_TIMEOUT_MS = 20_000L

    /** Человеческие метки тумблеров для строки `TOGGLE [hvacX]`. */
    fun toggleLabel(pref: String): String = when (pref) {
        StatusIcons.PREF_HVAC_TEMP -> "hvacTemp"
        StatusIcons.PREF_HVAC_BATT -> "hvacBatt"
        StatusIcons.PREF_HVAC_EXT_TEMP -> "hvacExtTemp"
        else -> pref
    }

    /** `via` по состоянию флагов: всё выключено ⇒ `nenhum` (сток), иначе `bind`. */
    fun viaFor(tempOn: Boolean, battOn: Boolean, extOn: Boolean): String =
        if (!tempOn && !battOn && !extOn) "nenhum" else "bind"

    fun flagsSegment(tempOn: Boolean, battOn: Boolean, extOn: Boolean): String =
        "temp:${if (tempOn) 1 else 0} batt:${if (battOn) 1 else 0} ext:${if (extOn) 1 else 0}"

    /**
     * Итоговая строка клика (без красных слов, статус — `✓` или `✗`).
     * iter33l §1.4/§2.3: `✓` только когда apply И verify подтверждены — иначе ложный успех
     * (лог 07.11: `✓ SUCCESS` при `HVACDIGITAL_PUSH rc=1 ERR_ROOT_CHANNEL_UNAVAILABLE`).
     */
    fun toggleLine(label: String, on: Boolean, flags: String, via: String, method: String,
                   result: String, verify: String = "none"): String {
        val ok = result == "ok" && verify == "ok"
        return "TOGGLE [$label] → ${if (on) "ON" else "OFF"} | flags=$flags | via=$via | " +
            "apply=$method result=$result verify=$verify | ${if (ok) "✓" else "✗"}"
    }

    /**
     * iter33l §1.1 — строка сверки записанного и найденного после recarga:
     * `FLAG_VERIFY wrote=temp:1 batt:0 ext:0 via=bind found=… via=… match=ok|rewritten`.
     * `rewritten` = SystemUI переписал `persist.sys.hvacdigital.*` своими значениями.
     */
    fun verifyLine(wrote: String, via: String, found: String, foundVia: String): String {
        val m = if (matches(wrote, via, found, foundVia)) "ok" else "rewritten"
        return "FLAG_VERIFY wrote=$wrote via=$via found=$found via_found=${foundVia.ifEmpty { "-" }} match=$m"
    }

    /** Сравнение «что записали» и «что прочитали» (порядок сегментов фиксирован). */
    fun matches(wrote: String, via: String, found: String, foundVia: String): Boolean =
        wrote.trim() == found.trim() && via.trim() == foundVia.trim()

    /** Разбор 4-строчного вывода getprop: temp, batt, exttemp, via. */
    fun parseFlagsAndVia(out: String): Pair<Triple<Boolean, Boolean, Boolean>, String> {
        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val flags = parseFlags(lines.take(3).joinToString("\n"))
        return flags to lines.getOrElse(3) { "" }
    }

    /** Строка эксперимента с именем пропа наружней (iter33l §1.2). */
    fun extProbeLine(prop: String, wrote: String, found: String): String =
        "EXT_PROBE prop=$prop wrote=$wrote found=${found.ifEmpty { "empty" }} " +
            "result=${if (found == wrote) "ok" else "missing"}"

    /** Скрипт эксперимента: пишем ОБА имени (наш `exttemp` и легаси `temp_ext`). */
    fun extProbeScript(): String =
        "setprop ${HvacDigitalProps.PROP_EXT} 1; setprop $PROP_EXT_LEGACY 1; " +
            "echo \"EXT_TMP=\$(getprop ${HvacDigitalProps.PROP_EXT}) EXT_LEGACY=\$(getprop $PROP_EXT_LEGACY})\""

    /** Строка применения (мандат fix1 §1.3): `FLAG_APPLY method=bar_recarga tries=… result=… dt=…ms`. */
    fun applyLine(method: String, tries: Int, result: String, dtMs: Long): String =
        "FLAG_APPLY method=$method tries=$tries result=$result dt=${dtMs}ms"

    /** Честная строка провала — словами референса (`barra NAO reiniciou`). */
    fun recargaFailLine(tries: Int): String =
        "bar_recarga fail (barra NAO reiniciou) tries=$tries"

    /**
     * Разбор факта из `getprop` (мандат §1.3: тумблеры показывают ФАКТ, не память приложения).
     * Вход — вывод `getprop X` по трём строкам в порядке temp, batt, exttemp; пусто = `0`.
     */
    fun parseFlags(out: String): Triple<Boolean, Boolean, Boolean> {
        val vals = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it == "1" }
            .toList()
        return Triple(vals.getOrElse(0) { false }, vals.getOrElse(1) { false }, vals.getOrElse(2) { false })
    }

    /**
     * Скрипт recarga панели — 1:1 с референсом (`x7.g("tela")`):
     * `p=$(pidof com.android.systemui); [ -z "$p" ] || kill $p`, затем ожидание подъёма
     * до [RECARGA_TRIES] попыток по [RECARGA_STEP_MS] мс с проверкой, что pid СМЕНИЛСЯ.
     */
    fun recargaScript(tries: Int = RECARGA_TRIES): String = """
        PB=@@(pidof $SYSUI_PKG)
        [ -z "@@PB" ] || kill @@PB
        i=0
        while [ @@i -lt $tries ]; do
          sleep ${RECARGA_STEP_MS / 1000.0}
          i=@@((i+1))
          PA=@@(pidof $SYSUI_PKG)
          if [ -n "@@PA" ] && [ "@@PA" != "@@PB" ]; then
            echo "RECARGA_OK pid_before=@@{PB:-none} pid_after=@@PA tries=@@i"
            exit 0
          fi
        done
        PA=@@(pidof $SYSUI_PKG)
        echo "RECARGA_FAIL pid_before=@@{PB:-none} pid_after=@@{PA:-none} tries=@@i (barra NAO reiniciou)"
    """.trimIndent().replace("@@", "$")

    /** Разбор вывода recarga: `ok|fail` и число попыток. */
    fun recargaResult(out: String): Pair<String, Int> {
        val ok = Regex("RECARGA_OK[^\\n]*tries=(\\d+)").find(out)
        if (ok != null) return "ok" to (ok.groupValues[1].toIntOrNull() ?: 0)
        val bad = Regex("RECARGA_FAIL[^\\n]*tries=(\\d+)").find(out)
        if (bad != null) return "fail" to (bad.groupValues[1].toIntOrNull() ?: 0)
        return "fail" to 0
    }

    /**
     * iter33l §2.1 — mutex: пока идёт push+recarga+verify, второй клик игнорируется
     * (в логе 07.11 клики летели пачками и recarga шла пять раз подряд).
     */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Результат клика: строка для лога/UI + честный признак успеха. */
    data class Outcome(val line: String, val ok: Boolean, val rc: Int, val match: Boolean, val busySkip: Boolean = false)

    /**
     * Один клик тумблера: преф → флаги → `via` → recarga панели (референс `x7.g("tela")`) →
     * `FLAG_VERIFY` (мандат §1.1). Успех = `rc=0` И `match=ok`; иначе `✗` (жёлтый в UI).
     */
    fun toggleOutcome(ctx: Context, pref: String, value: Boolean): Outcome {
        val label = toggleLabel(pref)
        if (!busy.compareAndSet(false, true)) {
            AppLog.i(TAG, "TOGGLE_SKIP reason=busy pref=$label value=$value (предыдущий клик ещё применяется)")
            return Outcome(
                toggleLine(label, value, "?", "?", "bar_recarga", "busy", "none"),
                ok = false, rc = 1, match = false, busySkip = true)
        }
        try {
            val app = ctx.applicationContext
            Prefs2.putBoolean(app, pref, value)
            val tempOn = Prefs2.getBoolean(app, StatusIcons.PREF_HVAC_TEMP, false)
            val battOn = Prefs2.getBoolean(app, StatusIcons.PREF_HVAC_BATT, false)
            val extOn = Prefs2.getBoolean(app, StatusIcons.PREF_HVAC_EXT_TEMP, false)
            val via = viaFor(tempOn, battOn, extOn)
            val wrote = flagsSegment(tempOn, battOn, extOn)
            val flagsCmd = HvacDigitalProps.flagsCommand(tempOn, battOn, extOn)
                .replace("&& echo FLAGS_OK", "&& setprop ${HvacDigitalProps.PROP_VIA} $via && echo FLAGS_OK")
            val (rc, out) = try {
                AdbClient.execute(flagsCmd, 6000)
            } catch (t: Throwable) {
                AppLog.w(TAG, "TOGGLE push failed: ${t.javaClass.simpleName}: ${t.message}")
                Pair(1, "")
            }
            AppLog.i(TAG, "HVACDIGITAL_PUSH rc=$rc ${HvacDigitalProps.flagsFingerprint(tempOn, battOn, extOn)} " +
                "via=$via out=${out.replace('\n', ' ').take(200)}")

            // 2) recarga панели — ровно как у референса (x7.g("tela")); только при удачном push
            val t0 = android.os.SystemClock.elapsedRealtime()
            var tries = 0
            var applyRes = "fail"
            if (rc == 0) {
                val rout = try {
                    AdbClient.executeScript("bar_recarga", recargaScript(),
                        (RECARGA_TIMEOUT_MS + 4_000L).toInt()).second
                } catch (t: Throwable) {
                    AppLog.w(TAG, "bar_recarga threw: ${t.javaClass.simpleName}: ${t.message}")
                    ""
                }
                val (r, n) = recargaResult(rout)
                applyRes = r
                tries = n
                AppLog.i(TAG, "RECARGA out=${rout.replace('\n', ' ').take(240)}")
            }
            val dt = android.os.SystemClock.elapsedRealtime() - t0
            AppLog.i(TAG, applyLine("bar_recarga", tries, applyRes, dt))
            if (rc != 0) AppLog.i(TAG, "bar_recarga skipped reason=push_failed rc=$rc")
            else if (applyRes != "ok") AppLog.i(TAG, recargaFailLine(tries))

            // 3) FLAG_VERIFY (§1.1): что реально лежит в props после подъёма панели
            var match = false
            var verify = "no_channel"
            if (rc == 0) {
                var r = verifyNow(wrote, via)
                match = r.first
                verify = if (match) "ok" else "rewritten"
                var retries = 0
                // повторный push БЕЗ новых kill — до 3 раз (мандат §1.1)
                while (!match && retries < FLAG_VERIFY_PUSH_TRIES) {
                    retries++
                    runCatching { AdbClient.execute(flagsCmd, 6000) }
                    AppLog.i(TAG, "FLAG_REWRITE pushed=$wrote found=${r.second} retries=$retries")
                    r = verifyNow(wrote, via)
                    match = r.first
                    verify = if (match) "ok" else "rewritten"
                }
            }
            val ok = rc == 0 && match
            val line = toggleLine(label, value, wrote, via, "bar_recarga", applyRes, verify)
            AppLog.i(TAG, line)
            if (!ok && rc == 0) AppLog.i(TAG, "TOGGLE_NOT_APPLIED pref=$label reason=props_rewritten " +
                "(SystemUI не принял флаги — см. FLAG_VERIFY/FLAG_REWRITE)")
            return Outcome(line, ok, rc, match)
        } finally {
            busy.set(false)
        }
    }

    /** Один замер: читаем 3 флага + `via`, печатаем `FLAG_VERIFY`, возвращаем (match, found). */
    private fun verifyNow(wrote: String, via: String): Pair<Boolean, String> {
        val cmd = "echo \$(getprop ${HvacDigitalProps.PROP_TEMP}); " +
            "echo \$(getprop ${HvacDigitalProps.PROP_BATT}); " +
            "echo \$(getprop ${HvacDigitalProps.PROP_EXT}); " +
            "echo \$(getprop ${HvacDigitalProps.PROP_VIA})"
        val out = try {
            AdbClient.execute(cmd, 5000).second
        } catch (t: Throwable) {
            AppLog.w(TAG, "FLAG_VERIFY failed: ${t.javaClass.simpleName}: ${t.message}")
            ""
        }
        if (out.isBlank()) {
            AppLog.i(TAG, "FLAG_VERIFY wrote=$wrote via=$via found=none match=no_channel")
            return false to "none"
        }
        val (found, foundVia) = parseFlagsAndVia(out)
        val foundLine = flagsSegment(found.first, found.second, found.third)
        AppLog.i(TAG, verifyLine(wrote, via, foundLine, foundVia))
        return matches(wrote, via, foundLine, foundVia) to foundLine
    }

    /**
     * iter33l §1.2 — эксперимент с именем пропа наружней: пишем `exttemp=1` И `temp_ext=1`,
     * делаем recarga и печатаем `EXT_PROBE prop=… wrote=1 found=… result=ok|missing` на каждое имя.
     * Владелец смотрит на панель: появилась наружняя — значит SystemUI читает это имя.
     */
    fun extProbe(ctx: Context): Outcome {
        if (!busy.compareAndSet(false, true)) {
            return Outcome("EXT_PROBE skipped reason=busy", false, 1, false, busySkip = true)
        }
        try {
            val (rc, out) = try {
                AdbClient.execute(extProbeScript(), 6000)
            } catch (t: Throwable) { Pair(1, "") }
            AppLog.i(TAG, "EXT_PROBE write rc=$rc out=${out.replace('\n', ' ').take(160)}")
            if (rc != 0) {
                AppLog.i(TAG, "EXT_PROBE prop=temp_ext,exttemp wrote=1 found=none result=no_channel")
                return Outcome("EXT_PROBE result=no_channel", false, rc, false)
            }
            val rout = runCatching {
                AdbClient.executeScript("bar_recarga", recargaScript(), (RECARGA_TIMEOUT_MS + 4_000L).toInt()).second
            }.getOrDefault("")
            AppLog.i(TAG, "RECARGA out=${rout.replace('\n', ' ').take(200)}")
            val read = "echo \$(getprop ${HvacDigitalProps.PROP_EXT}); echo \$(getprop $PROP_EXT_LEGACY)"
            val vals = runCatching { AdbClient.execute(read, 5000).second }.getOrDefault("")
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val l1 = extProbeLine(HvacDigitalProps.PROP_EXT.substringAfterLast('.'), "1", vals.getOrElse(0) { "" })
            val l2 = extProbeLine("temp_ext", "1", vals.getOrElse(1) { "" })
            AppLog.i(TAG, l1)
            AppLog.i(TAG, l2)
            AppLog.i(TAG, "EXT_PROBE done — владельцу: посмотрите, появилась ли наружняя на панели " +
                "(если да — какое имя читает SystemUI, см. две строки выше)")
            return Outcome(l1, true, 0, true)
        } finally {
            busy.set(false)
        }
    }

    /** Read-only факт флагов одной командой (для строк лога/UI). */
    fun readFlagsLine(): String {
        val cmd = "echo \$(getprop ${HvacDigitalProps.PROP_TEMP}); " +
            "echo \$(getprop ${HvacDigitalProps.PROP_BATT}); " +
            "echo \$(getprop ${HvacDigitalProps.PROP_EXT})"
        val out = try { AdbClient.execute(cmd, 5000).second } catch (t: Throwable) { "" }
        val (t, b, e) = parseFlags(out)
        return flagsSegment(t, b, e)
    }

    /**
     * Синхронизация UI↔факт (мандат §1.3): читаем `getprop` и, если префы разошлись с фактом,
     * пишем строку в лог и выравниваем префы по факту (источник истины — props).
     */
    @Volatile private var lastSyncAtMs = 0L
    internal const val SYNC_EVERY_MS = 60_000L

    fun syncFromDevice(ctx: Context, force: Boolean = false): Triple<Boolean, Boolean, Boolean>? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastSyncAtMs < SYNC_EVERY_MS) return cachedFlags
        lastSyncAtMs = now
        val cmd = "echo \$(getprop ${HvacDigitalProps.PROP_TEMP}); " +
            "echo \$(getprop ${HvacDigitalProps.PROP_BATT}); " +
            "echo \$(getprop ${HvacDigitalProps.PROP_EXT})"
        val out = try { AdbClient.execute(cmd, 5000).second } catch (t: Throwable) { return cachedFlags }
        if (out.isBlank()) return cachedFlags
        val (t, b, e) = parseFlags(out)
        cachedFlags = Triple(t, b, e)
        val app = ctx.applicationContext
        val pt = Prefs2.getBoolean(app, StatusIcons.PREF_HVAC_TEMP, false)
        val pb = Prefs2.getBoolean(app, StatusIcons.PREF_HVAC_BATT, false)
        val pe = Prefs2.getBoolean(app, StatusIcons.PREF_HVAC_EXT_TEMP, false)
        if (pt != t || pb != b || pe != e) {
            AppLog.i(TAG, "FLAGS_SYNC prefs=${flagsSegment(pt, pb, pe)} fact=${flagsSegment(t, b, e)} " +
                "action=ui_from_props (источник истины — persist.sys.hvacdigital.*)")
            Prefs2.putBoolean(app, StatusIcons.PREF_HVAC_TEMP, t)
            Prefs2.putBoolean(app, StatusIcons.PREF_HVAC_BATT, b)
            Prefs2.putBoolean(app, StatusIcons.PREF_HVAC_EXT_TEMP, e)
        }
        return cachedFlags
    }

    @Volatile internal var cachedFlags: Triple<Boolean, Boolean, Boolean>? = null
}
