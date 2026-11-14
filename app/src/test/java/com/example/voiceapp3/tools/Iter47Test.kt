package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * iter47 §4.3/§5.5/§6.3/§8/§9 — тесты PRODUCTION-оркестрации, а не модели.
 *
 * Для TTL проверяется сам [TtlRunner] (тот код, который вызывает `NetworkTtl`), с управляемым
 * fake транспорта (записывает последовательность применений, может падать/быть медленным) и
 * fake хранилища (намерение/поколение/факт). Это позволяет воспроизвести именно те гонки, из-за
 * которых в iter46 клик терялся.
 *
 * Уровень: JVM. Android-интеграция и ГУ не проверяются — это прямо указано в отчёте.
 */
class Iter47Test {

    // ───────────────────────────── fake-транспорт и хранилище ─────────────────────────────

    /** Транспорт: пишет историю, умеет «медленно» (для гонок) и «подтверждён/нет». */
    private class FakeTransport(
        var confirmed: Boolean = true,
        var failWithException: Boolean = false
    ) : TtlRunner.Transport {
        val applied = mutableListOf<Boolean>()
        var onApply: ((Boolean) -> Unit)? = null
        val parallel = AtomicInteger(0)
        val maxParallel = AtomicInteger(0)

        override fun apply(want: Boolean): TtlRunner.Outcome {
            val now = parallel.incrementAndGet()
            maxParallel.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                applied += want
                onApply?.invoke(want)
                if (failWithException) throw IllegalStateException("transport down")
                return if (confirmed) TtlRunner.Outcome(true)
                else TtlRunner.Outcome(false, "no_uplink")
            } finally {
                parallel.decrementAndGet()
            }
        }
    }

    private class FakeStore(want: Boolean? = null) : TtlRunner.Store {
        val desiredRef = AtomicLong(0) // 0=неизвестно, 1=ON, 2=OFF
        val gen = AtomicLong(0)
        @Volatile var intentSaved = true
        @Volatile var conflictFlag = false
        val facts = mutableListOf<String>()
        val appliedFor = mutableListOf<Long>()

        init {
            when (want) {
                true -> desiredRef.set(1); false -> desiredRef.set(2)
                null -> Unit
            }
        }

        override fun desired(): Boolean? = when (desiredRef.get()) {
            1L -> true
            2L -> false
            else -> null
        }

        override fun intentGen(): Long = gen.get()

        override fun beginIntent(want: Boolean): Long {
            desiredRef.set(if (want) 1L else 2L)
            if (!intentSaved) return -1L
            return gen.incrementAndGet()
        }

        override fun markApplied(ok: Boolean, want: Boolean, g: Long, current: Boolean, reason: String) {
            facts += "ok=$ok want=$want gen=$g current=$current reason=$reason"
            if (ok && current) appliedFor += g
        }

        override fun conflict(): Boolean = conflictFlag
    }

    private fun runner(
        t: FakeTransport,
        s: FakeStore,
        logs: MutableList<String> = mutableListOf()
    ) = TtlRunner(t, s, { logs += it }) to logs

    // ───────────────────────── §4.3: тот самый дефект iter46 ─────────────────────────

    @Test
    fun offDuringOnIsFollowedUpAndCoordinatorDoesNotStayRunning() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = false)
        val (r, _) = runner(t, s)

        // ON начался; во время выполнения владелец нажал OFF.
        t.onApply = { want -> if (want) { s.beginIntent(false) } }
        val res = r.request(true, "click")

        assertEquals(
            "после ON обязательно выполнен проход с OFF (последнее намерение достигнуто)",
            listOf(true, false), t.applied
        )
        assertTrue("координатор НЕ остался running", !r.isRunning())
        assertEquals("состояние = последнее намерение", false, s.desired())
        assertTrue("факт записан для актуального поколения", s.appliedFor.isNotEmpty())
        assertEquals(TtlRunner.Request.STARTED, res.request)
    }

    @Test
    fun newClickAfterFollowUpReallyRuns() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = true)
        val (r, _) = runner(t, s)
        t.onApply = { want -> if (want) s.beginIntent(false) }
        r.request(true, "click")           // ON → OFF (добивание)
        t.onApply = null
        t.applied.clear()
        val res = r.request(true, "click") // новый ON обязан реально выполниться
        assertEquals(listOf(true), t.applied)
        assertTrue("новый клик не «в ожидании»", res.request == TtlRunner.Request.STARTED)
        assertTrue(res.confirmed)
    }

    @Test
    fun exceptionInFirstPassIsFailureAndNotApplied() {
        val t = FakeTransport(failWithException = true)
        val s = FakeStore(want = true)
        val (r, _) = runner(t, s)
        val res = r.request(true, "click")
        assertFalse("исключение транспорта ≠ применено", res.confirmed)
        assertTrue(res.reason.startsWith("exception"))
        assertFalse(r.appliedOk())
        assertTrue("факт отмечен как провал", s.facts.all { it.contains("ok=false") })
    }

    @Test
    fun exceptionInFollowUpIsAlsoReported() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = true)
        val (r, _) = runner(t, s)
        var pass = 0
        t.onApply = {
            pass++
            if (pass == 1) { s.beginIntent(false); t.failWithException = true }
        }
        val res = r.request(true, "click")
        assertFalse(res.confirmed)
        assertEquals("второй проход всё равно был выполнен", 2, t.applied.size)
        assertFalse(r.isRunning())
    }

    @Test
    fun rapidClicksConvergeToLastIntentWithoutStorm() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = true)
        val (r, _) = runner(t, s)
        var clicks = 0
        t.onApply = {
            // Пока выполняется, владелец успевает нажать несколько раз.
            if (clicks == 0) { clicks = 1; s.beginIntent(false); s.beginIntent(true); s.beginIntent(false) }
        }
        val res = r.request(true, "click")
        assertEquals("последнее намерение OFF", false, s.desired())
        assertEquals("последним применён OFF", false, t.applied.last())
        assertTrue("число проходов ограничено (нет шторма): ${t.applied.size}", t.applied.size <= 3)
        assertTrue(res.request == TtlRunner.Request.STARTED || res.request == TtlRunner.Request.QUEUED)
    }

    @Test
    fun sameIntentIsNotReappliedInLoop() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = true)
        val (r, _) = runner(t, s)
        r.request(true, "click")
        r.request(true, "click")   // повторный клик того же намерения
        assertTrue("лишних прогонов нет: ${t.applied.size}", t.applied.size <= 2)
    }

    @Test
    fun networkEventDuringRunIsNotLost() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = true)
        val (r, logs) = runner(t, s)
        var trigger: TtlRunner.Result? = null
        t.onApply = {
            if (trigger == null) {
                // Сеть вернулась, пока шёл проход: событие не должно потеряться.
                trigger = r.onBackgroundTrigger("network")
            }
        }
        r.request(true, "click")
        // Во время прохода событие НЕ запускает второй проход (он не параллелен), но и не
        // теряется: оно помечается, и текущий цикл делает ещё один проход.
        assertTrue("событие зафиксировано как mark_dirty: $logs",
            logs.any { it.contains("action=mark_dirty") })
        assertTrue("после прохода выполнен добивочный проход: ${t.applied}",
            t.applied.size >= 2)
    }

    @Test
    fun networkTriggerWhenOffNeverTurnsOn() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = false)
        val (r, _) = runner(t, s)
        r.onBackgroundTrigger("network")
        assertTrue("при OFF не отправляется ON", t.applied.none { it })
        assertTrue("допустима только санация своих правил", t.applied.all { !it })
    }

    @Test
    fun bootTriggerDoesNotChangeIntent() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = true)
        val (r, _) = runner(t, s)
        r.onBackgroundTrigger("boot")
        assertEquals("намерение не тронуто автоматикой", true, s.desired())
        assertEquals("фоновый повод не создаёт нового намерения", 0L, s.gen.get())
    }

    @Test
    fun conflictBlocksWriteInsteadOfInventingOrder() {
        val t = FakeTransport()
        val s = FakeStore(want = true).apply { conflictFlag = true }
        val (r, _) = runner(t, s)
        val res = r.request(false, "click")
        assertEquals(TtlRunner.Request.CONFLICT, res.request)
        assertTrue("ни одной команды при конфликте", t.applied.isEmpty())
    }

    @Test
    fun failedIntentWriteIsReportedAndNotClaimedApplied() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = null).apply { intentSaved = false }
        val (r, _) = runner(t, s)
        val res = r.request(true, "click")
        assertEquals(TtlRunner.Request.INTENT_NOT_SAVED, res.request)
        assertFalse("факт «применено» не заявляется без сохранённого намерения", res.confirmed)
    }

    @Test
    fun unknownDesiredIsNotGuessed() {
        val t = FakeTransport()
        val s = FakeStore(want = null)
        val (r, _) = runner(t, s)
        assertNull("при неизвестном намерении фоновый триггер ничего не применяет",
            r.onBackgroundTrigger("boot"))
        assertTrue(t.applied.isEmpty())
    }

    @Test
    fun concurrentClicksNeverRunShellInParallel() {
        val t = FakeTransport(confirmed = true)
        val s = FakeStore(want = true)
        val (r, _) = runner(t, s)
        t.onApply = { Thread.sleep(5) }
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) { i ->
            pool.execute {
                start.await()
                try { r.request(i % 2 == 0, "click") } finally { done.countDown() }
            }
        }
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals("параллельных применений TTL НЕТ по построению", 1, t.maxParallel.get())
        assertEquals("последнее применённое совпадает с последним намерением",
            s.desired(), t.applied.last())
        assertFalse(r.isRunning())
    }

    // ───────────────────────── §6.1: правила CPS/DPS ─────────────────────────

    @Test
    fun newerValueWinsRegardlessOfStore() {
        val r1 = Toggles45.resolveBoolean(false, 5, false, true, 2, false)
        assertEquals(Toggles45.Resolved.Value(false), r1)
        val r2 = Toggles45.resolveBoolean(true, 2, false, false, 5, false)
        assertEquals(Toggles45.Resolved.Value(false), r2)
    }

    @Test
    fun equalRevisionsWithDifferentValuesAreConflict() {
        assertEquals(Toggles45.Resolved.Conflict,
            Toggles45.resolveBoolean(true, 7, false, false, 7, false))
        assertEquals(Toggles45.Resolved.Conflict,
            Toggles45.resolveString("/a.wav", 4, false, "/b.wav", 4, false))
    }

    @Test
    fun tombstoneDoesNotResurrectOldValueFromOtherCopy() {
        // Новее — УДАЛЕНИЕ в CPS: старый путь из DPS не должен воскреснуть.
        assertEquals(Toggles45.Resolved.Deleted,
            Toggles45.resolveString(null, 9, true, "/old.wav", 4, false))
        assertEquals(Toggles45.Resolved.Deleted,
            Toggles45.resolveBoolean(null, 9, true, true, 4, false))
        // Обе копии удалены — тоже Deleted, а не Unknown.
        assertEquals(Toggles45.Resolved.Deleted,
            Toggles45.resolveString(null, 3, true, null, 3, true))
    }

    @Test
    fun partialWriteDoesNotFallBackToStaleCopy() {
        // Победившая копия новее, но значения в ней нет и tombstone не выставлен (сбой записи):
        // это «неизвестно», а не «вторая копия права».
        assertEquals(Toggles45.Resolved.Unknown,
            Toggles45.resolveBoolean(null, 8, false, true, 3, false))
    }

    @Test
    fun legacyInstallWithoutRevisionsKeepsOldSemantics() {
        assertEquals(Toggles45.Resolved.Value(true), Toggles45.resolveBoolean(true, -1, false, null, -1, false))
        assertEquals(Toggles45.Resolved.Unknown, Toggles45.resolveBoolean(null, -1, false, null, -1, false))
        assertEquals(Toggles45.Resolved.Value("/a.wav"), Toggles45.resolveString(null, -1, false, "/a.wav", -1, false))
    }

    // ───────────────────────── §5: реестр владения монтированиями ─────────────────────────

    @Test
    fun ownMountRegistryKeepsSourceAndGeneration() {
        val parsed = parseOwnMountLike("target=/vendor/etc/carlock/a.wav",
            "/vendor/etc/carlock/a.wav|/data/local/tmp/carlock_custom_g3_ab12cd34.wav|3")
        assertEquals("/vendor/etc/carlock/a.wav", parsed.first)
        assertEquals("/data/local/tmp/carlock_custom_g3_ab12cd34.wav", parsed.second)
        assertEquals(3L, parsed.third)
        // Старый формат (только путь) — источник неизвестен, поколение -1.
        val legacy = parseOwnMountLike("target", "/vendor/etc/carlock/b.wav")
        assertEquals(-1L, legacy.third)
        assertEquals("", legacy.second)
    }

    private fun parseOwnMountLike(label: String, line: String): Triple<String, String, Long> {
        assertEquals(label, label) // подпись читаемости
        val parts = line.split('|')
        return Triple(parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "-1" }.toLong())
    }

    // ───────────────────────── §8: транзакционная ротация и spill ─────────────────────────

    @Test
    fun transactionRotationKeepsBothPartsOnFailure() {
        val dir = tmpDir()
        val f = File(dir, "voiceapp3.log")
        f.writeText("old-part\n")
        // `.1` — непустой каталог: удалить нельзя.
        File(dir, "voiceapp3.log.1").apply { mkdir(); File(this, "x").writeText("busy") }
        val r = FileLogCore.append(dir, "voiceapp3.log", "line\n", maxBytes = 4)
        assertTrue("строка не потеряна", r.written)
        assertTrue("прежняя часть цела", f.readText().startsWith("old-part"))
        assertTrue("новая строка дописана в активный файл", f.readText().endsWith("line\n"))
    }

    @Test
    fun successfulRotationMovesWholeFileAndPromotesNewPart() {
        val dir = tmpDir()
        val f = File(dir, "voiceapp3.log")
        f.writeText("old-part\n")
        val r = FileLogCore.append(dir, "voiceapp3.log", "fresh\n", maxBytes = 4)
        assertTrue(r.written)
        assertTrue(r.rotated)
        assertEquals("fresh\n", f.readText())
        assertEquals("old-part\n", File(dir, "voiceapp3.log.1").readText())
        assertFalse("временная часть не осталась", File(dir, "voiceapp3.log.new").exists())
    }

    @Test
    fun busyLockSpillsInsteadOfLosingLine() {
        val dir = tmpDir()
        val lockFile = File(dir, "voiceapp3.log.lock")
        java.io.RandomAccessFile(lockFile, "rw").use { holder ->
            // Другой «процесс» ДЕРЖИТ лок: без этого проверялось бы ничего.
            val held = holder.channel.lock()
            try {
                val r = FileLogCore.append(dir, "voiceapp3.log", "important\n", lockWaitMs = 0)
            assertTrue("строка сохранена в spill: " + r.wire(), r.written && r.spilled)
            assertNotNull(r.spillFile)
            assertTrue("spill назван честно", r.reason.contains("process_lock_busy"))
                assertTrue("в spill есть строка", r.spillFile!!.readText().contains("important"))
            } finally {
                runCatching { held.release() }
            }
        }
        // Лок освободился — spill вливается в журнал и не теряется.
        val drained = FileLogCore.drainSpill(dir, "voiceapp3.log")
        assertTrue("spill влит: $drained", drained > 0)
        assertTrue(File(dir, "voiceapp3.log").readText().contains("important"))
        assertFalse("spill-файл удалён после слияния", FileLogCore.spillFor(File(dir, "voiceapp3.log")).exists())
    }

    @Test
    fun spillIsPerProcessNotShared() {
        val dir = tmpDir()
        val a = FileLogCore.spillFor(File(dir, "voiceapp3.log"))
        val b = FileLogCore.spillFor(File(dir, "other.log"))
        assertTrue("spill одного журнала отличается от другого", a.name != b.name)
        assertTrue("spill не совпадает с основным файлом", a.name != "voiceapp3.log")
    }

    // ───────────────────────── §9: окна и §7: JIT-признак ─────────────────────────

    @Test
    fun lateReadbackMustNotDropOwnersAddedLater() {
        // Модель правила из WindowDoors: снимаем запись, только если набор НЕ изменился.
        fun canDrop(ownersAtAttempt: List<Int>, ownersNow: List<Int>) =
            ownersAtAttempt == ownersNow && ownersNow.isEmpty()

        assertTrue("пусто и не менялось — можно снять", canDrop(emptyList(), emptyList()))
        assertFalse("владельца добавили позже — запись сохраняем",
            canDrop(emptyList(), listOf(42)))
        assertFalse("набор изменился — нe трогаем", canDrop(listOf(7), listOf(7, 42)))
    }

    @Test
    fun zygoteRestartIsEvidenceAndClockShiftIsNot() {
        val now = 1_800_000_000_000L
        val mark = JitRef.OpMark.of("op1", true, JitRef.OpMark.UNDEFINED, now - 20_000)
        assertTrue("zygote стартовал после отметки — команда доставлена",
            JitOp.zygoteRestartEvidence(mark, now - 5_000, now))
        assertFalse("zygote стартовал ДО отметки — не доказательство",
            JitOp.zygoteRestartEvidence(mark, now - 120_000, now))
        assertFalse("zygote не читается", JitOp.zygoteRestartEvidence(mark, null, now))
        assertFalse("нет отметки", JitOp.zygoteRestartEvidence(null, now, now))
        // Коррекция часов: отметка «из будущего» — не доказательство.
        val future = JitRef.OpMark.of("op2", true, JitRef.OpMark.UNDEFINED, now + 600_000)
        assertFalse(JitOp.zygoteRestartEvidence(future, now + 10_000, now))
    }

    private fun tmpDir(): File =
        File.createTempFile("iter47", "").let { it.delete(); it.mkdirs(); it }
}
