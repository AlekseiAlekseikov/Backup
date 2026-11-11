package com.example.voiceapp3.tools

/**
 * JIT по референсу CentralEXAuto 3.3.9 (1.0.61, iter35) — платформо-независимое ядро.
 *
 * Сверено по DEX/smali APK `CentralEXAuto_v3.3.9.apk` (md5 `c3f1664125953b9851bf4483b265135f`
 * из ZIP `8a65c6c29749c64d4e335c57490a347a`; baksmali 2.5.2 + jadx 1.4.7):
 *
 *  • **клик** `w0/j9.onClick` (`pswitch_2b`, вызов из `w0/b9` `S0`): `WifiManager.isWifiEnabled()`
 *    (весь блок в `catchall_48 ⇒ false`), `NetworkInterface.getByName("ap0")` →
 *    `isUp() && getInetAddresses().hasMoreElements()` (`catchall_64 ⇒ false`), затем ОДИН
 *    `Editor.commit()`: `jit_pedido`, `perf_auto_otimizar`, `jit_restaurar="wifi=w,ap=a"`;
 *    результат `commit()` не проверяется; `pool.execute(new la(...))`.
 *  • **скрипт** `w0/la.run` (`pswitch_10e`), один литерал: `svc wifi disable` → ≤20×1 с до
 *    `ip rule show | grep -c 'oif wlan0'` = 0 → `umount -l` точек `/data/app/` из `/proc/mounts`
 *    → `setprop dalvik.vm.usejit true|false` → `setprop ctl.restart zygote` (последняя).
 *  • **продолжение** `CentralWatchdogService.onCreate`: `getString("jit_restaurar", null)` →
 *    `Pattern("^wifi=([01]),ap=([01])$").matcher(s).matches()` → `remove(...).apply()` ДО
 *    выполнения → `postDelayed(new e2(...), 15000)`.
 *  • **возврат сети** `w0/e2.run` (case 1): `if (ap) U("pós-JIT", false)` в try/catch (исключение
 *    обрывает весь возврат: «JIT: restauro falhou»), затем `if (wifi && !isWifiEnabled())
 *    setWifiEnabled(true)`.
 *  • **`U(tag, boolean)`** — `garantirApAberto`, разобран по DEX целиком (см. [JitOp.openAp]).
 *  • **сверка** `w0/bb` + `w0/r7`: `H1(8000,"getprop dalvik.vm.usejit")` → `trim()=="true"`;
 *    `Boolean` по `/proc/self/maps`; `ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE)`
 *    (jadx `w0/r7.java`: `z5 == z4 && (!z5 || !Intrinsics.areEqual(bool, Boolean.FALSE))`),
 *    `toggle.setActive(getprop)`, `remove("jit_pedido").apply()`, предупреждение при `!ok && pedido`.
 *
 * Чего в референсе НЕТ и чего здесь поэтому нет: `persist.*` JIT, `usejitprofiles`, watcher,
 * файл отмены, `sleep 8`, boot-backfill, собственные фазы/`HOLD`/`BUSY`, санация `ip rule`.
 *
 * Заявленные отличия (не «копия») — только там, где данные недоступны или где неизвестное нельзя
 * выдавать за факт; каждое перечислено в отчёте iter35 §5 и помечено в коде `ОТЛИЧИЕ`.
 */
object JitRef {

    // ─────────────────────────── ключи и свойства референса ───────────────────────────

    /** `jit_pedido`: намерение операции; снимается **удалением** после сверки (`w0/r7`). */
    internal const val KEY_PEDIDO = "jit_pedido"

    /** `perf_auto_otimizar`: тот же target, пишется тем же `commit()`. */
    internal const val KEY_AUTO = "perf_auto_otimizar"

    /** `jit_restaurar`: `wifi=<0|1>,ap=<0|1>`, снимается в `onCreate` ДО выполнения `e2`. */
    internal const val KEY_RESTORE = "jit_restaurar"

    internal const val PROP_USEJIT = "dalvik.vm.usejit"

    // ─────────────────────────── константы референса ───────────────────────────

    /** `while [ $_n -lt 20 ]` — окно слива правил `oif wlan0`. */
    internal const val DRAIN_LOOP_MAX = 20

    /** `H1(0x9c40, …)` — таймаут транспорта вокруг скрипта. */
    internal const val CHAIN_TIMEOUT_MS = 40_000

    /** `postDelayed(new e2(...), 0x3a98)` — 15 000 мс между снятием снимка и возвратом сети. */
    internal const val RESTORE_DELAY_MS = 15_000L

    /** `H1(0x1f40, "getprop dalvik.vm.usejit")` — 8000 мс. */
    internal const val GETPROP_TIMEOUT_MS = 8_000

    /** `CountDownLatch.await(0x19, SECONDS)` — ожидание чужого открытия AP. */
    internal const val AP_LATCH_WAIT_S = 25L

    /** `const/16 v10, 0x4e20` — ожидание `v()` после `d()`: 20 000 мс. */
    internal const val AP_POLL_TOTAL_MS = 20_000L

    /** `Thread.sleep(0x3e8)` — шаг опроса `v()`. */
    internal const val AP_POLL_STEP_MS = 1_000L

