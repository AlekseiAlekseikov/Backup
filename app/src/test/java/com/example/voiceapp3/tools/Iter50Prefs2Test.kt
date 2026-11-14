package com.example.voiceapp3.tools

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter50 §6.1 — «сохранено» подтверждается РЕАЛЬНЫМ результатом `commit()` той же копии,
 * а не совпадением чтения.
 *
 * Дефект на HEAD (Prefs2:168–193 и аналоги): `stores` считал успешные `commit()`, а `confirmed`
 * строился только по readback. `SharedPreferences.Editor.commit()` может вернуть `false` УЖЕ
 * после изменения in-memory состояния — поэтому оба commit могли вернуть false, а чтение в том
 * же процессе совпадало с запросом: `confirmed=true, stores=0`.
 *
 * Тест исполняет НАСТОЯЩИЙ код записи Prefs2 (`putBooleanCore`/`removeCore`/`putStringCore`/
 * `putBoolAndBumpCore` — те же функции, что вызывает боевой `putBooleanEx` под lock) на поддельных
 * `SharedPreferences`, которые моделируют:
 *  * `commit()==false` при уже обновлённой памяти;
 *  * отказ/исключение в одной копии при успехе другой;
 *  * частичное применение транзакции внутри одной копии;
 *  * равные ревизии с разными значениями (конфликт);
 *  * недоступную вторую копию (direct boot).
 *
 * Чего тест НЕ доказывает: поведение настоящего Android-файла настроек, реальный direct boot,
 * межпроцессную гонку (это [ProcLock], отдельный уровень) и durability при отключении питания.
 */
class Iter50Prefs2Test {

    private companion object {
        const val K = "test_key"
        const val GEN = "test_gen"
    }

