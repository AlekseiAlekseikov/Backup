package com.example.voiceapp3.tools

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter67 §K — две копии настроек: согласованное чтение и согласованное удаление.
 *
 * Живой случай: после правки только `cps` приложение выдало
 * `JIT_START stored=«ambiguous … detail=mark_unparsable cps:…;ts= dps:…;ts=1794577045082;state=sending»`
 * и блокировка стала бессрочной.
 */
class Iter67PairTest {

    /** Копия настроек в памяти: `commit()` можно «сломать», как на диске при отказе. */
    private class Store(
        private val commitOk: Boolean = true,
        private val throwOnCommit: Boolean = false
    ) : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()
        fun put(k: String, v: Any?) { map[k] = v }
        fun isEmpty() = map.isEmpty()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val ops = ArrayList<Pair<String, Any?>>()
            private val removes = ArrayList<String>()
            override fun putString(key: String?, value: String?) = apply { ops += key!! to value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { ops += key!! to values }
            override fun putInt(key: String?, value: Int) = apply { ops += key!! to value }
            override fun putLong(key: String?, value: Long) = apply { ops += key!! to value }
            override fun putFloat(key: String?, value: Float) = apply { ops += key!! to value }
            override fun putBoolean(key: String?, value: Boolean) = apply { ops += key!! to value }
            override fun remove(key: String?) = apply { removes += key!! }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean {
                if (throwOnCommit) throw IllegalStateException("disk gone")
                if (!commitOk) return false
                for ((k, v) in ops) map[k] = v
                for (k in removes) map.remove(k)
                return true
            }
            override fun apply() { commit() }
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    /** Реальные миллисекунды 2026 года: `OpMark.parse` отбрасывает значения вне диапазона. */
    private val T1 = 1_794_577_045_082L
    private val T2 = T1 + 9_000L

    private fun mark(op: String, ts: Long, state: String = JitRef.OpMark.SENDING) =
        JitRef.OpMark(op, ts, true, state).wire()

    // ───────────────────────── 1. Удаление в оба хранилища ─────────────────────────

    @Test
    fun `удаление уходит в оба хранилища`() {
        val a = Store(); val b = Store()
        a.put("jit_restore_marker", "x"); b.put("jit_restore_marker", "x")
        val r = OwnedRemover.removeOwnedEverywhereForTest(listOf(a, b), listOf("jit_restore_marker"))
        assertEquals("оба хранилища должны подтвердить commit", 2, r.ok)
        assertTrue("в cps осталось: ${a.map}", a.isEmpty())
        assertTrue("в dps осталось: ${b.map}", b.isEmpty())
        assertTrue(r.clean)
    }

    @Test
    fun `удаление всех ключей операции — один commit на хранилище`() {
        val a = Store(); val b = Store()
        for (k in JitRef.OWNED_KEYS) { a.put(k, "1"); b.put(k, "1") }
        val r = OwnedRemover.removeOwnedEverywhereForTest(listOf(a, b), emptyList())
        assertEquals("ключи операции: pedido, auto, restaurar, marker", 4, JitRef.OWNED_KEYS.size)
        assertEquals(2, r.ok)
        assertTrue("ключи операции должны исчезнуть из обоих: ${a.map} / ${b.map}",
            a.isEmpty() && b.isEmpty())
        assertTrue(r.clean)
        // Поколение намерения удалением операции НЕ трогается.
        assertFalse(JitRef.OWNED_KEYS.contains(JitRef.KEY_INTENT_SEQ))
    }

    @Test
    fun `commit=false не выдаётся за удаление`() {
        // fail-before: `commit()==false` считался успехом по факту вызова `remove`.
        val a = Store(commitOk = false); val b = Store()
        a.put(JitRef.KEY_MARK, "x"); b.put(JitRef.KEY_MARK, "x")
        val r = OwnedRemover.removeOwnedEverywhereForTest(listOf(a, b), listOf(JitRef.KEY_MARK))
        assertEquals("подтвердило одно хранилище", 1, r.ok)
        assertEquals("остаток назван", listOf(JitRef.KEY_MARK), r.still)
        assertFalse(r.clean)
    }

    @Test
    fun `исключение при commit не роняет удаление второй копии`() {
        val a = Store(throwOnCommit = true); val b = Store()
        a.put(JitRef.KEY_MARK, "x"); b.put(JitRef.KEY_MARK, "x")
        val r = OwnedRemover.removeOwnedEverywhereForTest(listOf(a, b), listOf(JitRef.KEY_MARK))
        assertEquals(1, r.ok)
        assertEquals(2, r.stores)
        assertTrue("dps должен быть очищен", b.isEmpty())
    }

    // ───────────────────────── 2. Чтение: класс расхождения ─────────────────────────

    @Test
    fun `ts_d различие при одном op не даёт вечной блокировки`() {
        val d = JitPair.classifyPair(
            PairOp("cps", "wifi=1", mark("x", T1)),
            PairOp("dps", "wifi=1", mark("x", T1 + 1_000))
        )
        assertEquals(PairDiag.TS_DIFF, d)
        // fail-before: blocked (AMBIGUOUS навсегда)
        assertTrue(JitPair.clickAllowedFor(PairDiag.TS_DIFF))
    }

    @Test
    fun `op_d различие блокирует`() {
        val d = JitPair.classifyPair(
            PairOp("cps", "wifi=1", mark("x", T1)),
            PairOp("dps", "wifi=1", mark("y", T1 + 1_000))
        )
        assertEquals(PairDiag.OP_DIFF, d)
        assertFalse(JitPair.clickAllowedFor(d))
    }

    @Test
    fun `state_d различие при одном op не блокирует`() {
        val d = JitPair.classifyPair(
            PairOp("cps", "wifi=1", mark("x", T1, JitRef.OpMark.REPLIED)),
            PairOp("dps", "wifi=1", mark("x", T1, JitRef.OpMark.UNDEFINED))
        )
        assertEquals(PairDiag.STATE_DIFF, d)
        assertTrue(JitPair.clickAllowedFor(d))
        // Из двух копий берётся «хуже»: неизвестный исход нельзя выдать за завершённый.
        assertEquals("dps", JitPair.worseOf(
            PairOp("cps", "wifi=1", mark("x", T1, JitRef.OpMark.REPLIED)),
            PairOp("dps", "wifi=1", mark("x", T1, JitRef.OpMark.UNDEFINED))
        ).name)
    }

    @Test
    fun `невалидная отметка в одной копии — берётся валидная`() {
        // Живой случай: `cps:…;ts=` (пусто) и `dps:…;ts=1794577045082;state=sending`.
        val d = JitPair.classifyPair(
            PairOp("cps", "wifi=1", "op=x;ts=;want=1;state=sending"),
            PairOp("dps", "wifi=1", mark("x", 1_794_577_045_082L))
        )
        assertEquals(PairDiag.INVALID_ONE, d)
        assertTrue(JitPair.clickAllowedFor(d))
        assertTrue(JitPair.line(d, "dps").startsWith("JIT_PAIR diag=invalid_one used=dps"))
    }

    @Test
    fun `обе копии пусты — это not_empty, а не конфликт`() {
        assertEquals(PairDiag.BOTH_EMPTY,
            JitPair.classifyPair(PairOp("cps", null, null), PairOp("dps", null, null)))
        assertEquals(PairDiag.ONE_EMPTY,
            JitPair.classifyPair(PairOp("cps", "wifi=1", mark("x", T1)), PairOp("dps", null, null)))
    }

    // ───────────────────────── 3. Боевой разбор assessStored ─────────────────────────

    @Test
    fun `assessStored не объявляет AMBIGUOUS при расхождении одной операции`() {
        val snap = "wifi=1"
        val assessed = JitRef.assessStored(
            listOf(
                JitRef.StoreOp("cps", snap, mark("op-1", 1_794_577_045_082L, JitRef.OpMark.REPLIED)),
                JitRef.StoreOp("dps", snap, mark("op-1", 1_794_577_045_083L, JitRef.OpMark.SENDING))
            )
        )
        // fail-before: AMBIGUOUS → `previous_ambiguous` → блокировка навсегда.
        assertTrue("состояние не должно быть терминальным: ${assessed.state}",
            assessed.state == JitRef.StoredState.UNDEFINED || assessed.state == JitRef.StoredState.PENDING)
        assertTrue("класс расхождения назван: ${assessed.detail}",
            assessed.detail.contains("diag="))
        assertTrue("согласование названо: ${assessed.detail}",
            assessed.detail.contains("repair=rewrite_worse"))
    }

    @Test
    fun `assessStored берёт самую свежую копию по времени`() {
        val snap = "wifi=1"
        val assessed = JitRef.assessStored(
            listOf(
                JitRef.StoreOp("cps", snap, mark("op-1", T1)),
                JitRef.StoreOp("dps", snap, mark("op-1", T2))
            )
        )
        assertEquals(T2, assessed.mark?.tsMs)
    }

    @Test
    fun `разные операции по-прежнему AMBIGUOUS`() {
        val snap = "wifi=1"
        val assessed = JitRef.assessStored(
            listOf(
                JitRef.StoreOp("cps", snap, mark("op-1", T1)),
                JitRef.StoreOp("dps", snap, mark("op-2", T2))
            )
        )
        assertEquals(JitRef.StoredState.AMBIGUOUS, assessed.state)
        assertTrue(assessed.detail.contains("op_conflict"))
    }

    @Test
    fun `очистка операции снимает и намерение, а не только снимок и отметку`() {
        val a = Store(); val b = Store()
        for (k in JitRef.OWNED_KEYS) { a.put(k, "1"); b.put(k, "1") }
        // Поколение намерения — НЕ ключ операции: удаление не должно его откатывать.
        a.put(JitRef.KEY_INTENT_SEQ, 7L); b.put(JitRef.KEY_INTENT_SEQ, 7L)
        val r = OwnedRemover.removeOwnedEverywhereForTest(listOf(a, b), JitRef.OWNED_KEYS)
        assertEquals(2, r.ok)
        assertTrue(r.clean)
        assertFalse("jit_pedido должен исчезнуть: ${a.map}", a.map.containsKey(JitRef.KEY_PEDIDO))
        assertEquals("поколение намерения не трогаем", 7L, a.map[JitRef.KEY_INTENT_SEQ])
        // fail-before: удалялись только `jit_restaurar` и `jit_restore_marker`, `jit_pedido`
        // оставался и следующая сверка снова печатала `JIT_PEDIDO kept`.
        val jar = java.io.File("app/src/main/java/com/example/voiceapp3/tools/JitNet.kt").readText()
        assertTrue("очистка операции не удаляет весь набор ключей",
            jar.contains("val keys = JitRef.OWNED_KEYS"))
    }
}