    /** `if-ge p2, 0x3` — не более 3 попыток открыть AP. */
    internal const val AP_ATTEMPTS = 3

    /** `Thread.sleep(0x7d0)` между попытками после сигнала FAILED. */
    internal const val AP_RETRY_PAUSE_MS = 2_000L

    /**
     * `w0/u2`: broadcast `android.net.wifi.WIFI_AP_STATE_CHANGED` (в APK — строковый литерал) и
     * `wifi_state == 14` (`WIFI_AP_STATE_FAILED`) ⇒ `u1 = elapsedRealtime()`.
     */
    internal const val AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    internal const val EXTRA_WIFI_AP_STATE = "wifi_state"
    internal const val AP_STATE_FAILED = 14

    /** `Thread.sleep(0x3e8)` в ожидании CarplayMonitor (`O()`). */
    internal const val CARPLAY_WAIT_STEP_MS = 1_000L

    /**
     * Шаг `umount -l` точек `/data/app/` из `w0/la.run` — **исполняется**, как в референсе.
     *
     * Монтирования владельцем разрешены; скрытого выключателя нет (в прежней итерации он был и
     * убран). Обратимость на живом ГУ **не проверена**: устройства в этом окружении нет, а
     * массовый `umount` ради проверки запрещён. Каждая отправка цепочки пишет
     * `mount_step=present`, а в отчёте это отдельный блокер ⛔ с read-only запросом данных.
     */
    internal const val MOUNT_STEP_NOTE = "mount_step=present"

    /** Регулярка референса — полное совпадение строки, без trim (`Matcher.matches()`). */
    private val RESTORE_RE = Regex("^wifi=([01]),ap=([01])$")

    // ─────────────────────────── интерфейсы (адаптеры — в JitNet) ───────────────────────────

    interface JitShell {
        fun exec(command: String, timeoutMs: Int): Pair<Int, String>

        /** `w0/bb`: `/proc/self/maps` текущего процесса; `null` — прочитать нельзя. */
        fun cacheVisible(): Boolean?
    }

    /**
     * Результат записи в префы.
     *
     * Хранилищ в приложении два (CPS и DPS), поэтому «успех» — это `attempted == ok`, а не
     * `ok первого && ok второго`: попытка выполняется в КАЖДОМ доступном хранилище независимо.
     */
    data class WriteResult(val attempted: Int, val ok: Int, val detail: String) {
        val allOk: Boolean get() = attempted > 0 && ok == attempted
        val anyOk: Boolean get() = ok > 0
        val failed: Int get() = attempted - ok

        companion object {
            fun none(why: String) = WriteResult(0, 0, why)
        }
    }

    interface JitStore {
        fun getString(key: String): String?
        fun getBool(key: String): Boolean?
        fun contains(key: String): Boolean

        /** Три ключа референса ОДНИМ `commit()` на каждое хранилище. */
        fun putSnapshot(want: Boolean, net: Net): WriteResult

        /** `remove(key)` — как `r7`/`onCreate` референса (не запись `false`). */
        fun remove(key: String): WriteResult
    }

    interface JitNetState {
        /** `isWifiEnabled()`; `null` — прочитать нельзя. */
        fun wifiOn(): Boolean?

        /** `getWifiState() == WIFI_STATE_ENABLING` — вторая половина `c1()`. */
        fun wifiEnabling(): Boolean?

        /** `U.v()`: `ap0` поднят и имеет адреса. `null` — прочитать нельзя. */
        fun apUp(): Boolean?

        /** `e2`: `setWifiEnabled(true)`. `null` — вызов не удался. */
        fun setWifi(on: Boolean): Boolean?

        /** `U.d()`: рефлексия `startSoftAp((WifiConfiguration) null)`. `null` — вызов не удался. */
        fun startAp(): Boolean?

        /**
         * `U.u1`: `elapsedRealtime` события `WIFI_AP_STATE_FAILED` (broadcast
         * `android.net.wifi.WIFI_AP_STATE_CHANGED`, `wifi_state == 14`); `0` — события не было.
         *
         * Пока AP не открывают, приёмник не нужен: [beginApWatch]/[endApWatch] включают и
         * выключают его вокруг попытки, чтобы не держать регистрацию постоянно.
         */
        fun apFailedAtMs(): Long
        fun beginApWatch() {}
        fun endApWatch() {}

        /**
         * `U.O()` — «CarplayMonitor ещё не готов». Возвращает `true`, если идёт переходное
         * состояние проекции, `false` — если нет, `null` — сигнала в нашем приложении **нет**
         * (ОТЛИЧИЕ: в референсе это `n1/o1/p1` + установленный `com.njda.adapter` +
         * `connadaptor_modo` из их префов; у нас таких источников нет).
         */
        fun carplayBusy(): Boolean? = null
    }

    interface JitWaiter {
        fun sleep(ms: Long)
        fun nowMs(): Long
    }

    /**
     * Отложенный запуск — как `Handler.postDelayed` в референсе.
     *
     * В референсе `onCreate` НЕ спит: он снимает снимок и планирует `e2` через 15 000 мс на
     * handler-е сервиса. Здесь так же: ожидание не занимает поток общего пула.
     */
    interface JitScheduler {
        fun postDelayed(delayMs: Long, task: () -> Unit)
    }

