package com.example.voiceapp3.tools

/**
 * iter33p (1.0.61) — JIT ровно по механике референса CentralEXAuto 3.3.9.
 *
 * Источник (проверено по декомпиляции в этой итерации, см. `docs/agent/ref/centralexauto_v339_jit_chain.md`):
 *  • клик — `w0/j9.onClick`: `isWifiEnabled()` + `ap0` → ОДИН `commit()` префов
 *    (`jit_pedido`, `perf_auto_otimizar`, `jit_restaurar="wifi=<0|1>,ap=<0|1>"`) → запуск скрипта;
 *  • скрипт — `w0/la.run`: `svc wifi disable` → ожидание `ip rule show | grep -c 'oif wlan0'` = 0
 *    до 20 с → шаг монтирований (`umount -l` найденных точек `/data/app/`) → `setprop dalvik.vm.usejit` →
 *    `setprop ctl.restart zygote` ПОСЛЕДНЕЙ командой (тот же литерал, байт в байт);
 *  • возврат — `CentralWatchdogService.onCreate` → `w0/e2.run`: `jit_restaurar` разбирается регуляркой
 *    `^wifi=([01]),ap=([01])$`, планируется через `Handler.postDelayed(..., 0x3a98)` = **15000 мс**,
 *    затем **AP первым**, после него Wi-Fi (`setWifiEnabled(true)`, если он был включён);
 *  • проверка — `w0/bb` + `w0/r7`: `getprop dalvik.vm.usejit` + наличие `dalvik-jit-code-cache`
 *    в `/proc/self/maps`; при `jit_pedido` вердикт `ok = (pedido == getprop) && (!pedido || cache != false)`.
 *
 * Чего в референсе НЕТ и чего здесь поэтому тоже нет: `persist.dalvik.vm.usejit`,
 * `persist.sys.dalvik.vm.usejit`, `dalvik.vm.usejitprofiles`, отдельного watcher-а, файла отмены,
 * `sleep 8`, санации `ip rule`, boot-backfill, TTL, срока жизни операции, проверок PID.
 *
 * Наши ДОБАВКИ к референсу (минимум, каждая с назначением — §3 отчёта):
 *  1. `jit_restaurar_boot` / `jit_restaurar_ts` — метка загрузки и время: без них нельзя отличить
 *     «продолжение этой операции в этой загрузке» от «остаток прошлой загрузки» (требование §1.3);
 *  2. подтверждение записи префов чтением обратно — иначе при отказе записи сеть уже выключена,
 *     а вернуть её нечем;
 *  3. отказ второго клика, пока снимок текущей загрузки не разобран (требование §1.3);
 *  4. шаг монтирований выключен константой [MOUNT_STEP_ENABLED] по §1.1 (риск не доказан, см. отчёт).
 */
object JitRef {

    // ─────────────────────────── ключи и константы референса ───────────────────────────

    /** `jit_pedido` — «запрос есть / ещё не сверен» (`w0/j9`, `w0/r7`). */
    internal const val KEY_PEDIDO = "jit_pedido"

    /** `perf_auto_otimizar` — состояние тумблера (тот же ключ, что `PREF_PERF_AUTO_OPTIMIZE`). */
    internal const val KEY_AUTO = "perf_auto_otimizar"

    /** `jit_restaurar` — состояние сети ДО операции в формате референса. */
    internal const val KEY_RESTORE = "jit_restaurar"

    /** Наша метка загрузки ГУ для снимка (см. §1.3 и §3 отчёта). */
    internal const val KEY_RESTORE_BOOT = "jit_restaurar_boot"

    /** Наше время записи снимка — только для лога и решения владельца. */
    internal const val KEY_RESTORE_TS = "jit_restaurar_ts"

    internal const val PROP_USEJIT = "dalvik.vm.usejit"

    /** Окно ожидания слива правил: `while [ $_n -lt 20 ]` в `w0/la.run`. */
    internal const val DRAIN_MAX_S = 20

    /** `0x3a98` = 15000 мс — задержка `postDelayed` перед `e2` в `CentralWatchdogService.onCreate`. */
    internal const val RESTORE_DELAY_MS = 15_000L

