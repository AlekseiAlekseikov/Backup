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
 * iter44 §1 — fail closed на изменение состава/тождества копий CPS/DPS и возврат только «своего».
 *
 * Уровень: **JVM-модель** production-кода (`JitOp.click`, `JitRef.planRestore`,
 * `JitRef.identityChanged`) на фейковом хранилище. Android-интеграция
 * (`JitNet.AndroidStore` с настоящими `SharedPreferences`) и ГУ этим НЕ доказаны.
 */
class JitFailClosed44Test {

    /**
     * Процессное состояние JIT (слот клика, «исход неопределён») — поля на жизнь процесса.
     * Без сброса тест, выполнивший цепочку с потерянным ответом, блокировал бы следующий клик
     * причиной `chain_undefined` — это порядок тестов, а не поведение.
     */
    @org.junit.Before
    fun resetJit() {
        JitOp.resetStateForTest()
        logs.clear()
    }

    private val logs = mutableListOf<String>()
    private fun log(s: String) { logs += s }
    private fun text(): String = logs.joinToString("\n")
    private fun stand() = JitIsoStand()

    @Test
    fun appearedCopyBlocksTheScript() {
        val store = JitFake.Store()
        store.appearAfterRead += 1     // DPS «появился» только к моменту записи
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertEquals("причина — изменение состава хранилищ", "store_set_changed", c.blocked)
        assertFalse("скрипт не отправлен", c.sent)
        assertTrue("shell не запускался", shell.pending.isEmpty())
        assertTrue("сеть не выключалась", net.requests.isEmpty())
        assertTrue("появившаяся копия названа", text().contains("appeared=[s1]"))
    }

    @Test
    fun vanishedCopyBlocksTheScript() {
        val store = JitFake.Store()
        store.vanishAfterRead += 0
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertEquals("store_set_changed", c.blocked)
        assertTrue("пропавшая копия названа", text().contains("absent=[s0]"))
        assertTrue(shell.pending.isEmpty())
    }

    @Test
    fun copyReplacedUnderSameNameBlocksTheScript() {
        val store = JitFake.Store()
        store.replaceIdentityAfterRead += 1
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())

        val c = JitOp.click(store, net, shell, want = true, log = ::log)

        assertEquals("store_set_changed", c.blocked)
        assertTrue("подмена тождества названа", text().contains("identity_changed=[s1]"))
        assertTrue("скрипт не отправлен", shell.pending.isEmpty())
        assertFalse("сеть не трогали", net.requests.contains("wifi=false"))
    }

    @Test
    fun sameCopyAndSameNamesAreNotBlocked() {
        val store = JitFake.Store()
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())
        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertNull("обычный клик не блокируется", c.blocked)
        assertTrue("скрипт отправлен", c.sent)
        assertFalse("нет ложного расхождения", text().contains("identity_changed=[s0"))
    }

    private fun prevOf(snapshot: String, mark: String): List<JitRef.StorePrev> =
        listOf(
            JitRef.StorePrev(
                "s0",
                listOf(
                    JitRef.KeyPrev(JitRef.KEY_PEDIDO, false, null, null),
                    JitRef.KeyPrev(JitRef.KEY_AUTO, false, null, null),
                    JitRef.KeyPrev(JitRef.KEY_RESTORE, true, null, snapshot),
                    JitRef.KeyPrev(JitRef.KEY_MARK, true, null, mark)
                )
            )
        )

    @Test
    fun planRestoreKeepsForeignAndUnreadableUntouched() {
        val snap = JitRef.WifiSnapshot(true)
        val foreignMark = JitRef.OpMark.of("op-1", true, JitRef.OpMark.SENDING).wire()
        val prev = prevOf("wifi=0", "op-0")
        val plan = JitRef.planRestore(prev, listOf(JitRef.StoreOp("s0", "wifi=0", foreignMark)), "op-2", snap)
        assertEquals("ничего не возвращаем", 0, plan.owned.size)
        assertEquals("чужое названо", listOf("s0"), plan.foreign)
        assertFalse("возврат неполный", plan.complete)
        assertTrue(plan.brief().contains("foreign=[s0]"))
    }

    @Test
    fun planRestoreOwnsWhenMarkAndSnapshotMatch() {
        val snap = JitRef.WifiSnapshot(true)
        val ourMark = JitRef.OpMark.of("op-1", true, JitRef.OpMark.SENDING).wire()
        val prev = prevOf("wifi=0", "op-0")
        val plan = JitRef.planRestore(prev, listOf(JitRef.StoreOp("s0", "wifi=1", ourMark)), "op-1", snap)
        assertEquals("своё возвращаем", 1, plan.owned.size)
        assertTrue(plan.complete)
    }

    @Test
    fun planRestoreTreatsReadErrorsAndMissingMarksAsUnknown() {
        val snap = JitRef.WifiSnapshot(true)
        val prev = prevOf("wifi=0", "op-0")
        val err = JitRef.planRestore(
            prev, listOf(JitRef.StoreOp("s0", null, null, "read:IllegalStateException")), "op-1", snap
        )
        assertEquals(listOf("s0"), err.unknown)
        val noMark = JitRef.planRestore(prev, listOf(JitRef.StoreOp("s0", "wifi=1", null)), "op-1", snap)
        assertEquals(listOf("s0"), noMark.unknown)
        val noOps = JitRef.planRestore(prev, emptyList(), "op-1", snap)
        assertEquals(listOf("s0"), noOps.unknown)
    }

    @Test
    fun clickLogDoesNotClaimNoWriteWhenRollbackHappened() {
        val store = JitFake.Store().apply { commitFails += listOf(0, 1); writeDespiteFalse = true }
        val net = JitFake.Net(wifi = true)
        val shell = JitFake.QueueShell(stand())

        val c = JitOp.click(store, net, shell, want = true, log = ::log)
        assertEquals("snapshot_not_saved", c.blocked)
        assertTrue("видно, что запись БЫЛА", text().contains("wrote_any=true"))
        assertTrue("показан план возврата", text().contains("restore_plan="))
        assertFalse("ложного «ни записи» нет", text().contains("(ни записи, ни shell"))
        assertTrue(shell.pending.isEmpty())
    }
}