    /** Состояние сети ДО операции (формат референса). */
    data class Net(val wifi: Boolean, val ap: Boolean) {
        fun wire(): String = "wifi=${if (wifi) 1 else 0},ap=${if (ap) 1 else 0}"
    }

    /**
     * Разбор `jit_restaurar` ровно как `onCreate`: строка обязана совпасть ЦЕЛИКОМ и без trim —
     * `Matcher.matches()` на сыром значении из префов.
     */
    fun parseRestore(raw: String?): Net? {
        if (raw == null) return null
        val m = RESTORE_RE.matchEntire(raw) ?: return null
        return Net(wifi = m.groupValues[1] == "1", ap = m.groupValues[2] == "1")
    }

    // ─────────────────────────── скрипт: `w0/la.run` байт в байт ───────────────────────────

    private fun scriptBody(want: Boolean): String {
        val D = "$"
        val drain = "        svc wifi disable\n" +
            "        _n=0\n" +
            "        while [ ${D}_n -lt $DRAIN_LOOP_MAX ]; do\n" +
            "          [ \"${D}(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n" +
            "          sleep 1; _n=${D}((_n + 1))\n" +
            "        done\n"
        val mount = "        for _m in ${D}(grep ' /data/app/' /proc/mounts | awk '{print ${D}2}'); do\n" +
            "          umount -l \"${D}_m\" 2>/dev/null\n" +
            "        done\n"
        val props = "        setprop $PROP_USEJIT ${if (want) "true" else "false"}\n" +
            "        setprop ctl.restart zygote\n    "
        return "\n" + drain + mount + props
    }

    /** Литерал `w0/la.run` целиком, включая шаг монтирований — эталон для сверки. */
    fun referenceScript(want: Boolean): String = scriptBody(want)

    /** Скрипт, который отправляется: тот же литерал, вариантов нет. */
    fun chainScript(want: Boolean): String = scriptBody(want)

    /** Отметка о шаге монтирований для лога: он есть, как в референсе. */
    fun mountStepNote(): String = MOUNT_STEP_NOTE

    // ─────────────────────────── чтение `getprop`: факт, а не догадка ───────────────────────────

    enum class Prop { TRUE, FALSE, UNKNOWN }

    data class PropFact(val value: Prop, val raw: String, val rc: Int, val reason: String) {
        val log: String
            get() = "getprop=${if (raw.isEmpty()) "empty" else raw} rc=$rc " +
                "fact=${value.name.lowercase()} reason=$reason"
    }

    /**
     * `w0/bb`: `H1(8000,"getprop dalvik.vm.usejit")` → `trim() == "true"`; любое другое значение
     * (включая пустой вывод при недоступном транспорте) референс трактует как `false`, `rc` не
     * смотрит.
     *
     * ОТЛИЧИЕ (разрешено мандатом): если прочитать НЕЛЬЗЯ (`rc≠0`, `ERR_…`, пусто, многострочный
     * вывод), значение помечается [Prop.UNKNOWN] — «неизвестно», а не «выключено». Иначе при
     * недоступном канале `want=false` давал бы ложный успех. Команды цепочки не меняются.
     */
    fun parseProp(rc: Int, out: String): PropFact {
        val raw = out.trim()
        if (rc != 0) return PropFact(Prop.UNKNOWN, raw.take(80), rc, "rc_nonzero")
        if (raw.contains("ERR_")) return PropFact(Prop.UNKNOWN, raw.take(80), rc, "transport_error")
        if (raw.contains('\n')) return PropFact(Prop.UNKNOWN, raw.take(80), rc, "multiline")
        return when (raw) {
            "true" -> PropFact(Prop.TRUE, raw, rc, "ok")
            "false" -> PropFact(Prop.FALSE, raw, rc, "ok")
            "" -> PropFact(Prop.UNKNOWN, raw, rc, "empty")
            else -> PropFact(Prop.UNKNOWN, raw.take(80), rc, "unexpected_value")
        }
    }

    /**
     * Итог формулы `w0/r7`. [CACHE_MISSING] — «JIT запрошен и `getprop=true`, но исполняемой
     * карты кэша нет»: в референсе это `ok=false` и предупреждение, поэтому это **не** успех.
     */
    enum class Verify { OK, NOT_APPLIED, CACHE_MISSING, UNKNOWN }

    /**
     * `w0/r7` (jadx `w0/r7.java`): `ok = (pedido == getprop) && (!pedido || maps != FALSE)`.
     *
     * Разложение по ветвям:
     *  • `pedido=false, getprop=false` → ok (независимо от `maps`, в том числе `maps=false`);
     *  • `pedido=true,  getprop=true`  → ok только если `maps != false` (TRUE или `null`);
     *  • несовпадение `pedido`/`getprop` → not ok;
     *  • `getprop` не прочитан → [Verify.UNKNOWN] (ОТЛИЧИЕ: референс считает «не true» = false).
     */
    fun verify(pedido: Boolean, prop: PropFact, cache: Boolean?): Verify = when (prop.value) {
        Prop.UNKNOWN -> Verify.UNKNOWN
        Prop.TRUE -> when {
            !pedido -> Verify.NOT_APPLIED
            cache == false -> Verify.CACHE_MISSING
            else -> Verify.OK
        }
        Prop.FALSE -> if (pedido) Verify.NOT_APPLIED else Verify.OK
    }

