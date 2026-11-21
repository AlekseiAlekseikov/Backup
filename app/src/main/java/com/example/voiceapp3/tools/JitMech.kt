package com.example.voiceapp3.tools

import java.util.concurrent.atomic.AtomicBoolean

/**
 * iter65 — механизм JIT по референсу CentralEXAuto 3.3.9 (разбор: `docs/agent/JIT_COMPARISON_1.0.65.md`).
 *
 * Три части, как в референсе, и ничего сверх них:
 *  1. [click] — `w0.j9.onClick` + `w0/la.run`: три ключа в префы, строка в лог, ОДНА команда-литерал;
 *  2. [watchdogStart] / [restoreNow] — `CentralWatchdogService.onCreate` + `w0/e2`: возврат Wi-Fi
 *     через 15 с после рестарта по одноразовому `jit_restaurar`;
 *  3. [verify] — `w0/bb` + `w0/r7`: `getprop` + `/proc/self/maps`, сверка с `jit_pedido`, ключ снимается.
 *
 * Без владения/claim, без отметок операции, без фаз, без PID-проверок, без persist-свойств.
 * Всё платформенное — за интерфейсами, чтобы та же логика проверялась тестами на JVM.
 */
object JitMech {

    const val KEY_PEDIDO = "jit_pedido"
    const val KEY_AUTO = "perf_auto_otimizar"
    const val KEY_RESTAURAR = "jit_restaurar"

    /** `mainActivity.H1(40000, …)` в `w0/la`. */
    const val CHAIN_TIMEOUT_MS = 40_000

    /** `postDelayed(new e2(…), 15000L)` в `CentralWatchdogService.onCreate`. */
    const val RESTORE_DELAY_MS = 15_000L

    /** `mainActivity2.H1(8000, "getprop dalvik.vm.usejit")` в `w0/bb`. */
    const val GETPROP_TIMEOUT_MS = 8_000

    const val GETPROP_CMD = "getprop dalvik.vm.usejit"

    interface Prefs {
        fun putBool(key: String, value: Boolean): Boolean
        fun getBool(key: String): Boolean?
        fun putString(key: String, value: String): Boolean
        fun getString(key: String): String?
        fun remove(key: String): Boolean
        fun contains(key: String): Boolean
    }

    interface Wifi {
        /** `WifiManager.isWifiEnabled`; исключение у вызывающего — это `null`. */
        fun isEnabled(): Boolean?
        fun setEnabled(on: Boolean): Boolean?
    }

    interface Shell {
        fun exec(command: String, timeoutMs: Int): Pair<Int, String>
    }

    interface Scheduler {
        fun postDelayed(delayMs: Long, task: () -> Unit)
    }

    // ─────────────────────────── 1. цепочка: литерал `w0/la` ───────────────────────────

    /**
     * Текст `w0/la.run` case 0 после `o2.m.V` (= `trimIndent`). Исходный литерал в DEX — 392 символа
     * (с отступами); после `trimIndent` уходит 298 символов для `true`, 299 для `false`.
     * Меняется ТОЛЬКО значение `usejit`. Ни одной команды сверх литерала.
     */
    fun chainScript(want: Boolean): String =
        "svc wifi disable\n" +
            "_n=0\n" +
            "while [ \$_n -lt 20 ]; do\n" +
            "  [ \"\$(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n" +
            "  sleep 1; _n=\$((_n + 1))\n" +
            "done\n" +
            "for _m in \$(grep ' /data/app/' /proc/mounts | awk '{print \$2}'); do\n" +
            "  umount -l \"\$_m\" 2>/dev/null\n" +
            "done\n" +
            "setprop dalvik.vm.usejit " + (if (want) "true" else "false") + "\n" +
            "setprop ctl.restart zygote"

    // ─────────────────────────── снимок сети ───────────────────────────

    data class Restaurar(val wifi: Boolean)

    /** Значение `jit_restaurar`: строго `wifi=0` / `wifi=1` (AP в JIT не переносится — решение владельца). */
    fun restaurarValue(wifiOn: Boolean): String = "wifi=" + (if (wifiOn) "1" else "0")

    /**
     * Разбор `jit_restaurar`. Референс: `^wifi=([01]),ap=([01])$`; у нас пишется `wifi=X`, а формат
     * референса принимается с игнорированием `ap`. Всё прочее — `null` (ключ не трогается, как в DEX).
     */
    fun parseRestaurar(raw: String?): Restaurar? {
        val m = RESTAURAR_RX.matchEntire(raw ?: return null) ?: return null
        return Restaurar(wifi = m.groupValues[1] == "1")
    }

