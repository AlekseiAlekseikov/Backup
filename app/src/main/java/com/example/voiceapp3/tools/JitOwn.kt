package com.example.voiceapp3.tools

/**
 * iter51 §7 (в промпте — §6/§7 JIT) — ВЛАДЕНИЕ НЕОПРЕДЕЛЁННОЙ JIT-ОПЕРАЦИЕЙ.
 *
 * ─────────────────────────── Что было не так ───────────────────────────
 * `JitNetCore` помечал операцию `undefined`, если ответ транспорта не пришёл, и блокировал
 * повторный клик «до свидетельства». Свидетельств было два: старт zygote после отметки или
 * истечение окна. Ни одно из них не относится к САМОЙ команде:
 *  • старт zygote доказывает лишь, что zygote когда-то стартовал (он мог упасть сам);
 *  • окно доказывает только терпение владельца.
 * При этом сама старая команда никак не «отменялась»: доставленный, но ещё не выполненный
 * payload мог выполниться позже — уже после того, как владелец нажал тумблер снова.
 *
 * ─────────────────────────── Что сделано ───────────────────────────
 * В настоящую цепочку добавлены ДВА файла на устройстве, которые создаёт только сама цепочка:
 *
 *  1. **claim** `/data/local/tmp/voiceapp3_jit.claim` — «чей сейчас JIT». Пишется КОРОТКОЙ
 *     командой перед отправкой payload, то есть ДО любых изменений настроек; содержимое —
 *     `op=<id>;ts=<…>;want=<0|1>`.
 *  2. **terminal** `/data/local/tmp/voiceapp3_jit_<id>.term` — `START` пишет начало, `trap EXIT`
 *     пишет `END rc=<код>`. Пишет ТОЛЬКО исполняемый payload, поэтому файл является фактом именно
 *     этой операции с её id.
 *
 *  Payload начинается с ГВАРДИИ: если claim принадлежит другой операции, payload печатает
 *  `JIT_REFUSED` и выходит с кодом 0, НЕ трогая сеть, свойства и zygote. Это и есть сериализация:
 *  позднее исполнение устаревшего payload становится доказанным no-op, а не «надеждой».
 *
 *  Неизвестность снимается terminal-файлом (см. [parseTerminal]): `END rc=…` — операция
 *  ЗАВЕРШИЛАСЬ; `START` без `END` — НАЧАЛАСЬ и ещё идёт; файла нет — либо не начиналась, либо
 *  не доставлена (это по-прежнему «неизвестно», и мы так и говорим).
 *
 *  Границы честно: гвардия защищает от ПОЗДНЕГО СТАРТА, но не отменяет уже начавшийся payload;
 *  запись claim/terminal может не дойти (тогда исход остаётся неизвестным). Ни одно из этих
 *  наблюдений не доказывает JIT-компиляцию и не доказывает рестарт zygote.
 */
internal object JitOwn {

    /** Каталог на устройстве (в тестах подменяется на временный). */
    const val BASE = "/data/local/tmp"

    /** Единственная точка сериализации: владелец JIT на устройстве (намерение). */
    const val CLAIM = "$BASE/voiceapp3_jit.claim"

    /**
     * iter52 §7.1 — БЛОКИРОВКА ИСПОЛНЕНИЯ (не намерения).
     *
     * Claim отвечает на вопрос «чья операция последняя», но не даёт взаимного исключения:
     * две операции могли записать claim по очереди и обе дойти до первого опасного действия
     * (claim пишется ОТДЕЛЬНОЙ командой, между записью и запуском payload есть окно).
     * Здесь — атомарный захват файла через noclobber (`set -C`): файл либо создаём мы (тогда
     * владеем), либо он уже чей-то (тогда payload отказывается работать ДО side effect).
     */
    const val LOCK = "$BASE/voiceapp3_jit.lock"

    /** Через сколько секунд брошенная блокировка считается мёртвой (и это ПИШЕТСЯ в лог). */
    const val LOCK_STALE_S = 180

    /** Стабильный префикс terminal-файлов. */
    const val TERM_PREFIX = "$BASE/voiceapp3_jit_"

    fun termPath(opId: String, base: String = BASE): String = "$base/voiceapp3_jit_$opId.term"