/**
 * iter44 §2 — журнал не теряет полезные записи; части разбиения и номера частей.
 * Уровень: JVM на **реальных файлах** (`LogFiles`), не Android-интеграция.
 */
class LogFiles44Test {

    private fun tmp(prefix: String = "lf44"): File = Files.createTempDirectory(prefix).toFile()

    /** §2.6: шторм выше прежнего бюджета — все уникальные события на месте. */
    @Test
    fun stormAboveOldBudgetKeepsEveryUniqueEvent() {
        val d = tmp()
        LogFiles.resetForTest()
        val ids = (1..500).map { "EVT%03d".format(it) }
        for (id in ids) {
            val r = LogFiles.appendTierForTest(d, "$id TTL/WIFI/JIT событие", LogFiles.Tier.MAIN)
            assertTrue("строка $id записана: ${r.reason}", r.written)
        }
        val all = LogFiles.partsOf(d, LogFiles.Tier.MAIN).joinToString("\n") { it.readText() }
        for (id in ids) assertTrue("событие $id сохранено", all.contains("$id "))
    }

    /** §2.4: `.999` → `.1000` не теряет распознавание своей части. */
    @Test
    fun partNumberBeyondThreeDigitsIsStillRecognised() {
        val base = "voiceapp3-01.01.2026"
        for (n in listOf(9, 999, 1000, 12345)) {
            val parsed = LogFiles.parseOwn(LogFiles.rotatedName("$base.log", n))
            assertNotNull("часть $n распознана", parsed)
            assertEquals("номер прочитан", n, parsed!!.third)
        }
        assertNull("слишком длинный номер — не наш файл",
            LogFiles.parseOwn("$base.1234567890.log"))
    }

    /** §2.3/§2.4: недоступный каталог — честный отказ, независимое сообщение о факте. */
    @Test
    fun unavailableDirectoryIsReportedNotFaked() {
        val d = tmp()
        LogFiles.resetForTest()
        val notDir = File(d, "not-a-dir")
        notDir.writeText("я не каталог")
        val r = LogFiles.appendTierForTest(notDir, "строка", LogFiles.Tier.MAIN)
        assertFalse("запись не подтверждена", r.written)
        assertTrue("причина названа", r.reason.isNotEmpty())
        assertTrue("отказ виден независимо от Result",
            LogFiles.lastFacts().any { it.contains("LOGFILES_WRITE_ERROR no_dir") })
    }

