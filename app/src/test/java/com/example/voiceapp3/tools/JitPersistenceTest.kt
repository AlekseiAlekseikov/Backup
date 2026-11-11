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

    private val snapTrue = JitRef.WifiSnapshot(true)
    private val snapFalse = JitRef.WifiSnapshot(false)

    @Test
    fun successfulCommitIsConfirmedByReadbackOfAllThreeKeys() {
        val (cps, dps) = bothPrefs()
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue)
        assertEquals(2, w.attempted)
        assertEquals(2, w.committed)
        assertEquals(2, w.confirmed)
        assertTrue(w.stores.all { it.durable })
        // Все три ключа дошли до «диска» в каждом хранилище.
        for (disk in listOf(cpsDisk, dpsDisk)) {
            assertEquals(true, disk[JitRef.KEY_PEDIDO])
            assertEquals(true, disk[JitRef.KEY_AUTO])
            assertEquals("wifi=1", disk[JitRef.KEY_RESTORE])
        }
    }

    @Test
    fun commitFalseWithValueInMemoryIsNotConfirmed() {
        // Ровно тот дефект, который был: значение видно в памяти, но на «диск» не попало.
        val (cps, dps) = bothPrefs()
        cps.commitOk = false
        dps.commitOk = false
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue)
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
        val w = s.putSnapshot(true, snapTrue)
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
        val w = s.putSnapshot(true, snapTrue)
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
        val w = s.putSnapshot(true, snapTrue)
        assertEquals(1, w.attempted)
        assertEquals(1, w.confirmed)
        assertFalse("нет ложного 2/2", w.attempted == 2)
    }

    @Test
    fun noStoreAtAllMeansNothingWasWritten() {
        val s = store(null, null)
        val w = s.putSnapshot(true, snapTrue)
        assertEquals(0, w.attempted)
        assertEquals(0, w.confirmed)
        assertFalse(w.stores.any { it.durable })
    }

    @Test
    fun editExceptionInOneStoreIsReportedPerStore() {
        val (cps, dps) = bothPrefs()
        cps.throwOnEdit = true
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue)
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
        val w = s.putSnapshot(true, snapTrue)
        assertTrue(w.attempted >= 1)
        assertEquals("wifi=1", s.getString(JitRef.KEY_RESTORE))
    }

    @Test
    fun partialKeyWriteIsNotDurable() {
        val (cps, dps) = bothPrefs()
        cps.partialKeys = setOf(JitRef.KEY_AUTO)   // один из трёх ключей не записался
        val s = store(cps, dps)
        val w = s.putSnapshot(true, snapTrue)
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
        s.putSnapshot(true, snapFalse)
        // «Перезапуск»: новые объекты читают только «диск».
        val fresh = store(JitFake.Prefs(cpsDisk), JitFake.Prefs(dpsDisk))
        assertEquals("снимок пережил перезапуск в DPS", "wifi=0", fresh.readRestore().value)
        assertTrue(fresh.contains(JitRef.KEY_PEDIDO))
    }

    @Test
    fun snapshotSurvivesRestartAndIsRemovedBeforeRestore() {
        val (cps, dps) = bothPrefs()
        store(cps, dps).putSnapshot(true, snapTrue)

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
        s.putSnapshot(false, snapTrue)
        s.remove(JitRef.KEY_PEDIDO)
        s.remove(JitRef.KEY_RESTORE)
        assertEquals("прежняя настройка драйвера на месте", false, cpsDisk[JitRef.KEY_AUTO])
        assertEquals(false, dpsDisk[JitRef.KEY_AUTO])
    }
}
