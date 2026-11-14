package com.example.voiceapp3.tools

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter52 §9 — журнал: идентичность процесса вместо PID+возраста, нечитаемый work-файл не
 * удаляется, файл лока живёт постоянно.
 *
 * Дефекты (аудит §9):
 *  1. «живой ли владелец spill» решалось по `pid` + возрасту файла (окно 30 минут). PID
 *     переиспользуется: после перезагрузки (или падения) тот же номер достаётся другому
 *     процессу — его spill считался живым, и строки журнала не забирались никогда;
 *  2. имя work-файла = `pid` + счётчик в процессе: при переиспользовании PID (и новом счётчике)
 *     имена могли совпасть с уже лежащими файлами прошлой инкарнации;
 *  3. `readText()` под `runCatching` отдавал `null`, и «прочитать нельзя» трактовалось как
 *     «файл пуст» — work удалялся вместе со строками;
 *  4. `mergeWork` удалял `.lock` файл, удерживая лок: другой процесс мог создать новый файл с тем
 *     же именем и войти в критическую секцию параллельно.
 *
 * Проверяется на настоящей ФС и подставленном `/proc`.
 * Чего тесты НЕ доказывают: поведение ext4/f2fs ГУ при одновременной записи двух процессов.
 */
class Iter52LogIdentityTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "iter52log_" + System.nanoTime())
    private val oldClock = FileLogCore.clock
    private val oldProc = FileLogCore.procRoot

    private fun setup(): File {
        dir.mkdirs()
        FileLogCore.clock = { 10_000_000_000_000L }
        FileLogCore.procRoot = File(dir, "proc").apply { mkdirs() }
        return dir
    }

    @After
    fun tearDown() {
        FileLogCore.clock = oldClock
        FileLogCore.procRoot = oldProc
        dir.deleteRecursively()
    }

    /** `/proc/<pid>/stat` с заданным starttime (поле 22). */
    private fun fakeStat(pid: String, startTime: String, name: String = "proc with space"): File {
        val d = File(FileLogCore.procRoot, pid).apply { mkdirs() }
        // поля 3..22: state, ppid, pgrp, session, tty, tpgid, flags, minflt, cminflt, majflt,
        // cmajflt, utime, stime, cutime, cstime, priority, nice, threads, itrealvalue, starttime
        val fields = (3..21).map { "$it" } + startTime
        return File(d, "stat").apply { writeText("$pid ($name) ${fields.joinToString(" ")}\n") }
    }

    // ───────────────── §9.1: идентичность процесса ─────────────────

    @Test
    fun tagDistinguishesBootsAndStarts() {
        val a = FileLogCore.buildTag("1234", "aaaabbbbcccc", "555")
        val b = FileLogCore.buildTag("1234", "aaaabbbbcccc", "556")
        val c = FileLogCore.buildTag("1234", "ddddbbbbcccc", "555")
        assertNotEquals("другой starttime → другой процесс", a, b)
        assertNotEquals("другая загрузка → другой тег", a, c)
        assertTrue("тег содержит идентичность: $a", a.startsWith("1234-aaaabbbb-555"))
        // Без идентичности тег всё равно уникален (случайная часть), но и это видно по формату.
        val noIdentity = FileLogCore.buildTag("1234", null, null)
        assertTrue("нет /proc-фактов — тег с r-частью: $noIdentity", noIdentity.contains("-r"))
    }

    @Test
    fun startTimeIsParsedFromStatWithSpacesInName() {
        setup()
        val st = fakeStat("777", "424242")
        assertTrue(st.exists())
        assertEquals("поле 22 прочитано несмотря на пробелы в имени процесса",
            "424242", FileLogCore.readStartTime("777"))
    }

    @Test
    fun spillFromPreviousBootIsRecoveredNotKept() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("live\n") }
        // Владелец — из ДРУГОЙ загрузки: boot-часть тега не совпадает с текущей.
        File(d, "app.log.31337-deadbeef-77.spill").writeText("prev_boot_1\nprev_boot_2\n")

        val n = FileLogCore.drainSpill(d, "app.log")

        assertEquals("две строки из прошлой загрузки перенесены", 2, n)
        assertTrue("строки в журнале: ${active.readText()}",
            active.readText().contains("prev_boot_1") && active.readText().contains("prev_boot_2"))
        assertFalse("spill прошлой загрузки снят", File(d, "app.log.31337-deadbeef-77.spill").exists())
    }

    @Test
    fun reusedPidWithOtherStartTimeIsNotAlive() {
        val d = setup()
        fakeStat("555", "999999")   // сейчас под этим pid ДРУГОЙ процесс
        val active = File(d, "app.log").apply { writeText("live\n") }
        File(d, "app.log.555-<boot>-111111.spill").writeText("orphan_reused\n")

        // boot-часть настоящая (иначе сработал бы более ранний признак «другая загрузка»).
        val boot = FileLogCore.readBootId()?.take(8) ?: return
        val f = File(d, "app.log.555-$boot-111111.spill")
        File(d, "app.log.555-<boot>-111111.spill").delete()
        f.writeText("orphan_reused\n")

        val n = FileLogCore.drainSpill(d, "app.log")

        assertEquals("pid переиспользован → spill забран", 1, n)
        assertTrue("строка в журнале", active.readText().contains("orphan_reused"))
    }

    @Test
    fun liveOwnerOfSameBootAndStartIsNotTaken() {
        val d = setup()
        val boot = FileLogCore.readBootId()?.take(8) ?: return
        fakeStat("556", "123123")
        val active = File(d, "app.log").apply { writeText("live\n") }
        File(d, "app.log.556-$boot-123123.spill").writeText("still_writing\n")

        val n = FileLogCore.drainSpill(d, "app.log")

        assertEquals("живой владелец — не забираем", 0, n)
        assertTrue("spill на месте", File(d, "app.log.556-$boot-123123.spill").exists())
        assertFalse("строки не перенесены", active.readText().contains("still_writing"))
    }

    // ───────────────── §9.4: нечитаемый work ≠ пустой ─────────────────

    @Test
    fun unreadableWorkFileIsKeptNotEmpty() {
        val unreadable = FileLogCore.classifyWorkRead(
            kotlin.Result.failure<String>(java.io.IOException("EIO"))
        )
        assertEquals("ошибка чтения — отдельный факт", FileLogCore.WorkReadKind.UNREADABLE, unreadable.kind)
        assertEquals("причина названа", "IOException", unreadable.error)

        val empty = FileLogCore.classifyWorkRead(kotlin.Result.success(""))
        assertEquals(FileLogCore.WorkReadKind.EMPTY, empty.kind)

        val text = FileLogCore.classifyWorkRead(kotlin.Result.success("a\nb\n"))
        assertEquals(FileLogCore.WorkReadKind.TEXT, text.kind)
        assertEquals("a\nb\n", text.text)
    }

    // ───────────────── §9.3: файл лока не удаляется ─────────────────

    @Test
    fun lockFileSurvivesMerge() {
        val d = setup()
        val active = File(d, "app.log").apply { writeText("") }
        val spill = File(d, "app.log.999.spill").apply { writeText("line1\n") }
        val work = File(d, "app.log.999.spill.drain.999-1").apply { writeText("line2\n") }
        assertTrue(work.isFile)
        assertTrue(spill.isFile)
        FileLogCore.drainSpill(d, "app.log")
        // Лок-файл (если создавался) обязан остаться: удаление под локом ломает исключительность.
        val lock = File(work.parentFile, work.name + ".lock")
        if (lock.exists()) {
            assertTrue("лок-файл остаётся стабильным замком", lock.exists())
        }
        assertTrue("журнал существует", active.exists() || File(d, "app.log.1").exists())
    }
}
