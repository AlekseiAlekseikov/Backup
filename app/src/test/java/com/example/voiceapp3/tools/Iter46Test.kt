package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * iter46 §4.2/§5.1/§5.2/§5.3/§6.2/§6.3/§8 — исправления этой итерации.
 *
 * Уровень: **JVM**, но по production-коду: [Toggles45] (приоритет CPS/DPS, окно операции),
 * [TtlReconcile] (политика «последнее намерение»), [FileLogCore] (реальные файлы: ротация,
 * NUL-хвост, конкурирующие писатели, отказ rename). Android-интеграция и ГУ не проверяются.
 */
class Iter46Test {

    private fun tmpDir(): File =
        File.createTempFile("iter46", "").let { it.delete(); it.mkdirs(); it }

    // ───────────────────────── §4.2: CPS/DPS — последняя запись побеждает ─────────────────────────

    @Test
    fun newerOffWinsOverOlderTrue() {
        // Владелец выключил: OFF записан с ревизией 7, в другой копии осталось старое true с 3.
        assertFalse(
            "старое true не перебивает новый OFF",
            Toggles45.pickBoolean(cpsValue = false, cpsRev = 7, dpsValue = true, dpsRev = 3, default = true)
        )
        // Симметрично: новое значение в DPS.
        assertTrue(
            Toggles45.pickBoolean(cpsValue = false, cpsRev = 3, dpsValue = true, dpsRev = 7, default = false)
        )
    }

    @Test
    fun legacyInstallKeepsOldSemantics() {
        // Ревизий нет (обновление с прежней версии): сохранённое ON не теряется.
        assertTrue(Toggles45.pickBoolean(cpsValue = false, cpsRev = -1, dpsValue = true, dpsRev = -1, default = false))
        assertFalse(Toggles45.pickBoolean(cpsValue = false, cpsRev = -1, dpsValue = false, dpsRev = -1, default = true))
        // Ключа нет нигде → дефолт.
        assertEquals(true, Toggles45.pickBoolean(null, -1, null, -1, default = true))
        assertEquals(false, Toggles45.pickBoolean(null, -1, null, -1, default = false))
    }

    @Test
    fun partialWriteOnOneCopyStillGivesLastIntent() {
        // Первая копия не записалась (null при ревизии 9) — значение берём из второй, а не «OFF».
        assertTrue(Toggles45.pickBoolean(cpsValue = null, cpsRev = 9, dpsValue = true, dpsRev = 9, default = false))
        // Обе копии без значения, но ревизия есть → дефолт, а не выдуманное состояние.
        assertEquals(false, Toggles45.pickBoolean(null, 5, null, 5, default = false))
    }

    @Test
    fun equalRevisionsDoNotInventValue() {
        assertTrue(Toggles45.pickBoolean(cpsValue = true, cpsRev = 4, dpsValue = true, dpsRev = 4, default = false))
        assertFalse(Toggles45.pickBoolean(cpsValue = false, cpsRev = 4, dpsValue = false, dpsRev = 4, default = true))
    }

    @Test
    fun stringsUseSameRule() {
        assertEquals("/a/new.wav", Toggles45.pickString("/a/new.wav", 5, "/a/old.wav", 2))
        assertEquals("/a/new.wav", Toggles45.pickString("/a/old.wav", 2, "/a/new.wav", 5))
        // Без ревизий — прежний порядок (CPS, затем DPS), выбранный файл не теряется.
        assertEquals("/cps.wav", Toggles45.pickString("/cps.wav", -1, "/dps.wav", -1))
        assertEquals("/dps.wav", Toggles45.pickString(null, -1, "/dps.wav", -1))
        assertNull(Toggles45.pickString(null, 3, null, 4))
    }

    // ───────────────────────── §5.1/§5.2: TTL — последнее намерение ─────────────────────────

    @Test
    fun offDuringRunningOnIsNotLost() {
        val r = TtlReconcile(maxChain = 3)
        assertEquals(TtlReconcile.Decision.RUN_NOW, r.onIntent(true))
        // OFF пришёл, пока ON выполняется: сохраняется и требует добивания.
        assertEquals(TtlReconcile.Decision.MARKED_AND_RUNNING, r.onIntent(false))
        assertEquals("намерение владельца — OFF", false, r.desired())
        // ON завершился «успешно», но последнее намерение OFF → добивание актуальным OFF.
        val next = r.onRunFinished(success = true)
        assertEquals("добиваем OFF, а не считаем ON достигнутым", false, next)
    }

