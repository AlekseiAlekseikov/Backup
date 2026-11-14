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
        // «Старые» файлы: часы теста далеко впереди.
        FileLogCore.clock = { 10_000_000_000_000L }
        FileLogCore.procRoot = File(dir, "proc")
        // iter53 §10.1: `/proc` считается ДОСТУПНЫМ только если читается `self/stat`. Без этого
        // «процесса нет» недоказуемо, и осиротевшие файлы не забираются (см. Liveness).
        File(FileLogCore.procRoot, "self").mkdirs()
        File(FileLogCore.procRoot, "self/stat").writeText("1 (test) " + (3..21).joinToString(" ") + " 1\n")
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
    fun silentOwnerIsNotDeclaredDeadByAge() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("") }
        // /proc/777 существует, файл spill СТАРЫЙ. Раньше это считалось «pid переиспользован ⇒
        // мёртв» и строки забирали из-под возможно живого писателя. iter53 §10.1: молчание
        // >30 минут — НЕ смерть, а UNKNOWN: файл не трогаем, факт откладывания фиксируем.
        File(d, "proc/777").mkdirs()
        val spill = File(d, "app.log.777.spill").apply { writeText("stale\n") }
        spill.setLastModified(1_000L)
        FileLogCore.clock = { spill.lastModified() + 31L * 60L * 1000L }

        val n = FileLogCore.drainSpill(d, "app.log")

        assertEquals("неизвестная живость — не забираем", 0, n)
        assertTrue("spill на месте", spill.exists())
        assertFalse("строки не перенесены", active.readText().contains("stale"))
        val index = FileLogCore.orphanIndexFile(d, "app.log")
        assertTrue("отложенный владелец назван: $index", index.isFile)
        assertTrue("причина и pid видны: ${index.readText()}",
            index.readText().contains("pid=777") && index.readText().contains("liveness=unknown"))
    }

    @Test
    fun reusedPidWithDifferentStartTimeIsDeadAndRecovered() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("") }
        // Доказательство смерти: под pid 777 СЕЙЧАС другой процесс (другой starttime).
        // Тег с идентичностью (`<pid>-<boot8>-<starttime>`) — только он позволяет сравнивать
        // starttime; старый числовой тег доказуемо мёртвым по starttime сделать нельзя (в нём
        // нет сохранённого starttime) — он остаётся UNKNOWN, если процесс существует.
        val boot = FileLogCore.readBootId()?.take(8) ?: return
        val proc = File(d, "proc/777").apply { mkdirs() }
        File(proc, "stat").writeText("777 (other) " + (3..21).joinToString(" ") + " 555000\n")
        File(d, "app.log.777-$boot-111000.spill").writeText("reused\n")

        val n = FileLogCore.drainSpill(d, "app.log")

        assertEquals("доказанный другой процесс — забираем", 1, n)
        assertTrue(active.readText().contains("reused"))
        assertFalse(File(d, "app.log.777-$boot-111000.spill").exists())
    }

    @Test
    fun livenessHasThreeDistinctStates() {
        val d = setup()
        val spill = File(d, "app.log.900.spill").apply { writeText("x\n") }
        // Доказанно жив: /proc/900/stat с совпадающим starttime и boot-частью тега.
        val boot = FileLogCore.readBootId()?.take(8) ?: return
        val proc = File(d, "proc/900").apply { mkdirs() }
        File(proc, "stat").writeText("900 (p) " + (3..21).joinToString(" ") + " 4242\n")
        assertEquals(FileLogCore.Liveness.LIVE_SAME_PROCESS,
            FileLogCore.livenessOf("900-$boot-4242", listOf(spill)))
        // Доказанно другой процесс.
        assertEquals(FileLogCore.Liveness.DEAD_OR_DIFFERENT_PROCESS,
            FileLogCore.livenessOf("900-$boot-9999", listOf(spill)))
        // Доказать нельзя: /proc недоступен (в подставленном корне нет self/stat).
        val broken = File(d, "proc_broken").apply { mkdirs() }
        FileLogCore.procRoot = broken
        assertEquals(FileLogCore.Liveness.UNKNOWN, FileLogCore.livenessOf("900-$boot-4242", listOf(spill)))
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
