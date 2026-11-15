package com.example.voiceapp3.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * iter57 §10 — ОКНА: все writers, событие между планом и физическим действием, ABA.
 *
 * Проверяется ПРОИЗВОДСТВЕННЫЙ путь `WindowDoors`: `planCloseInstall` (одна критическая секция:
 * было → переход → установка → поколение) и `rollbackAfterFailedClose` (единственная точка отк��та
 * после неподтверждённого закрытия). Не «модель рядом с кодом»: тест вызывает те же `internal`
 * методы, что и `closeVentWindow`.
 *
 * Чего эти тесты НЕ доказывают (и не должны выдаваться за доказательство):
 *  • реальное окно автомобиля, реальный VHAL и `CarPropertyManager` — в JVM их нет (`android.car`
 *    — стаб), поэтому физическое действие и `writeWindowPos`/`readWindowPos` здесь не исполняются;
 *  • UI/WebView и настоящие listeners — 🟡, нужен Android-стенд.
 */
class Iter57WindowTest {

    private val win = 0x40        // окно, которым владеет дверь 0x1 по DOOR_TO_WINDOW
    private val doorA = 0x1
    private val doorB = 0x4

    @After
    fun reset() = WindowDoors.seedStateForTest(emptyMap())

    // ───── 1. Событие между планом и откатом не теряется ─────

    @Test
    fun ownerAddedWhileTheCommandIsInFlightSurvivesTheRollback() {
        WindowDoors.seedStateForTest(mapOf(win to setOf(doorA)))
        val plan = WindowDoors.planCloseInstall(doorA, win)
        // Пока «летела» команда VHAL, другая дверь открыла окно. Событие вносится через ТУ ЖЕ
        // единственную точку мутации (`seedStateForTest` → `VentOwnerTx.seed`, двигает поколение):
        // физический путь открытия требует VHAL и в JVM недоступен (см. шапку класса).
        WindowDoors.seedStateForTest(mapOf(win to (plan.after + doorB)))
        assertTrue("новая дверь записана", doorB in (WindowDoors.ownersForTest()[win] ?: emptySet()))

        val rolledBack = WindowDoors.rollbackAfterFailedClose(
            win, plan.after, plan.before, plan.gen, plan.legacy
        )

        assertFalse("откат запрещён: набор изменили не мы последними", rolledBack)
        assertTrue("запись новой двери живёт: ${WindowDoors.ownersForTest()}",
            doorB in (WindowDoors.ownersForTest()[win] ?: emptySet()))
    }

    @Test
    fun abaSameContentButNewerGenerationIsNotRolledBack() {
        WindowDoors.seedStateForTest(mapOf(win to setOf(doorA)))
        val plan = WindowDoors.planCloseInstall(doorA, win)
        // A→B→A: содержимое вернулось к виду «как после нашего плана», но поколение другое.
        WindowDoors.planCloseInstall(doorB, win)
        WindowDoors.planCloseInstall(doorA, win)
        WindowDoors.seedStateForTest(mapOf(win to plan.after))     // содержимое ровно как в плане
        val genNow = WindowDoors.generationForTest()
        assertTrue("поколение действительно ушло вперёд", genNow != plan.gen)

        val rolledBack = WindowDoors.rollbackAfterFailedClose(
            win, plan.after, plan.before, plan.gen, plan.legacy
        )

        assertFalse("равное содержимое при другом поколении — не повод откатывать", rolledBack)
        assertEquals("запись не тронута", plan.after, WindowDoors.ownersForTest()[win])
    }

    @Test
    fun ownUnchangedRecordIsRolledBack() {
        WindowDoors.seedStateForTest(mapOf(win to setOf(doorA)))
        val plan = WindowDoors.planCloseInstall(doorA, win)
        val rolled = WindowDoors.rollbackAfterFailedClose(
            win, plan.after, plan.before, plan.gen, plan.legacy
        )
        assertTrue("своя неизменённая запись откатывается", rolled)
        assertEquals("вернулись к состоянию до попытки", plan.before,
            WindowDoors.ownersForTest()[win] ?: emptySet<Int>())
    }

    // ───── 2. Гонка writers: барьер, ни одного потерянного обновления ─────

