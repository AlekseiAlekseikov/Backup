package com.example.voiceapp3.tools

/**
 * iter33p (1.0.61, третий заход) — JIT по механике референса CentralEXAuto 3.3.9 + честные границы.
 *
 * Всё, что ниже, проверено по DEX/smali этого APK (md5 `c3f1664125953b9851bf4483b265135f`):
 *  • клик `w0/j9.onClick` (ветка `pswitch_2b`): `isWifiEnabled()` (catchall→false), `ap0`
 *    (`isUp() && getInetAddresses().hasMoreElements()`, catchall→false) → ОДИН `commit()` префов
 *    (`jit_pedido`(bool), `perf_auto_otimizar`(bool), `jit_restaurar`="wifi=<0|1>,ap=<0|1>")
 *    → `ThreadPoolExecutor.execute(new la(...))`;
 *  • скрипт `w0/la.run` (ветка `pswitch_10e`) — литерал ниже в [REF_SCRIPT]; `H1(0x9c40=40000, …)`,
 *    затем `postDelayed(ua, 0xafc8=45000)` (индикатор прогресса, к сети не относится);
 *  • возврат — `CentralWatchdogService.onCreate`: `getString("jit_restaurar")`, `Pattern
 *    "^wifi=([01]),ap=([01])$"`, `.matches()` → `remove("jit_restaurar").apply()` →
 *    `postDelayed(new e2(oe, service, 0), 0x3a98 = 15000)`; `w0/e2.run`: если `ap` →
 *    `U("pós-JIT", false)`, затем если `wifi` и `!isWifiEnabled()` → `setWifiEnabled(true)`;
 *    результат обоих вызовов НЕ проверяется, в лог идёт `c1()` (wifi сейчас) и `v()` (AP сейчас),
 *    исключение → «JIT: restauro falhou»;
 *  • `U(String,boolean)` («garantirApAberto»): `v()`→true если AP уже в air; guard: если клиентский
 *    Wi-Fi включён и не наш auto-enable — AP НЕ открывается (возврат false); мьютекс `CountDownLatch`
 *    на 25 с; затем `d()` (рефлексия `startSoftAp(WifiConfiguration)` + `setAccessible`, аргумент
 *    `null`), затем ожидание `v()` до `0x4e20 = 20000` мс шагом 1000 мс, до 3 попыток при
 *    «система ответила FAILED», иначе false + «entregando ao ConnAdaptor»;
 *  • сверка `w0/bb` + `w0/r7`: `H1(8000,"getprop dalvik.vm.usejit")`, `trim()=="true"`, `Boolean`
 *    из `/proc/self/maps` (`^\\br[-w]x[ps]\\b`, ошибка → null), `ok=(pedido==getprop) &&
 *    (!pedido || maps != FALSE)`, затем `remove("jit_pedido")`, `toggle.setActive(getprop)`.
 *
 * Чего в референсе НЕТ: `persist.dalvik.vm.usejit`, `persist.sys.dalvik.vm.usejit`,
 * `dalvik.vm.usejitprofiles`, watcher-а, файла отмены, `sleep 8`, санации `ip rule`, boot-backfill,
 * подтверждения рестарта zygote, анализа кода возврата транспорта, атомарного снимка.
 *
 * Что добавлено у нас и почему — в [JitOp] и в §3 отчёта; каждое отличие имеет назначение,
 * ссылку в коде и описание поведения при отказе.
 */
object JitRef {

    // ─────────────────────────── ключи и константы референса ───────────────────────────

    /** `jit_pedido` — «запрос есть / ещё не сверен» (boolean, как в `w0/j9` и `w0/r7`). */
    internal const val KEY_PEDIDO = "jit_pedido"

    /** `perf_auto_otimizar` — состояние тумблера (тот же ключ, что `PREF_PERF_AUTO_OPTIMIZE`). */
    internal const val KEY_AUTO = "perf_auto_otimizar"

    /** `jit_restaurar` — состояние сети ДО операции строкой формата референса. */
    internal const val KEY_RESTORE = "jit_restaurar"

    /**
     * НАШ ключ: состояние операции ОДНИМ значением (`v=1;phase=…;want=…;wifi=…;ap=…;boot=…;ts=…`).
     *
     * Зачем: референс пишет три ключа в одном `commit()`, но `Prefs2` пишет CPS и DPS отдельными
     * вызовами и глотает результат; трёхключевой снимок после смерти процесса может оказаться
     * частичным (в `jit_restaurar` новое, в `jit_op`/`jit_pedido` старое). Одно значение даёт
     * наблюдаемую целостность: либо прежнее целиком, либо новое целиком, и это подтверждается
     * чтением обратно ([JitStore.putStringConfirmed]).
     */
    internal const val KEY_OP = "jit_op"

    internal const val PROP_USEJIT = "dalvik.vm.usejit"

    /** Окно слива правил: `while [ $_n -lt 20 ]` в `w0/la.run`. */
    internal const val DRAIN_MAX_S = 20

    /** `0x3a98` = 15000 мс — `postDelayed` перед `e2` в `CentralWatchdogService.onCreate`. */
    internal const val RESTORE_DELAY_MS = 15_000L

    /** `0x9c40` = 40000 мс — таймаут `H1` для скрипта в `w0/la.run`. */
    internal const val CHAIN_TIMEOUT_MS = 40_000

    /** Ожидание AP: `0x4e20` = 20000 мс шагом 1000 мс (`U`, «AP NÃO subiu em 20s»). */
    internal const val AP_POLL_TOTAL_MS = 20_000L
    internal const val AP_POLL_STEP_MS = 1_000L

    /**
     * Ожидание Wi-Fi после `setWifiEnabled(true)`: в референсе ожидания нет вообще (только лог
     * `c1()` сразу). Наш шаг нужен, чтобы не выдавать запрос за факт; это отличие — §3 отчёта.
     */
    internal const val WIFI_POLL_TOTAL_MS = 10_000L
    internal const val WIFI_POLL_STEP_MS = 500L

    /**
     * Шаг монтирований референса (`umount -l` точек `/data/app/`) — реализован строкой в строку,
     * но ВЫКЛЮЧЕН: обратимость на целевой ГУ не доказана (§1.1 отчёта). Включается одним значением
     * после решения владельца; при включении текст скрипта совпадает с [REF_SCRIPT] целиком.
     */
    internal const val MOUNT_STEP_ENABLED = false

    private val RESTORE_RE = Regex("^wifi=([01]),ap=([01])$")

    /**
     * Результат ЗАПРОСА к Wi-Fi API — три разных факта, которые нельзя смешивать:
     *  • `invoked` — метод вообще удалось вызвать (нет исключения/нет метода);
     *  • `accepted` — API вернул «принято» (для `setWifiEnabled` — его boolean, для
     *    `startSoftAp` — `true`/`null` без исключения; `false` = отказ);
     *  • `detail` — сырой текст/имя метода для лога.
     *
     * Состояние сети читается ОТДЕЛЬНО (`wifiOn`/`apUp`) и только оно считается подтверждением.
     */
    data class Request(val invoked: Boolean, val accepted: Boolean, val detail: String) {
        fun log(): String = "invoked=$invoked accepted=$accepted detail=$detail"
        companion object {
            fun notInvoked(detail: String) = Request(false, false, detail)
            fun rejected(detail: String) = Request(true, false, detail)
            fun accepted(detail: String) = Request(true, true, detail)
        }
    }

