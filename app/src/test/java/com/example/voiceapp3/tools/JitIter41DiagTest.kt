package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * iter41 §5.1 — «фактически записано» вместо «сборщик сформировал строку».
 *
 * Уровень проверки: **настоящий адаптер на модели файла**. Строки идут через production
 * `LogBudget.WriteTally` и настоящий писатель датированного лога `LogFiles.appendToDir`
 * (`voiceapp3-DD.MM.YYYY.log`, append+flush) во ВРЕМЕННЫЙ каталог. Не проверено: `AppLog` с
 * настоящим Android `Context` (внешний/internal каталог, `getExternalFilesDir`) — это Android-код,
 * поэтому «фоллбэк внешний→внутренний» моделируется сменой каталога приёмника, а не самим
 * `LogFiles.append`. На ГУ ничего не запускалось.
 */
class DiagWriteConfirmation41Test {

    private fun tmpDir(prefix: String = "diag41"): File =
        Files.createTempDirectory(prefix).toFile()

    /**
     * Приёмник поверх РЕАЛЬНОГО `LogFiles.appendToDir`.
     *
     * `dirRef` можно переключить ПОСРЕДИ блока — это модель фоллбэка external→internal.
     * `failOn` позволяет смоделировать отказ записи конкретной строки (диск/ошибка ввода-вывода).
     */
    private class DirSink(
        var dir: File,
        private val failOn: (String) -> Boolean = { false },
        private val dropOn: (String) -> Boolean = { false }
    ) : LogBudget.Sink {
        val written = mutableListOf<String>()
        val independent = mutableListOf<String>()
        val locationsSeen = LinkedHashSet<String>()

        override fun write(line: String): LogBudget.LogWrite {
            locationsSeen += dir.absolutePath
            if (dropOn(line)) return LogBudget.LogWrite(LogBudget.WriteOutcome.DROPPED)
            if (failOn(line)) return LogBudget.LogWrite(LogBudget.WriteOutcome.FAILED)
            // iter42: production-писатель возвращает точный файл (включая ротацию по размеру).
            val r = LogFiles.appendTierForTest(dir, line, LogFiles.Tier.MAIN)
            if (r.written) written += line
            return if (r.written) LogBudget.LogWrite(LogBudget.WriteOutcome.WRITTEN, r.file)
            else LogBudget.LogWrite(LogBudget.WriteOutcome.FAILED)
        }

        override fun location(): String = dir.absolutePath

        override fun independent(line: String) {
            independent += line
        }

        fun textOf(d: File = dir): String =
            d.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
                ?.joinToString("\n") { it.readText() } ?: ""
    }

