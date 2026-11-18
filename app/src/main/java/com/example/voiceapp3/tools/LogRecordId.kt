package com.example.voiceapp3.tools

import java.io.File

/**
 * iter63 §P0-1 — ИДЕНТИЧНОСТЬ ЗАПИСИ ЖУРНАЛА (дубли различаются по СОБЫТИЮ, а не по тексту).
 *
 * Что было не так.
 *
 *  • iter55 §8: граничная запись (`активный` + `.new`) переносилась ДВАЖДЫ — в журнале появлялся
 *    дубль одного события (владелец это и видел).
 *  • iter62 §B2: чтобы убрать дубль, выбрасывалась ЛЮБАЯ запись `.new`, текст которой совпал с
 *    последней записью активного файла. Это уже ПОТЕРЯ события: две одинаковые строки реально
 *    бывают (`TTL_TRY action=skip`, повторный `LOCKSOUND_RESTORE_RESULT`, два `TOGGLE_HOLD` с
 *    одинаковыми полями). Владелец просил «не обрезать полезные записи», а не «убрать дубли
 *    любой ценой».
 *
 * Как теперь. Каждая запись получает ЯВНЫЙ идентификатор события: монотонный `rid` + отметку
 * времени `ts` + номер попытки записи `try`. Идентификатор виден в тексте строки суффиксом
 * ` [r=<rid>/<ts>/<try>]`, поэтому он переживает и `.new`, и ротацию, и перезапуск процесса:
 *
 *  • слияние `.new` выбрасывает РОВНО ту запись, чей идентификатор уже есть в активном файле;
 *  • две разные записи с одинаковым текстом имеют разные `rid` и сохраняются ОБЕ;
 *  • старая строка без суффикса (журналы прежних версий) получает идентификатор, выведенный из
 *    содержимого и позиции — она тоже не дублируется при переносе.
 *
 * iter64 — ГЛАВНАЯ ПОПРАВКА. В iter63 `rid` был счётчиком `AtomicLong(0)` В ПАМЯТИ ПРОЦЕССА.
 * Второй процесс того же APK (а JIT и TTL работают именно в нём) начинал с `rid=1`, и его запись
 * при слиянии `.new` выбрасывалась как «уже есть»; после `ctl.restart zygote`/рестарта — то же
 * самое. Идентичность обязана быть уникальной между потоками, процессами и ЗАПУСКАМИ.
 *
 * Поэтому единица идентичности — строка журнала целиком:
 *
 *     <тело> [r=<rid>/<ts>/<try>/w=<writer>]
 *
 *  • `rid` = (время мс в младших битах) | (хеш писателя в битах 41+) — значение, не зависящее от
 *    того, какой процесс его выдал; монотонность внутри процесса обеспечивается конструктором
 *    [EventKeyState];
 *  • `writer` = короткая (8 hex) метка писателя: pid + starttime процесса + boot_id
 *    (`/proc`), либо случайная метка процесса, если `/proc` недоступен;
 *  • `try` — номер попытки ЗАПИСИ одного события: повтор после отказа `fsync` даёт тот же ключ.
 *
 * Ключ события — [eventKey]: (rid, writer). Суффикс разбирается ОДНИМ парсером ([parse]) и
 * используется всеми путями (`appendStamped`, `.new`, spill, ротация) — отдельного дедупа нет,
 * есть одна функция [addNew]: вставляется только запись, чьего ключа нет среди уже принятых.
 *
 * Разбор строк БЕЗ суффикса (журналы прежних версий) остаётся прежним: такие строки переносятся
 * все — потеря невозможна, возможный дубль называется.
 *
 * Модуль чистый (без Android), поэтому проверяется JVM-тестами на реальных файлах, а конструктор
 * ключа — ещё и двумя РЕАЛЬНЫМИ процессами (`Iter64TwoProcessMain`).
 */
object LogRecordId {

    /** Разбор строки: `rid`, `ts`, `try`, `writer` и «тело» без суффикса. */
    data class Parsed(
        val rid: Long,
        val ts: Long,
        val tryNo: Int,
        val body: String,
        /** Метка писателя (`w=`); пусто у строк журнала прежних версий (iter63 и раньше). */
        val writer: String = ""
    )

    /** Ключ события: `rid` + писатель. `try` НЕ входит (повтор записи — то же событие). */
    data class EventKey(val rid: Long, val writer: String)

    /** Суффикс iter64: ` [r=<rid>/<ts>/<try>/w=<writer>]`. */
    private val SUFFIX = Regex("^(.*) \\[r=(\\d+)/(\\d+)/(\\d+)/w=([0-9a-f]{1,16})]$")

    /** Суффикс iter63 (` [r=<rid>/<ts>/<try>]`, без писателя) — читается, но писателем не выдаётся. */
    private val SUFFIX_LEGACY = Regex("^(.*) \\[r=(\\d+)/(\\d+)/(\\d+)]$")