    /** Состояние сети до операции (формат референса). */
    data class Net(val wifi: Boolean, val ap: Boolean) {
        fun wire(): String = "wifi=${if (wifi) 1 else 0},ap=${if (ap) 1 else 0}"
        fun log(): String = wire()
    }

    /** Разбор `jit_restaurar` ровно как `e2`: строка обязана совпасть целиком. */
    fun parseRestore(raw: String?): Net? {
        if (raw == null) return null
        val m = RESTORE_RE.find(raw.trim()) ?: return null
        return Net(wifi = m.groupValues[1] == "1", ap = m.groupValues[2] == "1")
    }

    // ─────────────────────────── скрипт: `w0/la.run` байт в строку ───────────────────────────

    private fun scriptBody(want: Boolean, mountStep: Boolean): String {
        val D = "$"
        val drain = "        svc wifi disable\n" +
            "        _n=0\n" +
            "        while [ ${D}_n -lt $DRAIN_MAX_S ]; do\n" +
            "          [ \"${D}(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n" +
            "          sleep 1; _n=${D}((_n + 1))\n" +
            "        done\n"
        val mount = "        for _m in ${D}(grep ' /data/app/' /proc/mounts | awk '{print ${D}2}'); do\n" +
            "          umount -l \"${D}_m\" 2>/dev/null\n" +
            "        done\n"
        val props = "        setprop $PROP_USEJIT ${if (want) "true" else "false"}\n" +
            "        setprop ctl.restart zygote\n    "
        return "\n" + drain + (if (mountStep) mount else "") + props
    }

    /** Полный литерал референса, включая шаг монтирований (эталон для сравнения в тестах). */
    fun referenceScript(want: Boolean): String = scriptBody(want, mountStep = true)

    /** Скрипт, который реально отправляется (по умолчанию без шага монтирований — §1.1). */
    fun chainScript(want: Boolean, mountStep: Boolean = MOUNT_STEP_ENABLED): String =
        scriptBody(want, mountStep)

    // ─────────────────────────── чтение состояния: только допустимые значения ───────────────────────────

    /**
     * Факт `getprop dalvik.vm.usejit` с анализом кода возврата.
     *
     * ЗАЧЕМ ОТЛИЧИЕ ОТ РЕФЕРЕНСА: `w0/bb` берёт `trim()=="true"` и любую другую строку считает
     * `false`, а rc не смотрит вовсе. При недоступном канале (`ERR_ROOT_CHANNEL_UNAVAILABLE`,
     * rc≠0, таймаут) это давало бы ложный `false` — и при `want=false` ложный «успех».
     * Здесь `true`/`false` принимаются только как точные значения, всё остальное — [Prop.UNKNOWN].
     */
    enum class Prop { TRUE, FALSE, UNKNOWN }

    data class PropFact(val value: Prop, val raw: String, val rc: Int, val reason: String) {
        val log: String get() = "getprop=${if (raw.isEmpty()) "empty" else raw} rc=$rc fact=${value.name.lowercase()} reason=$reason"
    }

    fun parseProp(rc: Int, out: String): PropFact {
        // `\n` в конце — обычный хвост вывода shell (`getprop` печатает с переводом строки), поэтому
        // многострочность проверяется на УЖЕ обрезанном значении: иначе любое реальное чтение
        // давало бы `unknown` и сверка не завершалась бы никогда.
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

    /** Итог сверки. `UNKNOWN` — «нет данных», а не «не применено» и не «ок». */
    enum class Verify { OK, NOT_APPLIED, CACHE_MISSING, UNKNOWN }

    fun verify(pedido: Boolean, prop: PropFact, cache: Boolean?): Verify {
        if (prop.value == Prop.UNKNOWN) return Verify.UNKNOWN
        val got = prop.value == Prop.TRUE
        if (got != pedido) return Verify.NOT_APPLIED
        if (!pedido) return Verify.OK
        // `cache == null` — «неизвестно», это НЕ доказательство отсутствия кэша.
        return if (cache == true) Verify.OK else if (cache == false) Verify.CACHE_MISSING else Verify.UNKNOWN
    }

    /**
     * Подтверждённое состояние для тумблера: `on` / `off` / `unknown` (REVIEW 3-го захода, п.5).
     *
     * `off` — только факт: property прочитан как `false` И есть завершённая (`DONE`) операция с
     * `want=false`. Всё остальное, включая ошибку транспорта и «ещё не читали», — `unknown`;
     * ложное «выключено» не показываем никогда.
     */
    fun confirmedState(fact: PropFact?, op: Op?): String = when (fact?.value) {
        Prop.TRUE -> "on"
        Prop.FALSE -> if (op != null && !op.want && op.phase == Phase.DONE) "off" else "unknown"
        null, Prop.UNKNOWN -> "unknown"
    }

    fun verifyLabel(v: Verify): String = when (v) {
        Verify.OK -> "ok"
        Verify.NOT_APPLIED -> "not_applied"
        Verify.CACHE_MISSING -> "cache_missing"
        Verify.UNKNOWN -> "unknown"
    }

    // ─────────────────────────── фазы и снимок одной операции ───────────────────────────

    enum class Phase {
        /** Снимок сохранён и подтверждён; скрипт ещё не отправлен. */
        ARMED,
        /** Скрипт отправлен (ответ транспорта может быть любым, включая таймаут). */
        SENT,
        /** Возврат сети выполнен и подтверждён чтением. */
        RESTORED,
        /** Всё закончено: сеть возвращена, сверка дала определённый результат. */
        DONE
    }

    /**
     * Чем закончился вызов транспорта для НАШЕГО скрипта — единственный доступный признак того,
     * что поздней команды из этой операции уже не будет.
     *
     *  • [REPLIED] — `AdbClient.execute` вернул ответ на наш скрипт: скрипт дошёл до конца, значит
     *    его последняя команда (`setprop ctl.restart zygote`) уже отправлена и ничего «позже» из
     *    этой операции не выполнится;
     *  • [LOST] — таймаут/обрыв (`rc=137`, `ERR_*`): транспорт не дал ответа, а поставленное в
     *    очередь задание он НЕ отменяет (`AdbClient.kt:417-425`), поэтому поздняя команда возможна;
     *  • [UNKNOWN] — на момент записи ответа ещё не было (процесс умер внутри вызова).
     *
     * ВАЖНО: выключенный Wi-Fi/AP — НЕ признак выполнения. `svc wifi disable` — первая команда
     * скрипта, после неё ещё до 20 с слива правил, `setprop` и `ctl.restart zygote` (REVIEW 3-го
     * захода, п.1).
     */
    enum class Shell { UNKNOWN, REPLIED, LOST }

    /**
     * Одна операция. Поля `want`, `net`, `bootTag`, `ts` — из снимка; `pedidoChecked` — была ли
     * сверка `getprop` определённой (иначе намерение не снимаем, см. `w0/r7` и §3 отчёта);
     * `shell` — чем закончился наш вызов транспорта ([Shell]).
     */
    data class Op(
        val phase: Phase,
        val want: Boolean,
        val net: Net,
        val bootTag: String,
        val ts: Long,
        val pedidoChecked: Boolean = false,
        val note: String = "",
        val shell: Shell = Shell.UNKNOWN
    ) {
        fun encode(): String = buildString {
            append("v=1")
            append(";phase=").append(phase.name)
            append(";want=").append(if (want) 1 else 0)
            append(";wifi=").append(if (net.wifi) 1 else 0)
            append(";ap=").append(if (net.ap) 1 else 0)
            append(";boot=").append(bootTag.replace(';', '_').replace('\n', '_'))
            append(";ts=").append(ts)
            append(";checked=").append(if (pedidoChecked) 1 else 0)
            append(";shell=").append(shell.name)
            append(";note=").append(note.replace(';', '_').replace('\n', '_'))
        }

        fun log(): String =
            "phase=${phase.name.lowercase()} want=$want net=${net.wire()} boot=$bootTag ts=$ts " +
                "checked=$pedidoChecked shell=${shell.name.lowercase()}" +
                (if (note.isEmpty()) "" else " note=$note")
    }

    /** Строгий разбор: любое отклонение ⇒ `null` (снимок не трогаем, см. §0.1(6)). */
    fun decodeOp(raw: String?): Op? {
        if (raw == null || raw.isBlank()) return null
        val map = LinkedHashMap<String, String>()
        for (part in raw.trim().split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) return null
            map[part.substring(0, i)] = part.substring(i + 1)
        }
        if (map["v"] != "1") return null
        val phase = Phase.values().firstOrNull { it.name == map["phase"] } ?: return null
        val want = when (map["want"]) { "1" -> true; "0" -> false; else -> return null }
        val wifi = when (map["wifi"]) { "1" -> true; "0" -> false; else -> return null }
        val ap = when (map["ap"]) { "1" -> true; "0" -> false; else -> return null }
        val boot = map["boot"] ?: return null
        val ts = map["ts"]?.toLongOrNull() ?: return null
        if (ts <= 0L) return null
        val checked = when (map["checked"]) { "1" -> true; "0" -> false; null -> false; else -> return null }
        // `shell` появился в третьем заходе: снимок, записанный раньше, читается как UNKNOWN
        // (то есть «выполнение не доказано»), а не как ошибка формата.
        val shell = when (map["shell"]) {
            null -> Shell.UNKNOWN
            else -> Shell.values().firstOrNull { it.name == map["shell"] } ?: return null
        }
        return Op(phase, want, Net(wifi, ap), boot, ts, checked, map["note"] ?: "", shell)
    }