    private val RESTAURAR_RX = Regex("^wifi=([01])(?:,ap=[01])?$")

    // ─────────────────────────── 1. клик ───────────────────────────

    /** Клик в полёте в ЭТОМ процессе. Пока он идёт, сверка не трогает `jit_pedido` (см. [verify]). */
    private val busy = AtomicBoolean(false)

    fun busy(): Boolean = busy.get()

    internal fun resetForTest() = busy.set(false)

    data class Click(
        val sent: Boolean,
        val refused: String?,
        val restaurar: String?,
        val prefsOk: Boolean,
        val rc: Int?,
        val out: String?
    )

    /**
     * `w0.j9.onClick` (case 0) + `w0/la.run` (case 0). Только из фонового потока: `exec` ждёт до 40 с,
     * а при рестарте zygote процесс просто погибает внутри вызова — так и задумано референсом.
     */
    fun click(prefs: Prefs, wifi: Wifi, shell: Shell, want: Boolean, log: (String) -> Unit): Click {
        if (!busy.compareAndSet(false, true)) {
            log("JIT_CLICK want=$want refused=busy (предыдущий клик ещё выполняется в этом процессе)")
            return Click(false, "busy", null, false, null, null)
        }
        try {
            // j9: `isWifiEnabled()`, исключение ⇒ 0.
            val wifiNow = wifi.isEnabled() ?: false
            val restaurar = restaurarValue(wifiNow)
            val okPedido = prefs.putBool(KEY_PEDIDO, want)
            val okAuto = prefs.putBool(KEY_AUTO, want)
            val okRest = prefs.putString(KEY_RESTAURAR, restaurar)
            val prefsOk = okPedido && okAuto && okRest
            log("JIT_CLICK want=$want $KEY_PEDIDO=$want $KEY_AUTO=$want $KEY_RESTAURAR=$restaurar " +
                "prefs=${if (prefsOk) "ok" else "partial pedido=$okPedido auto=$okAuto restaurar=$okRest"}")
            log("dalvik.vm.usejit -> $want; reiniciando o framework")
            val (rc, out) = runCatching { shell.exec(chainScript(want), CHAIN_TIMEOUT_MS) }
                .getOrElse { t -> -1 to "ERR_${t.javaClass.simpleName}" }
            // Сюда доходим, только если процесс пережил цепочку: zygote не перезапущен
            // (или транспорт ответил ошибкой). Это не «успех» и не «провал» — факт покажет сверка.
            log("JIT_CHAIN_RETURNED want=$want rc=$rc out=«${out.trim().takeLast(200)}» " +
                "(процесс жив после цепочки: рестарт zygote не произошёл или ещё не дошёл)")
            return Click(true, null, restaurar, prefsOk, rc, out)
        } finally {
            busy.set(false)
        }
    }

    // ─────────────────────────── 2. возврат сети ───────────────────────────

    /**
     * `CentralWatchdogService.onCreate`: прочитать `jit_restaurar`, при совпадении формата удалить
     * ключ (one-shot) и через 15 с, если сервис ещё жив, вернуть Wi-Fi ([restoreNow]).
     * @return разобранный снимок или `null`, если ключа нет / формат не тот.
     */
    fun watchdogStart(
        prefs: Prefs,
        wifi: Wifi,
        scheduler: Scheduler,
        alive: () -> Boolean,
        log: (String) -> Unit
    ): Restaurar? {
        val raw = prefs.getString(KEY_RESTAURAR) ?: return null
        val r = parseRestaurar(raw)
        if (r == null) {
            log("JIT_WATCHDOG $KEY_RESTAURAR=«${raw.take(40)}» format=invalid action=none")
            return null
        }
        val removed = prefs.remove(KEY_RESTAURAR)
        log("JIT_WATCHDOG $KEY_RESTAURAR=${restaurarValue(r.wifi)} removed=$removed " +
            "restore_in_ms=$RESTORE_DELAY_MS")
        scheduler.postDelayed(RESTORE_DELAY_MS) {
            if (alive()) restoreNow(r, wifi, log)
            else log("JIT_WATCHDOG restore=skipped reason=service_destroyed (как `T2=false` в референсе)")
        }
        return r
    }

