package com.example.voiceapp3.tools

/**
 * JIT — перенос цепочки референса CentralEXAuto 3.3.9 БЕЗ собственных механизмов (1.0.61).
 *
 * Всё сверено по DEX/smali APK `CentralEXAuto_v3.3.9.apk` (md5 `c3f1664125953b9851bf4483b265135f`
 * из ZIP `8a65c6c29749c64d4e335c57490a347a`; baksmali 2.5.2, jadx 1.4.7):
 *
 *  • **клик** `w0/j9.smali` (`onClick`, ветка `pswitch_2b`, вызов из `w0/b9.smali:1206`,
 *    индекс 0):
 *    `((WifiManager) getApplicationContext().getSystemService("wifi")).isWifiEnabled()`
 *    (весь блок в `catchall_48 → const/4 v3, 0` ⇒ исключение = `false`);
 *    `NetworkInterface.getByName("ap0")` → `isUp() && getInetAddresses().hasMoreElements()`
 *    (`catchall_64` ⇒ `false`); затем ОДИН `SharedPreferences.Editor.commit()`:
 *    `jit_pedido` = target, `perf_auto_otimizar` = target,
 *    `jit_restaurar` = `"wifi=" + wifi + ",ap=" + ap` (`Landroidx/activity/result/b;->f(II…)`);
 *    лог `"dalvik.vm.usejit -> <target>; reiniciando o framework"`; `pool.execute(new la(...))`.
 *    Результат `commit()` НЕ проверяется, второго клика/блокировки в референсе нет.
 *
 *  • **скрипт** `w0/la.smali` (ветка `pswitch_10e`) — один литерал, байт в байт:
 *    `svc wifi disable` → цикл до 20 итераций по `ip rule show | grep -c 'oif wlan0'` = 0 →
 *    `umount -l` для точек `/data/app/` из `/proc/mounts` → `setprop dalvik.vm.usejit <true|false>`
 *    → `setprop ctl.restart zygote` (последняя команда). Таймаут транспорта `H1(0x9c40=40000, …)`,
 *    после него `postDelayed(ua, 0xafc8=45000)` — это индикатор прогресса в UI, к сети не относится.
 *
 *  • **продолжение** `com/ex/auto/CentralWatchdogService.smali` (`onCreate`, 0x7b9…0x811):
 *    `getString("jit_restaurar", null)`; `Pattern.compile("^wifi=([01]),ap=([01])$").matcher(s)
 *    .matches()`; если совпало — `new oe(wifi, ap)`, `remove("jit_restaurar").apply()`
 *    (**до** выполнения), `postDelayed(new e2(oe, service, 0), 0x3a98 = 15000)`.
 *    `w0/e2.smali`: case 0 — поток (проверка `T2` = «сервис создан»), case 1 —
 *    `if (ap) service.U("pós-JIT", false);` затем `if (wifi && !wm.isWifiEnabled()) wm.setWifiEnabled(true);`
 *    лог `"JIT: restaurado após o restart (wifi=…, ap=…); wifi agora=…; ap agora=…"`,
 *    исключение → `"JIT: restauro falhou: …"`. Метки загрузки в референсе НЕТ, снимок не
 *    проверяется на «наша ли это операция».
 *
 *  • **`U(String,boolean)`** («garantirApAberto», `CentralWatchdogService.smali` 0x2880…):
 *    `v()` (AP в воздухе) ⇒ `true`; ожидание `O()` (CarplayMonitor) по 1 с; guard:
 *    клиентский Wi-Fi включён (`c1()`: `isWifiEnabled() || getWifiState()==2`, исключение ⇒ `true`)
 *    и не наш auto-enable ⇒ AP НЕ открываем, `false`; мьютекс `CountDownLatch` на `0x19 = 25` с;
 *    затем `d()` — рефлексия `getDeclaredMethod("startSoftAp", WifiConfiguration)` + `setAccessible`,
 *    аргумент `null`, результат `invoke` НЕ используется; ожидание `v()` до `0x4e20 = 20000` мс
 *    шагом 1000 мс; при сигнале FAILED (поле `u1`) — до 3 попыток с паузой `0x7d0 = 2000` мс.
 *
 *  • **сверка** `w0/bb.smali` (ветка по умолчанию, вызов из `MainActivity.S0()` = `bb(…, 1)`)
 *    → `w0/r7.smali`: `H1(0x1f40 = 8000, "getprop dalvik.vm.usejit")`, `trim() == "true"`;
 *    `Boolean` из `/proc/self/maps` (`"jit-cache"` или `"dalvik-jit-code-cache"`, ошибка ⇒ `null`);
 *    `toggle.setActive(getprop)`; если в префах есть `jit_pedido`:
 *    `ok = (pedido == getprop) && (!pedido || maps != FALSE)`, лог
 *    `"JIT pedido=… getprop=… code-cache-executavel=…"`, `remove("jit_pedido")`,
 *    при `!ok && pedido` — сообщение «не применилось».
 *
 * Чего в референсе НЕТ и чего здесь поэтому тоже нет: `persist.dalvik.vm.usejit`,
 * `persist.sys.dalvik.vm.usejit`, `dalvik.vm.usejitprofiles`, watcher, файл отмены, `sleep 8`,
 * санация/удаление `ip rule`, boot-backfill, собственный журнал шагов, фазы, `jit_op`, метка
 * загрузки, блокировки «одна операция» (`HOLD`/`BUSY`), подтверждение рестарта zygote.
 *
 * Единственное отличие в поведении — там, где референс выдаёт неизвестное за факт
 * (невозможность прочитать `getprop`): см. [JitRef.parseProp] и [JitRef.verify]. Оно описано в
 * отчёте §3 и не меняет ни одну команду цепочки.
 */
