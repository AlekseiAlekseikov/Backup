package com.example.voiceapp3.tools

/**
 * JIT по референсу CentralEXAuto 3.3.9 (1.0.61, iter37) — ядро без Android.
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
 *    выбрасывает пустые, при пустом списке возвращает `null`, иначе `any { Regex("\\br[-w]x[ps]\\b")
 *    .containsMatchIn(it) }`. Ошибка чтения ⇒ `null`.
 *  • **сверка** `w0/r7`: `ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE)`,
 *    `toggle.setActive(getprop)`, `remove("jit_pedido").apply()`, предупреждение при `!ok && pedido`.
 *
 * ЧЕГО В JIT НЕТ (решения владельца): точки доступа ГУ, `openAp`/`U`, `ap0`, `startSoftAp`,
 * `FAILED`/`WIFI_AP_STATE_CHANGED`, `CountDownLatch` открытия AP, `AUTO_WIFI_ENABLED`, owner/`dono`,
 * `CarplayMonitor`/`connadaptor_modo`. Ни одного вызова AP в JIT-цепочке нет.
 *
 * ЧЕГО НЕТ И В РЕФЕРЕНСЕ: `persist.*` JIT, `usejitprofiles`, watcher, файл отмены, `sleep 8`,
 * boot-backfill, свои фазы/`HOLD`/`BUSY`, санация `ip rule`. Продолжение возврата Wi-Fi от
 * пользовательского клика — **не** авто-восстановление JIT.
 *
 * Заявленные отличия от референса (каждое — в отчёте iter37 §5, помечено в коде `ОТЛИЧИЕ`):
 *  1. нечитаемый `getprop` = `unknown`, `jit_pedido` при этом НЕ удаляется;
 *  2. неизвестное состояние Wi-Fi **блокирует** разрушительную цепочку (`svc wifi disable`);
 *  3. снимок считается сохранённым только при `commit==true` **и** совпадающем readback всех трёх
 *     ключей хотя бы в одном хранилище, и только если будущее чтение (CPS→DPS) вернёт именно его;
 *  4. повторный клик во время выполняющегося отклоняется (`busy`) с честным пояснением;
 *  5. сверка не выполняется во время клика и не снимает намерение, если поколение запроса
 *     изменилось или исход предыдущей команды неопределён (`chain_undefined`);
 *  6. `maps` разбирается по правам, а не по имени файла; три значения `true/false/null`.
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

    /**
     * Отметка КОНКРЕТНОЙ операции — наш собственный ключ, в референсе его нет.
     *
     * Зачем он нужен (iter38 §1.2/§1.4/§4): `jit_restaurar` — просто строка `wifi=0|1`, одинаковая
     * у разных операций и у данных 1.0.60. По ней НЕЛЬЗЯ отличить «наш опознанный снимок, который
     * надо продолжить» от «неизвестного остатка прошлой версии», и нельзя понять, чья это запись,
     * когда старая отложенная задача просыпается уже после нового клика. Поэтому рядом с снимком
     * пишется `jit_op_mark=op=<id>;ts=<ms>;want=<0|1>;state=<sending|replied|undefined>`.
     *
     * Ключ пишется тем же `commit()`, что и три ключа референса, и удаляется вместе со снимком —
     * ПОСЛЕ фактического возврата сети. Отсутствие ключа = операция не опознана (legacy 1.0.60 или
     * запись до этого обновления) ⇒ продолжение не выполняется, данные сохраняются.
     */
    internal const val KEY_MARK = "jit_restore_marker"

    /** Все три ключа одной операции — проверяются вместе (согласованность записи). */
    internal val SNAPSHOT_KEYS = listOf(KEY_PEDIDO, KEY_AUTO, KEY_RESTORE)

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
     * отправка цепочки пишет `mount_step=present`; одноразовый read-only сбор этих данных —
     * [JitMountDiag] (в отчёте iter37 §5 — статус ⛔ до получения данных).
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

        /** `w0/bb`: содержимое `/proc/self/maps` процесса или `null`, если прочитать нельзя. */
        fun mapsExcerpt(): String?
    }

    /** Результат `remove` по хранилищам. */
    data class WriteResult(val attempted: Int, val ok: Int, val detail: String) {
        val allOk: Boolean get() = attempted > 0 && ok == attempted
        val anyOk: Boolean get() = ok > 0
        val failed: Int get() = attempted - ok

        companion object {
            fun none(why: String) = WriteResult(0, 0, why)
        }
    }

    /**
     * Отчёт по ОДНОМУ хранилищу после записи снимка.
     *
     * @param commit   `ok` / `false` / `exception:<T>`
     * @param readback все три ключа совпали с записанными сразу после `commit`
     */
    data class StoreReport(
        val name: String,
        val commit: String,
        val readback: Boolean,
        val error: String? = null
    ) {
        val committed: Boolean get() = commit == "ok"
        /** Запись надёжна по нашим данным: commit прошёл И значения читаются. */
        val durable: Boolean get() = committed && readback
        val brief: String
            get() = "$name:${commit.let { if (it == "ok") "commit_ok" else it }}" +
                (if (committed) (if (readback) ",readback_ok" else ",readback_mismatch") else "")
    }

    /**
     * Итог записи снимка по всем доступным хранилищам.
     *
     * `confirmed` — сколько хранилищ дали `commit==true` И совпадающий readback ВСЕХ трёх ключей.
     * Успешный `commit` — необходимое подтверждение записи, но не гарантия от аппаратного сбоя.
     */
    data class SnapshotWrite(
        val want: Boolean,
        val snap: WifiSnapshot,
        val stores: List<StoreReport>
    ) {
        val attempted: Int get() = stores.size
        val committed: Int get() = stores.count { it.committed }
        val confirmed: Int get() = stores.count { it.durable }
        val detail: String get() = stores.joinToString(",") { it.brief }.ifEmpty { "no_store" }
        /** Хранилища, куда наша запись прошла и подтвердилась чтением. */
        fun durableNames(): Set<String> = stores.filter { it.durable }.map { it.name }.toSet()
        /**
         * Порядок отчётов = порядок будущего чтения. Если ПЕРВОЕ хранилище не подтвердило запись,
         * то после перезапуска продолжение прочитает из него прежнее значение, хотя во втором
         * лежит наше: снимок неоднозначен, отправлять разрушительную цепочку нельзя.
         */
        val firstReadable: Boolean get() = stores.firstOrNull()?.durable == true
    }

    /**
     * Как продолжение БУДЕТ читать снимок: значение по порядку чтения (CPS→DPS) и список ключей,
     * где хранилища расходятся. Расхождение делает будущий снимок неоднозначным — отправлять
     * `svc wifi disable` в таком состоянии нельзя.
     */
    data class SnapshotRead(val value: String?, val conflicts: List<String>)

    interface JitStore {
        fun getString(key: String): String?
        fun getBool(key: String): Boolean?
        fun contains(key: String): Boolean

        /**
         * Прежнее состояние четырёх ключей операции (три референса + [KEY_MARK]) по каждому
         * доступному хранилищу, в порядке будущего чтения. Снимается ДО записи.
         */
        fun readSnapshotState(): List<StorePrev>

        /**
         * Три ключа референса + [KEY_MARK] ОДНИМ `commit()` на каждое хранилище, с отчётом по
         * каждому (commit + readback всех ключей).
         */
        fun putSnapshot(want: Boolean, snap: WifiSnapshot, mark: OpMark): SnapshotWrite

        /** Обновить только [KEY_MARK] (состояние `sending` → `replied`/`undefined`). */
        fun updateMark(mark: OpMark): WriteResult

        /**
         * Вернуть прежние значения ключей операции (best-effort, по одному `commit()` на
         * хранилище). Атомарности между CPS и DPS НЕ обещаем: каждый `commit()` независим.
         */
        fun restorePrevious(prev: List<StorePrev>): WriteResult

        /** Значение, которое увидит продолжение, + конфликты между хранилищами. */
        fun readRestore(): SnapshotRead

        /**
         * `remove(key)` — как `r7`/`onCreate` референса (не запись `false`).
         *
         * @param onlyStores если задано — трогать только эти хранилища (по имени). Нужно, чтобы не
         *   стирать прежние данные там, где наша запись вообще не прошла.
         */
        fun remove(key: String, onlyStores: Set<String>? = null): WriteResult
    }

    interface JitNetState {
        /** `isWifiEnabled()`; `null` — прочитать нельзя (исключение/нет `WifiManager`). */
        fun wifiOn(): Boolean?

        /** `e2`: `setWifiEnabled(true)`. `null` — результат вызова неизвестен. */
        fun setWifi(on: Boolean): Boolean?

        /** Read-only признак подключения: у `wlan0` есть не-loopback адрес. НЕ интернет. */
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
     * Отметка конкретной операции ([KEY_MARK]).
     *
     * @param op    идентификатор операции (уникален в пределах устройства: время + счётчик + случайное)
     * @param ts    время записи снимка (мс) — только для лога/пересылки владельцу, НЕ TTL
     * @param state `sending` — снимок записан, ответа транспорта ещё нет; `replied` — транспорт
     *              ответил целиком (это НЕ доказательство рестарта zygote); `undefined` — ответа не
     *              было (команда могла остаться в очереди)
     */
    data class OpMark(val op: String, val ts: Long, val want: Boolean, val state: String) {
        fun wire(): String =
            "op=$op;ts=$ts;want=${if (want) 1 else 0};state=$state"

        companion object {
            const val SENDING = "sending"
            const val REPLIED = "replied"
            const val UNDEFINED = "undefined"

            private val RE = Regex("^op=([A-Za-z0-9_.-]{1,48});ts=(\\d{1,19});want=([01]);state=(sending|replied|undefined)$")

            /** `null` — отметки нет или она не нашего формата (значит операция не опознана). */
            fun parse(raw: String?): OpMark? {
                if (raw == null) return null
                val m = RE.matchEntire(raw) ?: return null
                return OpMark(
                    op = m.groupValues[1],
                    ts = m.groupValues[2].toLongOrNull() ?: 0L,
                    want = m.groupValues[3] == "1",
                    state = m.groupValues[4]
                )
            }

            /** Отметка для тестов/адаптеров, где идентификатор задаётся явно. */
            fun of(op: String, want: Boolean, state: String = SENDING, ts: Long = 0L) =
                OpMark(op, ts, want, state)
        }
    }

    /**
     * Прежнее состояние ОДНОГО ключа в ОДНОМ хранилище (снимается ДО записи снимка).
     *
     * Нужно, чтобы неудачная запись не оставляла за собой «полузаписанные» значения: `commit=false`
     * тоже может изменить память, а удаление новой записи не возвращает прежнюю.
     */
    data class KeyPrev(val key: String, val present: Boolean, val bool: Boolean?, val str: String?)

    /** Прежнее состояние ключей операции в одном хранилище. */
    data class StorePrev(val name: String, val keys: List<KeyPrev>) {
        fun has(key: String): Boolean = keys.any { it.key == key && it.present }
        fun brief(): String = keys.joinToString("") { k ->
            val v = when {
                !k.present -> "none"
                k.str != null -> "\"${k.str.take(28)}\""
                k.bool != null -> k.bool.toString()
                else -> "value"
            }
            "${k.key}=$v;"
        }
    }

    /**
     * Разбор `jit_restaurar`.
     *
     * • новый формат `wifi=0|1` — `format=wifi_only`;
     * • старый формат референса `wifi=0|1,ap=0|1` — `format=legacy_ap_ignored`, берётся только
     *   `wifi`, поле `ap` НЕ используется и ни одного вызова AP не делает;
     * • иначе `null` — некорректную запись не выполняем. Старые данные не вычищаются.
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

    /** `w0/sa` case 3: строка относится к JIT-кэшу по имени файла. */
    fun isCacheLine(line: String): Boolean =
        line.contains("jit-cache") || line.contains("dalvik-jit-code-cache")

    /**
     * `w0/j5.d(String)` дословно: пустой список строк ⇒ `null`; иначе
     * `any { Regex("\\br[-w]x[ps]\\b").containsMatchIn(it) }`.
     *
     * `null` — данных нет/не прочитано, `false` — строки есть, но ни одна не исполняема (`rw-p`),
     * `true` — есть исполняемая (`r-xp`, `rwxp`, `r-xs`, `rwxs`). Это наблюдение карты ОДНОГО
     * процесса — не доказательство работающего JIT системы и не доказательство рестарта zygote.
     */
    fun cacheExecutable(mapsText: String?): Boolean? {
        if (mapsText == null) return null
        val lines = mapsText.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return lines.any { EXEC_RE.containsMatchIn(it) }
    }

    /** `w0/bb` → `w0/sa` → `w0/j5.d`: из карты берутся только строки кэша. */
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
     * вывод), значение помечается [Prop.UNKNOWN] — «неизвестно», а не «выключено».
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
     * `w0/r7` (jadx): `ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE)`.
     *
     * • `pedido=false, getprop=false` → ok (независимо от `maps`);
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

    /** Подпись подтверждённого не-успеха для существующей строки панели (`null` — сообщать нечего). */
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

    /** Положение тумблера: `on`/`off` — по подтверждённому факту, при `unknown` — preference. */
    fun switchPosition(confirmed: String, preference: Boolean): Boolean = when (confirmed) {
        "on" -> true
        "off" -> false
        else -> preference
    }

    /** Что сделать с тумблером после фонового чтения: `true/false` — переставить, `null` — нет. */
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

    /** Итог `setWifiEnabled(true)` — четыре разных состояния, а не «принято/не принято». */
    enum class SetWifiOutcome { ACCEPTED, REFUSED, UNKNOWN, EXCEPTION }

    fun setWifiOutcome(returned: Boolean?): SetWifiOutcome = when (returned) {
        true -> SetWifiOutcome.ACCEPTED
        false -> SetWifiOutcome.REFUSED
        null -> SetWifiOutcome.UNKNOWN
    }

    fun setWifiLabel(o: SetWifiOutcome): String = when (o) {
        SetWifiOutcome.ACCEPTED -> "accepted"
        SetWifiOutcome.REFUSED -> "refused"
        SetWifiOutcome.UNKNOWN -> "unknown"
        SetWifiOutcome.EXCEPTION -> "exception"
    }
}

