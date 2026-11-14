package com.example.voiceapp3.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter51 §7 — удаление по boolean и восстановление осиротевших `.spill/.drain` ВСЕХ наших pid.
 *
 * Дефекты на HEAD:
 *  1. `runCatching { f.delete() }.isSuccess` — истинно и при `delete() == false` (файл остался).
 *     «Удалено» заявлялось для файлов, лежащих на диске, и след дублей терялся.
 *  2. Восстанавливался только spill СВОЕГО pid: файлы умерших процессов
 *     (`<log>.<pid>.spill`, `.drain.<pid>-N`) не забирал никто и никогда.
 */
class Iter51LogOrphanTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "iter51log_" + System.nanoTime())
    private val oldClock = FileLogCore.clock
    private val oldProc = FileLogCore.procRoot

    private fun setup(): File {
        dir.mkdirs()
        // «Старые» файлы: часы теста далеко впереди, поэтому любой pid без /proc считается мёртвым.
        FileLogCore.clock = { 10_000_000_000_000L }
        FileLogCore.procRoot = File(dir, "proc")
        return dir
    }

    @After
    fun tearDown() {
        FileLogCore.clock = oldClock
        FileLogCore.procRoot = oldProc
        dir.deleteRecursively()
    }

    @Test
    fun deleteVerifiedHonoursBooleanNotAbsenceOfException() {
        setup()
        val gone = File(dir, "gone.txt")
        assertTrue("отсутствующий файл — это «его нет»", FileLogCore.deleteVerified(gone))

        val f = File(dir, "kept.txt").apply { writeText("x") }
        assertTrue(FileLogCore.deleteVerified(f))
        assertFalse("файл действительно удалён", f.exists())

        // Каталог с содержимым: `delete()` вернёт false, файл останется.
        val d = File(dir, "busy").apply { mkdirs() }
        File(d, "inner.txt").writeText("y")
        assertFalse("неудачное удаление НЕ выдаётся за успех", FileLogCore.deleteVerified(d))
        assertTrue("каталог на месте", d.exists())
    }

    @Test
    fun orphanSpillOfDeadProcessIsRecovered() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("live\n") }
        // Умерший pid 424242: /proc/424242 в подставленном корне отсутствует.
        File(d, "app.log.424242.spill").writeText("orphan1\norphan2\n")

        val n = FileLogCore.drainSpill(d, "app.log")

        assertEquals("две строки осиротевшего spill перенесены", 2, n)
        val text = active.readText()
        assertTrue("строка 1 в журнале: $text", text.contains("orphan1"))
        assertTrue("строка 2 в журнале: $text", text.contains("orphan2"))
        assertFalse("spill удалён", File(d, "app.log.424242.spill").exists())
        assertTrue("факт в заметке: ${FileLogCore.lastDrainNote()}",
            FileLogCore.lastDrainNote().contains("orphans_recovered:1"))
    }

    @Test
    fun orphanDrainOfDeadProcessIsRecoveredEvenWithoutNewSpill() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("live\n") }
        File(d, "app.log.31337.spill.drain.31337-1").writeText("drained\n")

        val n = FileLogCore.drainSpill(d, "app.log")
        assertEquals(1, n)
        assertTrue(active.readText().contains("drained"))
        assertFalse(File(d, "app.log.31337.spill.drain.31337-1").exists())
    }

    @Test
    fun liveProcessFilesAreNotTouched() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("live\n") }
        // pid 999 жив: есть /proc/999 и файл только что изменён (по часам теста).
        File(d, "proc/999").mkdirs()
        val spill = File(d, "app.log.999.spill").apply { writeText("boom\n") }
        FileLogCore.clock = { spill.lastModified() + 1_000L }

        val n = FileLogCore.drainSpill(d, "app.log")

        assertEquals("ничего не перенесено", 0, n)
        assertTrue("живой spill не тронут", spill.exists())
        assertEquals("и не изменён", "boom\n", spill.readText())
        assertFalse("в журнал не попал", active.readText().contains("boom"))
    }

    @Test
    fun pidReuseIsHandledByFreshness() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("") }
        // /proc/777 существует, но файл spill СТАРЫЙ (за пределами окна) — pid переиспользован.
        File(d, "proc/777").mkdirs()
        val spill = File(d, "app.log.777.spill").apply { writeText("stale\n") }
        spill.setLastModified(1_000L)               // древний
        FileLogCore.clock = { spill.lastModified() + 31L * 60L * 1000L }

        val n = FileLogCore.drainSpill(d, "app.log")
        assertEquals(1, n)
        assertTrue("переиспользованный pid: старые строки восстановлены", active.readText().contains("stale"))
    }

    @Test
    fun foreignLogFilesAreNeverTouched() {
        val d = setup()
        File(d, "app.log").writeText("")
        val other = File(d, "other.log.424242.spill").apply { writeText("не наше\n") }
        val plain = File(d, "notes.txt").apply { writeText("не наше\n") }

        FileLogCore.drainSpill(d, "app.log")

        assertTrue("другой журнал не тронут", other.exists())
        assertEquals("не наше\n", other.readText())
        assertTrue(plain.exists())
    }

    @Test
    fun ownSpillStillWorksAfterOrphanPass() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("") }
        File(d, "app.log.424242.spill").writeText("orphan\n")
        FileLogCore.spillAppend(active, "own\n")

        val n = FileLogCore.drainSpill(d, "app.log")
        assertEquals("осиротевшая + своя строка", 2, n)
        val text = active.readText()
        assertTrue(text.contains("orphan"))
        assertTrue(text.contains("own"))
    }
}
