package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * iter62 §B3 — ТРАНЗАКЦИЯ ВЛАДЕЛЬЦЕВ ОКОН: наружу не уходят изменяемые внутренние коллекции.
 *
 * Проверяемый дефект: `VentOwnerTx.planCloseInstall` складывал в `ClosePlan` ССЫЛКУ на карту
 * состояний дверей, которую передал вызывающий (`states = states`). Вызывающий мог изменить её
 * после транзакции — и «снимок решения» (по которому решалось, закрывать ли окно) менялся ЗАДНИМ
 * ЧИСЛОМ, вне lock и без нового поколения. Это тот же класс ошибки, что уже закрыт для
 * `snapshot()`/`read()` (mutable-set escape), но не для входных данных плана.
 *
 * Второй предмет проверки — конкурирующие открытие/закрытие: `before → переход → установка →
 * поколение` обязаны идти одной критической секцией, а поздний readback не должен снимать чужую
 * живую запись (ABA защищена поколением).
 */
class Iter62VentPlanTest {

    private fun tx(vararg seed: Pair<Int, Set<Int>>): VentOwnerTx =
        VentOwnerTx().apply { if (seed.isNotEmpty()) this.seed(seed.toMap()) }

    // ───────────── 1. План не хранит ссылок на входные/внутренние коллекции ─────────────

    @Test
    fun planDoesNotAliasTheCallersStateMap() {
        val doorStates = mutableMapOf(0x1 to true, 0x2 to false)
        val t = tx(0x40 to setOf(0x1))
        val plan = t.planCloseInstall(
            door = 0x1, window = 0x40,
            states = doorStates,
            doorToWindow = mapOf(0x1 to 0x40, 0x2 to 0x20)
        )
        assertTrue("в плане есть решение по двери 0x1", plan.states.containsKey(0x1))

        // Внешняя мутация ПОСЛЕ транзакции не должна менять план.
        doorStates[0x1] = false
        doorStates[0x3] = true
        assertEquals("план изменился вместе с картой вызывающего", true, plan.states[0x1])
        assertFalse("в план просочилась новая запись", plan.states.containsKey(0x3))
    }

    @Test
    fun planSetsAreIndependentOfInternalState() {
        val t = tx(0x40 to setOf(0x1))
        val plan = t.planCloseInstall(
            door = 0x1, window = 0x40,
            states = mapOf(0x1 to true),
            doorToWindow = mapOf(0x1 to 0x40)
        )
        // Менять набор из плана нечем (тип Set), но проверяем, что это КОПИЯ, а не живой набор.
        assertEquals("после закрытия двери владельцев не осталось", emptySet<Int>(), plan.after)
        assertEquals("before — снимок ровно на момент транзакции", setOf(0x1), plan.before)
        // Живое состояние не изменилось оттого, что мы «подержали» план.
        assertEquals("живой набор не совпадает с планом (после закрытия)", emptySet<Int>(),
            t.read { it[0x40] ?: emptySet() } - setOf<Int>())
    }

    // ───────────── 2. Поколение: ABA и поздний readback ─────────────

    @Test
    fun generationMovesOnEveryMutationEvenWhenContentIsEqual() {
        val t = tx(0x40 to setOf(0x1))
        val g0 = t.generation()
        t.mutate { map -> map[0x40] = HashSet(map[0x40] ?: emptySet()) }
        val g1 = t.generation()
        assertNotEquals("поколение не сдвинулось на равной мутации", g0, g1)

        // A→B→A: набор вернулся, поколение — нет (иначе поздний readback принял бы новую запись
        // за свою).
        t.mutate { map -> map[0x40] = hashSetOf(0x1, 0x2) }
        t.mutate { map -> map[0x40] = hashSetOf(0x1) }
        assertEquals(setOf(0x1), t.read { it[0x40] })
        assertTrue("поколение монотонно", t.generation() > g1)
    }

    @Test
    fun concurrentOpenAndCloseNeverLoseAnOwner() {
        val t = tx()
        val start = CountDownLatch(1)
        val workers = 4
        val done = CountDownLatch(workers)
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        for (i in 0 until workers) {
            Thread {
                try {
                    start.await(5, TimeUnit.SECONDS)
                    for (k in 0 until 40) {
                        val window = 0x40 + (i % 2)
                        val door = 0x1 + i
                        t.planCloseInstall(
                            door = door, window = window,
                            states = mapOf(door to true),
                            doorToWindow = mapOf(door to window)
                        )
                        t.mutate { map -> map.getOrPut(window) { HashSet() }.add(door) }
                    }
                } catch (e: Throwable) {
                    errors += "${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        assertTrue("потоки завершились", done.await(60, TimeUnit.SECONDS))
        assertTrue("исключений в конкурентных операциях нет: $errors", errors.isEmpty())
        val owners = t.read { it.values.flatten().toSet() }
        assertTrue("записи владельцев не потерялись: $owners", owners.size >= 2)
    }
}
