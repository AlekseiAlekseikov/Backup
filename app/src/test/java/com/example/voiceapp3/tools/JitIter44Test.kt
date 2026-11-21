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
