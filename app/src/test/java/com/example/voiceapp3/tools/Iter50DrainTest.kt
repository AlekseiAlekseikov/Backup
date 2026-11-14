package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter50 §8.1 — восстановление spill и work-файлов (`.drain`) на РЕАЛЬНЫХ файлах.
 *
 * Дефекты на HEAD `FileLogCore.kt`:
 *  * `drainSpill` выходил по `if (!spill.isFile) return 0` — сохранённый `.drain` (процесс умер
 *    между переименованием и слиянием) НЕ восстанавливался никогда;
 *  * `spill.renameTo(work)` мог ЗАМЕНИТЬ уже существующий `.drain` — сохранённые строки
 *    исчезали вместе с ним.
 *
 * Тест исполняет настоящий `FileLogCore.drainSpill`/`spillFor` на настоящих файлах.
 * Чего он НЕ доказывает: поведение ext4/f2fs при отключении питания, реальный fsync на
 * устройстве, гонку между двумя ЖИВЫМИ процессами (моделируется отдельно, через lock-файл).
 */
class Iter50DrainTest {

    private fun dir(): File = File.createTempFile("it50drain", "").let {
        it.delete(); it.mkdirs(); it
    }

    /** Имя work-файла внутри процесса: `<spill>.drain` (к нему же добавляется суффикс). */
    private fun workName(active: File): String = FileLogCore.spillFor(active).name + ".drain"

    @Test
    fun existingDrainIsRecoveredEvenWithoutNewSpill() {
        val d = dir()
        val active = File(d, "v.log").apply { writeText("a\n") }
        val spill = FileLogCore.spillFor(active)
        // spill отсутствует, но `.drain` остался от умершего процесса.
        val work = File(d, workName(active)).apply { writeText("b\n") }
        assertFalse(spill.exists())

        val n = FileLogCore.drainSpill(d, "v.log")

        assertEquals("строки восстановлены", 1, n)
        assertEquals("порядок сохранён", "a\nb\n", active.readText())
        assertFalse("work-файл снят после подтверждённой записи", work.exists())
        assertEquals("", FileLogCore.lastDrainNote())
        d.deleteRecursively()
    }

    @Test
    fun newSpillDoesNotOverwriteExistingDrain() {
        val d = dir()
        val active = File(d, "v.log").apply { writeText("a\n") }
        val spill = FileLogCore.spillFor(active).apply { writeText("s\n") }
        val work = File(d, workName(active)).apply { writeText("d\n") }

        val n = FileLogCore.drainSpill(d, "v.log")

        assertEquals("перенесены обе порции", 2, n)
        val text = active.readText()
        assertTrue("сохранённый work не потерян: $text", text.contains("d\n"))
        assertTrue("новый spill тоже перенесён: $text", text.contains("s\n"))
        assertEquals("порядок: сначала оставшийся work, потом свой spill", "a\nd\ns\n", text)
        assertFalse("work снят", work.exists())
        assertFalse("spill снят", spill.exists())
        d.deleteRecursively()
    }

    @Test
    fun drainSurvivesWhenMergeFailsAndNoteIsEmpty() {
        val d = dir()
        // Активный путь занят КАТАЛОГОМ → дозапись невозможна (модель «нет места/нет прав»).
        val active = File(d, "v.log").apply { mkdirs() }
        val work = File(d, workName(active)).apply { writeText("kept\n") }

        val n = FileLogCore.drainSpill(d, "v.log")

        assertEquals("слияние не подтверждено", -1, n)
        assertTrue("work-файл сохранён", work.isFile)
        assertEquals("содержимое не потеряно", "kept\n", work.readText())
        d.deleteRecursively()
    }

    @Test
    fun emptyDrainIsRemovedWithoutTouchingJournal() {
        val d = dir()
        val active = File(d, "v.log").apply { writeText("a\n") }
        val work = File(d, workName(active)).apply { writeText("") }

        val n = FileLogCore.drainSpill(d, "v.log")

        assertEquals(0, n)
        assertEquals("a\n", active.readText())
        assertFalse("пустой work убран", work.exists())
        d.deleteRecursively()
    }

    @Test
    fun lockedDrainIsNotMergedTwiceInParallel() {
        val d = dir()
        val active = File(d, "v.log").apply { writeText("a\n") }
        val work = File(d, workName(active)).apply { writeText("x\n") }
        // Живой владелец держит lock-файл этого work-файла.
        val lock = File(d, work.name + ".lock")
        java.io.RandomAccessFile(lock, "rw").use { raf ->
            val fl = raf.channel.lock()
            try {
                val n = FileLogCore.drainSpill(d, "v.log")
                assertEquals("в чужое слияние не влезаем", 0, n)
                assertTrue("work остался", work.isFile)
                assertTrue("журнал не тронут", active.readText() == "a\n")
            } finally {
                fl.release()
            }
        }
        // После освобождения lock файл сливается штатно.
        assertEquals(1, FileLogCore.drainSpill(d, "v.log"))
        assertEquals("a\nx\n", active.readText())
        d.deleteRecursively()
    }

    // ───────────── §8.2: восстановление `.new` после падения между rename и promote ─────────

    @Test
    fun pendingNewIsPromotedWhenActiveFileIsGone() {
        val d = dir()
        // Модель падения: активный файл уже переименован в `.1`, новая часть осталась в `.new`.
        File(d, "v.log.1").writeText("old\n")
        File(d, "v.log.new").writeText("newpart\n")

        val r = FileLogCore.append(d, "v.log", "next\n", maxBytes = Long.MAX_VALUE)

        assertTrue("строка записана: ${r.reason}", r.written)
        val active = File(d, "v.log")
        assertEquals("новая часть восстановлена и строка дописана", "newpart\nnext\n", active.readText())
        assertFalse("`.new` больше не висит", File(d, "v.log.new").exists())
        assertEquals("прежняя часть не тронута", "old\n", File(d, "v.log.1").readText())
        d.deleteRecursively()
    }

    @Test
    fun pendingNewIsNotPromotedOverLiveActive() {
        val d = dir()
        val active = File(d, "v.log").apply { writeText("live\n") }
        File(d, "v.log.new").writeText("pending\n")

        val r = FileLogCore.append(d, "v.log", "next\n", maxBytes = Long.MAX_VALUE)

        assertTrue(r.written)
        assertEquals("активный файл — источник истины, живые данные не подменяются", "live\nnext\n", active.readText())
        assertEquals("`.new` не тронут без нужды", "pending\n", File(d, "v.log.new").readText())
        d.deleteRecursively()
    }
}