    /** Таймаут транспорта для скрипта: в референсе `H1(40000, ...)`. */
    internal const val CHAIN_TIMEOUT_MS = 40_000

    /**
     * Шаг монтирований референса (`umount -l` точек `/data/app/`).
     *
     * ПОЧЕМУ ВЫКЛЮЧЕН (подробно — §1.1 отчёта): на целевой ГУ состав монтирований `/data/app/`
     * полем не подтверждён; `umount -l` снимает точку лениво и для приложений, чьи APK смонтированы
     * оттуда, это может закончиться падением этих приложений. Владелец разрешил механизм, но
     * потребовал не выполнять потенциально опасное действие без подтверждённой обратимости и
     * оценки рисков — поэтому шаг реализован, но по умолчанию не включается; решение за владельцем.
     */
    internal const val MOUNT_STEP_ENABLED = false

    private val RESTORE_RE = Regex("^wifi=([01]),ap=([01])$")

    /** Состояние сети до операции (формат референса). */
    data class Net(val wifi: Boolean, val ap: Boolean) {
        fun wire(): String = "wifi=${if (wifi) 1 else 0},ap=${if (ap) 1 else 0}"
        fun log(): String = wire()
    }

    /** Разбор `jit_restaurar` ровно как в референсе: строка обязана совпасть целиком. */
    fun parseRestore(raw: String?): Net? {
        if (raw == null) return null
        val m = RESTORE_RE.find(raw.trim()) ?: return null
        return Net(wifi = m.groupValues[1] == "1", ap = m.groupValues[2] == "1")
    }

    // ─────────────────────────── скрипт: байт в байт как `la.run` ───────────────────────────

    /**
     * Текст скрипта референса. Литерал восстановлен из строки `w0/la.run` (jadx, `StringBuilder`):
     * `"\n        svc wifi disable\n        _n=0\n        while [ $_n -lt 20 ]; do\n          ...`.
     *
     * [mountStep] добавляет ровно ту строку референса про `/data/app/` (по умолчанию выключено,
     * см. [MOUNT_STEP_ENABLED]); при `false` скрипт совпадает с референсом без этой строки.
     */
    fun chainScript(want: Boolean, mountStep: Boolean = MOUNT_STEP_ENABLED): String {
        val D = "$"
        val mount = "        for _m in ${D}(grep ' /data/app/' /proc/mounts | awk '{print ${D}2}'); do\n" +
            "          umount -l \"${D}_m\" 2>/dev/null\n" +
            "        done\n"
        return "\n        svc wifi disable\n" +
            "        _n=0\n" +
            "        while [ ${D}_n -lt $DRAIN_MAX_S ]; do\n" +
            "          [ \"${D}(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n" +
            "          sleep 1; _n=${D}((${D}_n + 1))\n" +
            "        done\n" +
            (if (mountStep) mount else "") +
            "        setprop $PROP_USEJIT ${if (want) "true" else "false"}\n" +
            "        setprop ctl.restart zygote\n    "
    }

    // ─────────────────────────── проверка результата (`bb`/`r7`) ───────────────────────────

    /** Что означает `getprop` + кэш по правилам `w0/r7`: `ok = (pedido == getprop) && (!pedido || cache != false)`. */
    enum class Verify { OK, NOT_APPLIED, CACHE_MISSING, NO_DATA }

    fun verify(pedido: Boolean, getprop: String, cache: Boolean?): Verify {
        val rt = getprop.trim()
        if (rt.isEmpty()) return Verify.NO_DATA                 // данных нет — не выдумываем успех
        val applied = (rt == "true") == pedido
        if (!applied) return Verify.NOT_APPLIED
        if (!pedido) return Verify.OK                          // выключать JIT — кэш не нужен
        return if (cache == true) Verify.OK else Verify.CACHE_MISSING
    }

    fun verifyLabel(v: Verify): String = when (v) {
        Verify.OK -> "ok"
        Verify.NOT_APPLIED -> "not_applied"
        Verify.CACHE_MISSING -> "cache_missing"
        Verify.NO_DATA -> "unknown"
    }

    // ─────────────────────────── продолжение операции после zygote ───────────────────────────