/**
 * Шаги цепочки референса — по одному методу на метод референса.
 *
 * Своего состояния операции нет: нет фаз, метки загрузки, `BUSY`-файлов, `HOLD`, сверки «наша ли
 * операция». Есть только то, что необходимо, чтобы не потерять данные пользователя:
 *  • `jit_pedido` / `perf_auto_otimizar` / `jit_restaurar` — ключи референса;
 *  • один слот на выполняющийся клик ([CLICK_SLOT]) — два конфликтующих клика не должны
 *    перезаписывать исходный снимок Wi-Fi состоянием, снятым уже после `svc wifi disable`;
 *  • поколение намерения ([PEDIDO_GEN]) — сверка снимает только ТОТ запрос, который читала;
 *  • признак «исход предыдущей команды неопределён» ([CHAIN_UNDEFINED]) — потерянный ответ
 *    транспорта не означает ни «выполнено», ни «не выполнено».
 */
object JitOp {

    private val INTENT_LOCK = Any()

    /** Один выполняющийся клик. `null` — свободно. */
    private val CLICK_SLOT = java.util.concurrent.atomic.AtomicReference<Any?>(null)

    /** Поколение намерения: растёт при каждой записи снимка кликом. */
    private val PEDIDO_GEN = java.util.concurrent.atomic.AtomicInteger(0)

