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
     * iter67 §J.2 — `applicationId` для `pm path` в скрипте. Значение ОБЯЗАНО совпадать с
     * `app/build.gradle.kts` (проверяется тестом); сам `applicationId` не меняется.
     */
    internal const val APP_ID = "com.example.voiceapp3"

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



    /**
     * iter56 §4.3: монотонное поколение намерения JIT (порядок кликов, а не показание часов).
     * Собственный ключ; в референсе его нет и на его ключи он не влияет.
     */
    /**
     * iter67 §K — ключи, которые принадлежат ОПЕРАЦИИ и удаляются только вместе и только в оба
     * хранилища. `KEY_INTENT_SEQ` сюда НЕ входит: монотонное поколение намерений не должно
     * откатываться удалением операции.
     */
    internal val OWNED_KEYS: List<String> = listOf(KEY_PEDIDO, KEY_AUTO, KEY_RESTORE, KEY_MARK)

    internal const val KEY_INTENT_SEQ = "jit_intent_seq"
    /** iter57 §7.2: токен эпохи счётчика (только для честного лога, порядок не задаёт). */
    internal const val KEY_INTENT_EPOCH = "jit_intent_epoch"

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
        /**
         * iter67c §1 — доставка ДЛИННОГО текста тем же путём, что payload (файл + запуск), без
         * лимита одного ADB-пакета 4096 байт.
         *
         * Почему это отдельный метод, а не «просто передать флаг»: 20.11.2026 команда `claim`
         * выросла до 44 454 байт и транспорт честно ответил `ERR_ADB_TOO_LONG`, а клик был
         * заблокирован на шаге владения. Размер текста — свойство СБОРКИ команды, а не намерения
         * владельца, и он обязан вести себя как payload: доставляться файлом.
         *
         * `name` — префикс адресации доставки в журнале (`jit_claim_<opId>`).
         * Возвращает `(rc, stdout+stderr)` так же, как [exec], чтобы разбор не различался.
         *
         * Тело по умолчанию оставлено ради тестовых shell'ов, которые моделируют транспорт без
         * файловой доставки: для них путь остаётся инлайновым и поведение не меняется. Боевой
         * транспорт ([AndroidShell]) и диагностический ([DiagShell]) переопределяют метод явно.
         */
        fun execScript(name: String, text: String, timeoutMs: Int): Pair<Int, String> =
            exec(text, timeoutMs)


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
     *
     * §3.2 iter41 — ЧЕСТНО О РЕАЛЬНОМ ТРАНСПОРТЕ: боевой `JitNet.AndroidShell` (поверх
     * `AdbClient`) этот интерфейс НЕ реализует, и автоматического разрешения в production нет.
     * Причины (по коду `AdbClient`, не по догадке):
     *  • `AdbClient.execute` ставит `Job` в очередь и ЖДЁТ `latch`; по таймауту вызывающему
     *    возвращается `ERR_ADB_TIMEOUT`, но job остаётся в очереди и может быть выполнен позже —
     *    его `onResult` уже никто не слушает, а вызывающий об этом никогда не узнает;
     *  • обрыв сокета/ошибка чтения дают `rc≠0`, но это НЕ доказывает, что команда на устройстве
     *    завершилась: shell в системе может продолжать выполнение;
     *  • процесс приложения может умереть раньше, чем придёт что-либо сообщать: пережить смерть
     *    процесса и «донести» ответ некому.
     * Поэтому «поздний callback» существует ниже как тестовый интерфейс (проверка логики), а не
     * как заявление о возможностях `AdbClient`. Пока транспорт не даёт ДОКАЗАННОГО свидетельства,
     * неопределённость остаётся, снимок и блокировка сохраняются (⛔).
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
        val error: String? = null,
        /**
         * §1.2 iter44: идентичность копии в момент записи (`имя@тождество`). Нужна, чтобы
         * отличить «ту же копию» от «другой копии под тем же именем»: имя совпадает, данные —
         * нет. `null` — идентичность недоступна (это тоже повод сказать об этом в логе).
         */
        val identity: String? = null
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
        val stores: List<StoreReport>,
        /**
         * iter43 §4: хранилища, которые стали доступны ПОСЛЕ предварительного чтения и потому
         * не участвовали в записи. Их имена не попадают в сравнение наборов, поэтому перечисляем
         * явно — иначе «появившаяся копия» тихо исчезала бы из отчёта.
         */
        val appeared: List<String> = emptyList(),
        /** Хранилища из [target], которые к моменту записи стали недоступны. */
        val absent: List<String> = emptyList()
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

    /**
     * §1.2/§1.3 iter44 — ЧТО ИМЕННО МОЖНО ВОЗВРАЩАТЬ.
     *
     * Безусловный `restorePrevious` опасен: если между нашей записью и возвратом значение изменил
     * кто-то другой (другая операция, плагин, ручная правка), «возврат прежнего» затрёт чужое.
     * Поэтому план строится по ФАКТУ чтения пары «снимок + отметка» ([StoreOp]):
     *  • [owned] — отметка принадлежит НАШЕЙ операции И снимок равен нашему значению;
     *  • [foreign] — лежит ЧУЖАЯ отметка: не трогаем и «ни записи не было» не заявляем;
     *  • [unknown] — опознать нельзя (ошибка чтения, нет отметки, снимок подменён).
     */
    data class RestorePlan(
        val owned: List<StorePrev>,
        val foreign: List<String>,
        val unknown: List<String>
    ) {
        val complete: Boolean get() = foreign.isEmpty() && unknown.isEmpty()
        fun brief(): String =
            "owned=${owned.map { it.name }.ifEmpty { listOf("none") }} " +
                "foreign=${foreign.ifEmpty { listOf("none") }} " +
                "unknown=${unknown.ifEmpty { listOf("none") }}"
    }

    /** Построить план возврата. Без чтения ([ops] пуст) план пустой и `complete=false`. */
    fun planRestore(
        prev: List<StorePrev>,
        ops: List<StoreOp>,
        opId: String,
        snap: WifiSnapshot
    ): RestorePlan {
        val owned = ArrayList<StorePrev>(prev.size)
        val foreign = ArrayList<String>(2)
        val unknown = ArrayList<String>(2)
        for (p in prev) {
            val o = ops.firstOrNull { it.name == p.name }
            if (o == null || o.error != null) { unknown += p.name; continue }
            val mark = OpMark.parse(o.mark)
            if (mark == null) { unknown += p.name; continue }
            if (mark.op != opId) { foreign += p.name; continue }
            if (o.snapshot != snap.wire()) { unknown += p.name; continue }
            owned += p
        }
        return RestorePlan(owned, foreign, unknown)
    }

    /**
     * §1.2 iter44: копия под тем же именем, но ДРУГАЯ (доказанное расхождение идентичностей).
     * Недоступную идентичность расхождением НЕ считаем — иначе клиент без такой возможности был
     * бы заблокирован навсегда; но и молчать о ней нельзя ([identityUnprovable]).
     */
    fun identityChanged(prev: List<StorePrev>, write: SnapshotWrite): List<String> {
        val out = ArrayList<String>(2)
        for (p in prev) {
            val w = write.stores.firstOrNull { it.name == p.name } ?: continue
            if (p.identity == null || w.identity == null) continue
            if (p.identity != w.identity) out += p.name
        }
        return out
    }

    /** §1.2 iter44: для каких хранилищ тождество копии ДОКАЗАТЬ нельзя. */
    fun identityUnprovable(prev: List<StorePrev>, write: SnapshotWrite): List<String> {
        val out = ArrayList<String>(2)
        for (p in prev) {
            val w = write.stores.firstOrNull { it.name == p.name } ?: continue
            if (p.identity == null || w.identity == null) out += p.name
        }
        return out
    }

    interface JitStore {
        /**
         * iter56 §4.3 — ВЫДАТЬ СЛЕДУЮЩЕЕ МОНОТОННОЕ ПОКОЛЕНИЕ НАМЕРЕНИЯ.
         *
         * Зачем отдельный счётчик, а не `System.currentTimeMillis()`: часы можно перевести назад,
         * два клика могут попасть в одну миллисекунду, а номер, сохранённый ДО отправки, переживает
         * restart процесса. Значение читается обратно; `null` — выдать и подтвердить не удалось
         * (тогда порядок честно считается по времени с правилом `ts_legacy`, а не выдаётся за
         * доказанный).
         */
        fun bumpIntentSeq(): Long? = null

        fun getString(key: String): String?

        /**
         * iter67 §I.1 — записать флаг-факт в КАЖДОЕ доступное хранилище одним `commit()` и
         * подтвердить чтением. `true` — подтверждено хотя бы в одной копии.
         */
        fun putBool(key: String, value: Boolean): Boolean = false

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

        /**
         * §7.3 iter47 — когда СТАРТОВАЛ текущий `zygote` (wall clock, мс), `null` — прочитать
         * нельзя. Это ЕДИНСТВЕННЫЙ доступный проверяемый факт о судьбе команды
         * `setprop ctl.restart zygote`: если процесс zygote стартовал ПОСЛЕ отметки операции,
         * значит команда была доставлена и выполнена, и «висит в очереди транспорта» она уже
         * быть не может. Чего это НЕ доказывает: что JIT-компиляция произошла.
         */
        fun zygoteStartMs(): Long? = null
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
    data class OpMark(
        val op: String,
        /** iter53 §5.1: ВСЕГДА миллисекунды — единица видна из имени поля. */
        val tsMs: Long,
        val want: Boolean,
        val state: String,
        /**
         * iter56 §4.3 — монотонное поколение намерения (порядок кликов).
         *
         * Пишется в ТОМ ЖЕ `commit()`, что и снимок, то есть ДО записи claim и ДО отправки
         * payload; значение выдаёт [JitStore.bumpIntentSeq] с подтверждением чтением. `0` —
         * поколение не выдано (легаси-отметка/счётчик недоступен): порядок тогда сравнивается
         * по времени с явным правилом `rule=ts_legacy`.
         */
        val seq: Long = 0L,
        /**
         * iter56 §5 — ЭКЗЕМПЛЯР исполнения (`inst`), записанный ДО отправки payload.
         *
         * Без него после перезапуска процесса нельзя сказать, чьё именно исполнение читается в
         * terminal'е: файл назван по opId, а opId переиспользуется. Тогда `END` повторной доставки
         * того же payload выглядел как завершение текущего исполнения. `null` — отметка прежних
         * версий; тогда факты берутся только при однозначной атрибуции (см. JitOwn.parseTerminal).
         */
        val inst: String? = null,
        /**
         * iter67 §C — номер ПОПЫТКИ для этой же операции.
         *
         * Дефект: `ts` в отметке при повторной отправке не обновлялся, поэтому возраст считался
         * от первой попытки и операция «старела» вечно (`JIT_PEDIDO kept` 147 и 279 раз), а
         * разблокировка молчала. Теперь `ts` = время ПОСЛЕДНЕЙ попытки, и номер попытки виден
         * отдельным полем (в id он уже был строкой `-<attempt>-`, но полем не был).
         * `0` — отметка прежних версий (поля не было): тогда номер попытки неизвестен.
         */
        val attempt: Int = 0,
        /**
         * iter67 §C.3 — идентификатор загрузки, при которой сделана отметка.
         *
         * `null` — отметка прежних версий (сравнивать не с чем: окно остаётся временным).
         * Смена загрузки закрывает окно по определению: команда, отправленная в прошлой загрузке,
         * в этой выполниться не может. Значение читает и сравнивает [JitUnblock] — сам разбор
         * остаётся вне ядра (правило `noOwnMechanismsLeftInCore`).
         */
        val boot: String? = null
    ) {
        fun wire(): String =
            "op=$op;ts_ms=$tsMs;ts_s=${JitOwn.msToS(tsMs)};want=${if (want) 1 else 0};state=$state" +
                (inst?.takeIf { it.isNotBlank() }?.let { ";inst=$it" } ?: "") +
                // iter56 §4.3: поколение дописывается ТОЛЬКО когда оно есть — отметки прежних
                // версий и тестовые записи остаются байт-в-байт теми же (совместимость чтения).
                (if (seq > 0L) ";seq=$seq" else "") +
                // iter67 §C: попытка и загрузка — тоже только когда известны.
                (if (attempt > 0) ";attempt=$attempt" else "") +
                (boot?.takeIf { it.isNotBlank() }?.let { ";boot=$it" } ?: "")

        companion object {
            const val SENDING = "sending"
            const val REPLIED = "replied"
            const val UNDEFINED = "undefined"

            // Поле времени: `ts_ms` (новое), `ts_s` (новое), оба сразу (так пишет [wire]) либо
            // легаси `ts=`. Единица нормализуется в миллисекунды через [JitOwn.Op.parseTsMs].
            private val RE = Regex(
                "^op=([A-Za-z0-9_.-]{1,48});" +
                    "(?:ts_ms=\\d{1,19};ts_s=\\d{1,19}|ts_ms=\\d{1,19}|ts_s=\\d{1,19}|ts=\\d{1,19})" +
                    ";want=([01]);state=(sending|replied|undefined)" +
                    // порядок полей фиксирован; хвосты необязательны (совместимость чтения:
                    // отметки прежних версий читаются без `attempt`/`boot`)
                    "(?:;inst=[A-Za-z0-9_.-]{1,80})?" +
                    "(?:;seq=\\d{1,19})?" +
                    "(?:;attempt=\\d{1,6})?" +
                    "(?:;boot=[A-Za-z0-9_.-]{1,64})?$"
            )

            /**
             * `null` — отметки нет или она не нашего формата (значит операция не опознана).
             *
             * Старые записи `ts=<n>` принимаются: единица определяется по величине (>= 1e11 — мс,
             * иначе секунды) и нормализуется в миллисекунды. Отметка вне допустимого диапазона —
             * `null`, а не «примерно ноль»: неизвестный возраст нельзя превращать в «свежая».
             */
            fun parse(raw: String?): OpMark? {
                if (raw == null) return null
                val m = RE.matchEntire(raw.trim()) ?: return null
                val ts = JitOwn.Op.parseTsMs(raw) ?: return null
                return OpMark(
                    op = m.groupValues[1],
                    inst = Regex("(?:^|;)inst=([A-Za-z0-9_.-]{1,80})(?:;|$)")
                        .find(raw.trim())?.groupValues?.get(1),
                    tsMs = ts,
                    want = m.groupValues[2] == "1",
                    state = m.groupValues[3],
                    seq = Regex("(?:^|;)seq=(\\d{1,19})(?=;|$)")
                        .find(raw.trim())?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
                    attempt = Regex("(?:^|;)attempt=(\\d{1,6})(?=;|$)")
                        .find(raw.trim())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    boot = Regex("(?:^|;)boot=([A-Za-z0-9_.-]{1,64})(?=;|$)")
                        .find(raw.trim())?.groupValues?.get(1)
                )
            }

            /** Отметка для тестов/адаптеров, где идентификатор задаётся явно. */
            fun of(op: String, want: Boolean, state: String = SENDING, tsMs: Long = 0L) =
                OpMark(op, tsMs, want, state)
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
    data class StorePrev(
        val name: String,
        val keys: List<KeyPrev>,
        /** §1.2 iter44: идентичность копии, снятая при предварительном чтении. */
        val identity: String? = null
    ) {
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
            // iter67 §K: если хоть ОДНА копия разбирается — берём её (класс `invalid_one` в журнал),
            // а не объявляем неоднозначность навсегда. Живой случай: `ts=` в `cps` и валидный `ts`
            // в `dps` давали `mark_unparsable` и бессрочную блокировку.
            val valid = used.firstOrNull { OpMark.parse(it.mark) != null }
            if (valid != null && marks.isNotEmpty()) {
                val restoresV = used.mapNotNull { parseSnapshot(it.snapshot) }
                if (restoresV.isEmpty()) {
                    return StoredOp(
                        StoredState.BROKEN_SNAPSHOT,
                        detail = "snapshot_unparsable " + used.joinToString(",") { it.brief() },
                        readable = readable, total = reads.size
                    )
                }
                val markV = OpMark.parse(valid.mark)!!
                val stateV = if (markV.state == OpMark.SENDING || markV.state == OpMark.UNDEFINED)
                    StoredState.UNDEFINED else StoredState.PENDING
                return StoredOp(
                    stateV, restoresV.first(), markV,
                    detail = "pair diag=invalid_one used=${valid.name} " +
                        JitPair.line(PairDiag.INVALID_ONE, valid.name),
                    readable = readable, total = reads.size
                )
            }
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
            // iter67 §K: разные операции в копиях — единственный случай, который блокирует клик
            // (`OP_DIFF`): какая операция и какой снимок верны — неизвестно.
            return StoredOp(
                StoredState.AMBIGUOUS,
                detail = "op_conflict ops=${ops.joinToString("/")} " +
                    JitPair.line(PairDiag.OP_DIFF, reads.first().name),
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
        // §4.1 iter41: одинаковый ID и одинаковый Wi-Fi НЕ означают одинакового состояния.
        // `state` (`sending`/`replied`/`undefined`), `want` и время записи — существенные поля:
        // отметка обновляется ОТДЕЛЬНЫМИ `commit()` в CPS и DPS, поэтому копии одной операции
        // могут разойтись. Если они разошлись, какая из них верна — неизвестно: это конфликт,
        // а не «берём первую по порядку чтения».
        // iter67 §K: расхождение `state`/`ts` при ОДНОЙ операции — не приговор. Раньше оно давало
        // `AMBIGUOUS`, а тот был терминальным: после правки только `cps` блокировка становилась
        // бессрочной (`mark_unparsable … ts=` / `ts=1794577045082`). Теперь берётся копия «хуже»
        // (SENDING/UNDEFINED), класс называется строкой `JIT_PAIR`, а выход дают правки C и H.
        val states = marks.map { it.state }.distinct()
        if (states.size > 1) {
            val pair = JitPair.classifyPair(
                PairOp(used[0].name, used[0].snapshot, used[0].mark),
                PairOp(used[1].name, used[1].snapshot, used[1].mark)
            )
            val worse = JitPair.worseOf(
                PairOp(used[0].name, used[0].snapshot, used[0].mark),
                PairOp(used[1].name, used[1].snapshot, used[1].mark)
            )
            val wm = OpMark.parse(worse.mark)
            val st = if (wm?.state == OpMark.SENDING || wm?.state == OpMark.UNDEFINED)
                StoredState.UNDEFINED else StoredState.PENDING
            return StoredOp(
                st, restores.filterNotNull().first(), wm,
                detail = "mark_state_conflict pair diag=${pair.name.lowercase()} used=${worse.name} " +
                    "states=${states.joinToString("/")} repair=rewrite_worse " +
                    JitPair.line(pair, worse.name),
                readable = readable, total = reads.size
            )
        }
        val wants = marks.map { it.want }.distinct()
        if (wants.size > 1) {
            return StoredOp(
                StoredState.AMBIGUOUS,
                detail = "mark_want_conflict want=${wants.joinToString("/") { if (it) "1" else "0" }}",
                readable = readable, total = reads.size
            )
        }
        val stamps = marks.map { it.tsMs }.distinct()
        if (stamps.size > 1) {
            // iter67 §K: возраст берётся по САМОЙ СВЕЖЕЙ копии — иначе старая копия держала бы
            // операцию «вечно старой» (и блокировка не снималась бы никогда).
            val newest = marks.maxByOrNull { it.tsMs }!!
            return StoredOp(
                StoredState.UNDEFINED, restores.filterNotNull().first(), newest,
                detail = "mark_ts_conflict pair diag=ts_diff used=ts:${newest.tsMs} " +
                    "ts=${stamps.joinToString("/")} repair=rewrite_worse " +
                    JitPair.line(PairDiag.TS_DIFF, "newest"),
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

    /**
     * Литерал `w0/la.run` (референс) + безопасность `umount` и диагностика iter67 §J.
     *
     * Дефект (живая ГУ): `umount -l` снимал и точку, из которой исполняется СОБСТВЕННЫЙ APK
     * (`/data/app/…`), процесс терял файлы и умирал ДО `setprop`: `zygote_pid` не менялся,
     * `dalvik.vm.usejit=false`, файлов `claim/lock/term` нет, `JIT_OP_PHASE`/`JIT_OP_PROBE` = 0.
     *
     * Что добавлено (порядок шагов референса сохранён: `svc wifi disable` → слив `ip rule` →
     * `umount` → `setprop` → `ctl.restart zygote`):
     *  1. `trap EXIT` ставится ДО первого side effect и пишет в `/data/local/tmp` (не в `/data/app`);
     *  2. свой каталог определяется через `pm path` + `dirname` и ИСКЛЮЧАЕТСЯ из `umount`;
     *  3. при неизвестном `pm path` размонтирование не выполняется (`UMOUNT_SKIPPED self_unknown`);
     *  4. строки `UMOUNT_*` пишутся ДО `setprop`.
     */
    private fun scriptBody(want: Boolean, guard: String = "", termPath: String? = null): String {
        val D = "$"
        // iter67 §J.1: `trap EXIT` — ДО первого side effect, в /data/local/tmp (свой каталог
        // операции), а не в /data/app, который сам же и размонтируется.
        val trap = if (termPath == null) "" else
            "    _term=\"$termPath\"\n" +
                "    trap 'rc=${D}?; echo \"TRAP rc=${D}rc pid=${D}${D} pstart=${D}(cut -d\" \" -f22 /proc/${D}${D}/stat 2>/dev/null)\" >> \"${D}_term\" 2>/dev/null' EXIT\n"
        val drain = "        svc wifi disable\n" +
            "        _n=0\n" +
            "        while [ ${D}_n -lt $DRAIN_LOOP_MAX ]; do\n" +
            "          [ \"${D}(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n" +
            "          sleep 1; _n=${D}((_n + 1))\n" +
            "        done\n"
        // iter67 §J.2/§J.3: исключить точку установки СВОЕГО APK; отчёт — в terminal-файл.
        val mount = if (termPath == null) {
            "        for _m in ${D}(grep ' /data/app/' /proc/mounts | awk '{print ${D}2}'); do\n" +
                "          umount -l \"${D}_m\" 2>/dev/null\n" +
                "        done\n"
        } else {
            "        _self_apk=${D}(pm path " + APP_ID + " 2>/dev/null | sed -n 's/^package://p' | head -1)\n" +
                "        _self_dir=${D}(dirname \"${D}_self_apk\" 2>/dev/null)\n" +
                "        if [ -z \"${D}_self_dir\" ] || [ \"${D}_self_dir\" = \".\" ]; then\n" +
                "          echo \"UMOUNT_SKIPPED reason=self_unknown\" >> \"${D}_term\" 2>/dev/null\n" +
                "        else\n" +
                "          for _m in ${D}(grep ' /data/app/' /proc/mounts | awk '{print ${D}2}'); do\n" +
                "            case \"${D}_m/\" in\n" +
                "              \"${D}_self_dir\"/*) echo \"UMOUNT_SKIP self=${D}_m\" >> \"${D}_term\" 2>/dev/null ;;\n" +
                "              *) umount -l \"${D}_m\" 2>/dev/null && echo \"UMOUNT_DONE path=${D}_m\" >> \"${D}_term\" 2>/dev/null" +
                " || echo \"UMOUNT_FAIL path=${D}_m\" >> \"${D}_term\" 2>/dev/null ;;\n" +
                "            esac\n" +
                "          done\n" +
                "        fi\n"
        }
        val props = "        setprop $PROP_USEJIT ${if (want) "true" else "false"}\n" +
            "        setprop ctl.restart zygote\n    "
        return guard + "\n" + trap + drain + mount + props
    }

    /** Литерал `w0/la.run` целиком, включая шаг монтирований — эталон для сверки. */
    fun referenceScript(want: Boolean): String = scriptBody(want)

    /**
     * Скрипт, который отправляется: тот же литерал референса + (если задана) ГВАРДИЯ владения.
     *
     * Гвардия (`JitOwn.guard`) стоит ДО первого side effect: при чужом claim payload печатает
     * `JIT_REFUSED` и выходит, ничего не меняя. Она же пишет `START`/`END` в terminal-файл
     * операции — это единственный факт исхода, привязанный к id самой операции.
     */
    fun chainScript(want: Boolean, guard: String = "", termPath: String? = null): String =
        scriptBody(want, guard, termPath)

    /** iter51 §7: тестовый фасад на литерал референса (без гвардии). */
    internal fun referenceScriptForTest(want: Boolean): String = referenceScript(want)

    /** iter51 §7: тестовый фасад на отправляемый скрипт (с гвардией). */
    internal fun chainScriptForTest(want: Boolean, guard: String): String =
        chainScript(want, guard, JitOwn.termPath("test"))

    /** iter51 §7: литерал референса БЕЗ добавок iter67 §J (для сверки с `w0/la.run`). */
    internal fun referenceScriptNoTrapForTest(want: Boolean): String = scriptBody(want, "", null)

    /** Значение свойства JIT (для проверки, что литерал референса не подменён). */
    internal const val PROP_USEJIT_VALUE_TEST = PROP_USEJIT

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

    data class PropFact(
        val value: Prop,
        val raw: String,
        val rc: Int,
        val reason: String,
        /** iter67 §A: вывод совпал с ожидаемым (`rc==0 && got==expect`). Без ожидания — `false`. */
        val expectOk: Boolean = false
    ) {
        val log: String
            get() = "getprop=${if (raw.isEmpty()) "empty" else raw} rc=$rc " +
                "fact=${value.name.lowercase()} reason=$reason expect_ok=$expectOk"
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

    /**
     * §5.2 iter41 — учёт ОГРАНИЧЕННОГО числа попыток диагностического сбора.
     *
     * Зачем: «сбор запущен» и «необходимый сбор успешно завершён» — разные факты. Раньше флаг
     * выставлялся до выполнения, поэтому неудачный сбор (канал не готов, пассивный доступ не
     * состоялся) навсегда лишал владельца возможности получить данные позже.
     *
     * Что делает: не больше [maxRuns] попыток за процесс; успех фиксируется только по факту
     * завершения сборщиков; повтор не бесконечный, сторожа и новых кнопок нет.
     */
    /**
     * §3 iter42 — ограничитель событийных снимков сети (сравнимых «до/после»).
     *
     * Снимок делается только по СУЩЕСТВУЮЩИМ событиям владельца/системы (изменение TTL, событие
     * Wi-Fi/активной сети). Своих кнопок, включения TTL и любых изменений сети здесь нет.
     *
     * Ограничения: не чаще [minIntervalMs] и не больше [maxPerHour] за час; один снимок на ключ
     * одновременно (без параллельных дублей); при шторме выполняется ОДИН итоговый снимок
     * устойчивого состояния после debounce, а не серия.
     */
    class EventSnapshots(
        private val minIntervalMs: Long = 90_000L,
        private val maxPerHour: Int = 6,
        /** Отдельная квота на ДЕЙСТВИЯ владельца: они не должны теряться из-за авто-лимита. */
        private val userMaxPerHour: Int = 12
    ) {
        private var lastAtMs = 0L
        private var lastKey: String? = null
        private var windowStartMs = 0L
        /**
         * Отдельный флаг начала окна. Раньше окно определялось как `windowStartMs == 0L`, и при
         * первом вызове с `nowMs = 0` (синтетическое время в тестах) окно обнулялось НА КАЖДОМ
         * вызове — часовая квота не набиралась вовсе. На реальном времени это не проявлялось,
         * но правило должно быть корректным при любом старте.
         */
        private var windowStarted = false
        private var inWindow = 0
        private var inWindowUser = 0
        private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val log = ArrayList<String>()
        private val maxLog = 32

        /** «После» принимается парным к «до», если прошло не больше 10 минут. */
        private val PAIR_WINDOW_MS = 600_000L

        /**
         * @return `null` — снимок разрешён (можно запускать); иначе причина пропуска.
         */
        fun tryAcquire(key: String, nowMs: Long): String? = acquire(key, nowMs, null)

        /**
         * iter43 §3.2 — снимок по ДЕЙСТВИЮ ВЛАДЕЛЬЦА (переключение TTL).
         *
         * Такие события не должны теряться из-за общего ограничения частоты: у них отдельная
         * квота ([userMaxPerHour]) и нет минимального интервала. Общий часовой лимит и дедуп
         * in-flight продолжают действовать, а каждый пропуск пишется с причиной.
         */
        fun tryAcquireUser(key: String, nowMs: Long): String? = acquire(key, nowMs, null, userEvent = true)

        /** Парный снимок пользовательского события (`_pre` → `_post`). */
        fun tryAcquireUserPaired(key: String, nowMs: Long, pairWith: String): String? =
            acquire(key, nowMs, pairWith, userEvent = true)

        /**
         * Снимок, парный к предыдущему (`pairWith`) — например «до» и «после» РЕАЛЬНОГО
         * изменения TTL. Пара не должна теряться из-за минимального интервала: иначе «после»
         * всегда отбрасывалось бы, и сравнивать было бы не с чем. Ограничение часа при этом
         * действует: шторм по-прежнему не превращается в поток снимков.
         */
        fun tryAcquirePaired(key: String, nowMs: Long, pairWith: String): String? =
            acquire(key, nowMs, pairWith)

        /**
         * База имени пары: `<событие>_pre` ↔ `<событие>_post`. Пара разрешена только внутри
         * ОДНОГО события — иначе «парностью» можно было бы обойти ограничение частоты.
         */
        private fun pairBase(k: String): String? = when {
            k.endsWith("_pre") -> k.removeSuffix("_pre")
            k.endsWith("_post") -> k.removeSuffix("_post")
            else -> null
        }

        private fun acquire(
            key: String,
            nowMs: Long,
            pairWith: String?,
            userEvent: Boolean = false
        ): String? {
            synchronized(this) {
                if (inFlight.putIfAbsent(key, true) != null) return "duplicate_in_flight"
                if (!windowStarted || nowMs - windowStartMs > 3_600_000L) {
                    windowStarted = true
                    windowStartMs = nowMs
                    inWindow = 0
                    inWindowUser = 0
                }
                if (userEvent) {
                    if (inWindowUser >= userMaxPerHour) {
                        inFlight.remove(key)
                        return "user_hour_limit_$userMaxPerHour"
                    }
                } else if (inWindow >= maxPerHour) {
                    inFlight.remove(key)
                    return "hour_limit_$maxPerHour"
                }
                val since = nowMs - lastAtMs
                val paired = pairWith != null && pairWith == lastKey &&
                    pairBase(key) != null && pairBase(key) == pairBase(pairWith) &&
                    since in 0..PAIR_WINDOW_MS
                if (!userEvent && !paired && lastAtMs != 0L && since in 0 until minIntervalMs) {
                    inFlight.remove(key)
                    return "rate_limited ${since}ms<$minIntervalMs"
                }
                lastAtMs = nowMs
                lastKey = key
                inWindow++
                if (userEvent) inWindowUser++
                return null
            }
        }

        fun release(key: String) {
            inFlight.remove(key)
        }

        fun used(): Int = synchronized(this) { inWindow }

        fun usedUser(): Int = synchronized(this) { inWindowUser }

        /** Сколько миллисекунд осталось до разрешённого авто-снимка (для trailing). */
        fun remainingMs(nowMs: Long): Long = synchronized(this) {
            if (lastAtMs == 0L) 0L else (minIntervalMs - (nowMs - lastAtMs)).coerceAtLeast(0L)
        }

        fun note(line: String) {
            synchronized(this) {
                if (log.size >= maxLog) log.removeAt(0)
                log.add(line)
            }
        }

        fun drain(): List<String> = synchronized(this) { log.toList().also { log.clear() } }

        fun resetForTest() {
            synchronized(this) {
                lastAtMs = 0L; lastKey = null; windowStartMs = 0L; windowStarted = false
                inWindow = 0; inWindowUser = 0
                inFlight.clear(); log.clear()
            }
        }
    }

    /**
     * §2.7/§3 iter42 — итог работы ОДНОГО сборщика диагностики.
     *
     * Отличается от «не было исключения»: здесь видно, сколько секций реально получено, сколько
     * строк подтверждено в файле, и только при выполнении явных критериев сбор считается
     * `completed`. Иначе — `partial` с причиной; недоступный канал — `channel_not_ready`.
     */
    data class DiagReport(
        val block: String,
        val sectionCount: Int,
        val sectionsDefinite: Int,
        val offered: Int,
        val written: Int,
        val failed: Int,
        val dropped: Int,
        val verdict: String,
        val sink: String,
        val status: String,
        val reason: String
    ) {
        val completed: Boolean get() = status == "completed"
    }

    /**
     * iter43 §2.3 — ОРКЕСТРАЦИЯ сбора диагностики: единственный запуск, ограниченные повторы,
     * отмена устаревших отложенных задач.
     *
     * Почему отдельный класс: [`DiagOnce`] — это счётчик попыток, а не планировщик. Он не мешал
     * параллельному запуску двух сборов, не планировал повтор после частичного результата и не
     * отменял «страховочную» проверку через 30 с после того, как сбор уже удался.
     *
     * Гарантии:
     *  • одновременно выполняется не более ОДНОГО сбора ([start] возвращает `false`, если занято);
     *  • после успеха ([Outcome.COMPLETED]) повторов нет, а все ранее запланированные задачи
     *    становятся no-op (проверка поколения);
     *  • после `PARTIAL`/`CHANNEL_NOT_READY` — не более [maxAttempts] попыток, каждая через
     *    [retryDelayMs]; после исчерпания — честный статус `exhausted`;
     *  • нет бесконечных циклов и утечек: число запланированных задач ограничено.
     *
     * Время и планировщик передаются снаружи ([post]), поэтому оркестрацию можно проверить
     * синтетическим временем без Android.
     */
    class DiagRunner(
        private val maxAttempts: Int = 3,
        private val retryDelayMs: Long = 20_000L,
        private val post: (name: String, delayMs: Long, task: () -> Unit) -> Unit
    ) {
        enum class Outcome { COMPLETED, PARTIAL, CHANNEL_NOT_READY, FAILED }

        private val lock = Any()
        private var generation = 0
        private var inFlight = false
        private var attempts = 0
        private var done = false
        private var exhausted = false
        private val notes = ArrayList<String>(32)

        fun completedNow(): Boolean = synchronized(lock) { done }
        fun attempts(): Int = synchronized(lock) { attempts }
        fun isExhausted(): Boolean = synchronized(lock) { exhausted }
        fun isBusy(): Boolean = synchronized(lock) { inFlight }
        fun notes(): List<String> = synchronized(lock) { notes.toList() }
        fun resetForTest() = synchronized(lock) {
            generation++; inFlight = false; attempts = 0; done = false; exhausted = false; notes.clear()
        }

        private fun note(s: String) = synchronized(lock) {
            if (notes.size > 48) notes.removeAt(0)
            notes.add(s)
        }

        /**
         * Запустить сбор.
         *
         * @return `false` — сбор не запущен (уже выполнен, идёт другой или попытки исчерпаны);
         *   причина видна в [notes].
         */
        fun start(
            reason: String,
            run: () -> Outcome
        ): Boolean {
            val gen: Int
            synchronized(lock) {
                if (done) { note("DIAG_ORCH reason=$reason status=skipped why=already_completed"); return false }
                if (inFlight) { note("DIAG_ORCH reason=$reason status=skipped why=in_flight"); return false }
                if (attempts >= maxAttempts) {
                    exhausted = true
                    note("DIAG_ORCH reason=$reason status=skipped why=attempts_exhausted " +
                        "attempts=$attempts/$maxAttempts (это ограничение, а не «данных нет»)")
                    return false
                }
                attempts++
                inFlight = true
                // Запоминаем тело попытки ЗДЕСЬ: повтор обязан выполнять ту же операцию.
                runRef = run
                gen = generation
                note("DIAG_ORCH reason=$reason status=started attempt=$attempts/$maxAttempts")
            }
            var outcome = Outcome.FAILED
            try {
                outcome = run()
            } catch (t: Throwable) {
                outcome = Outcome.FAILED
                note("DIAG_ORCH reason=$reason status=failed why=exception:${t.javaClass.simpleName}")
            } finally {
                synchronized(lock) { inFlight = false }
            }
            finish(reason, gen, outcome)
            return true
        }

        private fun finish(reason: String, gen: Int, outcome: Outcome) {
            synchronized(lock) {
                if (generation != gen) {
                    // Пока шёл сбор, состояние сброшено/сменилось — результат не применяем.
                    note("DIAG_ORCH reason=$reason status=stale_result outcome=${outcome.name.lowercase()}")
                    return
                }
                when (outcome) {
                    Outcome.COMPLETED -> {
                        done = true
                        // Устаревшие отложенные задачи (страховка «через 30 с», повторы) отменяются
                        // сменой поколения: они проверяют его перед запуском.
                        generation++
                        note("DIAG_ORCH reason=$reason status=completed attempt=$attempts/$maxAttempts " +
                            "pending_cancelled=true")
                    }
                    /**
                     * §4.2 iter44: `CHANNEL_NOT_READY` — это НЕ израсходованная попытка сбора:
                     * сбора не было вовсе (канал не готов). Иначе при недоступном канале бюджет
                     * выгорал холостыми заходами, и к моменту реальной готовности собирать было
                     * уже нечем. Счётчик возвращается, исчерпание не объявляется.
                     */
                    Outcome.CHANNEL_NOT_READY -> {
                        attempts = (attempts - 1).coerceAtLeast(0)
                        note("DIAG_ORCH reason=$reason status=wait_channel " +
                            "attempt=$attempts/$maxAttempts (попытка не израсходована: сбора не было)")
                    }
                    Outcome.PARTIAL -> {
                        if (attempts >= maxAttempts) {
                            exhausted = true
                            note("DIAG_ORCH reason=$reason status=exhausted " +
                                "outcome=${outcome.name.lowercase()} attempts=$attempts/$maxAttempts")
                        } else {
                            note("DIAG_ORCH reason=$reason status=retry_scheduled " +
                                "outcome=${outcome.name.lowercase()} delay_ms=$retryDelayMs " +
                                "attempt=$attempts/$maxAttempts")
                        }
                    }
                    Outcome.FAILED -> {
                        if (attempts >= maxAttempts) {
                            exhausted = true
                            note("DIAG_ORCH reason=$reason status=exhausted outcome=failed " +
                                "attempts=$attempts/$maxAttempts")
                        } else {
                            note("DIAG_ORCH reason=$reason status=retry_scheduled outcome=failed " +
                                "delay_ms=$retryDelayMs attempt=$attempts/$maxAttempts")
                        }
                    }
                }
            }
            if (outcome != Outcome.COMPLETED && outcome != Outcome.CHANNEL_NOT_READY &&
                attempts < maxAttempts) {
                val myGen = synchronized(lock) { generation }
                post("JitNet.diagRetry", retryDelayMs) {
                    val go = synchronized(lock) {
                        generation == myGen && !done && !inFlight && attempts < maxAttempts
                    }
                    // ВАЖНО: повтор получает ИСХОДНОЕ тело попытки, а не обёртку над собой —
                    // иначе `runRef` указывал бы на самого себя (StackOverflowError).
                    val original = synchronized(lock) { runRef }
                    if (go && original != null) {
                        start("retry_after_${outcome.name.lowercase()}", original)
                    }
                    else note("DIAG_ORCH reason=retry_after_${outcome.name.lowercase()} " +
                        "status=skipped why=stale_or_completed")
                }
            }
        }

        /**
         * Тело следующей попытки задаётся при первом [start]; храним его, чтобы повтор
         * выполнял ТУ ЖЕ операцию (без дублирования логики в вызывающем коде).
         */
        private var runRef: (() -> Outcome)? = null

        /** Запуск с запоминанием тела попытки (используется повторами). */
        fun startWithRetry(reason: String, run: () -> Outcome): Boolean = start(reason, run)

        /**
         * Запланировать «страховочную» проверку готовности канала: если сбор так и не удался,
         * через [delayMs] сообщается честный статус. После успешного сбора задача становится
         * no-op (проверка поколения и [done]).
         */
        fun scheduleNotReadyReport(
            reason: String,
            delayMs: Long,
            report: () -> Unit
        ) {
            val myGen = synchronized(lock) { generation }
            post("JitNet.diagNotReady", delayMs) {
                val go = synchronized(lock) { generation == myGen && !done }
                if (go) {
                    note("DIAG_ORCH reason=$reason status=not_ready_report fired=true")
                    report()
                } else {
                    note("DIAG_ORCH reason=$reason status=not_ready_report fired=false " +
                        "why=completed_or_stale")
                }
            }
        }
    }

    /**
     * iter43 §3.1 — КОНТРАКТ снимков «до/после включения TTL».
     *
     * Реальность: сбор снимка асинхронен и может занять секунды, а применение TTL выполняется
     * сразу — задерживать переключение тумблера на произвольное время недопустимо (это лишило бы
     * владельца управления). Поэтому «до» НЕ объявляется гарантированным состоянием до изменения:
     * каждая пара получает ЯВНУЮ метку по фактам времени:
     *
     *  • `pre_confirmed` — сбор «до» завершился РАНЬШЕ применения TTL;
     *  • `pre_overlap`   — сбор «до» пересёкся с применением (часть данных уже после изменения);
     *  • `pre_late`      — сбор «до» начался ПОСЛЕ применения: это не «до», а повторный снимок.
     *
     * Для «после» метка `post_after_apply` с ID операции, результатом применения и фактической
     * задержкой стабилизации. Отказ/пропуск диагностики НЕ меняет результат тумблера и не
     * задерживает его.
     */
    class TtlSnapshotFlow {
        /** Фаза снимка (не «машина состояний» JIT: только метка для отчёта). */
        enum class SnapPart { PRE, POST }
        data class Op(
            val id: String,
            val want: Boolean,
            val requestedAt: Long,
            var appliedAt: Long? = null,
            var appliedResult: String = "unknown",
            var preStartedAt: Long? = null,
            var preFinishedAt: Long? = null,
            var postScheduledAt: Long? = null,
            var postCollectedAt: Long? = null
        )

        private val lock = Any()
        private var current: Op? = null
        private var counter = 0

        fun begin(want: Boolean, nowMs: Long): Op = synchronized(lock) {
            counter++
            val op = Op("ttl-" + counter, want, nowMs)
            current = op
            op
        }

        /**
         * §3.2 iter44: все отметки привязаны к КОНКРЕТНОЙ операции. При быстром ON/OFF поздний
         * callback предыдущей операции не должен испортить следующую — проверяем `id` и
         * игнорируем чужой op (возвращаем `false`, чтобы вызывающий мог это записать в лог).
         */
        fun preStarted(op: Op, nowMs: Long): Boolean = applyTo(op) { it.preStartedAt = nowMs }
        fun preFinished(op: Op, nowMs: Long): Boolean = applyTo(op) { it.preFinishedAt = nowMs }
        fun applied(op: Op, nowMs: Long, result: String): Boolean = applyTo(op) {
            it.appliedAt = nowMs
            it.appliedResult = result
        }
        fun postScheduled(op: Op, nowMs: Long): Boolean = applyTo(op) { it.postScheduledAt = nowMs }
        fun postCollected(op: Op, nowMs: Long): Boolean = applyTo(op) { it.postCollectedAt = nowMs }

        /** Применить изменение ТОЛЬКО к текущей операции с тем же id. */
        private fun applyTo(op: Op, block: (Op) -> Unit): Boolean = synchronized(lock) {
            val cur = current
            if (cur == null || cur.id != op.id) return false
            block(cur)
            true
        }

        fun currentOp(): Op? = synchronized(lock) { current }

        /**
         * Метка фазы «до» — по ФАКТИЧЕСКИМ временам сбора, а не по времени заказа.
         *
         * §3.1 iter44: `pre_confirmed` НЕ выдаётся, пока сбор не завершён (`pre_unfinished`), и
         * `appliedAt == null` при завершённом сборе означает именно «применение ещё не начиналось»,
         * а не «мы уверены, что успели».
         */
        fun preLabel(op: Op): String {
            val started = op.preStartedAt
            if (started == null) return "pre_unknown"
            if (op.preFinishedAt == null) return "pre_unfinished"
            val applied = op.appliedAt ?: return "pre_confirmed"
            return when {
                started > applied -> "pre_late"
                (op.preFinishedAt ?: Long.MAX_VALUE) > applied -> "pre_overlap"
                else -> "pre_confirmed"
            }
        }

        /**
         * Метка «после». §3.2 iter44: `post_after_apply` означает только «снимок сделан после
         * применения», и НЕ выдаётся за успешное применение: результат применения виден отдельно
         * (`applied_result`), а при неуспехе метка так и говорит.
         */
        fun postLabel(op: Op): String {
            if (op.appliedAt == null) return "post_without_confirmed_apply"
            return when (op.appliedResult) {
                "ok" -> "post_after_apply"
                else -> "post_after_apply_not_ok:" + op.appliedResult
            }
        }

        fun describe(op: Op, part: SnapPart, snapshotLabel: String): String = when (part) {
            SnapPart.PRE -> "op=${op.id} want=${op.want} phase=pre label=${preLabel(op)} " +
                "requested_at=${op.requestedAt} applied_at=${op.appliedAt ?: "n/a"} " +
                "snapshot=$snapshotLabel"
            SnapPart.POST -> "op=${op.id} want=${op.want} phase=post label=${postLabel(op)} " +
                "applied_at=${op.appliedAt ?: "n/a"} applied_result=«${op.appliedResult}» " +
                "scheduled_at=${op.postScheduledAt ?: "n/a"} snapshot=$snapshotLabel"
        }

        fun resetForTest() = synchronized(lock) { current = null; counter = 0 }
    }

    class DiagOnce(private val maxRuns: Int = 2) {
        private val runs = java.util.concurrent.atomic.AtomicInteger(0)
        private val completed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** `null` — попытки исчерпаны; иначе номер попытки (1..[maxRuns]). */
        fun tryRun(): Int? {
            while (true) {
                val cur = runs.get()
                if (cur >= maxRuns) return null
                if (runs.compareAndSet(cur, cur + 1)) return cur + 1
            }
        }

        /** Отметить исход попытки: `ok` — оба сборщика завершились без сбоя при готовом канале. */
        fun finish(ok: Boolean): Boolean {
            if (ok) completed.set(true)
            return completed.get()
        }

        fun completedNow(): Boolean = completed.get()
        fun runsUsed(): Int = runs.get()
        fun maxRunsAllowed(): Int = maxRuns
        fun exhausted(): Boolean = runs.get() >= maxRuns

        fun status(): String = when {
            completed.get() -> "completed"
            exhausted() -> "exhausted"
            runs.get() > 0 -> "started"
            else -> "idle"
        }
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
     * §7.2 iter45: ГРАНИЦА ЖИЗНИ незавершённой операции.
     *
     * Проблема из лога 13.11: отметка `undefined` (её нормальный исход — наш процесс умирает
     * вместе с `ctl.restart zygote`, ответа физически быть не может) оставалась на диске
     * навсегда, и ВСЕ последующие клики блокировались (`blocked=chain_undefined`), а старый
     * снимок Wi-Fi участвовал в поздних запусках.
     *
     * 15 минут — обоснованная граница: дольше команда `setprop ctl.restart zygote` в очереди
     * транспорта не живёт (таймаут одной команды 8–40 с, максимум три попытки), и за это время
     * владелец успевает сам изменить Wi-Fi, поэтому «восстанавливать» старый снимок уже нельзя.
     * По истечении окна операция считается завершённой БЕЗ восстановления сети: блокировка
     * снимается только для НОВОГО пользовательского клика, автоматических рестартов нет.
     */
    private const val STALE_MARK_MS = 15 * 60_000L

    /** Возраст отметки операции (мс) или `null`, если её нет/время нечитаемо. */
    private fun markAgeMs(mark: JitRef.OpMark?): Long? {
        val ts = mark?.tsMs ?: return null
        if (ts <= 0L) return null
        return (System.currentTimeMillis() - ts).coerceAtLeast(0L)
    }

    /**
     * §7.2 iter46 — до какого возраста операции возврат имеет право ВКЛЮЧАТЬ Wi-Fi.
     *
     * Разница принципиальна: продолжение конкретного клика (процесс перезапустил `zygote`,
     * задача возврата стартует через [JitRef.RESTORE_DELAY_MS]) возвращает исходный Wi-Fi,
     * включая `setWifiEnabled(true)`. Но если приложение стартовало САМО и поздно (владелец
     * открыл его через полчаса, система подняла процесс из-за другого повода), то включать
     * Wi-Fi по старому снимку нельзя: за это время владелец мог выключить его сам, и мы бы
     * «вернули» сеть против его воли. Такой возврат выполняется в режиме «не включать».
     */
    internal const val WIFI_ENABLE_MAX_AGE_MS = 5 * 60_000L

    /**
     * §7.2: можно ли этому возврату включать Wi-Fi. Чистая функция (без Android) — проверяется
     * JVM-тестом. Возвращает `allowed` и причину для журнала.
     *
     * Защиты: нет отметки; `ts` нечитаем/ноль; `ts` в будущем (коррекция часов назад) — такое
     * время не доказательство свежести; возраст больше [WIFI_ENABLE_MAX_AGE_MS].
     */
    fun restoreMayEnableWifi(
        mark: JitRef.OpMark?,
        nowMs: Long,
        limitMs: Long = WIFI_ENABLE_MAX_AGE_MS
    ): Pair<Boolean, String> {
        val ts = mark?.tsMs ?: return false to "mark_absent"
        if (ts <= 0L) return false to "mark_ts_unreadable"
        if (ts > nowMs) return false to "clock_shift_back"
        val age = nowMs - ts
        if (age > limitMs) return false to "late_start:${age}>${limitMs}"
        return true to "click_continuation:${age}"
    }

    /**
     * §7.3 iter47 / §Д iter48 — признак «текущий zygote стартовал ПОСЛЕ отметки операции».
     *
     * Что было не так в iter47: допуск `zs >= ts - tolMs` с `tolMs = 60_000` позволял засчитать
     * как «наш рестарт» процесс, стартовавший ДО команды (в пределах минуты): перенос команды
     * через перезагрузку, очередь транспорта и переиспользование pid при таком допуске не
     * отличались от выполнения. Допуск убран: требуется СТРОГО `zs > ts`.
     *
     * Что этот признак доказывает: процесс zygote, работающий сейчас, стартовал позже отметки.
     * Чего НЕ доказывает (и не должно заявляться):
     *  • что команду выполнили ИМЕННО МЫ (zygote мог перезапуститься сам или упасть);
     *  • что JIT-компиляция произошла;
     *  • что команда не была отброшена и заменена чужим рестартом.
     * Поэтому результат используется ТОЛЬКО для снятия блокировки нового клика владельца, а не
     * как «успех JIT».
     *
     * Ограничения: нечитаемое время отметки/zygote, коррекция часов назад, отметка старше
     * [STALE_MARK_MS] — доказательством не считаются.
     */
    fun zygoteRestartEvidence(
        mark: JitRef.OpMark?,
        zygoteStartMs: Long?,
        nowMs: Long
    ): Boolean {
        val ts = mark?.tsMs ?: return false
        val zs = zygoteStartMs ?: return false
        if (ts <= 0L || zs <= 0L) return false
        // Отметка/старт «из будущего» — часы сдвинуты, порядок не доказан.
        if (ts > nowMs) return false
        if (zs > nowMs) return false
        // Отметка старше окна: команда уже не может быть в очереди, а «поздний» старт zygote
        // мог быть вызван чем угодно — доказательством не считаем.
        if (nowMs - ts > STALE_MARK_MS) return false
        return zs > ts
    }

    /** §7.2: отметка операции пережила окно (правило — [Toggles45.isStale]). */
    private fun opExpired(mark: JitRef.OpMark?): Boolean =
        Toggles45.isStale(mark?.tsMs, System.currentTimeMillis(), STALE_MARK_MS)

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

    /**
     * iter49 §7: id операции, неопределённость которой владелец закрыл выходом (окно/zygote).
     * Служит только для диагностики и для сверки позднего ответа: ответ приходит с id, и
     * [onLateOutcome] сверяет его с сохранённой отметкой, поэтому «приписать» его новой
     * операции нельзя даже при совпадении текста.
     */
    private val ABANDONED_OP = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** id последней оставленной операции (`null` — таких не было). Только чтение, для журнала. */
    fun lastAbandonedOp(): String? = ABANDONED_OP.get()

    /** Сброс процессного состояния. Нужен только тестам (в бою это поля на жизнь процесса). */
    @Synchronized
    internal fun resetStateForTest() {
        CLICK_SLOT.set(null)
        PEDIDO_GEN.set(0)
        CHAIN_UNDEFINED.set(false)
        ABANDONED_OP.set(null)
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
        log: (String) -> Unit = {},
        /**
         * iter67 §E.4 — планировщик отложенного перечитывания terminal (через 5 с после
         * `JIT_CHAIN_SENT`). `null` — планировщика нет (JVM-тест): перечитывание выполняется сразу
         * помеченным `attempt=immediate`, чтобы факт не терялся.
         */
        scheduler: JitRef.JitScheduler? = null
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
                // iter67 §K: разошедшиеся копии ОДНОЙ операции согласуются здесь же — «хуже» из
                // двух (SENDING/UNDEFINED) записывается в ОБА хранилища ОДНИМ `commit()` на
                // хранилище. Это не выдаёт неизвестный исход за известный: состояние остаётся
                // `sending`/`undefined`, а возраст считается от самой свежей копии, поэтому
                // следующее решение (правки C/H) снова имеет вход, а не молчит вечно.
                val repairMark = stored.mark
                if (repairMark != null && stored.detail.contains("repair=rewrite_worse")) {
                    val w = runCatching { store.updateMark(repairMark) }
                        .getOrElse { t -> JitRef.WriteResult.none("exception:" + t.javaClass.simpleName) }
                    log(
                        "JIT_PAIR_REPAIR op=${repairMark.op} attempt=${repairMark.attempt} " +
                            "stores=${w.ok}/${w.attempted} detail=«${w.detail}» " +
                            "state=${repairMark.state} ts_ms=${repairMark.tsMs} " +
                            "(копии одной операции приведены к «худшему» состоянию: исход не " +
                            "выдаётся за известный, но бессрочной блокировки больше нет)"
                    )
                }
            }
            // §7.2 iter45: за границей STALE_MARK_MS неопределённость больше не блокирует НОВЫЙ
            // клик владельца: команда уже не может быть в очереди, а старый снимок не применяется
            // (см. `staleOp` ниже). Автоматики нет — только пользовательский клик.
            val age = markAgeMs(stored.mark)
            // iter67 §H.2: невалидное время отметки — ЭТО ДОКАЗАННЫЙ РАЗРЫВ ЦЕПОЧКИ, отдельная
            // строка и разрешённый клик. Живой случай: `ts=-196980728` давал `isStale == false`,
            // окно не истекало никогда, блокировка держалась 6 суток.
            run {
                val ts = stored.mark?.tsMs
                if (ts != null) {
                    val verdict = JitUnblock.classifyMark(ts, System.currentTimeMillis())
                    if (verdict != MarkVerdict.OK) {
                        log(
                            "JIT_MARK_INVALID op=${stored.mark?.op} ts_ms=$ts class=${verdict.name.lowercase()} " +
                                "exit=mark_invalid_${verdict.name.lowercase()} " +
                                "clicks=allowed snapshot_applied=no " +
                                "(время отметки не может быть настоящим: возраст не вычисляется, " +
                                "держать владельца дальше нельзя; снимок Wi-Fi этим НЕ применяется " +
                                "и НЕ удаляется — возврат сети решает отдельное правило)"
                        )
                    }
                }
            }
            // §7.3 iter47: сначала ПРОВЕРЯЕМЫЙ признак (перезапуск zygote после отметки),
            // и только потом — временное окно как резервная граница.
            val zygoteStart = runCatching { netState.zygoteStartMs() }.getOrNull()
            val evidenceOp = stored.state == JitRef.StoredState.UNDEFINED &&
                zygoteRestartEvidence(stored.mark, zygoteStart, System.currentTimeMillis())
            if (evidenceOp) {
                log(
                    "JIT_UNDEFINED_RESOLVED op=${stored.mark?.op} evidence=zygote_started_after_mark " +
                        "mark_ts_ms=${stored.mark?.tsMs} zygote_start=$zygoteStart age_ms=$age " +
                        "wifi_restore=none clicks=allowed " +
                        "(текущий zygote СТАРТОВАЛ ПОСЛЕ отметки операции — строгое сравнение без " +
                        "допуска. Это НЕ доказывает, что рестарт выполнила наша команда (zygote мог " +
                        "перезапуститься сам/упасть), НЕ доказывает JIT-компиляцию и НЕ доказывает, " +
                        "что наша команда уже не выполнится позже: поздний ответ остаётся " +
                        "возможным и будет помечен как late_answer. Автоматического запуска нет — " +
                        "решает владелец)"
                )
            }
            // Разблокировка: либо проверяемый признак, либо (резерв) истечение окна.
            val staleOp = stored.state == JitRef.StoredState.UNDEFINED &&
                (evidenceOp || opExpired(stored.mark))
            if (staleOp) {
                // iter49 §7: РАЗБЛОКИРОВКА ≠ ДОКАЗАТЕЛЬСТВО. Раньше здесь было написано, что
                // «команда не может оставаться в очереди». Это недоказуемо: доставка/очередь
                // root-шелла может задержаться и за окно, а старт zygote после отметки не
                // доказывает ни выполнения нашей команды, ни невозможности её выполнения позже.
                // Поэтому:
                //  • клик РАЗРЕШАЕТСЯ (бессрочный запрет запрещён мандатом и вреден владельцу);
                //  • старый снимок НЕ применяется и не перезаписывается (это было и остаётся);
                //  • выход именуется явно, а возможный поздний ответ попадает в журнал как
                //    `late_answer` и НЕ может быть приписан новой операции (проверка идёт по
                //    id операции в [onLateOutcome]).
                val exit = if (evidenceOp) "zygote_after_mark" else "window_expired"
                ABANDONED_OP.set(stored.mark?.op)
                CHAIN_UNDEFINED.set(false)
                log(
                    "JIT_OP_ABANDONED op=${stored.mark?.op} exit=$exit age_ms=$age " +
                        "limit_ms=$STALE_MARK_MS late_answer_possible=1 wifi_restore=none " +
                        "clicks=allowed snapshot_reused=no " +
                        "(исход прошлой команды остаётся НЕДОКАЗАННЫМ: и ${
                            if (evidenceOp) "старт zygote после отметки" else "истечение окна"
                        } не доказывают, что команда не выполнится позже, и не доказывают успех " +
                        "JIT. Это выход владельца из неопределённости, а не свидетельство " +
                        "завершения: старый снимок не применяется, а ответ старой операции " +
                        "(id=${stored.mark?.op}) не будет приписан новой)"
                )
            }
            // §7 iter51: ПУТЬ ВЫХОДА ИЗ НЕИЗВЕСТНОСТИ. Пока исход прошлой операции не доказан,
            // читаем её terminal-файл (короткая команда, не payload): `END rc=…` или `JIT_REFUSED`
            // доказывают, что ЭТА операция уже закончилась. Это снимает блокировку без рестарта
            // zygote, без reboot и без «подождать окно»; старт zygote и новый pid сюда не входят.
            val prevOpId = stored.mark?.op
            val prevTerm = if (prevOpId != null &&
                (!staleOp || CHAIN_UNDEFINED.get() || stored.state == JitRef.StoredState.UNDEFINED)
            ) probeTerminal(shell, prevOpId, log, stored.mark?.inst) else null
            val prevResolved = prevTerm != null && (prevTerm.finished || prevTerm.refused)
            if (prevResolved && prevOpId != null) {
                val dec = JitOwn.clickDecision(
                    JitOwn.Op(prevOpId, stored.mark?.tsMs ?: 0L, stored.mark?.want ?: false,
                        JitOwn.State.UNKNOWN),
                    prevTerm
                )
                CHAIN_UNDEFINED.set(false)
                log(
                    "JIT_OP_RESOLVED op=$prevOpId evidence=terminal " +
                        "term=«${prevTerm!!.wire()}» risk=${dec.risk} " +
                        "(исход прошлой команды доказан ЕЁ ЖЕ terminal-файлом: ${
                            if (prevTerm.finished) "payload завершился" else "payload отказался работать"
                        }; снимок прошлой операции НЕ применяется и НЕ перезаписывается, " +
                        "JIT-компиляция этим НЕ доказана)"
                )
            }

            // iter67 §C: решение о разблокировке — ВСЕГДА строкой `JIT_UNBLOCK` с явным `exit=`.
            // До правки выход был, а строки не было (`JIT_OP_ABANDONED = 0` при 426 `JIT_PEDIDO kept`).
            runCatching {
                val dec = JitUnblock.decide(
                    markTsMs = stored.mark?.tsMs,
                    markBoot = stored.mark?.boot,
                    currentBootId = JitUnblock.currentBootId(),
                    nowMs = System.currentTimeMillis(),
                    limitMs = STALE_MARK_MS,
                    zygoteStartMs = zygoteStart,
                    terminalFinished = prevResolved
                )
                if (dec.exit != "none" || stored.state == JitRef.StoredState.UNDEFINED) {
                    log(dec.line(stored.mark?.op ?: "none", STALE_MARK_MS,
                        JitUnblock.bootSame(stored.mark?.boot, JitUnblock.currentBootId())))
                }
            }
            val blockedReason = when {
                // Неопределённость в RAM — то же состояние, что и в отметке; порядок важен.
                (!staleOp && CHAIN_UNDEFINED.get() && !prevResolved) ||
                    (stored.state == JitRef.StoredState.UNDEFINED && !staleOp && !prevResolved) ->
                    "chain_undefined"
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
            // iter56 §4.3: ПОРЯДОК НАМЕРЕНИЙ — монотонное поколение, выданное и подтверждённое
            // чтением ДО записи снимка/claim. Время (часы) порядок больше не задаёт: оно может
            // быть переведено назад, совпасть у двух кликов и ничего не говорит после restart.
            var seqNote: String? = null
            val intentSeq = runCatching { store.bumpIntentSeq() }.getOrNull() ?: 0L
            runCatching { seqNote = (store as? JitNet.AndroidStore)?.lastSeqNote }.getOrNull()
            log(
                "JIT_INTENT_SEQ op=$opId seq=$intentSeq " +
                    "rule=${if (intentSeq > 0L) "seq" else "ts_legacy"} " +
                    "issue=${seqNote ?: "unreported"} " +
                    if (intentSeq > 0L)
                        "(поколение намерения записано и подтверждено чтением ДО отправки)"
                    else
                        "(поколение намерения выдать не удалось: порядок сравнивается по времени " +
                            "с явным правилом ts_legacy — это слабее seq, и так и названо)"
            )
            // iter56 §5: ЭКЗЕМПЛЯР исполнения известен ДО записи снимка/claim/отправки payload.
            // Он попадает в durable-отметку (переживает перезапуск процесса), в claim и в гвардию,
            // поэтому прочитанный позже terminal можно привязать к КОНКРЕТНОМУ исполнению, а не к
            // opId, который переиспользуется повторными кликами.
            val clickTs = System.currentTimeMillis()
            val opInst = JitOwn.newInstance(opId, clickTs)
            val mark = JitRef.OpMark(
                opId, clickTs, want, JitRef.OpMark.SENDING, seq = intentSeq, inst = opInst
            )
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
            // iter67 §I.1: «сеть выключили МЫ». Флаг ставится ДО отправки цепочки: скрипт
            // начинается с `svc wifi disable`, и если он умрёт на `umount`/`setprop`, вернуть сеть
            // будет нечем, кроме этого флага.
            if (want && snap.wifi) {
                val flagOk = runCatching { store.putBool(JitNetDecision.KEY_NET_CUT_BY_US, true) }
                    .getOrDefault(false)
                log(
                    "JIT_WE_CUT_NET op=$opId value=1 confirmed=${if (flagOk) 1 else 0} " +
                        "(скрипт выключает клиентский Wi-Fi: возврат сети больше не зависит от " +
                        "возраста отметки и от наличия claim; флаг снимется только после " +
                        "подтверждённого включения сети)"
                )
            }
            log(
                "JIT_CLICK want=$want op=$opId restore=${snap.wire()} attempted=${write.attempted} " +
                    "committed=${write.committed} confirmed=${write.confirmed} " +
                    "stores=«${write.detail}» read=${read.value ?: "none"} " +
                    "conflicts=${read.conflicts.ifEmpty { listOf("none") }.joinToString(",")} " +
                    "previous=«${briefPrev(prev)}» " +
                    "(снимок ДО любых изменений; формат wifi_only — AP не снимается и не поднимается)"
            )
            // §5 iter42: набор CPS/DPS мог измениться МЕЖДУ предварительным чтением и записью
            // (провайдер вернул null/бросил, хранилище «пропало»). Раньше это логировалось
            // (`store_absent`), но отправку не блокировало — снимок мог оказаться не там, где его
            // прочитает продолжение. Теперь fail closed: набор обязан совпасть.
            val readNames = prev.map { it.name }.toSet()
            val writtenNames = write.stores.map { it.name }.toSet()
            // §1.2 iter44: `appeared`/`absent` — ПОЯВИВШАЯСЯ или ПРОПАВШАЯ копия. Раньше они лишь
            // писались в лог, а решение строилось по именам `prev` и `write.stores`: появившаяся
            // копия в `stores` не попадает (её прежнее состояние неизвестно), наборы «сходились»,
            // и скрипт уходил. Теперь это блокирует.
            val setDiff = readNames != writtenNames ||
                write.appeared.isNotEmpty() || write.absent.isNotEmpty()
            val idChanged = JitRef.identityChanged(prev, write)
            val idUnprovable = JitRef.identityUnprovable(prev, write)
            if (idUnprovable.isNotEmpty()) {
                log(
                    "JIT_CLICK want=$want op=$opId identity_unprovable=" +
                        "${idUnprovable.joinToString(",")} (тождество копии доказать нельзя: " +
                        "сравнение идентичностей недоступно)"
                )
            }
            val storeSetChanged = setDiff || idChanged.isNotEmpty()
            val blocked = when {
                storeSetChanged -> "store_set_changed"
                write.attempted == 0 -> "snapshot_not_saved"
                write.confirmed == 0 -> "snapshot_not_saved"
                !write.firstReadable -> "snapshot_not_saved"
                read.conflicts.isNotEmpty() -> "snapshot_conflict"
                read.value != snap.wire() -> "snapshot_not_readable"
                else -> null
            }
            if (blocked != null) {
                val why = when (blocked) {
                    "store_set_changed" ->
                        "состав/тождество хранилищ CPS/DPS изменились между чтением прежних " +
                            "значений и записью снимка (appeared=" +
                            "${write.appeared.ifEmpty { listOf("none") }}, absent=" +
                            "${write.absent.ifEmpty { listOf("none") }}, identity_changed=" +
                            "${idChanged.ifEmpty { listOf("none") }}): где снимок прочитает " +
                            "продолжение — неизвестно, поэтому не отправляем (fail closed)"
                    "snapshot_not_saved" ->
                        "ни одно хранилище не подтвердило запись снимка чтением: продолжение " +
                            "прочитает прежнее состояние, значит отправлять нельзя"
                    "snapshot_conflict" ->
                        "хранилища расходятся по ключам операции: какое состояние прочитает " +
                            "продолжение — неизвестно"
                    "snapshot_not_readable" ->
                        "чтение после записи вернуло не наш снимок: отправка запрещена"
                    else -> "состояние снимка недостоверно"
                }
                if (write.confirmed > 0 && !write.firstReadable) {
                    log(
                        "JIT_CLICK snapshot_order=first_store_not_durable " +
                            "stores=«${write.detail}» (запись есть не во всех хранилищах: после " +
                            "перезапуска продолжение прочитало бы прежнее значение из первого по " +
                            "порядку чтения — снимок неоднозначен, поэтому не отправляем)"
                    )
                }
                // §1.3 iter44: возврат ТОЛЬКО по доказанной принадлежности. Читаем пару
                // «снимок + отметка» по каждому хранилищу и возвращаем прежние значения лишь там,
                // где лежит НАША отметка И НАШ снимок. Чужое/нечитаемое не трогаем.
                val ops = runCatching { store.readOperation() }.getOrDefault(emptyList())
                val plan = JitRef.planRestore(prev, ops, opId, snap)
                val rb = if (plan.owned.isEmpty()) {
                    JitRef.WriteResult.none("nothing_proven_owned")
                } else synchronized(INTENT_LOCK) {
                    runCatching { store.restorePrevious(plan.owned) }
                        .getOrElse { t -> JitRef.WriteResult.none("exception:" + t.javaClass.simpleName) }
                }
                val leftPedido = runCatching { store.contains(JitRef.KEY_PEDIDO) }.getOrDefault(false)
                val leftRestore = runCatching { store.getString(JitRef.KEY_RESTORE) }.getOrNull()
                // §1.3 iter44: если запись БЫЛА, писать «ни записи» нельзя — показываем факт.
                // Запись считается состоявшейся, если её подтвердил commit/readback ИЛИ если
                // значение реально читается из хранилища (left_pedido) — именно так выглядит
                // `commit=false` при `writeDespiteFalse`: данные есть, гарантии записи нет.
                // Запись считается состоявшейся, если её подтвердил commit/readback ИЛИ если
                // ПОСЛЕ записи (до возврата) значение читалось как наше: именно так выглядит
                // `commit=false` при фактической записи — данные есть, гарантии записи нет.
                // Смотреть `left_pedido` ПОСЛЕ возврата нельзя: он уже снят возвратом.
                val wroteAny = write.confirmed > 0 || write.committed > 0 ||
                    read.value == snap.wire()
                log(
                    "JIT_CLICK want=$want op=$opId blocked=$blocked confirmed=${write.confirmed} " +
                        "committed=${write.committed} read=${read.value ?: "none"} " +
                        "wrote_any=$wroteAny prev_restore=${rb.ok}/${rb.attempted} " +
                        "restore_plan=«${plan.brief()}» prev_restore_detail=«${rb.detail}» " +
                        "left_pedido=$leftPedido left_restore=${leftRestore ?: "none"} " +
                        "action=none commands=0 " +
                        "(shell НЕ отправлен: $why. Сеть и системные свойства НЕ менялись. " +
                        (if (wroteAny)
                            "Настройки операции БЫЛИ записаны и возвращаются только там, где " +
                                "принадлежность доказана — это не «ни записи», см. prev_restore=/left_*. "
                         else
                            "Ни одно хранилище не подтвердило запись настроек операции. ") +
                        (if (!plan.complete)
                            "ЧАСТИЧНЫЙ ВОЗВРАТ: «${plan.brief()}» не тронуты, и «прежние значения " +
                                "восстановлены» не заявляется. "
                         else "") +
                        "commit=false сам по себе не доказывает отсутствия значения в памяти)"
                )
                return Click(want, snap, write, read, -1, "", blocked, previousUndefined)
            }

            /**
             * iter53 §5.2: ОТКАТ НАМЕРЕНИЯ, когда shell не отправлен по решению ВЛАДЕНИЯ
             * (`op_in_flight` / `claim_not_confirmed`). Возврат выполняется по доказанной
             * принадлежности (та же функция, что и в ветке `blocked`) — чужое не трогаем.
             */
            fun abortIntent(code: String, why: String): Click {
                val ops = runCatching { store.readOperation() }.getOrDefault(emptyList())
                val plan = JitRef.planRestore(prev, ops, opId, snap)
                val rb = if (plan.owned.isEmpty()) {
                    JitRef.WriteResult.none("nothing_proven_owned")
                } else synchronized(INTENT_LOCK) {
                    runCatching { store.restorePrevious(plan.owned) }
                        .getOrElse { t -> JitRef.WriteResult.none("exception:" + t.javaClass.simpleName) }
                }
                val leftPedido = runCatching { store.contains(JitRef.KEY_PEDIDO) }.getOrDefault(false)
                val leftRestore = runCatching { store.getString(JitRef.KEY_RESTORE) }.getOrNull()
                log(
                    "JIT_CLICK want=$want op=$opId blocked=$code prev_restore=${rb.ok}/${rb.attempted} " +
                        "restore_plan=«${plan.brief()}» left_pedido=$leftPedido " +
                        "left_restore=${leftRestore ?: "none"} action=none commands=0 " +
                        "($why. Сеть, свойства и zygote НЕ менялись)"
                )
                return Click(want, snap, write, read, -1, "", code, previousUndefined, 1, stored)
            }
            // §7 iter51 + iter53 §5.2: ЗАХВАТ ВЛАДЕНИЯ до любых изменений настроек, и он же
            // сериализован на устройстве: claim пишется ПОД ТЕМ ЖЕ lock, что держит payload.
            //  • `busy` — владелец lock жив (или его смерть не доказуема): payload НЕ отправляется,
            //    потому что второй опасный шаг (svc wifi / setprop / ctl.restart zygote) не должен
            //    идти конкурентно. Владельцу показывается id операции, которая ещё идёт;
            //  • `taken` — владение наше, цепочка отправляется;
            //  • `failed` — запись/чтение claim не подтверждены (канал, права). Payload всё равно
            //    отправлять нельзя «вслепую»: его гвардия прочитает claim и, если он не наш,
            //    откажется работать — то есть клик гарантированно ничего не сделает, а мы бы
            //    записали «отправлено». Поэтому клик останавливается здесь, честно.
            // iter54 §4: ОДИН экземпляр на клик — тот же `opInst`, что записан в отметку выше:
            // его видят и запись claim, и гвардия payload, и чтение terminal.
            val opState = JitOwn.Op(
                opId, mark.tsMs, want, JitOwn.State.CREATED,
                identity = JitOwn.ClaimIdentity(opInst, "", "", ""),
                seq = intentSeq
            )
            // iter67c §1: ВЫБОР ПУТИ ДОСТАВКИ. Текст claim собирается из общих shell-функций
            // владения и по мере роста защит (iter53…iter67) давно перестал влезать в один
            // ADB-пакет: 20.11.2026 он весил 44 454 байта, транспорт ответил `ERR_ADB_TOO_LONG`,
            // и клик был заблокирован на гейте владения. Размер текста — свойство СБОРКИ
            // команды, а не намерения владельца, поэтому длинный текст доставляется файлом, как
            // payload. Порог ниже 4096: `wrapForRc`, `shell:` и `\u0000` тоже занимают байты.
            val claimCmd = JitOwn.claimCmd(opState)
            val claimTextBytes = claimCmd.toByteArray(Charsets.UTF_8).size
            // Решение — по ФАКТИЧЕСКОМУ размеру пакета, который посчитает транспорт
            // (`wrapForRc` + `shell:` + `\u0000`), а не по «на глазок» добавленным 40 байтам.
            val claimBytes = runCatching { AdbClient.inlinePayloadBytes(claimCmd) }
                .getOrDefault(claimTextBytes + 48)
            val claimInline = JitOwn.deliveryPath(claimBytes) == "inline"
            log(
                "JIT_CLAIM_DELIVERY op=$opId path=${if (claimInline) "inline" else "script"} " +
                    "bytes=$claimBytes text_bytes=$claimTextBytes " +
                    "limit_inline=${JitOwn.INLINE_SAFE_BYTES} " +
                    "(без этой строки нельзя отличить «не отправлено» от «отправлено другим путём»)"
            )
            val claimT0 = System.currentTimeMillis()
            val claimRes = runCatching {
                if (claimInline) shell.exec(claimCmd, 12_000)
                else shell.execScript("jit_claim_$opId", claimCmd, 15_000)
            }.getOrElse { t -> Pair(-1, "ERR_${'$'}{t.javaClass.simpleName}") }
            // iter67c §4: таймаут ожидания админ-секции (или транспорта) — отдельная строка, чтобы
            // в журнале он не сливался с «занято живым владельцем» (busy_alive).
            run {
                val adm = Regex("CL_ADMIN_REFUSE=busy_timeout iters=(\\d+)").find(claimRes.second)
                val transportTimeout = claimRes.second.contains("ERR_ADB_TIMEOUT")
                if (adm != null || transportTimeout) {
                    log(
                        "JIT_CLAIM_TIMEOUT op=$opId waited_ms=${System.currentTimeMillis() - claimT0} " +
                            "iters=${adm?.groupValues?.get(1) ?: "unknown"} " +
                            "source=${if (adm != null) "admin_section" else "transport"} " +
                            "timeout_ms=${if (claimInline) 12_000 else 15_000} action=stop " +
                            "(claim не дождался сериализации — payload НЕ отправлен; повтор кликом)"
                    )
                }
            }
            val claimWrite = JitOwn.claimResult(claimRes.first, claimRes.second)
            // iter54 §4: ОДНО решающее правило для всех исходов захвата владения. Ни один
            // исход, кроме `Taken`, не разрешает отправку payload.
            val gate = JitOwn.claimGate(claimWrite)
            when (val cw = claimWrite) {
                is JitOwn.ClaimWrite.Taken -> log(
                    "JIT_CLAIM op=$opId taken=1 verified=1 path=${JitOwn.CLAIM} want=$want " +
                        "claim=«${cw.line}» " +
                        "(владение записано ПОД lock ДО изменения настроек; устаревший payload " +
                        "теперь откажется работать сам — см. JIT_REFUSED)"
                )
                is JitOwn.ClaimWrite.Busy -> {
                    val legacy = !cw.owner.contains("pid=")
                    log(
                        "JIT_CLAIM op=$opId taken=0 busy=1 owner=«${cw.owner}» " +
                            "path=${JitOwn.CLAIM} action=none " +
                            "(на устройстве уже исполняется другая JIT-операция либо её смерть " +
                            "не доказана: второй опасный шаг конкурентно НЕ запускается, снимок " +
                            "не применяется; повторный клик возможен, когда владелец завершится " +
                            "и напишет свой END — время само по себе блокировку не снимает)"
                    )
                    return abortIntent(
                        if (legacy) "lock_unverified" else "op_in_flight",
                        "на устройстве владеет другая JIT-операция «${'$'}{cw.owner}» и её " +
                            "завершение не доказано: второй опасный шаг конкурентно не запускается. " +
                            "Payload НЕ отправлен; окончание владельца (его END) откроет путь к " +
                            "следующему ручному клику — время блокировку не снимает"
                    )
                }
                is JitOwn.ClaimWrite.Stale -> {
                    log(
                        "JIT_CLAIM op=$opId taken=0 stale=1 newer=«${cw.newer}» " +
                            "path=${JitOwn.CLAIM} action=stop " +
                            "(на устройстве уже лежит БОЛЕЕ НОВОЕ намерение владельца: этот, более " +
                            "старый, клик его не отменяет. Payload НЕ отправлен, настройки " +
                            "возвращены по доказанной принадлежности — «${gate.why}»)"
                    )
                    return abortIntent(gate.code, gate.why)
                }
                is JitOwn.ClaimWrite.TooLong -> {
                    // iter67c §3: отказ по размеру называется своими словами и своим кодом.
                    // Молчаливый `claim_unproven` отправлял владельца искать причину в устройстве.
                    log(
                        "JIT_CLAIM op=$opId taken=0 too_long=1 bytes=${cw.bytes} limit=${cw.limit} " +
                            "path=${JitOwn.CLAIM} action=stop pending=1 commands=0 " +
                            "(команда claim не отправлена: ${cw.bytes} > ${cw.limit}. Это ошибка " +
                            "сборки команды, а не состояние устройства: ни один байт скрипта не ушёл, " +
                            "сеть, свойства и zygote НЕ менялись)"
                    )
                    return abortIntent("claim_cmd_too_long", gate.why)
                }
                is JitOwn.ClaimWrite.Failed -> {
                    // iter54 §4: раньше здесь стояло `action=proceed`, и скрипт всё равно
                    // уходил на устройство. Границы записи намерения нет — значит нет и fencing:
                    // отправлять payload нельзя. Намерение владельца НЕ стирается и НЕ
                    // переписывается: это честный pending, повтор возможен после восстановления
                    // канала (он же — единственный способ установить владение).
                    log(
                        "JIT_CLAIM op=$opId taken=0 verified=0 failed=«${cw.why}» rc=${claimRes.first} " +
                            "path=${JitOwn.CLAIM} action=stop pending=1 commands=0 " +
                            "(запись/чтение claim НЕ подтверждены — владение не установлено, " +
                            "payload НЕ отправлен: «${gate.why}». Настройки операции остаются " +
                            "записанными, потому что это намерение владельца; эффекта на сеть, " +
                            "свойства и zygote НЕТ — ни один байт скрипта не ушёл)"
                    )
                    return Click(
                        want, snap, write, read, -1, "", gate.code, previousUndefined,
                        attempt = 1, stored = stored
                    )
                }
            }
            // Страховка: любой исход, кроме `Taken`, не имеет права дойти до отправки.
            if (!gate.send) {
                log(
                    "JIT_CLAIM_GATE op=$opId send=0 code=${gate.code} commands=0 " +
                        "(«${gate.why}»: payload не отправлен)"
                )
                return Click(
                    want, snap, write, read, -1, "", gate.code, previousUndefined,
                    attempt = 1, stored = stored
                )
            }
            // iter67 §C.1: НОВАЯ ПОПЫТКА. `ts` отметки = время ЭТОЙ попытки (иначе возраст считался
            // от первой и окно не наступало никогда), номер попытки +1, загрузка записана.
            // Вызывается ровно один раз и ДО `JIT_CHAIN_SENT`; пишется в ОБА хранилища.
            var sentMark = mark
            runCatching {
                val now = System.currentTimeMillis()
                val (ts, att) = JitUnblock.nextAttempt(mark.tsMs, mark.attempt, now)
                sentMark = mark.copy(tsMs = ts, attempt = att, state = JitRef.OpMark.SENDING,
                    boot = JitUnblock.currentBootId())
                val putRes = store.updateMark(sentMark)
                log(
                    "JIT_ATTEMPT op=$opId attempt=${sentMark.attempt} ts_ms=${sentMark.tsMs} " +
                        "boot=${sentMark.boot ?: "unknown"} stores=${putRes.ok}/${putRes.attempted} " +
                        "detail=«${putRes.detail}» " +
                        "(отметка обновлена ДО отправки: возраст следующего решения считается от " +
                        "этой попытки, а не от первой)"
                )
            }
            log("dalvik.vm.usejit -> $want; reiniciando o framework")
            log(
                "JIT_CHAIN_SENT op=$opId ${JitRef.mountStepNote()} timeout_ms=${JitRef.CHAIN_TIMEOUT_MS} " +
                    "attempt=${sentMark.attempt} ts_ms=${sentMark.tsMs} " +
                    "restart=not_claimed command=w0/la.run (литерал референса)"
            )
            // iter67c (та же болезнь, что §1): цепочка = литерал референса + гвардия с helper'ами,
            // и даже после §2 это ~15 КБ при лимите пакета 4 096. Через `exec` она получала бы
            // ERR_ADB_TOO_LONG сразу после успешного claim. Решение о пути — явное и с той же
            // строкой в журнале, что у claim; сам текст цепочки не меняется ни на байт.
            val chainCmd = JitRef.chainScript(want, JitOwn.guard(opState), JitOwn.termPath(opId))
            val chainBytes = runCatching { AdbClient.inlinePayloadBytes(chainCmd) }
                .getOrDefault(chainCmd.toByteArray(Charsets.UTF_8).size + 48)
            val chainInline = JitOwn.deliveryPath(chainBytes) == "inline"
            log(
                "JIT_CHAIN_DELIVERY op=$opId path=${if (chainInline) "inline" else "script"} " +
                    "bytes=$chainBytes limit_inline=${JitOwn.INLINE_SAFE_BYTES}" +
                    (if (chainInline) "" else
                        " (цепочка не влезает в один ADB-пакет — доставляется файлом, как payload)")
            )
            val (rc, out) = if (chainInline) {
                shell.exec(chainCmd, JitRef.CHAIN_TIMEOUT_MS)
            } else {
                shell.execScript("jit_chain_$opId", chainCmd, JitRef.CHAIN_TIMEOUT_MS)
            }
            val outcome = JitRef.chainOutcome(rc, out)
            // §7 iter51: ФАЗА по фактам устройства. Ответ транспорта — не доказательство исхода;
            // terminal-файл ЭТОЙ операции — доказательство (START/END/refused). Ошибка чтения
            // terminal'а не «додумывается»: тогда исход остаётся неизвестным.
            val myTerm = probeTerminal(shell, opId, log, opInst)
            // iter52 §7.3: если terminal прочитать не удалось, но в РЕАЛЬНОМ stdout есть отказ
            // ИМЕННО нашей операции — это доказанный no-op, а не неизвестность.
            val phase = if (myTerm == null && JitOwn.refusedInOutput(opId, out)) JitOwn.State.REFUSED
                        else JitOwn.classify(outcome == JitRef.ChainOutcome.REPLY_RECEIVED, myTerm)
            // iter67 §E.1: `JIT_OP_PHASE` печатается ВСЕГДА и ДО любых выводов о состоянии
            // (на живой ГУ таких строк было ноль за всю историю: понять, где оборвалась цепочка,
            // было нельзя). `term=absent` — отдельный факт, который ниже называется строкой
            // `JIT_OP_PROBE`.
            log(
                "JIT_OP_PHASE op=$opId phase=${phase.name.lowercase()} " +
                    "term=${myTerm?.wire() ?: "absent"} rc=$rc outcome=${JitRef.chainOutcomeLabel(outcome)} " +
                    "out_tail=«${out.takeLast(120).trim()}»"
            )
            // iter67 §E.2: отсутствие terminal — ОТДЕЛЬНАЯ строка и НЕ доказательство «не выполнялось».
            // `result=absent` ставится и когда файла нет, и когда он прочитан, но НЕ доказывает
            // ничего (ни START, ни END, ни отказа): «нет улики» — это отдельный факт.
            run {
                val proves = myTerm != null &&
                    (myTerm.started || myTerm.finished || myTerm.refused)
                val result = if (proves) "ok" else "absent"
                log(
                    "JIT_OP_PROBE op=$opId result=$result file=${JitOwn.termPath(opId)} " +
                        "attempt=after_payload rc=$rc read=${if (myTerm == null) "unreadable" else "parsed"} " +
                        (if (proves) "" else
                            "(payload не начинался ИЛИ файл недоступен — это НЕ доказательство «не выполнялось»)")
                )
            }
            // iter67 §E.3: потеря канала во время payload — строка ДО восстановления и БЕЗ
            // повторной отправки: payload мог выполниться наполовину.
            if (outcome == JitRef.ChainOutcome.CHANNEL_LOST) {
                log(
                    "JIT_CHAIN_LOST op=$opId cause=$rc payload_maybe_running=1 commands_sent=1 " +
                        "action=no_resend (второй payload не отправляем)"
                )
            }
            // §7: «ответ транспорта есть, terminal не прочитан» — это SENT, как и на HEAD
            // (исход не доказан, но и канал не потерян). Неопределённость — только UNKNOWN и
            // STARTED (payload начался и не оставил END).
            val undefined = phase == JitOwn.State.UNKNOWN || phase == JitOwn.State.STARTED
            log(
                "JIT_OP_PHASE op=$opId phase=${phase.name.lowercase()} term=«${
                    myTerm?.wire() ?: "unreadable"
                }» — ${JitOwn.stateNote(phase)}"
            )
            // iter67 §E.4: один отложенный перечитыватель terminal (короткая команда, НЕ payload).
            // Нужен потому, что `END` пишется на устройстве после `ctl.restart zygote`: сразу после
            // ответа транспорта файла может ещё не быть, и без перечитывания исход остаётся
            // «неизвестным» навсегда.
            val rereadOp = opId
            val rereadInst = opInst
            val rereadTask = { probeTerminalDelayed(shell, rereadOp, log, rereadInst) }
            if (scheduler != null) {
                runCatching { scheduler.postDelayed(PROBE_DELAY_MS, rereadTask) }
                    .onFailure { t ->
                        log(
                            "JIT_OP_PROBE op=$opId result=not_scheduled reason=${t.javaClass.simpleName} " +
                                "(перечитывание terminal не поставлено)"
                        )
                    }
            } else {
                rereadTask()
            }
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
            if (phase == JitOwn.State.REFUSED) {
                // Доказанный no-op на устройстве: гвардия увидела ЧУЖОЙ claim. Ничего не
                // выполнено (ни сеть, ни свойства, ни zygote) — значит и «применено» заявлять
                // нельзя, и снимок прошлой операции оставлять нельзя: возвращаем настройки
                // только там, где принадлежность доказана, и снимаем СВОИ ключи.
                val plan = runCatching {
                    JitRef.planRestore(prev, store.readOperation(), opId, snap)
                }.getOrElse { JitRef.RestorePlan(emptyList(), emptyList(), listOf("read_failed")) }
                val rb = runCatching { store.restorePrevious(plan.owned) }
                    .getOrElse { t -> JitRef.WriteResult.none("exception:${t.javaClass.simpleName}") }
                val rm = runCatching { store.removeOwned(opId) }
                    .getOrElse { t ->
                        JitRef.OwnedRemoval(0, 0, 0, true, true,
                            "exception:${t.javaClass.simpleName}")
                    }
                CHAIN_UNDEFINED.set(false)
                log(
                    "JIT_CHAIN_REFUSED op=$opId rc=$rc term=«${myTerm?.wire() ?: "?"}» " +
                        "prev_restore=${rb.ok}/${rb.attempted} restore_plan=«${plan.brief()}» " +
                        "cleanup=«${rm.brief()}» restart=not_happened internet=not_checked " +
                        "action=revert_settings " +
                        "(payload отказался работать: claim на устройстве принадлежит другой " +
                        "операции — доказанный no-op. Сеть и свойства НЕ менялись, JIT не включён; " +
                        "настройки возвращаются только там, где принадлежность доказана " +
                        "(«${plan.brief()}»), свои ключи операции снимаются)"
                )
                return Click(want, null, write, read, rc, out, null, previousUndefined)
            }
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
     * §7 iter51: прочитать «claim + terminal» ЭТОЙ операции короткой командой.
     *
     * `null` — прочитать не удалось (транспорт/ошибка): неизвестность НЕ снимается, и мы не
     * подменяем её догадкой.
     */
    private fun probeTerminal(
        shell: JitRef.JitShell,
        opId: String,
        log: (String) -> Unit,
        expectInst: String? = null
    ): JitOwn.Terminal? {
        val r = runCatching { shell.exec(JitOwn.readCmd(opId), 8_000) }.getOrElse { t ->
            log(
                "JIT_OP_PROBE op=$opId read=failed reason=${t.javaClass.simpleName} " +
                    "(terminal не прочитан: исход операции остаётся неизвестным)"
            )
            return null
        }
        // iter56 §5: факты читаются по СВОЕМУ исполнению (identity из durable-отметки).
        // Если оно неизвестно (отметка прежней версии) — parseTerminal не заявляет ни `STARTED`,
        // ни `FINISHED` при нескольких исполнениях в файле: неоднозначность остаётся названной.
        val term = JitOwn.parseTerminal(opId, r.second, r.first, expectInst)
        if (term == null) {
            log(
                "JIT_OP_PROBE op=$opId read=failed rc=${r.first} out=«${r.second.trim().take(120)}» " +
                    "(terminal не прочитан: исход операции остаётся неизвестным)"
            )
        } else {
            log("JIT_OP_PROBE op=$opId read=ok ${term.wire()} note=${term.note}")
        }
        return term
    }

    /** iter67 §E.4: задержка отложенного перечитывания terminal (как в референсе продолжения). */
    private const val PROBE_DELAY_MS = 5_000L

    /**
     * iter67 §E.4 — ОДНО отложенное перечитывание terminal. Ничего не меняет: только факт.
     *
     * `result=ok` — файл прочитан и разобран (дальше его состояние называет `JIT_OP_PROBE`),
     * `result=absent` — файла нет/не читается. Второй раз не переспрашиваем: это диагностика, а не
     * механизм разрешения операции (разрешение — только по доказанному terminal или выходу правок C/H).
     */
    private fun probeTerminalDelayed(
        shell: JitRef.JitShell,
        opId: String,
        log: (String) -> Unit,
        expectInst: String?
    ) {
        val r = runCatching { shell.exec(JitOwn.readCmd(opId), 8_000) }.getOrElse { t ->
            log(
                "JIT_OP_PROBE op=$opId result=absent file=${JitOwn.termPath(opId)} " +
                    "attempt=delayed reason=${t.javaClass.simpleName}"
            )
            return
        }
        val term = JitOwn.parseTerminal(opId, r.second, r.first, expectInst)
        val proves = term != null && (term.started || term.finished || term.refused)
        if (!proves) {
            log(
                "JIT_OP_PROBE op=$opId result=absent file=${JitOwn.termPath(opId)} " +
                    "attempt=delayed rc=${r.first} out=«${r.second.trim().take(120)}» " +
                    "(файла нет ИЛИ он не доказывает исполнения — исход по-прежнему не доказан)"
            )
        } else {
            log(
                "JIT_OP_PROBE op=$opId result=ok file=${JitOwn.termPath(opId)} attempt=delayed " +
                    "started=${term!!.started} finished=${term.finished} refused=${term.refused} " +
                    "«${term.wire()}»"
            )
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
     * §3.1 iter41 — чего этот callback НЕ доказывает:
     *  • он не доказывает успех JIT, рестарт фреймворка и наличие интернета;
     *  • он не разрешает конфликтующий клик сам по себе: снимок остаётся, пока возврат сети не
     *    подтверждён отдельно (это разные факты);
     *  • `NONE` (данных уже нет) — НЕ доказательство разрешения. Если снимок исчез раньше, чем
     *    команда окончилась, восстановить исходное состояние нечем: честно пишем `unknown`/⛔ и
     *    НЕ снимаем блокировку;
     *  • если запись `replied` не подтверждена всеми хранилищами, отметка на диске остаётся
     *    `undefined` — неопределённость сохраняется, ложно снимать её в памяти нельзя.
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
            if (cur.state == JitRef.StoredState.NONE) {
                // §3.1 iter41: данных нет. Раньше здесь считалось `cleaned=true` и блокировка
                // снималась. Это неверно: снимок мог быть удалён ДО окончания команды, а сама
                // команда — затем снова отключить Wi-Fi. Восстанавливать нечего, доказательств
                // безопасного продолжения нет ⇒ ⛔ и блокировка остаётся.
                log(
                    "JIT_CHAIN_LATE op=$opId evidence=command_finished recovered=none " +
                        "reason=snapshot_lost store_state=none rc=$rc " +
                        "chain_undefined=true clicks=blocked " +
                        "(ответ команды получен, но исходного снимка на диске уже нет: заново " +
                        "оценить состояние сети и восстановить его нечем. Это ⛔, а не разрешение — " +
                        "новый конфликтующий клик не отправляется)"
                )
                return
            }
            if (cur.mark?.op != opId) {
                // iter49 §7: ответ пришёл с id, которого нет в сохранённой отметке. Это либо
                // ответ операции, которую уже закрыли выходом (`lastAbandonedOp`), либо чужой/
                // устаревший ответ. В обоих случаях он НЕ доказывает ничего о текущей операции.
                val abandoned = lastAbandonedOp()
                log(
                    "JIT_CHAIN_LATE op=$opId ignored reason=operation_changed " +
                        "abandoned_op=${abandoned ?: "none"} " +
                        "attributed=no now=«${cur.brief()}» " +
                        "(ответ относится к другой операции: к текущей его приписывать нельзя — " +
                        "ни как успех, ни как провал)"
                )
                return
            }
            val upd = runCatching { store.updateMark(cur.mark.copy(state = JitRef.OpMark.REPLIED)) }
                .getOrElse { t -> JitRef.WriteResult.none("exception:" + t.javaClass.simpleName) }
            val persisted = upd.attempted > 0 && upd.ok == upd.attempted
            if (!persisted) {
                // §3.1: запись отметки не подтверждена (commit=false/исключение/частичный успех) —
                // на диске осталось `undefined`, значит следующий процесс снова заблокируется.
                // Снимать неопределённость в памяти нельзя: это дало бы клик, которого не будет
                // после перезапуска. Честный блокер вместо ложного разрешения.
                log(
                    "JIT_CHAIN_LATE op=$opId evidence=command_finished recovered=none " +
                        "reason=mark_write_unconfirmed mark_write=${upd.ok}/${upd.attempted} " +
                        "detail=${upd.detail} chain_undefined=true clicks=blocked " +
                        "(ответ команды получен, но отметка `replied` не записана во все хранилища: " +
                        "на диске осталось `undefined`, поэтому блокировка сохраняется — ⛔)"
                )
                return
            }
            CHAIN_UNDEFINED.set(false)
            log(
                "JIT_CHAIN_LATE op=$opId evidence=command_finished rc=$rc " +
                    "out=${out.trim().take(160).ifEmpty { "empty" }} " +
                    "mark=replied mark_write=${upd.ok}/${upd.attempted} chain_undefined=false " +
                    "snapshot=kept operation=pending clicks=blocked " +
                    "(это свидетельство завершения команды, а НЕ успеха JIT и НЕ наличия интернета; " +
                    "снимок остаётся и удаляется только после ПОДТВЕРЖДЁННОГО возврата сети — " +
                    "следующим обычным запуском)"
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
        log: (String) -> Unit = {},
        /** iter52 §7.4: проверка владения перед включением Wi-Fi (по opId операции). */
        claimGuardFor: ((String) -> Pair<Boolean, String>)? = null
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
            // §7.2 iter45: снимок старше окна НЕ применяется. Иначе поздний запуск приложения
            // (обновление, сон, обычный старт через час) «возвращал» Wi-Fi по устаревшему снимку и
            // перебивал ручное состояние владельца — это и было видно в логе 13.11.
            val startAge = markAgeMs(stored.mark)
            if (opExpired(stored.mark) && stored.state != JitRef.StoredState.NONE) {
                log(
                    "JIT_START snapshot=${stored.snapshot?.snap?.wire() ?: "none"} stale=1 " +
                        "age_ms=$startAge limit_ms=$STALE_MARK_MS action=none wifi_restore=none " +
                        "stored=«${stored.brief()}» " +
                        "(продолжение операции по устаревшему снимку не выполняется: за окно " +
                        "владелец мог изменить сеть сам; данные не удаляем, блокировку клика снимает " +
                        "только пользовательский клик по правилу STALE_MARK_MS)"
                )
                return StartOutcome(stored.snapshot?.snap, stored.snapshot?.snap?.wire(),
                    stored.snapshot?.format, null, false, "stale_snapshot", stored.mark)
            }
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
                    runScheduledRestore(
                        store, netState, restore.snap, mark.op, persistedUndefined, token, log,
                        claimGuard = claimGuardFor?.let { f -> { f(mark.op) } }
                    )
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
                    "mark_state=${mark.state} mark_ts_ms=${mark.tsMs} identification=ok removed=none " +
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
        log: (String) -> Unit,
        claimGuard: (() -> Pair<Boolean, String>)? = null
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
            // iter67 §I.2/§I.3: решение о возврате — по двум независимым фактам. «Мы сняли сеть»
            // отменяет и возраст отметки, и отсутствие claim; «нет claim» ≠ «чужой claim».
            val curMark = runCatching { JitRef.assessStored(store.readOperation()).mark }.getOrNull()
            val weDisarmed = runCatching { store.getBool(JitNetDecision.KEY_NET_CUT_BY_US) == true }
                .getOrDefault(false)
            val claimNote = runCatching { claimGuard?.invoke()?.second ?: "" }.getOrDefault("")
            val claimKnown = !claimNote.contains("absent")
            val nowEnabled = runCatching { netState.wifiOn() }.getOrNull()
            val decision = JitNetDecision.restoreDecision(
                snap = snap,
                weDisarmed = weDisarmed,
                markAgeMs = curMark?.let { System.currentTimeMillis() - it.tsMs },
                claimKnown = claimKnown,
                nowEnabled = nowEnabled
            )
            val allowEnable = decision.restoreNetwork
            val enableWhy = decision.why
            log(
                "JIT_RESTORE_DECISION op=$opId restore=${if (allowEnable) 1 else 0} why=$enableWhy " +
                    "we_disarmed=${if (weDisarmed) 1 else 0} claim_known=${if (claimKnown) 1 else 0} " +
                    "claim=«${claimNote.take(40)}» now_enabled=${nowEnabled ?: "unread"} " +
                    "chain_resolved=${if (decision.chainResolved) 1 else 0} " +
                    "(сеть и исход цепочки — разные факты: решение о включении НЕ доказывает " +
                    "выполнения setprop)"
            )
            if (!allowEnable) {
                log(
                    "JIT_NET_RESTORE op=$opId wifi_enable=denied reason=$enableWhy " +
                        "ui=«${JitNetDecision.deniedText(enableWhy)}» " +
                        "(возврат выполняется без включения Wi-Fi)"
                )
            }
            report = runCatching {
                restoreWifi(netState, snap, log, allowEnable, enableWhy, claimGuard)
            }.getOrElse { t ->
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
                // iter67 §I.1: флаг снимается ТОЛЬКО после подтверждённого включения
                // (`WifiManager.isWifiEnabled == true`, то есть COMPLETE).
                if (completion == JitRef.RestoreCompletion.COMPLETE) {
                    val cleared = runCatching { store.putBool(JitNetDecision.KEY_NET_CUT_BY_US, false) }
                        .getOrDefault(false)
                    log(
                        "JIT_WE_CUT_NET op=$opId value=0 confirmed=${if (cleared) 1 else 0} " +
                            "(включение сети подтверждено чтением WifiManager.isWifiEnabled)"
                    )
                }
                if (completion == JitRef.RestoreCompletion.COMPLETE ||
                    completion == JitRef.RestoreCompletion.COMPLETE_WAS_OFF
                ) {
                    if (wasUndefined) {
                        // ─── §2.1/§2.2 iter41: ГЛАВНОЕ ИСПРАВЛЕНИЕ. ───
                        // Исход операции `sending`/`undefined` неизвестен: старая команда могла
                        // остаться в очереди транспорта и выполниться позже (в том числе после
                        // смерти этого процесса и после того, как сеть «выглядит восстановленной»).
                        // Поэтому здесь НЕ вызывается removeOwned: снимок и отметка остаются на
                        // диске и остаются принадлежащими этой операции, а новый клик — заблокирован.
                        // `COMPLETE_WAS_OFF` (снимок `wifi=0`) — то же самое: отсутствие
                        // необходимости включать Wi-Fi ничего не говорит о shell-команде.
                        log(
                            "JIT_RESTORE_KEPT op=$opId completion=${completion.name.lowercase()} " +
                                "network_state=restored cleanup=none data=kept " +
                                "mark_state=${JitRef.OpMark.UNDEFINED} write=none " +
                                "(снимок и отметка НЕ удаляются: сеть восстановлена, но исход старой " +
                                "команды неизвестен. Свидетельство ровно одно — ответ именно этой " +
                                "команды; включённый Wi-Fi, IP, getprop, boot id и новый процесс им " +
                                "не являются. После смерти процесса блокировка восстановится по " +
                                "отметке на диске)"
                        )
                        log(
                            "JIT_UNDEFINED op=$opId outcome=still_unknown " +
                                "network_state=restored operation=unresolved clicks=blocked " +
                                "evidence=command_finished_missing data=kept cleanup=none " +
                                "(⛔ без обещаний авторазрешения: пока нет ответа этой команды, " +
                                "новая цепочка не отправляется и исходный снимок не перезаписывается)"
                        )
                    } else {
                        // Операция опознана и её команда ответила целиком: разрешено согласованное
                        // удаление ТОЛЬКО своей операции (одно commit на хранилище).
                        val rm = runCatching { store.removeOwned(opId) }
                            .getOrElse { t ->
                                JitRef.OwnedRemoval(0, 0, 0, true, true,
                                    "exception:${t.javaClass.simpleName}")
                            }
                        log(
                            "JIT_RESTORE_DONE op=$opId completion=${completion.name.lowercase()} " +
                                "cleanup=owned ${rm.brief()} " +
                                (if (rm.consistent) "data=removed"
                                 else "data=kept_or_partial (неполная очистка: остаток будет прочитан " +
                                     "как неопознанный и НЕ выполнится; гарантии продолжения по нему нет)") +
                                " write=remove(restore+mark, own op)"
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
        log: (String) -> Unit = {},
        allowEnable: Boolean = true,
        enableWhy: String = "click_continuation",
        /**
         * iter52 §7.4: ПРОВЕРКА ВЛАДЕНИЯ перед включением Wi-Fi. Возвращает `(true, why)` —
         * можно; `(false, why)` — нельзя (владелец уже принял новое решение). Передаётся с
         * реального пути: JitNet → JitOwn.claimAllowsRestore по claim с устройства.
         */
        claimGuard: (() -> Pair<Boolean, String>)? = null
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
        if (!allowEnable && !stateBefore) {
            // §7.2 iter46: поздний независимый старт. Wi-Fi выключен, снимок говорит «был включён»,
            // но включать его по старому снимку нельзя: это не восстановление, а новое действие
            // против возможного выбора владельца. Данные операции НЕ удаляем и ничего не «лечим».
            val r = WifiRestoreReport(
                snap, false, null, false, null, "not_enabled:$enableWhy",
                JitRef.RestoreCompletion.PENDING
            )
            log(
                r.log + " (⛔ включать Wi-Fi по старому снимку нельзя: $enableWhy. " +
                    "Это повторный/поздний запуск, а не продолжение клика: сеть остаётся как есть, " +
                    "снимок сохранён, никаких авто-повторов не запускается. Выключить Wi-Fi, " +
                    "который владелец включил сам, мы тоже не будем)"
            )
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
        // iter52 §7.4: «снимок говорит был включён, сейчас выключен» — самое опасное место:
        // включение перебивает независимое решение владельца. Спрашиваем принадлежность ДО вызова.
        if (claimGuard != null) {
            val (allowed, why) = runCatching { claimGuard() }
                .getOrElse { t -> false to "claim_check_failed:${t.javaClass.simpleName}" }
            if (!allowed) {
                val r = WifiRestoreReport(
                    snap, false, null, false, null, "claim:$why",
                    JitRef.RestoreCompletion.PENDING
                )
                log(
                    r.log + " (⛔ Wi-Fi НЕ включаем: $why — владение операцией не наше/не доказано. " +
                        "Снимок сохранён, запрос setWifiEnabled(true) не отправлялся: иначе мы бы " +
                        "затёрли более новое решение владельца)"
                )
                return r
            }
            log("JIT_NET_RESTORE claim_gate allowed=1 why=$why (проверка ДО setWifiEnabled(true))")
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
        // iter67 §G: три различимых исхода. `pending_restart` (свойство выставлено, кэша нет) —
        // это НЕ провал и НЕ повод снимать намерение: не хватает только старта zygote.
        val outcome = JitUi.verifyOutcome(pedido ?: false, prop.value, cache)
        log(JitUi.stateLine(pedido, prop.raw, cache, outcome))
        log(
            "JIT pedido=$pedido getprop=${prop.raw.ifEmpty { "empty" }} " +
                "code-cache-executavel=${cache?.toString() ?: "unknown"} " +
                "result=${JitRef.verifyLabel(verdict)} outcome=${JitUi.outcomeWire(outcome)} " +
                "restart=not_claimed"
        )
        if (JitUi.keepPedido(outcome)) {
            val pending = outcome == VerifyOutcome.PENDING_RESTART
            val why = if (pending) "zygote_not_restarted" else "prop_unreadable"
            val note = if (pending)
                "свойство выставлено, но фреймворк ещё не перезапускался — ждём старта zygote, " +
                    "клик владельца не блокируется"
            else
                "свойство прочитать нельзя — запрос не выдаём за выполненный"
            log(
                "JIT_PEDIDO kept reason=${JitUi.outcomeWire(outcome)} why=$why " +
                    "write=none clicks=allowed (намерение сохранено: $note)"
            )
            return VerifyState(pedido, prop, cache, verdict, null, true,
                JitUi.outcomeWire(outcome))
        }
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

    /**
     * iter67 §A.3 — `getprop` читается только «с ожиданием».
     *
     * Дефект (измерено): `rc=0` у `getprop dalvik.vm.usejit` печатался как `→ SUCCESS`, хотя
     * значение было `false`, и UI показывал «включён». Теперь вывод сравнивается с ожидаемым
     * ([ExecExpect]: `rc==0 && out.trim()==expect`), и в журнал идёт строка `EXEC_EXPECT` со
     * значениями `expect=`/`got=`; `PropFact` получает флаг [JitRef.PropFact.expectOk].
     */
    fun readProp(shell: JitRef.JitShell): JitRef.PropFact {
        val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", JitRef.GETPROP_TIMEOUT_MS)
        val expected = readPropExpectation()
        val fact = JitRef.parseProp(rc, out)
        val expectOk = expected != null && ExecExpect.evaluate(rc, out, expected)
        // Журнал ядра — строкой через AppLog (сам модуль Android-независим и лог не держит).
        runCatching {
            AppLog.i("JitRef",
                ExecExpect.logLine(rc, out, expected ?: "<unread>", "getprop ${JitRef.PROP_USEJIT}") +
                    " expect_source=${if (expected == null) "none" else "caller"}")
        }
        return fact.copy(expectOk = expectOk)
    }

    /**
     * Ожидаемое значение для следующего чтения `getprop` — то, что мы записали и обязались
     * прочитать обратно (`null` — ожидания нет, тогда сравнение невозможно и `expectOk=false`).
     */
    @Volatile
    var propExpectation: String? = null

    fun readPropExpectation(): String? = propExpectation
}
