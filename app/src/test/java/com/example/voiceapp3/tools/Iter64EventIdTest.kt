package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * iter64 — ИДЕНТИЧНОСТЬ СОБЫТИЯ ЖУРНАЛА: уникальна между процессами и запусками.
 *
 * Дефект iter63 (причина, по которой журнал терял записи): `rid` был `AtomicLong(0)` в памяти
 * процесса (`FileLogCore.kt:36`,`:64`). Поэтому:
 *  • второй процесс того же APK (JIT и TTL живут в нём) начинал с `rid=1`, и его запись при слиянии
 *    `.new` выбрасывалась как «уже существующая»;
 *  • после `ctl.restart zygote` / рестарта процесса первая же запись нового запуска терялась.
 *
 * Здесь проверяется production-код (`FileLogCore.stamp/appendStamped/recoverPendingNew/drainSpill`,
 * `LogRecordId`) на РЕАЛЬНЫХ файлах. «Второй процесс» моделируется ДВУМЯ способами:
 *  1) отдельным экземпляром состояния писателя ([EventKeyGen.State]) с другой меткой —
 *     дешёвая и точная проверка формул ключа;
 *  2) ДВУМЯ НАСТОЯЩИМИ java-процессами ([Iter64TwoProcessMain]) — то, чего не было в iter63.
 *
 * R1, R2, R7 на коде iter63 падают (`expected:<3> but was:<2>` и т.п.).
 */
class Iter64EventIdTest {

    private fun newDir(): File = File.createTempFile("log64", "").let { it.delete(); it.mkdirs(); it }

    private fun lines(f: File): List<String> =
        if (f.isFile) f.readLines().filter { it.isNotEmpty() } else emptyList()

    private fun bodies(f: File): List<String> = lines(f).map { LogRecordId.parse(it).body }

