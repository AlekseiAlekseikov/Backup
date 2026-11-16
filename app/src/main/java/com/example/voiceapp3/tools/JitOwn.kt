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

    /**
     * iter54 §4 — идентификатор ЭКЗЕМПЛЯРА клика, один на всю цепочку.
     *
     * Почему он создаётся в Kotlin, а не в shell: claim пишет ОДИН процесс, а payload исполняет
     * ДРУГОЙ (каждый `su -c` — новый shell со своим `$$`). Если бы экземпляр определялся по `$$`,
     * гвардия всегда видела бы «другой экземпляр» и отказывалась бы работать. Поэтому `inst`
     * выдаётся один раз на клик и передаётся и в запись claim, и в гвардию; `pid`/`pstart`/`boot`
     * по-прежнему подставляет сам shell — это ДОКАЗАТЕЛЬСТВО того, кто именно исполнил.
     */
    fun newInstance(opId: String, tsMs: Long, nonce: Int = 0): String =
        if (nonce == 0) "$opId-$tsMs" else "$opId-$tsMs-n$nonce"

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
     * iter54 §4 — идентичность КОНКРЕТНОГО экземпляра исполнения.
     *
     * @param inst  `<opId>-<tsMs>-<pid>`: различает экземпляры ОДНОЙ операции;
     * @param pid   pid shell, взявшего владение;
     * @param pstart starttime pid'а (поле 22 `/proc/<pid>/stat`) — защита от переиспользования pid;
     * @param boot  boot_id текущей загрузки: pid/pstart из другой загрузки ничего не доказывают.
     */
    data class ClaimIdentity(
        val inst: String,
        val pid: String,
        val pstart: String,
        val boot: String
    ) {
        fun wire(): String = ";inst=$inst;pid=$pid;pstart=$pstart;boot=$boot"

        /** Разбор хвоста записи. `null` — поля нет/испорчено (тогда это НЕ идентичность). */
        companion object {
            private val RE = Regex(
                ";inst=([A-Za-z0-9_.:\\-]{1,96});pid=(\\d{1,10});pstart=(\\d{1,20}|\\?);" +
                    "boot=([A-Za-z0-9\\-]{0,64})$"
            )

            fun parse(raw: String?): ClaimIdentity? {
                if (raw == null) return null
                val m = RE.find(raw) ?: return null
                return ClaimIdentity(
                    inst = m.groupValues[1],
                    pid = m.groupValues[2],
                    pstart = m.groupValues[3],
                    boot = m.groupValues[4]
                )
            }
        }
    }

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
        val owner: String = "",
        /**
         * iter54 §4: ПОЛНАЯ идентичность владельца (`inst` + `pid` + `pstart` + `boot`).
         *
         * Один `operationId` может исполняться НЕСКОЛЬКИМИ экземплярами shell (повторный клик,
         * повторная отправка, ретрай транспорта). Раньше владение сводилось к `opId`, поэтому
         * отказ экземпляра B записывался как факт про операцию A: `END rc=0` после `START` A
         * выглядел как «A завершилась успешно». Теперь владелец — это opId+экземпляр+загрузка+
         * процесс, и terminal читается ПО ЭКЗЕМПЛЯРУ.
         */
        val identity: ClaimIdentity? = null,
        /**
         * iter56 §4.3 — МОНОТОННОЕ ПОКОЛЕНИЕ НАМЕРЕНИЯ (порядок кликов), а не время.
         *
         * `tsMs` — показание часов клиента: его можно перевести назад, два клика могут попасть в
         * одну миллисекунду, а после перезапуска процесса старое значение ничем не отличается от
         * нового. Поэтому порядок намерений на устройстве сравнивается по `seq` (счётчик,
         * сохраняемый до записи снимка), а `ts_ms` остаётся ТОЛЬКО fallback'ом для записей прежних
         * версий без `seq` и для наблюдения. `0` — поколение неизвестно (legacy), порядок по
         * времени с явным правилом `rule=ts_legacy`.
         */
        val seq: Long = 0L
    ) {
        /** Секунды (только для сравнения с `date +%s`, см. [msToS]). */
        val tsS: Long get() = msToS(tsMs)

        fun wire(): String =
            "op=$id;ts_ms=$tsMs;ts_s=$tsS;want=${if (want) 1 else 0};" +
                "state=${state.name.lowercase()}" +
                (if (seq > 0L) ";seq=$seq" else "") +
                (if (owner.isEmpty()) "" else ";owner=$owner")

        /** Строка для claim-файла: без состояния (оно локальное) — только владение. */
        fun claimLine(): String =
            "op=$id;ts_ms=$tsMs;want=${if (want) 1 else 0}" +
                (if (seq > 0L) ";seq=$seq" else "") +
                (identity?.wire() ?: "")

        /** Тот же opId и то же намерение, но ДРУГОЙ экземпляр исполнения. */
        fun sameIntent(otherOp: Op): Boolean = id == otherOp.id && tsMs == otherOp.tsMs

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

            /**
             * Разобрать claim-файл: `op=<id>;ts_ms=…;want=…` c НЕОБЯЗАТЕЛЬНОЙ полной
             * идентичностью экземпляра (`;inst=…;pid=…;pstart=…;boot=…`). Старое `ts=` и запись
             * без идентичности принимаются (совместимость), но тогда владелец НЕ доказан
             * по экземпляру — это видно по `identity == null`.
             */
            fun parseClaim(raw: String?): Op? {
                if (raw == null) return null
                val line = raw.trim()
                // iter56 §4.3: строка claim теперь содержит `ts_ms` И `ts_s` (единица видна из
                // имени поля) и необязательное `seq=`. Разбор идёт ПО ПОЛЯМ, а не одной жёсткой
                // склейкой: иначе добавление поля в запись делало её «нечитаемой» на клиенте.
                // Обязательны ровно два факта: `op` и `want`; время — необязательное наблюдение.
                val fields = line.split(';').mapNotNull { part ->
                    val k = part.substringBefore('=', "")
                    if (k.isEmpty()) null else k to part.substringAfter('=', "")
                }.toMap()
                val id = fields["op"]?.takeIf { Regex("[A-Za-z0-9_.-]{1,64}").matches(it) }
                    ?: return null
                val wantRaw = fields["want"]?.takeIf { it == "0" || it == "1" } ?: return null
                return Op(
                    id = id,
                    tsMs = parseTsMs(line) ?: 0L,
                    want = wantRaw == "1",
                    state = State.CLAIMED,
                    identity = ClaimIdentity.parse(line),
                    seq = fields["seq"]?.toLongOrNull()?.takeIf { it >= 0L } ?: 0L
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
# ── identity, извлечённая из lock-строки ${'$'}1 ─────────────────────────────
# Поля «держателя»: если payload уже отметился (`ppid=`/`ppstart=`), доказательство опирается
# на НЕГО, иначе — на shell, записавший claim (`pid=`/`pstart=`).
_jit_line_fields() {
  _lf=${'$'}1
  _lf_ppid=${'$'}(printf '%s' "${'$'}_lf" | sed -n 's/.*ppid=\([0-9][0-9]*\).*/\1/p')
  if [ -n "${'$'}_lf_ppid" ]; then
    _lf_pid=${'$'}_lf_ppid
    _lf_pst=${'$'}(printf '%s' "${'$'}_lf" | sed -n 's/.*ppstart=\([0-9][0-9]*\).*/\1/p')
    _lf_payload=1
  else
    _lf_pid=${'$'}(printf '%s' "${'$'}_lf" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p')
    _lf_pst=${'$'}(printf '%s' "${'$'}_lf" | sed -n 's/.*pstart=\([0-9][0-9]*\).*/\1/p')
    _lf_payload=0
  fi
  _lf_boot=${'$'}(printf '%s' "${'$'}_lf" | sed -n 's/.*boot=\([^;]*\).*/\1/p')
  _lf_op=${'$'}(printf '%s' "${'$'}_lf" | sed -n 's/^op=\([A-Za-z0-9_.-]*\).*/\1/p')
  _lf_inst=${'$'}(printf '%s' "${'$'}_lf" | sed -n 's/.*inst=\([^;]*\).*/\1/p')
}
# ── написал ли САМ владелец собственный END в свой terminal-файл ─────────────
# rc 0 = да (op, inst, pid и — когда он есть — pstart совпали целиком).
# Это единственное свидетельство «исполнение закончилось», не зависящее от /proc и часов.
# `REFUSED` доказательством НЕ является: его пишет другой shell (в том числе повторная доставка
# того же payload с тем же inst) — в iter56 эта строка объявляла живого владельца мёртвым.
_jit_end_proven() {
  _ep=${'$'}1
  _jit_line_fields "${'$'}_ep"
  [ -z "${'$'}_lf_op" ] && return 1
  [ -z "${'$'}_lf_inst" ] && return 1
  [ -z "${'$'}_lf_pid" ] && return 1
  _ep_term="${termPrefix}${'$'}_lf_op.term"
  [ -f "${'$'}_ep_term" ] || return 1
  _ep_need=3
  case "${'$'}_lf_pst" in ""|"?") : ;; *) _ep_need=4 ;; esac
  awk -v o="${'$'}_lf_op" -v i="${'$'}_lf_inst" -v p="${'$'}_lf_pid" -v s="${'$'}_lf_pst" \
      -v need="${'$'}_ep_need" '
    ${'$'}1=="END" {
      ok_o=0; ok_i=0; ok_p=0; ok_s=0
      for (n=2; n<=NF; n++) {
        split(${'$'}n, kv, "=")
        if (kv[1]=="op" && kv[2]==o) ok_o=1
        else if (kv[1]=="inst" && kv[2]==i) ok_i=1
        else if (kv[1]=="pid" && kv[2]==p) ok_p=1
        else if (kv[1]=="pstart" && kv[2]==s) ok_s=1
      }
      if (ok_o && ok_i && ok_p && (ok_o+ok_i+ok_p+ok_s)>=need) found=1
    }
    END { exit (found ? 0 : 1) }' "${'$'}_ep_term" 2>/dev/null
}
# ── death proven? — смерть владельца lock-строки ${'$'}1 ДОКАЗАНА? ──────────
# rc 0 = смерть ДОКАЗАНА: собственный END владельца; другая загрузка; процесса нет;
#        pid переиспользован.
# rc 1 = ЖИВ или доказать нечем → объект НЕ трогаем.
# Полярность положительная и совпадает с именем: у вызывающих нет отрицаний, поэтому «живой»
# не может быть прочитан как «мёртвый». Именно это произошло в iter57 — функция с именем
# `_jit_owner_dead` возвращала 0 для ЖИВОГО владельца, и admin-файл живого удалялся.
_jit_death_proven() {
  _dp=${'$'}1
  _jit_end_proven "${'$'}_dp" && return 0
  _jit_line_fields "${'$'}_dp"
  # Идентичность держателя неизвестна → доказать смерть нечем.
  [ -z "${'$'}_lf_pid" ] && return 1
  if [ -n "${'$'}_lf_boot" ] && [ -n "${'$'}_me_boot" ] && [ "${'$'}_lf_boot" != "${'$'}_me_boot" ]; then return 0; fi
  # /proc недоступен (контейнер/урезанная среда) — «процесса нет» доказательством не считается.
  if [ -d /proc/1 ] && [ ! -d "/proc/${'$'}_lf_pid" ]; then return 0; fi
  _dp_cur=${'$'}(_me_psof "${'$'}_lf_pid")
  if [ -n "${'$'}_dp_cur" ] && [ -n "${'$'}_lf_pst" ] && [ "${'$'}_dp_cur" != "${'$'}_lf_pst" ]; then return 0; fi
  # Процесс есть и identity совпала → владелец ЖИВ.
  return 1
}
# ── начал ли payload ЭТОГО исполнения работу (START в terminal) ─────────────
# rc 0 = START найден (исполнение могло начаться), rc 1 = START нет.
_jit_started_evidence() {
  _jit_line_fields ${'$'}1
  [ -z "${'$'}_lf_op" ] && return 1
  [ -f "${termPrefix}${'$'}_lf_op.term" ] || return 1
  awk -v o="${'$'}_lf_op" -v i="${'$'}_lf_inst" '
    ${'$'}1=="START" {
      ok_o=0; ok_i=0
      for (n=2; n<=NF; n++) { split(${'$'}n, kv, "=")
        if (kv[1]=="op" && kv[2]==o) ok_o=1
        else if (kv[1]=="inst" && kv[2]==i) ok_i=1 }
      if (ok_o && ok_i) found=1
    }
    END { exit (found ? 0 : 1) }' "${termPrefix}${'$'}_lf_op.term" 2>/dev/null
}
# ── можно ли перехватить lock этой строки ─────────────────────────────────
# 0 = да (смерть доказана И цепочка исполнения не может продолжиться),
# 1 = нет (жив / смерть недоказуема), 2 = payload мог начать работу и его смерть не доказана.
#
# Различие принципиально для ОПАСНОГО действия: lock, записанный короткоживущим shell'ом claim,
# не описывает payload. Если payload уже печатал START, а процесс-держатель мёртв по сторонним
# признакам (нет /proc, переиспользован pid), повторять то же опасное действие нельзя, пока
# владелец не оставит собственный END. Если END ЕСТЬ — исполнение закончилось, START не мешает.
_jit_may_takeover() {
  _mo_line=${'$'}1
  # Полярность одна и та же во всём файле: [_jit_death_proven] rc 0 = смерть ДОКАЗАНА.
  _jit_death_proven "${'$'}_mo_line" || return 1
  _jit_end_proven "${'$'}_mo_line" && return 0
  _jit_line_fields "${'$'}_mo_line"
  # payload уже отметился в lock своей identity ⇒ доказательство смерти относится к нему.
  if [ "${'$'}_lf_payload" = "1" ]; then return 0; fi
  if _jit_started_evidence "${'$'}_mo_line"; then return 2; fi
  return 0
}
# ── АТОМАРНОЕ создание файла с содержимым ──────────────────────────────────
# Файл появляется СРАЗУ с содержимым: temp + `ln` (жёсткая ссылка). Прежняя схема
# «set -C; printf > lock» создавала файл и лишь затем писала в него, поэтому конкурент мог
# прочитать ПУСТОЙ lock и решить, что владельца нет (или что владелец недоказуем).
_jit_take_lock() {
  _tl=${lock}.tmp.${'$'}${'$'}
  printf '%s\n' "${'$'}1" > "${'$'}_tl" 2>/dev/null || { rm -f "${'$'}_tl" 2>/dev/null; return 1; }
  if ln "${'$'}_tl" ${lock} 2>/dev/null; then rm -f "${'$'}_tl" 2>/dev/null; return 0; fi
  rm -f "${'$'}_tl" 2>/dev/null
  return 1
}
# ── iter59 §5.2: ВЗАИМНОЕ ИСКЛЮЧЕНИЕ БЕЗ «compare-and-unlink» ──────────────────
# Было (iter58): ОДИН общий файл `<lock>.admin`; освобождение доказанно мёртвого владельца
# делалось как «прочитать → доказать смерть → сравнить содержимое → rm по ИМЕНИ». Между
# сравнением и `rm` другой претендент успевал снять тот же файл и создать СВОЙ — и `rm` удалял
# admin ЖИВОГО участника. `test; rm` не атомарен, третье чтение этого не лечит.
#
# Стало: у каждого участника СВОЁ имя в каталоге `<lock>.adm` — `p.<inst>.<pid>`. Имя уникально
# и никем не переиспользуется, запись появляется атомарно (temp + `ln`: имя возникает сразу с
# содержимым). Вход в секцию — «билеты»: ticket = 1 + максимум видимых, равные тикеты
# разбираются именем. Входит только тот, для кого нет ЖИВОГО участника с меньшим ключом
# (тикет, имя); остальные ждут (ограниченно) и при неудаче НЕ действуют.
# Чужую запись можно снять ТОЛЬКО по её собственному уникальному имени и только после
# доказанной смерти — переиспользовать это имя живой участник не может, поэтому «удалить admin
# живого владельца» становится невозможным по построению, а не по порядку проверок.
# Каталог `<lock>.adm` никогда не удаляется: гонки на его создание/снятие нет.
#
# Crash-release: файл участника остаётся, но следующий участник доказывает смерть по
# pid/pstart/boot (`_jit_death_proven`) и снимает ИМЕННО его запись. Если смерть доказать
# нечем — вход не происходит (безопасный отказ), а не «действие без секции».

# Разобрать запись участника: _ak_t = тикет (для дешёвого сравнения ключей).
_adm_read() {
  _ak_line=${'$'}(cat "${'$'}1" 2>/dev/null)
  _ak_t=${'$'}(printf '%s' "${'$'}_ak_line" | sed -n 's/^ticket=\([0-9][0-9]*\);.*/\1/p')
}

_jit_admin_enter() {
  _adm_dir="${lock}.adm"
  mkdir -p "${'$'}_adm_dir" 2>/dev/null || return 1
  _ad_me="${'$'}{_adm_dir}/p.${'$'}{_me_inst:-x}.${'$'}{_me_pid:-0}"

  # 0) Совместимость: admin-ФАЙЛ прежней версии. Новый код его не создаёт; он может остаться
  #    от прежнего запуска. Его нельзя удалять по имени (именно это и было гонкой), поэтому:
  #    доказали смерть → перенесли в УНИКАЛЬНОЕ имя и проверили, что перенесли тот же объект;
  #    перенесли не тот (появился живой) → возвращаем обратно (ошибка только в сторону лишней
  #    блокировки, никогда — в сторону двух владельцев).
  if [ -f "${lock}.admin" ]; then
    _ad_old=${'$'}(cat "${lock}.admin" 2>/dev/null)
    _jit_death_proven "${'$'}_ad_old" || return 1
    _ad_mv="${'$'}{_adm_dir}/retired.legacy.${'$'}${'$'}"
    mv -f "${lock}.admin" "${'$'}_ad_mv" 2>/dev/null
    _ad_got=${'$'}(cat "${'$'}_ad_mv" 2>/dev/null)
    if [ "${'$'}_ad_got" != "${'$'}_ad_old" ]; then
      ln "${'$'}_ad_mv" "${lock}.admin" 2>/dev/null || cp "${'$'}_ad_mv" "${lock}.admin" 2>/dev/null
      rm -f "${'$'}_ad_mv" 2>/dev/null
      return 1
    fi
    rm -f "${'$'}_ad_mv" 2>/dev/null
  fi

  # 1) Своя запись уже есть (вложенный вход того же shell) — считаем, что мы внутри.
  if [ -f "${'$'}_ad_me" ]; then
    _ad_own=0
    _ad_t=${'$'}(sed -n 's/^ticket=\([0-9][0-9]*\);.*/\1/p' "${'$'}_ad_me" 2>/dev/null)
    return 0
  fi

  # 2) Тикет = 1 + максимум видимых. Равные тикеты (гонка двух создателей) разбираются именем.
  _ad_maxt=0
  for _ad_f in "${'$'}_adm_dir"/p.*; do
    [ -f "${'$'}_ad_f" ] || continue
    _ad_t2=${'$'}(sed -n 's/^ticket=\([0-9][0-9]*\);.*/\1/p' "${'$'}_ad_f" 2>/dev/null)
    case "${'$'}_ad_t2" in ''|*[!0-9]*) continue ;; esac
    [ "${'$'}_ad_t2" -gt "${'$'}_ad_maxt" ] && _ad_maxt=${'$'}_ad_t2
  done
  _ad_t=${'$'}((_ad_maxt + 1))

  # 3) Атомарно: имя появляется сразу с содержимым (temp + `ln`).
  _ad_tmp="${'$'}{_adm_dir}/.tmp.${'$'}{_me_inst:-x}.${'$'}{_me_pid:-0}"
  printf 'ticket=%s;inst=%s;pid=%s;pstart=%s;boot=%s\n' \
    "${'$'}_ad_t" "${'$'}{_me_inst:-?}" "${'$'}{_me_pid:-?}" "${'$'}{_me_pstart:-?}" "${'$'}{_me_boot:-?}" \
    > "${'$'}_ad_tmp" 2>/dev/null || { rm -f "${'$'}_ad_tmp" 2>/dev/null; return 1; }
  if ! ln "${'$'}_ad_tmp" "${'$'}_ad_me" 2>/dev/null; then
    rm -f "${'$'}_ad_tmp" 2>/dev/null
    return 1
  fi
  rm -f "${'$'}_ad_tmp" 2>/dev/null
  _ad_own=1

  # 4) Ждём, пока не останется ЖИВЫХ участников с меньшим ключом.
  #    Порядок проверок выбран по СТОИМОСТИ: сначала дешёвое сравнение тикетов, и только для
  #    участника, который нас реально блокирует, доказывается смерть (это дорого: /proc + awk).
  _ad_wait=0
  while [ "${'$'}_ad_wait" -lt 200 ]; do
    _ad_wait=${'$'}((_ad_wait + 1))
    _ad_block=0
    for _ad_f in "${'$'}_adm_dir"/p.*; do
      [ -f "${'$'}_ad_f" ] || continue
      [ "${'$'}_ad_f" = "${'$'}_ad_me" ] && continue
      _ak_t=${'$'}(sed -n 's/^ticket=\([0-9][0-9]*\);.*/\1/p' "${'$'}_ad_f" 2>/dev/null)
      if [ -z "${'$'}_ak_t" ]; then
        # Содержимое не разобрано: по этому протоколу живой участник так выглядеть не может
        # (имя появляется сразу с содержимым). Снимаем только после доказанной смерти.
        _ad_line=${'$'}(cat "${'$'}_ad_f" 2>/dev/null)
        if _jit_death_proven "${'$'}_ad_line"; then rm -f "${'$'}_ad_f" 2>/dev/null; continue; fi
        _ad_block=1; break
      fi
      # Нас блокирует только МЕНЬШИЙ ключ (тикет, имя). Больший уступит нам сам.
      if [ "${'$'}_ak_t" -gt "${'$'}_ad_t" ]; then continue; fi
      if [ "${'$'}_ak_t" -eq "${'$'}_ad_t" ]; then
        case "${'$'}(basename "${'$'}_ad_f")" in
          "${'$'}(basename "${'$'}_ad_me")") continue ;;
        esac
        # Равные тикеты: приоритет у меньшего имени.
        if [ "${'$'}(basename "${'$'}_ad_f")" \> "${'$'}(basename "${'$'}_ad_me")" ]; then continue; fi
      fi
      _ad_line=${'$'}(cat "${'$'}_ad_f" 2>/dev/null)
      if _jit_death_proven "${'$'}_ad_line"; then
        rm -f "${'$'}_ad_f" 2>/dev/null      # уникальное имя: живой участник не пострадает
        continue
      fi
      _ad_block=1
      break
    done
    [ "${'$'}_ad_block" = "1" ] || return 0
    sleep 0.01
  done
  # Не дождались: уходим БЕЗ секции. Действие в таком состоянии выполнять нельзя.
  rm -f "${'$'}_ad_me" 2>/dev/null
  _ad_own=0
  return 1
}