    /** Ответ транспорта потерян: команда могла остаться в очереди — исход неопределён. */
    private val CHAIN_UNDEFINED = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Одна конкретная запланированная задача возврата Wi-Fi (не «весь процесс»).
     * Снимается, когда задача выполнилась или планирование не удалось.
     */
    private var scheduledTask: Any? = null

    /** Счётчик операций в этом процессе (часть идентификатора). */
    private val opSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** Сброс процессного состояния. Нужен только тестам (в бою это поля на жизнь процесса). */
    @Synchronized
    internal fun resetStateForTest() {
        CLICK_SLOT.set(null)
        PEDIDO_GEN.set(0)
        CHAIN_UNDEFINED.set(false)
        scheduledTask = null
    }

    internal fun generation(): Int = PEDIDO_GEN.get()

    /**
     * Идентификатор операции: время + счётчик процесса + случайная часть. Нужен только для того,
     * чтобы отличить «свою» запись от чужой/новой (не для TTL и не для проверок по часам).
     */
    private fun newOpId(): String =
        java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" +
            java.lang.Integer.toString(opSeq.incrementAndGet(), 36) + "-" +
            java.lang.Long.toString((Math.random() * 0x7FFFFFFFL).toLong() and 0xFFFFFFFL, 36)

    private fun briefPrev(prev: List<JitRef.StorePrev>): String =
        prev.joinToString(",") { "${it.name}:${it.brief()}" }.ifEmpty { "none" }

