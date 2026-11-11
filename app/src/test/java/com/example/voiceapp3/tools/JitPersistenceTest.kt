package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Настоящий `JitNet.AndroidStore` на реалистичной модели `SharedPreferences`.
 *
 * Модель ([JitFake.Prefs]) ведёт себя как Android: `commit()` сразу меняет ПАМЯТЬ, а на «диск»
 * пишет только при успехе; [JitFake.Prefs.newProcess] — это «новый процесс», который читает
 * только то, что осталось на «диске». Именно здесь проверяется то, чего нельзя проверить на
 * простом фейке: «commit=false, но значение видно в памяти» и «а что прочитает продолжение после
 * перезапуска».
 *
 * Это тест ЛОГИКИ хранилища на JVM. Не Android-интеграция и не прогон на ГУ: реальный
 * `SharedPreferences` ГУ здесь не участвует.
 */
class JitPersistenceTest {

    private lateinit var cpsDisk: MutableMap<String, Any?>
    private lateinit var dpsDisk: MutableMap<String, Any?>

    @Before
    fun setUp() {
        cpsDisk = mutableMapOf()
        dpsDisk = mutableMapOf()
    }

    private fun store(cps: JitFake.Prefs?, dps: JitFake.Prefs?) =
        JitNet.AndroidStore({ cps }, { dps })

    private fun bothPrefs() = JitFake.Prefs(cpsDisk) to JitFake.Prefs(dpsDisk)

    private fun mk(want: Boolean = true, op: String = "op-test", state: String = JitRef.OpMark.SENDING) =
        JitRef.OpMark.of(op, want, state, 1_700_000_000_000L)

    private val snapTrue = JitRef.WifiSnapshot(true)
    private val snapFalse = JitRef.WifiSnapshot(false)

