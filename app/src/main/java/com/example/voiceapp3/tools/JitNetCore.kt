package com.example.voiceapp3.tools

/**
 * JIT по референсу CentralEXAuto 3.3.9 (1.0.61, iter36 «клиентский Wi-Fi») — ядро без Android.
 *
 * Сверено по DEX/smali APK `CentralEXAuto_v3.3.9.apk` (md5 `c3f1664125953b9851bf4483b265135f`
 * из ZIP `8a65c6c29749c64d4e335c57490a347a`; baksmali 2.5.2):
 *
 *  • **клик** `w0/j9.onClick` (`pswitch_2b`): `isWifiEnabled()` (блок в `catchall_48`),
 *    `ap0` (`catchall_64`), один `Editor.commit()`: `jit_pedido`, `perf_auto_otimizar`,
 *    `jit_restaurar="wifi=w,ap=a"`; результат `commit()` не проверяется; `pool.execute(new la)`.
 *  • **скрипт** `w0/la.run` (`pswitch_10e`), один литерал: `svc wifi disable` → ≤20×1 с до
 *    `ip rule show | grep -c 'oif wlan0'` = 0 → `umount -l` точек `/data/app/` → `setprop
 *    dalvik.vm.usejit true|false` → `setprop ctl.restart zygote` (последняя).
 *  • **продолжение** `CentralWatchdogService.onCreate`: `getString("jit_restaurar")` →
 *    `Pattern("^wifi=([01]),ap=([01])$").matcher(s).matches()` → `remove(...).apply()` ДО
 *    выполнения → `postDelayed(new e2(...), 15000)`.
 *  • **возврат сети** `w0/e2.run` (case 1): `if (ap) U("pós-JIT", false)` в try/catch, затем
 *    `if (wifi && !isWifiEnabled()) setWifiEnabled(true)`.
 *  • **кэш JIT** `w0/bb`: читает `/proc/self/maps`, оставляет строки с `jit-cache` /
 *    `dalvik-jit-code-cache` (`w0/sa` case 3) и отдаёт их `w0/j5.d`; `j5.d` разбивает на строки,
 *    выбрасывает пустые, при пустом списке возвращает **`null`**, иначе `any { Regex(
 *    "\\br[-w]x[ps]\\b").containsMatchIn(it) }`. Ошибка чтения файла ⇒ `null` (`catchall_67`).
 *  • **сверка** `w0/r7`: `ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE)`,
 *    `toggle.setActive(getprop)`, `remove("jit_pedido").apply()`, предупреждение при `!ok && pedido`.
 *
 * ЧЕГО В JIT НЕТ (решение владельца, iter36 §2.1): точки доступа ГУ, `openAp`/`U`, попытки,
 * таймеры, `FAILED`/`WIFI_AP_STATE_CHANGED`, `CountDownLatch` открытия AP, `AUTO_WIFI_ENABLED`,
 * owner/`dono`, `CarplayMonitor`/`connadaptor_modo`. Ни одного вызова AP в JIT-цепочке нет, и
 * мёртвого кода «на будущее» тоже нет.
 *
 * ЧЕГО НЕТ И В РЕФЕРЕНСЕ: `persist.*` JIT, `usejitprofiles`, watcher, файл отмены, `sleep 8`,
 * boot-backfill, свои фазы/`HOLD`/`BUSY`, санация `ip rule`.
 *
 * Заявленные отличия (каждое — в отчёте iter36 §5, помечено в коде `ОТЛИЧИЕ`), только там, где
 * данные недоступны или где неизвестное нельзя выдавать за факт:
 *  1. нечитаемый `getprop` = `unknown`, `jit_pedido` при этом НЕ удаляется;
 *  2. неизвестное состояние Wi-Fi **блокирует** разрушительную цепочку (`svc wifi disable`) —
 *     иначе после рестарта фреймворка сеть нечем вернуть;
 *  3. новый минимальный формат снимка `wifi=0|1` (+ приём старого `wifi=…,ap=…`, `ap`
 *     игнорируется) — AP из снимка не нужен;
 *  4. сверка не выполняется, пока наш клик в полёте (иначе она удаляла бы свежее намерение);
 *  5. `maps` разбирается по правам (`\br[-w]x[ps]\b`), а не по имени файла.
 */
object JitRef {

    // ─────────────────────────── ключи и свойства референса ───────────────────────────