    internal fun clickBusy(): Boolean = CLICK_SLOT.get() != null

    internal fun chainUndefined(): Boolean = CHAIN_UNDEFINED.get()

    // ─────────────────────────── 1. клик: `w0/j9.onClick` ───────────────────────────

    data class Click(
        val want: Boolean,
        val snap: JitRef.WifiSnapshot?,
        val write: JitRef.SnapshotWrite?,
        val read: JitRef.SnapshotRead?,
        val exitCode: Int,
        val output: String,
        /** `null` — скрипт отправлен; иначе причина, по которой НЕ отправлен. */
        val blocked: String? = null,
        /** `true` — до этого клика уже была команда с потерянным ответом (могла остаться в очереди). */
        val previousUndefined: Boolean = false,
        /** Сколько раз этот вызов был отклонён из-за уже выполняющегося клика (для лога). */
        val attempt: Int = 1
    ) {
        val sent: Boolean get() = blocked == null
        val outcome: JitRef.ChainOutcome get() = JitRef.chainOutcome(exitCode, output)
        val confirmed: Int get() = write?.confirmed ?: 0
    }

    /**
     * Клик тумблера JIT — ветка `pswitch_2b` из `w0/j9` + скрипт `w0/la`.
     *
     * Порядок (минимальный и достаточный):
     *  1. занять слот клика; второй параллельный вызов отклоняется (`busy`);
     *  2. прочитать Wi-Fi (три значения; `null` ⇒ [JitRef.Prop]… то есть «неизвестно» ⇒ блок);
     *  3. под [INTENT_LOCK] увеличить поколение и записать три ключа в каждое хранилище;
     *  4. отправить скрипт ТОЛЬКО если `commit` прошёл **и** readback всех трёх ключей совпал хотя
     *     бы в одном хранилище, и будущее чтение (CPS→DPS) вернёт именно наш снимок. Иначе —
     *     честная причина, сеть и свойства не меняются, прежние ключи в чужих хранилищах не
     *     трогаются;
     *  5. при потере ответа транспорта — `chain_undefined` (команда могла остаться в очереди).
     */
    fun click(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        shell: JitRef.JitShell,
        want: Boolean,
        log: (String) -> Unit = {}
    ): Click {
        val token = Any()
        if (!CLICK_SLOT.compareAndSet(null, token)) {
            log(
                "JIT_CLICK want=$want blocked=busy action=none commands=0 " +
                    "(предыдущий клик ещё выполняется: второй цепочки нет, исходный снимок Wi-Fi " +
                    "не перезаписывается состоянием после svc wifi disable)"
            )
            return Click(want, null, null, null, -1, "", "busy")
        }
        val previousUndefined = CHAIN_UNDEFINED.get()
        try {
            // ── iter38 §1.1: исход прошлой команды не установлен ⇒ новой цепочки НЕТ ──
            //
            // Почему именно так: команда, у которой потерян ответ транспорта, может всё ещё лежать
            // в очереди и выполниться ПОЗЖЕ — уже после того, как мы снимем новый снимок Wi-Fi.
            // Тогда `svc wifi disable` из старой команды отработает при снимке «Wi-Fi был
            // включён», и `e2` вернёт сеть, которой никто не выключал, либо наоборот. Никакого
            // доказательства «выполнено/не выполнено» у нас нет: таймаут, новое чтение getprop,
            // смена процесса и boot id ничего не доказывают (см. отчёт iter38 §1.1).
            if (previousUndefined) {
                log(
                    "JIT_CLICK want=$want blocked=chain_undefined action=none commands=0 " +
                        "(у прошлой команды не было ответа транспорта: она могла остаться в очереди " +
                        "и выполниться позже. Пока исход не установлен, вторая цепочка НЕ " +
                        "отправляется и снимок не перезаписывается. Доказательства выполнения или " +
                        "невыполнения получить нечем: таймаут, getprop, boot id и новый процесс " +
                        "таким доказательством не являются. Блокировка живёт до конца процесса; " +
                        "после следующего старта решение принимает опознанный снимок, а не догадка)"
                )
                return Click(want, null, null, null, -1, "", "chain_undefined", previousUndefined)
            }
            // ── iter38 §1.2: отложенный возврат по прошлому снимку ещё не выполнен ──
            if (scheduledTask != null) {
                log(
                    "JIT_CLICK want=$want blocked=restore_pending action=none commands=0 " +
                        "(возврат клиентского Wi-Fi по прошлому снимку уже запланирован и ещё не " +
                        "выполнен: новый клик сейчас перезаписал бы снимок и мог бы поставить " +
                        "вторую цепочку поверх отложенного возврата)"
                )
                return Click(want, null, null, null, -1, "", "restore_pending")
            }
            // Три состояния, без подмены «неизвестно» на «выключено».
            val wifi = runCatching { netState.wifiOn() }.getOrNull()
            if (wifi == null) {
                log(
                    "JIT_CLICK want=$want blocked=wifi_unknown action=none commands=0 " +
                        "(состояние клиентского Wi-Fi прочитать нельзя: скрипт НЕ отправлен, " +
                        "иначе после перезапуска фреймворка сеть нечем вернуть)"
                )
                return Click(want, null, null, null, -1, "", "wifi_unknown", previousUndefined)
            }
            val snap = JitRef.WifiSnapshot(wifi)
            val opId = newOpId()
            val mark = JitRef.OpMark(opId, System.currentTimeMillis(), want, JitRef.OpMark.SENDING)
            val prev: List<JitRef.StorePrev>
            val write: JitRef.SnapshotWrite
            val read: JitRef.SnapshotRead
            synchronized(INTENT_LOCK) {
                // Прежнее состояние — ДО записи: иначе неудачную запись нечем вернуть (§1.3).
                prev = runCatching { store.readSnapshotState() }.getOrDefault(emptyList())
                PEDIDO_GEN.incrementAndGet()
                write = store.putSnapshot(want, snap, mark)
                read = store.readRestore()
            }
            log(
                "JIT_CLICK want=$want op=$opId restore=${snap.wire()} attempted=${write.attempted} " +
                    "committed=${write.committed} confirmed=${write.confirmed} " +
                    "stores=«${write.detail}» read=${read.value ?: "none"} " +
                    "conflicts=${read.conflicts.ifEmpty { listOf("none") }.joinToString(",")} " +
                    "previous=«${briefPrev(prev)}» " +
                    "(снимок ДО любых изменений; формат wifi_only — AP не снимается и не поднимается; " +
                    "прежние значения сняты для честного откката)"
            )
            val blocked = when {
                write.attempted == 0 -> "snapshot_not_saved"
                write.confirmed == 0 -> "snapshot_not_saved"
                !write.firstReadable -> "snapshot_not_saved"
                read.conflicts.isNotEmpty() -> "snapshot_conflict"
                read.value != snap.wire() -> "snapshot_not_readable"
                else -> null
            }
            if (blocked != null) {
                if (write.confirmed > 0 && !write.firstReadable) {
                    log(
                        "JIT_CLICK snapshot_order=first_store_not_durable " +
                            "stores=«${write.detail}» (запись есть не во всех хранилищах: после " +
                            "перезапуска продолжение прочитало бы прежнее значение из первого по " +
                            "порядку чтения — снимок неоднозначен, поэтому не отправляем)"
                    )
                }
                // Восстановление прежних: возвращаем ПРЕЖНИЕ значения всех четырёх ключей там, где это возможно.
                // Отказ восстановления не скрывается; атомарности между CPS и DPS не обещаем.
                val rb = synchronized(INTENT_LOCK) {
                    runCatching { store.restorePrevious(prev) }
                        .getOrElse { t -> JitRef.WriteResult.none("exception:${t.javaClass.simpleName}") }
                }
                val leftPedido = runCatching { store.contains(JitRef.KEY_PEDIDO) }.getOrDefault(false)
                val leftRestore = runCatching { store.getString(JitRef.KEY_RESTORE) }.getOrNull()
                log(
                    "JIT_CLICK want=$want op=$opId blocked=$blocked confirmed=${write.confirmed} " +
                        "committed=${write.committed} read=${read.value ?: "none"} " +
                        "prev_restore=${rb.ok}/${rb.attempted} prev_restore_detail=${rb.detail} " +
                        "left_pedido=$leftPedido left_restore=${leftRestore ?: "none"} " +
                        "action=none commands=0 " +
                        "(сеть и системные свойства НЕ менялись; commit=false сам по себе не " +
                        "доказывает отсутствия значения в памяти, поэтому решение — по readback и " +
                        "по будущему чтению. Прежние значения восстановлены там, где хранилище " +
                        "приняло запись; если восстановление не прошло — это видно здесь и в left_*, " +
                        "и клик остаётся заблокированным)"
                )
                return Click(want, snap, write, read, -1, "", blocked, previousUndefined)
            }
            log("dalvik.vm.usejit -> $want; reiniciando o framework")
            log(
                "JIT_CHAIN_SENT op=$opId ${JitRef.mountStepNote()} timeout_ms=${JitRef.CHAIN_TIMEOUT_MS} " +
                    "restart=not_claimed command=w0/la.run (литерал референса)"
            )
            val (rc, out) = shell.exec(JitRef.chainScript(want), JitRef.CHAIN_TIMEOUT_MS)
            val outcome = JitRef.chainOutcome(rc, out)
            val undefined = outcome != JitRef.ChainOutcome.REPLY_RECEIVED
            CHAIN_UNDEFINED.set(undefined)
            // Состояние операции в отметке: `replied`/`undefined`. Пишется best-effort: если
            // запись не прошла, это честно видно в логе, но решение уже принято по ответу
            // транспорта (он в памяти этого процесса).
            val state = if (undefined) JitRef.OpMark.UNDEFINED else JitRef.OpMark.REPLIED
            val markUpd = runCatching { store.updateMark(mark.copy(state = state)) }
                .getOrElse { t -> JitRef.WriteResult.none("exception:${t.javaClass.simpleName}") }
            log(
                "JIT_CHAIN_RETURN op=$opId rc=$rc outcome=${JitRef.chainOutcomeLabel(outcome)} " +
                    "restart=not_claimed internet=not_checked wifi_before=${snap.wire()} " +
                    "mark_state=$state mark_write=${markUpd.ok}/${markUpd.attempted} " +
                    "out=${out.trim().take(200).ifEmpty { "empty" }} " +
                    "(ответ транспорта ≠ подтверждение рестарта zygote; состояние покажет сверка)"
            )
            if (undefined) {
                log(
                    "JIT_CHAIN_ERROR op=$opId rc=$rc reason=channel_lost action=none " +
                        "(команда могла остаться в очереди: это НЕ «не выполнено» и НЕ «выполнено». " +
                        "Повторный клик в этом процессе будет отклонён (blocked=chain_undefined), " +
                        "пока исход не установлен; удаления намерения сверкой тоже не будет)"
                )
            }
            return Click(want, snap, write, read, rc, out, null, previousUndefined)
        } finally {
            CLICK_SLOT.compareAndSet(token, null)
        }
    }