    /**
     * Что делать с `jit_restaurar` при старте процесса.
     *
     *  • `CONTINUE` — снимок есть и он из ТЕКУЩЕЙ загрузки ГУ: это продолжение той же операции,
     *    возвращаем сохранённое (AP → Wi-Fi) через [RESTORE_DELAY_MS];
     *  • `NO_RESTORE` — снимка нет: сети не касаемся вообще;
     *  • `FOREIGN_BOOT` — снимок от другой загрузки (или загрузку определить нельзя): НИЧЕГО не
     *    пишем и не удаляем, только фиксируем в логе (`need=owner_decision`).
     */
    enum class Continuation { NO_RESTORE, CONTINUE, FOREIGN_BOOT }

    fun continuation(hasRestore: Boolean, bootMatches: Boolean, bootKnown: Boolean): Continuation = when {
        !hasRestore -> Continuation.NO_RESTORE
        bootKnown && bootMatches -> Continuation.CONTINUE
        else -> Continuation.FOREIGN_BOOT
    }

    /**
     * Можно ли начинать новую операцию при существующем снимке.
     * Блокируем только снимок ТЕКУЩЕЙ загрузки (операция могла ещё не завершиться);
     * остаток прошлой загрузки не удаляем, но и не считаем доказательством — новый клик его
     * заменяет своим снимком (это действие пользователя, а не автоматики).
     */
    fun canStart(hasRestore: Boolean, bootMatches: Boolean): Boolean = !(hasRestore && bootMatches)

    /** Итог запуска скрипта — БЕЗ выдачи предположения за факт. */
    enum class ChainOutcome { REPLY_RECEIVED, CHANNEL_LOST }

    fun chainOutcome(exitCode: Int, output: String): ChainOutcome =
        if (output.contains("ERR_") || exitCode == 137) ChainOutcome.CHANNEL_LOST else ChainOutcome.REPLY_RECEIVED

    fun chainOutcomeLabel(o: ChainOutcome): String = when (o) {
        ChainOutcome.REPLY_RECEIVED -> "reply_received"
        ChainOutcome.CHANNEL_LOST -> "channel_lost"
    }
}

// ═══════════════════════════ швы для JVM-прогона и ядро операции ═══════════════════════════

/** Исполнитель shell-команд: на устройстве — [AdbClient], в тестах — подставной. */
interface JitShell {
    fun exec(command: String, timeoutMs: Int): Pair<Int, String>
    /** `/proc/self/maps` текущего процесса (`r7`); `null` — прочитать не удалось. */
    fun cacheVisible(): Boolean?
}

/** Хранилище префов операции: на устройстве — `Prefs2`, в тестах — память. */
interface JitStore {
    fun get(key: String): String?
    fun put(key: String, value: String?): Boolean
    fun getBool(key: String, def: Boolean): Boolean
    fun putBool(key: String, value: Boolean): Boolean
}

/** Состояние сети: на устройстве — `WifiManager`/`NetworkInterface`, в тестах — подставное. */
interface JitNetState {
    fun wifiOn(): Boolean?
    fun apUp(): Boolean?
    fun setWifi(on: Boolean): Boolean
    fun startAp(): Boolean
}

/**
 * Ядро операции — ровно механике референса, без Android-зависимостей (потому и покрыто JVM-тестом).
 *
 * Клик (`j9.onClick`):
 *  1. отказ, если снимок ЭТОЙ загрузки ещё не разобран (наше требование §1.3, см. [JitRef.canStart]);
 *  2. чтение состояния сети → запись снимка → ПОДТВЕРЖДЕНИЕ ЧТЕНИЕМ;
 *  3. только после подтверждения — один скрипт `la.run`.
 *
 * Продолжение после подъёма фреймворка (`e2.run`): снимок этой загрузки → пауза
 * [JitRef.RESTORE_DELAY_MS] → AP, затем Wi-Fi → снимок снимается только при подтверждении.
 *
 * Проверка (`bb`/`r7`): `getprop` + `dalvik-jit-code-cache`, затем `jit_pedido` снимается.
 */
object JitOp {

    /** Почему клик не дошёл до скрипта. */
    enum class ClickResult { STARTED, REFUSED_PENDING, STATE_UNKNOWN, SNAPSHOT_WRITE_FAILED }

    data class Click(
        val result: ClickResult,
        val net: JitRef.Net?,
        val run: ChainRun?,
        val mountStep: Boolean
    )

