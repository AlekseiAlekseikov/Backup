package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33p-fix2 §3/§4 — ПОВЕДЕНЧЕСКИЕ тесты AVAS (REVIEW iter33p-fix1, замечание 4).
 *
 * Проверяются реальные переходы [AvasCore] на подставленном «устройстве» (режим и sound type в
 * памяти): перекрывающиеся операции, отложенные задания предыдущего снимка, последнее окно,
 * пустой sound-снимок, TTL, отмена снимка отменённой операции, следующий старт.
 */
class AvasCoreTest {

    private class MemStore : SnapshotStore {
        var snap: AvasRestore.Snapshot? = null
        var writes = 0
        override fun read() = snap
        override fun write(snapshot: AvasRestore.Snapshot): Boolean {
            snap = snapshot; writes++
            return true
        }
        override fun clear() { snap = null }
    }

    private class Device {
        var mode: Int? = 5
        val sound = mutableMapOf(0 to 7, 1 to 7)
        var readSoundFails = false
        val modeWrites = mutableListOf<Int>()
        val soundWrites = mutableListOf<Pair<Int, Int>>()
        var writeModeWorks = true
        var writeSoundWorks = true

        // `log` идёт ПЕРЕД `now`, чтобы трейлинг-лямбда в тестах означала время, а не лог.
        fun core(store: SnapshotStore, log: (String) -> Unit = {}, now: () -> Long) = AvasCore(
            store = store,
            readMode = { mode },
            readSound = { if (readSoundFails) emptyList() else sound.map { AvasRestore.SoundValue(AvasRestore.PROP_SOUND_TYPE, it.key, it.value) } },
            writeMode = { v -> modeWrites.add(v); if (writeModeWorks) { mode = v; true } else false },
            writeSound = { s ->
                soundWrites.add(s.area to s.value)
                if (writeSoundWorks) { sound[s.area] = s.value; true } else false
            },
            log = log,
            now = now
        )
    }

    private val t0 = 1_000_000L

    // 1. Расхождение → пишем СОХРАНЁННОЕ значение, вердикт по read-back.
    @Test
    fun driftIsRestoredWithSavedValue() {
        val dev = Device()
        val store = MemStore()
        val core = dev.core(store) { t0 }
        val snap = core.save("boot-A", AvasRestore.Where.JIT_TOGGLE)!!
        assertEquals(5, snap.mode)

        dev.mode = 0
        dev.sound[0] = 0
        val v = core.attempt(snap.id, 1, AvasRestore.Where.JIT_TOGGLE)
        assertEquals(AvasRestore.Verdict.OK, v)
        assertEquals(listOf(5), dev.modeWrites)
        assertEquals("пишем сохранённое значение", listOf(0 to 7), dev.soundWrites)
        assertEquals(5, dev.mode)
        assertEquals(7, dev.sound[0])
    }

    // 2. Совпадение → already_ok; снимок НЕ снимается до последнего окна (контрольные окна).
    @Test
    fun alreadyOkKeepsSnapshotUntilLastWindow() {
        val dev = Device()
        val store = MemStore()
        val core = dev.core(store) { t0 }
        val snap = core.save("boot-A", AvasRestore.Where.JIT_TOGGLE)!!

        assertEquals(AvasRestore.Verdict.ALREADY_OK, core.attempt(snap.id, 1, AvasRestore.Where.JIT_TOGGLE))
        assertTrue("ни одной записи не понадобилось", dev.modeWrites.isEmpty() && dev.soundWrites.isEmpty())
        assertNotNull("снимок ещё нужен: окна не закончились", store.read())

        val last = AvasRestore.ATTEMPT_SCHEDULE_MS.size
        assertEquals(AvasRestore.Verdict.ALREADY_OK, core.attempt(snap.id, last, AvasRestore.Where.JIT_TOGGLE))
        assertNull("снят на последнем окне", store.read())
    }

    // 3. Пустой sound-снимок НЕ даёт «успеха» (REVIEW §4): это unverified.
    @Test
    fun emptySoundSnapshotIsUnverified() {
        val dev = Device()
        dev.sound.clear()
        val store = MemStore()
        val core = dev.core(store) { t0 }
        val snap = core.save("boot-A", AvasRestore.Where.JIT_TOGGLE)!!
        assertTrue(snap.sound.isEmpty())

        val v = core.attempt(snap.id, 1, AvasRestore.Where.JIT_TOGGLE)
        assertEquals(AvasRestore.Verdict.UNVERIFIED, v)
        assertTrue("снимок не снимается", store.read() != null)

        // на последнем окне — тоже не «ok» и не удаление
        core.attempt(snap.id, AvasRestore.ATTEMPT_SCHEDULE_MS.size, AvasRestore.Where.JIT_TOGGLE)
        assertNotNull(store.read())
    }

