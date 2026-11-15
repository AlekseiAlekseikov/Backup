package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * iter54 §9 — ЖУРНАЛ: без content-дедупликации, одна межпроцессная дисциплина.
 *
 * Дефекты (аудит §9):
 *  1. `recoverPendingNew` выбрасывал строки, которые уже встречались в хвосте активного файла.
 *     Одинаковые строки в журнале ЛЕГАЛЬНЫ (два события могут совпасть по тексту) — записи
 *     терялись молча;
 *  2. слияние `.new` шло БЕЗ лока, хотя append/ротация идут под `<name>.lock`: два процесса
 *     могли переносить одно и то же, а третий в это время писать в активный файл;
 *  3. `.new` удалялся по факту вызова `delete`, а не по доказанному результату.
 *
 * Всё проверяется на НАСТОЯЩИХ файлах и настоящих потоках (каждый со своим экземпляром
 * логгера, как отдельный процесс).
 */
class Iter54LogTest {

    private fun dir(tag: String): File =
        File.createTempFile("it54log_$tag", "").let { f -> f.delete(); f.mkdirs(); f }

    @Test
    fun identicalRecordsAreNotDeduplicatedByContent() {
        val d = dir("dup")
        val active = File(d, "voiceapp3.log")
        val same = "2026-10-22 10:00:00 | SAME EVENT"
        // В активном файле УЖЕ есть такая же строка — и это нормально.
        active.writeText(same + "\n" + "tail\n")
        // В `.new` лежит та же по тексту запись, но это ДРУГОЕ событие. Она НЕ на границе
        // (последняя запись активного файла — "tail"), значит и повода её пропускать нет.
        File(d, "voiceapp3.log.new").writeText(same + "\n")

        val note = FileLogCore.recoverPendingNew(d, "voiceapp3.log")
        val lines = active.readLines()
        assertEquals("обе записи сохранены (content-дедупликации нет): $note", 3, lines.size)
        assertEquals("одинаковых записей две: $lines", 2, lines.count { it == same })
        assertTrue("граничного совпадения не было: $note", note.contains("boundary_same=0"))
        assertTrue("перенос назван: $note", note.contains("new_merged=1"))
        assertFalse("`.new` убран только после переноса", File(d, "voiceapp3.log.new").exists())
    }

    @Test
    fun boundaryMatchIsReportedButTheRecordIsKept() {
        val d = dir("boundary")
        val active = File(d, "voiceapp3.log")
        active.writeText("first\ncarry\n")
        // Та же строка на границе. iter55 §8: совпадение текста и позиции НЕ доказывает, что это
        // одно событие, поэтому запись не выбрасывается — оба события остаются в журнале.
        File(d, "voiceapp3.log.new").writeText("carry\nnext\n")
        val note = FileLogCore.recoverPendingNew(d, "voiceapp3.log")
        assertEquals("перенесены ВСЕ записи: $note", listOf("first", "carry", "carry", "next"),
            active.readLines())
        assertTrue("совпадение названо фактом, а не поводом удалить: $note",
            note.contains("boundary_same=1"))
        assertTrue("перенесены обе записи: $note", note.contains("new_merged=2"))
    }

    @Test
    fun identicalBoundaryRecordsAreBothKept() {
        // Требование iter55 §8 буквально: active="same\n", .new="same\n" (два самостоятельных
        // события) → в активном файле ДВЕ записи "same", ни одно событие не исчезло.
        val d = dir("same_boundary")
        val active = File(d, "voiceapp3.log")
        active.writeText("same\n")
        File(d, "voiceapp3.log.new").writeText("same\n")
        val note = FileLogCore.recoverPendingNew(d, "voiceapp3.log")
        assertEquals("две самостоятельные записи сохранены: $note", listOf("same", "same"),
            active.readLines())
        assertTrue(note.contains("boundary_same=1"))
    }

    @Test
    fun threeIdenticalRecordsSurviveRecovery() {
        val d = dir("three")
        val active = File(d, "voiceapp3.log")
        active.writeText("same\n")
        File(d, "voiceapp3.log.new").writeText("same\nsame\n")
        FileLogCore.recoverPendingNew(d, "voiceapp3.log")
        assertEquals("три записи на месте", 3, active.readLines().count { it == "same" })
    }

    @Test
    fun incompleteTailIsNotTreatedAsBoundary() {
        val d = dir("tail")
        val active = File(d, "voiceapp3.log")
        // Активный файл БЕЗ перевода строки в конце: последняя запись неполная.
        active.writeText("first\ncarry")
        File(d, "voiceapp3.log.new").writeText("carry\nnext\n")
        FileLogCore.recoverPendingNew(d, "voiceapp3.log")
        val text = active.readText()
        assertTrue("ничего не потеряно: «$text»", text.contains("carry"))
        assertTrue("вторая запись добавлена: «$text»", text.contains("next"))
    }

    @Test
    fun pendingIsKeptWhenTheSameLockIsHeldByAnotherProcess() {
        val d = dir("locked")
        val active = File(d, "voiceapp3.log")
        active.writeText("a\n")
        val pending = File(d, "voiceapp3.log.new").apply { writeText("b\n") }
        // Другой «процесс» держит тот же лок-файл.
        val lockFile = File(d, "voiceapp3.log.lock")
        java.io.RandomAccessFile(lockFile, "rw").use { raf ->
            val fl = raf.channel.lock()
            try {
                val note = FileLogCore.recoverPendingNew(d, "voiceapp3.log")
                assertTrue("под чужим локом слияние не выполняется: $note",
                    note.startsWith("new_locked_kept"))
                assertTrue("`.new` сохранён как свидетельство", pending.exists())
                assertEquals("активный файл не изменён", listOf("a"), active.readLines())
            } finally {
                runCatching { fl.release() }
            }
        }
        // Лок освобождён — слияние проходит.
        val note2 = FileLogCore.recoverPendingNew(d, "voiceapp3.log")
        assertTrue("после освобождения лока перенос выполнен: $note2", note2.contains("new_merged=1"))
        assertEquals(listOf("a", "b"), active.readLines())
    }

    @Test
    fun concurrentRecoveryFromTwoProcessesDoesNotDuplicate() {
        val d = dir("conc")
        val active = File(d, "voiceapp3.log")
        active.writeText("x\n")
        repeat(20) { File(d, "voiceapp3.log.new").appendText("event-$it\n") }
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) {
            Thread {
                start.await(5, TimeUnit.SECONDS)
                FileLogCore.recoverPendingNew(d, "voiceapp3.log")
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("оба «процесса» завершились", done.await(20, TimeUnit.SECONDS))
        val lines = active.readLines()
        assertEquals("каждая запись ровно один раз", 21, lines.size)
        assertEquals("дублей нет", lines.size, lines.toSet().size)
        assertFalse("`.new` не остался", File(d, "voiceapp3.log.new").exists())
    }

    @Test
    fun appendKeepsIdenticalLines() {
        val d = dir("append")
        val ok1 = FileLogCore.append(d, "voiceapp3.log", "same\n")
        val ok2 = FileLogCore.append(d, "voiceapp3.log", "same\n")
        assertTrue("обе записи приняты: ${ok1.wire()} / ${ok2.wire()}", ok1.written && ok2.written)
        assertEquals("одинаковые строки сохранены обе", listOf("same", "same"),
            File(d, "voiceapp3.log").readLines())
    }
}