    fun verifyLabel(v: Verify): String = when (v) {
        Verify.OK -> "ok"
        Verify.NOT_APPLIED -> "not_applied"
        Verify.CACHE_MISSING -> "cache_missing"
        Verify.UNKNOWN -> "unknown"
    }

    /** Состояние для тумблера: `on`/`off` по факту `getprop`, `unknown` — если прочитать нельзя. */
    fun confirmedState(fact: PropFact?): String = when (fact?.value) {
        Prop.TRUE -> "on"
        Prop.FALSE -> "off"
        null, Prop.UNKNOWN -> "unknown"
    }

    /**
     * Положение тумблера JIT: `on`/`off` — по подтверждённому факту (как `toggle.setActive(getprop)`
     * в `w0/r7`), и только при `unknown` — по preference (намерение водителя), с явной подписью.
     */
    fun switchPosition(confirmed: String, preference: Boolean): Boolean = when (confirmed) {
        "on" -> true
        "off" -> false
        else -> preference
    }

    /**
     * Что сделать с тумблером после фонового чтения: `true/false` — переставить, `null` — оставить
     * как есть (неизвестное состояние не выдаётся за факт).
     */
    fun switchUpdate(current: Boolean, confirmed: String): Boolean? = when (confirmed) {
        "on" -> if (current) null else true
        "off" -> if (current) false else null
        else -> null
    }

    /** CSV `c1()`: `isWifiEnabled() || getWifiState()==ENABLING`; исключение ⇒ `true`. */
    fun clientWifiBusy(wifiOn: Boolean?, wifiEnabling: Boolean?): Boolean = when {
        wifiOn == true -> true
        wifiOn == false -> wifiEnabling ?: true
        else -> true
    }

    /** Итог запуска скрипта — без выдачи таймаута за успех. */
    enum class ChainOutcome { REPLY_RECEIVED, CHANNEL_LOST }

    fun chainOutcome(exitCode: Int, output: String): ChainOutcome =
        if (exitCode == 0 && !output.contains("ERR_")) ChainOutcome.REPLY_RECEIVED
        else ChainOutcome.CHANNEL_LOST

    fun chainOutcomeLabel(o: ChainOutcome): String = when (o) {
        ChainOutcome.REPLY_RECEIVED -> "reply_received"
        ChainOutcome.CHANNEL_LOST -> "channel_lost"
    }
}

/**
 * Шаги цепочки референса — по одному методу на метод референса.
 *
 * Своего состояния операции нет: снимок — это три ключа референса; нет фаз, метки загрузки,
 * `BUSY`, `HOLD`, сверки «наша ли это операция». Есть только то, что есть в референсе:
 * `jit_pedido` (намерение), `jit_restaurar` (что вернуть), общий `CountDownLatch` открытия AP и
 * флаг «Wi-Fi включили мы сами» (`V` в `U`).
 */
object JitOp {

    /**
     * `U.V` — «клиентский Wi-Fi включён нашим auto-enable» (`y1`: `setWifiEnabled(true)` вернул
     * true ⇒ `V=true`; `t1`: `!c1()` ⇒ `V=false`). В референсе это поле сервиса; у нас — то же
     * самое по смыслу значение процесса.
     */
    private val AUTO_WIFI_ENABLED = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Сброс процесса-состояния `U` (флаг «Wi-Fi включили мы» и latch). Нужен только тестам:
     * в референсе это поля сервиса, которые живут до пересоздания сервиса.
     */
    @Synchronized
    internal fun resetStateForTest() {
        AUTO_WIFI_ENABLED.set(false)
        apLatch = null
    }

    /** `U.w1` — общий latch одного открытия AP; снимается тем, кто открывал. */
    private val LATCH_LOCK = Any()
    private var apLatch: java.util.concurrent.CountDownLatch? = null

    // ─────────────────────────── 1. клик: `w0/j9.onClick` ───────────────────────────

    data class Click(
        val want: Boolean,
        val net: JitRef.Net,
        val snapshot: JitRef.WriteResult,
        val exitCode: Int,
        val output: String
    ) {
        val outcome: JitRef.ChainOutcome get() = JitRef.chainOutcome(exitCode, output)
        /** Результат `commit()` — только для лога: цепочка от него не зависит (как в `j9`). */
        val saved: Boolean get() = snapshot.allOk
    }