    class ChainRun(val exitCode: Int, val output: String) {
        val outcome: JitRef.ChainOutcome get() = JitRef.chainOutcome(exitCode, output)
    }

    /**
     * Замок на участок «проверить снимок → записать снимок → подтвердить чтением».
     *
     * Зачем: пользователь может нажать тумблер дважды (второй палец, автоповтор, две панели).
     * Без взаимного исключения два клика прочитают «снимка нет» и оба начнут операцию — тогда
     * `jit_restaurar` перезапишется, и вернуть можно будет не то состояние. Замок держится только
     * на время арифметики и записи префов (несколько миллисекунд), сам скрипт в нём НЕ исполняется.
     */
    private val startLock = Any()

    /**
     * Клик тумблера: снимок + один скрипт. Возврат сети — на следующем старте
     * ([continueAfterBoot]), как `w0/e2.run` после подъёма фреймворка.
     */
    fun click(
        store: JitStore,
        netState: JitNetState,
        shell: JitShell,
        want: Boolean,
        bootTag: String,
        nowMs: Long,
        mountStep: Boolean = JitRef.MOUNT_STEP_ENABLED,
        log: (String) -> Unit = {}
    ): Click {
        val prepared = synchronized(startLock) {
            prepare(store, netState, want, bootTag, nowMs, mountStep, log)
        }
        if (prepared.result != ClickResult.STARTED) return prepared
        val script = JitRef.chainScript(want, mountStep)
        log("JIT_CHAIN_SENT want=$want bytes=${script.toByteArray(Charsets.UTF_8).size} " +
            "mount_step=${if (mountStep) "on" else "off"} text=\"${script.replace('\n', ' ').replace(Regex(" +"), " ").trim()}\"")
        val (code, out) = shell.exec(script, JitRef.CHAIN_TIMEOUT_MS)
        val run = ChainRun(code, out)
        log("JIT_CHAIN_RETURN rc=$code outcome=${JitRef.chainOutcomeLabel(run.outcome)} " +
            "restart=unknown out=\"${out.replace('\n', ' ').trim().take(200)}\" " +
            "(ответ транспорта сам по себе не доказывает ни рестарт, ни его отсутствие)")
        return Click(ClickResult.STARTED, prepared.net, run, mountStep)
    }

    /** Решение «можно ли начинать» + снимок. Вызывается только под [startLock]. */
    private fun prepare(
        store: JitStore,
        netState: JitNetState,
        want: Boolean,
        bootTag: String,
        nowMs: Long,
        mountStep: Boolean,
        log: (String) -> Unit
    ): Click {
        val existingRaw = store.get(JitRef.KEY_RESTORE)
        val existing = JitRef.parseRestore(existingRaw)
        val existingBoot = store.get(JitRef.KEY_RESTORE_BOOT).orEmpty()
        val existingMatches = existingBoot.isNotEmpty() &&
            JitBootMark.sameLoad("$existingBoot:0", bootTag)
        if (existing != null && !JitRef.canStart(true, existingMatches)) {
            log("JIT_APPLIED_REQUEST refused reason=pending_operation restore=${existing.log()} " +
                "boot=$bootTag want=$want (снимок этой загрузки ещё не разобран: сеть не трогаем)")
            return Click(ClickResult.REFUSED_PENDING, existing, null, mountStep)
        }
        if (existing != null && !existingMatches) {
            log("JIT_SNAPSHOT previous_boot restore=${existing.log()} " +
                "saved_boot=${existingBoot.ifEmpty { "unknown" }} boot=$bootTag action=replaced_by_click " +
                "(остаток прошлой загрузки не применялся и автоматически не удалялся)")
        }
        val wifi = netState.wifiOn()
        val ap = netState.apUp()
        if (wifi == null || ap == null) {
            log("JIT_SNAPSHOT skipped reason=state_unknown wifi=$wifi ap=$ap " +
                "(канал состояния недоступен: сеть не трогаем, рестарта не будет)")
            return Click(ClickResult.STATE_UNKNOWN, null, null, mountStep)
        }
        val net = JitRef.Net(wifi = wifi, ap = ap)
        store.put(JitRef.KEY_PEDIDO, if (want) "1" else "0")
        store.putBool(JitRef.KEY_AUTO, want)
        val okRestore = store.put(JitRef.KEY_RESTORE, net.wire()) &&
            store.get(JitRef.KEY_RESTORE) == net.wire()
        val okBoot = store.put(JitRef.KEY_RESTORE_BOOT, bootTag) &&
            store.get(JitRef.KEY_RESTORE_BOOT) == bootTag
        store.put(JitRef.KEY_RESTORE_TS, nowMs.toString())
        log("JIT_SNAPSHOT want=$want net=${net.wire()} boot=$bootTag " +
            "restore=$okRestore boot_ok=$okBoot ts=$nowMs")
        if (!(okRestore && okBoot)) {
            // Вернуть было бы нечего ⇒ не начинаем: сеть ещё не выключена.
            store.put(JitRef.KEY_PEDIDO, null)
            log("JIT_SNAPSHOT failed reason=write_not_confirmed (сеть не трогаем, рестарта не будет)")
            return Click(ClickResult.SNAPSHOT_WRITE_FAILED, net, null, mountStep)
        }
        return Click(ClickResult.STARTED, net, null, mountStep)
    }