    fun claimPath(base: String = BASE): String = "$base/voiceapp3_jit.claim"

    fun lockPath(base: String = BASE): String = "$base/voiceapp3_jit.lock"

    /**
     * Состояние операции. Различаем ровно то, что можно доказать:
     *  • [CREATED] — только намерение, на устройство ничего не ушло;
     *  • [CLAIMED] — claim записан и проверен чтением (мир знает про эту операцию);
     *  • [SENT] — payload передан транспорту (доставка ≠ исполнение);
     *  • [STARTED] — на устройстве есть `START` этой операции;
     *  • [FINISHED] — на устройстве есть `END rc=…` этой операции;
     *  • [REFUSED] — payload отказался работать: claim уже чужой (доказанный no-op);
     *  • [UNKNOWN] — ни отказа, ни terminal-файла: исход НЕИЗВЕСТЕН (не «не выполнено»).
     */
    enum class State { CREATED, CLAIMED, SENT, STARTED, FINISHED, REFUSED, UNKNOWN }

    /** Запись операции: id, время, желаемое состояние, фаза. */
    data class Op(
        val id: String,
        val ts: Long,
        val want: Boolean,
        val state: State,
        /** Кто владеет: обычно идентификатор процесса/сессии, чтобы не приписать чужое. */
        val owner: String = ""
    ) {
        fun wire(): String =
            "op=$id;ts=$ts;want=${if (want) 1 else 0};state=${state.name.lowercase()}" +
                (if (owner.isEmpty()) "" else ";owner=$owner")

        /** Строка для claim-файла: без состояния (оно локальное) — только владение. */
        fun claimLine(): String = "op=$id;ts=$ts;want=${if (want) 1 else 0}"

        companion object {
            private val RE = Regex(
                "^op=([A-Za-z0-9_.-]{1,64});ts=(\\d{1,19});want=([01]);state=" +
                    "(created|claimed|sent|started|finished|refused|unknown)(?:;owner=([A-Za-z0-9_.-]{0,64}))?$"
            )

            /** `null` — запись отсутствует/повреждена: это «неизвестно», а не «операции не было». */
            fun decode(raw: String?): Op? {
                if (raw == null) return null
                val m = RE.matchEntire(raw.trim()) ?: return null
                return Op(
                    id = m.groupValues[1],
                    ts = m.groupValues[2].toLongOrNull() ?: return null,
                    want = m.groupValues[3] == "1",
                    state = State.values().first { it.name.equals(m.groupValues[4], true) },
                    owner = m.groupValues[5]
                )
            }

            /** Разобрать claim-файл: `op=<id>;ts=…;want=…`. `null` — claim не наш формат. */
            fun parseClaim(raw: String?): Op? {
                if (raw == null) return null
                val m = Regex("^op=([A-Za-z0-9_.-]{1,64});ts=(\\d{1,19});want=([01])$")
                    .matchEntire(raw.trim()) ?: return null
                return Op(
                    id = m.groupValues[1],
                    ts = m.groupValues[2].toLongOrNull() ?: return null,
                    want = m.groupValues[3] == "1",
                    state = State.CLAIMED
                )
            }
        }
    }

    /** Короткая команда записи claim (много меньше 4096 байт — идёт обычным `execute`). */
    fun claimCmd(op: Op, base: String = BASE): String {
        val line = op.claimLine()
        // `umask`-безопасно: файл в /data/local/tmp, читается только нашей цепочкой.
        val target = claimPath(base)
        return "printf '%s\\n' '" + line.replace("'", "") + "' > " + target +
            " 2>/dev/null && cat " + target + " 2>/dev/null"
    }

    /**
     * Короткая команда чтения «claim + terminal» одной операцией. Печатает две секции:
     * `CLAIM=<строка>` и `TERM=<строки через | >`.
     */
    fun readCmd(opId: String, base: String = BASE): String =
        "echo CLAIM=\"\$(cat ${claimPath(base)} 2>/dev/null)\"; " +
            "echo TERM=\"\$(cat ${termPath(opId, base)} 2>/dev/null | tr '\\n' '|')\""