    /**
     * Идентификатор, выведенный из содержимого и позиции — для строк прежних версий.
     *
     * Позиция (`offset`) обязательна: без неё две одинаковые старые строки получили бы один и
     * тот же идентификатор, и одна из них считалась бы дублем (та же потеря, только тише).
     */
    fun derivedId(body: String, lineIndex: Long): Long {
        var h = -3750763034362895579L   // FNV-1a 64 offset basis (литералом: без переполнения)
        for (b in body.toByteArray(Charsets.UTF_8)) {
            h = h xor b.toLong()
            h *= 0x100000001b3L
        }
        h = h xor (lineIndex * 0x9e3779b97f4a7c15uL.toLong())
        return h and 0x7fffffffffffffffL
    }

    /** Разобрать строку журнала (с суффиксом или без). */
    fun parse(line: String, lineIndex: Long = 0L): Parsed {
        SUFFIX.matchEntire(line)?.let { m ->
            val rid = m.groupValues[2].toLongOrNull()
            val ts = m.groupValues[3].toLongOrNull()
            val tryNo = m.groupValues[4].toIntOrNull()
            if (rid != null && ts != null && tryNo != null) {
                return Parsed(rid, ts, tryNo, m.groupValues[1], m.groupValues[5])
            }
        }
        // Строка iter63: ключ события у неё есть, но без писателя. Такую строку НЕЛЬЗЯ считать
        // «той же», что и новая запись iter64 с тем же `rid`: писатель неизвестен, а iter63
        // выдавал `rid` с 1 в каждом процессе. Поэтому `writer` остаётся пустым и ключ отличается.
        SUFFIX_LEGACY.matchEntire(line)?.let { m ->
            val rid = m.groupValues[2].toLongOrNull()
            val ts = m.groupValues[3].toLongOrNull()
            val tryNo = m.groupValues[4].toIntOrNull()
            if (rid != null && ts != null && tryNo != null) {
                return Parsed(rid, ts, tryNo, m.groupValues[1], "")
            }
        }
        return Parsed(derivedId(line, lineIndex), 0L, 0, line, "")
    }

    fun parseAll(lines: List<String>): List<Parsed> = lines.mapIndexed { i, l -> parse(l, i.toLong()) }

    /**
     * Ключ идентичности события — `rid` + писатель. `try` не входит: повторная запись ТОЙ ЖЕ
     * записи после отказа fsync — это то же событие, а не второе.
     *
     * У строк журнала прежней версии писателя нет; их ключ строится от `derivedId(body, index)`,
     * который тоже учитывает писателя-«наследство» пустой строкой.
     */
    fun eventKey(p: Parsed): EventKey = EventKey(p.rid, p.writer)

    /** Ключ события для строки целиком (с позицией — для строк без суффикса). */
    fun keyOf(line: String, lineIndex: Long = 0L): EventKey = eventKey(parse(line, lineIndex))

    /** Несёт ли строка ЯВНЫЙ идентификатор события (iter63 или iter64 — оба читаются). */
    fun isStamped(line: String): Boolean {
        val t = line.trimEnd('\n')
        return SUFFIX.matches(t) || SUFFIX_LEGACY.matches(t)
    }

    /** Строка выдана текущим форматом (с писателем)? */
    fun isWriterStamped(line: String): Boolean = SUFFIX.matches(line.trimEnd('\n'))

    fun format(body: String, rid: Long, ts: Long, tryNo: Int, writer: String): String =
        "$body [r=$rid/$ts/$tryNo/w=$writer]"

    /**
     * Приём одной новой записи в набор уже принятых.
     *
     * ЕДИНСТВЕННОЕ место, где решается «дубль или нет» — для `.new`, spill, work-файлов и
     * ротации. Правило: запись с ЯВНЫМ ключом отбрасывается только если такой ключ уже принят;
     * строка без суффикса (журнал прежней версии) принимается всегда — потерь нет, дубль честно
     * называется.
     *
     * @return true — запись добавлена (в `out` и в `seen`).
     */
    fun addNew(out: MutableList<String>, seen: MutableSet<EventKey>, line: String, lineIndex: Long): Boolean {
        val explicit = isStamped(line)
        val key = keyOf(line, lineIndex)
        if (explicit && !seen.add(key)) return false
        if (!explicit) seen.add(key)
        out.add(line)
        return true
    }

    /** Набор ключей принятых записей (порядок строк сохраняется). */
    fun seenOf(lines: List<String>): MutableSet<EventKey> {
        val seen = HashSet<EventKey>(lines.size * 2)
        lines.forEachIndexed { i, l -> seen.add(keyOf(l, i.toLong())) }
        return seen
    }

    /**
     * Слить содержимое `.new` в активный файл: отбрасывается ровно та запись, чьё событие уже
     * есть в активном файле.
     *
     * @return пары (строка, взята ли она из `.new`), чтобы вызывающий мог честно посчитать
     *         `new_merged` и `boundary_same`.
     */
    fun merge(active: List<String>, pending: List<String>): List<String> {
        val out = ArrayList<String>(active.size + pending.size)
        out.addAll(active)
        val seen = seenOf(active)
        pending.forEachIndexed { i, line -> addNew(out, seen, line, active.size + i.toLong()) }
        return out
    }

    /** Сколько записей `.new` реально добавляется (для отчёта `new_merged=N`). */
    fun mergedCount(active: List<String>, pending: List<String>): Int =
        merge(active, pending).size - active.size

    fun readLines(f: File): List<String> =
        if (!f.isFile) emptyList() else f.readLines().filter { it.isNotEmpty() }
}