    // ─────────────────────────── 2. продолжение: `onCreate` → `e2` ───────────────────────────

    data class StartOutcome(
        val snapshot: JitRef.WifiSnapshot?,
        val rawSnapshot: String?,
        val format: String?,
        /**
         * Всегда `null` на старте: снимок БОЛЬШЕ НЕ удаляется до выполнения (iter38 §1.4).
         * Удаление происходит в задаче возврата — и только своей операции (по [JitRef.KEY_MARK]).
         */
        val removed: JitRef.WriteResult?,
        /** `true`, если возврат Wi-Fi запланирован (а не выполнен: выполнение — в фоне через 15 с). */
        val scheduled: Boolean,
        /**
         * `scheduled` · `duplicate` · `no_snapshot` · `legacy_or_unknown` (снимок есть, но
         * конкретная операция не опознана — действия нет, данные сохранены) · `scheduler_failed`.
         */
        val reason: String,
        /** Опознанная отметка операции (если она была). */
        val mark: JitRef.OpMark? = null
    )

    /**
     * Продолжение операции на старте процесса — `CentralWatchdogService.onCreate`.
     *
     * Порядок (минимальное обоснованное отступление от референса, см. отчёт iter38 §1.4):
     *  1. прочитать `jit_restaurar` → разобрать (битое не выполняется);
     *  2. прочитать [JitRef.KEY_MARK]. Нет отметки ⇒ операция НЕ опознана (`legacy_or_unknown`):
     *     ничего не удаляем, ничего не планируем, пишем в лог ⛔ и оставляем данные владельцу;
     *  3. запланировать возврат через 15 с. **Снимок не удаляется** — иначе смерть процесса в эти
     *     15 с (или отказ планировщика) теряла бы продолжение навсегда;
     *  4. снимок и отметка удаляются в самой задаче, ПОСЛЕ фактического возврата, и только если
     *     отметка всё ещё принадлежит этой операции (иначе данные нового клика не трогаются).
     *
     * Дубль предотвращается для ОДНОЙ задачи ([scheduledTask]); после её выполнения новый снимок в
     * том же процессе снова может быть запланирован.
     *
     * Чего это НЕ даёт: гарантии, что приложение запустится после `ctl.restart zygote`
     * (`START_STICKY` этого не обещает). Если оно не запустится, снимок останется на диске и
     * продолжение выполнится при следующем обычном запуске — тогда же, когда владелец увидит панель.
     */
    fun onProcessStart(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        scheduler: JitRef.JitScheduler,
        log: (String) -> Unit = {}
    ): StartOutcome {
        synchronized(INTENT_LOCK) {
            val raw = runCatching { store.getString(JitRef.KEY_RESTORE) }.getOrNull()
            if (scheduledTask != null) {
                val known = JitRef.parseSnapshot(raw)
                log(
                    "JIT_START snapshot=${known?.snap?.wire() ?: if (raw == null) "none" else "unparsed"} " +
                        "duplicate=true action=none (эта же задача уже запланирована: onCreate и " +
                        "onStartCommand не запускают e2 дважды; флаг относится к задаче, а не к процессу)"
                )
                return StartOutcome(known?.snap, raw, known?.format, null, false, "duplicate")
            }
            val restore = JitRef.parseSnapshot(raw)
            if (restore == null) {
                log(
                    "JIT_START snapshot=${if (raw == null) "none" else "unparsed"} " +
                        "raw=\"${raw?.take(60) ?: ""}\" format=none action=none " +
                        "(ни wifi=0|1, ни старый wifi=0|1,ap=0|1 → e2 не запускается)"
                )
                return StartOutcome(null, raw, null, null, false, "no_snapshot")
            }
            val markRaw = runCatching { store.getString(JitRef.KEY_MARK) }.getOrNull()
            val mark = JitRef.OpMark.parse(markRaw)
            if (mark == null) {
                log(
                    "JIT_START snapshot=${restore.snap.wire()} format=${restore.format} " +
                        "identification=none mark=\"${markRaw?.take(60) ?: "none"}\" action=none " +
                        "reason=legacy_or_unknown write=none " +
                        "(ОТКАЗ, а не «сеть не вернулась из-за JIT»: снимок есть, но КОНКРЕТНАЯ " +
                        "операция не опознана — так выглядят данные 1.0.60 и записи до обновления. " +
                        "Ничего не удаляем и не выполняем: продолжить нечем доказать, что это наш " +
                        "подтверждённый снимок. Данные сохранены; решение — за владельцем после " +
                        "разбора логов)"
                )
                return StartOutcome(restore.snap, raw, restore.format, null, false, "legacy_or_unknown")
            }
            val token = Any()
            val prev = scheduledTask
            scheduledTask = token
            val scheduled = try {
                scheduler.postDelayed(JitRef.RESTORE_DELAY_MS) {
                    runScheduledRestore(store, netState, restore.snap, mark.op, token, log)
                }
                true
            } catch (t: Throwable) {
                synchronized(INTENT_LOCK) {
                    if (scheduledTask === token) scheduledTask = null
                }
                log(
                    "JIT_START op=${mark.op} scheduler=failed reason=${t.javaClass.simpleName} " +
                        "action=none removed=none (задача не поставлена; снимок и отметка ОСТАВЛЕНЫ: " +
                        "следующий обычный запуск продолжит эту же опознанную операцию — потери " +
                        "продолжения больше нет)"
                )
                false
            }
            log(
                "JIT_START snapshot=${restore.snap.wire()} format=${restore.format} op=${mark.op} " +
                    "mark_state=${mark.state} mark_ts=${mark.ts} identification=ok removed=none " +
                    "delay_ms=${JitRef.RESTORE_DELAY_MS} scheduled=$scheduled " +
                    "action=${if (scheduled) "restore_scheduled" else "none"} internet=not_checked " +
                    "(снимок НЕ удаляется до фактического возврата: смерть процесса в эти 15 с больше " +
                    "не отменяет продолжение — следующий старт увидит тот же опознанный снимок)"
            )
            if (prev != null) {
                log("JIT_START note=previous_task_replaced (предыдущая задача уже завершилась)")
            }
            if (!scheduled) {
                return StartOutcome(restore.snap, raw, restore.format, null, false,
                    "scheduler_failed", mark)
            }
            return StartOutcome(restore.snap, raw, restore.format, null, true, "scheduled", mark)
        }
    }