object JitRef {

    // ─────────────────────────── ключи и константы референса ───────────────────────────

    /** `jit_pedido`: намерение операции («включить/выключить»), снимается сверкой (`w0/r7`). */
    internal const val KEY_PEDIDO = "jit_pedido"
    /** `perf_auto_otimizar`: тумблер в UI референса, пишется тем же `commit()`, что и снимок. */
    internal const val KEY_AUTO = "perf_auto_otimizar"
    /** `jit_restaurar`: `wifi=<0|1>,ap=<0|1>`, снимается в `onCreate` ДО выполнения `e2`. */
    internal const val KEY_RESTORE = "jit_restaurar"

    /** Свойство, которое ставит скрипт и читает сверка (`w0/la`, `w0/bb`). */
    internal const val PROP_USEJIT = "dalvik.vm.usejit"

    /** `while [ $_n -lt 20 ]` — предел ожидания исчезновения `oif wlan0` (`w0/la.smali`). */
    internal const val DRAIN_LOOP_MAX = 20
    /** `H1(0x9c40, …)` — таймаут транспорта на весь скрипт (`w0/la.smali`). */
    internal const val CHAIN_TIMEOUT_MS = 40_000
    /** `postDelayed(new e2(...), 0x3a98)` — пауза перед возвратом сети (`onCreate`). */
    internal const val RESTORE_DELAY_MS = 15_000L
    /** `H1(0x1f40, "getprop dalvik.vm.usejit")` (`w0/bb.smali`). */
    internal const val GETPROP_TIMEOUT_MS = 8_000
    /** `CountDownLatch.await(0x19, SECONDS)` — мьютекс `U` на 25 с. */
    internal const val AP_LATCH_TIMEOUT_S = 25L
    /** `if-ge v0, 0x4e20` — ожидание AP в `U`, 20000 мс. */
    internal const val AP_POLL_TOTAL_MS = 20_000L
    /** шаг `Thread.sleep(0x3e8)` в ожидании AP. */
    internal const val AP_POLL_STEP_MS = 1_000L
    /** `if-ge p2, 0x3` — не более 3 попыток открыть AP. */
    internal const val AP_ATTEMPTS = 3
    /** `Thread.sleep(0x7d0)` между попытками AP. */
    internal const val AP_RETRY_PAUSE_MS = 2_000L

    /**
     * Шаг `umount -l` точек `/data/app/` из `w0/la.run` — **исполняется**, как в референсе.
     *
     * Монтирования владельцем разрешены, отключать шаг «потому что это монтирование» нельзя, и
     * скрытого флага «выключено» здесь нет. Обратимость на живом ГУ при этом **не проверена**:
     * в этом окружении устройства нет, а массовый `umount` ради проверки запрещён. Поэтому шаг
     * виден в каждой отправке цепочки (`mount_step=present`), а в отчёте он отдельным пунктом
     * «точная копия литерала, поле не исполнялось».
     */

    /** Регулярка референса — полное совпадение строки, без trim (`Matcher.matches()`). */
    private val RESTORE_RE = Regex("^wifi=([01]),ap=([01])$")

    /** Модель интерфейсов, которыми пользуется цепочка (адаптеры — в `JitNet`). */
    interface JitShell {
        fun exec(command: String, timeoutMs: Int): Pair<Int, String>
        fun cacheVisible(): Boolean?
    }