    @Test
    fun successfulCommitIsConfirmedByReadbackOfAllThreeKeys() {
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue, mk())
        assertEquals(2, w.attempted)
        assertEquals(2, w.committed)
        assertEquals(2, w.confirmed)
        assertTrue(w.stores.all { it.durable })
        // Все три ключа дошли до «диска» в каждом хранилище.
        for (disk in listOf(cpsDisk, dpsDisk)) {
            assertEquals(true, disk[JitRef.KEY_PEDIDO])
            assertEquals(true, disk[JitRef.KEY_AUTO])
            assertEquals("wifi=1", disk[JitRef.KEY_RESTORE])
            assertEquals("отметка операции тоже durable", mk().wire(), disk[JitRef.KEY_MARK])
        }
    }

    @Test
    fun commitFalseWithValueInMemoryIsNotConfirmed() {
        // Ровно тот дефект, который был: значение видно в памяти, но на «диск» не попало.
        val (cps, dps) = bothPrefs()
        cps.commitOk = false
        dps.commitOk = false
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue, mk())
        assertEquals(0, w.committed)
        assertEquals(0, w.confirmed)
        assertEquals("значение видно в памяти текущего процесса", "wifi=1", s.getString(JitRef.KEY_RESTORE))
        assertNull("а после перезапуска его нет", JitFake.Prefs(cpsDisk).getString(JitRef.KEY_RESTORE, null))
    }

    @Test
    fun commitFalseInOneStoreStillAllowsTheOtherWhenReadIsUnambiguous() {
        val (cps, dps) = bothPrefs()
        cps.commitOk = false     // CPS: значение только в памяти
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue, mk())
        assertEquals(1, w.committed)
        assertEquals(1, w.confirmed)
        assertEquals(setOf("dps"), w.durableNames())
        assertEquals("DPS переживёт перезапуск", "wifi=1", JitFake.Prefs(dpsDisk).getString(JitRef.KEY_RESTORE, null))
    }

    @Test
    fun divergedStoresMakeTheSnapshotAmbiguous() {
        // Хранилища уже расходятся: продолжение прочитало бы CPS, а при недоступном CPS — DPS.
        cpsDisk[JitRef.KEY_RESTORE] = "wifi=0"
        dpsDisk[JitRef.KEY_RESTORE] = "wifi=1"
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        val read = s.readRestore()
        assertEquals("порядок чтения CPS→DPS: побеждает CPS", "wifi=0", read.value)
        assertTrue("расхождение зафиксировано (повод заблокировать JIT локально)",
            read.conflicts.any { it.startsWith(JitRef.KEY_RESTORE) })
    }

    @Test
    fun keyPresentOnlyInFirstStoreIsNotAConflict() {
        // Отсутствие ключа во втором хранилище не делает снимок неоднозначным: чтение возьмёт
        // первое, а при его недоступности получит `null` = «снимка нет» (JIT ничего не сделает).
        cpsDisk[JitRef.KEY_PEDIDO] = true
        cpsDisk[JitRef.KEY_RESTORE] = "wifi=1"
        val (cps, dps) = bothPrefs()
        val read = store(cps, dps).readRestore()
        assertEquals("wifi=1", read.value)
        assertTrue("конфликта нет", read.conflicts.isEmpty())
    }

    @Test
    fun partialCommitIsNotUsableForTheFollowUp() {
        // CPS не записался, DPS записался: отчёт не выдаёт 2/2, а будущее чтение однозначно.
        cpsDisk[JitRef.KEY_RESTORE] = "wifi=0"
        dpsDisk[JitRef.KEY_RESTORE] = "wifi=0"
        val (cps, dps) = bothPrefs()
        cps.commitOk = false
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue, mk())
        assertEquals(2, w.attempted)
        assertEquals(1, w.committed)
        assertEquals(1, w.confirmed)
        assertEquals(setOf("dps"), w.durableNames())
        assertFalse("первое по порядку чтения хранилище не подтвердило запись", w.firstReadable)
        // Поэтому ядро и блокирует клик: после перезапуска продолжение прочитает из CPS СТАРОЕ.
        val fresh = store(JitFake.Prefs(cpsDisk), JitFake.Prefs(dpsDisk))
        assertEquals("после перезапуска читается прежнее значение", "wifi=0", fresh.readRestore().value)
    }

    @Test
    fun firstRunWithoutAnyValueHasNoConflict() {
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        assertNull(s.readRestore().value)
        assertTrue(s.readRestore().conflicts.isEmpty())
    }

    @Test
    fun missingStoreIsNotTwoOfTwo() {
        val cps = JitFake.Prefs(cpsDisk)
        val s = store(cps, null)   // DPS недоступен
        val w = s.putSnapshot(true, snapTrue, mk())
        assertEquals(1, w.attempted)
        assertEquals(1, w.confirmed)
        assertFalse("нет ложного 2/2", w.attempted == 2)
    }

    @Test
    fun noStoreAtAllMeansNothingWasWritten() {
        val s = store(null, null)
        val w = s.putSnapshot(true, snapTrue, mk())
        assertEquals(0, w.attempted)
        assertEquals(0, w.confirmed)
        assertFalse(w.stores.any { it.durable })
    }

    @Test
    fun editExceptionInOneStoreIsReportedPerStore() {
        val (cps, dps) = bothPrefs()
        cps.throwOnEdit = true
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue, mk())
        assertEquals(1, w.committed)
        assertTrue(w.stores.any { it.commit.startsWith("exception") })
        assertTrue(w.detail.contains("exception"))
    }

    @Test
    fun readExceptionDoesNotPretendSuccess() {
        val (cps, dps) = bothPrefs()
        dps.throwOnRead = true
        val s = store(cps, dps)
        // CPS читается нормально, поэтому DPS-сбой не должен ломать снимок, но и не должен
        // выдавать себя за согласованность: конфликт/подтверждение считаются по фактам.
        val w = s.putSnapshot(true, snapTrue, mk())
        assertTrue(w.attempted >= 1)
        assertEquals("wifi=1", s.getString(JitRef.KEY_RESTORE))
    }

    @Test
    fun partialKeyWriteIsNotDurable() {
        val (cps, dps) = bothPrefs()
        cps.partialKeys = setOf(JitRef.KEY_AUTO)   // один из трёх ключей не записался
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue, mk())
        val cpsReport = w.stores.first { it.name == "cps" }
        assertFalse("чтения одного ключа недостаточно", cpsReport.durable)
        assertEquals(1, w.confirmed)
    }

    @Test
    fun removeTouchesOnlyRequestedStoresAndLeavesOthersIntact() {
        val (cps, dps) = bothPrefs()
        cpsDisk[JitRef.KEY_PEDIDO] = true
        dpsDisk[JitRef.KEY_PEDIDO] = true
        val s = store(cps, dps)
        val r = s.remove(JitRef.KEY_PEDIDO, setOf("dps"))
        assertEquals(1, r.attempted)
        assertEquals(1, r.ok)
        assertEquals("CPS не тронут", true, cpsDisk[JitRef.KEY_PEDIDO])
        assertFalse("DPS очищен", dpsDisk.containsKey(JitRef.KEY_PEDIDO))
    }

    @Test
    fun removeFailureIsVisibleAndKeySurvivesRestart() {
        val (cps, dps) = bothPrefs()
        cpsDisk[JitRef.KEY_PEDIDO] = true
        cps.commitOk = false
        val s = store(cps, null)
        val r = s.remove(JitRef.KEY_PEDIDO)
        assertEquals(0, r.ok)
        assertEquals(1, r.attempted)
        assertTrue("отказ виден в detail", r.detail.contains("commit_false"))
        // В памяти текущего процесса ключа уже нет (как в Android), но на «диске» он есть —
        // следующий старт увидит незавершённое намерение. Это и есть честная картина отказа.
        assertTrue("ключ вернётся после перезапуска", JitFake.Prefs(cpsDisk).contains(JitRef.KEY_PEDIDO))
    }

    @Test
    fun newProcessReadsOnlyWhatWasDurablyWritten() {
        val (cps, dps) = bothPrefs()
        cps.commitOk = false      // CPS: только память
        val s = store(cps, dps)
        s.putSnapshot(true, snapFalse, mk())
        // «Перезапуск»: новые объекты читают только «диск».
        val fresh = store(JitFake.Prefs(cpsDisk), JitFake.Prefs(dpsDisk))
        assertEquals("снимок пережил перезапуск в DPS", "wifi=0", fresh.readRestore().value)
        assertTrue(fresh.contains(JitRef.KEY_PEDIDO))
    }

    @Test
    fun snapshotSurvivesRestartAndIsRemovedBeforeRestore() {
        val (cps, dps) = bothPrefs()
        store(cps, dps).putSnapshot(true, snapTrue, mk())

        // Новый процесс: снимок читается и снимается до планирования.
        val fresh = store(JitFake.Prefs(cpsDisk), JitFake.Prefs(dpsDisk))
        val parse = JitRef.parseSnapshot(fresh.readRestore().value)
        assertEquals(snapTrue, parse!!.snap)
        fresh.remove(JitRef.KEY_RESTORE)
        assertFalse(fresh.contains(JitRef.KEY_RESTORE))
        assertNull("в том числе на «диске»", JitFake.Prefs(cpsDisk).getString(JitRef.KEY_RESTORE, null))
    }

    @Test
    fun autoKeyIsNeverRemovedByBlockedCleanup() {
        val (cps, dps) = bothPrefs()
        cpsDisk[JitRef.KEY_AUTO] = false
        dpsDisk[JitRef.KEY_AUTO] = false
        cps.commitOk = false
        dps.commitOk = false
        val s = store(cps, dps)
        s.putSnapshot(false, snapTrue, mk(want = false))
        s.remove(JitRef.KEY_PEDIDO)
        s.remove(JitRef.KEY_RESTORE)
        assertEquals("прежняя настройка драйвера на месте", false, cpsDisk[JitRef.KEY_AUTO])
        assertEquals(false, dpsDisk[JitRef.KEY_AUTO])
    }

    // ─────────────────── iter38 §1.3: прежние значения, откат, отметка операции ───────────────────

    @Test
    fun snapshotStateCapturesPreviousValuesAndAbsence() {
        cpsDisk[JitRef.KEY_AUTO] = false
        cpsDisk[JitRef.KEY_RESTORE] = "wifi=0"
        dpsDisk[JitRef.KEY_PEDIDO] = true
        val (cps, dps) = bothPrefs()
        val prev = store(cps, dps).readSnapshotState()
        assertEquals("оба хранилища в порядке чтения", listOf("cps", "dps"), prev.map { it.name })
        val cpsKeys = prev[0].keys.associateBy { it.key }
        assertEquals("прежнее значение ATO", false, cpsKeys.getValue(JitRef.KEY_AUTO).bool)
        assertEquals("прежний снимок", "wifi=0", cpsKeys.getValue(JitRef.KEY_RESTORE).str)
        assertEquals("PEDIDO отсутствовал", false, cpsKeys.getValue(JitRef.KEY_PEDIDO).present)
        assertEquals("отметки не было", false, cpsKeys.getValue(JitRef.KEY_MARK).present)
        val dpsKeys = prev[1].keys.associateBy { it.key }
        assertEquals(true, dpsKeys.getValue(JitRef.KEY_PEDIDO).bool)
        assertEquals("снимка в DPS не было", false, dpsKeys.getValue(JitRef.KEY_RESTORE).present)
    }

    @Test
    fun rollbackRestoresAbsentKeysByRemovalAndPresentOnesByValue() {
        cpsDisk[JitRef.KEY_AUTO] = false
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        val prev = s.readSnapshotState()
        s.putSnapshot(true, snapTrue, mk())          // нашла запись: ATO стал true, появились ключи
        assertEquals(true, cpsDisk[JitRef.KEY_AUTO])
        val r = s.restorePrevious(prev)
        assertEquals(2, r.attempted)
        assertEquals(2, r.ok)
        assertEquals("прежнее значение ATO возвращено", false, cpsDisk[JitRef.KEY_AUTO])
        assertFalse("наши ключи убраны", cpsDisk.containsKey(JitRef.KEY_PEDIDO))
        assertFalse(cpsDisk.containsKey(JitRef.KEY_RESTORE))
        assertFalse(cpsDisk.containsKey(JitRef.KEY_MARK))
        assertTrue("DPS тоже очищен от нашей записи", dpsDisk.isEmpty())
    }

    @Test
    fun rollbackOnStoreThatRefusesCommitsIsReported() {
        cpsDisk[JitRef.KEY_AUTO] = false
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        val prev = s.readSnapshotState()
        cps.commitOk = false
        val r = s.restorePrevious(prev)
        assertEquals(1, r.ok)
        assertEquals("отказ виден по имени хранилища", true, r.detail.contains("cps"))
        assertEquals("данные на «диске» CPS не изменились", false, cpsDisk[JitRef.KEY_AUTO])
    }

    @Test
    fun updateMarkChangesOnlyTheMarkKey() {
        cpsDisk[JitRef.KEY_AUTO] = false
        dpsDisk[JitRef.KEY_AUTO] = false
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        s.putSnapshot(true, snapTrue, mk(state = JitRef.OpMark.SENDING))
        cpsDisk[JitRef.KEY_AUTO] = false       // значение после записи снимка
        dpsDisk[JitRef.KEY_AUTO] = false
        val r = s.updateMark(mk(state = JitRef.OpMark.REPLIED))
        assertEquals(2, r.ok)
        for (disk in listOf(cpsDisk, dpsDisk)) {
            assertEquals(mk(state = JitRef.OpMark.REPLIED).wire(), disk[JitRef.KEY_MARK])
            assertEquals("пользовательский ключ не тронут", false, disk[JitRef.KEY_AUTO])
            assertEquals(true, disk[JitRef.KEY_PEDIDO])
        }
    }

    @Test
    fun updateMarkFailureIsReported() {
        val (cps, dps) = bothPrefs()
        cps.commitOk = false
        val r = store(cps, dps).updateMark(mk(state = JitRef.OpMark.UNDEFINED))
        assertEquals(1, r.ok)
        assertEquals(2, r.attempted)
        assertTrue(r.detail.contains("cps"))
    }

    @Test
    fun snapshotFromPreviousVersionHasNoMarkAndIsDetectable() {
        // Так выглядят данные 1.0.60: снимок есть, отметки нет.
        cpsDisk[JitRef.KEY_RESTORE] = "wifi=1,ap=0"
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        assertNull("отметки нет — операция не опознана", JitRef.OpMark.parse(s.getString(JitRef.KEY_MARK)))
        assertEquals("старый формат читается", "wifi=1,ap=0", s.readRestore().value)
    }

    @Test
    fun markIsWrittenInTheSameEditorAsTheReferenceKeys() {
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        s.putSnapshot(true, snapTrue, mk())
        assertEquals("один commit на хранилище", 1, cps.writes.get())
        assertEquals(1, dps.writes.get())
    }

    // ─────────────────── iter38 §4: обновление 1.0.60 → 1.0.61 на реальном хранилище ───────────────────

    @Test
    fun legacySnapshotFrom1060IsNotContinuedByTheRealStore() {
        // Так на диске выглядят данные прошлой версии: снимок и намерение есть, отметки операции нет.
        cpsDisk[JitRef.KEY_RESTORE] = "wifi=1,ap=1"
        cpsDisk[JitRef.KEY_PEDIDO] = true
        dpsDisk[JitRef.KEY_RESTORE] = "wifi=1,ap=1"
        val (cps, dps) = bothPrefs()
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store(cps, dps), net, sched, {})
        assertEquals("legacy_or_unknown", out.reason)
        assertFalse(out.scheduled)
        assertTrue(sched.scheduled.isEmpty())
        assertTrue("ни одной команды/запроса", net.requests.isEmpty())
        assertEquals("данные сохранены на «диске»", "wifi=1,ap=1",
            JitFake.Prefs(cpsDisk).getString(JitRef.KEY_RESTORE, null))
        assertEquals("намерение тоже не тронуто", true, JitFake.Prefs(cpsDisk).getBoolean(JitRef.KEY_PEDIDO, false))
    }

    @Test
    fun newOperationIsContinuedAfterRestartAndRemovedOnlyAfterRestore() {
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        val mark = mk(op = "op-1", state = JitRef.OpMark.REPLIED)
        s.putSnapshot(true, snapTrue, mark)
        s.updateMark(mark)

        // «Новый процесс» после перезапуска фреймворка читает то же с диска.
        val fresh = store(JitFake.Prefs(cpsDisk), JitFake.Prefs(dpsDisk))
        val net = JitFake.Net(wifi = false)
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(fresh, net, sched, {})
        assertEquals("scheduled", out.reason)
        assertEquals("op-1", out.mark?.op)
        assertTrue("снимок на месте до возврата", fresh.contains(JitRef.KEY_RESTORE))

        sched.drain()
        assertEquals(listOf("wifi=true"), net.requests)
        assertFalse("после возврата снимок снят и на «диске»",
            JitFake.Prefs(cpsDisk).contains(JitRef.KEY_RESTORE))
        assertFalse(JitFake.Prefs(cpsDisk).contains(JitRef.KEY_MARK))
    }

    @Test
    fun upgradeWithNoSnapshotWritesNothing() {
        val (cps, dps) = bothPrefs()
        val net = JitFake.Net(wifi = true)
        val sched = JitFake.Scheduler()
        val out = JitOp.onProcessStart(store(cps, dps), net, sched, {})
        assertEquals("no_snapshot", out.reason)
        assertTrue(sched.scheduled.isEmpty())
        assertTrue(cpsDisk.isEmpty())
        assertTrue(dpsDisk.isEmpty())
        assertTrue(net.requests.isEmpty())
    }
}
