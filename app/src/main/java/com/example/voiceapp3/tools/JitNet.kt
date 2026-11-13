package com.example.voiceapp3.tools

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager

/**
 * Android-адаптеры для ядра [JitOp]. Решений здесь нет — только те вызовы, что есть в референсе:
 * `WifiManager.isWifiEnabled()` / `setWifiEnabled()`, чтение `/proc/self/maps` текущего процесса,
 * `getprop`, префы проекта.
 *
 * Точки доступа ГУ (`ap0`, `startSoftAp`, `WIFI_AP_STATE_CHANGED`, `wifi_state==14`), `CarplayMonitor`,
 * `connadaptor_modo` и ConnAdaptor в JIT не переносятся (решения владельца).
 *
 * Сетевые команды — только внутри цепочки клика и её продолжения после рестарта фреймворка;
 * `ip rule` только читается; `ctl.restart zygote` — единственная команда рестарта.
 */
object JitNet {

    private const val TAG = "CENTRAL_JitNet"

    internal const val KEY_PEDIDO = JitRef.KEY_PEDIDO
    internal const val KEY_AUTO = JitRef.KEY_AUTO
    internal const val KEY_RESTORE = JitRef.KEY_RESTORE
    internal const val KEY_MARK = JitRef.KEY_MARK
    internal const val PROP_USEJIT = JitRef.PROP_USEJIT
    internal const val RESTORE_DELAY_MS = JitRef.RESTORE_DELAY_MS
    internal const val CHAIN_TIMEOUT_MS = JitRef.CHAIN_TIMEOUT_MS

    /**
     * Лог не должен влиять на решение: если запись в лог невозможна (например, в JVM-тесте
     * `android.util.Log` — заглушка), операция продолжается по тем же правилам.
     */
    private val log: (String) -> Unit = { line -> runCatching { AppLog.i(TAG, line) } }

    /** Планировщик отложенного возврата сети: тот же смысл, что `postDelayed` у handler-а сервиса. */
    internal object AndroidScheduler : JitRef.JitScheduler {
        override fun postDelayed(delayMs: Long, task: () -> Unit) {
            BgWorker.postDelayed("JitNet.restoreWifi", delayMs, task)
        }
    }

    // ─────────────────────────── shell ───────────────────────────

