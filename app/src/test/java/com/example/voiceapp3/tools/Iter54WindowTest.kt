package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * iter54 §8 — ОКНА: `before → переход → установка → поколение` в ОДНОЙ критической секции.
 *
 * Дефект (аудит §8): снимок `before` брался отдельным lock, а установка набора шла другим
 * вызовом. Между ними другая дверь успевала добавить владельца — её запись затиралась, окно
 * оставалось без владельца, и закрывать его было некому. Кроме того, ветка «окно закрылось»
 * правила набор В ОБХОД единой точки мутации, то есть без нового поколения: поздний readback
 * видел «ничего не менялось» и снимал живую запись.
 *
 * Здесь проверяется не текст, а ПОВЕДЕНИЕ настоящих параллельных вызовов.
 */
class Iter54WindowTest {

    private val tx = VentOwnerTx()
    private val states = mapOf(0x1 to true, 0x4 to true)
    private val doorToWindow = mapOf(0x1 to 0x40, 0x4 to 0x40)
    private val doorA = 0x1
    private val doorB = 0x4
    private val windowX = 0x40

    @Test
    fun concurrentPlansNeverLoseAnOwnersUpdate() {
        tx.seed(mapOf(windowX to setOf(doorA)))
        val gen0 = tx.generation()

        val threads = 8
        val perThread = 25
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val errors = AtomicInteger(0)
        val generations = java.util.Collections.synchronizedSet(HashSet<Long>())

        repeat(threads) { t ->
            Thread {
                start.await(5, TimeUnit.SECONDS)
                repeat(perThread) {
                    // Разные двери вперемешку: каждая транзакция обязана видеть состояние,
                    // оставленное ПРЕДЫДУЩЕЙ, а не снимок «до гонки».
                    val door = if ((t + it) % 2 == 0) doorA else doorB
                    val plan = tx.planCloseInstall(door, windowX, states, doorToWindow)
                    // Транзакции сериализованы: каждое поколение уникально и больше базового.
                    if (plan.gen <= gen0 || !generations.add(plan.gen)) errors.incrementAndGet()
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("потоки завершились", done.await(30, TimeUnit.SECONDS))

        assertEquals("каждая установка получила СВОЁ поколение", 0, errors.get())
        assertEquals("все поколения различны (транзакции не слиплись)",
            threads * perThread, generations.size)
        assertEquals(
            "поколение выросло ровно на число установок",
            gen0 + threads * perThread,
            tx.generation()
        )
        assertTrue("запись окна не потеряна",
            tx.snapshot().containsKey(windowX))
    }

    @Test
    fun closingOneDoorDoesNotDropOwnersAddedByAnother() {
        // Окно делят две двери; закрывается только A.
        tx.seed(mapOf(windowX to setOf(doorA, doorB)))
        val plan = tx.planCloseInstall(doorA, windowX, states, doorToWindow)
        assertEquals("владелец B сохранён (потерянного обновления нет)", setOf(doorB), plan.after)
        assertTrue("A снят", doorA !in plan.after)
        assertTrue("поколение выдано в той же секции", plan.gen > 0)
    }

    @Test
    fun rollbackRefusesToEraseANewerEvent() {
        val ours = setOf(doorA)
        // Набор изменился после нашей установки (другая дверь добавила владельца) → откат запрещён.
        val changed = setOf(doorA, doorB)
        assertNull("новое событие не стирается откатом",
            ventRollbackOwners(changed, ours, setOf(doorA), genNow = 10L, genInstalled = 9L))
        // Тот же набор, но ДРУГОЕ поколение (A→B→A): тоже не откатываем.
        assertNull("ABA по поколению распознана",
            ventRollbackOwners(ours, ours, emptySet(), genNow = 11L, genInstalled = 9L))
        // Ничего не менялось — откат разрешён и возвращает прежнее.
        assertNotNull("честный откат возможен",
            ventRollbackOwners(ours, ours, emptySet(), genNow = 9L, genInstalled = 9L))
    }

    @Test
    fun closedBranchUsesTheSameMutationDiscipline() {
        val src = listOf(
            File("src/main/java/com/example/voiceapp3/tools/WindowDoors.kt"),
            File("app/src/main/java/com/example/voiceapp3/tools/WindowDoors.kt"),
            File("/home/user/w/app/src/main/java/com/example/voiceapp3/tools/WindowDoors.kt")
        ).first { it.isFile }.readText()
        // Ровно один путь записи: прямое `ventOwners.remove(...)` вне mutateOwners недопустимо.
        val directRemovals = Regex("synchronized\\(ventOwners\\)\\s*\\{[^}]*ventOwners\\.remove")
            .findAll(src).count()
        assertEquals("прямых удалений в обход дисциплины нет", 0, directRemovals)
        assertTrue("закрытие окна идёт через mutateOwners",
            src.contains("mutateOwners { map -> if (map[windowAreaId]?.isEmpty() == true) map.remove(windowAreaId) }"))
        assertTrue("план закрытия — одна транзакция", src.contains("ventTx.planCloseInstall("))
        assertTrue("все чтения/записи набора идут через один объект",
            !src.contains("synchronized(ventOwners)"))
    }
}