    /** `jit_pedido`: намерение операции; снимается **удалением** после сверки (`w0/r7`). */
    internal const val KEY_PEDIDO = "jit_pedido"

    /** `perf_auto_otimizar`: тот же target, пишется тем же `commit()`. */
    internal const val KEY_AUTO = "perf_auto_otimizar"

    /** `jit_restaurar`: снимок клиентского Wi-Fi. Новый формат — `wifi=0|1`. */
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

    /**
     * Шаг `umount -l` точек `/data/app/` из `w0/la.run` — **исполняется**, как в референсе.
     *
     * Монтирования владельцем разрешены; скрытого выключателя нет. Обратимость общего `umount -l`
     * на живой ГУ **не проверена**: данных о mount namespace и источниках точек нет. Каждая
     * отправка цепочки пишет `mount_step=present`, а в отчёте это отдельный блокер ⛔ вместе с
     * read-only командами, которые нужно получить с ГУ.
     */
    internal const val MOUNT_STEP_NOTE = "mount_step=present"

    /** Новый минимальный формат снимка: только клиентский Wi-Fi, без AP. */
    private val SNAPSHOT_RE = Regex("^wifi=([01])$")

    /** Старый формат референса (`j9`): принимается, поле `ap` игнорируется без вызовов AP. */
    private val SNAPSHOT_LEGACY_RE = Regex("^wifi=([01]),ap=([01])$")

    /** `w0/j5` `<clinit>`: `"\br[-w]x[ps]\b"`. */
    private val EXEC_RE = Regex("\\br[-w]x[ps]\\b")

    // ─────────────────────────── интерфейсы (адаптеры — в JitNet) ───────────────────────────

    interface JitShell {
        fun exec(command: String, timeoutMs: Int): Pair<Int, String>

        /**
         * `w0/bb`: содержимое `/proc/self/maps` ТЕКУЩЕГО процесса или `null`, если прочитать
         * нельзя (в референсе — `catchall_67`). Разбор — в [cacheExecutable].
         */
        fun mapsExcerpt(): String?
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
        fun putSnapshot(want: Boolean, snap: WifiSnapshot): WriteResult

        /**
         * Сколько хранилищ РЕАЛЬНО отдают [expected] для [KEY_RESTORE] после записи.
         *
         * Нужно, чтобы не отправлять `svc wifi disable`, когда снимок не подтверждён: без него
         * возвращать Wi-Fi после рестарта фреймворка будет нечего. `commit()==false` при этом НЕ
         * трактуется как «значение точно не записалось» — решение принимается по чтению.
         */
        fun confirmSnapshot(expected: String): Int

        /** `remove(key)` — как `r7`/`onCreate` референса (не запись `false`). */
        fun remove(key: String): WriteResult
    }

    interface JitNetState {
        /** `isWifiEnabled()`; `null` — прочитать нельзя (исключение/нет `WifiManager`). */
        fun wifiOn(): Boolean?

        /** `e2`: `setWifiEnabled(true)`. `null` — вызов не удался. */
        fun setWifi(on: Boolean): Boolean?

        /**
         * Read-only признак подключения: у `wlan0` есть не-loopback адрес.
         * `null` — прочитать нельзя. Это НЕ доказательство наличия интернета.
         */
        fun wifiAddress(): Boolean?
    }

    /** Отложенный запуск — как `Handler.postDelayed` в референсе. */
    interface JitScheduler {
        fun postDelayed(delayMs: Long, task: () -> Unit)
    }

    /** Снимок клиентского Wi-Fi перед операцией. AP в нём нет (решение владельца). */
    data class WifiSnapshot(val wifi: Boolean) {
        fun wire(): String = "wifi=${if (wifi) 1 else 0}"
    }

    /** Разобранный снимок + в каком формате он лежал (для честного лога миграции). */
    data class Restore(val snap: WifiSnapshot, val format: String)

    /**
     * Разбор `jit_restaurar`.
     *
     * • новый формат `wifi=0|1` — [format] = `wifi_only`;
     * • старый формат референса `wifi=0|1,ap=0|1` — [format] = `legacy_ap_ignored`, берётся
     *   только `wifi`, поле `ap` НЕ используется и ни одного вызова AP не делает;
     * • иначе `null` — некорректную запись не выполняем (как `Matcher.matches()` в `onCreate`).
     *   Старые данные при этом не вычищаются: чистка «на всякий случай» запрещена.
     */
    fun parseSnapshot(raw: String?): Restore? {
        if (raw == null) return null
        SNAPSHOT_RE.matchEntire(raw)?.let { m ->
            return Restore(WifiSnapshot(m.groupValues[1] == "1"), "wifi_only")
        }
        SNAPSHOT_LEGACY_RE.matchEntire(raw)?.let { m ->
            return Restore(WifiSnapshot(m.groupValues[1] == "1"), "legacy_ap_ignored")
        }
        return null
    }