    /**
     * Тело отложенной задачи возврата (референс `w0/e2.execute`).
     *
     * Что здесь важно (iter38 §1.2/§1.4):
     *  • пока идёт клик, возврат не выполняется (`click_in_flight`) — старая задача не меняет Wi-Fi
     *    посреди новой цепочки;
     *  • снимок и отметка удаляются ТОЛЬКО если отметка всё ещё принадлежит этой операции;
     *  • при исключении/неудаче возврата снимок остаётся — следующий старт повторит (не «успех»).
     */
    private fun runScheduledRestore(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        snap: JitRef.WifiSnapshot,
        opId: String,
        token: Any,
        log: (String) -> Unit
    ) {
        var report: WifiRestoreReport? = null
        var skipped: String? = null
        try {
            if (CLICK_SLOT.get() != null) {
                skipped = "click_in_flight"
                log(
                    "JIT_NET_RESTORE skipped reason=click_in_flight action=none op=$opId " +
                        "(уже идёт новый клик: старый возврат не меняет Wi-Fi посреди новой цепочки; " +
                        "снимок не тронут — его продолжит следующий обычный запуск)"
                )
                return
            }
            report = runCatching { restoreWifi(netState, snap, log) }.getOrElse { t ->
                log(
                    "JIT_NET_RESTORE exception=${t.javaClass.simpleName} action=none op=$opId " +
                        "(как e2: возврат прерван; снимок оставлен, следующий запуск попробует снова)"
                )
                null
            }
        } finally {
            synchronized(INTENT_LOCK) {
                if (scheduledTask === token) scheduledTask = null
                if (skipped == null && report != null) {
                    cleanupOwnSnapshot(store, opId, log)
                }
            }
        }
    }