    /** Поддельные SharedPreferences: память и результат commit моделируются независимо. */
    private class FakePrefs(
        val name: String,
        var commitResult: Boolean = true,
        var throwOnCommit: Boolean = false,
        /** Сколько операций редактора реально применить при commit (null — все). */
        var applyFirst: Int? = null
    ) : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()
        private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        private val removed = Any()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String, defValue: String?): String? =
            if (map.containsKey(key)) map[key] as String? else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            if (map.containsKey(key)) map[key] as MutableSet<String>? else defValues

        override fun getInt(key: String, defValue: Int): Int =
            if (map.containsKey(key)) map[key] as Int else defValue

        override fun getLong(key: String, defValue: Long): Long =
            if (map.containsKey(key)) map[key] as Long else defValue

        override fun getFloat(key: String, defValue: Float): Float =
            if (map.containsKey(key)) map[key] as Float else defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            if (map.containsKey(key)) map[key] as Boolean else defValue

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            listeners.remove(listener)
        }

        private inner class Editor : SharedPreferences.Editor {
            private val ops = mutableListOf<Pair<String, Any?>>()

            override fun putString(key: String, value: String?) = apply { ops += key to value }
            override fun putStringSet(key: String, values: MutableSet<String>?) =
                apply { ops += key to values }

            override fun putInt(key: String, value: Int) = apply { ops += key to value }
            override fun putLong(key: String, value: Long) = apply { ops += key to value }
            override fun putFloat(key: String, value: Float) = apply { ops += key to value }
            override fun putBoolean(key: String, value: Boolean) = apply { ops += key to value }
            override fun remove(key: String) = apply { ops += key to removed }
            override fun clear() = apply { ops += "*" to removed }

            /** Имитация поведения платформы: память меняется ДО возврата результата. */
            private fun applyToMemory(limit: Int?) {
                val n = limit ?: ops.size
                for ((i, op) in ops.withIndex()) {
                    if (i >= n) break
                    val (k, v) = op
                    when {
                        k == "*" -> map.clear()
                        v === removed -> map.remove(k)
                        else -> map[k] = v
                    }
                }
            }

            override fun commit(): Boolean {
                if (throwOnCommit) throw IllegalStateException("disk I/O error")
                applyToMemory(applyFirst)
                return commitResult
            }

            override fun apply() {
                applyToMemory(applyFirst)
            }
        }
    }

    private fun copies(a: FakePrefs, b: FakePrefs?): List<Prefs2.Copy> =
        listOf(Prefs2.Copy("cps", a), Prefs2.Copy("dps", b))

    @Test
    fun commitFalseWithMatchingReadbackIsNotConfirmed() {
        // Оба commit вернули false, но память уже изменилась → чтение совпадёт с запросом.
        val a = FakePrefs("cps", commitResult = false)
        val b = FakePrefs("dps", commitResult = false)
        val save = Prefs2.putBooleanCore(copies(a, b), K, true, rev = 1L, whoTag = "test")
        assertEquals("запись не сохранена на диск", 0, save.stores)
        assertEquals("но прочиталась из памяти", 2, save.readback)
        assertFalse("confirmed=true при stores=0 — это и был дефект", save.confirmed)
        assertEquals(-1L, save.rev)
        assertTrue("причина названа: ${save.reason}", save.reason.startsWith("commit_false_readback_only"))
        // Значение при этом физически в памяти — тест не должен «притворяться», что его нет.
        assertTrue(a.map[K] == true)
    }

    @Test
    fun oneCopyCommittedIsPartialNotDurable() {
        val a = FakePrefs("cps", commitResult = true)
        val b = FakePrefs("dps", commitResult = false)
        val save = Prefs2.putBooleanCore(copies(a, b), K, true, rev = 3L, whoTag = "test")
        assertEquals(1, save.stores)
        assertTrue("одна копия записалась — это подтверждено", save.confirmed)
        assertEquals(3L, save.rev)
        assertTrue("честно обозначено как частичное", save.partial)
        assertEquals("partial_stores:1", save.reason)
    }

    @Test
    fun exceptionInOneCopyDoesNotConfirmTheOther() {
        val a = FakePrefs("cps", throwOnCommit = true)
        val b = FakePrefs("dps", commitResult = false)
        b.map[K] = true // вторая копия «уже видит» значение, но commit не удался
        val save = Prefs2.putBooleanCore(copies(a, b), K, true, rev = 2L, whoTag = "test")
        assertEquals(0, save.stores)
        assertFalse("исключение в копии не даёт права заявлять успех", save.confirmed)
        assertEquals("commit_false_readback_only:1", save.reason)
    }

    @Test
    fun equalRevisionWithDifferentValuesIsConflict() {
        // Копии уже содержат РАВНУЮ ревизию с разными значениями (писавший параллельно процесс
        // успел записать, мы — нет: applyFirst=0 моделирует commit, который ничего не применил).
        val a = FakePrefs("cps", commitResult = false, applyFirst = 0)
        val b = FakePrefs("dps", commitResult = false, applyFirst = 0)
        a.map[K] = true; a.map["$K#rev"] = 7L
        b.map[K] = false; b.map["$K#rev"] = 7L
        val save = Prefs2.putBooleanCore(copies(a, b), K, true, rev = 7L, whoTag = "test")
        assertTrue(save.conflict)
        assertFalse("порядок записей неизвестен — подтверждать нельзя", save.confirmed)
        assertEquals("conflict_same_rev", save.reason)
    }

    @Test
    fun unavailableSecondCopyIsNotASecondStore() {
        val a = FakePrefs("cps", commitResult = true)
        val save = Prefs2.putBooleanCore(copies(a, null), K, true, rev = 1L, whoTag = "test")
        assertTrue(save.confirmed)
        assertEquals("физически одна копия — один store", 1, save.stores)
        assertTrue("direct boot: обозначено частичным", save.partial)
    }

    @Test
    fun removeIsConfirmedOnlyByTombstoneInCommittedCopy() {
        val a = FakePrefs("cps", commitResult = true)
        val b = FakePrefs("dps", commitResult = false)
        a.map[K] = true; b.map[K] = true
        val save = Prefs2.removeCore(copies(a, b), K, rev = 5L, whoTag = "test")
        assertTrue("tombstone + отсутствие значения в записавшейся копии", save.confirmed)
        assertEquals(1, save.stores)
        assertFalse("в неписавшейся копии ключ жив", b.map.containsKey(K))
        assertTrue("tombstone стоит", a.map["$K#del"] == true)
        assertFalse("значения в подтверждённой копии нет", a.map.containsKey(K))
    }

    @Test
    fun removeWithoutCommitIsNotConfirmedEvenIfMemoryIsClean() {
        val a = FakePrefs("cps", commitResult = false)
        val b = FakePrefs("dps", commitResult = false)
        a.map[K] = true; b.map[K] = true
        val save = Prefs2.removeCore(copies(a, b), K, rev = 1L, whoTag = "test")
        assertFalse("память очищена, но на диск не легло", save.confirmed)
        assertEquals(0, save.stores)
        assertEquals("commit_false_readback_only:2", save.reason)
        assertFalse(a.map.containsKey(K))
    }

    @Test
    fun stringWriteFollowsTheSameRule() {
        val a = FakePrefs("cps", commitResult = false)
        val b = FakePrefs("dps", commitResult = true)
        val save = Prefs2.putStringCore(copies(a, b), K, "zh", rev = 4L, whoTag = "test")
        assertTrue(save.confirmed)
        assertEquals(1, save.stores)
        assertEquals("partial_stores:1", save.reason)
        assertEquals("zh", b.map[K])
    }

    @Test
    fun stringConflictIsNotConfirmed() {
        val a = FakePrefs("cps", commitResult = false, applyFirst = 0)
        val b = FakePrefs("dps", commitResult = false, applyFirst = 0)
        a.map[K] = "ru"; a.map["$K#rev"] = 9L
        b.map[K] = "en"; b.map["$K#rev"] = 9L
        val save = Prefs2.putStringCore(copies(a, b), K, "ru", rev = 9L, whoTag = "test")
        assertTrue(save.conflict)
        assertFalse(save.confirmed)
    }

    @Test
    fun transactionNeedsBothPartsInTheSameCopy() {
        // cps применяет только intent (поколение «теряется»), dps — ничего не применил,
        // хотя оба commit() вернули true.
        val a = FakePrefs("cps", commitResult = true, applyFirst = 4)
        val b = FakePrefs("dps", commitResult = true, applyFirst = 0)
        val tx = Prefs2.putBoolAndBumpCore(
            copies(a, b), K, true, GEN, genNew = 8L, revB = 2L, revL = 3L, whoTag = "test"
        )
        assertTrue("intent лёг в одну копию", tx.save.confirmed)
        assertFalse("поколение нигде не подтверждено", tx.genSave.confirmed)
        assertFalse(tx.confirmed)
        assertEquals("фиктивное поколение не выдаётся", -1L, tx.gen)
    }

    @Test
    fun transactionConfirmedWhenOneCopyHoldsBothParts() {
        val a = FakePrefs("cps", commitResult = true)
        val b = FakePrefs("dps", commitResult = false)
        val tx = Prefs2.putBoolAndBumpCore(
            copies(a, b), K, true, GEN, genNew = 8L, revB = 2L, revL = 3L, whoTag = "test"
        )
        assertTrue(tx.confirmed)
        assertEquals(8L, tx.gen)
        assertEquals(1, tx.save.stores)
        assertTrue("частично (одна копия) — обозначено", tx.save.partial)
    }

    @Test
    fun transactionWithFailingCommitsIsNotConfirmed() {
        val a = FakePrefs("cps", commitResult = false)
        val b = FakePrefs("dps", commitResult = false)
        val tx = Prefs2.putBoolAndBumpCore(
            copies(a, b), K, true, GEN, genNew = 2L, revB = 1L, revL = 1L, whoTag = "test"
        )
        assertFalse(tx.confirmed)
        assertEquals(-1L, tx.gen)
        assertEquals(0, tx.save.stores)
        assertEquals(0, tx.genSave.stores)
    }

    @Test
    fun generationNeverWrapsIntoOldValue() {
        assertEquals("нет предыдущего значения", 1L, Prefs2.nextGen(null))
        assertEquals("нулевое/мусорное значение", 1L, Prefs2.nextGen(0L))
        assertEquals(1L, Prefs2.nextGen(-5L))
        assertEquals(6L, Prefs2.nextGen(5L))
        assertEquals("переполнение не переходит в отрицательное", 1L, Prefs2.nextGen(Long.MAX_VALUE))
    }
}