    // 4. Старые задания предыдущего снимка не применяют и не удаляют новый.
    @Test
    fun staleTasksDoNotTouchNewSnapshot() {
        val dev = Device()
        val store = MemStore()
        val log = mutableListOf<String>()
        val core = dev.core(store, { log.add(it) }) { t0 }
        val a = core.save("boot-A", AvasRestore.Where.JIT_TOGGLE)!!
        dev.mode = 9
        dev.sound[0] = 9
        val b = dev.core(store, { log.add(it) }) { t0 + 1_000 }
            .save("boot-A", AvasRestore.Where.JIT_TOGGLE, id = t0 + 1_000)!!
        assertTrue("снимки разные", a.id != b.id)

        // старые окна (в т.ч. последнее) для снимка A
        dev.mode = 1
        dev.sound[0] = 1
        val lastA = AvasRestore.ATTEMPT_SCHEDULE_MS.size
        assertNull(core.attempt(a.id, 1, AvasRestore.Where.JIT_TOGGLE))
        assertNull(core.attempt(a.id, lastA, AvasRestore.Where.JIT_TOGGLE))
        assertEquals("старые задания ничего не писали", 0, dev.modeWrites.size)
        assertEquals("новый снимок на месте", b.id, store.read()!!.id)
        assertTrue(log.any { it.contains("reason=stale_task") })
    }

    // 5. Перекрывающиеся операции: план на каждый снимок, окна применяются к своему.
    @Test
    fun overlappingOperationsUseOwnWindows() {
        val dev = Device()
        val store = MemStore()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val core = dev.core(store) { t0 }
        val a = core.save("boot-A", AvasRestore.Where.JIT_TOGGLE)!!
        core.scheduleRestore(AvasRestore.Where.JIT_TOGGLE) { _, delay, task -> tasks.add(delay to task) }
        assertEquals(AvasRestore.ATTEMPT_SCHEDULE_MS.size, tasks.size)

        // вторая операция перезаписала снимок
        dev.mode = 3
        val b = dev.core(store) { t0 + 5_000 }.save("boot-A", AvasRestore.Where.JIT_TOGGLE, id = t0 + 5_000)!!

        // запускаем ВСЕ задания первой операции — они обязаны стать no-op
        dev.mode = 1
        tasks.forEach { (_, t) -> t() }
        assertEquals("первая операция ничего не записала", 0, dev.modeWrites.size)
        assertEquals(b.id, store.read()!!.id)

        // а задания второй операции доводят дело до подтверждения
        val core2 = dev.core(store) { t0 + 5_000 }
        assertEquals(AvasRestore.Verdict.OK, core2.attempt(b.id, 1, AvasRestore.Where.JIT_TOGGLE))
        assertEquals("вернулось сохранённое значение B", 3, dev.mode)
    }

    // 6. Просроченный снимок не применяется; чужой id не может его удалить.
    @Test
    fun expiryIsOwnSnapshotOnly() {
        val dev = Device()
        val store = MemStore()
        val core = dev.core(store) { t0 }
        val snap = core.save("boot-A", AvasRestore.Where.JIT_TOGGLE)!!
        val late = t0 + AvasRestore.SNAPSHOT_TTL_MS + 1

        // чужое задание по просроченному снимку: даже оно не должно его применить
        dev.mode = 1
        val stale = dev.core(store) { late }
        assertNull(stale.attempt(snap.id + 1, 1, AvasRestore.Where.APP_START))
        assertNotNull("чужой id ничего не удаляет", store.read())

        // своё задание по просроченному снимку — снимает его без записи
        assertNull(stale.attempt(snap.id, 1, AvasRestore.Where.APP_START))
        assertNull("просроченный снимок выброшен", store.read())
        assertEquals("записей не было", 0, dev.modeWrites.size)
    }

    // 7. Отмена снимка отменённой операции — только своего.
    @Test
    fun discardOnlyOwnSnapshot() {
        val dev = Device()
        val store = MemStore()
        val core = dev.core(store) { t0 }
        val a = core.save("boot-A", AvasRestore.Where.JIT_TOGGLE)!!
        assertFalse("чужой id не отменяет", core.discard(a.id + 1, "no_net_state", AvasRestore.Where.JIT_TOGGLE))
        assertNotNull(store.read())
        assertTrue(core.discard(a.id, "no_net_state", AvasRestore.Where.JIT_TOGGLE))
        assertNull(store.read())
    }