    /** Удалить снимок и отметку, если они всё ещё принадлежат операции [opId]. */
    private fun cleanupOwnSnapshot(store: JitRef.JitStore, opId: String, log: (String) -> Unit) {
        val cur = JitRef.OpMark.parse(runCatching { store.getString(JitRef.KEY_MARK) }.getOrNull())
        if (cur == null || cur.op != opId) {
            log(
                "JIT_RESTORE_DONE op=$opId cleanup=skipped reason=snapshot_belongs_to_other_op " +
                    "current=${cur?.op ?: "none"} write=none " +
                    "(данные другой/новой операции не тронуты; если это остаток без отметки — " +
                    "он сохранён для разбора, а не вычищен)"
            )
            return
        }
        val r = runCatching { store.remove(JitRef.KEY_RESTORE) }
            .getOrElse { JitRef.WriteResult.none("exception") }
        val m = runCatching { store.remove(JitRef.KEY_MARK) }
            .getOrElse { JitRef.WriteResult.none("exception") }
        val left = runCatching { store.contains(JitRef.KEY_RESTORE) }.getOrDefault(false)
        log(
            "JIT_RESTORE_DONE op=$opId cleanup=restore:${r.ok}/${r.attempted}" +
                "/mark:${m.ok}/${m.attempted} still_present=$left write=remove " +
                "(снимок снят ПОСЛЕ фактического возврата; при отказе удаления он останется и " +
                "следующий запуск повторит возврат — это видно по still_present)"
        )
    }

    // ─────────────────────────── 3. возврат клиентского Wi-Fi: `e2` ───────────────────────────

    data class WifiRestoreReport(
        val snapshot: JitRef.WifiSnapshot,
        val requested: Boolean,
        val outcome: JitRef.SetWifiOutcome?,
        val stateAfter: Boolean?,
        val address: Boolean?,
        val failure: String?
    ) {
        val log: String
            get() = "JIT_NET_RESTORE snapshot=${snapshot.wire()} request=" +
                (if (requested) "setWifiEnabled(true)" else "none") +
                " returned=${outcome?.let { JitRef.setWifiLabel(it) } ?: "not_called"} " +
                "state_after=${stateAfter?.toString() ?: "unknown"} " +
                "address=${address?.toString() ?: "unknown"} internet=not_checked" +
                (failure?.let { " failure=$it" } ?: "")
    }