_jit_admin_exit() {
  _adm_dir="${lock}.adm"
  [ -n "${'$'}{_ad_me:-}" ] || return 0
  # Уборка записей мёртвых участников — в enter (там, где это реально блокирует). Здесь только
  # своя запись: выход не должен зависеть от /proc и не должен стоить лишних процессов.
  if [ "${'$'}{_ad_own:-0}" = "1" ]; then rm -f "${'$'}_ad_me" 2>/dev/null; fi
  _ad_own=0
  return 0
}
# ── ПОЛУЧИТЬ lock: 0 — наш; 1 — занят живым/не доказано; 2 — не взяли админ-файл ──
# $1 — строка, которую надо записать; $2 — строка, смерть которой доказана ('' — не нужно).
_jit_lock_acquire() {
  _la_line=${'$'}1; _la_expect=${'$'}2
  _jit_admin_enter || return 2
  _la_rc=1
  _la_cur=${'$'}(cat ${lock} 2>/dev/null)
  if [ -z "${'$'}_la_cur" ]; then
    # lock отсутствует (или пуст — пустой считается отсутствующим: владельца нет).
    [ -n "${'$'}_la_cur" ] && rm -f ${lock} 2>/dev/null
    _jit_take_lock "${'$'}_la_line" && _la_rc=0
  elif [ -n "${'$'}_la_expect" ] && [ "${'$'}_la_cur" = "${'$'}_la_expect" ]; then
    # Содержимое — РОВНО тот объект, смерть которого доказана. Под админом снимаем имя и
    # ставим своё; живой lock сюда не попадает, потому что его строка другая.
    rm -f ${lock} 2>/dev/null
    [ -f ${lock} ] || { _jit_take_lock "${'$'}_la_line" && _la_rc=0; }
  else
    # Имя занято ДРУГИМ объектом (возможно, живым владельцем): не трогаем.
    _la_rc=1
  fi
  _jit_admin_exit
  return ${'$'}_la_rc
}
# ── ПОРЯДОК НАМЕРЕНИЙ (iter56 §4.3, ужесточён в iter57 §7.1) ──────────────
# Результат — в переменных _ord_verdict/_ord_rule (shell-функция не может вернуть строку).
# Правила, в порядке силы доказательства:
#  1. поколение (seq) с ОБЕИХ сторон → сравниваем числа: больше = новее;
#  2. «не больше» ⇒ уступаем ВСЕГДА (`older_intent`/`seq_tie`). Завершение более нового
#     намерения (его собственный `END`) порядок НЕ меняет: владелец уже выбрал другое, и то,
#     что оно доехало, не делает прежний клик последним. В iter56 здесь была ветка
#     `seq_owner_ended` — она возвращала ровно тот дефект, который убирали в iter55;
#  3. поколения нет (записи прежних версий) → время, с ЯВНЫМ правилом `ts_legacy`.
# Время нигде не выдаётся за доказанный порядок: оно только fallback и наблюдение.
_jit_intent_order() {
  _ord_line=${'$'}1; _ord_meseq=${'$'}2; _ord_mets=${'$'}3; _ord_myop=${'$'}4
  _ord_verdict=free; _ord_rule=none
  _ord_op=${'$'}(printf '%s' "${'$'}_ord_line" | sed -n 's/^op=\([A-Za-z0-9_.-]*\).*/\1/p')
  [ -z "${'$'}_ord_op" ] && { _ord_verdict=free; return 0; }
  [ "${'$'}_ord_op" = "${'$'}_ord_myop" ] && { _ord_verdict=same; return 0; }
  _ord_seq=${'$'}(printf '%s' "${'$'}_ord_line" | sed -n 's/.*seq=\([0-9][0-9]*\).*/\1/p')
  _ord_ts=${'$'}(printf '%s' "${'$'}_ord_line" | sed -n 's/.*ts_ms=\([0-9][0-9]*\).*/\1/p')
  if [ -n "${'$'}_ord_seq" ] && [ "${'$'}_ord_meseq" != "0" ] && [ "${'$'}_ord_seq" != "0" ]; then
    _ord_rule=seq
    if [ "${'$'}_ord_meseq" -gt "${'$'}_ord_seq" ]; then _ord_verdict=newer_intent; return 0; fi
    if [ "${'$'}_ord_meseq" -eq "${'$'}_ord_seq" ]; then _ord_verdict=seq_tie
    else _ord_verdict=older_intent; fi
    return 0
  fi
  if [ -n "${'$'}_ord_ts" ]; then
    _ord_rule=ts_legacy
    if [ "${'$'}_ord_ts" -gt "${'$'}_ord_mets" ]; then _ord_verdict=older_intent
    elif [ "${'$'}_ord_ts" -eq "${'$'}_ord_mets" ]; then _ord_verdict=ts_tie
    else _ord_verdict=newer_intent; fi
    return 0
  fi
  _ord_rule=unprovable
  _ord_verdict=older_intent
  return 0
}
# ── освободить ТОЛЬКО свою блокировку (под admin) ─────────────────────────
# iter58 §2.1: удаление основного lock — такая же СМЕНА объекта, как создание и перехват,
# поэтому она тоже идёт под `<lock>.admin`. Прежний `rm -f` без admin пересекался с перехватом:
# претендент снимал имя и ставил своё, а release удалял УЖЕ ЕГО lock.
# rc 0 — своей блокировки больше нет; 1 — lock не наш (не трогали); 2 — admin занят живым
# владельцем, lock оставлен (его снимет доказанно мёртвый путь: вечной блокировки это не даёт).
_jit_release() {
  _cl=${'$'}(cat ${lock} 2>/dev/null)
  case "${'$'}_cl" in
    *"inst=${'$'}{_me_inst:-?}"*) : ;;
    *) return 1 ;;
  esac
  _jit_admin_enter || return 2
  _cl2=${'$'}(cat ${lock} 2>/dev/null)
  case "${'$'}_cl2" in
    *"inst=${'$'}{_me_inst:-?}"*) rm -f ${lock} 2>/dev/null ;;
  esac
  _jit_admin_exit
  return 0
}