    // ─────────────────────────── опознание загрузки ───────────────────────────

    /**
     * Совпадение тегов загрузки. Три состояния, а не «да/нет»:
     *  • [BootMatch.SAME] — теги равны и это идентификаторы загрузки (`boot_id`, содержит '-' и
     *    длиннее 8 символов) — единственное, что можно считать этой же загрузкой;
     *  • [BootMatch.PREVIOUS] — оба тега — `boot_id`, и они разные (загрузка доказанно другая);
     *  • [BootMatch.UNKNOWN] — всё остальное, включая `epoch…`-теги (часы/NTP), пустые строки и
     *    разные форматы: доказательства нет.
     *
     * ЗАЧЕМ: fallback `epoch ±60 с` не доказывает «та же загрузка» (REVIEW §0.1(6)), поэтому
     * восстановление сети на нём не разрешается — только «сохранить и сообщить владельцу».
     */
    enum class BootMatch { SAME, PREVIOUS, UNKNOWN }

    private fun looksLikeBootId(tag: String): Boolean =
        tag.length > 8 && tag.contains('-') && tag.none { it.isWhitespace() }

    fun bootMatch(savedTag: String, currentTag: String): BootMatch {
        val a = savedTag.trim()
        val b = currentTag.trim()
        if (a.isEmpty() || b.isEmpty()) return BootMatch.UNKNOWN
        if (!looksLikeBootId(a) || !looksLikeBootId(b)) return BootMatch.UNKNOWN
        return if (a == b) BootMatch.SAME else BootMatch.PREVIOUS
    }

    /** Что делать при старте процесса с уже сохранённым снимком. */
    enum class StartDecision {
        /** Снимка нет — обычный старт (только чтение). */
        IDLE,
        /** Скрипт доказанно закончился ([Shell.REPLIED]) ⇒ возвращаем сохранённую сеть. */
        RESTORE,
        /**
         * Снимок этой загрузки, но выполнение скрипта НЕ доказано (нет ответа транспорта):
         * возможна поздняя команда. Ничего не пишем и сеть не трогаем — ждём решения владельца.
         */
        HOLD_UNSAFE_PHASE,
        /** Возвращать нечего (Wi-Fi и AP в снимке были выключены) и скрипт доказанно закончился. */
        NOTHING_TO_RESTORE,
        /** Снимок доказанно другой загрузки: не применяем, не удаляем. */
        KEEP_PREVIOUS_BOOT,
        /** Загрузку доказать нельзя (epoch, пусто, разные форматы): не применяем, не удаляем. */
        KEEP_UNKNOWN_BOOT,
        /** Операция доказанно завершена. */
        DONE
    }

    /**
     * Решение при старте.
     *
     * ЧТО СЧИТАЕТСЯ ДОКАЗАТЕЛЬСТВОМ (REVIEW 3-го захода, пп.1,2,6):
     *  • только [Shell.REPLIED] — ответ транспорта на наш скрипт: скрипт дошёл до последней
     *    команды, поздних команд этой операции не будет;
     *  • НЕ считается: выключенный Wi-Fi/AP (их мог выключить кто угодно, а `svc wifi disable` —
     *    первая команда скрипта), совпадение `getprop`, истёкшее время, `ARMED`, `SENT`, смерть
     *    приложения, пустой журнал и ответ транспорта `rc=137`.
     *
     * Если доказательства нет — [StartDecision.HOLD_UNSAFE_PHASE]: ноль записей, ноль сетевых
     * действий, снимок сохраняется, владельцу сообщается, что нужно решение.
     */
    fun startDecision(op: Op?, currentTag: String): StartDecision {
        if (op == null) return StartDecision.IDLE
        if (op.phase == Phase.DONE) return StartDecision.DONE
        return when (bootMatch(op.bootTag, currentTag)) {
            BootMatch.PREVIOUS -> StartDecision.KEEP_PREVIOUS_BOOT
            BootMatch.UNKNOWN -> StartDecision.KEEP_UNKNOWN_BOOT
            BootMatch.SAME -> when {
                // Ничего не возвращаем, но и «завершено» без доказательства не объявляем.
                op.shell != Shell.REPLIED -> StartDecision.HOLD_UNSAFE_PHASE
                !op.net.wifi && !op.net.ap -> StartDecision.NOTHING_TO_RESTORE
                op.phase == Phase.RESTORED -> StartDecision.DONE
                else -> StartDecision.RESTORE
            }
        }
    }

    /** Отказ клика: пока снимок не разобран, второй клик не начинаем (REVIEW §0.1(1),(6)). */
    enum class ClickGate { ALLOW, REFUSE_PENDING, REFUSE_UNRESOLVED }