    /**
     * `e2.run` (case 1) — только клиентская часть: `if (wifi && !isWifiEnabled())
     * setWifiEnabled(true)`.
     *
     * Отличия (заявлены): AP-ветвь `U("pós-JIT")` не переносится (решение владельца); возвращённое
     * `true` НЕ выдаётся за наличие интернета (`internet=not_checked`, рядом — read-only `address`);
     * результат вызова различается на `accepted`/`refused`/`unknown`/`exception`.
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
        var outcome: JitRef.SetWifiOutcome? = null
        var failure: String? = null
        try {
            outcome = JitRef.setWifiOutcome(netState.setWifi(true))
        } catch (t: Throwable) {
            outcome = JitRef.SetWifiOutcome.EXCEPTION
            failure = "set_wifi:${t.javaClass.simpleName}"
        }
        val after = runCatching { netState.wifiOn() }.getOrNull()
        val addr = runCatching { netState.wifiAddress() }.getOrNull()
        val r = WifiRestoreReport(snap, true, outcome, after, addr, failure)
        log(
            r.log + " (" +
                when (outcome) {
                    JitRef.SetWifiOutcome.ACCEPTED ->
                        "API принял запрос (true); включение подтверждается только чтением, " +
                            "интернет не проверялся"
                    JitRef.SetWifiOutcome.REFUSED ->
                        "API ОТКАЗАЛ (false) — запрос НЕ принят, сеть этим вызовом не включена"
                    JitRef.SetWifiOutcome.UNKNOWN ->
                        "результат вызова неизвестен (null) — запрос мог не уйти"
                    JitRef.SetWifiOutcome.EXCEPTION ->
                        "исключение при вызове — запрос не принят"
                    null -> "запрос не отправлялся"
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
        /** Не-`null` — сверка не выполнялась (или не снимала намерение), и вот почему. */
        val skipped: String? = null
    )

    /**
     * `bb.run` + `r7.run`: `getprop dalvik.vm.usejit` (8000 мс) + `/proc/self/maps`, затем
     * `ok = (pedido == getprop) && (!pedido || maps != FALSE)`, `toggle.setActive(getprop)`,
     * `remove("jit_pedido").apply()` и предупреждение при `!ok && pedido`.
     *
     * ВАЖНО: это НЕ read-only — при подтверждённых `true/false` ключ реально удаляется (как `r7`).
     *
     * ОТЛИЧИЯ (заявлены):
     *  • нечитаемый `getprop` ⇒ [JitRef.Verify.UNKNOWN], `jit_pedido` НЕ удаляется;
     *  • пока наш клик в полёте — сверка не выполняется (`click_in_flight`);
     *  • удаление и сверка связаны ОДНИМ поколением запроса под одним локом: если между чтением и
     *    удалением записали новое намерение (даже такое же по значению), удаления не будет
     *    (`race=pedido_changed`). Окна «проверил → удалил» больше нет;
     *  • если исход предыдущей команды неопределён (`chain_undefined`), намерение не снимается.
     */
    fun verifyApplied(
        store: JitRef.JitStore,
        shell: JitRef.JitShell,
        log: (String) -> Unit = {}
    ): VerifyState {
        val gen0: Int
        val present: Boolean
        val pedido: Boolean?
        synchronized(INTENT_LOCK) {
            if (CLICK_SLOT.get() != null) {
                log(
                    "JIT_APPLIED skipped reason=click_in_flight action=none write=none commands=0 " +
                        "(клик пишет снимок до отправки скрипта: сверка сейчас удалила бы новое " +
                        "намерение)"
                )
                return VerifyState(null, null, null, JitRef.Verify.UNKNOWN, null, false, "click_in_flight")
            }
            gen0 = PEDIDO_GEN.get()
            present = store.contains(JitRef.KEY_PEDIDO)
            pedido = if (present) store.getBool(JitRef.KEY_PEDIDO) ?: false else null
        }
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
        val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", JitRef.GETPROP_TIMEOUT_MS)
        val prop = JitRef.parseProp(rc, out)
        val cache = runCatching { JitRef.cacheExecutableFromMaps(shell.mapsExcerpt()) }.getOrNull()
        val verdict = JitRef.verify(pedido ?: false, prop, cache)
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
        if (CHAIN_UNDEFINED.get()) {
            log(
                "JIT_PEDIDO kept reason=chain_undefined write=none " +
                    "(ответа транспорта не было: команда могла остаться в очереди — исход " +
                    "неопределён, намерение не снимаем)"
            )
            return VerifyState(pedido, prop, cache, JitRef.Verify.UNKNOWN, null, true, "chain_undefined")
        }
        // Сверка и удаление связаны одним поколением под одним локом: окна «проверил → удалил» нет.
        synchronized(INTENT_LOCK) {
            if (CLICK_SLOT.get() != null) {
                log(
                    "JIT_APPLIED race=click_started action=none write=none " +
                        "(пока шла сверка, начался новый клик — намерение не снимаем)"
                )
                return VerifyState(pedido, prop, cache, JitRef.Verify.UNKNOWN, null, true, "click_in_flight")
            }
            if (PEDIDO_GEN.get() != gen0) {
                log(
                    "JIT_APPLIED race=pedido_changed read_gen=$gen0 now=${PEDIDO_GEN.get()} " +
                        "action=none write=none " +
                        "(пока шла сверка, запрос записали заново — устаревший результат его не снимает)"
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
            if (verdict != JitRef.Verify.OK && pedido == true) {
                log(
                    "JIT_NOT_APPLIED result=${JitRef.verifyLabel(verdict)} pedido=$pedido " +
                        "getprop=${prop.raw.ifEmpty { "empty" }} cache=${cache?.toString() ?: "unknown"} " +
                        "(как r7: предупреждение при !ok && pedido; ничего не дозаписываем)"
                )
            }
            return VerifyState(pedido, prop, cache, verdict, removed, stillPresent)
        }
    }

    fun readProp(shell: JitRef.JitShell): JitRef.PropFact {
        val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", JitRef.GETPROP_TIMEOUT_MS)
        return JitRef.parseProp(rc, out)
    }
}