    /** Все строки журнала [name] в каталоге: активный файл + `.1` + `.new` + spill/work-файлы. */
    private fun allJournalLines(dir: File, name: String): List<String> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(name) && !it.name.endsWith(".lock") }
            .sortedBy { it.name }
            .flatMap { lines(it) }

    /** «Другой процесс»: своё состояние писателя, ничего общего с текущим. */
    private fun otherProcess(tag: String = "bbbbbbbb") = EventKeyGen.State(tag)

    // ───────────── R1: два процесса пишут в один журнал — теряться нечему ─────────────

    @Test
    fun r1_twoWritersOneJournalKeepAllThreeRecords() {
        val dir = newDir()
        val name = "app.log"
        val b = otherProcess()

        // Процесс A (этот) записал две строки.
        FileLogCore.appendStamped(dir, name, "A1 APP_STARTED")
        FileLogCore.appendStamped(dir, name, "A2 TOGGLE_HOLD key=perfJit")

        // Процесс B пишет свою первую строку — свой `rid`, своя метка писателя.
        val fromB = FileLogCore.stamp("B1 TTL_APPLY_RESULT want=true rc=0", writer = b)
        assertNotEquals(
            "ключ события B совпал с ключом события A — это и есть потеря iter63",
            LogRecordId.keyOf(lines(File(dir, name)).first()),
            LogRecordId.keyOf(fromB.trimEnd('\n'))
        )
        File(dir, "$name.new").writeText(fromB)

        val note = FileLogCore.recoverPendingNew(dir, name)
        val out = bodies(File(dir, name))
        assertEquals("запись второго процесса потеряна: $note / $out", 3, out.size)
        assertTrue("A1 на месте: $out", out.any { it.contains("A1 APP_STARTED") })
        assertTrue("A2 на месте: $out", out.any { it.contains("A2 TOGGLE_HOLD") })
        assertTrue("B1 (другой процесс) сохранена: $out", out.any { it.contains("B1 TTL_APPLY_RESULT") })
        assertEquals("перенесена ровно одна новая запись: $note", 1,
            Regex("new_merged=(\\d+)").find(note)?.groupValues?.get(1)?.toInt() ?: -1)
    }

    // ───────────── R2: рестарт процесса — первая запись нового запуска не теряется ─────────────

    @Test
    fun r2_firstRecordAfterProcessRestartIsKept() {
        val dir = newDir()
        val name = "voiceapp3.log"
        // Журнал прежнего запуска этого же APK: три записи, выданные «старым» писателем.
        val old = otherProcess("0ldpr0c1")
        val oldLines = (1..3).joinToString("") { FileLogCore.stamp("OLD$it event", writer = old) }
        File(dir, name).writeText(oldLines)

        // Процесс перезапущен (ctl.restart zygote / падение): новый писатель, свой счётчик времени.
        val fresh = EventKeyGen.State("n3wpr0c2")
        val first = FileLogCore.stamp("NEW1 APP_STARTED after_restart", writer = fresh)
        File(dir, "$name.new").writeText(first)

        val note = FileLogCore.recoverPendingNew(dir, name)
        val out = bodies(File(dir, name))
        assertEquals("первая запись после рестарта потеряна: $note / $out", 4, out.size)
        assertTrue("строка нового запуска в журнале: $out", out.any { it.contains("NEW1 APP_STARTED") })
    }

    // ───────────── R3: два потока одного процесса ─────────────

    @Test
    fun r3_twoThreadsOfOneProcessWriteTwoHundredDistinctRecords() {
        val dir = newDir()
        val name = "threads.log"
        val n = 100
        val start = CountDownLatch(1)
        val lost = AtomicInteger(0)
        val threads = (1..2).map { t ->
            Thread {
                start.await()
                for (i in 1..n) {
                    val r = FileLogCore.appendStamped(dir, name, "T$t#$i payload")
                    // `written=false` при `jvm_lock_busy:spilled` — это НЕ потеря: строка ушла в
                    // spill-файл процесса и будет влита. Потеря — только когда не удалось ни то,
                    // ни другое (`spill_failed`).
                    if (!r.written && !r.reason.endsWith(":spilled")) lost.incrementAndGet()
                }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        threads.forEach { it.join(TimeUnit.MINUTES.toMillis(2)) }

        // Занятый JVM-лок уводит строку в spill-файл этого процесса — это ШТАТНЫЙ путь
        // (строка сохранена), поэтому перед подсчётом выполняем обычное восстановление.
        FileLogCore.drainSpill(dir, name)
        val all = allJournalLines(dir, name)
        assertEquals("поток потерял строки", 2 * n, all.size)
        val keys = all.map { LogRecordId.keyOf(it) }
        assertEquals("ключи событий должны быть уникальны", keys.size, keys.toSet().size)
        assertEquals("строки, не сохранённые ни в журнал, ни в spill", 0, lost.get())
    }

    // ───────────── R4: повтор после отказа fsync — одно событие ─────────────

    @Test
    fun r4_retryAfterFsyncFailureIsTheSameEvent() {
        val dir = newDir()
        val name = "retry.log"
        val first = FileLogCore.stamp("LOCKSOUND_RESTORE_RESULT result=ok")
        File(dir, name).writeText(first)

        // Повтор ТОЙ ЖЕ строки: ключ события тот же, растёт только `try`.
        val retry = FileLogCore.stamp(first)
        assertEquals("ключ события изменился у повтора",
            LogRecordId.keyOf(first.trimEnd('\n')), LogRecordId.keyOf(retry.trimEnd('\n')))
        assertEquals("номер попытки не вырос",
            LogRecordId.parse(first.trimEnd('\n')).tryNo + 1, LogRecordId.parse(retry.trimEnd('\n')).tryNo)

        File(dir, "$name.new").writeText(retry)
        val note = FileLogCore.recoverPendingNew(dir, name)
        assertEquals("одно событие должно остаться одной строкой: $note", 1, bodies(File(dir, name)).size)
        assertTrue("пропуск повтора назван: $note", note.contains("skipped_as_duplicate=1"))
    }

    // ───────────── R5: журнал прежней версии (без суффиксов) — ничего не теряем ─────────────

    @Test
    fun r5_legacyJournalLosesNothing() {
        val dir = newDir()
        val name = "legacy.log"
        File(dir, name).writeText("old one\nold two\n")
        File(dir, "$name.new").writeText("old two\nold three\n")
        FileLogCore.recoverPendingNew(dir, name)
        val out = bodies(File(dir, name))
        assertTrue("потеряна строка прежнего журнала: $out", out.contains("old three"))
        assertEquals("правило iter55: строки прежнего журнала не выбрасываются", 4, out.size)
    }

    // ───────────── R6: журнал iter63 (rid без писателя) не путается с новым ─────────────

    @Test
    fun r6_iter63RecordsWithoutWriterAreNotTreatedAsTheSameEvent() {
        val dir = newDir()
        val name = "migrated.log"
        // Строка, выданная iter63: `rid=1` без писателя. Именно она в iter63 «съедала» запись B.
        File(dir, name).writeText("APP_STARTED [r=1/1000/1]\n")
        val b = otherProcess()
        // Новый писатель тоже может получить rid=1-подобное значение — ключ должен отличаться.
        val fresh = FileLogCore.stamp("B1 TTL_APPLY_RESULT", writer = b)
        File(dir, "$name.new").writeText(fresh)

        val note = FileLogCore.recoverPendingNew(dir, name)
        assertEquals("запись потеряна при сравнении с журналом iter63: $note", 2, bodies(File(dir, name)).size)
    }

    // ───────────── R7: два процесса пишут ОДИНАКОВЫЙ текст одновременно ─────────────

    @Test
    fun r7_twoWritersSameTextBothRecordsAreKept() {
        val dir = newDir()
        val name = "same.log"
        val a = EventKeyGen.State("aaaaaaaa")
        val b = otherProcess("bbbbbbbb")

        // Оба процесса пишут одну и ту же строку (одна миллисекунда — худший случай).
        val ts = 1_700_000_000_000L
        val fromA = FileLogCore.stamp("TTL_TRY action=skip", writer = a, nowMs = { ts })
        val fromB = FileLogCore.stamp("TTL_TRY action=skip", writer = b, nowMs = { ts })
        assertNotEquals("два писателя в одну мс получили одинаковый rid",
            LogRecordId.parse(fromA.trimEnd('\n')).rid, LogRecordId.parse(fromB.trimEnd('\n')).rid)

        File(dir, name).writeText(fromA)
        File(dir, "$name.new").writeText(fromB)
        val note = FileLogCore.recoverPendingNew(dir, name)
        assertEquals("одинаковый текст двух событий: обе строки обязаны остаться: $note",
            listOf("TTL_TRY action=skip", "TTL_TRY action=skip"), bodies(File(dir, name)))
    }

    // ───────────── R8: перевод часов назад ─────────────

    @Test
    fun r8_clockMovedBackwardsDoesNotRepeatAKey() {
        val dir = newDir()
        val name = "clock.log"
        val w = otherProcess("c10ck001")
        val later = 1_700_000_100_000L
        val earlier = 1_700_000_000_000L
        val first = FileLogCore.stamp("event one", writer = w, nowMs = { later })
        val second = FileLogCore.stamp("event two", writer = w, nowMs = { earlier })

        File(dir, name).writeText(first)
        File(dir, "$name.new").writeText(second)
        val note = FileLogCore.recoverPendingNew(dir, name)
        assertEquals("после перевода часов назад событие потеряно: $note", 2, bodies(File(dir, name)).size)
        assertTrue("ключи различны",
            LogRecordId.keyOf(first.trimEnd('\n')) != LogRecordId.keyOf(second.trimEnd('\n')))
    }

    // ───────────── ключ уникален по построению ─────────────

    @Test
    fun keyContainsTimeAndWriterSoDifferentProcessesNeverCollide() {
        val ts = 1_700_000_000_123L
        val a = EventKeyGen.State("aaaaaaaa").next(ts)
        val b = EventKeyGen.State("bbbbbbbb").next(ts)
        assertNotEquals("разные писатели в одну мс", a, b)
        // Внутри одного писателя — монотонность даже в одну миллисекунду.
        val w = EventKeyGen.State("cccccccc")
        val s1 = w.next(ts); val s2 = w.next(ts); val s3 = w.next(ts - 5_000)
        assertTrue("нет монотонности: $s1 / $s2", s2 > s1)
        assertTrue("перевод часов назад нарушил монотонность: $s2 / $s3", s3 > s2)
    }

    @Test
    fun writerTagIsStableWithinProcessAndDiffersBetweenProcesses() {
        val one = EventKeyGen.writerTag("1234", "boot-aaaa", "567", "r1")
        val same = EventKeyGen.writerTag("1234", "boot-aaaa", "567", "r1")
        val otherPid = EventKeyGen.writerTag("1235", "boot-aaaa", "567", "r2")
        val otherStart = EventKeyGen.writerTag("1234", "boot-aaaa", "568", "r3")
        val otherBoot = EventKeyGen.writerTag("1234", "boot-bbbb", "567", "r4")
        assertEquals("метка писателя нестабильна внутри процесса", one, same)
        assertNotEquals(one, otherPid)
        assertNotEquals(one, otherStart)
        assertNotEquals(one, otherBoot)
        assertEquals("метка должна быть короткой (её читает человек)", 8, one.length)
        assertTrue("метка должна быть hex", one.all { it in "0123456789abcdef" })
    }

    @Test
    fun suffixStaysReadable() {
        val line = FileLogCore.stamp("TTL_APPLY_RESULT want=true rc=0").trimEnd('\n')
        assertTrue("тело строки испорчено: $line", line.startsWith("TTL_APPLY_RESULT want=true rc=0 [r="))
        assertTrue("в суффиксе нет писателя: $line", Regex("\\[r=\\d+/\\d+/\\d+/w=[0-9a-f]{8}]$").containsMatchIn(line))
        // Старый журнал iter63 читается тем же парсером (миграция без потерь).
        val oldStyle = LogRecordId.parse("APP_STARTED [r=1/1000/1]")
        assertEquals("APP_STARTED", oldStyle.body)
        assertEquals(1L, oldStyle.rid)
        assertEquals("у строк iter63 писателя нет", "", oldStyle.writer)
    }

    // ───────────── ГЛАВНОЕ: два РЕАЛЬНЫХ процесса, один журнал ─────────────

    @Test
    fun twoRealProcessesKeepEveryRecord() {
        val dir = newDir()
        val name = "two.log"
        val perProcess = 30
        val cp = System.getProperty("java.class.path")
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val runs = listOf("PA" to perProcess, "PB" to perProcess).map { (prefix, count) ->
            val pb = ProcessBuilder(
                java, "-cp", cp,
                "com.example.voiceapp3.tools.Iter64TwoProcessMain",
                dir.absolutePath, name, prefix, count.toString()
            ).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS)
            Triple(prefix, p.exitValue(), out)
        }

        runs.forEach { (prefix, rc, out) ->
            assertEquals("процесс $prefix завершился с ошибкой: $out", 0, rc)
            assertTrue("процесс $prefix не сообщил писателя: $out", out.contains("writer="))
        }
        // Процессы разные — метки писателя обязаны различаться.
        val tags = runs.map { Regex("writer=([0-9a-f]+)").find(it.third)?.groupValues?.get(1) }
        assertEquals("метка писателя не прочитана: ${runs.map { it.third }}", 2, tags.filterNotNull().toSet().size)

        // Каждый процесс писал свои строки; журнал мог уйти в `.1`/`.new` из-за локов — читаем всё.
        val all = lines(File(dir, name)) + lines(File(dir, "$name.1")) + lines(File(dir, "$name.new"))
        val bodiesAll = all.map { LogRecordId.parse(it).body }
        (1..perProcess).forEach { i ->
            assertTrue("потеряна запись PA $i: ${bodiesAll.size} строк", bodiesAll.contains("PA $i"))
            assertTrue("потеряна запись PB $i: ${bodiesAll.size} строк", bodiesAll.contains("PB $i"))
        }
        val keys = all.map { LogRecordId.keyOf(it) }
        assertEquals("дубли ключей между процессами: ${keys.size - keys.toSet().size}", keys.size, keys.toSet().size)
        assertFalse("в журнале не должно быть пустых строк: $all", all.isEmpty())
    }

    // ───────────── R9: spill второго процесса вливается ровно один раз ─────────────

    @Test
    fun spilledRecordsOfAnotherProcessAreMergedExactlyOnce() {
        val dir = newDir()
        val name = "spill.log"
        FileLogCore.appendStamped(dir, name, "A1 own line")
        // «Spill другого процесса»: файл с его строками (с ключами его писателя).
        val b = otherProcess("5b111111")
        // Метка мёртвого процесса в формате, который понимает [FileLogCore.orphanFilesOf]
        // (`<pid>`), и pid, которого заведомо нет: файл считается осиротевшим и забирается.
        val deadPid = (900000..999999).first { !File(FileLogCore.procRoot, it.toString()).exists() }
        val spill = File(dir, "$name.$deadPid.spill")
        spill.writeText(
            FileLogCore.stamp("B1 spilled line", writer = b) +
                FileLogCore.stamp("B2 spilled line", writer = b)
        )
        // Два прохода восстановления подряд: второй не должен задвоить перенесённое.
        FileLogCore.drainSpill(dir, name)
        FileLogCore.drainSpill(dir, name)
        val out = bodies(File(dir, name))
        assertEquals("строки spill потеряны или задвоены (${FileLogCore.lastDrainNote()}): $out",
            listOf("A1 own line", "B1 spilled line", "B2 spilled line"), out)
    }
}