    private fun okShell(): JitRef.JitShell = object : JitRef.JitShell {
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            val section = Regex("__JMD_(\\w+)_RC").find(command)?.groupValues?.get(1) ?: return 0 to ""
            return 0 to "0: from all lookup local\n__JMD_${section}_RC=0\n__JMD_EOF=1"
        }
        override fun mapsExcerpt(): String? = null
    }

    private fun mountShell(): JitRef.JitShell = object : JitRef.JitShell {
        override fun exec(command: String, timeoutMs: Int) = 0 to
            "1 0 8:1 / /data/app/com.example rw 0 0\n__JMD_ID_RC=0\n__JMD_NSSELF_RC=0\n" +
            "__JMD_NSINIT_RC=0\n__JMD_WC_RC=0\n__JMD_CAT_RC=0\n__JMD_EOF=1"
        override fun mapsExcerpt(): String? = null
    }

    // prefix + пробел: иначе `…_END` совпал бы с `…_END_CONFIRM`
    private fun endOf(lines: List<String>, prefix: String): String =
        lines.lastOrNull { it.startsWith("$prefix ") || it == prefix }
            ?: error("нет строки $prefix; было:\n" + lines.joinToString("\n") {
                it.substringBefore(" note=").take(110)
            })

    /**
     * iter43 §2.1: ИТОГ блока — строка `…_END_CONFIRM` (END провизионная, `written=unconfirmed`).
     */
    private fun confirmOf(lines: List<String>, block: String): String =
        endOf(lines, "${block}_END_CONFIRM")

    // ─────────────────────────── §5.1: сеть ───────────────────────────

    @Test
    fun fullOnlyWhenEveryLineIsConfirmedInOneFile() {
        val dir = tmpDir()
        val sink = DirSink(dir)
        InternetDiag.collect(okShell(), true, null, sink, "test", "1.0.61") { false }

        val end = endOf(sink.written, "INTERNET_DIAG_END")
        assertTrue("END не заявляет подтверждённый итог: $end", end.contains("written=unconfirmed"))
        val confirm = confirmOf(sink.written, "INTERNET_DIAG")
        assertTrue("подтверждение записи: $confirm", confirm.contains("written=full"))
        // iter42 §2.6: указывается ТОЧНЫЙ файл строки, а не каталог.
        assertTrue("указан конкретный файл: $confirm",
            confirm.contains("sink=«${dir.absolutePath}/${LogFiles.fileNameFor(System.currentTimeMillis())}»"))
        // `_END_CONFIRM` сама себя не считает: в её тексте все строки ДО неё.
        val blockLines = sink.written.size - 1
        assertTrue("число строк подтверждено: $confirm",
            confirm.contains("lines=$blockLines/$blockLines"))
        assertTrue("усечения не было видно", confirm.contains("skipped_by_budget=0"))

        // iter43: `.logfiles.lock` — служебный файл координации, не часть журнала.
        val files = dir.listFiles()!!.map { it.name }.filter { it.endsWith(".log") }
        assertEquals("один файл на сутки", 1, files.size)
        assertTrue("имя файла по образцу владельца: $files",
            files[0].matches(Regex("voiceapp3-\\d{2}\\.\\d{2}\\.\\d{4}\\.log")))
        val text = sink.textOf()
        assertTrue("BEGIN в файле", text.contains("INTERNET_DIAG_BEGIN"))
        assertTrue("END в файле", text.contains("INTERNET_DIAG_END"))
        assertTrue("секции в файле", text.contains("INTERNET_DIAG_SECTION"))
        assertTrue(sink.independent.isEmpty())
    }

    @Test
    fun failureInTheMiddleOfABlockIsPartialNotFull() {
        val dir = tmpDir()
        // Отказ ровно на одной строке ВНУТРИ блока (`DATA` раздела identity), не на BEGIN/END.
        val sink = DirSink(dir, failOn = { it.startsWith("INTERNET_DIAG_DATA name=wlan0_addr") })
        InternetDiag.collect(okShell(), true, null, sink, "test", "1.0.61") { false }

        val confirm = confirmOf(sink.written, "INTERNET_DIAG")
        assertFalse("нельзя утверждать full: $confirm", confirm.contains("written=full"))
        assertTrue("честная метка: $confirm", confirm.contains("written=partial"))
        assertTrue("видно число неудачных записей: $confirm", confirm.contains("write_failed=1"))
        assertTrue("виден первый сбой: $confirm", confirm.contains("write_reason=write_failed=1"))
        assertFalse("эта строка действительно не в файле", sink.written.any { it.contains("__FAILED__") })
    }

    @Test
    fun failingEndIsReportedThroughIndependentChannel() {
        val dir = tmpDir()
        // Отказ ровно у `…_END` (не у подтверждающей строки).
        val sink = DirSink(dir, failOn = { it.startsWith("INTERNET_DIAG_END ") })
        InternetDiag.collect(okShell(), true, null, sink, "test", "1.0.61") { false }

        assertTrue("сводка не попала в файл", sink.written.none { it.startsWith("INTERNET_DIAG_END ") })
        val msg = sink.independent.joinToString("\n")
        assertTrue("факт ушёл в независимый канал: $msg", msg.contains("INTERNET_DIAG_END_NOT_CONFIRMED"))
        assertTrue("указан sink: $msg", msg.contains("sink=«"))
    }

    @Test
    fun skippedByBudgetIsTruncatedNotFull() {
        val dir = tmpDir()
        val sink = DirSink(dir, dropOn = { it.contains("_DATA") })
        InternetDiag.collect(okShell(), true, null, sink, "test", "1.0.61") { false }

        val confirm = confirmOf(sink.written, "INTERNET_DIAG")
        assertTrue("пропуск бюджетом виден отдельно: $confirm", confirm.contains("skipped_by_budget="))
        assertFalse("не full", confirm.contains("written=full"))
        assertTrue("метка truncated: $confirm", confirm.contains("written=truncated"))
    }

    @Test
    fun fallbackInTheMiddleIsReportedAsTwoSinks() {
        val first = tmpDir("diag41a")
        val second = tmpDir("diag41b")
        val sink = DirSink(first)
        // Первые 5 строк — во внешний каталог, дальше «переключение» на внутренний.
        var n = 0
        val switching = object : LogBudget.Sink {
            override fun write(line: String): LogBudget.LogWrite {
                if (n++ == 5) sink.dir = second
                return sink.write(line)
            }
            override fun location(): String = sink.location()
            override fun independent(line: String) { sink.independent += line }
        }
        InternetDiag.collect(okShell(), true, null, switching, "test", "1.0.61") { false }

        val confirm = confirmOf(sink.written, "INTERNET_DIAG")
        assertTrue("части в разных файлах видны: $confirm", confirm.contains("written=partial"))
        assertTrue("указаны оба места: $confirm",
            confirm.contains("split_across_sinks") && confirm.contains("+"))
        assertTrue("в первом каталоге есть файл", (first.listFiles()?.isNotEmpty() == true))
        assertTrue("во втором каталоге есть файл", (second.listFiles()?.isNotEmpty() == true))
        assertTrue("BEGIN ушёл в первый файл", sink.textOf(first).contains("INTERNET_DIAG_BEGIN"))
    }

    @Test
    fun unwritableDirectoryIsNoneNotEmpty() {
        // Каталог, которого нет и который нельзя создать (под ним лежит файл).
        val blocker = File(tmpDir(), "not-a-dir").apply { writeText("x") }
        val sink = DirSink(blocker)
        InternetDiag.collect(okShell(), true, null, sink, "test", "1.0.61") { false }

        assertTrue("ни одна строка не записана", sink.written.isEmpty())
        val anyEnd = sink.independent.joinToString("\n")
        assertTrue("факт вне файла: $anyEnd", anyEnd.contains("END_NOT_CONFIRMED"))
        assertTrue("честная метка отсутствия записи: $anyEnd", anyEnd.contains("reason="))
    }

    @Test
    fun truncationProbeOverridesFull() {
        val dir = tmpDir()
        for ((probe, expected) in listOf<Pair<(() -> Boolean)?, String>>(
            ({ true }) to "written=truncated",
            ({ false }) to "written=full",
            (null as (() -> Boolean)?) to "written=unknown"
        )) {
            val sink = DirSink(dir)
            InternetDiag.collect(okShell(), true, null, sink, "test", "1.0.61", probe)
            val confirm = confirmOf(sink.written, "INTERNET_DIAG")
            assertTrue("ожидалась метка $expected, получено: $confirm", confirm.contains(expected))
        }
    }

    // ─────────────────────────── §5.1: монтирования ───────────────────────────

    @Test
    fun mountBlockConfirmsItsOwnWrittenVerdict() {
        val dir = tmpDir("diag41m")
        val sink = DirSink(dir)
        JitMountDiag.collect(mountShell(), true, sink, "test") { false }

        // iter43 §2.1: END больше НЕ заявляет подтверждённый итог (она не может подтвердить саму
        // себя). Итог сообщается отдельной строкой `…_END_CONFIRM` уже после записи END.
        val end = endOf(sink.written, "JIT_MOUNT_DIAG_END")
        assertTrue("END не выдаёт себя за подтверждённый итог: $end", end.contains("written=unconfirmed"))
        assertFalse("в END нет ложного full: $end", end.contains("written=full"))
        val confirm = endOf(sink.written, "JIT_MOUNT_DIAG_END_CONFIRM")
        assertTrue("итог подтверждён в конкретном файле: $confirm", confirm.contains("written=full"))
        assertTrue("указан файл: $confirm",
            confirm.contains("sink=«${dir.absolutePath}/${LogFiles.fileNameFor(System.currentTimeMillis())}»"))
        assertTrue("сеть не трогали", end.contains("system_writes=0"))
    }

    @Test
    fun mountBlockFailureIsPartialAndDoesNotClaimFull() {
        val dir = tmpDir("diag41m2")
        val sink = DirSink(dir, failOn = { it.startsWith("JIT_MOUNT_DIAG_DATA name=identity") })
        JitMountDiag.collect(mountShell(), true, sink, "test") { false }
        val confirm = confirmOf(sink.written, "JIT_MOUNT_DIAG")
        assertFalse("не full: $confirm", confirm.contains("written=full"))
        assertTrue("partial: $confirm", confirm.contains("written=partial"))
    }

    // ─────────────────────────── §5.1: сам писатель ───────────────────────────

    @Test
    fun datedWriterConfirmsAndUsesOwnerName() {
        val dir = tmpDir("diag41w")
        val now = 1_700_000_000_000L
        // Содержимое — ASCII: кодировка по умолчанию JVM в этом окружении не UTF-8, и
        // кириллица при записи в файл заменялась бы на '?'. Кодировка здесь ни при чём.
        assertTrue(LogFiles.appendToDir(dir, "first line", now))
        assertTrue(LogFiles.appendToDir(dir, "second line", now))
        // iter43: в каталоге есть служебный `.logfiles.lock` (межпроцессная координация) —
        // частью журнала он не является, поэтому выбираем именно файл лога.
        val file = dir.listFiles()!!.single { it.name.endsWith(".log") }
        assertEquals(LogFiles.fileNameFor(now), file.name)
        assertEquals(listOf("first line", "second line"), file.readLines())

        val blocker = File(dir, "file-not-dir").apply { writeText("x") }
        assertFalse("запись в файл-вместо-каталога не подтверждается",
            LogFiles.appendToDir(blocker, "строка", now))
    }

    // ─────────────────────────── §5.2: попытки сбора ───────────────────────────

    @Test
    fun diagOnceLimitsRunsAndTracksCompletion() {
        val once = JitRef.DiagOnce(2)
        assertEquals("idle", once.status())
        assertEquals(1, once.tryRun())
        assertEquals("started", once.status())
        assertFalse("пока не завершён — не собран", once.completedNow())
        assertFalse(once.finish(false))
        assertEquals("started", once.status())
        assertEquals(2, once.tryRun())
        assertTrue("успех фиксируется по факту", once.finish(true))
        assertEquals("completed", once.status())
        assertNull("третьей попытки нет", once.tryRun())
        assertEquals(2, once.runsUsed())
    }

    @Test
    fun failedRunDoesNotConsumeTheSuccessfulOne() {
        val once = JitRef.DiagOnce(2)
        once.tryRun()
        assertFalse("сборщик упал — успеха нет", once.finish(false))
        assertFalse(once.completedNow())
        assertNotNull("ограниченная повторная попытка остаётся", once.tryRun())
        assertTrue(once.finish(true))
        assertTrue(once.completedNow())
    }

    @Test
    fun exhaustionIsHonestAndNotSuccess() {
        val once = JitRef.DiagOnce(1)
        once.tryRun()
        once.finish(false)
        assertNull(once.tryRun())
        assertTrue(once.exhausted())
        assertFalse("исчерпание — это не собранный лог", once.completedNow())
        assertEquals("exhausted", once.status())
    }

    @Test
    fun competingRequestsNeverExceedTheLimit() {
        val once = JitRef.DiagOnce(2)
        val got = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val threads = (1..8).map {
            Thread { once.tryRun()?.let { r -> got.add(r) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        assertEquals("ровно две попытки, без дублей", 2, got.size)
        assertEquals(setOf(1, 2), got.toSet())
    }
}
