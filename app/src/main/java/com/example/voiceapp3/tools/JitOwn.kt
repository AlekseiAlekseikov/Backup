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

    /**
     * iter53 §5.1: сколько секунд брошенная блокировка считается старой — ТОЛЬКО ДЛЯ СООБЩЕНИЯ.
     *
     * Раньше это число было РАЗРЕШЕНИЕМ на перехват. Это неверно: возраст lock не доказывает
     * смерть shell (зависание, потеря ответа, сон, скачок часов). Теперь перехват возможен только
     * по ДОКАЗАННОЙ смерти владельца (pid+starttime+boot identity или его собственный `END`),
     * а это значение используется только в тексте `JIT_LOCK_AGED` для владельца.
     */
    const val LOCK_AGE_NOTE_S = 180

    /**
     * Допустимый диапазон меток времени — проверка переполнения/мусора.
     *
     * 2000-01-01 … 2100-01-01 в миллисекундах. Значение вне диапазона НЕ приводится к «примерно
     * правильному»: это отказ разбора, а не догадка.
     */
    const val TS_MS_MIN = 946_684_800_000L
    const val TS_MS_MAX = 4_102_444_800_000L

    /** Единица измерения метки в записи на устройстве. Имя поля говорит её явно. */
    enum class TsUnit { MS, S, LEGACY }

    /**
     * Приведение метки к ОДНОЙ единице (миллисекунды) с проверкой диапазона.
     *
     * Правила:
     *  • `ts_ms=<n>` — уже миллисекунды;
     *  • `ts_s=<n>` — секунды, умножаются на 1000 (с проверкой на переполнение);
     *  • `ts=<n>` (записи прежних версий) — единица НЕИЗВЕСТНА: определяется по величине
     *    (>= 1e11 → ms, иначе s). Это ровно тот дефект, который привёл к «ms против date +%s»;
     *    поэтому старый формат принимается, но нормализуется и больше нигде не сравнивается
     *    с `date +%s` напрямую.
     *
     * @return миллисекунды либо `null` — значение вне допустимого диапазона/переполнение.
     */
    fun normalizeTsMs(value: Long, unit: TsUnit): Long? {
        // 0 — «время неизвестно» (так пишут служебные/тестовые отметки). Это НЕ диапазон
        // «около нуля»: такое значение не превращается в дату, но и не выдаётся за мусор.
        if (value == 0L) return 0L
        if (value < 0L) return null
        val ms = when (unit) {
            TsUnit.MS -> value
            TsUnit.S -> if (value > TS_MS_MAX / 1000L) return null else value * 1000L
            TsUnit.LEGACY -> if (value >= 100_000_000_000L) value
            else if (value > TS_MS_MAX / 1000L) return null else value * 1000L
        }
        return if (ms in TS_MS_MIN..TS_MS_MAX) ms else null
    }

    /** Миллисекунды → секунды (единственное место, где выполняется деление). */
    fun msToS(ms: Long): Long = ms / 1000L

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

    /**
     * Запись операции: id, время, желаемое состояние, фаза.
     *
     * iter53 §5.1: [tsMs] — ВСЕГДА миллисекунды, и это видно из имени поля. Раньше поле `ts`
     * означало то миллисекунды (`System.currentTimeMillis()`), то секунды (`date +%s`) —
     * из-за этого блокировка «старела» никогда или сразу.
     */
    data class Op(
        val id: String,
        val tsMs: Long,
        val want: Boolean,
        val state: State,
        /** Кто владеет: обычно идентификатор процесса/сессии, чтобы не приписать чужое. */
        val owner: String = ""
    ) {
        /** Секунды (только для сравнения с `date +%s`, см. [msToS]). */
        val tsS: Long get() = msToS(tsMs)

        fun wire(): String =
            "op=$id;ts_ms=$tsMs;ts_s=$tsS;want=${if (want) 1 else 0};state=${state.name.lowercase()}" +
                (if (owner.isEmpty()) "" else ";owner=$owner")

        /** Строка для claim-файла: без состояния (оно локальное) — только владение. */
        fun claimLine(): String = "op=$id;ts_ms=$tsMs;want=${if (want) 1 else 0}"

        companion object {
            /** Явное имя единицы в записи: `ts_ms=`, `ts_s=`; старое `ts=` — только чтение. */
            private val TS_FIELD = Regex("(?:^|;)ts_(ms|s)=(\\d{1,19})(?=;|${'$'})")
            private val LEGACY_TS = Regex("(?:^|;)ts=(\\d{1,19})(?=;|${'$'})")

            /** Метка времени из записи → миллисекунды. `null` — поле не разобрано. */
            fun parseTsMs(raw: String): Long? {
                TS_FIELD.find(raw)?.let { m ->
                    val unit = if (m.groupValues[1] == "ms") TsUnit.MS else TsUnit.S
                    return normalizeTsMs(m.groupValues[2].toLongOrNull() ?: return null, unit)
                }
                LEGACY_TS.find(raw)?.let { m ->
                    return normalizeTsMs(m.groupValues[1].toLongOrNull() ?: return null, TsUnit.LEGACY)
                }
                return null
            }

            private val RE = Regex(
                "^op=([A-Za-z0-9_.-]{1,64});" +
                    "(?:ts_ms=\\d{1,19};ts_s=\\d{1,19}|ts_ms=\\d{1,19}|ts_s=\\d{1,19}|ts=\\d{1,19})" +
                    ";want=([01]);state=(created|claimed|sent|started|finished|refused|unknown)" +
                    "(?:;owner=([A-Za-z0-9_.-]{0,64}))?$"
            )

            /** `null` — запись отсутствует/повреждена: это «неизвестно», а не «операции не было». */
            fun decode(raw: String?): Op? {
                if (raw == null) return null
                val line = raw.trim()
                val m = RE.matchEntire(line) ?: return null
                return Op(
                    id = m.groupValues[1],
                    tsMs = parseTsMs(line) ?: 0L,
                    want = m.groupValues[2] == "1",
                    state = State.values().first { it.name.equals(m.groupValues[3], true) },
                    owner = m.groupValues[4]
                )
            }

            /** Разобрать claim-файл: `op=<id>;ts_ms=…;want=…` (старое `ts=` тоже принимается). */
            fun parseClaim(raw: String?): Op? {
                if (raw == null) return null
                val line = raw.trim()
                val m = Regex("^op=([A-Za-z0-9_.-]{1,64});(?:ts_(?:ms|s)|ts)=\\d{1,19};want=([01])$")
                    .matchEntire(line) ?: return null
                return Op(
                    id = m.groupValues[1],
                    tsMs = parseTsMs(line) ?: 0L,
                    want = m.groupValues[2] == "1",
                    state = State.CLAIMED
                )
            }
        }
    }

    /**
     * Общие shell-функции владения: идентичность ЭТОГО shell и доказательство смерти владельца.
     *
     * Используются и командой записи claim, и гвардией payload — чтобы правило было ОДНО.
     * Ни одна из функций не использует ВРЕМЯ как доказательство.
     */
    private fun ownHelpers(base: String): String {
        val lock = lockPath(base)
        val termPrefix = "$base/voiceapp3_jit_"
        return """
# ── идентичность этого shell ──────────────────────────────────────────────
_me_pid=${'$'}${'$'}
_me_psof() { awk '{print ${'$'}22}' "/proc/${'$'}1/stat" 2>/dev/null; }
_me_pstart=${'$'}(_me_psof "${'$'}_me_pid")
_me_boot=${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
# ── смерть владельца lock-строки ${'$'}1 доказана? ──────────────────────────
# rc 0 = жив ИЛИ доказать смерть нечем → перехватывать НЕЛЬЗЯ
# rc 1 = смерть доказана: другая загрузка; процесса нет; pid переиспользован;
#        либо сам владелец написал END/REFUSED в свой terminal-файл
_jit_owner_dead() {
  _lo=${'$'}1
  _lopid=${'$'}(printf '%s' "${'$'}_lo" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p')
  _lopst=${'$'}(printf '%s' "${'$'}_lo" | sed -n 's/.*pstart=\([0-9][0-9]*\).*/\1/p')
  _loboot=${'$'}(printf '%s' "${'$'}_lo" | sed -n 's/.*boot=\([^;]*\).*/\1/p')
  _loop=${'$'}(printf '%s' "${'$'}_lo" | sed -n 's/^op=\([A-Za-z0-9_.-]*\).*/\1/p')
  if [ -n "${'$'}_loop" ]; then
    if grep -q "END rc=[-0-9]* op=${'$'}_loop${'$'}" "${termPrefix}${'$'}_loop.term" 2>/dev/null; then return 1; fi
    if grep -q "REFUSED op=${'$'}_loop " "${termPrefix}${'$'}_loop.term" 2>/dev/null; then return 1; fi
  fi
  [ -z "${'$'}_lopid" ] && return 0
  if [ -n "${'$'}_loboot" ] && [ -n "${'$'}_me_boot" ] && [ "${'$'}_loboot" != "${'$'}_me_boot" ]; then return 1; fi
  if [ -d /proc/1 ] && [ ! -d "/proc/${'$'}_lopid" ]; then return 1; fi
  _cur=${'$'}(_me_psof "${'$'}_lopid")
  if [ -n "${'$'}_cur" ] && [ -n "${'$'}_lopst" ] && [ "${'$'}_cur" != "${'$'}_lopst" ]; then return 1; fi
  return 0
}
# ── освободить ТОЛЬКО свою блокировку ─────────────────────────────────────
_jit_release() {
  _cl=${'$'}(cat ${lock} 2>/dev/null)
  case "${'$'}_cl" in
    *"inst=${'$'}_me_inst"*) rm -f ${lock} 2>/dev/null ;;
  esac
}
""".trimIndent()
    }

    /**
     * Короткая команда ЗАПИСИ claim (десятки байт — идёт обычным `execute`).
     *
     * iter53 §5.2: запись claim выполняется ПОД ТОЙ ЖЕ блокировкой, что и исполнение payload.
     *
     * Зачем это обязательно: иначе остаётся обратная гонка — A уже исполняет опасное действие,
     * B записывает свой claim и «переименовывает» операцию, которая продолжает идти. С lock'ом
     * модель согласована в ОБЕ стороны:
     *  • B записал claim раньше, чем A взял lock → A перечитывает claim ПОД lock и отказывается
     *    (устаревший payload не делает side effect);
     *  • A держит lock → B не может записать claim и получает ЯВНЫЙ `CLAIM_BUSY`.
     *
     * Вывод (разбирается [claimResult]): `CL_BEGIN … CL_STATUS=taken|busy|failed … CL_END`.
     */
    fun claimCmd(op: Op, base: String = BASE): String {
        val claim = claimPath(base)
        val lock = lockPath(base)
        val line = op.claimLine()
        val inst = "${op.id}-${op.tsMs}-\$\$"
        return """
set +e
echo CL_BEGIN
_me_inst=${inst}
${ownHelpers(base)}
_me_line="op=${op.id};ts_ms=${op.tsMs};ts_s=${op.tsS};inst=${'$'}_me_inst;pid=${'$'}_me_pid;pstart=${'$'}{_me_pstart:-?};boot=${'$'}{_me_boot:-?}"
_cl_status=failed; _cl_owner=""
if [ -f ${lock} ]; then
  _lo=${'$'}(cat ${lock} 2>/dev/null)
  if ! _jit_owner_dead "${'$'}_lo"; then
    mv ${lock} ${lock}.dead.${'$'}${'$'} 2>/dev/null
    ( set -C; printf '%s\n' "${'$'}_me_line" > ${lock} ) 2>/dev/null || {
      mv ${lock}.dead.${'$'}${'$'} ${lock} 2>/dev/null; _cl_owner="${'$'}_lo"; _cl_status=busy; }
  else
    _cl_status=busy; _cl_owner="${'$'}_lo"
  fi
else
  ( set -C; printf '%s\n' "${'$'}_me_line" > ${lock} ) 2>/dev/null || { _cl_owner="race"; _cl_status=busy; }
fi
if [ "${'$'}_cl_status" != "busy" ]; then
  printf '%s\n' '${line}' > ${claim} 2>/dev/null
  _rb=${'$'}(cat ${claim} 2>/dev/null)
  if [ "${'$'}_rb" = '${line}' ]; then _cl_status=taken; else _cl_status=failed; fi
  _jit_release
fi
echo "CL_STATUS=${'$'}_cl_status"
[ "${'$'}_cl_status" = "taken" ] && echo "CL_VALUE=${'$'}_rb"
[ -n "${'$'}_cl_owner" ] && echo "CL_OWNER=${'$'}_cl_owner"
echo CL_END
""".trimIndent()
    }

    /** Результат записи claim: «занято» отделено от «записано» и от «неизвестно». */
    sealed interface ClaimWrite {
        data class Taken(val line: String) : ClaimWrite
        data class Busy(val owner: String) : ClaimWrite
        data class Failed(val why: String) : ClaimWrite
    }

    /** Разбор вывода [claimCmd]: те же строгие правила, что и у чтения. */
    fun claimResult(rc: Int, out: String): ClaimWrite {
        if (rc != 0) return ClaimWrite.Failed("transport_rc=$rc")
        if (out.contains("ERR_")) return ClaimWrite.Failed("transport")
        val lines = out.split('\n').map { it.trim() }
        if (lines.count { it == "CL_BEGIN" } != 1 || lines.count { it == "CL_END" } != 1)
            return ClaimWrite.Failed("framing")
        val status = lines.filter { it.startsWith("CL_STATUS=") }.map { it.removePrefix("CL_STATUS=") }
        if (status.size != 1) return ClaimWrite.Failed("status_lines=${status.size}")
        val value = lines.filter { it.startsWith("CL_VALUE=") }.map { it.removePrefix("CL_VALUE=") }
        val owner = lines.filter { it.startsWith("CL_OWNER=") }.map { it.removePrefix("CL_OWNER=") }
        return when (status.first()) {
            "taken" -> if (value.size == 1) ClaimWrite.Taken(value.first()) else ClaimWrite.Failed("value_missing")
            "busy" -> ClaimWrite.Busy(owner.firstOrNull() ?: "unknown")
            "failed" -> ClaimWrite.Failed("device_failed")
            else -> ClaimWrite.Failed("status_unknown=${status.first()}")
        }
    }

    /**
     * iter53 §4.1 — КОМАНДА ЧТЕНИЯ С ЯВНЫМ ПРОТОКОЛОМ, у которой нет двусмысленности.
     *
     * Дефект iter52: команда печатала `echo CLAIM="$(cat …)"`. Кавычки — синтаксис shell, в
     * stdout они НЕ попадают, поэтому вывод выглядел как `CLAIM=op=abc;ts=123;want=1`, а парсер
     * в `JitNet` требовал ЗАКРЫВАЮЩУЮ кавычку. Совпадения не было, `null` трактовался как
     * «claim отсутствует» → возврат Wi-Fi разрешался при ЧУЖОМ claim.
     *
     * Теперь чтение отвечает тремя РАЗНЫМИ фактами и обрамлено маркерами:
     * ```
     * CS_BEGIN
     * CLAIM_STATUS=present|absent|empty|unreadable
     * CLAIM_VALUE=<строка без переводов строк, только при present>
     * TERM_STATUS=present|absent|empty|unreadable
     * TERM_VALUE=<строки через |, только при present>
     * CS_END
     * ```
     *  • `absent` печатается только когда файла действительно НЕТ и каталог доступен
     *    (`[ -e ]` + `[ -d ]`), а не когда `cat` вернул пустоту;
     *  • отказ `cat` — `unreadable`, а не «пусто»: `rc` самого `cat` проверяется;
     *  • маркеры позволяют отличить обрезанный/неполный вывод (нет `CS_END`) от нормального.
     */
    fun readCmd(opId: String, base: String = BASE): String {
        val claim = claimPath(base)
        val term = termPath(opId, base)
        val dir = java.io.File(claim).parent ?: BASE
        return """
set +e
echo CS_BEGIN
_cv=""; _cs=unreadable
if [ -e $claim ]; then
  _cv=${'$'}(cat $claim 2>/dev/null); _cr=${'$'}?
  if [ "${'$'}_cr" != "0" ]; then _cs=unreadable
  elif [ -z "${'$'}_cv" ]; then _cs=empty
  else _cs=present; fi
else
  if [ -d $dir ]; then _cs=absent; else _cs=unreadable; fi
fi
_cv=${'$'}(printf '%s' "${'$'}_cv" | tr '\n' '|')
echo "CLAIM_STATUS=${'$'}_cs"
[ "${'$'}_cs" = "present" ] && echo "CLAIM_VALUE=${'$'}_cv"
_tv=""; _ts=unreadable
if [ -e $term ]; then
  _tv=${'$'}(cat $term 2>/dev/null); _tr=${'$'}?
  if [ "${'$'}_tr" != "0" ]; then _ts=unreadable
  elif [ -z "${'$'}_tv" ]; then _ts=empty
  else _ts=present; fi
else
  if [ -d $dir ]; then _ts=absent; else _ts=unreadable; fi
fi
_tv=${'$'}(printf '%s' "${'$'}_tv" | tr '\n' '|')
echo "TERM_STATUS=${'$'}_ts"
[ "${'$'}_ts" = "present" ] && echo "TERM_VALUE=${'$'}_tv"
echo CS_END
""".trimIndent()
    }

    // ─────────────────────────── iter53 §4.2: строгий разбор ответа ───────────────────────────

    /**
     * Результат чтения claim — ТРИ разных факта, а не «есть/нет».
     *
     *  • [Present] — файл прочитан и значение разобрано; владение знает только [Op.id];
     *  • [ConfirmedAbsent] — доказано, что файла НЕТ (shell проверил `[ -e ]`, каталог доступен);
     *  • [Unknown] — транспорт/формат/чтение: НЕЛЬЗЯ трактовать как «claim отсутствует».
     */
    sealed interface ClaimRead {
        data class Present(val op: Op) : ClaimRead
        data object ConfirmedAbsent : ClaimRead
        data class Unknown(val why: String) : ClaimRead
    }

    /** Результат чтения terminal-файла операции (значение — строки через `|`). */
    data class TermRead(val status: String, val lines: List<String>) {
        val present: Boolean get() = status == "present"
    }

    /** Полный разбор ответа [readCmd]: владение + terminal одной командой. */
    data class ReadReply(val claim: ClaimRead, val term: TermRead?)

    private val CLAIM_VALUE_RE = Regex(
        "^op=([A-Za-z0-9_.-]{1,64});(?:ts_(?:ms|s)|ts)=(\\d{1,19});want=([01])${'$'}"
    )

    /**
     * Строгий разбор stdout [readCmd].
     *
     * Правила, каждое из которых закрывает конкретный способ «додумать успех»:
     *  • `rc != 0`, `ERR_…` в выводе — транспорт отказал: [ClaimRead.Unknown];
     *  • ровно один `CS_BEGIN` и ровно один `CS_END`, причём в этом порядке — иначе вывод
     *    обрезан/склеен: [ClaimRead.Unknown];
     *  • ровно одна строка `CLAIM_STATUS=…` — дубликаты считаются подменой;
     *  • `present` требует ровно одного `CLAIM_VALUE=` с валидным форматом claim;
     *  • `absent` — только явный статус от shell, не пустой вывод.
     */
    fun parseClaimReply(rc: Int, out: String): ClaimRead = parseReadReply(rc, out).claim

    fun parseReadReply(rc: Int, out: String): ReadReply {
        if (rc != 0) return ReadReply(ClaimRead.Unknown("transport_rc=$rc"), null)
        if (out.contains("ERR_")) return ReadReply(ClaimRead.Unknown("transport"), null)
        val lines = out.split('\n').map { it.trim() }
        val begins = lines.count { it == "CS_BEGIN" }
        val ends = lines.count { it == "CS_END" }
        if (begins != 1 || ends != 1 || lines.indexOf("CS_BEGIN") > lines.indexOf("CS_END"))
            return ReadReply(ClaimRead.Unknown("framing:begin=$begins end=$ends"), null)
        val body = lines.subList(lines.indexOf("CS_BEGIN") + 1, lines.indexOf("CS_END"))
        val claimStatuses = body.filter { it.startsWith("CLAIM_STATUS=") }.map { it.removePrefix("CLAIM_STATUS=") }
        if (claimStatuses.size != 1) return ReadReply(ClaimRead.Unknown("claim_status_lines=${claimStatuses.size}"), null)
        val termStatuses = body.filter { it.startsWith("TERM_STATUS=") }.map { it.removePrefix("TERM_STATUS=") }
        val termValues = body.filter { it.startsWith("TERM_VALUE=") }.map { it.removePrefix("TERM_VALUE=") }
        val term = when {
            termStatuses.size == 1 -> TermRead(
                termStatuses.first(),
                termValues.firstOrNull()?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            )
            else -> null
        }
        val status = claimStatuses.first()
        val claim: ClaimRead = when (status) {
            "present" -> {
                val values = body.filter { it.startsWith("CLAIM_VALUE=") }.map { it.removePrefix("CLAIM_VALUE=") }
                if (values.size != 1) return ReadReply(ClaimRead.Unknown("claim_value_lines=${values.size}"), term)
                val raw = values.first().trim()
                val m = CLAIM_VALUE_RE.matchEntire(raw) ?: return ReadReply(ClaimRead.Unknown("claim_value_invalid"), term)
                // Время вне диапазона — НЕ повод объявить claim «испорченным»: владение
                // определяется id, а не часами. Неизвестное время остаётся 0.
                val tsMs = Op.parseTsMs(raw) ?: 0L
                ClaimRead.Present(
                    Op(
                        id = m.groupValues[1],
                        tsMs = tsMs,
                        want = m.groupValues[3] == "1",
                        state = State.CLAIMED
                    )
                )
            }
            "absent" -> ClaimRead.ConfirmedAbsent
            "empty" -> ClaimRead.Unknown("claim_empty")
            "unreadable" -> ClaimRead.Unknown("claim_unreadable")
            else -> ClaimRead.Unknown("claim_status_unknown=$status")
        }
        return ReadReply(claim, term)
    }

    /**
     * iter53 §4.2 — можно ли ВКЛЮЧАТЬ Wi-Fi по сохранённому снимку, по факту чтения claim.
     *
     * Правило одно и без исключений: включать можно только когда claim принадлежит НАШЕЙ
     * операции. Почему так, а не «отсутствие = конкурирующих нет» (как было в iter52):
     * гвардия payload (`guard`) отказывается работать, если claim не её. Значит, если claim
     * отсутствует/чужой/нечитаем — наш payload НЕ выполнялся, Wi-Fi им не выключался, и
     * включать его «по старому снимку» нельзя: это было бы новое действие против возможного
     * более позднего решения владельца.
     */
    fun restoreDecision(opId: String, read: ClaimRead): Pair<Boolean, String> = when (read) {
        is ClaimRead.Present ->
            if (read.op.id == opId) true to "claim_ours"
            else false to "claim_foreign:${read.op.id}"
        ClaimRead.ConfirmedAbsent -> false to "claim_confirmed_absent_payload_refused"
        is ClaimRead.Unknown -> false to "claim_unknown:${read.why}"
    }

    /**
     * Гвардия payload: доказанная защита от ПОЗДНЕГО и ОДНОВРЕМЕННОГО исполнения.
     *
     * Вставляется в самое начало скрипта, до любых side effect. Модель (iter53 §5.2):
     *
     *  1. claim читается ДО lock — только для заметки в отказе (он мог устареть);
     *  2. атомарный захват lock (`set -C`, noclobber). Перехват чужого lock возможен ТОЛЬКО при
     *     ДОКАЗАННОЙ смерти владельца (`pid`+`pstart`+`boot` identity или его собственный `END`).
     *     Истечение 180 секунд доказательством НЕ является и больше не перехватывает;
     *  3. ПОД lock claim ПЕРЕЧИТЫВАЕТСЯ и сравнивается с нашим id. Это закрывает гонку
     *     «A прочитал claim=A → B записал claim=B и выполнился → A продолжил»: A увидит claim=B.
     *  4. только после этого пишется `START` и разрешено первое опасное действие;
     *  5. `trap EXIT` пишет `END rc=…` и освобождает ТОЛЬКО свою блокировку (по `inst`).
     *
     * Отказ всегда оставляет `REFUSED` + `END` в terminal-файле ЭТОЙ операции и печатает
     * `JIT_REFUSED`, поэтому «payload отказался» видно даже без stdout.
     */
    fun guard(op: Op, base: String = BASE): String {
        val id = op.id
        val term = termPath(id, base)
        val claim = claimPath(base)
        val lock = lockPath(base)
        val tsMs = op.tsMs
        val tsS = op.tsS
        // `inst` различает ЭКЗЕМПЛЯРЫ одной операции: тот же opId в другом процессе — не владелец.
        val inst = "$id-$tsMs-\$\$"
        return """
# ── iter53 §5: lock, ПОД lock актуальный claim, и только потом действие ────
_vc=${'$'}(cat ${claim} 2>/dev/null)
_lo=""
_refuse() {
  echo "REFUSED op=${id} reason=${'$'}1 detail=${'$'}2 claim=${'$'}{_vc:-none}" >> ${term} 2>/dev/null
  echo "END rc=0 op=${id}" >> ${term} 2>/dev/null
  echo "JIT_REFUSED op=${id} reason=${'$'}1 claim=${'$'}{_vc:-none} lock=${'$'}{_lo:-none}"
  exit 0
}
# 0) РАННЯЯ (неавторитетная) проверка: явно чужой claim — отказ ДО создания lock.
#    Авторитетная проверка ниже, ПОД lock: ранняя нужна лишь затем, чтобы устаревший payload
#    не занимал lock даже на мгновение (иначе он вытеснил бы актуальную операцию).
case "${'$'}_vc" in
  "op=${id};"*|"op=${id}") : ;;
  *) _refuse superseded "claim=${'$'}{_vc:-none}" ;;
esac
_me_inst=${inst}
${ownHelpers(base)}
_me_line="op=${id};ts_ms=${tsMs};ts_s=${tsS};inst=${'$'}_me_inst;pid=${'$'}_me_pid;pstart=${'$'}{_me_pstart:-?};boot=${'$'}{_me_boot:-?}"
_lk=0
if [ -f ${lock} ]; then
  _lo=${'$'}(cat ${lock} 2>/dev/null)
  case "${'$'}_lo" in
    *"inst=${'$'}_me_inst"*) _refuse duplicate_instance "${'$'}_lo" ;;
  esac
  if ! _jit_owner_dead "${'$'}_lo"; then
    # смерть доказана — перехват, и это ПИШЕТСЯ с доказательством
    mv ${lock} ${lock}.dead.${'$'}${'$'} 2>/dev/null
    ( set -C; printf '%s\n' "${'$'}_me_line" > ${lock} ) 2>/dev/null && _lk=1
    if [ "${'$'}_lk" != "1" ]; then
      mv ${lock}.dead.${'$'}${'$'} ${lock} 2>/dev/null
      _refuse lock_race "${'$'}_lo"
    fi
    echo "JIT_LOCK_TAKEOVER proof=owner_dead owner=${'$'}_lo"
  else
    case "${'$'}_lo" in
      "op=${id};"*|"op=${id}") _refuse same_op_live "${'$'}_lo" ;;
    esac
    _age=${'$'}(printf '%s' "${'$'}_lo" | sed -n 's/.*ts_s=\([0-9][0-9]*\).*/\1/p')
    _now=${'$'}(date +%s 2>/dev/null)
    if [ -n "${'$'}_age" ] && [ -n "${'$'}_now" ]; then
      echo "JIT_LOCK_AGED owner=${'$'}_lo age_s=${'$'}(( _now - _age )) action=none (возраст НЕ доказательство)"
    fi
    _refuse lock_held "${'$'}_lo"
  fi
else
  ( set -C; printf '%s\n' "${'$'}_me_line" > ${lock} ) 2>/dev/null && _lk=1
  [ "${'$'}_lk" = "1" ] || _refuse lock_race ""
fi
# ── ПОД lock: актуальный claim (закрывает TOCTOU «прочитал до lock») ───────
_vc2=${'$'}(cat ${claim} 2>/dev/null)
case "${'$'}_vc2" in
  "op=${id};"*|"op=${id}") : ;;
  *) _jit_release; _refuse superseded_under_lock "claim=${'$'}_vc2" ;;
esac
# ── начало операции видно на устройстве ───────────────────────────────────
echo "START ts=${'$'}(date +%s) op=${id}" > ${term} 2>/dev/null
trap 'echo "END rc=$? op=${id}" >> ${term} 2>/dev/null; _jit_release; rm -f ${lock}.dead.* 2>/dev/null' EXIT
""".trimIndent()
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
        // iter53 §4.1: разбор идёт по протоколу `CLAIM_STATUS/CLAIM_VALUE/TERM_STATUS/TERM_VALUE`
        // внутри маркеров CS_BEGIN…CS_END. Прежние regex искали `CLAIM=`/`TERM=` и требовали
        // закрывающую кавычку, которой в stdout нет → terminal «не читался» даже когда читался.
        val reply = parseReadReply(rc, out)
        val claimRead = reply.claim
        val termRead = reply.term
        if (claimRead is ClaimRead.Unknown && termRead == null) return null
        val claim = (claimRead as? ClaimRead.Present)?.op
        val lines = termRead?.lines ?: emptyList()
        val claimNote = when (claimRead) {
            is ClaimRead.Present -> if (claimRead.op.id == opId) "claim_ours" else "claim_foreign"
            ClaimRead.ConfirmedAbsent -> "claim_absent"
            is ClaimRead.Unknown -> "claim_unknown:" + claimRead.why
        }
        val termRaw = lines.joinToString("|")
        if (claim == null && lines.isEmpty() && claimRead !is ClaimRead.ConfirmedAbsent) return null
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
            note = claimNote +
                (if (foreignCount > 0) ";term_lines_other_op=$foreignCount" else "") +
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
     *  • iter53 §4.2 — claim отсутствует/нечитаем — НЕТ (`claim_confirmed_absent_payload_refused`
     *    / `claim_unknown:…`): отсутствие владения не доказывает, что наша цепочка выключала
     *    Wi-Fi, а гвардия payload в этом случае отказывается работать;
     *  • claim испорчен — НЕТ (`claim_unparsable`): это неизвестность, а не «конкурирующих нет».
     *
     * Что это НЕ даёт: защиты от того, что владелец включил Wi-Fi сам после клика (это состояние
     * читается отдельно перед включением: «уже включён» → запрос не отправляется).
     */
    fun claimAllowsRestore(opId: String, claimRaw: String?): Pair<Boolean, String> {
        // iter53 §4.2: отсутствие claim НЕ разрешает включение Wi-Fi. Если claim не наш,
        // гвардия payload отказалась работать — значит наша цепочка Wi-Fi не выключала и
        // возвращать «по снимку» нечего (см. [restoreDecision]).
        if (claimRaw.isNullOrBlank()) return false to "claim_confirmed_absent_payload_refused"
        val claim = Op.parseClaim(claimRaw) ?: return false to "claim_unknown:claim_value_invalid"
        return if (claim.id == opId) true to "claim_ours" else false to "claim_foreign:${claim.id}"
    }
}
