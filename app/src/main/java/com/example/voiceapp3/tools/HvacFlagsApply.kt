package com.example.voiceapp3.tools

import android.content.Context

/**
 * iter33k §1-§2 — ЕДИНЫЙ путь тумблеров слотов: флаги → `via` → apply.
 *
 * Что было (1.0.55, дефект из лога владельца): клик «Процент заряда» запускал ПОЛНЫЙ
 * `plugin.enable` (маунт + `kill SystemUI`), а клики «Цифровой климат»/«Наружняя» — только
 * PUSH-флаги. Теперь тумблер НИКОГДА не монтирует плагин и не убивает SystemUI:
 *
 *  1. переключаем свой преф (это состояние тумблера);
 *  2. одна PUSH-команда — три флага `0|1` сразу (формат референса `w0/x7.i()`);
 *  3. `persist.sys.hvacdigital.via` = `bind`, если хоть один тумблер включён, иначе `nenhum`
 *     (всё выключено ⇒ сток);
 *  4. мягкий apply — перезапуск ПРОЦЕССА ПЛАГИНА (`kill $(pidof com.flyme.auto.systemuiplugin)`),
 *     как в старом рабочем логе владельца (`setHvacDigital = prop=… restart=com.flyme.auto.systemuiplugin`).
 *     SystemUI автоматически не убивается никогда — только кнопкой «Применить слоты (SystemUI)».
 *
 * Одна строка лога на клик:
 *   `TOGGLE [hvacBatt] → ON | flags=temp:0 batt:1 ext:0 | via=bind | apply=plugin_restart result=ok | ✓`
 */
internal object HvacFlagsApply {
    private const val TAG = "CENTRAL_HvacFlagsApply"

    /** Процесс нашего плагина (пакет `com.flyme.auto.systemuiplugin`). */
    internal const val PLUGIN_PKG = "com.flyme.auto.systemuiplugin"
    internal const val SYSUI_PKG = "com.android.systemui"

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

    /** Строка мягкого apply. */
    fun applyLine(method: String, flags: String, result: String): String =
        "FLAG_APPLY method=$method flags=$flags result=$result"

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

    /** Скрипт мягкого apply: перезапуск процесса плагина (no-op, если процесса нет). */
    fun pluginRestartScript(): String =
        "P=\$(pidof $PLUGIN_PKG); if [ -n \"\$P\" ]; then kill \$P && echo APPLY_KILLED=\$P; " +
            "else echo APPLY_NOOP=no_process; fi"

    /** Какая строка результата соответствует выводу скрипта apply. */
    fun applyResult(out: String): String = when {
        out.contains("APPLY_KILLED=") -> "ok"
        out.contains("APPLY_NOOP=") -> "noop"
        out.contains("Permission denied") -> "denied"
        out.isBlank() -> "failed"
        else -> "ok"
    }

    /**
     * Один клик тумблера: преф → флаги → `via` → мягкий apply.
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
        // 2) мягкий apply — процесс плагина, не SystemUI
        val (_, applyOut) = try {
            AdbClient.execute(pluginRestartScript(), 8000)
        } catch (t: Throwable) {
            Pair(1, "")
        }
        val result = if (rc == 0) applyResult(applyOut) else "failed"
        AppLog.i(TAG, applyLine("plugin_restart", flagsSegment(tempOn, battOn, extOn), result))
        val line = toggleLine(toggleLabel(pref), value, flagsSegment(tempOn, battOn, extOn), via, "plugin_restart", result)
        AppLog.i(TAG, line)
        return line
    }

    /**
     * Кнопка «Применить слоты (SystemUI)» — единственное место, где убивается SystemUI,
     * и только по явному клику с предупреждением (CarPlay/музыка прервутся на ~10 с).
     */
    fun applySystemUi(): String {
        val (rc, out) = try {
            AdbClient.execute(
                "P=\$(pidof $SYSUI_PKG); if [ -n \"\$P\" ]; then kill \$P && echo APPLY_KILLED=\$P; " +
                    "else echo APPLY_NOOP=no_process; fi", 12000)
        } catch (t: Throwable) {
            Pair(1, "")
        }
        val result = applyResult(out)
        val flags = readFlagsLine()
        AppLog.i(TAG, applyLine("sysui_restart", flags, if (rc == 0) result else "failed"))
        AppLog.i(TAG, "SYSUI_RESTART_BY_OWNER killed=${out.contains("APPLY_KILLED=")} result=$result")
        return result
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