    @Test
    fun barrierOfConcurrentClosePlansLosesNoUpdate() {
        val doors = listOf(0x1, 0x4, 0x10, 0x40, 0x100, 0x400, 0x1000, 0x2000)
        WindowDoors.seedStateForTest(mapOf(win to doors.toSet()))
        val barrier = CyclicBarrier(doors.size)
        val done = CountDownLatch(doors.size)
        for (d in doors) {
            Thread {
                try {
                    barrier.await(10, TimeUnit.SECONDS)
                    WindowDoors.planCloseInstall(d, win)
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true; start() }
        }
        assertTrue("все планы дошли", done.await(30, TimeUnit.SECONDS))
        // План снимает ТОЛЬКО свою дверь: если бы `before` читалось вне секции, часть дверей
        // стёрлась бы чужой записью.
        assertEquals("ни одно чужое владение не потеряно: ${WindowDoors.ownersForTest()}",
            emptySet<Int>(), WindowDoors.ownersForTest()[win] ?: emptySet<Int>())
        assertTrue("поколение двигалось на каждую мутацию",
            WindowDoors.generationForTest() >= doors.size)
    }

    // ───── 3. readback/VHAL: «принято» ≠ «закрыто» ─────

    @Test
    fun missingOrRejectedReadbackIsNeverCalledClosed() {
        assertEquals("команда отклонена", "write_rejected",
            ventCloseOutcome(false, 0, 0, 3))
        assertEquals("подтверждения нет — не закрыто", "readback_missing",
            ventCloseOutcome(true, null, 0, 3))
        assertEquals("VHAL ещё показывает открыто", "late_readback",
            ventCloseOutcome(true, 10, 0, 3))
        assertEquals("подтверждено чтением", "closed",
            ventCloseOutcome(true, 1, 0, 3))
    }

    @Test
    fun ownersAreNotDroppedByALateReadbackOfAnOlderAttempt() {
        // Поздний readback прошлого закрытия не снимает живую запись: поколение другое.
        assertFalse("другое поколение — не трогаем",
            ventLateReadbackMayDropOwners(emptyList(), emptyList(), genNow = 9L, genAtAttempt = 8L))
        assertTrue("то же поколение и пусто — можно",
            ventLateReadbackMayDropOwners(emptyList(), emptyList(), genNow = 8L, genAtAttempt = 8L))
        assertFalse("набор не пуст — окно живёт",
            ventLateReadbackMayDropOwners(listOf(doorA), listOf(doorA), genNow = 8L, genAtAttempt = 8L))
    }

    // ───── 4. mutable-set escape закрыт ─────

    @Test
    fun escapedCollectionFromReadCannotMutateLiveState() {
        WindowDoors.seedStateForTest(mapOf(win to setOf(doorA)))
        val genBefore = WindowDoors.generationForTest()
        val escaped = WindowDoors.readOwnersForTest()
        @Suppress("UNCHECKED_CAST")
        val attempt = runCatching {
            (escaped[win] as MutableSet<Int>).add(doorB)
        }
        assertTrue("мутация снимка либо невозможна, либо не влияет", attempt.isFailure ||
            doorB !in (WindowDoors.ownersForTest()[win] ?: emptySet()))
        assertEquals("состояние не изменилось", setOf(doorA),
            WindowDoors.ownersForTest()[win] ?: emptySet<Int>())
        assertEquals("поколение не двигалось", genBefore, WindowDoors.generationForTest())
    }

    // ───── 5. UI-зависимость строки: скрытие не стирает выбор ─────

    @Test
    fun hidingTheDependentRowIsNotAWriteToTheOwnersChoice() {
        // «Наружная температура» видна только при включённом «проценте заряда», но при скрытии
        // строка НЕ переписывает pref (иначе включение владельца вернуло бы OFF).
        assertTrue("неизвестность не выдаётся за выключено", Toggles45.dependentVisible(null))
        assertTrue("включено — видно", Toggles45.dependentVisible(true))
        assertFalse("подтверждённое выключение — скрыто", Toggles45.dependentVisible(false))
        assertFalse("скрытие не является записью", Toggles45.dependentHidesChoice)
    }
}