# ── payload ДОПИСЫВАЕТ свою identity в lock (ppid/ppstart) ДО первого действия ──
# Определение обязано существовать ДО вызова. В iter57 вызов стоял выше определения (и ошибка
# была подавлена `2>/dev/null`), поэтому отметка identity payload не выполнялась; функции жили
# только в тексте. Теперь она в общих helper'ах, которые подключаются и claim-командой, и
# гвардией.
_lock_self_mark() {
  _sm_cur=${'$'}(cat ${lock} 2>/dev/null)
  case "${'$'}_sm_cur" in
    *"inst=${'$'}{_me_inst:-?}"*) : ;;
    *) return 1 ;;
  esac
  case "${'$'}_sm_cur" in
    *"ppid="*) return 0 ;;
  esac
  _sm_new="${'$'}_sm_cur;ppid=${'$'}_me_pid;ppstart=${'$'}{_me_pstart:-?}"
  _jit_admin_enter || return 2
  _sm_rc=1
  _sm_try=0
  # iter59 §5.2: «снять имя → поставить своё» безопасно ТОЛЬКО внутри admin-секции (там имя не
  # может изменить никто из наших участников), и результат ОБЯЗАН быть проверен чтением.
  # Раньше (`:575–577`) ошибка обеих попыток оставляла lock отсутствующим, а функция всё равно
  # возвращала 0 — payload шёл к опасному действию без владения.
  while [ "${'$'}_sm_try" -lt 2 ]; do
    _sm_try=${'$'}(( _sm_try + 1 ))
    _sm_now=${'$'}(cat ${lock} 2>/dev/null)
    case "${'$'}_sm_now" in
      *"inst=${'$'}{_me_inst:-?}"*) : ;;
      *) _sm_rc=1; break ;;          # строка больше не наша — не трогаем
    esac
    rm -f ${lock} 2>/dev/null
    if _jit_take_lock "${'$'}_sm_new"; then
      _sm_back=${'$'}(cat ${lock} 2>/dev/null)
      if [ "${'$'}_sm_back" = "${'$'}_sm_new" ]; then _sm_rc=0; break; fi
    fi
    # Не получилось: возвращаем прежнюю строку, чтобы владение не пропало молча.
    _jit_take_lock "${'$'}_sm_now" 2>/dev/null
  done
  _jit_admin_exit
  return ${'$'}_sm_rc
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
        val inst = op.identity?.inst ?: "${op.id}-${op.tsMs}-\$\$"
        // Префикс terminal-файлов: нужен, чтобы уступить владение только ЗАВЕРШИВШЕМУСЯ
        // более новому намерению (см. ниже), не собирая путь в shell из ненадёжной склейки.
        val termPrefix = "$base/voiceapp3_jit_"
        return """
set +e
echo CL_BEGIN
_me_inst=${inst}
${ownHelpers(base)}
_me_line="op=${op.id};ts_ms=${op.tsMs};ts_s=${op.tsS};want=${if (op.want) 1 else 0};seq=${op.seq};inst=${'$'}_me_inst;pid=${'$'}_me_pid;pstart=${'$'}{_me_pstart:-?};boot=${'$'}{_me_boot:-?}"
_cl_status=failed; _cl_owner=""; _cl_why=""
# ── порядок НАМЕРЕНИЙ: поздний claim не отменяет более новое намерение ────────
# `ts_ms` — время клика владельца, а не время выполнения команды. Если в claim уже лежит
# БОЛЕЕ НОВОЕ намерение, эта (устаревшая) операция не имеет права его перезаписать: иначе
# «догоняющий» устаревший payload снимал бы решение, которое владелец принял позже.
# Перезапись чужого claim допускается только если новее МЫ (по поколению) или владелец
# намерения доказанно завершился (его собственный END/смерть) — тогда его намерение уже не
# «последнее», а счётчики относятся к разным поколениям установки.
_cm_seen=$(cat ${claim} 2>/dev/null)
_cm_ts=$(printf '%s' "${'$'}_cm_seen" | sed -n 's/.*ts_ms=\([0-9][0-9]*\).*/\1/p')
_cm_seq=$(printf '%s' "${'$'}_cm_seen" | sed -n 's/.*seq=\([0-9][0-9]*\).*/\1/p')
_cm_op=$(printf '%s' "${'$'}_cm_seen" | sed -n 's/^op=\([A-Za-z0-9_.-]*\).*/\1/p')
_cm_verdict=free; _cm_rule=none
if [ -n "${'$'}_cm_op" ] && [ "${'$'}_cm_op" != "${op.id}" ]; then
  _jit_intent_order "${'$'}_cm_seen" ${op.seq} ${op.tsMs} ${op.id}
  _cm_verdict=${'$'}_ord_verdict; _cm_rule=${'$'}_ord_rule
fi
# iter55 §4.3: НИКАКОГО `newer_finished`. То, что более новое намерение завершилось,
# не делает старое намерение актуальным: владелец уже выбрал другое. Устаревшая операция
# уступает навсегда, иначе «догоняющий» старый payload снимал бы последнее решение.
echo "CL_ORDER seen_op=${'$'}{_cm_op:-none} seen_seq=${'$'}{_cm_seq:-none} seen_ts_ms=${'$'}{_cm_ts:-none} me_seq=${op.seq} rule=${'$'}_cm_rule verdict=${'$'}_cm_verdict"
case "${'$'}_cm_verdict" in
  older_intent|ts_tie|seq_tie)
    _cl_status=stale; _cl_owner="${'$'}_cm_seen"
    _cl_why="newer_intent_${'$'}{_cm_verdict}" ;;
  *)
_lo=""
[ -f ${lock} ] && _lo=${'$'}(cat ${lock} 2>/dev/null)
_mo=1
[ -n "${'$'}_lo" ] && _jit_may_takeover "${'$'}_lo"; _mo=${'$'}?
if [ "${'$'}_mo" = "0" ]; then
  # Смерть доказана И payload не мог начать. Перехват — под админ-файлом и ТОЛЬКО если
  # содержимое всё ещё ровно то, смерть которого доказана.
  _jit_lock_acquire "${'$'}_me_line" "${'$'}_lo"; _acq=${'$'}?
  case "${'$'}_acq" in
    0) echo "CL_TAKEOVER proof=owner_dead_exact_inst owner=${'$'}_lo" ;;
    2) _cl_status=busy; _cl_owner="admin_busy"
       echo "CL_TAKEOVER proof=refused reason=admin_busy (сериализация не получена)" ;;
    *) _cl_status=busy; _cl_owner="race_object_replaced"
       echo "CL_TAKEOVER proof=refused reason=object_replaced (имя уже занято ДРУГИМ lock)" ;;
  esac
elif [ "${'$'}_mo" = "2" ]; then
  # Владелец мёртв, но payload МОГ начать работу (есть START): повторять опасное действие нельзя.
  _cl_status=busy; _cl_owner="payload_possibly_running"
  echo "CL_TAKEOVER proof=refused reason=payload_possibly_running owner=${'$'}_lo"
else
  # Живого владельца не перехватываем: берём только СВОБОДНОЕ имя.
  _jit_lock_acquire "${'$'}_me_line" ""; _acq=${'$'}?
  case "${'$'}_acq" in
    0) : ;;
    2) _cl_status=busy; _cl_owner="admin_busy" ;;
    *) if [ -n "${'$'}_lo" ]; then _cl_status=busy; _cl_owner="${'$'}_lo"
       else _cl_owner="race"; _cl_status=busy; fi ;;
  esac
fi
# ── ПОД lock: ПОРЯДОК НАМЕРЕНИЙ ПРОВЕРЯЕТСЯ ЗАНОВО ────────────────────────────
# Решение выше принято ДО захвата и могло устареть за время ожидания. Здесь claim
# перечитывается уже под lock'ом, и если конкурент успел записать БОЛЕЕ НОВОЕ намерение,
# наш claim не перезаписывает его. Решение и запись происходят в одной критической секции.
if [ "${'$'}_cl_status" != "busy" ]; then
  _v2=${'$'}(cat ${claim} 2>/dev/null)
  _v2_op=${'$'}(printf '%s' "${'$'}_v2" | sed -n 's/^op=\([A-Za-z0-9_.-]*\).*/\1/p')
  _v2_ts=${'$'}(printf '%s' "${'$'}_v2" | sed -n 's/.*ts_ms=\([0-9][0-9]*\).*/\1/p')
  _v2_seq=${'$'}(printf '%s' "${'$'}_v2" | sed -n 's/.*seq=\([0-9][0-9]*\).*/\1/p')
  _v2_verdict=free; _v2_rule=none
  # Под lock порядок пересчитывается ТЕМ ЖЕ правилом, что и до него (ноль расхождений
  # между ранней и авторитетной проверкой). «Не новее» здесь означает уступку.
  if [ -n "${'$'}_v2_op" ] && [ "${'$'}_v2_op" != "${op.id}" ]; then
    _jit_intent_order "${'$'}_v2" ${op.seq} ${op.tsMs} ${op.id}
    _v2_rule=${'$'}_ord_rule
    case "${'$'}_ord_verdict" in
      older_intent|seq_tie|ts_tie) _v2_verdict=newer_intent ;;
      *) _v2_verdict=free ;;
    esac
  fi
  echo "CL_ORDER_UNDER_LOCK seen_op=${'$'}{_v2_op:-none} seen_seq=${'$'}{_v2_seq:-none} seen_ts_ms=${'$'}{_v2_ts:-none} me_seq=${op.seq} rule=${'$'}_v2_rule verdict=${'$'}_v2_verdict"
  if [ "${'$'}_v2_verdict" = "newer_intent" ]; then
    _jit_release
    _cl_status=stale; _cl_owner="${'$'}_v2"; _cl_why="newer_intent_under_lock"
  fi
fi
# ── ПОД lock: замена claim АТОМАРНА (tmp + mv), а не «exists + запись по месту» ──
if [ "${'$'}_cl_status" != "busy" ] && [ "${'$'}_cl_status" != "stale" ]; then
  _tmp="${claim}.tmp.${'$'}${'$'}"
  printf '%s\n' "${'$'}_me_line" > "${'$'}_tmp" 2>/dev/null
  _wr=${'$'}?
  if [ "${'$'}_wr" = "0" ] && mv "${'$'}_tmp" ${claim} 2>/dev/null; then
    _cl_status=written
  else
    rm -f "${'$'}_tmp" 2>/dev/null; _cl_status=failed
  fi
  _rb=${'$'}(cat ${claim} 2>/dev/null)
  # «записано» и «подтверждено чтением» — разные факты: taken ставится ТОЛЬКО после readback.
  if [ "${'$'}_cl_status" = "written" ]; then
    if [ "${'$'}_rb" = "${'$'}_me_line" ]; then _cl_status=taken; else _cl_status=failed; fi
  fi
  _jit_release
fi ;;
esac
echo "CL_STATUS=${'$'}_cl_status"
[ -n "${'$'}_cl_why" ] && echo "CL_WHY=${'$'}_cl_why"
[ "${'$'}_cl_status" = "taken" ] && echo "CL_VALUE=${'$'}_rb"
[ -n "${'$'}_cl_owner" ] && echo "CL_OWNER=${'$'}_cl_owner"
echo CL_END
""".trimIndent()
    }

    /** Результат записи claim: «занято» отделено от «записано» и от «неизвестно». */
    sealed interface ClaimWrite {
        data class Taken(val line: String) : ClaimWrite
        data class Busy(val owner: String) : ClaimWrite

        /**
         * iter54 §4: на устройстве уже лежит БОЛЕЕ НОВОЕ намерение. Это не «занято на время»:
         * уступающий обязан остановиться насовсем (его решение отменено владельцем позже).
         */
        data class Stale(val newer: String) : ClaimWrite

        data class Failed(val why: String) : ClaimWrite
    }

    /** Разбор вывода [claimCmd]: те же строгие правила, что и у чтения. */
    /**
     * iter58 §2.1 — тот же текст общих shell-функций владения, но доступный тесту.
     *
     * Смысл: проверки admin-lock, `release`, `_lock_self_mark` и доказательства смерти должны
     * исполняться НАСТОЯЩИМ `/bin/sh` на том же коде, который уходит на устройство, а не на его
     * пересказе в тесте. Ничего, кроме доступа к тексту, эта функция не добавляет.
     */
    internal fun ownHelpersForTest(base: String = BASE): String = ownHelpers(base)

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
            "stale" -> ClaimWrite.Stale(owner.firstOrNull() ?: "unknown")
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

    /**
     * iter56 §4.3 — ПРОВЕРКА ФОРМАТА значения claim ПО ПОЛЯМ.
     *
     * Жёсткая склейка regex'ом ломалась от любого нового поля (`seq=`): запись становилась
     * «нечитаемой» на клиенте, хотя на устройстве была валидной. Здесь проверяется структура:
     * ключ=значение, известный набор ключей, обязательные `op` и `want`, ровно одно поле времени.
     */
    private fun validClaimValue(raw: String): Boolean {
        val parts = raw.split(';')
        if (parts.any { it.isEmpty() || !it.contains('=') }) return false
        val known = setOf("op", "ts", "ts_ms", "ts_s", "want", "seq", "inst", "pid", "pstart", "boot")
        val fields = LinkedHashMap<String, String>()
        for (part in parts) {
            val k = part.substringBefore('=')
            if (k !in known) return false
            if (fields.containsKey(k)) return false
            fields[k] = part.substringAfter('=')
        }
        val op = fields["op"] ?: return false
        if (!Regex("[A-Za-z0-9_.-]{1,64}").matches(op)) return false
        if (fields["want"] !in setOf("0", "1")) return false
        // Время: либо старое `ts=` (одно поле), либо `ts_ms=` и НЕОБЯЗАТЕЛЬНОЕ `ts_s=`
        // (production пишет оба: миллисекунды для порядка, секунды для читаемости).
        if (fields.containsKey("ts")) {
            if (fields.containsKey("ts_ms") || fields.containsKey("ts_s")) return false
        } else if (!fields.containsKey("ts_ms")) return false
        for (k in listOf("ts", "ts_ms", "ts_s", "seq")) {
            fields[k]?.let { if (!Regex("\\d{1,19}").matches(it)) return false }
        }
        return true
    }

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
                // iter56 §4.3: формат проверяется ПО ПОЛЯМ — новое поле записи не делает
                // валидный claim «нечитаемым» на клиенте.
                if (!validClaimValue(raw)) return ReadReply(ClaimRead.Unknown("claim_value_invalid"), term)
                // Поля читаются по имени (формат уже проверен [validClaimValue]).
                val f = raw.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
                // Время вне диапазона — НЕ повод объявить claim «испорченным»: владение
                // определяется id, а не часами. Неизвестное время остаётся 0.
                val tsMs = Op.parseTsMs(raw) ?: 0L
                ClaimRead.Present(
                    Op(
                        id = f.getValue("op"),
                        tsMs = tsMs,
                        want = f["want"] == "1",
                        state = State.CLAIMED,
                        identity = ClaimIdentity.parse(raw)
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
     * iter54 §4 — РЕШЕНИЕ ОБ ОТПРАВКЕ PAYLOAD по исходу захвата владения.
     *
     * Это единственное место, где «claim не записан» превращается в «payload не отправляется».
     * Раньше `ClaimWrite.Failed` логировался как `action=proceed`, и скрипт всё равно уходил на
     * устройство: гвардия, прочитав НЕ наш claim, отказывалась работать, и клик гарантированно
     * ничего не делал — но в журнале это было «отправлено», то есть исход подменялся намерением.
     *
     * Правила:
     *  • `Taken` → можно отправлять (владелец записан и проверен чтением);
     *  • `Busy` → нельзя: владелец жив или его смерть не доказана;
     *  • `Stale` → нельзя и повторять не нужно: намерение отменено более НОВЫМ решением;
     *  • `Failed` → нельзя: граница записи намерения не установлена, fencing отсутствует.
     *    Это честный pending: намерение владельца сохранено, но применять его «вслепую» нельзя.
     */
    data class Gate(val send: Boolean, val code: String, val why: String)

    fun claimGate(write: ClaimWrite): Gate = when (write) {
        is ClaimWrite.Taken -> Gate(true, "claimed", "владение записано и проверено чтением")
        is ClaimWrite.Busy -> Gate(
            false, "op_in_flight",
            "на устройстве владеет другая JIT-операция, её завершение не доказано"
        )
        is ClaimWrite.Stale -> Gate(
            false, "superseded_by_newer_intent",
            "на устройстве более НОВОЕ намерение: устаревшая операция не имеет права его снять"
        )
        is ClaimWrite.Failed -> Gate(
            false, "claim_unproven",
            "запись/чтение claim не подтверждены (${write.why}): fencing нет, payload не отправляется"
        )
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
        // Значение приходит из Kotlin (один экземпляр на клик) и совпадает с записанным в claim;
        // без него — `$$` этого shell (совместимость/тесты).
        val inst = op.identity?.inst ?: "$id-$tsMs-\$\$"
        return """
# ── iter53 §5: lock, ПОД lock актуальный claim, и только потом действие ────
# Идентичность экземпляра объявляется ПЕРВОЙ: её пишут и отказ, и terminal.
_me_inst=${inst}
_vc=${'$'}(cat ${claim} 2>/dev/null)
_lo=""
# iter56 §4.1: отказ НЕ пишет `END`. Прежняя строка `END rc=0 … refused=1` выглядела как
# завершение операции и вместе с совпадающими op+inst (повторная доставка того же payload)
# служила ложным доказательством смерти ДЕЙСТВУЮЩЕГО владельца — по ней разрешался перехват
# живого lock. Отказ — это след экземпляра (`REFUSED`), а не конец намерения.
_refuse() {
  echo "REFUSED op=${id} inst=${'$'}{_me_inst:-?} reason=${'$'}1 detail=${'$'}2 claim=${'$'}{_vc:-none}" >> ${term} 2>/dev/null
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
# iter55 §4.2: ЭКЗЕМПЛЯР проверяется ДО захвата lock. Иначе повтор той же операции (второй
# shell с тем же opId) мог занять lock, обнаружить несовпадение inst ПОД lock и отказаться,
# оставив настоящего владельца без владения — операция не выполнялась никем. Отказ по inst
# теперь не касается lock вовсе, поэтому актуальный экземпляр проходит свободно.
_vc_inst0=${'$'}(printf '%s' "${'$'}_vc" | sed -n 's/.*inst=\([^;]*\).*/\1/p')
case "${'$'}_vc_inst0" in
  ""|"${'$'}_me_inst") : ;;
  *) _refuse instance_mismatch "claim_inst=${'$'}_vc_inst0 me=${'$'}_me_inst (до lock)" ;;
esac
${ownHelpers(base)}
_me_line="op=${id};ts_ms=${tsMs};ts_s=${tsS};inst=${'$'}_me_inst;pid=${'$'}_me_pid;pstart=${'$'}{_me_pstart:-?};boot=${'$'}{_me_boot:-?}"
_lk=0
if [ -f ${lock} ]; then
  _lo=${'$'}(cat ${lock} 2>/dev/null)
  case "${'$'}_lo" in
    *"inst=${'$'}_me_inst"*) _refuse duplicate_instance "${'$'}_lo" ;;
  esac
  _mo=1
  _jit_may_takeover "${'$'}_lo"; _mo=${'$'}?
  if [ "${'$'}_mo" = "2" ]; then
    # Payload мог начать работу: его смерть не доказана, повторять опасное действие нельзя.
    _refuse payload_possibly_running "${'$'}_lo"
  fi
  if [ "${'$'}_mo" = "0" ]; then
    # Смерть доказана. Перехват — под админ-файлом и только того объекта, содержимое которого
    # проверено: живой lock никогда не перемещается и не удаляется.
    _jit_lock_acquire "${'$'}_me_line" "${'$'}_lo"; _acq=${'$'}?
    case "${'$'}_acq" in
      0) _lk=1; echo "JIT_LOCK_TAKEOVER proof=owner_dead_exact_inst owner=${'$'}_lo" ;;
      2) _refuse admin_busy "${'$'}_lo" ;;
      *) _refuse lock_object_replaced "${'$'}_lo" ;;
    esac
  else
    case "${'$'}_lo" in
      "op=${id};"*|"op=${id}") _refuse same_op_live "${'$'}_lo" ;;
    esac
    _age=${'$'}(printf '%s' "${'$'}_lo" | sed -n 's/.*ts_s=\([0-9][0-9]*\).*/\1/p')
    _now=${'$'}(date +%s 2>/dev/null)
    if [ -n "${'$'}_age" ] && [ -n "${'$'}_now" ]; then
      echo "JIT_LOCK_AGED owner=${'$'}_lo age_s=${'$'}(( _now - _age )) action=none (возраст НЕ доказательство)"
    fi
    # iter56 §5: lock мог держать КОРОТКИЙ претендент — команда записи claim другого намерения,
    # которая ещё не успела уступить (она живёт миллисекунды). Отказ без ожидания отменял бы
    # легитимный клик из-за чужой 30-байтовой команды: два одновременных клика давали НОЛЬ
    # side effect'ов вместо одного. Ждём ОГРАНИЧЕННО (до 2 c), не выполняя никаких действий;
    # если lock так и держат — отказ, как раньше. Взяли — идём к проверке claim ПОД lock.
    _lw=0
    while [ "${'$'}_lw" -lt 40 ]; do
      _jit_lock_acquire "${'$'}_me_line" "" && { _lk=1; break; }
      sleep 0.05
      _lw=${'$'}(( _lw + 1 ))
    done
    [ "${'$'}_lk" = "1" ] || _refuse lock_held "${'$'}_lo"
  fi
else
  # Кто первый создал файл — тот и владелец; содержимое уже внутри (temp + ln) и создание
  # сериализовано админ-файлом.
  _jit_lock_acquire "${'$'}_me_line" "" && _lk=1
  [ "${'$'}_lk" = "1" ] || _refuse lock_race ""
fi
# iter59 §5.2: отметка identity payload'а обязательна. Не поставили (lock потерян/подменён) —
# опасное действие НЕ выполняется: без своего lock владение не доказано, а `umount`/`iptables`
# уже необратимы.
if ! _lock_self_mark; then
  _jit_release
  _refuse self_mark_failed "pid=${'$'}_me_pid pstart=${'$'}{_me_pstart:-?} lock=${'$'}{_lo:-none}"
fi
# ── ПОД lock: актуальный claim (закрывает TOCTOU «прочитал до lock») ───────
_vc2=${'$'}(cat ${claim} 2>/dev/null)
case "${'$'}_vc2" in
  "op=${id};"*|"op=${id}") : ;;
  *) _jit_release; _refuse superseded_under_lock "claim=${'$'}_vc2" ;;
esac
# iter54 §4: владелец — это НЕ только opId. Если в claim записан ДРУГОЙ экземпляр той же
# операции (повторная отправка/ретрай/другой shell), это чужое владение: опасное действие
# выполнять нельзя, иначе два экземпляра одной операции подействуют дважды.
_vc2_inst=${'$'}(printf '%s' "${'$'}_vc2" | sed -n 's/.*inst=\([^;]*\).*/\1/p')
case "${'$'}_vc2_inst" in
  ""|"${'$'}_me_inst") : ;;
  *) _jit_release; _refuse instance_mismatch "claim_inst=${'$'}_vc2_inst me=${'$'}_me_inst" ;;
esac
# ── iter57 §7.3: payload ПЕРЕПИСЫВАЕТ lock своей identity ДО первого действия ──
# Теперь lock описывает процесс, который реально исполняет опасное действие (`ppid`), а не
# короткоживущий shell записи claim. Без этого «claim-процесс мёртв» ошибочно считалось
# доказательством конца намерения, и свежий lock живого payload можно было перехватить.
# ── начало операции видно на устройстве ───────────────────────────────────
# `inst` обязателен: по нему terminal отличается «этот экземпляр» от «тот же opId, другой shell».
# ДОЗАПИСЬ, а не усечение: отказ другого экземпляра мог быть записан раньше, и `>` его терял.
echo "START ts=${'$'}(date +%s) op=${id} inst=${'$'}_me_inst" >> ${term} 2>/dev/null
# iter56 §5: конец намерения доказывается ТОЧНОЙ identity — END пишет op, inst, pid, pstart и
# boot того же shell, что записал lock. Иначе конец «намерения» доказывался бы любым END с тем же
# op+inst (повторная доставка) — и lock живого владельца считался бы брошенным.
trap 'echo "END rc=$? op=${id} inst=${'$'}_me_inst pid=${'$'}{_me_pid:-?} pstart=${'$'}{_me_pstart:-?} boot=${'$'}{_me_boot:-?}" >> ${term} 2>/dev/null; _jit_release; _jit_admin_exit 2>/dev/null' EXIT
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
        val note: String = "",
        /**
         * iter54 §4: ЭКЗЕМПЛЯР, к которому относятся эти факты (`inst`), и сколько ДРУГИХ
         * экземпляров той же операции оставили следы в том же terminal-файле.
         *
         * Раньше все строки файла смешивались: `END rc=0` экземпляра B (отказ) читался как
         * завершение экземпляра A. Теперь факты берутся по ОДНОМУ экземпляру, а чужие только
         * считаются — отказ B больше не подменяет terminal A.
         */
        val inst: String = "",
        val otherInstances: Int = 0
    ) {
        fun wire(): String = "started=$started finished=$finished rc=${rc ?: "?"} " +
            "refused=$refused claim_other=$claimOther inst=${if (inst.isEmpty()) "?" else inst}" +
            (if (otherInstances > 0) " other_inst=$otherInstances" else "")
    }

    /**
     * Разбор вывода [readCmd].
     *
     * @param opId чья операция интересует.
     * @param out  вывод команды чтения (возможно, с ошибкой транспорта).
     * @return `null` — прочитать НЕ УДАЛОСЬ (неизвестность не снимается).
     */
    fun parseTerminal(
        opId: String,
        out: String,
        rc: Int = 0,
        expectInst: String? = null
    ): Terminal? {
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
        val mineAll = lines.filter { Regex("(^| )op=" + Regex.escape(opId) + "( |$)").containsMatchIn(it) }
        val foreignCount = lines.size - mineAll.size
        fun instOf(l: String): String =
            Regex("(?:^| )inst=([^ ]+)").find(l)?.groupValues?.get(1) ?: ""
        // iter56 §5: terminal привязан к КОНКРЕТНОМУ ИСПОЛНЕНИЮ, а не к «последнему, что нашлось
        // в файле». Прежний выбор `lastInst` делал факты зависимыми от порядка строк: любой
        // поздний след (в том числе отказ повторной доставки того же payload) подменял предмет
        // чтения, и его `END rc=0` становился «нашим завершением».
        //
        // Правило: исполнение опознаётся по START/END. Если клиент знает свой `inst`
        // ([expectInst] — он записан в durable-отметке ДО отправки), факты берутся ТОЛЬКО его.
        // Если не знает и исполнений несколько — атрибуция НЕВОЗМОЖНА: `started`/`finished`
        // не заявляются (неизвестность не подменяется самой «удобной» строкой файла).
        val pidOf = { l: String -> Regex("(?:^| )pid=([^ ]+)").find(l)?.groupValues?.get(1) }
        val execInsts = mineAll.filter { it.startsWith("START") || it.startsWith("END") }
            .map { instOf(it) }.distinct()
        val refusedInsts = mineAll.filter { it.startsWith("REFUSED") }.map { instOf(it) }.distinct()
        val wantInst = expectInst?.takeIf { it.isNotBlank() }
        // Файл прежних версий: события есть, но НИ ОДНО не несёт identity. Тогда привязка к
        // запрошенному исполнению не выдумывается, но и не отбрасывается: строки привязываются
        // к исполнению БЕЗ identity, и это названо в note (`inst_unproven_legacy`).
        val onlyUnidentified = execInsts.isNotEmpty() && execInsts.all { it.isEmpty() }
        val chosen: String? = when {
            wantInst != null -> if (onlyUnidentified) "" else wantInst
            execInsts.size == 1 -> execInsts[0]
            execInsts.size > 1 -> null
            // START/END нет: занимать нечем — если клиент не сказал, чьё исполнение ему нужно,
            // остаются только отказы, и они НЕ являются событиями исполнения.
            else -> null
        }
        val ambiguous = chosen == null && execInsts.size > 1
        val mine = if (chosen == null) emptyList() else mineAll.filter { instOf(it) == chosen }
        val otherInst = mineAll.map { instOf(it) }.filter { it.isNotEmpty() && it != chosen }
            .distinct().size
        val startLine = mine.firstOrNull { it.startsWith("START") }
        val endLine = mine.lastOrNull { it.startsWith("END") }
        // END принадлежит тому же исполнению, что и START: при известных pid они обязаны совпасть
        // (END пишет полную identity; чужой END с тем же inst — не наше завершение).
        val endPid = endLine?.let(pidOf)
        val startPid = startLine?.let(pidOf)
        val sameExec = endLine == null || startPid == null || endPid == null || startPid == endPid
        val rcEnd = if (sameExec) endLine?.let {
            Regex("rc=(-?\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
        } else null
        // Отказ доказывается ТОЛЬКО своим экземпляром (или живым stdout нашего payload'а).
        // Отказ из файла от другого исполнения той же операции — не наш отказ: он не доказывает,
        // что наша цепочка не выполнялась.
        val refused = (chosen != null && refusedInsts.contains(chosen)) ||
            Regex("JIT_REFUSED op=" + Regex.escape(opId) + "( |$)").containsMatchIn(out)
        val startOk = !ambiguous && startLine != null
        val finishOk = !ambiguous && endLine != null && rcEnd != null
        return Terminal(
            started = startOk,
            finished = finishOk,
            rc = rcEnd,
            refused = refused,
            claimOther = claim != null && claim.id != opId,
            note = claimNote +
                (if (foreignCount > 0) ";term_lines_other_op=$foreignCount" else "") +
                (if (lines.isNotEmpty() && mine.isEmpty()) ";term_only_foreign_op" else "") +
                (if (otherInst > 0) ";term_other_instance=$otherInst" else "") +
                (if (ambiguous) ";term_exec_ambiguous=${execInsts.size}" else "") +
                (if (wantInst != null && chosen != null && !mine.any { it.startsWith("START") })
                    ";term_no_own_execution" else "") +
                (if (onlyUnidentified) ";inst_unproven_legacy" else ""),
            inst = chosen ?: "",
            otherInstances = otherInst
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