    // ─────────────────────────── скрипт: `w0/la.run` ───────────────────────────

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

    // ─────────────────────────── `/proc/self/maps`: права, а не имя ───────────────────────────

    /**
     * `w0/sa` case 3: строка относится к JIT-кэшу, если содержит `jit-cache` или
     * `dalvik-jit-code-cache` (`Lo2/k;->e0` = `contains`, без учёта регистра не проверяется).
     */
    fun isCacheLine(line: String): Boolean =
        line.contains("jit-cache") || line.contains("dalvik-jit-code-cache")

    /**
     * `w0/j5.d(String)` дословно:
     *
     * ```java
     * List<String> lines = text.lines().filter { it.isNotBlank() };
     * if (lines.isEmpty()) return null;                 // 0048–0056
     * return lines.any { EXEC.containsMatchIn(it) };    // 0082–0098, "\br[-w]x[ps]\b"
     * ```
     *
     * Поэтому: `null` — данных нет/не прочитано, `false` — строки есть, но ни одна не исполняема
     * (`rw-p`), `true` — есть исполняемая (`r-xp`, `rwxp`, `r-xs`, `rwxs`).
     *
     * ВАЖНО: это наблюдение карты ОДНОГО процесса — не доказательство работающего JIT системы и
     * не доказательство перезапуска zygote.
     */
    fun cacheExecutable(mapsText: String?): Boolean? {
        if (mapsText == null) return null
        val lines = mapsText.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return lines.any { EXEC_RE.containsMatchIn(it) }
    }

    /**
     * Полный путь `w0/bb` → `w0/sa` → `w0/j5.d`: из вывода `/proc/self/maps` берутся только
     * строки кэша, они склеиваются через `\n` и разбираются [cacheExecutable].
     *
     * Пустой результат фильтра даёт `null` (как пустой список в `j5.d`), а НЕ `false`:
     * подходящих строк может не быть просто потому, что JIT-кэш ещё не отображён.
     */
    fun cacheExecutableFromMaps(mapsText: String?): Boolean? {
        if (mapsText == null) return null
        val excerpt = mapsText.split('\n').filter { it.isNotBlank() && isCacheLine(it) }
        if (excerpt.isEmpty()) return null
        return cacheExecutable(excerpt.joinToString("\n"))
    }

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
     * • `pedido=false, getprop=false` → ok (независимо от `maps`, в том числе `maps=false`);
     * • `pedido=true,  getprop=true`  → ok только если `maps != false` (TRUE или `null`);
     * • несовпадение `pedido`/`getprop` → not ok;
     * • `getprop` не прочитан → [Verify.UNKNOWN] (ОТЛИЧИЕ: референс считает «не true» = false).
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