    interface JitStore {
        fun getString(key: String): String?
        fun getBool(key: String): Boolean?
        fun contains(key: String): Boolean
        /** Три ключа референса ОДНИМ `commit()`. Возвращает результат `commit()`. */
        fun putSnapshot(want: Boolean, net: Net): Boolean
        fun putBool(key: String, value: Boolean): Boolean
        /** `null` ⇒ `remove(key)`. */
        fun putString(key: String, value: String?): Boolean
    }

    interface JitNetState {
        /** `isWifiEnabled()`; `null` — прочитать нельзя (исключение). */
        fun wifiOn(): Boolean?
        /** `ap0` поднят и имеет адреса (`U.v()`); `null` — прочитать нельзя. */
        fun apUp(): Boolean?
        /** `setWifiEnabled(on)`; `null` — вызов не удался (исключение). */
        fun setWifi(on: Boolean): Boolean?
        /** `d()`: рефлексия `startSoftAp(null)`; `null` — вызов не удался (`startSoftAp FALHOU`). */
        fun startAp(): Boolean?
    }

    interface JitWaiter {
        fun sleep(ms: Long)
        fun nowMs(): Long
    }

    /** Состояние сети ДО операции (формат референса). */
    data class Net(val wifi: Boolean, val ap: Boolean) {
        fun wire(): String = "wifi=${if (wifi) 1 else 0},ap=${if (ap) 1 else 0}"
    }

    /**
     * Разбор `jit_restaurar` ровно как `e2`: строка обязана совпасть ЦЕЛИКОМ и без trim —
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
    fun mountStepNote(): String = "mount_step=present"

    // ─────────────────────────── чтение `getprop`: факт, а не догадка ───────────────────────────

    enum class Prop { TRUE, FALSE, UNKNOWN }

    data class PropFact(val value: Prop, val raw: String, val rc: Int, val reason: String) {
        val log: String
            get() = "getprop=${if (raw.isEmpty()) "empty" else raw} rc=$rc " +
                "fact=${value.name.lowercase()} reason=$reason"
    }

    /**
     * `w0/bb`: `H1(8000,"getprop dalvik.vm.usejit")` → `trim() == "true"`; любое другое значение
     * (включая пустой вывод при недоступном транспорте) трактуется как `false`, `rc` не смотрится.
     *
     * ОТЛИЧИЕ (единственное поведенческое): если прочитать НЕЛЬЗЯ (`rc≠0`, `ERR_…`, пусто,
     * многострочный вывод), значение помечается [Prop.UNKNOWN] — «неизвестно», а не «выключено».
     * Иначе при `want=false` недоступный канал давал бы ложный «успех». Команды цепочки от этого
     * не меняются: `setprop` уходит ровно тот же.
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

    /** Итог формулы `w0/r7`. `UNKNOWN` — нет данных (см. [parseProp]). */
    enum class Verify { OK, NOT_APPLIED, CACHE_MISSING, UNKNOWN }

