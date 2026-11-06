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

    /** Итоговая строка клика (без красных слов, статус — `✓` или `✗`). */
    fun toggleLine(label: String, on: Boolean, flags: String, via: String, method: String, result: String): String =
        "TOGGLE [$label] → ${if (on) "ON" else "OFF"} | flags=$flags | via=$via | " +
            "apply=$method result=$result | ${if (result == "ok" || result == "noop") "✓" else "✗"}"

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
     * Один клик тумблера: преф → флаги → `via` → recarga панели (референс `x7.g("tela")`).
     * Возвращает строку `TOGGLE …` (она же уходит в лог), чтобы UI мог показать статус.
     */
    fun toggle(ctx: Context, pref: String, value: Boolean): String {
        val app = ctx.applicationContext
        // 1) состояние тумблера — наш преф (не трогаем чужие props значениями)
        Prefs2.putBoolean(app, pref, value)
        val tempOn = Prefs2.getBoolean(app, StatusIcons.PREF_HVAC_TEMP, false)
        val battOn = Prefs2.getBoolean(app, StatusIcons.PREF_HVAC_BATT, false)
        val extOn = Prefs2.getBoolean(app, StatusIcons.PREF_HVAC_EXT_TEMP, false)
        val via = viaFor(tempOn, battOn, extOn)
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
        // 2) recarga панели — ровно как у референса (x7.g("tela"))
        val t0 = android.os.SystemClock.elapsedRealtime()
        var tries = 0
        var result = "fail"
        if (rc == 0) {
            val out = try {
                AdbClient.executeScript("bar_recarga", recargaScript(), (RECARGA_TIMEOUT_MS + 4_000L).toInt()).second
            } catch (t: Throwable) {
                AppLog.w(TAG, "bar_recarga threw: ${t.javaClass.simpleName}: ${t.message}")
                ""
            }
            val (r, n) = recargaResult(out)
            result = r
            tries = n
            AppLog.i(TAG, "RECARGA out=${out.replace('\n', ' ').take(240)}")
        }
        val dt = android.os.SystemClock.elapsedRealtime() - t0
        AppLog.i(TAG, applyLine("bar_recarga", tries, result, dt))
        if (result != "ok") AppLog.i(TAG, recargaFailLine(tries))
        val line = toggleLine(toggleLabel(pref), value, flagsSegment(tempOn, battOn, extOn), via, "bar_recarga", result)
        AppLog.i(TAG, line)
        return line
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