    /**
     * Клик тумблера JIT — дословно ветка `pswitch_2b` из `w0/j9`:
     *
     * ```
     * wifi = isWifiEnabled()            // исключение → false
     * ap   = ap0 в воздухе              // исключение → false
     * edit().putBoolean(jit_pedido, t).putBoolean(perf_auto_otimizar, t)
     *       .putString(jit_restaurar, "wifi=w,ap=a").commit()      // один commit
     * pool.execute(la)                  // скрипт
     * ```
     */
    fun click(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        shell: JitRef.JitShell,
        want: Boolean,
        log: (String) -> Unit = {}
    ): Click {
        // Исключение при чтении в референсе даёт `false` (catchall_48 / catchall_64).
        val wifi = runCatching { netState.wifiOn() }.getOrNull() ?: false
        val ap = runCatching { netState.apUp() }.getOrNull() ?: false
        val net = JitRef.Net(wifi = wifi, ap = ap)
        val snapshot = store.putSnapshot(want, net)
        log(
            "JIT_CLICK want=$want net=${net.wire()} saved=${snapshot.allOk} " +
                "stores=${snapshot.ok}/${snapshot.attempted} detail=«${snapshot.detail}» " +
                "(снимок ДО любых изменений; результат commit не гейтит отправку — как j9)"
        )
        log("dalvik.vm.usejit -> $want; reiniciando o framework")
        log(
            "JIT_CHAIN_SENT ${JitRef.mountStepNote()} timeout_ms=${JitRef.CHAIN_TIMEOUT_MS} " +
                "restart=not_claimed command=w0/la.run (литерал референса)"
        )
        val (rc, out) = shell.exec(JitRef.chainScript(want), JitRef.CHAIN_TIMEOUT_MS)
        val outcome = JitRef.chainOutcome(rc, out)
        log(
            "JIT_CHAIN_RETURN rc=$rc outcome=${JitRef.chainOutcomeLabel(outcome)} " +
                "restart=not_claimed internet=not_checked out=${out.trim().take(200).ifEmpty { "empty" }} " +
                "(ответ транспорта ≠ подтверждение рестарта zygote; состояние покажет сверка)"
        )
        if (outcome != JitRef.ChainOutcome.REPLY_RECEIVED) {
            log(
                "JIT_CHAIN_ERROR rc=$rc reason=channel_lost action=none " +
                    "(команда могла остаться в очереди: это НЕ «не выполнено» и НЕ «выполнено»)"
            )
        }
        return Click(want, net, snapshot, rc, out)
    }

    // ─────────────────────────── 2. продолжение: `onCreate` → `e2` ───────────────────────────

    data class StartOutcome(
        val snapshot: JitRef.Net?,
        val rawSnapshot: String?,
        val removed: JitRef.WriteResult?,
        /** `true`, если `e2` запланирован (а не выполнен: выполнение — в фоне через 15 с). */
        val scheduled: Boolean
    )

    /**
     * Продолжение операции на старте процесса — `CentralWatchdogService.onCreate`:
     * прочитать `jit_restaurar` → `matches()` → `remove()` **до** запуска → `postDelayed(e2, 15000)`.
     *
     * ОТЛИЧИЕ (структурное, поведение то же): в референсе планировщик — handler сервиса; у нас
     * [JitRef.JitScheduler] поверх `BgWorker.postDelayed`. Поток общего пула ожиданием не занят,
     * второго цикла/сторожа нет.
     *
     * Сохранён и честно назван риск референса: снимок снимается ДО 15-секундной паузы, поэтому
     * смерть процесса в эти 15 с отменяет возврат сети навсегда; а запуск приложения после
     * `ctl.restart zygote` ничем не гарантирован (это не `BOOT_COMPLETED`).
     */
    fun onProcessStart(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        waiter: JitRef.JitWaiter,
        scheduler: JitRef.JitScheduler,
        log: (String) -> Unit = {}
    ): StartOutcome {
        val taken: Pair<JitRef.Net, JitRef.WriteResult>?
        val raw: String?
        synchronized(LATCH_LOCK) {
            raw = store.getString(JitRef.KEY_RESTORE)
            val parsed = JitRef.parseRestore(raw)
            if (parsed == null) {
                log(
                    "JIT_START snapshot=${if (raw == null) "none" else "unparsed"} " +
                        "raw=\"${raw?.take(60) ?: ""}\" action=none " +
                        "(регулярка ^wifi=([01]),ap=([01])$ не совпала → e2 не запускается)"
                )
                return StartOutcome(null, raw, null, scheduled = false)
            }
            // `remove("jit_restaurar").apply()` — ДО выполнения, как в onCreate.
            val removed = store.remove(JitRef.KEY_RESTORE)
            val stillThere = store.contains(JitRef.KEY_RESTORE)
            log(
                "JIT_START snapshot=${parsed.wire()} removed=${removed.allOk} " +
                    "stores=${removed.ok}/${removed.attempted} still_present=$stillThere " +
                    "delay_ms=${JitRef.RESTORE_DELAY_MS} action=restore_scheduled " +
                    "(remove → postDelayed(e2, 15000); снимок снят до выполнения: смерть процесса " +
                    "в эти 15 с отменяет возврат — риск референса)"
            )
            taken = parsed to removed
        }
        val net = taken!!.first
        scheduler.postDelayed(JitRef.RESTORE_DELAY_MS) {
            runCatching { restoreNetwork(netState, net, waiter, log) }
                .onFailure { log("JIT_NET_RESTORE exception=${it.javaClass.simpleName} (как e2: возврат прерван)") }
        }
        return StartOutcome(net, raw, taken.second, scheduled = true)
    }

    // ─────────────────────────── 3. возврат сети: `e2` ───────────────────────────