    /**
     * Можно ли начинать новую операцию.
     *
     *  • `DONE` — можно (операция доказанно завершена);
     *  • снимок доказанно ПРОШЛОЙ загрузки — можно: после перезагрузки ГУ ни одно задание прежнего
     *    процесса выполняться не может; старый снимок при этом НЕ применяем;
     *  • `ARMED/SENT` этой загрузки (в том числе `wifi=0,ap=0`) — НЕЛЬЗЯ: скрипт мог остаться в
     *    очереди транспорта, и второй клик разрешил бы позднюю команду поверх нового состояния
     *    (REVIEW 3-го захода, п.2). Это честный блок, а не «уже всё хорошо»;
     *  • неразбираемый/неизвестный — нельзя: это может быть активная операция.
     */
    fun clickGate(op: Op?, unresolved: Boolean, currentTag: String): ClickGate = when {
        unresolved -> ClickGate.REFUSE_UNRESOLVED
        op == null -> ClickGate.ALLOW
        op.phase == Phase.DONE -> ClickGate.ALLOW
        bootMatch(op.bootTag, currentTag) == BootMatch.PREVIOUS -> ClickGate.ALLOW
        else -> ClickGate.REFUSE_PENDING
    }

    /** Итог запуска скрипта — без выдачи предположения за факт. */
    enum class ChainOutcome { REPLY_RECEIVED, CHANNEL_LOST }

    fun chainOutcome(exitCode: Int, output: String): ChainOutcome =
        if (exitCode == 137 || output.contains("ERR_")) ChainOutcome.CHANNEL_LOST
        else ChainOutcome.REPLY_RECEIVED

    fun chainOutcomeLabel(o: ChainOutcome): String = when (o) {
        ChainOutcome.REPLY_RECEIVED -> "reply_received"
        ChainOutcome.CHANNEL_LOST -> "channel_lost"
    }
}

// ═══════════════════════════ швы (на устройстве — Android, в тестах — подставные) ═══════════════

/** Исполнитель shell-команд: на устройстве — [AdbClient], в тестах — подставной. */
interface JitShell {
    fun exec(command: String, timeoutMs: Int): Pair<Int, String>
    /** `/proc/self/maps` этого процесса (`r7`); `null` — прочитать не удалось (неизвестно). */
    fun cacheVisible(): Boolean?
}

/**
 * Хранилище операции.
 *
 * `put…Confirmed` обязан вернуть правду: запись дошла до диска (commit) И чтение обратно дало
 * ровно записанное. Это единственная гарантия, на которой строится «снимок безопасен, можно
 * выключать Wi-Fi».
 */
interface JitStore {
    fun getString(key: String): String?
    fun putStringConfirmed(key: String, value: String?): Boolean
    fun getBool(key: String): Boolean?
    fun putBoolConfirmed(key: String, value: Boolean): Boolean
}

/** Состояние сети: на устройстве — `WifiManager`/`NetworkInterface`, в тестах — подставное. */
interface JitNetState {
    fun wifiOn(): Boolean?
    fun apUp(): Boolean?
    /** `e2`/`U`: запрос включения Wi-Fi. Возвращает ЗАПРОС, а не состояние сети. */
    fun setWifi(on: Boolean): JitRef.Request
    /** `U`/`d`: рефлексия `startSoftAp(null)`. Возвращает ЗАПРОС, а не состояние AP. */
    fun startAp(): JitRef.Request
}

/** Ожидание и часы — подставляются в тестах, чтобы проверять паузы без реального сна. */
interface JitWaiter {
    fun sleep(ms: Long)
    fun nowMs(): Long
}

/**
 * Ядро операции. Никакого `android.*` — поэтому вся логика исполняется в JVM-тестах.
 *
 * Порядок клика повторяет `w0/j9.onClick` → `w0/la.run`; возврат — `CentralWatchdogService.onCreate`
 * → `w0/e2.run` (пауза 15000 мс, AP первым, затем Wi-Fi); сверка — `w0/bb`/`w0/r7`.
 * Отличия от референса (все — по требованиям §0.1) перечислены в §3 отчёта.
 */
object JitOp {

    class ChainRun(val exitCode: Int, val output: String) {
        val outcome: JitRef.ChainOutcome get() = JitRef.chainOutcome(exitCode, output)
    }

    enum class ClickResult {
        STARTED,
        /** На этом же `store` прямо сейчас идёт клик или старт процесса — второй не начинаем. */
        BUSY,
        /** Снимок этой загрузки (или неизвестный) ещё не разобран. */
        REFUSED_PENDING,
        /** Снимок повреждён/частичен: не заменяем и не удаляем. */
        REFUSED_UNRESOLVED,
        /** Состояние сети прочитать не удалось: Wi-Fi не выключаем. */
        REFUSED_STATE_UNKNOWN,
        /** Снимок не подтверждён записью: Wi-Fi не выключаем. */
        SNAPSHOT_FAILED
    }

    data class Click(
        val result: ClickResult,
        val op: JitRef.Op?,
        val run: ChainRun?,
        val mountStep: Boolean
    )

    /**
     * ОДНА операция на всё приложение: клик и старт процесса не могут идти одновременно.
     *
     * ЗАЧЕМ (REVIEW 3-го захода, п.4): раньше клик запирал только `prepare`, а после ответа
     * транспорта писал СТАРУЮ копию `op` (в `SENT`/`DONE`), тогда как `onProcessStart` в это время
     * мог записать сверку или `RESTORED` — записи теряли друг друга, а `pending` в хранилище
     * сбрасывался посреди операции. Теперь весь переход — «прочитал → решил → записал» — один
     * неделимый шаг под этим замком, и параллельный вызов получает [ClickResult.BUSY] /
     * [StartDecision.DONE]-независимый отказ (`JIT_BUSY`), не затирая снимок.
     *
     * Транспорт (`shell.exec`) исполняется ВНЕ замка: иначе 40-секундный скрипт блокировал бы UI
     * и старт процесса. Ответ транспорта пишется отдельным коротким шагом (см. [click]).
     */
    private val opLock = Any()