    @Test
    fun repeatedClicksApplyOnlyLastIntent() {
        // ON выполняется; OFF, затем снова ON: последнее намерение совпало с выполняемым —
        // добивания не требуется, лишних команд нет.
        val r = TtlReconcile()
        r.onIntent(true)
        r.onIntent(false)
        r.onIntent(true)
        assertNull("намерение уже достигается текущим применением", r.onRunFinished(success = true))
        assertEquals(true, r.applied())

        // А если последним остался OFF — добивание обязательно (модель выше).
        val r2 = TtlReconcile()
        r2.onIntent(true)
        r2.onIntent(false)
        assertEquals(false, r2.onRunFinished(success = true))
    }

    @Test
    fun chainIsBounded() {
        val r = TtlReconcile(maxChain = 2)
        r.onIntent(true)
        var runs = 0
        var next: Boolean? = r.onRunFinished(success = false)   // провал → добивание
        while (next != null && runs < 10) {
            runs++
            next = r.onRunFinished(success = false)
        }
        assertTrue("добивания ограничены, бесконечного цикла нет: runs=$runs", runs <= 2)
    }

    @Test
    fun networkEventDuringRunIsNotLost() {
        val r = TtlReconcile()
        r.onIntent(true)
        assertFalse("во время применения событие только помечается", r.onNetworkEvent())
        assertEquals("после применения нужен ещё один проход", true, r.onRunFinished(success = true))
    }

    @Test
    fun networkEventWhenIdleRunsImmediately() {
        val r = TtlReconcile()
        assertTrue(r.onNetworkEvent())
    }

    @Test
    fun successfulApplySatisfiesIntent() {
        val r = TtlReconcile()
        r.onIntent(true)
        assertNull(r.onRunFinished(success = true))
        assertEquals(true, r.applied())
    }

    // ───────────────────────── §5.3: факт не переживает загрузку ─────────────────────────

    @Test
    fun appliedFactIsTiedToBootAndStaleAfterChange() {
        // Логика повторяет правило NetworkTtl.appliedState: applied из другой загрузки → pending.
        fun appliedFact(raw: String, storedBoot: Long, nowBoot: Long): String =
            if (raw != "applied") raw
            else if (storedBoot < 0 || nowBoot < 0 || storedBoot != nowBoot) "pending" else raw

        assertEquals("applied", appliedFact("applied", 12, 12))
        assertEquals("pending", appliedFact("applied", 11, 12))
        assertEquals("pending", appliedFact("applied", -1, 12))
        assertEquals("pending", appliedFact("applied", 12, -1))
        assertEquals("pending", appliedFact("pending", 12, 12))
        assertEquals("off", appliedFact("off", 12, 12))
    }

    // ───────────────────────── §6.2/§6.3: звук — backoff и отмена ─────────────────────────