    /**
     * Гвардия payload: доказанная защита от позднего СТАРТА устаревшей операции.
     *
     * Вставляется в самое начало скрипта, до любых side effect. При чужом claim печатает
     * `JIT_REFUSED` и выходит — `svc wifi disable`, `setprop`, `ctl.restart zygote` не выполняются.
     */
    fun guard(op: Op, base: String = BASE): String {
        val id = op.id
        val term = termPath(id, base)
        val claim = claimPath(base)
        val lock = lockPath(base)
        val claimLine = "op=$id;ts=${op.ts}"
        return buildString {
            append("_vc=\$(cat ").append(claim).append(" 2>/dev/null)\n")
            // Отказ ОБЯЗАН оставить след в terminal-файле: иначе «payload отказался» виден только
            // по stdout, а stdout мог не доехать — и мы снова гадаем.
            append("_refuse() {\n")
            append("  echo \"REFUSED op=").append(id)
            append(" reason=\$1 detail=\$2 claim=\${_vc:-none}\" >> ").append(term).append(" 2>/dev/null\n")
            append("  echo \"END rc=0 op=").append(id).append("\" >> ").append(term).append(" 2>/dev/null\n")
            append("  echo \"JIT_REFUSED op=").append(id)
            append(" reason=\$1 claim=\${_vc:-none} lock=\${_lo:-none}\"\n")
            append("  exit 0\n}\n")
            // 1) поздний СТАРТ устаревшей операции: claim уже чужой.
            append("case \"\$_vc\" in\n")
            append("  \"op=").append(id).append(";\"*|op=").append(id).append(") : ;;\n")
            append("  *) _refuse superseded claim_foreign ;;\n")
            append("esac\n")
            // 2) ВЗАИМНОЕ ИСКЛЮЧЕНИЕ: атомарный захват lock ДО первого опасного действия.
            append("_lk_ours=0; _lo=\"\"\n")
            append("if [ -f ").append(lock).append(" ]; then\n")
            append("  _lo=\$(cat ").append(lock).append(" 2>/dev/null)\n")
            append("  case \"\$_lo\" in\n")
            append("    \"").append(claimLine).append("\"|\"").append(id).append("\") _lk_ours=1 ;;\n")
            append("    *)\n")
            append("      _lts=\$(printf '%s' \"\$_lo\" | sed -n 's/.*ts=\\([0-9][0-9]*\\).*/\\1/p')\n")
            append("      _now=\$(date +%s 2>/dev/null)\n")
            append("      if [ -n \"\$_lts\" ] && [ -n \"\$_now\" ] && \\\n")
            append("         [ \$((_now - _lts)) -gt ").append(LOCK_STALE_S).append(" ] 2>/dev/null; then\n")
            append("        echo \"JIT_LOCK_STALE owner=\\u00ab\$_lo\\u00bb age_s=\$((_now - _lts)) action=takeover\"\n")
            append("        printf '%s\\n' '").append(claimLine)
            append("' > ").append(lock).append(" 2>/dev/null && _lk_ours=1\n")
            append("      fi ;;\n")
            append("  esac\n")
            append("else\n")
            append("  ( set -C; printf '%s\\n' '").append(claimLine).append("' > ").append(lock)
            append(" ) 2>/dev/null && _lk_ours=1\n")
            append("fi\n")
            append("if [ \"\$_lk_ours\" != \"1\" ]; then _refuse lock_held; fi\n")
            // 3) Начало операции — только после полученной блокировки.
            append("echo \"START ts=\$(date +%s) op=").append(id).append("\" > ").append(term)
            append(" 2>/dev/null\n")
            // 4) END + освобождение ТОЛЬКО своей блокировки.
            append("trap 'echo \"END rc=\$? op=").append(id).append("\" >> ").append(term)
            append(" 2>/dev/null; ")
            append("_lo2=\$(cat ").append(lock).append(" 2>/dev/null); ")
            append("case \"\$_lo2\" in \"").append(claimLine).append("\"|\"").append(id)
            append("\") rm -f ").append(lock).append(" 2>/dev/null ;; esac' EXIT\n")
        }
    }

