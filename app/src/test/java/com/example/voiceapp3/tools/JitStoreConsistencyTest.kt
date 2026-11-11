package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Согласованность записи снимка между CPS и DPS (REVIEW 3-го захода, п.3).
 *
 * Проверяется правило `JitNet.judgeWrite` — то, по которому настоящий `AndroidStore` решает,
 * подтверждена ли запись. Сценарии: недоступное хранилище, `commit=false`, расходящиеся значения,
 * приоритетное чтение, оба хранилища недоступны.
 *
 * ГРАНИЦА ЧЕСТНОСТИ: это не прогон `SharedPreferences` на Android (в песочнице нет ни эмулятора, ни
 * Robolectric). Проверяется наша логика подтверждения; поведение конкретного `commit()` на устройстве
 * остаётся непроверенным и в отчёте помечено.
 */
class JitStoreConsistencyTest {

    private fun judge(
        value: String?,
        cpsCommit: Boolean = true,
        dpsCommit: Boolean = true,
        cpsAvailable: Boolean = true,
        dpsAvailable: Boolean = true,
        readFull: String? = value,
        readCps: String? = value,
        readDps: String? = value
    ) = JitNet.judgeWrite(value, cpsCommit, dpsCommit, cpsAvailable, dpsAvailable, readFull, readCps, readDps)

    @Test
    fun bothStoresWrittenAndReadBack() {
        val out = judge("v=1;phase=SENT")
        assertTrue(out.confirmed)
        assertEquals("ok", out.reason)
    }

    @Test
    fun commitFalseButReadbackOkIsAcceptedWithWarning() {
        // Именно этот случай REVIEW: CPS commit=false, DPS commit=true, читается из CPS.
        val out = judge("v=1", cpsCommit = false, dpsCommit = true)
        assertTrue("значение читается из ОБОИХ хранилищ — снимок безопасен", out.confirmed)
        assertEquals("readback_ok_commit_false", out.reason)
    }

    @Test
    fun divergentStoresAreNotConfirmed() {
        val out = judge("new", readCps = "new", readDps = "old", readFull = "new")
        assertFalse("второе хранилище отдаёт старое значение ⇒ не подтверждено", out.confirmed)
        assertEquals("readback_mismatch", out.reason)
    }

    @Test
    fun priorityReadMismatchIsNotConfirmed() {
        val out = judge("new", readCps = "new", readDps = "new", readFull = "old")
        assertFalse("приоритетное чтение вернуло не то, что записали", out.confirmed)
        assertEquals("priority_read_mismatch", out.reason)
    }

    @Test
    fun oneStoreUnavailableStillConfirmsThroughTheOther() {
        val out = judge("v=1", cpsAvailable = false, cpsCommit = false, readCps = null)
        assertTrue("достаточно одного хранилища с подтверждённым значением", out.confirmed)
        val out2 = judge("v=1", dpsAvailable = false, dpsCommit = false, readDps = null)
        assertTrue(out2.confirmed)
    }

    @Test
    fun bothStoresUnavailableIsNeverConfirmed() {
        val out = judge("v=1", cpsAvailable = false, dpsAvailable = false, readCps = null, readDps = null,
            readFull = null)
        assertFalse(out.confirmed)
        assertEquals("no_store", out.reason)
    }

    @Test
    fun nullValueMeansKeyMustBeGoneFromBoth() {
        val out = judge(null, readFull = null, readCps = null, readDps = null)
        assertTrue("удаление подтверждено, когда ключа нет ни в одном хранилище", out.confirmed)
        val stale = judge(null, readFull = null, readCps = null, readDps = "old")
        assertFalse("в DPS осталось старое значение — удаление не подтверждено", stale.confirmed)
    }

    @Test
    fun snapshotWriteGoesThroughTheSameRule() {
        // Сквозная проверка на подставном хранилище: снимок пишется один раз и читается обратно.
        val store = JitFake.Store()
        val op = JitRef.Op(JitRef.Phase.ARMED, true, JitRef.Net(true, true), "id-1234-abcd", 5L)
        assertTrue(store.putStringConfirmed(JitRef.KEY_OP, op.encode()))
        assertEquals(op, JitRef.decodeOp(store.getString(JitRef.KEY_OP)))
        // Отказ записи в оба хранилища не должен выглядеть подтверждением.
        val broken = JitFake.Store().apply { cpsFails = true; dpsFails = true; commitReportsFalse = true }
        assertFalse(broken.putStringConfirmed(JitRef.KEY_OP, op.encode()))
    }
}