    data class RestoreReport(
        val apRequested: Boolean,
        val apUp: Boolean,
        val apDetail: String,
        val wifiRequested: Boolean,
        val wifiOn: Boolean?,
        val failure: String?
    )

    /**
     * `e2.run` (case 1) дословно:
     *
     * ```java
     * if (ap) { try { U("pós-JIT", false); } catch (Throwable t) { log("JIT: restauro falhou: " + t); return; } }
     * if (wifi && !wm.isWifiEnabled()) wm.setWifiEnabled(true);
     * log("JIT: restaurado após o restart (wifi=…, ap=…); wifi agora=" + c1() + " ap agora=" + v());
     * ```
     *
     * Общий catch вокруг AP-пути обрывает и Wi-Fi-путь — здесь так же ([RestoreReport.failure]).
     */
    fun restoreNetwork(
        netState: JitRef.JitNetState,
        net: JitRef.Net,
        waiter: JitRef.JitWaiter,
        log: (String) -> Unit = {}
    ): RestoreReport {
        var failure: String? = null
        var apUp = !net.ap
        var apDetail = "not_needed"
        if (net.ap) {
            try {
                val r = openAp(netState, waiter, "pós-JIT", log = log)
                apUp = r.up
                apDetail = r.detail
            } catch (t: Throwable) {
                apUp = false
                apDetail = "exception"
                failure = "ap:${t.javaClass.simpleName}"
                log("JIT: restauro falhou: $failure (как в e2: исключение обрывает весь возврат)")
            }
        } else {
            log("JIT_NET_RESTORE ap=0 request=none (AP был выключен до операции)")
        }
        var wifiRequested = false
        var wifiOn: Boolean? = null
        if (failure == null && net.wifi) {
            try {
                if (netState.wifiOn() == true) {
                    log("JIT_NET_RESTORE wifi=1 state=on request=none (уже включён)")
                } else {
                    wifiRequested = true
                    val accepted = netState.setWifi(true)
                    // `y1`: V=true только если setWifiEnabled вернул true.
                    if (accepted == true) AUTO_WIFI_ENABLED.set(true)
                    log(
                        "JIT_NET_RESTORE wifi=1 request=setWifiEnabled(true) " +
                            "returned=${accepted?.toString() ?: "unknown"} auto_wifi=$AUTO_WIFI_ENABLED " +
                            "(референс результат не смотрит; запрос ≠ состояние)"
                    )
                }
                wifiOn = netState.wifiOn()
            } catch (t: Throwable) {
                failure = "wifi:${t.javaClass.simpleName}"
                log("JIT: restauro falhou: $failure (как в e2)")
            }
        } else if (failure == null) {
            log("JIT_NET_RESTORE wifi=0 request=none (Wi-Fi был выключен до операции)")
        }
        return RestoreReport(net.ap, apUp, apDetail, wifiRequested, wifiOn, failure)
    }

    // ─────────────────────────── 4. `U(tag, boolean)` целиком ───────────────────────────

    data class ApResult(val up: Boolean, val detail: String)