    /** Флаг «идёт клик или старт процесса»: второй вызов получает BUSY и ничего не пишет. */
    private val opGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Снимок на диске: «прочитал → проверил решатель → записал → подтвердил чтением».
     *
     * Возвращает `true`, только если запись подтверждена И снимок не изменился под нами (защита от
     * записи по устаревшей копии: если кто-то успел записать другое значение, мы его не затираем).
     */
    private fun writeOp(store: JitStore, before: String?, op: JitRef.Op, log: (String) -> Unit): Boolean {
        val now = store.getString(JitRef.KEY_OP)
        if (now != before) {
            log("JIT_STORE write_skipped reason=snapshot_changed_by_another_writer " +
                "expected_len=${before?.length ?: 0} actual_len=${now?.length ?: 0} " +
                "(снимок изменён параллельно: свою устаревшую копию не записываем)")
            return false
        }
        val ok = store.putStringConfirmed(JitRef.KEY_OP, op.encode()) &&
            store.getString(JitRef.KEY_OP) == op.encode()
        log("JIT_STORE write phase=${op.phase.name.lowercase()} shell=${op.shell.name.lowercase()} " +
            "confirmed=$ok")
        return ok
    }

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
        if (!opGate.compareAndSet(false, true)) {
            log("JIT_BUSY what=click reason=operation_in_progress action=none " +
                "(клик и старт процесса не идут одновременно)")
            return Click(ClickResult.BUSY, null, null, mountStep)
        }
        try {
            val prepared = synchronized(opLock) {
                prepare(store, netState, want, bootTag, nowMs, mountStep, log)
            }
            if (prepared.result != ClickResult.STARTED) return prepared
            val op = prepared.op ?: return prepared
            return runChain(store, shell, op, want, mountStep, log)
        } finally {
            opGate.set(false)
        }
    }

    /**
     * Отправка скрипта и запись итога — двумя короткими записями.
     *
     * 1. Перед отправкой: `ARMED → SENT/shell=UNKNOWN` — «скрипт отдан транспорту». Если процесс
     *    умрёт внутри вызова, на диске останется `SENT`+`UNKNOWN`, и продолжение на старте
     *    ОТКАЖЕТСЯ что-либо делать (возможна поздняя команда).
     * 2. После ответа транспорта: перечитать снимок и записать `shell=REPLIED|LOST` ПОВЕРХ
     *    актуального значения, а не поверх копии, прочитанной до `shell.exec` (иначе терялись бы
     *    `checked`/`phase`, записанные параллельно сверкой).
     */
    private fun runChain(
        store: JitStore,
        shell: JitShell,
        op: JitRef.Op,
        want: Boolean,
        mountStep: Boolean,
        log: (String) -> Unit
    ): Click {
        val beforeSend = synchronized(opLock) {
            val sent = op.copy(phase = JitRef.Phase.SENT, shell = JitRef.Shell.UNKNOWN)
            val ok = writeOp(store, op.encode(), sent, log)
            if (ok) op.encode() to sent else null
        }
        if (beforeSend == null) {
            log("JIT_PHASE phase=sent confirmed=false action=abort " +
                "(не удалось зафиксировать «скрипт отправляется» — команду не отправляем)")
            return Click(ClickResult.SNAPSHOT_FAILED, op, null, mountStep)
        }
        val script = JitRef.chainScript(want, mountStep)
        log("JIT_CHAIN_SENT want=$want bytes=${script.toByteArray(Charsets.UTF_8).size} " +
            "mount_step=${if (mountStep) "on" else "off"} " +
            "text=\"${script.replace('\n', ' ').replace(Regex(" +"), " ").trim()}\"")
        val (code, out) = shell.exec(script, JitRef.CHAIN_TIMEOUT_MS)
        val run = ChainRun(code, out)
        log("JIT_CHAIN_RETURN rc=$code outcome=${JitRef.chainOutcomeLabel(run.outcome)} restart=unknown " +
            "out=\"${out.replace('\n', ' ').trim().take(200)}\" " +
            "(ответ транспорта сам по себе не доказывает ни рестарт, ни его отсутствие)")
        val shellResult = when (run.outcome) {
            JitRef.ChainOutcome.REPLY_RECEIVED -> JitRef.Shell.REPLIED
            JitRef.ChainOutcome.CHANNEL_LOST -> JitRef.Shell.LOST
        }
        val after = synchronized(opLock) {
            val parsed = JitRef.decodeOp(store.getString(JitRef.KEY_OP))
            val base = if (parsed != null && parsed.bootTag == op.bootTag && parsed.ts == op.ts) parsed else op
            val next = base.copy(
                phase = if (base.phase == JitRef.Phase.ARMED || base.phase == JitRef.Phase.SENT)
                    JitRef.Phase.SENT else base.phase,
                shell = shellResult,
                note = if (!base.net.wifi && !base.net.ap) "nothing_to_restore" else base.note
            )
            val ok = writeOp(store, base.encode(), next, log)
            next to ok
        }
        log("JIT_PHASE phase=${after.first.phase.name.lowercase()} shell=${shellResult.name.lowercase()} " +
            "confirmed=${after.second} restart=not_claimed " +
            "(ответ транспорта — единственный признак завершения НАШЕГО скрипта; " +
            "выключенная сеть им не является)")
        return Click(ClickResult.STARTED, after.first, run, mountStep)
    }

    /**
     * ТОЛЬКО ДЛЯ ТЕСТОВ: попытка записи «поздним писателем», у которого на руках устаревшая копия
     * снимка. Должна быть отклонена [writeOp] — так проверяется отсутствие гонки старых записей
     * (REVIEW 3-го захода, п.4).
     */
    internal fun publishForTest(store: JitStore, stale: JitRef.Op, log: (String) -> Unit): Boolean =
        writeOp(store, stale.encode(), stale.copy(phase = JitRef.Phase.DONE), log)

    private fun prepare(
        store: JitStore,
        netState: JitNetState,
        want: Boolean,
        bootTag: String,
        nowMs: Long,
        mountStep: Boolean,
        log: (String) -> Unit
    ): Click {
        val rawOp = store.getString(JitRef.KEY_OP)
        val op = JitRef.decodeOp(rawOp)
        val unresolved = rawOp != null && rawOp.isNotBlank() && op == null
        when (JitRef.clickGate(op, unresolved, bootTag)) {
            JitRef.ClickGate.REFUSE_UNRESOLVED -> {
                log("JIT_APPLIED_REQUEST refused reason=unresolved_snapshot raw=\"${rawOp?.take(120)}\" " +
                    "boot=$bootTag action=none need=owner_decision " +
                    "(повреждённый/частичный снимок не заменяем и не удаляем)")
                return Click(ClickResult.REFUSED_UNRESOLVED, null, null, mountStep)
            }
            JitRef.ClickGate.REFUSE_PENDING -> {
                log("JIT_APPLIED_REQUEST refused reason=pending_operation op=${op?.log()} " +
                    "match=${JitRef.bootMatch(op?.bootTag ?: "", bootTag).name.lowercase()} " +
                    "(состояние одной операции не перезаписываем; второй клик не запускает скрипт)")
                return Click(ClickResult.REFUSED_PENDING, op, null, mountStep)
            }
            JitRef.ClickGate.ALLOW -> Unit
        }
        if (op != null && op.phase != JitRef.Phase.DONE) {
            log("JIT_SNAPSHOT previous_boot op=${op.log()} boot=$bootTag action=replaced_by_click " +
                "old_kept_until_write=1 (снимок прошлой загрузки не применялся на старте)")
        }
        val wifi = netState.wifiOn()
        val ap = netState.apUp()
        if (wifi == null || ap == null) {
            log("JIT_SNAPSHOT skipped reason=state_unknown wifi=$wifi ap=$ap " +
                "(канал состояния недоступен: Wi-Fi не трогаем, скрипт не отправляем)")
            return Click(ClickResult.REFUSED_STATE_UNKNOWN, null, null, mountStep)
        }
        val net = JitRef.Net(wifi = wifi, ap = ap)
        if (!wifi && !ap) {
            log("JIT_SNAPSHOT nothing_to_restore net=${net.wire()} " +
                "(Wi-Fi и AP были выключены: возвращать нечего, но операция всё равно ждёт " +
                "подтверждения завершения скрипта)")
        }
        val fresh = JitRef.Op(
            phase = JitRef.Phase.ARMED, want = want, net = net, bootTag = bootTag, ts = nowMs
        )
        val payload = fresh.encode()
        val restoreOk = store.putStringConfirmed(JitRef.KEY_OP, payload) &&
            store.getString(JitRef.KEY_OP) == payload
        log("JIT_SNAPSHOT want=$want net=${net.wire()} boot=$bootTag ts=$nowMs " +
            "atomic=${if (restoreOk) "ok" else "failed"} key=${JitRef.KEY_OP}")
        if (!restoreOk) {
            log("JIT_SNAPSHOT failed reason=write_not_confirmed (Wi-Fi не выключен, рестарта не будет)")
            return Click(ClickResult.SNAPSHOT_FAILED, null, null, mountStep)
        }
        // Ключи референса — для совместимости и диагностики. Решения по ним не принимаются:
        // источник истины один ([JitRef.KEY_OP]), потому что три отдельных ключа не атомарны.
        val refRestore = store.putStringConfirmed(JitRef.KEY_RESTORE, net.wire())
        val refPedido = store.putBoolConfirmed(JitRef.KEY_PEDIDO, want)
        val refAuto = store.putBoolConfirmed(JitRef.KEY_AUTO, want)
        log("JIT_REFS jit_restaurar=$refRestore jit_pedido=$refPedido perf_auto_otimizar=$refAuto " +
            "(пишутся после подтверждённого снимка; на безопасность не влияют)")
        return Click(ClickResult.STARTED, fresh, null, mountStep)
    }

    // ─────────────────────────── старт процесса: продолжение операции ───────────────────────────

    class StartOutcome(
        val decision: JitRef.StartDecision,
        val restore: RestoreReport?,
        val verify: VerifyState?,
        val op: JitRef.Op?
    )

    /**
     * Старт процесса. Возврат сети — ТОЛЬКО при доказанной загрузке И признаках выполнения цепочки
     * ([JitRef.startDecision]); одного boot ID и 15 с ожидания недостаточно (REVIEW §0.1(1)).
     * Ничего не удаляем автоматически: снимок переходит в фазу `RESTORED`/`DONE` или остаётся.
     */
    /**
     * Старт процесса: разбор одной операции и продолжение ЕЁ ЖЕ (без второго клика).
     *
     * Общий single-flight с кликом ([opLock]/[opGate]): старт и клик не идут одновременно, поэтому
     * запись `RESTORED` не может быть затёрта устаревшей копией из [click] и наоборот.
     *
     * Записи делает ТОЛЬКО этот путь и только при доказанном основании:
     *  • [JitRef.StartDecision.RESTORE] / [NOTHING_TO_RESTORE] / [DONE] — своя загрузка;
     *  • [JitRef.StartDecision.HOLD_UNSAFE_PHASE] — НОЛЬ записей и НОЛЬ сетевых действий: нет
     *    доказательства, что скрипт уже не выполнится позже; решение за владельцем;
     *  • [KEEP_PREVIOUS_BOOT]/[KEEP_UNKNOWN_BOOT] — снимок не применяем, не удаляем и НЕ
     *    ПЕРЕПИСЫВАЕМ (сверка идёт в режиме `persist=false`: даже `jit_pedido` не трогаем).
     */
    fun onProcessStart(
        store: JitStore,
        netState: JitNetState,
        shell: JitShell,
        bootTag: String,
        waiter: JitWaiter,
        log: (String) -> Unit = {}
    ): StartOutcome {
        if (!opGate.compareAndSet(false, true)) {
            log("JIT_BUSY what=start reason=operation_in_progress boot=$bootTag action=none " +
                "(клик или другой старт уже работает: второй разбор не начинаем)")
            return StartOutcome(JitRef.StartDecision.DONE, null, null, null)
        }
        try {
            return synchronized(opLock) { onProcessStartLocked(store, netState, shell, bootTag, waiter, log) }
        } finally {
            opGate.set(false)
        }
    }

    private fun onProcessStartLocked(
        store: JitStore,
        netState: JitNetState,
        shell: JitShell,
        bootTag: String,
        waiter: JitWaiter,
        log: (String) -> Unit
    ): StartOutcome {
        val rawOp = store.getString(JitRef.KEY_OP)
        val op = JitRef.decodeOp(rawOp)
        if (rawOp != null && rawOp.isNotBlank() && op == null) {
            log("JIT_START snapshot=unresolved raw=\"${rawOp.take(120)}\" action=none " +
                "need=owner_decision boot=$bootTag (не применяем и не удаляем)")
            return StartOutcome(JitRef.StartDecision.KEEP_UNKNOWN_BOOT, null, null, null)
        }
        if (op == null) {
            log("JIT_START snapshot=none boot=$bootTag (обычный старт: сеть не трогаем)")
            // Обычный старт (снимка нет): только чтение. Даже `jit_pedido=false` не пишем —
            // на boot/30 с/ребуте должно быть НОЛЬ записей (мандат §1.2, §1.3).
            val verify = verifyApplied(store, shell, null, false, log)
            return StartOutcome(JitRef.StartDecision.IDLE, null, verify, null)
        }
        val decision = JitRef.startDecision(op, bootTag)
        val wifi = netState.wifiOn()
        val ap = netState.apUp()
        log("JIT_START op=${op.log()} boot=$bootTag match=" +
            "${JitRef.bootMatch(op.bootTag, bootTag).name.lowercase()} " +
            "wifi_now=$wifi ap_now=$ap decision=${decision.name.lowercase()} " +
            "(wifi_now/ap_now — только наблюдение, НЕ признак завершения скрипта)")
        // `persist` — только для снимка, который мы вправе применять (эта загрузка).
        val persist = decision == JitRef.StartDecision.RESTORE ||
            decision == JitRef.StartDecision.NOTHING_TO_RESTORE ||
            decision == JitRef.StartDecision.DONE
        val verify = if (persist || decision == JitRef.StartDecision.HOLD_UNSAFE_PHASE)
            verifyApplied(store, shell, op.want, persist, log) else null
        when (decision) {
            JitRef.StartDecision.RESTORE -> {
                log("JIT_NET_CONT start reason=post_zygote_operation net=${op.net.wire()} " +
                    "delay_ms=${JitRef.RESTORE_DELAY_MS} shell=replied " +
                    "(продолжение этой же операции: скрипт ответил, второго клика нет)")
                waiter.sleep(JitRef.RESTORE_DELAY_MS)
                val rep = restoreNetwork(netState, op.net, waiter, log)
                val base = JitRef.decodeOp(store.getString(JitRef.KEY_OP)) ?: op
                val phase = if (rep.confirmed) JitRef.Phase.RESTORED else base.phase
                val next = base.copy(phase = phase, note = rep.summary)
                val ok = writeOp(store, base.encode(), next, log)
                log("JIT_NET_CONT done confirmed=${rep.confirmed} phase=${phase.name.lowercase()} " +
                    "saved=$ok internet=not_checked " +
                    "(снимок остаётся историей операции и не удаляется)")
                return StartOutcome(decision, rep, verify, next)
            }
            JitRef.StartDecision.NOTHING_TO_RESTORE -> {
                // Оба радио были выключены ещё до клика: возвращать нечего. Завершение фиксируется
                // ТОЛЬКО по ответу транспорта (иначе снимок остался бы активным и второй клик был бы
                // запрещён навсегда — REVIEW 3-го захода, п.2).
                // Перечитываем снимок: сверка выше могла уже пометить `checked`/`note`, и запись
                // по устаревшей копии была бы отброшена защитой `writeOp`.
                val base = JitRef.decodeOp(store.getString(JitRef.KEY_OP)) ?: op
                val next = base.copy(phase = JitRef.Phase.DONE, note = "nothing_to_restore")
                val ok = writeOp(store, base.encode(), next, log)
                log("JIT_NET_CONT skipped reason=nothing_to_restore shell=replied saved=$ok " +
                    "action=none (оба радио были выключены: сеть не трогаем)")
                return StartOutcome(decision, null, verify, next)
            }
            JitRef.StartDecision.HOLD_UNSAFE_PHASE -> {
                log("JIT_NET_CONT skipped reason=unsafe_phase shell=${op.shell.name.lowercase()} " +
                    "net=${op.net.wire()} action=none write=none need=owner_decision " +
                    "(нет ответа транспорта: возможна поздняя команда и рестарт zygote; " +
                    "возврат сети сейчас мог бы отменить чужие изменения)")
                return StartOutcome(decision, null, verify, op)
            }
            JitRef.StartDecision.KEEP_PREVIOUS_BOOT -> {
                log("JIT_NET_CONT skipped reason=previous_boot op=${op.log()} boot=$bootTag " +
                    "action=none write=none need=owner_decision " +
                    "(снимок другой загрузки не применяем, не удаляем и не переписываем)")
                return StartOutcome(decision, null, verify, op)
            }
            JitRef.StartDecision.KEEP_UNKNOWN_BOOT -> {
                log("JIT_NET_CONT skipped reason=boot_unknown saved_boot=${op.bootTag} boot=$bootTag " +
                    "action=none write=none need=owner_decision " +
                    "(загрузку доказать нельзя: epoch/пусто/разные форматы)")
                return StartOutcome(decision, null, verify, op)
            }
            JitRef.StartDecision.DONE, JitRef.StartDecision.IDLE ->
                return StartOutcome(decision, null, verify, op)
        }
    }

    // ─────────────────────────── возврат сети: AP первым, затем Wi-Fi ───────────────────────────

    class RestoreReport(
        val apRequested: Boolean,
        val apConfirmed: Boolean,
        val apReason: String,
        val wifiRequested: Boolean,
        val wifiConfirmed: Boolean,
        val wifiReason: String,
        val dry: String
    ) {
        val confirmed: Boolean get() = apConfirmed && wifiConfirmed
        val summary: String
            get() = "ap=${if (apConfirmed) "ok" else "unconfirmed"}:$apReason " +
                "wifi=${if (wifiConfirmed) "ok" else "unconfirmed"}:$wifiReason"
    }

    /**
     * Возврат сохранённого состояния: **AP первым**, затем Wi-Fi (как `e2`), с ожиданием факта.
     *
     * ОТЛИЧИЕ ОТ РЕФЕРЕНСА (§3 отчёта): `e2` результат `U()`/`setWifiEnabled()` не проверяет и
     * печатает только «сейчас»; здесь каждый шаг ждёт подтверждения состояния (AP — до 20 с шагом
     * 1 с как `U`, Wi-Fi — до 10 с шагом 0.5 с) и пишет отдельно ЗАПРОС, СОСТОЯНИЕ и ОШИБКУ.
     * «Запрошено» ≠ «включено» ≠ «есть интернет» — три разных факта в логе.
     */
    fun restoreNetwork(
        netState: JitNetState,
        net: JitRef.Net,
        waiter: JitWaiter,
        log: (String) -> Unit = {}
    ): RestoreReport {
        var apRequested = false
        var apConfirmed = true
        var apReason = "not_needed"
        if (net.ap) {
            apRequested = true
            when (netState.apUp()) {
                true -> { apConfirmed = true; apReason = "already_up"; log("JIT_NET_RESTORE ap=1 request=none state=up reason=already_up") }
                null -> { apConfirmed = false; apReason = "state_unknown"; log("JIT_NET_RESTORE ap=1 request=none state=unknown reason=state_unknown") }
                false -> {
                    // Guard `U`: если клиентский Wi-Fi включён — за радио не спорим. Но НЕИЗВЕСТНОЕ
                    // состояние (`null`) выключенным не считаем (REVIEW 3-го захода, п.7): в этом
                    // случае запрос AP не отправляем, а честно сообщаем `wifi_state_unknown`.
                    when (netState.wifiOn()) {
                        true -> {
                            apConfirmed = false; apReason = "wifi_client_on_guard"
                            log("JIT_NET_RESTORE ap=1 request=none state=down reason=wifi_client_on_guard " +
                                "(как U: клиентский Wi-Fi включён — AP не открываю)")
                        }
                        null -> {
                            apConfirmed = false; apReason = "wifi_state_unknown"
                            log("JIT_NET_RESTORE ap=1 request=none state=down reason=wifi_state_unknown " +
                                "(состояние Wi-Fi прочитать нельзя: неизвестное не считаем выключенным, " +
                                "запрос AP не отправляем)")
                        }
                        else -> {
                            val res: JitRef.Request = netState.startAp()
                            log("JIT_NET_RESTORE ap=1 request=startSoftAp invoked=${res.invoked} " +
                                "accepted=${res.accepted} detail=${res.detail} state_before=down " +
                                "(вызов ≠ принятый запрос ≠ подтверждённое состояние)")
                            val up = poll(netState::apUp, JitRef.AP_POLL_TOTAL_MS, JitRef.AP_POLL_STEP_MS, waiter)
                            apConfirmed = up == true
                            apReason = when {
                                up == true && !res.accepted -> "up_but_request_not_accepted"
                                up == true -> "up_after_request"
                                up == false && !res.invoked -> "api_unavailable"
                                up == false && !res.accepted ->
                                    "request_rejected_not_up_${JitRef.AP_POLL_TOTAL_MS / 1000}s"
                                up == false -> "not_up_after_${JitRef.AP_POLL_TOTAL_MS / 1000}s"
                                else -> "state_unknown_after_request"
                            }
                            log("JIT_NET_RESTORE ap=1 state_after=${up?.toString() ?: "unknown"} " +
                                "confirmed=$apConfirmed reason=$apReason invoked=${res.invoked} " +
                                "accepted=${res.accepted} (подтверждено только чтение состояния AP)")
                        }
                    }
                }
            }
        } else {
            log("JIT_NET_RESTORE ap=0 request=none (AP был выключен)")
        }
        var wifiRequested = false
        var wifiConfirmed = true
        var wifiReason = "not_needed"
        if (net.wifi) {
            wifiRequested = true
            when (netState.wifiOn()) {
                true -> { wifiConfirmed = true; wifiReason = "already_on"; log("JIT_NET_RESTORE wifi=1 request=none state=on") }
                null -> { wifiConfirmed = false; wifiReason = "state_unknown"; log("JIT_NET_RESTORE wifi=1 request=none state=unknown") }
                false -> {
                    val res: JitRef.Request = netState.setWifi(true)
                    log("JIT_NET_RESTORE wifi=1 request=setWifiEnabled(true) invoked=${res.invoked} " +
                        "accepted=${res.accepted} detail=${res.detail} state_before=off " +
                        "(вызов ≠ принятый запрос ≠ подтверждённое состояние)")
                    val on = poll(netState::wifiOn, JitRef.WIFI_POLL_TOTAL_MS, JitRef.WIFI_POLL_STEP_MS, waiter)
                    wifiConfirmed = on == true
                    wifiReason = when {
                        on == true && !res.accepted -> "on_but_request_not_accepted"
                        on == true -> "on_after_request"
                        on == false && !res.invoked -> "api_unavailable"
                        on == false && !res.accepted -> "request_rejected_still_off_${JitRef.WIFI_POLL_TOTAL_MS / 1000}s"
                        on == false -> "still_off_after_${JitRef.WIFI_POLL_TOTAL_MS / 1000}s"
                        else -> "state_unknown_after_request"
                    }
                    log("JIT_NET_RESTORE wifi=1 state_after=${on?.toString() ?: "unknown"} " +
                        "confirmed=$wifiConfirmed reason=$wifiReason invoked=${res.invoked} " +
                        "accepted=${res.accepted} (подтверждено только чтение состояния Wi-Fi)")
                }
            }
        } else {
            log("JIT_NET_RESTORE wifi=0 request=none (Wi-Fi был выключен)")
        }
        val dry = "wifi_online=not_checked"
        log("JIT_NET_RESTORE result ap=$apConfirmed($apReason) wifi=$wifiConfirmed($wifiReason) " +
            "internet=$dry (подтверждено состояние, а не наличие интернета)")
        return RestoreReport(apRequested, apConfirmed, apReason, wifiRequested, wifiConfirmed, wifiReason, dry)
    }

    private fun poll(read: () -> Boolean?, totalMs: Long, stepMs: Long, waiter: JitWaiter): Boolean? {
        val t0 = waiter.nowMs()
        var last: Boolean? = read()
        while (last != true && waiter.nowMs() - t0 < totalMs) {
            waiter.sleep(stepMs)
            last = read()
        }
        return last
    }

    // ─────────────────────────── сверка `bb`/`r7` с честным rc ───────────────────────────

    class VerifyState(
        val pedido: Boolean?,
        val prop: JitRef.PropFact,
        val cache: Boolean?,
        val verdict: JitRef.Verify
    )

    /**
     * Сверка: `getprop` (с rc) + `dalvik-jit-code-cache` (`/proc/self/maps`, `null` — неизвестно).
     *
     * `jit_pedido`/`jit_op.checked` снимаются только при ОПРЕДЕЛЁННОМ результате (ok/not_applied/
     * cache_missing). При `unknown` намерение сохраняется и сверка повторится на следующем старте —
     * отличие от `w0/r7`, который удаляет `jit_pedido` всегда (REVIEW §0.1(4)).
     */
    fun verifyApplied(
        store: JitStore,
        shell: JitShell,
        wantFallback: Boolean?,
        persist: Boolean = true,
        log: (String) -> Unit = {}
    ): VerifyState {
        val op = JitRef.decodeOp(store.getString(JitRef.KEY_OP))
        val pedidoPref = store.getBool(JitRef.KEY_PEDIDO)
        val pedido = op?.want ?: pedidoPref ?: wantFallback
        val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", 8_000)
        val prop = JitRef.parseProp(rc, out)
        val cache = shell.cacheVisible()
        val verdict = if (pedido == null) JitRef.Verify.UNKNOWN
        else JitRef.verify(pedido, prop, cache)
        log("JIT_APPLIED ${prop.log} pedido=${pedido ?: "none"} " +
            "code_cache_executavel=${cache?.toString() ?: "unknown"} result=${JitRef.verifyLabel(verdict)} " +
            "restart=not_claimed (рестарт фреймворка по этим данным не подтверждается и не заявляется)")
        val definitive = verdict != JitRef.Verify.UNKNOWN
        if (!persist) {
            // Снимок чужой/неопознанной загрузки: результат сверки только сообщаем, состояние
            // операции не меняем (`jit_pedido`/`checked` не трогаем) — иначе мы бы «переписывали»
            // снимок, который не имеем права применять (REVIEW §0.1(1),(6)).
            log("JIT_PEDIDO kept reason=snapshot_not_applicable result=${JitRef.verifyLabel(verdict)} " +
                "write=none (снимок не этой загрузки/неопознан: не применяем, не удаляем, не меняем)")
            return VerifyState(pedido, prop, cache, verdict)
        }
        // НАМЕРЕНИЕ СНИМАЕМ ТОЛЬКО ПО ОПЕРАЦИИ, А НЕ ПО СОВПАДЕНИЮ PROPERTY (REVIEW 3-го захода, п.8):
        // `getprop` может показать старое значение от прошлой операции, а `shell` мог вообще не
        // выполниться. Поэтому `jit_pedido`/`checked` снимаются только когда (а) транспорт ответил
        // на наш скрипт и (б) намерение этой операции — «включить JIT» (`want=true`).
        //   • `want=false`: «выключено» и «не включилось» выглядят одинаково ⇒ НИКОГДА не снимаем;
        //   • `shell != REPLIED`: выполнение не доказано ⇒ не снимаем;
        //   • `unknown`: не снимаем.
        val settled = definitive && op?.want == true && op.shell == JitRef.Shell.REPLIED &&
            op.phase != JitRef.Phase.ARMED
        if (settled) {
            val pedidoSaved = store.putBoolConfirmed(JitRef.KEY_PEDIDO, false)
            val base = JitRef.decodeOp(store.getString(JitRef.KEY_OP)) ?: op!!
            val next = base.copy(
                phase = if (base.phase == JitRef.Phase.RESTORED) JitRef.Phase.DONE else base.phase,
                pedidoChecked = true,
                note = base.note.ifEmpty { "verify=${JitRef.verifyLabel(verdict)}" }
            )
            val opSaved = writeOp(store, base.encode(), next, log)
            log("JIT_PEDIDO cleared after verify result=${JitRef.verifyLabel(verdict)} " +
                "saved_pedido=$pedidoSaved saved_op=$opSaved shell=replied")
        } else {
            val why = when {
                op == null -> "snapshot_absent"
                op.want.not() -> "want_false_not_distinguishable"
                op.shell != JitRef.Shell.REPLIED -> "shell_not_proven:${op.shell.name.lowercase()}"
                !definitive -> "verify_unknown"
                else -> "not_settled"
            }
            log("JIT_PEDIDO kept result=${JitRef.verifyLabel(verdict)} reason=$why write=none " +
                "(доступность property, состояние runtime, выполнение операции и факт рестарта — " +
                "разные вещи; намерение сохраняем)")
        }
        if (verdict == JitRef.Verify.NOT_APPLIED || verdict == JitRef.Verify.CACHE_MISSING) {
            log("JIT_NOT_APPLIED reason=${JitRef.verifyLabel(verdict)} pedido=$pedido " +
                "getprop=${prop.raw.ifEmpty { "empty" }} cache=${cache?.toString() ?: "unknown"} " +
                "(факт, а не предположение; ничего не дозаписываем)")
        }
        return VerifyState(pedido, prop, cache, verdict)
    }

    fun readProp(shell: JitShell): JitRef.PropFact {
        val (rc, out) = shell.exec("getprop ${JitRef.PROP_USEJIT}", 8_000)
        return JitRef.parseProp(rc, out)
    }
}