    /**
     * §3.2 iter41: НЕ реализует `JitRef.JitLateEvidence` — и это осознанно, а не недоделка.
     *
     * `AdbClient.execute` по таймауту возвращает `ERR_ADB_TIMEOUT`, но job остаётся в очереди и
     * может выполниться позже; слушателя у него уже нет, а пережить смерть процесса и «донести»
     * ответ некому. Значит достоверного свидетельства «старая команда больше не выполнится»
     * реальный транспорт не даёт, и выдавать `rc`/обрыв сокета/новый процесс за такое
     * свидетельство нельзя. Пока это не доказано кодом и тестами, JIT остаётся ⛔ по §7.
     */
    internal object AndroidShell : JitRef.JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.execute(command, timeoutMs)
        }.getOrElse { t -> Pair(1, "ERR_EXCEPTION:${t.javaClass.simpleName}") }

        /**
         * `w0/bb`: `BufferedReader(FileInputStream("/proc/self/maps"))` → строки.
         * Ошибка чтения ⇒ `null` (в референсе `catchall_67`).
         */
        override fun mapsExcerpt(): String? = runCatching {
            java.io.File("/proc/self/maps").readText()
        }.getOrNull()
    }

    /**
     * iter39 §3.1 — shell ТОЛЬКО для диагностики: пассивный путь транспорта.
     *
     * Отдельный объект, а не [AndroidShell]: клик JIT и сверка должны сохранить прежнее поведение
     * транспорта (включая восстановление канала), а диагностика — не иметь к нему доступа вовсе.
     * Пассивный путь не запускает bootstrap, переподключение, replay и `ctl.restart adbd`; при
     * недоступности возвращает `ERR_ADB_PASSIVE_UNAVAILABLE`, который сборщики показывают как
     * `unavailable`/`channel_not_ready`, а не как «нет root» или «пусто».
     */
    internal object DiagShell : JitRef.JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> = runCatching {
            AdbClient.executePassive(command, timeoutMs)
        }.getOrElse { t ->
            Pair(AdbPassive.RC_UNAVAILABLE,
                "ERR_ADB_PASSIVE_UNAVAILABLE reason=exception:${t.javaClass.simpleName}")
        }

        /** `/proc/self/maps` читающего процесса — к диагностике сети/монтирований не относится. */
        override fun mapsExcerpt(): String? = null
    }

    // ─────────────────────────── префы ───────────────────────────

    /**
     * Префы операции поверх хранилищ проекта (`Prefs2.cps`/`Prefs2.dps`).
     *
     * Ключи и порядок — как у одного `Editor` референса. Отличия (только локально для JIT, общая
     * семантика `Prefs2` не меняется):
     *  • запись выполняется в КАЖДОЕ хранилище отдельно и отчитывается по каждому
     *    (`commit` + readback ВСЕХ трёх ключей);
     *  • `readRestore()` показывает, что именно увидит продолжение (порядок CPS→DPS, как у
     *    `getString`) и есть ли конфликт между хранилищами;
     *  • `remove(key, onlyStores)` не трогает хранилища, куда наша запись не прошла.
     */
    internal class AndroidStore(
        private val cpsProvider: () -> SharedPreferences?,
        private val dpsProvider: () -> SharedPreferences?
    ) : JitRef.JitStore {

        /**
         * Боевой конструктор. Провайдеры вынесены в параметры, чтобы ТОТ ЖЕ код можно было
         * проверить тестами на реалистичном `SharedPreferences` (отдельные память и диск,
         * «новый процесс»), а не только на фейке хранилища.
         */
        constructor(app: Context) : this(
            { try { Prefs2.cps(app) } catch (t: Throwable) { null } },
            // iter43 §4: БЕЗ скрытого фоллбэка — иначе CPS выдавался бы за DPS и «две копии»
            // были бы фикцией. `null` честно означает «второй копии нет».
            { try { Prefs2.dpsOrNull(app) } catch (t: Throwable) { null } }
        )

        private fun cps(): SharedPreferences? = runCatching { cpsProvider() }.getOrNull()
        private fun dps(): SharedPreferences? = runCatching { dpsProvider() }.getOrNull()

        /**
         * §4.2 iter41 — ПОЛУЧЕНИЕ хранилищ с отчётом о сбоях.
         *
         * Раньше исключение провайдера превращалось в `null`, хранилище просто исчезало из
         * `stores()`, и `readOperation()` об этом ничего не сообщал: «копия не прочитана» выглядело
         * как «копии нет». Здесь это разделено:
         *  • `absent:same_store` — DPS совпал с CPS (штатная конфигурация `Prefs2`, когда
         *    device-protected storage недоступен): это ОДНО хранилище, а не ошибка;
         *  • `provider_null` / `provider:<Тип>` — хранилище не получено: состояние неизвестно,
         *    выдавать его за «данных нет» нельзя.
         */
        private class Acquired(
            val stores: List<Pair<String, SharedPreferences>>,
            val errors: List<JitRef.StoreOp>
        )

        private fun acquire(): Acquired {
            val out = ArrayList<Pair<String, SharedPreferences>>(2)
            val errors = ArrayList<JitRef.StoreOp>(2)
            val c = try {
                cpsProvider()
            } catch (t: Throwable) {
                log("JIT_STORE acquire store=cps error=${t.javaClass.simpleName}")
                errors += JitRef.StoreOp("cps", null, null, "provider:" + t.javaClass.simpleName)
                null
            }
            if (c != null) out += "cps" to c
            else if (errors.none { it.name == "cps" }) {
                log("JIT_STORE acquire store=cps error=provider_null")
                errors += JitRef.StoreOp("cps", null, null, "provider_null")
            }
            val d = try {
                dpsProvider()
            } catch (t: Throwable) {
                log("JIT_STORE acquire store=dps error=${t.javaClass.simpleName}")
                errors += JitRef.StoreOp("dps", null, null, "provider:" + t.javaClass.simpleName)
                null
            }
            if (d != null) {
                if (out.none { it.second === d }) out += "dps" to d
                else log(
                    "JIT_STORE acquire dps_distinct=false copies=1 " +
                        "ids=cps:${System.identityHashCode(c)} dps:${System.identityHashCode(d)} " +
                        "(DPS вернул ТУ ЖЕ копию: это одна копия, а не две; вход в одну точку данных)"
                )
            } else if (errors.none { it.name == "dps" }) {
                log("JIT_STORE acquire store=dps error=provider_null")
                errors += JitRef.StoreOp("dps", null, null, "provider_null")
            }
            return Acquired(out, errors)
        }

        /**
         * §1.2 iter44: идентичность копии = имя + тождество объекта. Позволяет отличить «ту же
         * копию» от «другой копии под тем же именем». Это ТОЖДЕСТВО В ПРЕДЕЛАХ ПРОЦЕССА (не путь
         * к файлу): между процессами сравнивать нечем, и это ограничение заявлено в отчёте.
         */
        private fun identityOf(name: String, s: SharedPreferences): String =
            name + "@" + System.identityHashCode(s)

        /** Порядок тот же, что в [getString]: CPS первым. `distinct()` — если DPS недоступен. */
        private fun stores(): List<Pair<String, SharedPreferences>> = acquire().stores

        override fun getString(key: String): String? {
            val a = runCatching { cps()?.getString(key, null) }.getOrNull()
            val b = runCatching { dps()?.getString(key, null) }.getOrNull()
            if (a != null && b != null && a != b) {
                log("JIT_STORE conflict key=$key cps=\"${a.take(40)}\" dps=\"${b.take(40)}\" used=cps")
            }
            return a ?: b
        }

        override fun getBool(key: String): Boolean? {
            val a = runCatching { cps()?.let { if (it.contains(key)) it.getBoolean(key, false) else null } }
                .getOrNull()
            val b = runCatching { dps()?.let { if (it.contains(key)) it.getBoolean(key, false) else null } }
                .getOrNull()
            if (a != null && b != null && a != b) {
                log("JIT_STORE conflict key=$key cps=$a dps=$b used=cps")
            }
            return a ?: b
        }

        override fun contains(key: String): Boolean =
            runCatching { stores().any { it.second.contains(key) } }.getOrDefault(false)

        /**
         * Прежнее состояние ключей операции по каждому хранилищу, в порядке чтения (CPS→DPS).
         * Снимается ДО записи; при неудаче оно же возвращается отккатом.
         */
        override fun readSnapshotState(): List<JitRef.StorePrev> {
            val acq = acquire()
            val known = acq.stores.map { (name, s) -> readStorePrev(name, s) }
            // §4.2: если хотя бы одно хранилище не получено, прежнее состояние недостоверно —
            // откат по нему запрещён (ключи с present=null).
            val missing = acq.errors.map { e ->
                JitRef.StorePrev(
                    e.name,
                    (JitRef.SNAPSHOT_KEYS + KEY_MARK).map { k -> JitRef.KeyPrev(k, null, null, null) }
                )
            }
            return known + missing
        }

        private fun readStorePrev(name: String, s: SharedPreferences): JitRef.StorePrev =
            JitRef.StorePrev(
                name = name,
                identity = identityOf(name, s),
                keys = (JitRef.SNAPSHOT_KEYS + KEY_MARK).map { key ->
                    // §2.5: «ключа нет» и «прочитать нельзя» — РАЗНОЕ. Исключение чтения даёт
                    // present=null (неизвестно), и тогда менять данные нельзя вовсе.
                    val present: Boolean? = try {
                        s.contains(key)
                    } catch (t: Throwable) {
                        log("JIT_STORE prev_unknown store=$name key=$key reason=${t.javaClass.simpleName}")
                        null
                    }
                    if (present == null) {
                        JitRef.KeyPrev(key, null, null, null)
                    } else if (!present) {
                        // Ключа достоверно нет — это НЕ ошибка чтения.
                        JitRef.KeyPrev(key, false, null, null)
                    } else {
                        // §2.4 iter40: ключ есть, но значение прочитать нельзя (исключение,
                        // неожиданный тип, недостоверный null) — это `error`, а НЕ «известное
                        // значение». Такой ключ нельзя ни удалять, ни перезаписывать.
                        // Тип чтения — по СМЫСЛУ ключа: `getString` на булевом ключе в Android
                        // бросает ClassCastException, и это была бы ложная «ошибка значения».
                        var error: String? = null
                        val isBool = key == KEY_PEDIDO || key == KEY_AUTO
                        val str: String? = if (isBool) null else try {
                            s.getString(key, null)
                        } catch (t: Throwable) {
                            error = "read:" + t.javaClass.simpleName
                            null
                        }
                        val bool: Boolean? = if (isBool) {
                            try {
                                s.getBoolean(key, false)
                            } catch (t: Throwable) {
                                error = "type:" + t.javaClass.simpleName
                                null
                            }
                        } else null
                        if (error == null && str == null && bool == null) {
                            // Ключ есть (contains=true), а значения не получили: доверять нельзя.
                            error = "null_value"
                        }
                        JitRef.KeyPrev(key, present, bool, str, error)
                    }
                }
            )

        /**
         * Вернуть прежние значения. Один `commit()` на хранилище; отчёт — по каждому.
         *
         * Чего это НЕ даёт: атомарности между CPS и DPS и гарантии от аппаратного сбоя. Если
         * откат не прошёл, это видно вызывающему (`ok/attempted` + `detail`), и он остаётся
         * заблокированным — «как будто ничего не писали» не заявляется.
         */
        override fun restorePrevious(prev: List<JitRef.StorePrev>): JitRef.WriteResult {
            if (prev.isEmpty()) return JitRef.WriteResult.none("no_previous_state")
            var ok = 0
            val detail = StringBuilder()
            for (p in prev) {
                if (!p.complete) {
                    detail.append(if (detail.isEmpty()) "" else ",").append("${p.name}:prev_unknown")
                    continue
                }
                val s = stores().firstOrNull { it.first == p.name }?.second
                if (s == null) {
                    detail.append(if (detail.isEmpty()) "" else ",").append("${p.name}:store_absent")
                    continue
                }
                val good = runCatching {
                    val e = s.edit()
                    for (k in p.keys) {
                        when {
                            k.present == false -> e.remove(k.key)
                            k.str != null -> e.putString(k.key, k.str)
                            k.bool != null -> e.putBoolean(k.key, k.bool)
                            else -> e.remove(k.key)
                        }
                    }
                    e.commit()
                }.getOrElse { t ->
                    log("JIT_STORE restore_previous store=${p.name} exception=${t.javaClass.simpleName}")
                    false
                }
                if (good) ok++ else detail.append(if (detail.isEmpty()) "" else ",")
                    .append("${p.name}:commit_false")
            }
            val r = JitRef.WriteResult(prev.size, ok, detail.ifEmpty { "all_ok" }.toString())
            log("JIT_STORE restore_previous attempted=${r.attempted} ok=${r.ok} detail=${r.detail}")
            return r
        }

        /**
         * §2.5 iter40: пара «снимок + отметка» ПО КАЖДОМУ хранилищу, одним проходом, в порядке
         * чтения (CPS→DPS). Ошибка чтения попадает в `error`, поэтому «не удалось прочитать» не
         * превращается в «данных нет», а пара никогда не собирается из разных хранилищ.
         */
        override fun readOperation(): List<JitRef.StoreOp> {
            val acq = acquire()
            val reads = acq.stores.map { (name, s) ->
                try {
                    val snap = s.getString(KEY_RESTORE, null)
                    val mark = s.getString(KEY_MARK, null)
                    JitRef.StoreOp(name, snap, mark)
                } catch (t: Throwable) {
                    log("JIT_STORE read_operation store=$name exception=${t.javaClass.simpleName}")
                    JitRef.StoreOp(name, null, null, "read:" + t.javaClass.simpleName)
                }
            }
            // §4.2: недоступное хранилище — это НЕ «данных нет». Ошибка получения идёт отдельной
            // записью с `error`, поэтому `assessStored` даёт UNREADABLE, а не NONE.
            return reads + acq.errors
        }

        /**
         * §2.2: согласованное условное удаление данных СВОЕЙ операции.
         *
         * В каждом хранилище: если отметка принадлежит [opId] — снимок и отметка удаляются ОДНИМ
         * `editor/commit` и проверяются чтением. Чужая отметка ⇒ хранилище не трогается.
         * Атомарности между CPS и DPS нет: это отдельные `commit()`; частичный успех честно
         * виден в отчёте и НЕ выдаётся за гарантированное продолжение.
         */
        override fun removeOwned(opId: String): JitRef.OwnedRemoval {
            val all = stores()
            if (all.isEmpty()) {
                log("JIT_STORE remove_owned op=$opId attempted=0 reason=no_store")
                return JitRef.OwnedRemoval(0, 0, 0, true, true, "no_store")
            }
            var clean = 0
            var other = 0
            // `commit() == false` означает «удаление НЕ подтверждено», а не «удалено»: память
            // процесса пуста, но на диске значение могло остаться, и после перезапуска оно
            // вернётся. Поэтому такой остаток считается возможным (честно, без гарантий).
            var unconfirmed = false
            val detail = StringBuilder()
            for ((name, s) in all) {
                val cur = try {
                    JitRef.OpMark.parse(s.getString(KEY_MARK, null))
                } catch (t: Throwable) {
                    null
                }
                if (cur == null || cur.op != opId) {
                    other++
                    detail.append(if (detail.isEmpty()) "" else ",")
                        .append("$name:skip(${cur?.op ?: "no_mark"})")
                    continue
                }
                val ok = try {
                    s.edit().remove(KEY_RESTORE).remove(KEY_MARK).commit()
                } catch (t: Throwable) {
                    log("JIT_STORE remove_owned store=$name exception=${t.javaClass.simpleName}")
                    false
                }
                val restoreGone = try { !s.contains(KEY_RESTORE) } catch (t: Throwable) { false }
                val markGone = try { !s.contains(KEY_MARK) } catch (t: Throwable) { false }
                if (ok && restoreGone && markGone) {
                    clean++
                } else {
                    unconfirmed = true
                    detail.append(if (detail.isEmpty()) "" else ",")
                        .append("$name:commit=${if (ok) "ok" else "false"}" +
                            ",restore_left=${!restoreGone},mark_left=${!markGone}")
                }
            }
            // Остаток смотрим по порядку чтения (CPS→DPS) — так его увидит следующий процесс.
            // Плюс: если хоть одно хранилище не подтвердило удаление, остаток возможен на диске
            // даже при пустой памяти — согласованность не заявляем.
            val restoreLeft = unconfirmed ||
                runCatching { getString(KEY_RESTORE) != null }.getOrDefault(true)
            val markLeft = unconfirmed ||
                runCatching { getString(KEY_MARK) != null }.getOrDefault(true)
            val r = JitRef.OwnedRemoval(
                all.size, clean, other, restoreLeft, markLeft,
                detail.ifEmpty { "all_clean" }.toString()
            )
            log("JIT_STORE remove_owned op=$opId ${r.brief()}")
            return r
        }

        /** Обновить ТОЛЬКО отметку операции (`sending` → `replied`/`undefined`). */
        override fun updateMark(mark: JitRef.OpMark): JitRef.WriteResult {
            val all = stores()
            if (all.isEmpty()) return JitRef.WriteResult.none("no_store")
            var ok = 0
            val detail = StringBuilder()
            for ((name, s) in all) {
                val good = runCatching { s.edit().putString(KEY_MARK, mark.wire()).commit() }.getOrElse { t ->
                    log("JIT_STORE mark store=$name exception=${t.javaClass.simpleName}")
                    false
                }
                if (good) ok++ else detail.append(if (detail.isEmpty()) "" else ",").append("$name:commit_false")
            }
            return JitRef.WriteResult(all.size, ok, detail.ifEmpty { "all_ok" }.toString())
        }

        override fun putSnapshot(
            want: Boolean,
            snap: JitRef.WifiSnapshot,
            mark: JitRef.OpMark,
            target: List<String>?
        ): JitRef.SnapshotWrite {
            // §2.5: набор хранилищ фиксируется моментом чтения прежних значений; если хранилище
            // исчезло или появилось между этапами — отчёт покажет это, а не «тихо» переедет.
            val available = stores()
            val all = available.filter { target == null || it.first in target }
            // §4 iter43: и ПОЯВИВШИЕСЯ, и ПРОПАВШИЕ копии перечисляются явно — имена не должны
            // «тихо» исчезать из сравнения набора. Появившаяся копия НЕ участвует в записи
            // (её прежнее состояние неизвестно), поэтому клик будет заблокирован.
            val appeared = if (target == null) emptyList()
            else available.map { it.first }.filter { it !in target }
            val absent = if (target == null) emptyList()
            else target.filter { t -> all.none { it.first == t } }
            if (all.isEmpty()) {
                log("JIT_STORE snapshot attempted=0 reason=no_store appeared=${appeared.ifEmpty { listOf("none") }}")
                return JitRef.SnapshotWrite(want, snap, emptyList(), appeared, absent)
            }
            if (absent.isNotEmpty() || appeared.isNotEmpty()) {
                log("JIT_STORE snapshot note=store_set_changed " +
                    "absent=${absent.ifEmpty { listOf("none") }} " +
                    "appeared=${appeared.ifEmpty { listOf("none") }} " +
                    "(состав копий CPS/DPS изменился между чтением и записью)")
            }
            val reports = ArrayList<JitRef.StoreReport>(all.size)
            for ((name, s) in all) {
                var commit = "false"
                var err: String? = null
                try {
                    commit = if (
                        s.edit()
                            .putBoolean(KEY_PEDIDO, want)
                            .putBoolean(KEY_AUTO, want)
                            .putString(KEY_RESTORE, snap.wire())
                            .putString(KEY_MARK, mark.wire())
                            .commit()
                    ) "ok" else "false"
                } catch (t: Throwable) {
                    commit = "exception:${t.javaClass.simpleName}"
                    err = t.message?.take(60)
                }
                // readback ВСЕХ трёх ключей: чтение одного Wi-Fi-ключа не доказывает остальных.
                val readback = runCatching {
                    val pedidoOk = s.contains(KEY_PEDIDO) && s.getBoolean(KEY_PEDIDO, !want) == want
                    val autoOk = s.contains(KEY_AUTO) && s.getBoolean(KEY_AUTO, !want) == want
                    val restoreOk = s.getString(KEY_RESTORE, null) == snap.wire()
                    val markOk = s.getString(KEY_MARK, null) == mark.wire()
                    pedidoOk && autoOk && restoreOk && markOk
                }.getOrDefault(false)
                reports += JitRef.StoreReport(name, commit, readback, err, identityOf(name, s))
            }
            val w = JitRef.SnapshotWrite(want, snap, reports, appeared, absent)
            log(
                "JIT_STORE snapshot keys=$KEY_PEDIDO,$KEY_AUTO,$KEY_RESTORE,$KEY_MARK op=${mark.op} " +
                    "value=${snap.wire()} " +
                    "attempted=${w.attempted} committed=${w.committed} confirmed=${w.confirmed} " +
                    "detail=${w.detail} (как j9: один commit на хранилище)"
            )
            return w
        }

        /**
         * Что увидит продолжение: значение по порядку CPS→DPS + конфликты.
         *
         * Если хранилища расходятся, будущий снимок неоднозначен: при падении CPS повторный старт
         * прочитал бы DPS, а при живом CPS — CPS. Такой снимок не даёт права выключать Wi-Fi.
         */
        override fun readRestore(): JitRef.SnapshotRead {
            val all = stores()
            if (all.isEmpty()) return JitRef.SnapshotRead(null, emptyList())
            val values = all.map { (name, s) ->
                name to runCatching { s.getString(KEY_RESTORE, null) }.getOrNull()
            }
            val conflicts = ArrayList<String>()
            for (key in JitRef.SNAPSHOT_KEYS) {
                // Конфликт — это ДВА РАЗНЫХ значения. Отсутствие ключа во втором хранилище
                // конфликтом не является: продолжение прочитает первое, а если первое недоступно —
                // получит `null`, то есть «снимка нет» (операция просто не выполнится).
                val perStore = all.mapNotNull { (name, s) ->
                    runCatching {
                        when (key) {
                            KEY_RESTORE -> s.getString(key, null)?.let { name to it }
                            else -> if (s.contains(key)) name to s.getBoolean(key, false).toString() else null
                        }
                    }.getOrNull()
                }
                if (perStore.size > 1 && perStore.map { it.second }.distinct().size > 1) {
                    conflicts += "$key(${perStore.joinToString("/") { "${it.first}=${it.second}" }})"
                }
            }
            val value = values.firstOrNull { it.second != null }?.second
            val from = values.firstOrNull { it.second != null }?.first ?: "-"
            if (conflicts.isNotEmpty()) {
                log("JIT_STORE read_conflict value=$value from=$from conflicts=${conflicts.joinToString(",")}")
            }
            return JitRef.SnapshotRead(value, conflicts)
        }

        override fun remove(key: String, onlyStores: Set<String>?): JitRef.WriteResult {
            val all = stores().filter { onlyStores == null || it.first in onlyStores }
            if (all.isEmpty()) return JitRef.WriteResult.none("no_store")
            var ok = 0
            val detail = StringBuilder()
            for ((name, s) in all) {
                val good = runCatching { s.edit().remove(key).commit() }.getOrElse { t ->
                    log("JIT_STORE remove store=$name exception=${t.javaClass.simpleName}")
                    false
                }
                if (good) ok++ else detail.append(if (detail.isEmpty()) "" else ",").append("$name:commit_false")
            }
            val r = JitRef.WriteResult(all.size, ok, detail.ifEmpty { "all_ok" }.toString())
            log(
                "JIT_STORE remove key=$key attempted=${r.attempted} ok=${r.ok} detail=${r.detail}" +
                    (if (onlyStores != null) " only=${onlyStores.joinToString(",")}" else "")
            )
            return r
        }
    }

    // ─────────────────────────── состояние клиентского Wi-Fi ───────────────────────────

    /**
     * Только клиентский Wi-Fi: `isWifiEnabled`, `setWifiEnabled` и read-only признак адреса.
     *
     * `wifiOn()` возвращает `null`, если прочитать нельзя: трактовка вынесена в [JitOp].
     */
    internal class AndroidNetState(private val app: Context) : JitRef.JitNetState {

        private fun wm(): WifiManager? =
            runCatching { app.getSystemService(Context.WIFI_SERVICE) as WifiManager }.getOrNull()

        override fun wifiOn(): Boolean? = runCatching { wm()?.isWifiEnabled }.getOrNull()

        override fun setWifi(on: Boolean): Boolean? =
            runCatching { wm()?.setWifiEnabled(on) }.getOrNull()

        /**
         * Read-only: у `wlan0` есть не-loopback адрес. Признак подключения к точке доступа, но НЕ
         * доказательство работающего интернета (маршруты/DNS/TTL не проверяются и не меняются).
         * SSID/пароли не читаются и не сохраняются.
         */
        override fun wifiAddress(): Boolean? = runCatching {
            val ni = java.net.NetworkInterface.getByName("wlan0") ?: return@runCatching false
            ni.isUp && ni.inetAddresses.toList().any { !it.isLoopbackAddress }
        }.getOrNull()
    }

    // ─────────────────────────── публичный вход для AppPerformance ───────────────────────────

    fun read(app: Context, key: String): String? = AndroidStore(app).getString(key)

    fun contains(app: Context, key: String): Boolean = AndroidStore(app).contains(key)

    fun usejitFact(app: Context): JitRef.PropFact = JitOp.readProp(AndroidShell)

    /**
     * `true`/`false`/`null` по `/proc/self/maps` этого процесса — как `w0/j5.d`.
     * Не доказательство JIT системы: только то, что видит наша карта памяти.
     */
    fun codeCacheExecutable(): Boolean? =
        JitRef.cacheExecutableFromMaps(AndroidShell.mapsExcerpt())

    /** Клик тумблера — `w0/j9.onClick` + `w0/la.run`. Только из фонового потока. */
    fun click(app: Context, want: Boolean): JitOp.Click = JitOp.click(
        store = AndroidStore(app),
        netState = AndroidNetState(app),
        shell = AndroidShell,
        want = want,
        log = log
    )

    /**
     * Продолжение операции после рестарта фреймворка — `CentralWatchdogService.onCreate` + `w0/e2`.
     * Возвращается сразу: снимок снят, возврат Wi-Fi запланирован через 15 с ([AndroidScheduler]).
     */
    fun onProcessStart(app: Context, source: String = "process"): JitOp.StartOutcome =
        JitOp.onProcessStart(
            store = AndroidStore(app),
            netState = AndroidNetState(app),
            scheduler = AndroidScheduler,
            log = { log("source=$source $it") }
        )

    /** Сверка `w0/bb` + `w0/r7` (getprop + `/proc/self/maps` + `jit_pedido`). Фоновый поток. */
    fun verifyApplied(app: Context): JitOp.VerifyState = JitOp.verifyApplied(
        store = AndroidStore(app),
        shell = AndroidShell,
        log = log
    )

    /** Есть ли незавершённое намерение — дешёвая локальная проверка, без shell. */
    fun hasPendingPedido(app: Context): Boolean = AndroidStore(app).contains(KEY_PEDIDO)

    // ─────────────────────────── одноразовая read-only диагностика ───────────────────────────

    /** Процессный флаг: право на попытку уже занято (вход в диагностику один раз за процесс). */
    private val diagAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * §5.2 iter41: попытки сбора (не более двух за процесс) и ФАКТ успешного завершения.
     * «Запущен» больше не выдаётся за «собран».
     */
    @Volatile private var diagOnce = JitRef.DiagOnce(2)

    /**
     * §5.1 iter41 — приёмник диагностических строк для боевого пути.
     *
     * Возвращает ФАКТ записи: `AppLog.diagLine` отдаёт результат `LogFiles.append` (датированный
     * файл), а не «вызов принят». `DROPPED` — строку сознательно не писали (бюджет диагностики);
     * `FAILED` — не подтверждено. Сам лог-приёмник ничего решать не может: сеть он не трогает.
     */
    internal object DiagSink : LogBudget.Sink {
        override fun write(line: String): LogBudget.LogWrite {
            val r = runCatching { AppLog.diagLineResult(TAG, line) }
                .getOrElse { t -> LogFiles.Result.fail("io:" + t.javaClass.simpleName) }
            if (r.written) return LogBudget.LogWrite(LogBudget.WriteOutcome.WRITTEN, r.file)
            // Не подтверждено: отличаем «бюджет не пустил» от «запись не удалась».
            val label = LogBudget.diagLabel(line)
            val out = if (LogBudget.isDiagAlways(line) || !AppLog.diagTruncatedFor(label)) {
                LogBudget.WriteOutcome.FAILED
            } else {
                LogBudget.WriteOutcome.DROPPED
            }
            return LogBudget.LogWrite(out, "")
        }

        override fun location(): String = runCatching { LogFiles.currentPath() }.getOrDefault("unknown")

        override fun independent(line: String) {
            // Независимый канал: logcat + обычный (не диагностический) путь лога.
            runCatching { android.util.Log.e("CENTRAL_DiagWrite", line) }
            runCatching { AppLog.e(TAG, line) }
        }
    }

    /** Лок на однократную отложенную запись «канал пока не готов» (§3.4). */
    private val diagFallbackLock = Any()

    /**
     * Одноразовая **read-only** диагностика: монтирования ([JitMountDiag]) и сеть ([InternetDiag]).
     *
     * Требования, которые здесь соблюдены: запуск в фоне (не UI-поток), не чаще одной попытки за
     * процесс, без удержания рабочего потока ожиданием канала, без кнопок, без связи с кликом JIT,
     * без единой изменяющей команды. У каждой части свои метки `*_BEGIN/_SECTION/_DATA/_END` и
     * свои ошибки: провал одной не рушит вторую.
     *
     * Готовность канала: если он ещё не готов, ставится ОДИН обработчик существующего события
     * готовности (`AdbClient.onChannelReadyOnce`) и одна отложенная проверка на 30 с. Бесконечных
     * повторов и нового сторожа нет; при неготовности пишется честный `channel_not_ready`, а не
     * «нет root» и не «нет интернета».
     *
     * §5.2 iter41: успешный сбор фиксируется ТОЛЬКО по факту завершения обоих сборщиков при
     * готовом канале; неудачная попытка не считается собранной и не лишает владельца ограниченной
     * повторной попытки ([JitRef.DiagOnce], максимум 2 внешних набора проверок).
     */
    fun requestDiagOnce(
        app: Context,
        source: String,
        delayMs: Long = 0L,
        netInfo: InternetDiag.NetInfo? = null
    ) {
        // §4.2 iter44: одиночный флаг больше не «сжигает» диагностику навсегда. Раньше вторая
        // попытка после события готовности канала молча ничего не делала. Теперь повторный вход
        // запрещён только когда сбор УЖЕ завершён; число реальных сборов по-прежнему ограничено
        // ([JitRef.DiagOnce], 2 запуска на процесс).
        if (!markDiagAttempted() && diagOnce.completedNow()) {
            AppLog.i(TAG, "DIAG source=$source status=skipped why=already_completed " +
                "runs=${diagOnce.runsUsed()}/${diagOnce.maxRunsAllowed()} " +
                "(сбор уже дал результат: повторный не нужен)")
            return
        }
        val appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        /**
         * Полный сбор (канал готов).
         *
         * §2.7 iter42: «не было исключения» больше НЕ считается успехом. Статус берётся из
         * [JitRef.DiagReport]: критерии — готовый канал, отсутствие секций `unknown`/`timeout`,
         * отсутствие неудачных записей и вердикт записи `full`/`truncated`. Частичный результат
         * честно остаётся частичным и не закрывает ограниченную повторную попытку.
         */
        val runBoth: (String) -> Boolean = { via ->
            val attempt = diagOnce.tryRun()
            if (attempt == null) {
                AppLog.i(TAG, "DIAG via=$via status=exhausted runs=${diagOnce.runsUsed()}/" +
                    "${diagOnce.maxRunsAllowed()} completed=${diagOnce.completedNow()} " +
                    "(попытки сбора исчерпаны: это ограничение, а не «данных нет»)")
                false
            } else {
                val mount = runCatching {
                    JitMountDiag.collectWithReport(DiagShell, true, DiagSink, "$source/$via") {
                        AppLog.diagTruncatedFor("JIT_MOUNT_DIAG")
                    }
                }.onFailure { t ->
                    AppLog.i(TAG, "JIT_MOUNT_DIAG source=$source/$via status=failed " +
                        "reason=collector_exception:${t.javaClass.simpleName} " +
                        "(сбой сборщика, а не результат чтения)")
                }.getOrNull()
                val net = runCatching {
                    InternetDiag.collectWithReport(DiagShell, true, netInfo, DiagSink, "$source/$via", appVersion) {
                        AppLog.diagTruncatedFor("INTERNET_DIAG")
                    }
                }.onFailure { t ->
                    AppLog.i(TAG, "INTERNET_DIAG source=$source/$via status=failed " +
                        "reason=collector_exception:${t.javaClass.simpleName} " +
                        "(сбой сборщика, сеть не менялась)")
                }.getOrNull()
                for (r in listOfNotNull(mount?.info, net?.info)) {
                    AppLog.i(TAG, "DIAG_SECTION_RESULT block=${r.block} status=${r.status} " +
                        "sections=${r.sectionCount} definite=${r.sectionsDefinite} " +
                        "lines=${r.written}/${r.offered} failed=${r.failed} dropped=${r.dropped} " +
                        "written=${r.verdict} sink=«${r.sink}» reason=${r.reason}")
                }
                val ok = mount?.info?.completed == true && net?.info?.completed == true
                val completed = diagOnce.finish(ok)
                val st = when {
                    ok -> "completed"
                    mount == null || net == null -> "failed"
                    mount.info.status == "channel_not_ready" || net.info.status == "channel_not_ready" ->
                        "channel_not_ready"
                    else -> "partial"
                }
                AppLog.i(TAG, "DIAG via=$via run=$attempt/${diagOnce.maxRunsAllowed()} " +
                    "status=$st mount=${mount?.info?.status ?: "failed"} network=${net?.info?.status ?: "failed"} " +
                    "collected=$completed " +
                    "(успех — только при выполнении критериев; частичный сбор остаётся частичным и " +
                    "не закрывает ограниченную повторную попытку)")
                ok
            }
        }
        // §3.4: отложенная честная запись «канал пока не готов» НЕ занимает успешный сбор.
        var fallbackWrote = false
        val treatNotReady: (String) -> Unit = { why ->
            val once = synchronized(diagFallbackLock) {
                if (fallbackWrote) false else { fallbackWrote = true; true }
            }
            if (once) {
                runCatching {
                    JitMountDiag.collect(DiagShell, false, DiagSink, source) {
                        AppLog.diagTruncatedFor("JIT_MOUNT_DIAG")
                    }
                }.onFailure { t -> AppLog.i(TAG, "JIT_MOUNT_DIAG status=unknown reason=${t.javaClass.simpleName}") }
                // §4.2 iter40: канал недоступен, но Android API-часть читается независимо —
                // её и записываем (shell-разделы честно `channel_not_ready`).
                runCatching {
                    InternetDiag.collect(
                        DiagShell, false, netInfo, DiagSink, "$source/fallback:$why", appVersion
                    ) { AppLog.diagTruncatedFor("INTERNET_DIAG") }
                }.onFailure { t ->
                    AppLog.i(TAG, "INTERNET_DIAG status=unknown reason=${t.javaClass.simpleName}")
                }
                AppLog.i(TAG, "DIAG source=$source status=partial_android_only " +
                    "reason=$why collected=${diagOnce.completedNow()} " +
                    "(shell-разделы не собраны: это НЕ успешный сбор; сбор по готовности канала " +
                    "ещё возможен в пределах лимита попыток)")
            }
        }

        /**
         * §2.3 iter43 — оркестрация: один сбор одновременно, ограниченные повторы по факту
         * готовности канала/частичного результата, отмена устаревшей «страховки» после успеха.
         */
        val runner = JitRef.DiagRunner(
            maxAttempts = 2,
            retryDelayMs = 20_000L
        ) { name, delay, task -> BgWorker.postDelayed(name, delay, task) }

        lateinit var attempt: () -> JitRef.DiagRunner.Outcome
        attempt = attempt@{
            BgWorker.assertNotMain("JitNet.requestDiagOnce")
            val ready = runCatching { AdbClient.isChannelReady() }.getOrDefault(false)
            if (!ready) {
                AppLog.i(TAG, "DIAG source=$source status=channel_not_ready action=wait_event " +
                    "(канал приложения ещё не готов: это НЕ отсутствие root; ждём существующее " +
                    "событие готовности, повторов без него нет)")
                runCatching {
                    AdbClient.onChannelReadyOnce {
                        BgWorker.post("JitNet.diagOnReady") {
                            runner.startWithRetry("after_ready_event", attempt)
                        }
                    }
                }
                // Одна ограниченная «страховка»: если канал так и не поднялся, статус будет честным.
                runner.scheduleNotReadyReport("channel_not_ready", 30_000L) {
                    treatNotReady("channel_not_ready_after_30s")
                }
                JitRef.DiagRunner.Outcome.CHANNEL_NOT_READY
            } else {
                val ok = runBoth("attempt")
                if (ok) JitRef.DiagRunner.Outcome.COMPLETED else JitRef.DiagRunner.Outcome.PARTIAL
            }
        }

        val task: () -> Unit = {
            runner.startWithRetry("initial", attempt)
            for (n in runner.notes()) AppLog.i(TAG, n)
        }
        if (delayMs > 0) BgWorker.postDelayed("JitNet.diag", delayMs, task)
        else BgWorker.post("JitNet.diag", task)
    }

    // ─────────────────────── событийные снимки сети: «до/после» (iter42 §3) ───────────────────────

    /**
     * Ограничитель событийных снимков: не чаще 90 с, не больше 6 в час, без дублей.
     * Один экземпляр на процесс.
     */
    private val eventSnaps = JitRef.EventSnapshots()

    /** Ключи в полёте — чтобы поздний debounce одного события не перекрыл другое. */
    private val eventInFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * §3 iter42 — СРАВНИМЫЙ снимок сети по СУЩЕСТВУЮЩЕМУ событию (включение/выключение TTL,
     * смена Wi-Fi/активной сети). Никаких новых кнопок и никаких изменений сети: только чтение.
     *
     * Зачем: в логе владельца 12.11 снимок сети есть ДО включения TTL (18:56) и отсутствует
     * ПОСЛЕ (19:00) — сравнить «до/после» нечем.
     *
     * При шторме событий выполняется ОДИН снимок устойчивого состояния: `postDelayed` на
     * [stableDelayMs], при этом дубли отсекаются, а факт пропуска пишется с причиной.
     * Результат не объявляется заранее: строка `SNAP reason=… status=…` пишется по факту.
     */
    fun requestEventSnapshot(
        app: Context,
        reason: String,
        netInfo: InternetDiag.NetInfo? = null,
        stableDelayMs: Long = 15_000L,
        /** Снимок парный к предыдущему (`ttl_on_pre` → `ttl_on_post`): интервал не режет пару. */
        pairWith: String? = null,
        /** `true` — событие вызвано ДЕЙСТВИЕМ ВЛАДЕЛЬЦА (переключение TTL): своя квота. */
        userEvent: Boolean = false,
        /** Явная метка фазы (из [JitRef.TtlSnapshotFlow]): `pre_confirmed`/`pre_overlap`/`post_after_apply`. */
        phaseLabel: String? = null,
        /**
         * Метка фазы, вычисляемая В МОМЕНТ фактического сбора: «до» может пересёкся с применением
         * TTL, и честная метка зависит от времени сбора, а не от времени заказа.
         */
        phaseLabelProvider: (() -> String)? = null,
        /** Вызывается после фактического сбора (для меток «до»/«после»). */
        onCollected: (() -> Unit)? = null,
        /**
         * §3.1 iter44: вызывается В МОМЕНТ фактического начала сбора, а не при заказе.
         * Именно это время — начало «до»-снимка.
         */
        onCollectStart: (() -> Unit)? = null
    ) {
        val appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val key = "snap:" + reason.substringBefore(':')
        val now = System.currentTimeMillis()
        val skip = when {
            userEvent && pairWith != null -> eventSnaps.tryAcquireUserPaired(key, now, "snap:$pairWith")
            userEvent -> eventSnaps.tryAcquireUser(key, now)
            pairWith != null -> eventSnaps.tryAcquirePaired(key, now, "snap:$pairWith")
            else -> eventSnaps.tryAcquire(key, now)
        }
        val label = if (phaseLabel != null) " label=$phaseLabel" else ""
        if (skip != null) {
            AppLog.i(TAG, "SNAP reason=$reason status=skipped why=$skip$label " +
                "used_last_hour=${eventSnaps.used()} used_user=${eventSnaps.usedUser()} " +
                "(снимок не делается: ограничение частоты/дубль; состояние сети не менялось)")
            // §3.3 iter43: шторм не должен «съесть» итоговое состояние — планируется ОДИН
            // trailing-снимок после окна частоты (не периодический watchdog, не цикл).
            if (skip.startsWith("rate_limited")) {
                scheduleTrailing(reason, netInfo, appVersion, label)
            }
            return
        }
        if (eventInFlight.putIfAbsent(key, true) != null) {
            eventSnaps.release(key)
            AppLog.i(TAG, "SNAP reason=$reason status=skipped why=in_flight$label " +
                "(второй снимок не нужен)")
            return
        }
        AppLog.i(TAG, "SNAP reason=$reason status=scheduled delay_ms=$stableDelayMs " +
            "pair=${pairWith ?: "none"}$label " +
            "note=\"снимок после стабилизации: одно событие сетевого шторма не даёт серию снимков\"")
        BgWorker.postDelayed("JitNet.eventSnapshot", stableDelayMs) {
            try {
                // §3.1 iter44: фактическое начало сбора (не время заказа).
                runCatching { onCollectStart?.invoke() }
                val report = runCatching {
                    InternetDiag.collectWithReport(
                        DiagShell, AdbClient.isChannelReady(), netInfo, DiagSink,
                        "event:$reason", appVersion
                    ) { AppLog.diagTruncatedFor("INTERNET_DIAG") }
                }.onFailure { t ->
                    AppLog.i(TAG, "SNAP reason=$reason status=failed$label " +
                        "why=collector_exception:${t.javaClass.simpleName} (сеть не менялась)")
                }.getOrNull()
                val labelDone = phaseLabelProvider?.let { p ->
                    runCatching { " label_done=" + p() }.getOrDefault("")
                } ?: ""
                // §3.1 iter44: статическая метка фазы, посчитанная при заказе, может устареть —
                // в лог идёт и она (`label=`), и фактическая на момент сбора (`label_done=`).
                AppLog.i(TAG, "SNAP reason=$reason status=${report?.info?.status ?: "failed"}$label$labelDone " +
                    "sections=${report?.info?.sectionCount ?: 0} written=${report?.info?.verdict ?: "none"} " +
                    "sink=«${report?.info?.sink ?: "none"}» why=${report?.info?.reason ?: "failed"} " +
                    "(это факты, а не диагноз: единый снимок на событие; сеть не менялась)")
            } finally {
                eventInFlight.remove(key)
                eventSnaps.release(key)
                runCatching { onCollected?.invoke() }
            }
        }
    }

    // ───────────────── §3.1 iter43: пара снимков «до/после» включения-выключения TTL ─────────────────

    /** Состояние пары «до/после» для переключения TTL (одна операция на переключение). */
    private val ttlFlow = JitRef.TtlSnapshotFlow()

    internal fun ttlSnapshotForTest(): JitRef.TtlSnapshotFlow.Op? = ttlFlow.currentOp()

    /**
     * Начать пару снимков для РЕАЛЬНОГО переключения TTL.
     *
     * Контракт (§3.1): «до» не задерживает переключение и не гарантируется как состояние строго до
     * изменения — метка (`pre_confirmed`/`pre_overlap`/`pre_late`) вычисляется по фактическим
     * временам и пишется в лог. Отказ сбора не влияет на результат тумблера.
     */
    fun beginTtlSnapshot(app: Context, want: Boolean, netInfo: InternetDiag.NetInfo? = null):
        JitRef.TtlSnapshotFlow.Op {
        val op = ttlFlow.begin(want, System.currentTimeMillis())
        requestEventSnapshot(
            app, "ttl_" + (if (want) "on" else "off") + "_pre", netInfo,
            stableDelayMs = 0L, userEvent = true,
            // §3.1 iter44: «до» начинается тогда, когда сбор РЕАЛЬНО начался, а не при заказе.
            onCollectStart = { ttlFlow.preStarted(op, System.currentTimeMillis()) },
            phaseLabelProvider = { ttlFlow.preLabel(op) },
            onCollected = { ttlFlow.preFinished(op, System.currentTimeMillis()) }
        )
        return op
    }

    /**
     * Завершить пару: зафиксировать ФАКТ применения TTL (его результат) и заказать «после»
     * с ограниченной задержкой стабилизации. Метка `post_after_apply` подтверждает порядок.
     */
    fun finishTtlSnapshot(
        app: Context,
        op: JitRef.TtlSnapshotFlow.Op,
        appliedResult: String,
        netInfo: InternetDiag.NetInfo? = null,
        stableDelayMs: Long = 15_000L
    ) {
        // §3.2 iter44: изменения применяются ТОЛЬКО к своей операции; если за это время началась
        // следующая (быстрый ON/OFF), старая отметка не испортит её состояние.
        val applied = ttlFlow.applied(op, System.currentTimeMillis(), appliedResult)
        ttlFlow.postScheduled(op, System.currentTimeMillis())
        requestEventSnapshot(
            app, "ttl_" + (if (op.want) "on" else "off") + "_post", netInfo,
            stableDelayMs = stableDelayMs, pairWith = "ttl_" + (if (op.want) "on" else "off") + "_pre",
            userEvent = true,
            // Метка считается В МОМЕНТ СБОРА (при заказе она могла бы устареть).
            phaseLabelProvider = { ttlFlow.postLabel(op) },
            onCollected = {
                ttlFlow.postCollected(op, System.currentTimeMillis())
            }
        )
        AppLog.i(TAG, "TTL_SNAP_PAIR applied_to_op=$applied " +
            ttlFlow.describe(op, JitRef.TtlSnapshotFlow.SnapPart.POST, "ordered") +
            " pre_label_now=" + ttlFlow.preLabel(op) +
            " (метка «до» окончательна после сбора; сеть/TTL этим кодом не менялись)")
    }

    internal fun resetTtlSnapshotsForTest() {
        ttlFlow.resetForTest()
        trailingScheduled.set(false)
    }

    /** Не более одного запланированного trailing-снимка одновременно. */
    private val trailingScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * §3.3 iter43 — ОДИН отложенный снимок устойчивого состояния после затихания шторма.
     * Ограничен по времени (≤ окна частоты) и по количеству (один одновременно), без повторов.
     */
    private fun scheduleTrailing(
        reason: String,
        netInfo: InternetDiag.NetInfo?,
        appVersion: String,
        label: String
    ) {
        if (!trailingScheduled.compareAndSet(false, true)) return
        val delay = eventSnaps.remainingMs(System.currentTimeMillis()).coerceIn(1_000L, 90_000L)
        AppLog.i(TAG, "SNAP reason=$reason status=trailing_scheduled delay_ms=$delay$label " +
            "note=\"шторм не теряет итоговое состояние: снимок один, не цикл\"")
        BgWorker.postDelayed("JitNet.eventSnapshotTrailing", delay) {
            trailingScheduled.set(false)
            runCatching {
                InternetDiag.collectWithReport(
                    DiagShell, AdbClient.isChannelReady(), netInfo, DiagSink,
                    "event:trailing:$reason", appVersion
                ) { AppLog.diagTruncatedFor("INTERNET_DIAG") }
            }.onFailure { t ->
                AppLog.i(TAG, "SNAP reason=trailing:$reason status=failed " +
                    "why=${t.javaClass.simpleName}")
            }.onSuccess { r ->
                AppLog.i(TAG, "SNAP reason=trailing:$reason status=${r.info.status} " +
                    "written=${r.info.verdict} sink=«${r.info.sink}» why=${r.info.reason} " +
                    "(устойчивое состояние после шторма; сеть не менялась)")
            }
        }
    }

    /** Только для тестов: сбросить ограничитель событийных снимков. */
    internal fun resetEventSnapshotsForTest() {
        eventSnaps.resetForTest()
        eventInFlight.clear()
    }

    /** Только для тестов: состояние ограничителя. */
    internal fun eventSnapshotsUsedForTest(): Int = eventSnaps.used()

    /** Занять единственную попытку сбора в этом процессе (одноразовый флаг, без повторов). */
    internal fun markDiagAttempted(): Boolean = diagAttempted.compareAndSet(false, true)

    /** Только для тестов: сбросить процессные флаги одноразового сбора. */
    internal fun resetDiagForTest() {
        diagAttempted.set(false)
        diagOnce = JitRef.DiagOnce(2)
    }

    /** Только для тестов/логов: состояние ограниченного сбора (запущен/завершён/исчерпан). */
    internal fun diagStatusForTest(): String = diagOnce.status()
}
