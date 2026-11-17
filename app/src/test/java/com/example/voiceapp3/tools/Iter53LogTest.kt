package com.example.voiceapp3.tools

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter53 §10 — журнал: три состояния живости, атомарное резервирование имени work-файла,
 * восстановление «повисшего» `.new` по всем точкам падения.
 *
 * Чего тесты НЕ доказывают: поведение f2fs/ext4 ГУ при одновременной записи из двух процессов
 * (в JVM доступны только потоки).
 */
class Iter53LogTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "iter53log_" + System.nanoTime())
    private val oldClock = FileLogCore.clock
    private val oldProc = FileLogCore.procRoot

    private fun setup(): File {
        dir.mkdirs()
        FileLogCore.clock = { 10_000_000_000_000L }
        FileLogCore.procRoot = File(dir, "proc").apply { mkdirs() }
        // Доступность `/proc` доказывается чтением self/stat.
        File(FileLogCore.procRoot, "self").mkdirs()
        File(FileLogCore.procRoot, "self/stat")
            .writeText("1 (t) " + (3..21).joinToString(" ") + " 1\n")
        return dir
    }

    @After
    fun tearDown() {
        FileLogCore.clock = oldClock
        FileLogCore.procRoot = oldProc
        dir.deleteRecursively()
    }

    private fun stat(pid: String, start: String) {
        val d = File(FileLogCore.procRoot, pid).apply { mkdirs() }
        File(d, "stat").writeText("$pid (p) " + (3..21).joinToString(" ") + " $start\n")
    }

    // ───────────────── §10.1: три состояния физиономии живости ─────────────────

    @Test
    fun unreadableProcIsUnknownNotAliveNotDead() {
        val d = setup()
        val spill = File(d, "app.log.424242.spill").apply { writeText("x\n") }
        // /proc «есть», но self/stat не читается ⇒ доступность НЕ доказана.
        FileLogCore.procRoot = File(d, "proc_closed").apply { mkdirs() }
        assertEquals(FileLogCore.Liveness.UNKNOWN,
            FileLogCore.livenessOf("424242", listOf(spill)))
        FileLogCore.procRoot = File(d, "proc").apply { mkdirs() }
        File(FileLogCore.procRoot, "self").mkdirs()
        if (!File(FileLogCore.procRoot, "self/stat").exists())
            File(FileLogCore.procRoot, "self/stat").writeText("1 (t) " + (3..21).joinToString(" ") + " 1\n")
        // При доступном /proc отсутствие процесса — доказанная смерть.
        assertEquals(FileLogCore.Liveness.DEAD_OR_DIFFERENT_PROCESS,
            FileLogCore.livenessOf("424242", listOf(spill)))
    }

    @Test
    fun identityProvesBothDirections() {
        val d = setup()
        val spill = File(d, "app.log.700.spill").apply { writeText("x\n") }
        val boot = FileLogCore.readBootId()?.take(8) ?: return
        stat("700", "31337")
        assertEquals("совпали boot+starttime — это тот же процесс",
            FileLogCore.Liveness.LIVE_SAME_PROCESS, FileLogCore.livenessOf("700-$boot-31337", listOf(spill)))
        assertEquals("другой starttime — другой процесс",
            FileLogCore.Liveness.DEAD_OR_DIFFERENT_PROCESS,
            FileLogCore.livenessOf("700-$boot-31338", listOf(spill)))
        assertEquals("другая загрузка — процесса уже нет",
            FileLogCore.Liveness.DEAD_OR_DIFFERENT_PROCESS,
            FileLogCore.livenessOf("700-ffffffff-31337", listOf(spill)))
    }

    @Test
    fun unknownOwnerIsDeferredToIndexNotLost() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("live\n") }
        // Тег без pid (`p<hash>`): принадлежность доказать нельзя.
        val spill = File(d, "app.log.p1a2b3c.spill").apply { writeText("unknown_owner\n") }

        val n = FileLogCore.drainSpill(d, "app.log")

        assertEquals("неизвестный владелец — не забираем", 0, n)
        assertTrue("файл остался на диске", spill.exists())
        val index = FileLogCore.orphanIndexFile(d, "app.log")
        assertTrue("факт откладывания записан: ${if (index.exists()) index.readText() else "нет индекса"}",
            index.isFile && index.readText().contains("liveness=unknown"))
        assertFalse("в журнал не попал", active.readText().contains("unknown_owner"))
    }

    @Test
    fun deferredFileIsRecoveredAsSoonAsDeathIsProven() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("live\n") }
        // Старый формат тега (`<pid>` без identity): без /proc доказать смерть НЕЛЬЗЯ — но и
        // терять файл нельзя, он ждёт следующего прохода.
        val f = File(d, "app.log.808.spill").apply { writeText("later_ok\n") }
        File(d, "app.log.p0bad0.spill").writeText("no_identity\n")
        FileLogCore.procRoot = File(d, "proc_closed").apply { mkdirs() }
        assertEquals("пока живость не доказана — ничего не забираем", 0, FileLogCore.drainSpill(d, "app.log"))
        assertTrue(f.exists())

        // /proc снова доступен, процесса 808 нет ⇒ смерть доказана ⇒ забираем.
        FileLogCore.procRoot = File(d, "proc").apply { mkdirs() }
        File(FileLogCore.procRoot, "self").mkdirs()
        File(FileLogCore.procRoot, "self/stat")
            .writeText("1 (t) " + (3..21).joinToString(" ") + " 1\n")
        val n = FileLogCore.drainSpill(d, "app.log")

        assertEquals("отложенный файл забран без потери", 1, n)
        assertTrue(active.readText().contains("later_ok"))
        assertFalse("забранный файл снят", f.exists())
        // Файл без идентичности остаётся отложенным — он не теряется.
        assertTrue(File(d, "app.log.p0bad0.spill").exists())
    }

    // ───────────────── §10.2: имя work-файла и лок ─────────────────

    @Test
    fun workNamesAreReservedAtomicallyAndNeverOverwrite() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("live\n") }
        // Лежащий work-файл от прошлой инкарнации процесса — его строки не должны исчезнуть.
        val stale = File(d, "app.log.500-aaaa-1.spill.drain.500-aaaa-1-1")
        stale.writeText("older_lines\n")
        // Свой spill с тем же «вычисленным» именем (pid-1) — раньше renameTo затёр бы stale.
        val own = FileLogCore.spillFor(active).apply { writeText("new_lines\n") }

        FileLogCore.drainSpill(d, "app.log")

        val text = active.readText()
        assertTrue("старые строки сохранены: $text", text.contains("older_lines"))
        assertTrue("свои строки тоже в журнале: $text", text.contains("new_lines"))
        assertFalse("свой spill снят", own.exists())
    }

    @Test
    fun reservedWorkFileIsCreatedBeforeRename() {
        val d = setup()
        val spill = File(d, "app.log.999-aaaa-1.spill").apply { writeText("x\n") }
        // Резервирование атомарно: имя занимается созданием файла, а не вычислением.
        val a = FileLogCore.reserveWorkForTest(spill)
        val b = FileLogCore.reserveWorkForTest(spill)
        assertNotEquals("имена не совпадают", a.name, b.name)
        assertTrue("первое имя зарезервировано на диске", a.exists())
        assertTrue("второе имя зарезервировано на диске", b.exists())
        // Резерв не перезаписывает содержимое уже занятого имени.
        a.writeText("keep_me\n")
        val c = FileLogCore.reserveWorkForTest(spill)
        assertNotEquals("занятое имя не выдаётся повторно", a.name, c.name)
        assertEquals("содержимое занятого имени цело", "keep_me\n", a.readText())
    }

    @Test
    fun lockFileIsStableAndNeverDeleted() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("") }
        FileLogCore.append(d, "app.log", "one\n", maxBytes = 1_000_000L)
        val lock = File(d, "app.log.lock")
        assertTrue("лок-файл существует", lock.isFile)
        FileLogCore.append(d, "app.log", "two\n", maxBytes = 1_000_000L)
        assertTrue("лок-файл не удалён под локом (стабильный замок)", lock.isFile)
        assertEquals("строки записаны", true, active.readText().contains("two"))
    }

    // ───────────────── §10.2(3): точки падения `.new/.1` ─────────────────

    @Test
    fun newPendingWithLiveActiveIsMergedWithoutDuplicates() {
        val d = setup()
        val name = "app.log"
        val active = File(d, name).apply { writeText("a\nb\n") }
        // Падение после записи `.new`, но до переименования активного: активный жив.
        File(d, "$name.new").writeText("c\nd\n")

        // Восстановление живёт в контексте восстановления журнала (drain), а не в горячем пути.
        FileLogCore.drainSpill(d, name)
        val note = FileLogCore.lastDrainNote()

        val text = active.readText()
        assertTrue("новые строки попали в журнал: $text", text.contains("c") && text.contains("d"))
        assertFalse(".new снят после переноса", File(d, "$name.new").exists())
        assertTrue("заметка о переносе: $note", note.contains("new_merged="))
    }

    @Test
    fun boundaryMatchKeepsBothRecordsIter63() {
        val d = setup()
        val name = "app.log"
        val active = File(d, name).apply { writeText("a\nb\n") }
        // iter63 §P0-1: контракт ВОЗВРАЩЁН к iter55 и усилен. iter62 выбрасывал запись `.new` по
        // совпадению ТЕКСТА — это потеря настоящего события (аудит iter62). Теперь каждая запись
        // несёт `rid` события, поэтому здесь (журнал прежней версии, без `rid`) сохраняется всё, а
        // дедуп работает только по идентификатору (см. `Iter63LogIdentityTest` L1/L2).
        File(d, "$name.new").writeText("b\ne\n")
        FileLogCore.drainSpill(d, name)
        val lines = active.readLines().filter { it.isNotEmpty() }
        assertEquals("ни одна запись не потеряна: $lines", listOf("a", "b", "b", "e"), lines)
    }

    @Test
    fun missingActivePromotesNew() {
        val d = setup()
        val name = "app.log"
        // Падение между move(active→.1) и повышением .new: активного нет.
        val active = File(d, name)
        File(d, "$name.new").writeText("only_part\n")
        FileLogCore.append(d, name, "next\n", maxBytes = 1_000_000L)
        val text = File(d, name).readText()
        assertTrue(".new повышен, прежняя часть на месте: $text", text.contains("only_part"))
        assertTrue("новая строка тоже в журнале: $text", text.contains("next"))
    }

    @Test
    fun previousPartIsNotLostWhenRotationIsInterrupted() {
        val d = setup()
        val name = "app.log"
        File(d, name).writeText("OLD\n")
        File(d, "$name.1").writeText("EVEN_OLDER\n")
        File(d, "$name.new").writeText("NEW_PART\n")
        FileLogCore.append(d, name, "tail\n", maxBytes = 10L)
        assertTrue("прежняя часть `.1` не удалена до переименования: ${File(d, "$name.1").readText()}",
            File(d, "$name.1").isFile)
        assertTrue("журнал существует", File(d, name).isFile)
    }
}