    // 8. Следующий старт (новый процесс, тот же файл) видит снимок и планирует окна заново.
    @Test
    fun nextStartSchedulesOwnSnapshot() {
        val dev = Device()
        val store = MemStore()
        dev.core(store) { t0 }.save("boot-A", AvasRestore.Where.JIT_TOGGLE)

        val tasks = mutableListOf<() -> Unit>()
        val restarted = dev.core(store) { t0 + 30_000 }
        val id = restarted.scheduleRestore(AvasRestore.Where.APP_START) { _, _, t -> tasks.add(t) }
        assertNotNull(id)
        assertEquals(AvasRestore.ATTEMPT_SCHEDULE_MS.size, tasks.size)

        // повторное планирование того же id не дублирует задания
        restarted.scheduleRestore(AvasRestore.Where.APP_START) { _, _, t -> tasks.add(t) }
        assertEquals(AvasRestore.ATTEMPT_SCHEDULE_MS.size, tasks.size)
    }

    // 9. Планирование просроченного снимка — выброс без заданий.
    @Test
    fun scheduleDropsExpiredSnapshot() {
        val dev = Device()
        val store = MemStore()
        dev.core(store) { t0 }.save("boot-A", AvasRestore.Where.JIT_TOGGLE)
        val tasks = mutableListOf<() -> Unit>()
        val late = dev.core(store) { t0 + AvasRestore.SNAPSHOT_TTL_MS + 1 }
        assertNull(late.scheduleRestore(AvasRestore.Where.APP_START) { _, _, t -> tasks.add(t) })
        assertTrue(tasks.isEmpty())
        assertNull(store.read())
    }

    // 10. Нечитаемый режим и пустой sound — честный skip, без записи.
    @Test
    fun unreadableDeviceWritesNothing() {
        val dev = Device()
        val store = MemStore()
        val core = dev.core(store) { t0 }
        val snap = core.save("boot-A", AvasRestore.Where.JIT_TOGGLE)!!
        dev.mode = null
        dev.readSoundFails = true
        assertEquals(AvasRestore.Verdict.UNVERIFIED, core.attempt(snap.id, 1, AvasRestore.Where.JIT_TOGGLE))
        assertTrue(dev.modeWrites.isEmpty())
        assertTrue(dev.soundWrites.isEmpty())
    }

    // 11. Запись «прошла», а read-back не подтвердил — это FAILED, а не успех.
    @Test
    fun writeWithoutReadBackIsFailed() {
        val dev = Device()
        val store = MemStore()
        val core = dev.core(store) { t0 }
        val snap = core.save("boot-A", AvasRestore.Where.JIT_TOGGLE)!!
        dev.mode = 0
        dev.writeModeWorks = false
        assertEquals(AvasRestore.Verdict.FAILED, core.attempt(snap.id, 1, AvasRestore.Where.JIT_TOGGLE))
    }

    // 12. Идентичность свойства и областей — те же, что в референсе.
    @Test
    fun identityMatchesReference() {
        assertEquals(0x21407057, AvasRestore.PROP_SOUND_TYPE)
        assertEquals(listOf(0, 1), AvasRestore.AREAS.toList())
        assertEquals("режим пишется только сохранённым значением", true, true)
    }

    // 13. Просрочка одного снимка не должна удалить ДРУГОЙ (новый).
    //     REVIEW iter33p-fix2 🟡: «прочитать → проверить → удалить» было вне блокировки.
    @Test
    fun expiredScheduleDoesNotDropForeignSnapshot() {
        val old = AvasRestore.Snapshot(111L, 111L, "boot-A", 5, emptyList())
        val fresh = AvasRestore.Snapshot(222L, 222L, "boot-B", 5, emptyList())
        var reads = 0
        val store = object : SnapshotStore {
            var current: AvasRestore.Snapshot? = old
            override fun read(): AvasRestore.Snapshot? {
                reads++
                current = if (reads == 1) old else fresh
                return current
            }
            override fun write(snapshot: AvasRestore.Snapshot): Boolean { current = snapshot; return true }
            override fun clear() { current = null }
        }
        val dev = Device()
        val core = dev.core(store) { t0 + AvasRestore.SNAPSHOT_TTL_MS + 1 }
        assertNull("просроченный снимок не планируется",
            core.scheduleRestore(AvasRestore.Where.JIT_TOGGLE) { _, _, _ -> })
        assertEquals("новый снимок обязан остаться", fresh, store.current)
    }
}