    /**
     * `CentralWatchdogService.U(String, boolean)` — «garantirApAberto», по DEX:
     *
     * 1. `v()` (AP уже в воздухе) ⇒ `true` — **до** любых ожиданий;
     * 2. `O()` ⇒ ожидание CarplayMonitor шагом 1 с; после ожидания `v()` ⇒ `true`;
     * 3. `c1()` — клиентский Wi-Fi (`isWifiEnabled() || ENABLING`), исключение ⇒ `true`;
     * 4. `p2 == true` (только служебный путь `p1(…, true)`): `dono == PESSOA` ⇒ `false`;
     *    наш путь (`U("pós-JIT", false)`) эту ветвь не проходит;
     * 5. `c1() && !V` ⇒ `false` («não vou disputar o rádio»); `c1() && V` ⇒ открываем «por cima»;
     * 6. общий `CountDownLatch`: второй запрос ждёт чужой результат `await(25 с)` и возвращает `v()`;
     * 7. своя попытка: `d()` → опрос `v()` до 20 с шагом 1 с; при событии `WIFI_AP_STATE_FAILED`
     *    (`u1 > старт попытки`) — до 3 попыток с паузой 2 с; иначе `false` («entregando ao
     *    ConnAdaptor»);
     * 8. latch снимается и `countDown()` — во всех исходах, включая исключение (там — проброс).
     *
     * ОТЛИЧИЯ (заявлены, см. отчёт):
     *  • `O()`/CarplayMonitor — [JitRef.JitNetState.carplayBusy] = `null` ⇒ ожидание пропускается
     *    (`carplay_wait=skipped reason=signal_unavailable`), а не изображается;
     *  • `dono`/`sombra_projecao` — служебные состояния референса, в нашем пути не участвуют
     *    (наш вызов всегда `p2=false`), поэтому ветвь owner реализована как явный параметр.
     */
    fun openAp(
        netState: JitRef.JitNetState,
        waiter: JitRef.JitWaiter,
        tag: String,
        ownerCheck: Boolean = false,
        ownerIsPerson: (() -> Boolean)? = null,
        log: (String) -> Unit = {}
    ): ApResult {
        log("JIT_AP garantirApAberto($tag) owner_check=$ownerCheck")
        // 1. AP уже в воздухе.
        if (runCatching { netState.apUp() }.getOrNull() == true) {
            log("JIT_AP tag=$tag state=up action=none result=true (ранний v())")
            return ApResult(true, "already_up")
        }
        // 2. Ожидание CarplayMonitor (O()): в референсе — пока проекция в переходе.
        val carplay = runCatching { netState.carplayBusy() }.getOrNull()
        if (carplay == null) {
            log("JIT_AP tag=$tag carplay_wait=skipped reason=signal_unavailable " +
                "(n1/o1/p1 + com.njda.adapter + connadaptor_modo в нашем приложении отсутствуют)")
        } else if (carplay) {
            log("JIT_AP tag=$tag carplay_wait=start step=${JitRef.CARPLAY_WAIT_STEP_MS}ms")
            while (runCatching { netState.carplayBusy() }.getOrNull() == true) {
                waiter.sleep(JitRef.CARPLAY_WAIT_STEP_MS)
            }
            if (runCatching { netState.apUp() }.getOrNull() == true) {
                log("JIT_AP tag=$tag carplay_wait=done state=up result=true")
                return ApResult(true, "up_after_carplay_wait")
            }
            log("JIT_AP tag=$tag carplay_wait=done state=down (как U: «fim da espera — sigo»)")
        }
        // 3–4. Клиентский Wi-Fi и owner.
        // `c1()`: исключение ⇒ `true` (Wi-Fi считается занятым, радио не трогаем).
        val wifiOn = runCatching { netState.wifiOn() }.getOrNull()
        if (wifiOn == false) AUTO_WIFI_ENABLED.set(false) // `t1`: !c1() ⇒ V=false
        val clientBusy = JitRef.clientWifiBusy(
            wifiOn,
            runCatching { netState.wifiEnabling() }.getOrNull()
        )
        if (ownerCheck) {
            val person = ownerIsPerson?.invoke() ?: true
            if (person) {
                log("JIT_AP tag=$tag owner=pessoa action=none result=false " +
                    "(«o AP por antecipação não disputa o rádio»)")
                return ApResult(false, "owner_person")
            }
        }
        val auto = AUTO_WIFI_ENABLED.get()
        if (clientBusy && !auto) {
            log("JIT_AP tag=$tag state=down wifi_client=on auto=false action=none result=false " +
                "(как U: «AP fechado, mas o WiFi cliente está ligado — não vou disputar o rádio»)")
            return ApResult(false, "wifi_client_on")
        }
        if (clientBusy) {
            log("JIT_AP tag=$tag wifi_client=on auto=true action=open " +
                "(как U: «ligado pelo auto-enable, abro o AP por cima»)")
        }
        // 6. Общий latch: второй запрос ждёт чужой результат.
        val mine = java.util.concurrent.CountDownLatch(1)
        val other: java.util.concurrent.CountDownLatch?
        synchronized(LATCH_LOCK) {
            other = apLatch
            if (other == null) apLatch = mine
        }
        if (other != null) {
            log("JIT_AP tag=$tag action=await latch=foreign max=${JitRef.AP_LATCH_WAIT_S}s " +
                "(как U: «AP já sendo aberto por outro pedido — espero o mesmo resultado»)")
            try {
                other.await(JitRef.AP_LATCH_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val up = runCatching { netState.apUp() }.getOrNull() == true
            log("JIT_AP tag=$tag latch=foreign result=$up (как U: «no ar (pelo outro pedido)»/«NÃO subiu»)")
            return ApResult(up, if (up) "up_by_other" else "not_up_by_other")
        }
        var attempt = 1
        try {
            netState.beginApWatch()
            while (attempt <= JitRef.AP_ATTEMPTS) {
                val startedAt = waiter.nowMs()
                log(
                    "JIT_AP tag=$tag attempt=$attempt/${JitRef.AP_ATTEMPTS} state=down — " +
                        "abrindo (как U: «AP fechado — abrindo»)"
                )
                val invoked = netState.startAp()
                log(
                    "JIT_AP tag=$tag request=startSoftAp(null) invoked=${invoked != null} " +
                        "returned=${invoked?.toString() ?: "unknown"} " +
                        "(референс результат invoke не использует: вызов ≠ открытый AP)"
                )
                var waited = 0L
                var failedSignal = false
                var up = false
                while (waited < JitRef.AP_POLL_TOTAL_MS) {
                    if (runCatching { netState.apUp() }.getOrNull() == true) {
                        up = true
                        break
                    }
                    waiter.sleep(JitRef.AP_POLL_STEP_MS)
                    waited += JitRef.AP_POLL_STEP_MS
                    // `u1 > t0`: событие WIFI_AP_STATE_FAILED пришло уже во время этой попытки.
                    if (runCatching { netState.apFailedAtMs() }.getOrDefault(0L) > startedAt) {
                        failedSignal = true
                        break
                    }
                }
                if (up) {
                    log(
                        "JIT_AP tag=$tag state_after=up após=${waited / 1000}s attempt=$attempt " +
                            "(подтверждением считается только чтение ap0)"
                    )
                    return ApResult(true, "up_after_${waited / 1000}s")
                }
                if (failedSignal && attempt < JitRef.AP_ATTEMPTS) {
                    log(
                        "JIT_AP tag=$tag system=FAILED após=${waited / 1000}s action=retry " +
                            "pause=${JitRef.AP_RETRY_PAUSE_MS}ms " +
                            "(как U: «rádio ainda não pronto? espero 2s e tento de novo»)"
                    )
                    waiter.sleep(JitRef.AP_RETRY_PAUSE_MS)
                    attempt++
                    continue
                }
                log(
                    "JIT_AP tag=$tag state_after=down result=false " +
                        (if (failedSignal) "FAILED de novo" else "timeout ${waited / 1000}s") +
                        " — entregando ao ConnAdaptor (в нашем приложении этого пути нет)"
                )
                return ApResult(false, if (failedSignal) "failed_again" else "timeout_${waited / 1000}s")
            }
            return ApResult(false, "attempts_exhausted")
        } finally {
            netState.endApWatch()
            synchronized(LATCH_LOCK) { if (apLatch === mine) apLatch = null }
            mine.countDown()
        }
    }

    // ─────────────────────────── 5. сверка: `bb` + `r7` ───────────────────────────

    data class VerifyState(
        val pedido: Boolean?,
        val prop: JitRef.PropFact,
        val cache: Boolean?,
        val verdict: JitRef.Verify,
        val removed: JitRef.WriteResult?,
        val stillPresent: Boolean
    )

    /**
     * `bb.run` + `r7.run`: `getprop dalvik.vm.usejit` (8000 мс) + `/proc/self/maps`, затем
     * `ok = (pedido == getprop) && (!pedido || maps != FALSE)`, `toggle.setActive(getprop)`,
     * `remove("jit_pedido").apply()` и предупреждение при `!ok && pedido`.
     *
     * ВАЖНО: это НЕ read-only — при подтверждённых `true/false` ключ реально удаляется (как в
     * `r7`), поэтому и лог пишет `write=remove(...)`.
     *
     * ОТЛИЧИЕ (разрешено мандатом): при нечитаемом `getprop` результат [JitRef.Verify.UNKNOWN],
     * `jit_pedido` НЕ удаляется — иначе одна неудачная попытка чтения выбросила бы намерение и
     * следующая сверка не смогла бы ничего сказать.
     */
    fun verifyApplied(
        store: JitRef.JitStore,
        shell: JitRef.JitShell,
        log: (String) -> Unit = {}
    ): VerifyState {
        val present = store.contains(JitRef.KEY_PEDIDO)
        if (!present) {
            val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", JitRef.GETPROP_TIMEOUT_MS)
            val prop = JitRef.parseProp(rc, out)
            val cache = shell.cacheVisible()
            log(
                "JIT_APPLIED ${prop.log} code_cache_executavel=${cache?.toString() ?: "unknown"} " +
                    "pedido=none result=no_request write=none " +
                    "(в префах нет jit_pedido — сверять нечего, ничего не пишем)"
            )
            return VerifyState(null, prop, cache, JitRef.Verify.UNKNOWN, null, false)
        }
        val pedido = store.getBool(JitRef.KEY_PEDIDO) ?: false
        val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", JitRef.GETPROP_TIMEOUT_MS)
        val prop = JitRef.parseProp(rc, out)
        val cache = shell.cacheVisible()
        val verdict = JitRef.verify(pedido, prop, cache)
        log(
            "JIT pedido=$pedido getprop=${prop.raw.ifEmpty { "empty" }} " +
                "code-cache-executavel=${cache?.toString() ?: "unknown"} " +
                "result=${JitRef.verifyLabel(verdict)} restart=not_claimed"
        )
        if (verdict == JitRef.Verify.UNKNOWN) {
            log(
                "JIT_PEDIDO kept reason=property_unreadable ${prop.log} write=none " +
                    "(ОТЛИЧИЕ: референс удаляет jit_pedido всегда; здесь намерение сохраняется, " +
                    "чтобы следующая сверка не потеряла запрос)"
            )
            return VerifyState(pedido, prop, cache, verdict, null, true)
        }
        // `remove("jit_pedido").apply()` — настоящее удаление, не запись false.
        val removed = store.remove(JitRef.KEY_PEDIDO)
        val stillPresent = store.contains(JitRef.KEY_PEDIDO)
        log(
            "JIT_PEDIDO removed=${removed.allOk} stores=${removed.ok}/${removed.attempted} " +
                "still_present=$stillPresent write=remove(${JitRef.KEY_PEDIDO}) " +
                "(как r7: remove().apply() после сверки)"
        )
        if (verdict != JitRef.Verify.OK && pedido) {
            log(
                "JIT_NOT_APPLIED result=${JitRef.verifyLabel(verdict)} pedido=$pedido " +
                    "getprop=${prop.raw.ifEmpty { "empty" }} cache=${cache?.toString() ?: "unknown"} " +
                    "(как r7: предупреждение при !ok && pedido; ничего не дозаписываем)"
            )
        }
        return VerifyState(pedido, prop, cache, verdict, removed, stillPresent)
    }

    fun readProp(shell: JitRef.JitShell): JitRef.PropFact {
        val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", JitRef.GETPROP_TIMEOUT_MS)
        return JitRef.parseProp(rc, out)
    }
}