    /** §2.4: старая часть не удаляется, если новая запись не выполнена. */
    @Test
    fun oldPartSurvivesWhenNewPartCannotBeWritten() {
        val d = tmp()
        LogFiles.resetForTest()
        val base = LogFiles.fileNameOf(java.time.LocalDate.now())
        val chunk = "x".repeat(64 * 1024)
        var guard = 0
        while (guard++ < 400 && (!File(d, base).isFile || File(d, base).length() < LogFiles.MAX_FILE_BYTES)) {
            if (!LogFiles.appendTierForTest(d, chunk, LogFiles.Tier.MAIN).written) break
        }
        assertTrue("активная часть заполнена", File(d, base).isFile)
        val before = LogFiles.partsOf(d, LogFiles.Tier.MAIN).map { it.name }.toSet()
        val ro = File(d, "ro")
        assertTrue(ro.mkdirs())
        ro.setWritable(false)
        val r = LogFiles.appendTierForTest(ro, "nope", LogFiles.Tier.MAIN)
        ro.setWritable(true)
        assertFalse("в недоступный каталог не записано", r.written)
        assertEquals("старые части НЕ удалены", before,
            LogFiles.partsOf(d, LogFiles.Tier.MAIN).map { it.name }.toSet())
    }

    /** §2.4: ретеншн работает ВО ВРЕМЯ работы, не только в `prepare`. */
    @Test
    fun retentionWorksWhileRunning() {
        val d = tmp()
        LogFiles.resetForTest()
        val old = LogFiles.fileNameOf(java.time.LocalDate.now().minusDays(LogFiles.RETENTION_DAYS + 5))
        File(d, old).writeText("старьё\n")
        LogFiles.appendTierForTest(d, "первая", LogFiles.Tier.MAIN, System.currentTimeMillis())
        assertFalse("просроченная часть удалена во время работы", File(d, old).isFile)
        assertTrue("факт удаления виден в журнале фактов",
            LogFiles.lastFacts().any { it.contains("LOG_RETENTION_RUNTIME") })
    }
}

/**
 * iter44 §3.1/§3.2 — метки TTL-снимков по фактическим временам и привязка к операции.
 * Уровень: JVM-модель (`JitRef.TtlSnapshotFlow`); Android-интеграция тумблера не проверена.
 */
class TtlSnapshotFlow44Test {

    @Test
    fun labelIsNotConfirmedWhileCollectionIsStillRunning() {
        val f = JitRef.TtlSnapshotFlow()
        val op = f.begin(true, 1_000L)
        f.preStarted(op, 1_100L)
        // Сбор идёт: уверенного «до» ещё нет.
        assertEquals("pre_unfinished", f.preLabel(op))
        f.preFinished(op, 1_400L)
        assertEquals("сбор завершён до применения — можно говорить «до»", "pre_confirmed", f.preLabel(op))
    }

    @Test
    fun missingStartIsUnknownEvenIfFinished() {
        val f = JitRef.TtlSnapshotFlow()
        val op = f.begin(true, 1_000L)
        f.preFinished(op, 1_400L)
        assertEquals("pre_unknown", f.preLabel(op))
    }

    @Test
    fun lateCallbackOfPreviousOpDoesNotTouchNewOp() {
        val f = JitRef.TtlSnapshotFlow()
        val first = f.begin(true, 1_000L)
        val second = f.begin(false, 1_100L)     // быстрый ON/OFF: началась новая операция
        assertFalse("отметка старой операции не применяется", f.applied(first, 1_200L, "ok"))
        assertFalse(f.postScheduled(first, 1_200L))
        assertNull("у новой операции применения нет", f.currentOp()!!.appliedAt)
        assertEquals("post_without_confirmed_apply", f.postLabel(second))
        assertTrue("своя отметка применяется", f.applied(second, 1_300L, "ok"))
        assertEquals("post_after_apply", f.postLabel(second))
    }

    @Test
    fun failedApplyIsNotCalledSuccess() {
        val f = JitRef.TtlSnapshotFlow()
        val op = f.begin(true, 1_000L)
        f.preStarted(op, 1_000L)
        f.preFinished(op, 1_100L)
        f.applied(op, 1_200L, "fail:setprop_rc=1")
        assertEquals("post_after_apply_not_ok:fail:setprop_rc=1", f.postLabel(op))
        assertFalse("успех не заявляется", f.postLabel(op) == "post_after_apply")
    }

    @Test
    fun describeKeepsBothLabelsForReport() {
        val f = JitRef.TtlSnapshotFlow()
        val op = f.begin(true, 1_000L)
        f.preStarted(op, 1_000L)
        f.preFinished(op, 1_500L)
        f.applied(op, 1_400L, "ok")
        val d = f.describe(op, JitRef.TtlSnapshotFlow.SnapPart.PRE, "net=validated")
        assertTrue("пересечение сбора и применения названо", d.contains("label=pre_overlap"))
        assertTrue("метка не выдаётся за подтверждение успеха", !d.contains("label=post_after_apply"))
    }
}
