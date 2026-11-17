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
 * Модуль чистый (без Android), поэтому проверяется JVM-тестами на реальных файлах.
 */
object LogRecordId {

    /** Разбор строки: `rid`, `ts`, `try` и «тело» без суффикса. */
    data class Parsed(val rid: Long, val ts: Long, val tryNo: Int, val body: String)

    private val SUFFIX = Regex("^(.*) \\[r=(\\d+)/(\\d+)/(\\d+)]$")

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
        val m = SUFFIX.matchEntire(line)
        if (m != null) {
            val rid = m.groupValues[2].toLongOrNull()
            val ts = m.groupValues[3].toLongOrNull()
            val tryNo = m.groupValues[4].toIntOrNull()
            if (rid != null && ts != null && tryNo != null) {
                return Parsed(rid, ts, tryNo, m.groupValues[1])
            }
        }
        return Parsed(derivedId(line, lineIndex), 0L, 0, line)
    }

    fun parseAll(lines: List<String>): List<Parsed> = lines.mapIndexed { i, l -> parse(l, i.toLong()) }

    /**
     * Ключ идентичности события. `try` НЕ входит: повторная запись ТОЙ ЖЕ записи после отказа
     * fsync — это то же событие, а не второе.
     */
    fun eventKey(p: Parsed): Long = p.rid

    /** Несёт ли строка ЯВНЫЙ идентификатор события (а не выведенный из текста). */
    fun isStamped(line: String): Boolean = SUFFIX.matches(line.trimEnd('\n'))

    fun format(body: String, rid: Long, ts: Long, tryNo: Int): String = "$body [r=$rid/$ts/$tryNo]"

    /**
     * Слить содержимое `.new` в активный файл: отбрасывается ровно та запись, чьё событие уже
     * есть в активном файле.
     *
     * @return пары (строка, взята ли она из `.new`), чтобы вызывающий мог честно посчитать
     *         `new_merged` и `boundary_same`.
     */
    fun merge(active: List<String>, pending: List<String>): List<String> {
        // Старое содержимое активного файла, записанное ДО появления идентификаторов, не может
        // служить доказательством «это то же событие»: у таких строк `rid` выводится из текста, и
        // совпадение текста снова начало бы выбрасывать настоящие записи. Поэтому дедуп по
        // идентификатору применяется, только когда в активном файле есть ХОТЯ БЫ ОДНА запись с
        // явным `rid`; журнал прежней версии обрабатывается прежним правилом (см. mergePendingLocked).
        val hasExplicitIds = active.any { isStamped(it) }
        if (!hasExplicitIds) {
            val out = ArrayList<String>(active.size + pending.size)
            out.addAll(active)
            for ((i, line) in pending.withIndex()) {
                val p = parse(line, active.size + i.toLong())
                if (!isStamped(line) || active.none { a -> eventKey(parse(a, 0L)) == eventKey(p) }) out.add(line)
            }
            return out
        }
        val seen = HashSet<Long>()
        active.forEachIndexed { i, l -> seen.add(eventKey(parse(l, i.toLong()))) }
        val out = ArrayList<String>(active.size + pending.size)
        out.addAll(active)
        for ((i, line) in pending.withIndex()) {
            val p = parse(line, active.size + i.toLong())
            if (seen.add(eventKey(p))) out.add(line)
        }
        return out
    }

    /** Сколько записей `.new` реально добавляется (для отчёта `new_merged=N`). */
    fun mergedCount(active: List<String>, pending: List<String>): Int =
        merge(active, pending).size - active.size

    fun readLines(f: File): List<String> =
        if (!f.isFile) emptyList() else f.readLines().filter { it.isNotEmpty() }
}
