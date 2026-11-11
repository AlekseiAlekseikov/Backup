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
            { try { Prefs2.dps(app) } catch (t: Throwable) { null } }
        )

        private fun cps(): SharedPreferences? = runCatching { cpsProvider() }.getOrNull()
        private fun dps(): SharedPreferences? = runCatching { dpsProvider() }.getOrNull()

        /** Порядок тот же, что в [getString]: CPS первым. `distinct()` — если DPS недоступен. */
        private fun stores(): List<Pair<String, SharedPreferences>> {
            val out = ArrayList<Pair<String, SharedPreferences>>(2)
            cps()?.let { out += "cps" to it }
            dps()?.let { d -> if (out.none { it.second === d }) out += "dps" to d }
            return out
        }

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
        override fun readSnapshotState(): List<JitRef.StorePrev> = stores().map { (name, s) ->
            JitRef.StorePrev(
                name,
                (JitRef.SNAPSHOT_KEYS + KEY_MARK).map { key ->
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
                    } else {
                        val str = try { s.getString(key, null) } catch (t: Throwable) { null }
                        val bool = try {
                            if (present && (key == KEY_PEDIDO || key == KEY_AUTO)) s.getBoolean(key, false) else null
                        } catch (t: Throwable) { null }
                        JitRef.KeyPrev(key, present, bool, str)
                    }
                }
            )
        }

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
            val all = stores().filter { target == null || it.first in target }
            if (all.isEmpty()) {
                log("JIT_STORE snapshot attempted=0 reason=no_store")
                return JitRef.SnapshotWrite(want, snap, emptyList())
            }
            val absent = target?.filter { t -> all.none { it.first == t } } ?: emptyList()
            if (absent.isNotEmpty()) {
                log("JIT_STORE snapshot note=store_absent names=${absent.joinToString(",")}")
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
                reports += JitRef.StoreReport(name, commit, readback, err)
            }
            val w = JitRef.SnapshotWrite(want, snap, reports)
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

    /** Процессный флаг: попытка сбора была (не чаще одной за процесс). */
    private val diagAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Процессный флаг: сбор УЖЕ выполнен (успешный или честно-неуспешный — второй не начинаем). */
    private val diagRan = java.util.concurrent.atomic.AtomicBoolean(false)

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
     */
    fun requestDiagOnce(
        app: Context,
        source: String,
        delayMs: Long = 0L,
        netInfo: InternetDiag.NetInfo? = null
    ) {
        if (!markDiagAttempted()) return
        val appVersion = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "unknown"

        val runBoth: (String) -> Unit = { via ->
            if (diagRan.compareAndSet(false, true)) {
                runCatching { JitMountDiag.collect(DiagShell, true, log, "$source/$via") }
                    .onFailure { t ->
                        AppLog.i(TAG, "JIT_MOUNT_DIAG source=$source/$via status=unknown " +
                            "reason=${t.javaClass.simpleName} (сбой сборщика, не результат чтения)")
                    }
                runCatching { InternetDiag.collect(DiagShell, true, netInfo, log, "$source/$via", appVersion) }
                    .onFailure { t ->
                        AppLog.i(TAG, "INTERNET_DIAG source=$source/$via status=unknown " +
                            "reason=${t.javaClass.simpleName} (сбой сборщика, сеть не менялась)")
                    }
            }
        }
        // §3.4: отложенная честная запись «канал пока не готов» НЕ занимает единственный сбор.
        // Если канал станет готов позже, сбор ещё выполнится (обработчик события остаётся живым),
        // но не более одного раза — это по-прежнему `diagRan`, а не этот флаг.
        var fallbackWrote = false
        val treatNotReady: (String) -> Unit = { why ->
            val once = synchronized(diagFallbackLock) {
                if (fallbackWrote) false else { fallbackWrote = true; true }
            }
            if (once) {
                runCatching { JitMountDiag.collect(DiagShell, false, log, source) }
                    .onFailure { t -> AppLog.i(TAG, "JIT_MOUNT_DIAG status=unknown reason=${t.javaClass.simpleName}") }
                runCatching { InternetDiag.collectChannelNotReady(log, source, why) }
                    .onFailure { t -> AppLog.i(TAG, "INTERNET_DIAG status=unknown reason=${t.javaClass.simpleName}") }
            }
        }

        val task: () -> Unit = {
            BgWorker.assertNotMain("JitNet.requestDiagOnce")
            val ready = runCatching { AdbClient.isChannelReady() }.getOrDefault(false)
            if (ready) {
                runBoth("immediate")
            } else {
                AppLog.i(TAG, "DIAG source=$source status=channel_not_ready action=wait_event " +
                    "(канал приложения ещё не готов: это НЕ отсутствие root; ждём существующее " +
                    "событие готовности, повторов нет)")
                runCatching {
                    AdbClient.onChannelReadyOnce {
                        BgWorker.post("JitNet.diagOnReady") { runBoth("after_ready_event") }
                    }
                }
                // Одна отложенная проверка: если канал так и не поднялся, статус будет честным.
                BgWorker.postDelayed("JitNet.diagFallback", 30_000L) {
                    treatNotReady("channel_not_ready_after_30s")
                }
            }
        }
        if (delayMs > 0) BgWorker.postDelayed("JitNet.diag", delayMs, task)
        else BgWorker.post("JitNet.diag", task)
    }

    /** Занять единственную попытку сбора в этом процессе (одноразовый флаг, без повторов). */
    internal fun markDiagAttempted(): Boolean = diagAttempted.compareAndSet(false, true)

    /** Только для тестов: сбросить процессные флаги одноразового сбора. */
    internal fun resetDiagForTest() {
        diagAttempted.set(false)
        diagRan.set(false)
    }
}