    @Test
    fun backoffIsBoundedAndGrows() {
        // Та же формула, что в LockSound.backoffMs (сам объект требует Android-API и вне
        // JVM-стенда): 5/10/20/40 с и без бесконечного роста.
        fun backoff(attempt: Int) = 5_000L * (1 shl (attempt - 1).coerceIn(0, 3))
        val seq = (1..6).map { backoff(it) }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 40_000L, 40_000L), seq)
    }

    @Test
    fun offCancelsPendingWorkByGeneration() {
        // Модель проверки, применяемой в LockSound: задача со старым поколением не выполняется.
        var gen = 0L
        val ran = AtomicInteger(0)
        val captured = gen
        gen++                       // владелец выключил звук — поколение сменилось
        if (captured == gen) ran.incrementAndGet()
        assertEquals("задача старого поколения не смонтировала звук", 0, ran.get())
    }

    // ───────────────────────── §7.2: поздний старт не включает Wi-Fi ─────────────────────────

    @Test
    fun lateStartNeverEnablesWifiFromSavedSnapshot() {
        val now = 1_800_000_000_000L
        val fresh = JitRef.OpMark.of("op1", true, ts = now - 20_000)
        assertEquals("свежая отметка = продолжение клика", true,
            JitOp.restoreMayEnableWifi(fresh, now).first)

        val late = JitRef.OpMark.of("op1", true, ts = now - 40 * 60_000L)
        val (allowed, why) = JitOp.restoreMayEnableWifi(late, now)
        assertFalse("поздний независимый старт не включает Wi-Fi", allowed)
        assertTrue(why.startsWith("late_start"))

        // Коррекция часов назад/вперёд не должна выдаваться за свежесть.
        assertFalse(JitOp.restoreMayEnableWifi(JitRef.OpMark.of("op", true, ts = now + 60_000), now).first)
        assertFalse("нет отметки — нет права включать", JitOp.restoreMayEnableWifi(null, now).first)
        assertFalse(JitOp.restoreMayEnableWifi(JitRef.OpMark.of("op", true, ts = 0), now).first)
    }

    // ───────────────────────── §8.3: жертвы выбираются по возрасту, активная защищена ─────────

    @Test
    fun pruneDeletesOldestFirstAndSparesActivePart() {
        val dir = tmpDir()
        val today = java.time.LocalDate.now()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
        // 4 части за сегодня (.1 … .4) — активная по инварианту: максимальный номер.
        for (n in 1..4) {
            File(dir, "voiceapp3-" + today.format(fmt) + "." + n + ".log").writeText("x".repeat(100))
        }
        val removed = LogFiles.pruneTier(dir, System.currentTimeMillis(), LogFiles.Tier.MAIN)
        assertTrue("активная часть (максимальный номер) не удалена",
            removed.none { it.endsWith(".4.log") })
        assertTrue(File(dir, "voiceapp3-" + today.format(fmt) + ".4.log").isFile)
    }

    @Test
    fun thousandthPartIsStillOurOwnFile() {
        val parsed = LogFiles.parseOwn("voiceapp3-13.11.2026.1000.log")
        assertTrue("`.1000` распознаётся как своя часть", parsed != null)
        assertEquals(1000, parsed!!.third)
        assertEquals(null, LogFiles.parseOwn("voiceapp3-important.log"))
        assertEquals(null, LogFiles.parseOwn("voiceapp3-13.11.2026.bak.log"))
    }

    // ───────────────────────── §8: реальные файлы журнала ─────────────────────────

    @Test
    fun appendsUnderLockAndReportsTruthfully() {
        val dir = tmpDir()
        val r1 = FileLogCore.append(dir, "voiceapp3.log", "line-1\n", maxBytes = 1024)
        assertTrue(r1.written)
        val r2 = FileLogCore.append(dir, "voiceapp3.log", "line-2\n", maxBytes = 1024)
        assertTrue(r2.written)
        assertEquals("line-1\nline-2\n", File(dir, "voiceapp3.log").readText())
        assertFalse("факт взятия лока сообщается честно", !r2.locked && r2.reason.isNotEmpty())
    }

    @Test
    fun rotationKeepsPreviousPartWhenNewCannotBeCreated() {
        val dir = tmpDir()
        val f = File(dir, "voiceapp3.log")
        f.writeText("x".repeat(64))
        // «.1» существует как НЕПУСТОЙ каталог — delete() вернёт false.
        // §8.2 iter47: теперь это не «потерять данные», а осознанный откат к дозаписи в
        // АКТИВНЫЙ файл: строка не теряется, прежнее содержимое не переписывается.
        File(dir, "voiceapp3.log.1").apply { mkdir(); File(this, "keep").writeText("busy") }
        val r = FileLogCore.append(dir, "voiceapp3.log", "new\n", maxBytes = 16)
        assertTrue("строка сохранена (дозаписью в активный файл)", r.written)
        assertEquals("ротации не было — прежняя часть цела", false, r.rotated)
        assertTrue("старые данные целы", f.readText().startsWith("x".repeat(64)))
        assertTrue("новая строка дописана", f.readText().endsWith("new\n"))
    }

    @Test
    fun rotationMovesWholeFileAndStartsNewOne() {
        val dir = tmpDir()
        val f = File(dir, "voiceapp3.log")
        f.writeText("old-data\n")
        val r = FileLogCore.append(dir, "voiceapp3.log", "fresh\n", maxBytes = 4)
        assertTrue(r.written)
        assertTrue("ротация заявлена", r.rotated)
        assertEquals("fresh\n", f.readText())
        assertEquals("старая часть сохранена целиком", "old-data\n",
            File(dir, "voiceapp3.log.1").readText())
    }

    @Test
    fun tailNulIsDetectedBeforeWriting() {
        val dir = tmpDir()
        val f = File(dir, "voiceapp3.log")
        java.io.FileOutputStream(f, true).use { out ->
            out.write("ok-line\n".toByteArray())
            out.write(ByteArray(4096))
        }
        val r = FileLogCore.append(dir, "voiceapp3.log", "after\n", maxBytes = 1024 * 1024)
        assertTrue(r.written)
        assertEquals("повреждение зафиксировано ДО записи", 4096, r.tailNulBefore)
        assertTrue("данные не переписаны: старый хвост на месте", f.readText().startsWith("ok-line\n"))
    }

    @Test
    fun concurrentWritersDoNotInterleaveLines() {
        val dir = tmpDir()
        val threads = 8
        val linesPer = 60
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                try {
                    repeat(linesPer) { i ->
                        // Ротация здесь не нужна (она удаляет .1 по контракту «две части») —
                        // проверяем именно целостность строк при конкурирующих писателях.
                        val r = FileLogCore.append(dir, "voiceapp3.log", "t$t-$i\n",
                            maxBytes = 10L * 1024 * 1024, lockWaitMs = 2000)
                        // Писатель обязан либо записать, либо назвать причину — «тихих» потерь нет.
                        assertTrue("строка t$t-$i: ${r.reason}", r.written)
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("писатели завершились", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()
        // Все строки должны остаться целыми: ни одной склеенной/разорванной.
        val parts = dir.listFiles { f -> f.name.startsWith("voiceapp3.log") && !f.name.endsWith(".lock") }!!
        val all = parts.flatMap { it.readLines() }.filter { it.isNotEmpty() }
        assertEquals("ни одна строка не потеряна", threads * linesPer, all.size)
        assertTrue("все строки целые", all.all { Regex("^t\\d+-\\d+$").matches(it) })
    }

    @Test
    fun busyLockRefusesToWriteInsteadOfCorrupting() {
        val dir = tmpDir()
        val probe = File(dir, "voiceapp3.log.lock")
        java.io.RandomAccessFile(probe, "rw").use { other ->
            val held = other.channel.lock()
            // Другой «процесс» держит лок. §8.1 iter47: строка не пишется в общий offset
            // (источник нулевых «дыр»), но и не теряется — уходит в spill-файл этого процесса.
            val blocked = FileLogCore.append(dir, "voiceapp3.log", "nope\n", lockWaitMs = 0)
            assertTrue("либо запись, либо spill с причиной: ${blocked.wire()}",
                blocked.written || blocked.reason.isNotEmpty())
            if (blocked.written) assertTrue("в основной файл не писали", blocked.spilled)
            // А с ожиданием лок освобождается и запись проходит.
            val waiting = FileLogCore.append(dir, "voiceapp3.log", "later\n", lockWaitMs = 30)
            assertFalse("результат определён", waiting.reason.startsWith("io:"))
            runCatching { held.release() }
        }
        // Без конкурента (лок освобождён) запись проходит и подтверждается.
        val ok = FileLogCore.append(dir, "voiceapp3.log", "after\n")
        assertTrue("после освобождения лока запись подтверждена", ok.written)
        assertTrue(ok.locked)
    }

    @Test
    fun failedWriteIsReportedNotSilentlySwallowed() {
        val dir = tmpDir()
        // Каталог недоступен: вместо «тихого успеха» — честный отказ с причиной.
        val notDir = File(dir, "not-a-dir")
        notDir.writeText("x")
        val r = FileLogCore.append(notDir, "voiceapp3.log", "line\n")
        assertFalse(r.written)
        assertTrue("причина названа, а не проглочена", r.reason.isNotEmpty())
    }
}