    /**
     * Подпись подтверждённого не-успеха для СУЩЕСТВУЮЩЕЙ строки панели (не кнопка, не красный
     * текст). `null` — сообщать нечего (успех или неизвестность: неизвестное не выдаём за ошибку).
     */
    fun verifyHint(v: Verify, prop: PropFact?, cache: Boolean?): String? = when (v) {
        Verify.NOT_APPLIED ->
            "JIT не применился: свойство ${prop?.raw?.ifEmpty { "пусто" } ?: "неизвестно"}, запрос другим"
        Verify.CACHE_MISSING ->
            "JIT не подтверждён: свойство true, исполняемого JIT-кэша в карте процесса нет" +
                if (cache == null) " (карта не прочитана)" else ""
        Verify.OK, Verify.UNKNOWN -> null
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
 * Своего состояния операции нет: снимок — это ключи референса; нет фаз, метки загрузки, `BUSY`,
 * `HOLD`, сверки «наша ли это операция». Есть только то, что есть в референсе: `jit_pedido`
 * (намерение), `jit_restaurar` (что вернуть) и два защитных факта процесса — «наш клик сейчас в
 * полёте» (чтобы сверка не съела свежее намерение) и «возврат в этом процессе уже запланирован»
 * (чтобы `onCreate`+`onStartCommand` не запланировали его дважды).
 */
object JitOp {

    /** Сколько кликов сейчас в полёте (обычно 0 или 1). Не механизм: живёт до конца вызова. */
    private val CLICKS_IN_FLIGHT = java.util.concurrent.atomic.AtomicInteger(0)

    /** Возврат уже запланирован в ЭТОМ процессе (процесс умрёт от `ctl.restart zygote`). */
    private val RESTORE_SCHEDULED = java.util.concurrent.atomic.AtomicBoolean(false)

    private val RESTORE_LOCK = Any()

    /** Сброс процессного состояния. Нужен только тестам (в бою это поля на жизнь процесса). */
    @Synchronized
    internal fun resetStateForTest() {
        CLICKS_IN_FLIGHT.set(0)
        RESTORE_SCHEDULED.set(false)
    }

    // ─────────────────────────── 1. клик: `w0/j9.onClick` ───────────────────────────

    data class Click(
        val want: Boolean,
        val snap: JitRef.WifiSnapshot?,
        val snapshot: JitRef.WriteResult,
        /** Сколько хранилищ реально отдают снимок после записи. */
        val confirmed: Int,
        val exitCode: Int,
        val output: String,
        /** `null` — скрипт отправлен; иначе причина, по которой НЕ отправлен. */
        val blocked: String? = null
    ) {
        val sent: Boolean get() = blocked == null
        val outcome: JitRef.ChainOutcome get() = JitRef.chainOutcome(exitCode, output)
        val saved: Boolean get() = snapshot.allOk
    }

    /**
     * Клик тумблера JIT — ветка `pswitch_2b` из `w0/j9` + скрипт `w0/la`.
     *
     * Отличие (заявлено, iter36 §2.3): референс при исключении чтения Wi-Fi считает `false` и всё
     * равно выключает Wi-Fi скриптом. Здесь **неизвестный** снимок блокирует отправку: если
     * состояние прочитать нельзя, после `ctl.restart zygote` возвращать будет нечего, и ГУ осталась
     * бы без клиентского Wi-Fi. Возвращается честная причина — её показывает существующая панель.
     *
     * Второе отличие: снимок должен быть ПОДТВЕРЖДЁН чтением (`confirmSnapshot`), иначе скрипт тоже
     * не отправляется. `commit()==false` при этом не объявляется «значение не записалось».
     */
    fun click(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        shell: JitRef.JitShell,
        want: Boolean,
        log: (String) -> Unit = {}
    ): Click {
        CLICKS_IN_FLIGHT.incrementAndGet()
        try {
            // Три состояния, без подмены «неизвестно» на «выключено».
            val wifi = runCatching { netState.wifiOn() }.getOrNull()
            if (wifi == null) {
                log(
                    "JIT_CLICK want=$want blocked=wifi_unknown action=none commands=0 " +
                        "(состояние клиентского Wi-Fi прочитать нельзя: скрипт НЕ отправлен, " +
                        "иначе после перезапуска фреймворка сеть нечем вернуть)"
                )
                return Click(want, null, JitRef.WriteResult.none("wifi_unknown"), 0, -1, "", "wifi_unknown")
            }
            val snap = JitRef.WifiSnapshot(wifi)
            val written = store.putSnapshot(want, snap)
            val confirmed = runCatching { store.confirmSnapshot(snap.wire()) }.getOrDefault(0)
            log(
                "JIT_CLICK want=$want restore=${snap.wire()} written=${written.ok}/${written.attempted} " +
                    "confirmed=$confirmed detail=«${written.detail}» " +
                    "(снимок ДО любых изменений; формат wifi_only — AP не снимается и не поднимается)"
            )
            // Решение принимается ПО ЧТЕНИЮ (`confirmed`), а не по флагу commit: `false` от
            // `commit()` не доказывает, что значения нет, а `true` — что оно переживёт смерть
            // процесса. Минимальное условие: снимок читается хотя бы из одного хранилища.
            if (confirmed == 0) {
                // Снимок не подтверждён ⇒ возврата не будет ⇒ разрушительную цепочку не шлём.
                val c1 = runCatching { store.remove(JitRef.KEY_PEDIDO) }
                val c2 = runCatching { store.remove(JitRef.KEY_AUTO) }
                val c3 = runCatching { store.remove(JitRef.KEY_RESTORE) }
                log(
                    "JIT_CLICK want=$want blocked=snapshot_not_saved written=${written.ok}/" +
                        "${written.attempted} confirmed=$confirmed cleanup=pedido:${c1.getOrNull()?.ok ?: -1}/" +
                        "auto:${c2.getOrNull()?.ok ?: -1}/restore:${c3.getOrNull()?.ok ?: -1} " +
                        "action=none commands=0 (удаление ключей не гарантировано: commit мог не пройти)"
                )
                return Click(
                    want, snap, written, confirmed, -1, "", "snapshot_not_saved"
                )
            }
            log("dalvik.vm.usejit -> $want; reiniciando o framework")
            log(
                "JIT_CHAIN_SENT ${JitRef.mountStepNote()} timeout_ms=${JitRef.CHAIN_TIMEOUT_MS} " +
                    "restart=not_claimed command=w0/la.run (литерал референса)"
            )
            val (rc, out) = shell.exec(JitRef.chainScript(want), JitRef.CHAIN_TIMEOUT_MS)
            val outcome = JitRef.chainOutcome(rc, out)
            log(
                "JIT_CHAIN_RETURN rc=$rc outcome=${JitRef.chainOutcomeLabel(outcome)} " +
                    "restart=not_claimed internet=not_checked wifi_before=${snap.wire()} " +
                    "out=${out.trim().take(200).ifEmpty { "empty" }} " +
                    "(ответ транспорта ≠ подтверждение рестарта zygote; состояние покажет сверка)"
            )
            if (outcome != JitRef.ChainOutcome.REPLY_RECEIVED) {
                log(
                    "JIT_CHAIN_ERROR rc=$rc reason=channel_lost action=none " +
                        "(команда могла остаться в очереди: это НЕ «не выполнено» и НЕ «выполнено»)"
                )
            }
            return Click(want, snap, written, confirmed, rc, out, null)
        } finally {
            CLICKS_IN_FLIGHT.decrementAndGet()
        }
    }

    // ─────────────────────────── 2. продолжение: `onCreate` → `e2` ───────────────────────────

    data class StartOutcome(
        val snapshot: JitRef.WifiSnapshot?,
        val rawSnapshot: String?,
        val format: String?,
        val removed: JitRef.WriteResult?,
        /** `true`, если возврат Wi-Fi запланирован (а не выполнен: выполнение — в фоне через 15 с). */
        val scheduled: Boolean,
        val reason: String
    )

    /**
     * Продолжение операции на старте процесса — `CentralWatchdogService.onCreate`:
     * прочитать `jit_restaurar` → разобрать → `remove()` **до** запуска → `postDelayed(e2, 15000)`.
     *
     * ОТЛИЧИЕ (структурное, поведение то же): планировщик — [JitRef.JitScheduler] поверх
     * `BgWorker.postDelayed`, а не handler сервиса. Поток пула ожиданием не занят, второго
     * сторожа нет.
     *
     * Сохранён и честно назван риск референса: снимок снимается ДО 15-секундной паузы, поэтому
     * смерть процесса в эти 15 с отменяет возврат; запуск приложения после `ctl.restart zygote`
     * ничем не гарантирован (это не `BOOT_COMPLETED`, и `START_STICKY` этого не обещает).
     *
     * Защита от повтора минимальная и естественная: `onCreate` и `onStartCommand` вызывают этот
     * метод оба, поэтому внутри процесса возврат планируется ОДИН раз ([RESTORE_SCHEDULED]).
     * Никакого периодического механизма, `HOLD` или сверки «наша ли операция» нет.
     */
    fun onProcessStart(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        scheduler: JitRef.JitScheduler,
        log: (String) -> Unit = {}
    ): StartOutcome {
        synchronized(RESTORE_LOCK) {
            val raw = runCatching { store.getString(JitRef.KEY_RESTORE) }.getOrNull()
            val restore = JitRef.parseSnapshot(raw)
            if (restore == null) {
                log(
                    "JIT_START snapshot=${if (raw == null) "none" else "unparsed"} " +
                        "raw=\"${raw?.take(60) ?: ""}\" format=none action=none " +
                        "(ни wifi=0|1, ни старый wifi=0|1,ap=0|1 → e2 не запускается)"
                )
                return StartOutcome(null, raw, null, null, false, "no_snapshot")
            }
            if (RESTORE_SCHEDULED.getAndSet(true)) {
                log(
                    "JIT_START snapshot=${restore.snap.wire()} format=${restore.format} " +
                        "duplicate=true action=none (в этом процессе возврат уже запланирован: " +
                        "onCreate и onStartCommand не запускают e2 дважды)"
                )
                return StartOutcome(restore.snap, raw, restore.format, null, false, "duplicate")
            }
            // `remove("jit_restaurar").apply()` — ДО выполнения, как в onCreate.
            val removed = runCatching { store.remove(JitRef.KEY_RESTORE) }
                .getOrDefault(JitRef.WriteResult.none("exception"))
            val stillThere = runCatching { store.contains(JitRef.KEY_RESTORE) }.getOrDefault(false)
            log(
                "JIT_START snapshot=${restore.snap.wire()} format=${restore.format} " +
                    "removed=${removed.allOk} stores=${removed.ok}/${removed.attempted} " +
                    "still_present=$stillThere delay_ms=${JitRef.RESTORE_DELAY_MS} " +
                    "action=restore_scheduled internet=not_checked " +
                    "(remove → postDelayed(e2, 15000); снимок снят до выполнения — смерть процесса в " +
                    "эти 15 с отменяет возврат, это риск референса)"
            )
            if (stillThere) {
                log(
                    "JIT_START removal_unconfirmed=true action=restore_scheduled_once " +
                        "(снимок всё ещё читается: повторного действия в этом процессе не будет; " +
                        "после смерти процесса старт может вернуть Wi-Fi ещё раз — setWifiEnabled " +
                        "идемпотентен, но это не гарантия)"
                )
            }
            scheduler.postDelayed(JitRef.RESTORE_DELAY_MS) {
                runCatching { restoreWifi(netState, restore.snap, log) }
                    .onFailure { log("JIT_NET_RESTORE exception=${it.javaClass.simpleName} (как e2: возврат прерван)") }
                // Повторная попытка снять снимок — дешёвая и только после выполнения.
                runCatching { store.remove(JitRef.KEY_RESTORE) }
            }
            return StartOutcome(restore.snap, raw, restore.format, removed, true, "scheduled")
        }
    }

    // ─────────────────────────── 3. возврат клиентского Wi-Fi: `e2` ───────────────────────────

    data class WifiRestoreReport(
        val snapshot: JitRef.WifiSnapshot,
        val requested: Boolean,
        val returned: Boolean?,
        val stateAfter: Boolean?,
        val address: Boolean?,
        val failure: String?
    ) {
        val log: String
            get() = "JIT_NET_RESTORE snapshot=${snapshot.wire()} request=" +
                (if (requested) "setWifiEnabled(true)" else "none") +
                " returned=${returned?.toString() ?: "not_called"} " +
                "state_after=${stateAfter?.toString() ?: "unknown"} " +
                "address=${address?.toString() ?: "unknown"} internet=not_checked" +
                (failure?.let { " failure=$it" } ?: "")
    }

    /**
     * `e2.run` (case 1) — только клиентская часть: `if (wifi && !isWifiEnabled())
     * setWifiEnabled(true)`.
     *
     * Отличия (заявлены): (1) AP-ветвь `U("pós-JIT")` не переносится по решению владельца, поэтому
     * и «общего catch, обрывающего Wi-Fi» больше нет; (2) возвращённое `true` НЕ выдаётся за
     * наличие интернета — рядом пишутся read-only признаки (`state_after`, `address`) и
     * `internet=not_checked`.
     */
    fun restoreWifi(
        netState: JitRef.JitNetState,
        snap: JitRef.WifiSnapshot,
        log: (String) -> Unit = {}
    ): WifiRestoreReport {
        if (!snap.wifi) {
            val addr = runCatching { netState.wifiAddress() }.getOrNull()
            val r = WifiRestoreReport(snap, false, null, null, addr, null)
            log(r.log + " (Wi-Fi до операции был ВЫКЛЮЧЕН: не включаем — как e2)")
            return r
        }
        val stateBefore = runCatching { netState.wifiOn() }.getOrElse { t ->
            val r = WifiRestoreReport(snap, false, null, null, null, "read:${t.javaClass.simpleName}")
            log(r.log + " (как e2: чтение состояния сорвалось — запрос не отправлен)")
            return r
        }
        if (stateBefore == null) {
            val r = WifiRestoreReport(snap, false, null, null, null, "wifi_manager_absent")
            log(r.log + " (WifiManager недоступен: запрос не отправлен)")
            return r
        }
        if (stateBefore) {
            val addr = runCatching { netState.wifiAddress() }.getOrNull()
            val r = WifiRestoreReport(snap, false, null, true, addr, null)
            log(r.log + " (Wi-Fi уже включён: запрос не нужен)")
            return r
        }
        var failure: String? = null
        var returned: Boolean? = null
        try {
            returned = netState.setWifi(true)
            if (returned == null) failure = "set_wifi_returned_null"
        } catch (t: Throwable) {
            failure = "set_wifi:${t.javaClass.simpleName}"
        }
        val after = runCatching { netState.wifiOn() }.getOrNull()
        val addr = runCatching { netState.wifiAddress() }.getOrNull()
        val r = WifiRestoreReport(snap, true, returned, after, addr, failure)
        log(
            r.log + " (" +
                when {
                    failure != null -> "запрос не удался — сеть НЕ подтверждена"
                    after == true -> "Wi-Fi включён по факту чтения; интернет не проверялся"
                    else -> "запрос принят, но включение по факту НЕ подтверждено"
                } + ")"
        )
        return r
    }

    // ─────────────────────────── 4. сверка: `bb` + `r7` ───────────────────────────

    data class VerifyState(
        val pedido: Boolean?,
        val prop: JitRef.PropFact?,
        val cache: Boolean?,
        val verdict: JitRef.Verify,
        val removed: JitRef.WriteResult?,
        val stillPresent: Boolean,
        /** Не-`null` — сверка не выполнялась, и вот почему (например `click_in_flight`). */
        val skipped: String? = null
    )

    /**
     * `bb.run` + `r7.run`: `getprop dalvik.vm.usejit` (8000 мс) + `/proc/self/maps`, затем
     * `ok = (pedido == getprop) && (!pedido || maps != FALSE)`, `toggle.setActive(getprop)`,
     * `remove("jit_pedido").apply()` и предупреждение при `!ok && pedido`.
     *
     * ВАЖНО: это НЕ read-only — при подтверждённых `true/false` ключ реально удаляется (как `r7`),
     * поэтому лог пишет `write=remove(...)`.
     *
     * ОТЛИЧИЯ (заявлены):
     *  • нечитаемый `getprop` ⇒ [JitRef.Verify.UNKNOWN], `jit_pedido` НЕ удаляется;
     *  • если наш клик сейчас в полёте, сверка **не выполняется**: `j9` пишет `jit_pedido` ДО
     *    отправки скрипта, и параллельная сверка по старому `getprop` удалила бы свежее намерение,
     *    а пользователь увидел бы ложное «не применилось»;
     *  • перед удалением значение перечитывается: если ключ изменился (другой клик), удаление не
     *    выполняется (`race=pedido_changed`) — старый результат не стирает новый запрос.
     */
    fun verifyApplied(
        store: JitRef.JitStore,
        shell: JitRef.JitShell,
        log: (String) -> Unit = {}
    ): VerifyState {
        if (CLICKS_IN_FLIGHT.get() > 0) {
            log(
                "JIT_APPLIED skipped reason=click_in_flight action=none write=none commands=0 " +
                    "(клик пишет снимок до отправки скрипта: сверка сейчас удалила бы новое намерение)"
            )
            return VerifyState(null, null, null, JitRef.Verify.UNKNOWN, null, false, "click_in_flight")
        }
        val present = store.contains(JitRef.KEY_PEDIDO)
        if (!present) {
            val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", JitRef.GETPROP_TIMEOUT_MS)
            val prop = JitRef.parseProp(rc, out)
            val cache = runCatching { JitRef.cacheExecutableFromMaps(shell.mapsExcerpt()) }.getOrNull()
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
        val cache = runCatching { JitRef.cacheExecutableFromMaps(shell.mapsExcerpt()) }.getOrNull()
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
        // Старый результат не должен стирать новый запрос: перечитываем перед удалением.
        val pedidoNow = runCatching { store.getBool(JitRef.KEY_PEDIDO) }.getOrNull()
        if (pedidoNow != pedido) {
            log(
                "JIT_APPLIED race=pedido_changed read=$pedido now=$pedidoNow action=none write=none " +
                    "(пока шла сверка, запрос записали заново — чужой/устаревший результат его не снимает)"
            )
            return VerifyState(pedido, prop, cache, JitRef.Verify.UNKNOWN, null, true, "pedido_changed")
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