    /** Исход продолжения операции на старте процесса. */
    enum class RestoreResult { NO_RESTORE, FOREIGN_BOOT, UNPARSABLE, RESTORED, UNCONFIRMED }

    fun restoreResultLabel(r: RestoreResult): String = when (r) {
        RestoreResult.NO_RESTORE -> "no_restore"
        RestoreResult.FOREIGN_BOOT -> "foreign_boot"
        RestoreResult.UNPARSABLE -> "unparsable"
        RestoreResult.RESTORED -> "restored"
        RestoreResult.UNCONFIRMED -> "unconfirmed"
    }

    /**
     * Продолжение ТОЙ ЖЕ операции после подъёма фреймворка — без второго клика.
     *
     * [sleep] подставляется в тестах (в бою — `Thread.sleep`), поэтому пауза
     * [JitRef.RESTORE_DELAY_MS] проверяется без ожидания.
     */
    fun continueAfterBoot(
        store: JitStore,
        netState: JitNetState,
        bootTag: String,
        sleep: (Long) -> Unit,
        log: (String) -> Unit = {}
    ): RestoreResult {
        val raw = store.get(JitRef.KEY_RESTORE)
        val savedBoot = store.get(JitRef.KEY_RESTORE_BOOT).orEmpty()
        val ts = store.get(JitRef.KEY_RESTORE_TS)?.toLongOrNull() ?: 0L
        val bootKnown = bootTag.isNotEmpty() && savedBoot.isNotEmpty()
        val matches = bootKnown && JitBootMark.sameLoad("$savedBoot:0", bootTag)
        val hasRestore = raw != null || savedBoot.isNotEmpty()
        return when (JitRef.continuation(hasRestore, matches, bootKnown)) {
            JitRef.Continuation.NO_RESTORE -> {
                log("JIT_NET_CONT skipped reason=no_restore_marker boot=$bootTag " +
                    "(обычный старт: сеть не трогаем)")
                RestoreResult.NO_RESTORE
            }
            JitRef.Continuation.FOREIGN_BOOT -> {
                log("JIT_NET_CONT skipped reason=foreign_boot restore=\"${raw ?: "none"}\" " +
                    "saved_boot=${savedBoot.ifEmpty { "unknown" }} boot=$bootTag ts=$ts action=none " +
                    "need=owner_decision (снимок другой загрузки: не применяем и не удаляем)")
                RestoreResult.FOREIGN_BOOT
            }
            JitRef.Continuation.CONTINUE -> {
                val net = JitRef.parseRestore(raw)
                if (net == null) {
                    log("JIT_NET_CONT skipped reason=restore_unparsable raw=\"${raw ?: "none"}\" " +
                        "saved_boot=$savedBoot action=none (формат референса: wifi=<0|1>,ap=<0|1>)")
                    RestoreResult.UNPARSABLE
                } else {
                    log("JIT_NET_CONT start restore=${net.log()} saved_boot=$savedBoot boot=$bootTag " +
                        "ts=$ts delay_ms=${JitRef.RESTORE_DELAY_MS} (продолжение этой же операции)")
                    sleep(JitRef.RESTORE_DELAY_MS)
                    val ok = restoreNetwork(netState, net, "post_zygote", log)
                    if (ok) {
                        store.put(JitRef.KEY_RESTORE, null)
                        store.put(JitRef.KEY_RESTORE_BOOT, null)
                        store.put(JitRef.KEY_RESTORE_TS, null)
                        val cleared = store.get(JitRef.KEY_RESTORE) == null &&
                            store.get(JitRef.KEY_RESTORE_BOOT) == null
                        log("JIT_NET_CONT done restore=${net.log()} marker=${if (cleared) "cleared" else "stays"} " +
                            "(снимаем только после подтверждения чтением)")
                        RestoreResult.RESTORED
                    } else {
                        log("JIT_NET_CONT keep restore=${net.log()} " +
                            "(подтверждения нет — снимок остаётся, вернёмся на следующем старте)")
                        RestoreResult.UNCONFIRMED
                    }
                }
            }
        }
    }