    /** Факт из terminal-файла: что именно доказано про ЭТУ операцию. */
    data class Terminal(
        val started: Boolean,
        val finished: Boolean,
        /** Код завершения payload (если `END` есть). */
        val rc: Int?,
        /** payload отказался работать: claim уже был чужой. */
        val refused: Boolean,
        /** В claim лежит ДРУГАЯ операция. */
        val claimOther: Boolean,
        /** Claim чужой/испорчен — описание для журнала. */
        val note: String = ""
    ) {
        fun wire(): String = "started=$started finished=$finished rc=${rc ?: "?"} " +
            "refused=$refused claim_other=$claimOther"
    }

    /**
     * Разбор вывода [readCmd].
     *
     * @param opId чья операция интересует.
     * @param out  вывод команды чтения (возможно, с ошибкой транспорта).
     * @return `null` — прочитать НЕ УДАЛОСЬ (неизвестность не снимается).
     */
    fun parseTerminal(opId: String, out: String, rc: Int = 0): Terminal? {
        if (rc != 0 || out.contains("ERR_")) return null
        val claimLine = Regex("CLAIM=\"?([^\"\\n]*)\"?").find(out)?.groupValues?.get(1)?.trim()
        val termRaw = Regex("TERM=\"?([^\"\\n]*)\"?").find(out)?.groupValues?.get(1) ?: ""
        if (claimLine == null && termRaw.isEmpty()) return null
        val claim = Op.parseClaim(claimLine)
        val lines = termRaw.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        // iter52 §7.2: строки terminal'а принимаются ТОЛЬКО со своим `op=<id>`. Файл назван по id,
        // но id мог быть переиспользован (перезапуск/повтор клика), а на устройстве мог остаться
        // файл прошлой операции: считать чужой `END rc=0` своим завершением нельзя.
        val mine = lines.filter { Regex("(^| )op=" + Regex.escape(opId) + "( |$)").containsMatchIn(it) }
        val foreignCount = lines.size - mine.size
        val startLine = mine.firstOrNull { it.startsWith("START") }
        val endLine = mine.lastOrNull { it.startsWith("END") }
        val rcEnd = endLine?.let { Regex("rc=(-?\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        // Отказ считается доказанным, если он есть в terminal'е НАШЕЙ операции ИЛИ в stdout
        // с нашим id (payload печатает `JIT_REFUSED op=<id> …`).
        val refused = mine.any { it.startsWith("REFUSED") } ||
            Regex("JIT_REFUSED op=" + Regex.escape(opId) + "( |$)").containsMatchIn(out) ||
            Regex("JIT_REFUSED op=" + Regex.escape(opId) + "( |$)").containsMatchIn(termRaw)
        val startOk = startLine != null
        val finishOk = endLine != null && rcEnd != null
        return Terminal(
            started = startOk,
            finished = finishOk,
            rc = rcEnd,
            refused = refused,
            claimOther = claim != null && claim.id != opId,
            note = when {
                claim == null && claimLine.isNullOrEmpty() -> "claim_absent"
                claim == null -> "claim_unparsable"
                claim.id == opId -> "claim_ours"
                else -> "claim_foreign"
            } + (if (foreignCount > 0) ";term_lines_other_op=$foreignCount" else "") +
                (if (lines.isNotEmpty() && mine.isEmpty()) ";term_only_foreign_op" else "")
        )
    }

    /**
     * Доказан ли отказ по РЕАЛЬНОМУ stdout — без чтения terminal'а. Нужно на случай, когда
     * terminal прочитать не удалось, а payload всё же ответил.
     */
    fun refusedInOutput(opId: String, out: String): Boolean =
        Regex("JIT_REFUSED op=" + Regex.escape(opId) + "( |$)").containsMatchIn(out)

    /**
     * Фаза операции ПО ФАКТАМ устройства (то, что можно заявить).
     *
     * `sentAck` — транспорт вернул ответ на нашу команду; `term` — прочитанный terminal.
     */
    fun classify(sentAck: Boolean, term: Terminal?): State = when {
        term == null -> if (sentAck) State.SENT else State.UNKNOWN
        term.refused -> State.REFUSED
        term.finished -> State.FINISHED
        term.started -> State.STARTED
        sentAck -> State.SENT
        else -> State.UNKNOWN
    }

    /** Чем заканчивается вывод фазы в журнале: одной строкой, без обещаний. */
    fun stateNote(state: State): String = when (state) {
        State.CREATED -> "намерение, на устройство ничего не ушло"
        State.CLAIMED -> "claim записан и проверен; payload ещё не отправлен"
        State.SENT -> "payload передан транспорту — доставка не равна исполнению"
        State.STARTED ->
            "payload НАЧАЛ работу (START есть, END нет): отменить его нельзя, " +
                "ждём END; повторный клик перехватит claim и отменит только ПОЗДНИЙ старт"
        State.FINISHED -> "payload ЗАВЕРШИЛСЯ (END rc≠?) — исход команды известен, JIT не доказан"
        State.REFUSED -> "payload отказался работать: claim уже принадлежал другой операции"
        State.UNKNOWN ->
            "ни отказа, ни terminal-файла: НЕ доставлено и НЕ выполнено — не доказано ни то, ни другое"
    }

    /**
     * Разрешён ли повторный клик и с каким остаточным риском.
     *
     * Бессрочного запрета нет (мандат): если исход неизвестен, клик разрешается, но НЕ объявляется
     * отменой старой команды. Если старая операция ещё ИДЁТ (`STARTED`), риск назван прямо.
     */
    data class Decision(val allow: Boolean, val risk: String, val why: String)

    fun clickDecision(prev: Op?, term: Terminal?): Decision {
        if (prev == null) return Decision(true, "none", "предыдущей операции нет")
        val phase = classify(sentAck = false, term = term)
        return when (phase) {
            State.FINISHED -> Decision(true, "none", "предыдущая операция завершилась (END rc=${
                term?.rc ?: "?"
            }) — исход известен")
            State.REFUSED -> Decision(true, "none", "предыдущий payload отказался работать (чужой claim)")
            State.STARTED -> Decision(
                true, "old_payload_running",
                "предыдущий payload НАЧАЛ работу и ещё не закончил: новый claim не отменит " +
                    "уже начавшийся рестарт. Поздний СТАРТ отменяется, текущий — нет"
            )
            State.UNKNOWN -> Decision(
                true, "old_payload_maybe_queued",
                "исход предыдущей операции НЕИЗВЕСТЕН (terminal-файла нет, claim ${
                    term?.note ?: "не прочитан"
                }). Новый claim защитит от позднего СТАРТА, но не доказывает, что старая " +
                    "команда не выполнится: если она уже стартовала, отменить нельзя"
            )
            State.SENT, State.CLAIMED, State.CREATED -> Decision(
                true, "old_payload_maybe_queued",
                "предыдущая операция не подтверждена terminal-файлом"
            )
        }
    }

    /**
     * Можно ли возвращать Wi-Fi прежнего значения: только владелец claim'а — эта операция.
     * Чужой/новый выбор не откатываем (иначе затрём решение владельца, принятое позже).
     */
    fun mayRestoreWifi(opId: String, claimRaw: String?): Boolean {
        val claim = Op.parseClaim(claimRaw) ?: return false
        return claim.id == opId
    }

    /**
     * iter52 §7.4 — можно ли ВКЛЮЧАТЬ Wi-Fi по сохранённому снимку.
     *
     * Возвращает решение И причину, потому что «нет» и «не знаю» — разные факты:
     *  • claim принадлежит этой операции — да (`claim_ours`);
     *  • claim принадлежит ДРУГОЙ операции — нет (`claim_foreign`): владелец уже нажал тумблер
     *    снова, и включение Wi-Fi по старому снимку затирало бы его новое решение;
     *  • claim отсутствует — да (`claim_absent`): конкурирующей операции нет (снимок всё равно
     *    проверяется по своему opId выше);
     *  • claim испорчен — НЕТ (`claim_unparsable`): это неизвестность, а не «конкурирующих нет».
     *
     * Что это НЕ даёт: защиты от того, что владелец включил Wi-Fi сам после клика (это состояние
     * читается отдельно перед включением: «уже включён» → запрос не отправляется).
     */
    fun claimAllowsRestore(opId: String, claimRaw: String?): Pair<Boolean, String> {
        if (claimRaw.isNullOrBlank()) return true to "claim_absent"
        val claim = Op.parseClaim(claimRaw) ?: return false to "claim_unparsable"
        return if (claim.id == opId) true to "claim_ours" else false to "claim_foreign"
    }
}