    /**
     * `ok = (pedido == getprop) && (!pedido || maps != Boolean.FALSE)` — дословно `w0/r7`.
     * При нечитаемом `getprop` результат не выводится ([Verify.UNKNOWN]).
     */
    fun verify(pedido: Boolean, prop: PropFact, cache: Boolean?): Verify = when (prop.value) {
        Prop.UNKNOWN -> Verify.UNKNOWN
        Prop.TRUE -> if (pedido) Verify.OK else Verify.NOT_APPLIED
        Prop.FALSE -> when {
            pedido -> Verify.NOT_APPLIED
            cache == false -> Verify.CACHE_MISSING
            else -> Verify.OK
        }
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
 * Никакого собственного состояния операции здесь нет: снимок — это три ключа референса, а не
 * отдельная модель; нет фаз, метки загрузки, `BUSY`, `HOLD`, сверки «наша ли это операция».
 * Продолжение после рестарта фреймворка делается так же, как в `CentralWatchdogService.onCreate`:
 * строка префа совпала с регуляркой → снять строку → подождать 15 с → AP, затем Wi-Fi.
 */
object JitOp {

    private val RESTORE_LOCK = Any()

    // ─────────────────────────── 1. клик: `w0/j9.onClick` ───────────────────────────

    data class Click(
        val want: Boolean,
        val net: JitRef.Net?,
        val saved: Boolean,
        val exitCode: Int,
        val output: String
    ) {
        val outcome: JitRef.ChainOutcome get() = JitRef.chainOutcome(exitCode, output)
    }

    /**
     * Клик тумблера JIT — дословно ветка `pswitch_2b` из `w0/j9.smali`:
     *
     * ```
     * wifi = isWifiEnabled()            // исключение → false
     * ap   = ap0 in air                 // исключение → false
     * edit().putBoolean(jit_pedido, t).putBoolean(perf_auto_otimizar, t)
     *       .putString(jit_restaurar, "wifi=w,ap=a").commit()      // один commit
     * pool.execute(la)                  // скрипт
     * ```
     *
     * Сетевых команд до `commit()` нет; порядок «снимок → скрипт» сохранён. Наличие второго клика
     * при незавершённой операции референс не проверяет — перезапись снимка новой операцией
     * является его поведением (описано в отчёте, не «исправлено» своей блокировкой).
     */
    fun click(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        shell: JitRef.JitShell,
        want: Boolean,
        waiter: JitRef.JitWaiter,
        log: (String) -> Unit = {}
    ): Click {
        // Исключение при чтении в референсе даёт `false` (catchall_48 / catchall_64).
        val wifi = netState.wifiOn() ?: false
        val ap = netState.apUp() ?: false
        val net = JitRef.Net(wifi = wifi, ap = ap)
        val saved = store.putSnapshot(want, net)
        log(
            "JIT_CLICK want=$want net=${net.wire()} saved=$saved " +
                "wifi_state=${netState.wifiOn()?.toString() ?: "unknown"} " +
                "ap_state=${netState.apUp()?.toString() ?: "unknown"} " +
                "(снимок ДО любых изменений; wifi/ap = то, что прочитано перед кликом)"
        )
        log("dalvik.vm.usejit -> $want; reiniciando o framework")
        log("JIT_CHAIN_SENT ${JitRef.mountStepNote()} timeout_ms=${JitRef.CHAIN_TIMEOUT_MS} " +
            "restart=not_claimed command=w0/la.run (литерал референса)")
        val (rc, out) = shell.exec(JitRef.chainScript(want), JitRef.CHAIN_TIMEOUT_MS)
        val outcome = JitRef.chainOutcome(rc, out)
        log(
            "JIT_CHAIN_RETURN rc=$rc outcome=${JitRef.chainOutcomeLabel(outcome)} " +
                "restart=not_claimed internet=not_checked out=${out.trim().take(200).ifEmpty { "empty" }} " +
                "(ответ транспорта ≠ подтверждение рестарта zygote; состояние проверит сверка)"
        )
        if (outcome != JitRef.ChainOutcome.REPLY_RECEIVED) {
            log(
                "JIT_CHAIN_ERROR rc=$rc reason=channel_lost action=none " +
                    "(команда могла остаться в очереди транспорта: это НЕ «не выполнено» и НЕ «выполнено»)"
            )
        }
        return Click(want, net, saved, rc, out)
    }

    // ─────────────────────────── 2. продолжение: `CentralWatchdogService.onCreate` ───────────────────────────

    data class RestoreReport(
        val apNeeded: Boolean,
        val apUp: Boolean,
        val apReason: String,
        val wifiNeeded: Boolean,
        val wifiRequested: Boolean,
        val wifiOn: Boolean?,
        val failure: String?
    )

    data class StartOutcome(
        val snapshot: JitRef.Net?,
        val rawSnapshot: String?,
        val report: RestoreReport?
    )

    /**
     * Продолжение операции на старте процесса — дословно `CentralWatchdogService.onCreate`:
     * `getString("jit_restaurar")` → `matches()` → `remove()` → `postDelayed(e2, 15000)` → `e2`.
     *
     * Блокировка `synchronized` здесь не «машина состояний», а защита от двух одновременных
     * вызовов из одного процесса (`onCreate` + `onStartCommand`): иначе `e2` запустился бы дважды
     * и сеть дёргалась бы два раза. Состояние операции она не хранит.
     */
    fun onProcessStart(
        store: JitRef.JitStore,
        netState: JitRef.JitNetState,
        waiter: JitRef.JitWaiter,
        log: (String) -> Unit = {}
    ): StartOutcome = synchronized(RESTORE_LOCK) {
        val raw = store.getString(JitRef.KEY_RESTORE)
        val net = JitRef.parseRestore(raw)
        if (net == null) {
            log(
                "JIT_START snapshot=${if (raw == null) "none" else "unparsed"} " +
                    "raw=\"${raw?.take(60) ?: ""}\" action=none " +
                    "(референс: регулярка ^wifi=([01]),ap=([01])$ не совпала → e2 не запускается)"
            )
            return@synchronized StartOutcome(null, raw, null)
        }
        // `remove("jit_restaurar").apply()` — ДО выполнения: так в референсе.
        store.putString(JitRef.KEY_RESTORE, null)
        log(
            "JIT_START snapshot=${net.wire()} removed=true delay_ms=${JitRef.RESTORE_DELAY_MS} " +
                "action=restore_scheduled (референс: remove → postDelayed(e2, 15000); " +
                "снимок снимается до выполнения, поэтому смерть процесса в эти 15 с отменяет возврат)"
        )
        waiter.sleep(JitRef.RESTORE_DELAY_MS)
        val report = restoreNetwork(netState, net, waiter, log)
        log(
            "JIT_NET_RESTORE done ap=${report.apUp}(${report.apReason}) " +
                "wifi_requested=${report.wifiRequested} wifi_agora=${report.wifiOn?.toString() ?: "unknown"} " +
                "failure=${report.failure ?: "none"} internet=not_checked " +
                "(подтверждено только прочитанное состояние радио, не интернет)"
        )
        StartOutcome(net, raw, report)
    }

    // ─────────────────────────── 3. возврат сети: `e2` + `U` ───────────────────────────

    /**
     * `e2.run` (case 1): `if (ap) U("pós-JIT", false); if (wifi && !isWifiEnabled()) setWifiEnabled(true);`
     *
     * Порядок AP → Wi-Fi сохранён. Исключение внутри этого блока в референсе ловится целиком и
     * пишется «JIT: restauro falhou» — тогда Wi-Fi уже не запрашивается; здесь так же
     * ([RestoreReport.failure]).
     */
    fun restoreNetwork(
        netState: JitRef.JitNetState,
        net: JitRef.Net,
        waiter: JitRef.JitWaiter,
        log: (String) -> Unit = {}
    ): RestoreReport {
        var failure: String? = null
        var apUp = true
        var apReason = "not_needed"
        if (net.ap) {
            try {
                apUp = openAp(netState, waiter, "pós-JIT", log)
                apReason = if (apUp) "up" else "not_up"
            } catch (t: Throwable) {
                apUp = false
                apReason = "exception"
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
                    log(
                        "JIT_NET_RESTORE wifi=1 request=setWifiEnabled(true) " +
                            "accepted=${accepted?.toString() ?: "unknown"} " +
                            "(референс результат не смотрит: запрос ≠ состояние, состояние ниже)"
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
        return RestoreReport(net.ap, apUp, apReason, net.wifi, wifiRequested, wifiOn, failure)
    }

    /**
     * `U(tag, false)` — «garantirApAberto», перенесённая логика, а не только вызов
     * `startSoftAp(null)`:
     *
     * 1. `v()` (AP уже в воздухе) ⇒ `true`;
     * 2. guard: клиентский Wi-Fi включён ⇒ AP не открываем (`false`, «não disputo o rádio»);
     * 3. `d()` — рефлексия `startSoftAp(null)`;
     * 4. ожидание `v()` до 20 с шагом 1 с ⇒ `true`/`false`.
     *
     * Не перенесено и помечено в отчёте: `O()` (ожидание CarplayMonitor — в нашем приложении такого
     * состояния нет), мьютекс `CountDownLatch` на 25 с (в нашем приложении AP открывает только это
     * продолжение, один вызов за раз — сериализует [RESTORE_LOCK]) и ветка «система ответила FAILED»
     * с 3 попытками (сигнал `u1` приходит из broadcast-приёмника референса, которого у нас нет).
     */
    fun openAp(
        netState: JitRef.JitNetState,
        waiter: JitRef.JitWaiter,
        tag: String,
        log: (String) -> Unit = {}
    ): Boolean {
        log("JIT_AP garantirApAberto($tag)")
        if (netState.apUp() == true) {
            log("JIT_AP tag=$tag state=up action=none (AP уже в воздухе → true)")
            return true
        }
        // `c1()`: isWifiEnabled() || getWifiState()==ENABLING; в референсе исключение ⇒ true
        // (считаем Wi-Fi включённым и не трогаем радио).
        val wifiClientOn = netState.wifiOn() ?: true
        if (wifiClientOn) {
            log(
                "JIT_AP tag=$tag state=down wifi_client=on action=none result=false " +
                    "(как U: «AP fechado, mas o WiFi cliente está ligado — não vou disputar o rádio»)"
            )
            return false
        }
        val accepted = netState.startAp()
        log(
            "JIT_AP tag=$tag request=startSoftAp(null) invoked=${accepted != null} " +
                "accepted=${accepted?.toString() ?: "unknown"} " +
                "(референс результат invoke не использует; вызов ≠ открытый AP)"
        )
        val up = poll(netState::apUp, JitRef.AP_POLL_TOTAL_MS, JitRef.AP_POLL_STEP_MS, waiter)
        log(
            "JIT_AP tag=$tag state_after=${up?.toString() ?: "unknown"} " +
                "poll=${JitRef.AP_POLL_TOTAL_MS / 1000}s step=${JitRef.AP_POLL_STEP_MS}ms " +
                "(подтверждением считается только чтение ap0)"
        )
        return up == true
    }

    /**
     * `bb.run` + `r7.run`: `getprop dalvik.vm.usejit` (8000 мс) + `/proc/self/maps`, затем
     * `ok = (pedido == getprop) && (!pedido || maps != FALSE)`, снятие `jit_pedido` и сообщение
     * «не применилось».
     *
     * ОТЛИЧИЕ (см. [JitRef.parseProp]): при нечитаемом `getprop` результат `unknown` и
     * `jit_pedido` НЕ снимается — иначе одна неудачная попытка чтения выбросила бы намерение и
     * следующая сверка не смогла бы ничего сказать. В референсе `jit_pedido` снимается всегда.
     */
    data class VerifyState(
        val pedido: Boolean?,
        val prop: JitRef.PropFact,
        val cache: Boolean?,
        val verdict: JitRef.Verify,
        val pedidoCleared: Boolean
    )

    fun verifyApplied(
        store: JitRef.JitStore,
        shell: JitRef.JitShell,
        log: (String) -> Unit = {}
    ): VerifyState {
        val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", JitRef.GETPROP_TIMEOUT_MS)
        val prop = JitRef.parseProp(rc, out)
        val cache = shell.cacheVisible()
        val present = store.contains(JitRef.KEY_PEDIDO)
        val pedido = if (present) (store.getBool(JitRef.KEY_PEDIDO) ?: false) else null
        if (pedido == null) {
            log("JIT_APPLIED ${prop.log} code_cache_executavel=${cache?.toString() ?: "unknown"} " +
                "pedido=none result=no_request (в префах нет jit_pedido — сверять нечего)")
            return VerifyState(null, prop, cache, JitRef.Verify.UNKNOWN, false)
        }
        val verdict = JitRef.verify(pedido, prop, cache)
        log("JIT pedido=$pedido getprop=${prop.raw.ifEmpty { "empty" }} " +
            "code-cache-executavel=${cache?.toString() ?: "unknown"} " +
            "result=${JitRef.verifyLabel(verdict)} restart=not_claimed")
        if (verdict == JitRef.Verify.UNKNOWN) {
            log("JIT_PEDIDO kept reason=property_unreadable ${prop.log} write=none " +
                "(в референсе jit_pedido снимается всегда; здесь намерение сохраняется, чтобы " +
                "следующая сверка не потеряла запрос — отличие описано в отчёте §3)")
            return VerifyState(pedido, prop, cache, verdict, false)
        }
        val cleared = store.putBool(JitRef.KEY_PEDIDO, false)
        log("JIT_PEDIDO cleared=$cleared (как r7: remove(jit_pedido) после сверки)")
        if (verdict != JitRef.Verify.OK && pedido) {
            log("JIT_NOT_APPLIED result=${JitRef.verifyLabel(verdict)} pedido=$pedido " +
                "getprop=${prop.raw.ifEmpty { "empty" }} cache=${cache?.toString() ?: "unknown"} " +
                "(факт, а не предположение; ничего не дозаписываем)")
        }
        return VerifyState(pedido, prop, cache, verdict, cleared)
    }

    fun readProp(shell: JitRef.JitShell): JitRef.PropFact {
        val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", JitRef.GETPROP_TIMEOUT_MS)
        return JitRef.parseProp(rc, out)
    }

    private fun poll(
        read: () -> Boolean?,
        totalMs: Long,
        stepMs: Long,
        waiter: JitRef.JitWaiter
    ): Boolean? {
        val t0 = waiter.nowMs()
        var last = read()
        while (last != true && waiter.nowMs() - t0 < totalMs) {
            waiter.sleep(stepMs)
            last = read()
        }
        return last
    }
}
