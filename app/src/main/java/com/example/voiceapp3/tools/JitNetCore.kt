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

    /**
     * §2.1 — транспорт, который может сообщить о ПОЗДНЕМ завершении уже «потерянной» команды.
     *
     * Это единственное принимаемое свидетельство того, что старая команда больше не может
     * исполниться: ответ ИМЕННО ЭТОЙ команды (её rc) пришёл после того, как вызывающий уже
     * решил, что время вышло. Ни новый процесс, ни boot id, ни `getprop`, ни включённый Wi-Fi,
     * ни IP такого свидетельства не дают.
     */
    interface JitLateEvidence {
        /** Зарегистрировать обработчик завершения ТЕКУЩЕЙ команды; вызывается не более одного раза. */
        fun onLateOutcome(cb: (rc: Int, out: String) -> Unit)
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
        fun putSnapshot(
            want: Boolean,
            snap: WifiSnapshot,
            mark: OpMark,
            /** Имена хранилищ, снятых при чтении прежних значений; `null` — все доступные. */
            target: List<String>? = null
        ): SnapshotWrite

        /** Обновить только [KEY_MARK] (состояние `sending` → `replied`/`undefined`). */
        fun updateMark(mark: OpMark): WriteResult

        /**
         * Вернуть прежние значения ключей операции (best-effort, по одному `commit()` на
         * хранилище). Атомарности между CPS и DPS НЕ обещаем: каждый `commit()` независим.
         */
        fun restorePrevious(prev: List<StorePrev>): WriteResult

        /**
         * Согласованное условное удаление данных СВОЕЙ операции: в каждом хранилище, где лежит
         * отметка этой операции, снимок и отметка удаляются ОДНИМ `editor/commit` и проверяются
         * чтением. Хранилища с чужой отметкой не трогаются. Атомарности между CPS и DPS нет.
         */
        fun removeOwned(opId: String): OwnedRemoval

        /** Значение, которое увидит продолжение, + конфликты между хранилищами. */
        fun readRestore(): SnapshotRead

        /**
         * §2.5 — прочитать пару «снимок + отметка» ПО КАЖДОМУ хранилищу одним проходом,
         * вместе с ошибками чтения и фактическим порядком доступа.
         */
        fun readOperation(): List<StoreOp>

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
    data class KeyPrev(
        val key: String,
        /** `true` — ключ есть, `false` — ключа нет, `null` — ЧТЕНИЕ НЕ УДАЛОСЬ (не «нет»). */
        val present: Boolean?,
        val bool: Boolean?,
        val str: String?,
        /**
         * §2.4: ключ есть, но ЗНАЧЕНИЕ прочитать нельзя (исключение, неожиданный тип,
         * недостоверный `null`). Такой ключ НЕ считается известным: удалять/перезаписывать его
         * нельзя, потому что прежнее значение неизвестно.
         */
        val error: String? = null
    ) {
        /** Значение известно достоверно (ключ есть или его точно нет, и ошибки чтения нет). */
        val known: Boolean get() = present != null && error == null

        /** Ключа достоверно нет. */
        val absent: Boolean get() = present == false && error == null

        fun brief(): String = when {
            error != null -> "unknown($error)"
            present == null -> "unknown(read)"
            present == false -> "none"
            str != null -> "\"${str.take(28)}\""
            bool != null -> bool.toString()
            else -> "unknown(value)"
        }
    }

    /** Прежнее состояние ключей операции в одном хранилище. */
    data class StorePrev(val name: String, val keys: List<KeyPrev>) {
        /** Все ключи прочитаны достоверно; иначе менять данные нельзя (§2.5). */
        val complete: Boolean get() = keys.all { it.known }
        fun has(key: String): Boolean = keys.any { it.key == key && it.present == true }
        fun value(key: String): KeyPrev? = keys.firstOrNull { it.key == key }
        fun brief(): String = keys.joinToString("") { k -> "${k.key}=${k.brief()};" }
    }

    /**
     * §2.5 — прочитанная пара «снимок + отметка» ОДНОГО хранилища.
     *
     * Читается и оценивается целиком, чтобы нельзя было собрать пару из разных копий/операций
     * (например, снимок из CPS, отметку из DPS). `error` — чтение этого хранилища не удалось.
     */
    data class StoreOp(
        val name: String,
        val snapshot: String?,
        val mark: String?,
        val error: String? = null
    ) {
        val empty: Boolean get() = error == null && snapshot == null && mark == null
        fun brief(): String =
            "$name:snapshot=${snapshot?.take(24) ?: "none"},mark=${mark?.take(24) ?: "none"}" +
                (error?.let { ",error=$it" } ?: "")
    }

    /**
     * Состояние СОХРАНЁННОЙ операции по всем хранилищам (§2.1–2.3, §2.5).
     *
     * Это единственный барьер перед новым кликом и перед любой записью: событие старта
     * приложения не является барьером.
     */
    enum class StoredState {
        /** Достоверно ничего нет: ни снимка, ни отметки, ошибок чтения нет. */
        NONE,

        /** Хотя бы одно хранилище не читается — «данных нет» утверждать нельзя. */
        UNREADABLE,

        /** Пара неполная или копии расходятся (снимок без отметки, разные ID, разные значения). */
        AMBIGUOUS,

        /** Снимок есть, но он не нашего формата — выполнять нельзя, удалять без разбора нельзя. */
        BROKEN_SNAPSHOT,

        /** Операция с потерянным ответом: старая команда могла остаться в очереди. */
        UNDEFINED,

        /** Операция опознана и не завершена: снимок ждёт подтверждённого возврата сети. */
        PENDING
    }

    /** Оценка сохранённой операции: состояние + что именно прочитано + пояснение для лога. */
    data class StoredOp(
        val state: StoredState,
        val snapshot: Restore? = null,
        val mark: OpMark? = null,
        val detail: String = "none",
        /** Сколько хранилищ реально прочитано (для честного «1 из 2»). */
        val readable: Int = 0,
        val total: Int = 0
    ) {
        /** Новый клик конфликтует с сохранённой операцией. */
        val blocksClick: Boolean get() = state != StoredState.NONE

        fun brief(): String =
            "${state.name.lowercase()} snapshot=${snapshot?.snap?.wire() ?: "none"} " +
                "mark=${mark?.let { it.op + "/" + it.state } ?: "none"} " +
                "readable=$readable/$total detail=$detail"
    }

    /**
     * §2.5: оценка сохранённой операции по парам каждого хранилища.
     *
     * Правила (проверяются тестами):
     *  • ошибка чтения любого хранилища ⇒ `UNREADABLE` (не «пусто»);
     *  • ровно один компонент пары ⇒ `AMBIGUOUS` (пара не восстанавливается догадкой);
     *  • разные ID операций или разные значения снимка ⇒ `AMBIGUOUS`;
     *  • неразбираемая отметка ⇒ `AMBIGUOUS`, неразбираемый снимок ⇒ `BROKEN_SNAPSHOT`;
     *  • `sending`/`undefined` ⇒ `UNDEFINED` (исход старой команды неизвестен);
     *  • всё целое и `replied` ⇒ `PENDING` (снимок ждёт подтверждённого возврата).
     */
    fun assessStored(reads: List<StoreOp>): StoredOp {
        val readable = reads.count { it.error == null }
        if (reads.isEmpty()) {
            return StoredOp(StoredState.UNREADABLE, detail = "no_store", readable = 0, total = 0)
        }
        val errors = reads.filter { it.error != null }
        if (errors.isNotEmpty()) {
            return StoredOp(
                StoredState.UNREADABLE, detail = errors.joinToString(",") { it.brief() },
                readable = readable, total = reads.size
            )
        }
        val used = reads.filterNot { it.empty }
        if (used.isEmpty()) {
            return StoredOp(StoredState.NONE, detail = "empty", readable = readable, total = reads.size)
        }
        val half = used.filter { it.snapshot == null || it.mark == null }
        if (half.isNotEmpty()) {
            return StoredOp(
                StoredState.AMBIGUOUS,
                detail = "incomplete_pair " + half.joinToString(",") { it.brief() },
                readable = readable, total = reads.size
            )
        }
        val marks = used.mapNotNull { OpMark.parse(it.mark) }
        if (marks.size != used.size) {
            return StoredOp(
                StoredState.AMBIGUOUS, detail = "mark_unparsable " + used.joinToString(",") { it.brief() },
                readable = readable, total = reads.size
            )
        }
        val restores = used.map { parseSnapshot(it.snapshot) }
        if (restores.any { it == null }) {
            return StoredOp(
                StoredState.BROKEN_SNAPSHOT,
                detail = "snapshot_unparsable " + used.joinToString(",") { it.brief() },
                readable = readable, total = reads.size
            )
        }
        val ops = marks.map { it.op }.distinct()
        if (ops.size > 1) {
            return StoredOp(
                StoredState.AMBIGUOUS, detail = "op_conflict ops=${ops.joinToString("/")}",
                readable = readable, total = reads.size
            )
        }
        val wires = restores.filterNotNull().map { it.snap.wire() }.distinct()
        if (wires.size > 1) {
            return StoredOp(
                StoredState.AMBIGUOUS, detail = "snapshot_conflict values=${wires.joinToString("/")}",
                readable = readable, total = reads.size
            )
        }
        val mark = marks.first()
        val restore = restores.filterNotNull().first()
        val state = if (mark.state == OpMark.SENDING || mark.state == OpMark.UNDEFINED)
            StoredState.UNDEFINED else StoredState.PENDING
        return StoredOp(
            state, restore, mark,
            detail = "stores=${used.joinToString(",") { it.name }}",
            readable = readable, total = reads.size
        )
    }

    /**
     * Итог согласованного удаления данных СВОЕЙ операции (§2.2).
     *
     * `consistent` — по всем хранилищам, где лежала НАША отметка, снимок и отметка удалены одним
     * `commit()` и подтверждены чтением. Атомарности между CPS и DPS не обещаем: это отдельные
     * `commit()`; `partial` показывает, что очистка неполная, и продолжение по такому остатку
     * НЕ гарантируется (следующий старт увидит остаток как неопознанный и не выполнит его).
     */
    data class OwnedRemoval(
        val attempted: Int,
        val clean: Int,
        val skippedOtherOp: Int,
        val snapshotLeft: Boolean,
        val markLeft: Boolean,
        val detail: String
    ) {
        val consistent: Boolean get() = snapshotLeft.not() && markLeft.not()
        val partial: Boolean get() = !consistent && (clean > 0 || skippedOtherOp > 0)
        fun brief(): String =
            "attempted=$attempted clean=$clean other_op=$skippedOtherOp " +
                "restore_left=$snapshotLeft mark_left=$markLeft detail=$detail"
    }

    /**
     * Чем закончился возврат клиентского Wi-Fi относительно ИСХОДНОГО снимка (§2.1).
     *
     * Удалять снимок можно только при [COMPLETE] / [COMPLETE_WAS_OFF]: тогда состояние сети
     * подтверждено чтением (или возврат по определению не требовался). `accepted` от API,
     * «запрос отправлен», наличие IP и `internet=not_checked` успехом НЕ считаются.
     */
    enum class RestoreCompletion {
        /** `wifi=1`: Wi‑Fi включён и это подтверждено чтением состояния. */
        COMPLETE,

        /** `wifi=0`: включать было нельзя и не пытались — обработка этого состояния завершена. */
        COMPLETE_WAS_OFF,

        /** Запрос принят/состояние пока не подтверждено — данные НЕ удаляем, ждём следующего старта. */
        PENDING,

        /** `setWifiEnabled` вернул `false` — запрос отклонён, состояние не подтверждено. */
        REFUSED,

        /** `WifiManager` недоступен. */
        UNAVAILABLE,

        /** Исключение при вызове/чтении. */
        EXCEPTION,

        /** Состояние прочитать нельзя — ничего не утверждаем. */
        UNKNOWN_STATE
    }

    /** Критерий завершения: что считается достаточным для удаления снимка. */
    fun completionOf(snap: WifiSnapshot, requested: Boolean, outcome: SetWifiOutcome?, stateAfter: Boolean?): RestoreCompletion =
        when {
            !snap.wifi -> RestoreCompletion.COMPLETE_WAS_OFF
            outcome == SetWifiOutcome.EXCEPTION -> RestoreCompletion.EXCEPTION
            outcome == SetWifiOutcome.REFUSED -> RestoreCompletion.REFUSED
            stateAfter == null -> RestoreCompletion.UNKNOWN_STATE
            stateAfter -> RestoreCompletion.COMPLETE
            requested -> RestoreCompletion.PENDING      // приняли, но включение ещё не видно
            else -> RestoreCompletion.PENDING
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
     * Читается и пишется ТОЛЬКО под [INTENT_LOCK] (§2.4).
     */
    private var scheduledTask: Any? = null

    /**
     * Слот идущего возврата Wi-Fi (§2.4): пока он занят, клик не начинается, и наоборот.
     * Берётся и снимается под [INTENT_LOCK], поэтому проверка актуальности и изменение сети не
     * расходятся; длинных вызовов (shell, Android API) под локом нет.
     */
    private val RESTORE_SLOT = java.util.concurrent.atomic.AtomicReference<Any?>(null)

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
        val attempt: Int = 1,
        /** §2.2: оценка сохранённой операции, снятая ПЕРЕД любым решением этого клика. */
        val stored: JitRef.StoredOp? = null
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
            // ─── §2.2 iter40: единственный барьер — сохранённое состояние, прочитанное ЗДЕСЬ,
            // под тем же локом, что и последующая запись, и ДО любой записи/shell.
            // Событие старта приложения (`onProcessStart`) барьером больше не является: если клик
            // выиграл гонку и старт получил `click_in_flight`, клик всё равно видит старую пару.
            var stored = JitRef.StoredOp(JitRef.StoredState.UNREADABLE, detail = "not_read")
            var restoreBusy = false
            synchronized(INTENT_LOCK) {
                restoreBusy = scheduledTask != null || RESTORE_SLOT.get() != null
                val reads = runCatching { store.readOperation() }.getOrElse { t ->
                    listOf(JitRef.StoreOp("read", null, null, "exception:" + t.javaClass.simpleName))
                }
                stored = JitRef.assessStored(reads)
            }
            val blockedReason = when {
                // Неопределённость в RAM — то же состояние, что и в отметке; порядок важен.
                CHAIN_UNDEFINED.get() || stored.state == JitRef.StoredState.UNDEFINED -> "chain_undefined"
                restoreBusy -> "restore_pending"
                stored.state == JitRef.StoredState.PENDING -> "previous_unfinished"
                stored.state == JitRef.StoredState.AMBIGUOUS -> "previous_ambiguous"
                stored.state == JitRef.StoredState.BROKEN_SNAPSHOT -> "previous_broken"
                stored.state == JitRef.StoredState.UNREADABLE -> "prev_unknown"
                else -> null
            }
            if (blockedReason != null) {
                val why = when (blockedReason) {
                    "chain_undefined" ->
                        "прошлая команда осталась без ответа и могла выполниться позже — исход НЕ " +
                            "известен. Ни таймаут, ни getprop, ни boot id, ни новый процесс, ни " +
                            "запись ID доказательством не являются; включённый Wi-Fi/IP тоже. " +
                            "Свидетельство ровно одно: ответ ИМЕННО той команды (поздний callback " +
                            "транспорта), после которого её выполнение уже невозможно. До него " +
                            "вторая цепочка не отправляется, снимок не перезаписывается"
                    "restore_pending" ->
                        "возврат клиентского Wi-Fi по прошлому снимку запланирован или уже " +
                            "выполняется: новый клик перезаписал бы снимок и мог бы поставить вторую " +
                            "цепочку поверх отложенного возврата"
                    "previous_unfinished" ->
                        "сохранённая операция не завершена (возврат сети не подтверждён или очистка " +
                            "не прошла). Перезапись снимка новым состоянием потеряла бы исходное"
                    "previous_ambiguous" ->
                        "сохранённая пара «снимок+отметка» неполная или копии CPS/DPS расходятся: " +
                            "какая операция и какое состояние верны — достоверно неизвестно"
                    "previous_broken" ->
                        "сохранённый снимок не нашего формата: выполнять его нельзя, а затирать " +
                            "новым значением — потерять данные владельца"
                    else ->
                        "сохранённое состояние прочитать не удалось: «данных нет» утверждать нельзя"
                }
                log(
                    "JIT_CLICK want=$want blocked=$blockedReason action=none commands=0 " +
                        "stored=«${stored.brief()}» " +
                        "(ни записи, ни shell: $why)"
                )
                return Click(want, null, null, null, -1, "", blockedReason, previousUndefined,
                    1, stored)
            }
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
            var prev: List<JitRef.StorePrev> = emptyList()
            var prevUnknown = false
            val write: JitRef.SnapshotWrite
            val read: JitRef.SnapshotRead
            synchronized(INTENT_LOCK) {
                prev = runCatching { store.readSnapshotState() }.getOrDefault(emptyList())
                prevUnknown = prev.isEmpty() || prev.any { !it.complete }
                if (prevUnknown) {
                    // §2.5: при недостоверном прежнем состоянии НИЧЕГО не меняем.
                    write = JitRef.SnapshotWrite(want, snap, emptyList())
                    read = JitRef.SnapshotRead(null, emptyList())
                } else {
                    PEDIDO_GEN.incrementAndGet()
                    write = store.putSnapshot(want, snap, mark, prev.map { it.name })
                    read = store.readRestore()
                }
            }
            if (prevUnknown) {
                log(
                    "JIT_CLICK want=$want op=$opId blocked=prev_unknown action=none commands=0 " +
                        "stores=«${prev.joinToString(",") { it.name + ":" + it.brief() }.ifEmpty { "none" }}» " +
                        "(прежние значения ключей прочитаны не полностью: менять настройки и " +
                        "отправлять скрипт нельзя — откат мог бы удалить чужое значение)"
                )
                return Click(want, snap, write, read, -1, "", "prev_unknown", previousUndefined)
            }
            log(
                "JIT_CLICK want=$want op=$opId restore=${snap.wire()} attempted=${write.attempted} " +
                    "committed=${write.committed} confirmed=${write.confirmed} " +
                    "stores=«${write.detail}» read=${read.value ?: "none"} " +
                    "conflicts=${read.conflicts.ifEmpty { listOf("none") }.joinToString(",")} " +
                    "previous=«${briefPrev(prev)}» " +
                    "(снимок ДО любых изменений; формат wifi_only — AP не снимается и не поднимается)"
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
                        "доказывает отсутствия значения в памяти. Прежние значения восстановлены " +
                        "там, где хранилище приняло запись; если восстановление не прошло — это " +
                        "видно здесь и в left_*, и клик остаётся заблокированным)"
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
                        "Отметка state=undefined сохранена на диск, поэтому блокировка переживёт " +
                        "смерть процесса; повторный клик будет отклонён, пока не появится " +
                        "достоверное свидетельство завершения ЭТОЙ команды)"
                )
                armLateEvidence(store, shell, opId, log)
            }
            return Click(want, snap, write, read, rc, out, null, previousUndefined)
        } finally {
            CLICK_SLOT.compareAndSet(token, null)
        }
    }

    /**
     * §2.1 iter40 — взвести единственное принимаемое свидетельство: ответ ИМЕННО потерянной
     * команды. Пока его нет, `CHAIN_UNDEFINED` остаётся `true`, снимок не удаляется, новый клик
     * заблокирован (это переживает смерть процесса через отметку `undefined`).
     *
     * Если транспорт такого свидетельства не умеет, честно пишем `armed=none`: тогда
     * неопределённость не снимается ничем, кроме нового подтверждённого свидетельства,
     * и блокировка остаётся.
     */
    private fun armLateEvidence(
        store: JitRef.JitStore,
        shell: JitRef.JitShell,
        opId: String,
        log: (String) -> Unit
    ) {
        val ev = shell as? JitRef.JitLateEvidence
        if (ev == null) {
            log(
                "JIT_CHAIN_LATE op=$opId armed=none reason=transport_has_no_late_callback " +
                    "(транспорт не сообщает о позднем завершении: свидетельства не будет, " +
                    "неопределённость сохраняется, снимок и блокировка остаются)"
            )
            return
        }
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        runCatching {
            ev.onLateOutcome { lrc, lout ->
                if (once.compareAndSet(false, true)) {
                    runCatching { onLateOutcome(store, opId, lrc, lout, log) }
                }
            }
        }.onFailure { t ->
            log(
                "JIT_CHAIN_LATE op=$opId armed=failed reason=${t.javaClass.simpleName} " +
                    "(подписка не удалась: свидетельства не будет, блокировка остаётся)"
            )
            return
        }
        log(
            "JIT_CHAIN_LATE op=$opId armed=transport_callback " +
                "(свидетельством считается только ответ этой команды: он доказывает, что " +
                "команда завершилась. Включённый Wi-Fi, IP, getprop, boot id и новый процесс " +
                "свидетельством НЕ являются)"
        )
    }

    /**
     * Пришёл ответ потерянной команды: она завершилась, позднего исполнения больше не будет.
     *
     * Снимаем неопределённость (`chain_undefined`) и помечаем операцию как ожидающую возврата
     * сети (`pending`). Снимок НЕ удаляется и сеть здесь не меняется: возврат выполнит обычный
     * старт (или он уже выполняется — тогда задача увидит `replied` и сможет завершиться).
     */
    internal fun onLateOutcome(
        store: JitRef.JitStore,
        opId: String,
        rc: Int,
        out: String,
        log: (String) -> Unit = {}
    ) {
        synchronized(INTENT_LOCK) {
            val cur = runCatching { JitRef.assessStored(store.readOperation()) }
                .getOrNull()
            if (cur == null) {
                log(
                    "JIT_CHAIN_LATE op=$opId ignored reason=store_unreadable " +
                        "(прочитать состояние операции нельзя: неопределённость сохраняется)"
                )
                return
            }
            // Данных уже нет (возврат и очистка прошли раньше): обновлять нечего, но ответ
            // именно этой команды — свидетельство, что она завершилась.
            val cleaned = cur.state == JitRef.StoredState.NONE
            if (!cleaned && cur.mark?.op != opId) {
                log(
                    "JIT_CHAIN_LATE op=$opId ignored reason=operation_changed " +
                        "now=«${cur.brief()}» (данные текущей операции не трогаем)"
                )
                return
            }
            val upd = if (cleaned) JitRef.WriteResult.none("operation_already_cleaned") else
                runCatching { store.updateMark(cur.mark!!.copy(state = JitRef.OpMark.REPLIED)) }
                    .getOrElse { t -> JitRef.WriteResult.none("exception:" + t.javaClass.simpleName) }
            CHAIN_UNDEFINED.set(false)
            log(
                "JIT_CHAIN_LATE op=$opId evidence=command_finished rc=$rc " +
                    "out=${out.trim().take(160).ifEmpty { "empty" }} " +
                    "mark=replied cleaned=$cleaned mark_write=${upd.ok}/${upd.attempted} " +
                    "chain_undefined=false clique=allowed_after_restore " +
                    "(это свидетельство завершения команды, а НЕ успеха JIT и НЕ наличия интернета; " +
                    "снимок остаётся до подтверждённого возврата сети)"
            )
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
            // §2.4: старт не планирует задачу, пока идёт клик — иначе два пути к сети.
            if (CLICK_SLOT.get() != null) {
                log(
                    "JIT_START skipped reason=click_in_flight action=none " +
                        "(идёт клик: планировать возврат по старому снимку нельзя. Барьером для " +
                        "клика событие старта НЕ является — клик сам читает сохранённую операцию)"
                )
                return StartOutcome(null, null, null, null, false, "click_in_flight")
            }
            // §2.5 iter40: читаем ПАРУ «снимок + отметка» по каждому хранилищу одним проходом.
            val reads = runCatching { store.readOperation() }.getOrElse { t ->
                listOf(JitRef.StoreOp("read", null, null, "exception:" + t.javaClass.simpleName))
            }
            val stored = JitRef.assessStored(reads)
            val raw = stored.snapshot?.snap?.wire()?.takeIf { stored.state != JitRef.StoredState.NONE }
                ?: reads.firstNotNullOfOrNull { it.snapshot }
            if (scheduledTask != null) {
                log(
                    "JIT_START snapshot=${stored.snapshot?.snap?.wire() ?: "none"} duplicate=true " +
                        "stored=«${stored.brief()}» action=none (эта же задача уже запланирована: " +
                        "onCreate и onStartCommand не запускают e2 дважды; флаг относится к задаче)"
                )
                return StartOutcome(stored.snapshot?.snap, raw, stored.snapshot?.format, null, false,
                    "duplicate", stored.mark)
            }
            when (stored.state) {
                JitRef.StoredState.UNREADABLE -> {
                    log(
                        "JIT_START stored=«${stored.brief()}» reason=stored_unreadable action=none " +
                            "write=none (прочитать сохранённое состояние не удалось: это НЕ «данных " +
                            "нет». Ничего не выполняем и не удаляем; клик будет заблокирован)"
                    )
                    return StartOutcome(null, null, null, null, false, "stored_unreadable")
                }
                JitRef.StoredState.NONE -> {
                    log(
                        "JIT_START snapshot=none stored=«${stored.brief()}» action=none " +
                            "(ни снимка, ни отметки: e2 не запускается)"
                    )
                    return StartOutcome(null, null, null, null, false, "no_snapshot")
                }
                JitRef.StoredState.AMBIGUOUS -> {
                    log(
                        "JIT_START stored=«${stored.brief()}» reason=stored_ambiguous action=none " +
                            "write=none (пара «снимок+отметка» неполная или копии CPS/DPS расходятся: " +
                            "какая операция верна — неизвестно, поэтому ни выполнения, ни удаления)"
                    )
                    return StartOutcome(stored.snapshot?.snap, raw, stored.snapshot?.format, null,
                        false, "stored_ambiguous")
                }
                JitRef.StoredState.BROKEN_SNAPSHOT -> {
                    log(
                        "JIT_START stored=«${stored.brief()}» reason=snapshot_unparsable " +
                            "format=none action=none write=none (снимок не нашего формата: не " +
                            "выполняем; данные владельца не удаляем)"
                    )
                    return StartOutcome(null, raw, null, null, false, "snapshot_unparsable")
                }
                JitRef.StoredState.PENDING, JitRef.StoredState.UNDEFINED -> Unit
            }
            val restore = stored.snapshot!!
            val mark = stored.mark!!
            val persistedUndefined = stored.state == JitRef.StoredState.UNDEFINED
            if (persistedUndefined) {
                CHAIN_UNDEFINED.set(true)
                log(
                    "JIT_START op=${mark.op} mark_state=${mark.state} outcome=unknown " +
                        "action=restore_state_only clicks=blocked " +
                        "(команда могла остаться в очереди прошлого процесса. Записанный ID не " +
                        "доказывает ни выполнения, ни невыполнения, а включённый Wi-Fi/IP — тем более. " +
                        "Возврат клиентского Wi-Fi выполняется ОТДЕЛЬНО от разрешения операции: он " +
                        "возвращает то, что было до клика, и НЕ означает, что старая команда " +
                        "завершилась. Неопределённость снимается только ответом самой этой команды " +
                        "(поздний callback транспорта), иначе остаётся ⛔)"
                )
            }
            val token = Any()
            val prev = scheduledTask
            scheduledTask = token
            val scheduled = try {
                scheduler.postDelayed(JitRef.RESTORE_DELAY_MS) {
                    runScheduledRestore(store, netState, restore.snap, mark.op, persistedUndefined, token, log)
                }
                true
            } catch (t: Throwable) {
                if (scheduledTask === token) scheduledTask = null
                log(
                    "JIT_START op=${mark.op} scheduler=failed reason=${t.javaClass.simpleName} " +
                        "action=none removed=none (задача не поставлена; снимок и отметка ОСТАВЛЕНЫ: " +
                        "следующий обычный запуск продолжит эту же опознанную операцию. Запуск " +
                        "приложения Android не гарантирует)"
                )
                false
            }
            log(
                "JIT_START snapshot=${restore.snap.wire()} format=${restore.format} op=${mark.op} " +
                    "mark_state=${mark.state} mark_ts=${mark.ts} identification=ok removed=none " +
                    "stored=«${stored.brief()}» " +
                    "delay_ms=${JitRef.RESTORE_DELAY_MS} scheduled=$scheduled " +
                    "action=${if (scheduled) "restore_scheduled" else "none"} internet=not_checked " +
                    "(снимок НЕ удаляется до подтверждённого возврата: смерть процесса в эти 15 с " +
                    "больше не отменяет продолжение)"
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
     * §2.1: данные операции (снимок + отметка) удаляются ТОЛЬКО при подтверждённом завершении
     * возврата относительно исходного снимка ([JitRef.RestoreCompletion]). Принятый запрос,
     * отказ, исключение и неизвестное состояние — `pending`, данные остаются, следующий обычный
     * запуск попробует снова. Постоянного сторожа нет.
     *
     * §2.2: удаление снимка и отметки выполняется согласованно (одно `commit` на хранилище,
     * условно по принадлежности операции) — `removeOwned`.
     *
     * §2.4: актуальность задачи, слот клика, принадлежность отметки и изменение сети согласованы
     * одним локом; `RESTORE_SLOT` не даёт клику начаться, пока идёт возврат.
     */
    private fun runScheduledRestore(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        snap: JitRef.WifiSnapshot,
        opId: String,
        wasUndefined: Boolean,
        token: Any,
        log: (String) -> Unit
    ) {
        var claimed = false
        var skip: String? = null
        synchronized(INTENT_LOCK) {
            if (scheduledTask !== token) {
                skip = "task_replaced"
            } else if (CLICK_SLOT.get() != null) {
                skip = "click_in_flight"
            } else {
                // §2.5: принадлежность проверяется по ЦЕЛОЙ паре, а не по одной отметке.
                val cur = runCatching { JitRef.assessStored(store.readOperation()) }.getOrNull()
                if (cur == null || cur.mark?.op != opId) {
                    skip = if (cur == null) "store_unreadable" else "snapshot_belongs_to_other_op"
                } else {
                    RESTORE_SLOT.set(token)
                    claimed = true
                }
            }
            if (!claimed && scheduledTask === token) scheduledTask = null
        }
        if (!claimed) {
            log(
                "JIT_NET_RESTORE skipped reason=$skip action=none op=$opId " +
                    "(возврат не выполняется: изменилось состояние операции или идёт клик. " +
                    "Снимок не тронут — его продолжит следующий обычный запуск или новая операция)"
            )
            return
        }
        var report: WifiRestoreReport? = null
        try {
            report = runCatching { restoreWifi(netState, snap, log) }.getOrElse { t ->
                log(
                    "JIT_NET_RESTORE exception=${t.javaClass.simpleName} action=none op=$opId " +
                        "(как e2: возврат прерван; снимок оставлен, следующий запуск попробует снова)"
                )
                null
            }
        } finally {
            synchronized(INTENT_LOCK) {
                RESTORE_SLOT.compareAndSet(token, null)
                if (scheduledTask === token) scheduledTask = null
                val completion = report?.completion
                if (completion == JitRef.RestoreCompletion.COMPLETE ||
                    completion == JitRef.RestoreCompletion.COMPLETE_WAS_OFF
                ) {
                    // Согласованное удаление ТОЛЬКО своей операции (одно commit на хранилище).
                    val rm = runCatching { store.removeOwned(opId) }
                        .getOrElse { t ->
                            JitRef.OwnedRemoval(0, 0, 0, true, true,
                                "exception:${t.javaClass.simpleName}")
                        }
                    log(
                        "JIT_RESTORE_DONE op=$opId completion=${completion?.name?.lowercase()} " +
                            "cleanup=owned ${rm.brief()} " +
                            (if (rm.consistent) "data=removed"
                             else "data=kept_or_partial (неполная очистка: остаток будет прочитан " +
                                 "как неопознанный и НЕ выполнится; гарантии продолжения по нему нет)") +
                            " write=remove(restore+mark, own op)"
                    )
                    if (wasUndefined) {
                        // §2.1 iter40: восстановление СЕТИ и разрешение НЕОПРЕДЕЛЁННОЙ операции —
                        // разные вещи. Текущий Wi-Fi ничего не говорит о старой команде: она могла
                        // остаться в очереди и выключить сеть позже. Поэтому флаг НЕ снимается.
                        // Свидетельство ровно одно — ответ самой этой команды (armLateEvidence).
                        log(
                            "JIT_UNDEFINED op=$opId outcome=still_unknown " +
                                "network_state=restored operation=unresolved clicks=blocked " +
                                "evidence=command_finished_missing " +
                                "(сеть возвращена, но исход старой команды по-прежнему неизвестен: " +
                                "включённый Wi-Fi, IP, getprop, boot id и новый процесс им не " +
                                "являются. Пока нет ответа этой команды, новый клик заблокирован — " +
                                "⛔ без обещаний авторазрешения)"
                        )
                    }
                } else {
                    log(
                        "JIT_RESTORE_PENDING op=$opId completion=${completion?.name?.lowercase() ?: "none"} " +
                            "data=kept write=none clicks=blocked reason=previous_unfinished " +
                            "(снимок и отметка ОСТАВЛЕНЫ и остаются принадлежащими этой операции: " +
                            "включение не подтверждено чтением состояния, поэтому новый клик " +
                            "заблокирован — иначе он перезаписал бы исходный снимок. Принятый " +
                            "запрос, IP-адрес и internet=not_checked успехом не считаются. " +
                            "Повторная попытка — при следующем обычном запуске, без сторожа)"
                    )
                    if (wasUndefined) {
                        CHAIN_UNDEFINED.set(true)
                        log(
                            "JIT_UNDEFINED op=$opId outcome=still_unknown " +
                                "network_state=not_restored operation=unresolved clicks=blocked " +
                                "(сеть не восстановлена подтверждённо И исход старой команды " +
                                "неизвестен: двух независимых условий для продолжения нет — ⛔)"
                        )
                    }
                }
            }
        }
    }

    // ─────────────────────────── 3. возврат клиентского Wi-Fi: `e2` ───────────────────────────

    data class WifiRestoreReport(
        val snapshot: JitRef.WifiSnapshot,
        val requested: Boolean,
        val outcome: JitRef.SetWifiOutcome?,
        val stateAfter: Boolean?,
        val address: Boolean?,
        val failure: String?,
        /** §2.1: чем закончился возврат ОТНОСИТЕЛЬНО исходного снимка. */
        val completion: JitRef.RestoreCompletion = JitRef.RestoreCompletion.PENDING
    ) {
        val log: String
            get() = "JIT_NET_RESTORE snapshot=${snapshot.wire()} request=" +
                (if (requested) "setWifiEnabled(true)" else "none") +
                " returned=${outcome?.let { JitRef.setWifiLabel(it) } ?: "not_called"} " +
                "state_after=${stateAfter?.toString() ?: "unknown"} " +
                "address=${address?.toString() ?: "unknown"} internet=not_checked " +
                "completion=${completion.name.lowercase()}" +
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
            val r = WifiRestoreReport(
                snap, false, null, null, addr, null, JitRef.RestoreCompletion.COMPLETE_WAS_OFF
            )
            log(r.log + " (Wi-Fi до операции был ВЫКЛЮЧЕН: не включаем — как e2)")
            return r
        }
        val stateBefore = runCatching { netState.wifiOn() }.getOrElse { t ->
            val r = WifiRestoreReport(
                snap, false, null, null, null, "read:${t.javaClass.simpleName}",
                JitRef.RestoreCompletion.EXCEPTION
            )
            log(r.log + " (как e2: чтение состояния сорвалось — запрос не отправлен)")
            return r
        }
        if (stateBefore == null) {
            val r = WifiRestoreReport(
                snap, false, null, null, null, "wifi_manager_absent",
                JitRef.RestoreCompletion.UNAVAILABLE
            )
            log(r.log + " (WifiManager недоступен: запрос не отправлен)")
            return r
        }
        if (stateBefore) {
            // Уже включён — это подтверждённое чтением состояние, возврат завершён.
            val addr = runCatching { netState.wifiAddress() }.getOrNull()
            val r = WifiRestoreReport(
                snap, false, null, true, addr, null, JitRef.RestoreCompletion.COMPLETE
            )
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
        val completion = if (failure != null) JitRef.RestoreCompletion.EXCEPTION
                         else JitRef.completionOf(snap, true, outcome, after)
        val r = WifiRestoreReport(snap, true, outcome, after, addr, failure, completion)
        log(
            r.log + " (" +
                when (outcome) {
                    JitRef.SetWifiOutcome.ACCEPTED ->
                        if (after == true)
                            "API принял запрос (true) и включение подтверждено чтением состояния; " +
                                "это НЕ доказательство доступности интернета (internet=not_checked)"
                        else
                            "API принял запрос (true), но включение НЕ подтверждено чтением " +
                                "(state_after=${after?.toString() ?: "unknown"}) → pending, данные " +
                                "операции сохраняются"
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