    /**
     * Возврат сохранённого: **AP первым**, затем Wi-Fi (`e2.run`). Возвращает «подтверждено
     * чтением состояния», а НЕ «есть интернет»: `setWifiEnabled(true)`/`startSoftAp` — только запрос.
     */
    fun restoreNetwork(
        netState: JitNetState,
        net: JitRef.Net,
        reason: String,
        log: (String) -> Unit = {}
    ): Boolean {
        var apOk = true
        if (net.ap) {
            val before = netState.apUp()
            if (before != true) netState.startAp()
            val after = netState.apUp()
            apOk = after == true
            log("JIT_NET_RESTORE ap=1 reason=$reason request=${if (before == true) "already_up" else "startSoftAp"} " +
                "state_before=${before?.toString() ?: "unknown"} state_after=${after?.toString() ?: "unknown"}")
        } else {
            log("JIT_NET_RESTORE ap=0 reason=$reason request=none (AP был выключен)")
        }
        var wifiOk = true
        if (net.wifi) {
            val before = netState.wifiOn()
            if (before != true) netState.setWifi(true)
            val after = netState.wifiOn()
            wifiOk = after == true
            log("JIT_NET_RESTORE wifi=1 reason=$reason request=${if (before == true) "already_on" else "setWifiEnabled(true)"} " +
                "state_before=${before?.toString() ?: "unknown"} state_after=${after?.toString() ?: "unknown"}")
        } else {
            log("JIT_NET_RESTORE wifi=0 reason=$reason request=none (Wi-Fi был выключен)")
        }
        log("JIT_NET_RESTORE why=$reason saved=${net.log()} ap_confirmed=$apOk wifi_confirmed=$wifiOk")
        return apOk && wifiOk
    }

    data class VerifyState(
        val runtime: String,
        val pedido: Boolean,
        val cache: Boolean?,
        val verdict: JitRef.Verify
    )

    /** Сверка (`bb`/`r7`): факты, затем снятие `jit_pedido`. Ничего не «дописываем». */
    fun verifyApplied(
        store: JitStore,
        shell: JitShell,
        wantFallback: Boolean,
        log: (String) -> Unit = {}
    ): VerifyState {
        val pedidoRaw = store.get(JitRef.KEY_PEDIDO)
        val runtime = shell.exec("getprop ${JitRef.PROP_USEJIT}", 6_000).second.trim()
        val cache = shell.cacheVisible()
        val pedido = when (pedidoRaw?.trim()) {
            "1" -> true
            "0" -> false
            else -> wantFallback
        }
        val verdict = JitRef.verify(pedido, runtime, cache)
        log("JIT_APPLIED runtime=${runtime.ifEmpty { "empty" }} pedido=$pedido " +
            "code_cache_executavel=${cache?.toString() ?: "unknown"} result=${JitRef.verifyLabel(verdict)} " +
            "restart=not_claimed (рестарт фреймворка по этим данным не подтверждается и не заявляется)")
        if (pedidoRaw != null) {
            store.put(JitRef.KEY_PEDIDO, null)
            log("JIT_PEDIDO removed after verify result=${JitRef.verifyLabel(verdict)}")
        }
        return VerifyState(runtime, pedido, cache, verdict)
    }
}