    /** `w0/e2` case 1 (без AP — решение владельца). */
    fun restoreNow(r: Restaurar, wifi: Wifi, log: (String) -> Unit) {
        if (r.wifi && wifi.isEnabled() == false) runCatching { wifi.setEnabled(true) }
        log("JIT: restaurado após o restart (wifi=${r.wifi}); wifi agora=${wifi.isEnabled()}")
    }

    // ─────────────────────────── 3. сверка ───────────────────────────

    /** Фильтр `w0/sa` case 3 + `w0/j5.d`: `true`/`false`, или `null`, если строк кэша нет. */
    fun cacheExecutable(maps: String?): Boolean? {
        val lines = (maps ?: return null).lineSequence()
            .filter { it.contains("jit-cache") || it.contains("dalvik-jit-code-cache") }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return null
        return lines.any { EXEC_RX.containsMatchIn(it) }
    }

    private val EXEC_RX = Regex("\\br[-w]x[ps]\\b")

    /** `w0/r7`: `ok = (pedido == getprop) && (!pedido || maps != FALSE)`. */
    fun verdictOk(pedido: Boolean, getprop: Boolean, maps: Boolean?): Boolean =
        pedido == getprop && (!pedido || maps != false)

    /** `getprop` прочитан: rc 0 и ровно `true`/`false`. Иначе — `null` (не «false»). */
    fun parseGetprop(rc: Int, out: String): Boolean? = when {
        rc != 0 -> null
        out.trim() == "true" -> true
        out.trim() == "false" -> false
        else -> null
    }

    data class Verify(
        val getprop: Boolean?,
        val maps: Boolean?,
        val pedido: Boolean?,
        val ok: Boolean?,
        /** `master_perf_jit_not_applied`: только `pedido == true` и не ok (как `if (ok || !z5) return`). */
        val notApplied: Boolean,
        val pedidoRemoved: Boolean,
        val skipped: String?
    )

    /**
     * Текст `master_perf_jit_not_applied` для существующей строки состояния (тоста/новых элементов
     * UI нет). Только при [Verify.notApplied]; иначе `null`.
     */
    fun notAppliedHint(v: Verify): String? = when {
        !v.notApplied -> null
        v.getprop != v.pedido -> "JIT не применился: dalvik.vm.usejit=${v.getprop}, запрошено ${v.pedido}"
        else -> "JIT не применился: свойство true, исполняемого JIT-кэша в карте процесса нет"
    }

    /**
     * `w0/bb` + `w0/r7`. Тумблер рисуется по [Verify.getprop]. `jit_pedido` снимается после сверки.
     * Отличия от референса (обоснованы в сравнении): сверка не идёт, пока в этом процессе клик в
     * полёте, и откладывается, если `getprop` не прочитан.
     */
    fun verify(prefs: Prefs, shell: Shell, maps: () -> String?, log: (String) -> Unit): Verify {
        val (rc, out) = runCatching { shell.exec(GETPROP_CMD, GETPROP_TIMEOUT_MS) }
            .getOrElse { t -> -1 to "ERR_${t.javaClass.simpleName}" }
        val getprop = parseGetprop(rc, out)
        val cache = runCatching { cacheExecutable(maps()) }.getOrNull()
        if (!prefs.contains(KEY_PEDIDO)) return Verify(getprop, cache, null, null, false, false, "no_pedido")
        if (busy()) {
            log("JIT_VERIFY skipped reason=click_in_flight getprop=$getprop ($KEY_PEDIDO не трогается)")
            return Verify(getprop, cache, null, null, false, false, "click_in_flight")
        }
        if (getprop == null) {
            log("JIT_VERIFY skipped reason=getprop_unread rc=$rc out=«${out.trim().take(80)}» " +
                "($KEY_PEDIDO остаётся до чтения)")
            return Verify(null, cache, null, null, false, false, "getprop_unread")
        }
        val pedido = prefs.getBool(KEY_PEDIDO) ?: false
        val ok = verdictOk(pedido, getprop, cache)
        log("JIT pedido=$pedido getprop=$getprop code-cache-executavel=$cache")
        val removed = prefs.remove(KEY_PEDIDO)
        val notApplied = !ok && pedido
        log("JIT_VERIFY ok=$ok not_applied=$notApplied pedido_removed=$removed")
        return Verify(getprop, cache, pedido, ok, notApplied, removed, null)
    }
}
