package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter51 §8.1 — откат владельцев окна при неподтверждённом закрытии.
 *
 * Дефект, введённый в iter50 (`WindowDoors.kt:576–580`): при неуспехе readback набор владельцев
 * переписывался напрямую — `synchronized(ventOwners) { set.clear(); set.addAll(before) }` — в
 * обход `mutateOwners`. Два следствия:
 *  1. Поколение (`ventGen`) не увеличивалось, и поздний readback считал состояние «не менявшимся».
 *  2. Любой владелец, добавленный ДРУГОЙ дверью за время команды VHAL, молча стирался —
 *     окно оставалось без владельца и больше не закрывалось.
 */
class Iter51VentRollbackTest {

    private val win = 0x40
    private val doorA = 0x1
    private val doorB = 0x4

    @Test
    fun rollbackRestoresOwnBeforeSetWhenNobodyElseChanged() {
        val before = setOf(doorA)
        val installed = setOf(doorA, doorB)   // мы попытались закрыть дверью B
        assertEquals("никто не менял — откат к before",
            before, ventRollbackOwners(installed, installed, before))
    }

    @Test
    fun rollbackDoesNotEraseOwnerAddedMeanwhile() {
        val before = setOf(doorA)
        val installed = setOf(doorA, doorB)
        val current = setOf(doorA, doorB, 0x80)   // третья дверь добавилась во время команды
        assertNull("новый владелец НЕ должен быть стёрт", ventRollbackOwners(current, installed, before))
    }

    @Test
    fun rollbackDoesNotUndoNewerOtherChange() {
        val before = emptySet<Int>()
        val installed = setOf(doorB)
        assertNull("набор изменился (A→B→A тоже считается изменением на уровне поколения)",
            ventRollbackOwners(setOf(doorA), installed, before))
    }

    @Test
    fun rollbackToEmptyIsAllowedForLegacyWindow() {
        assertEquals(emptySet<Int>(),
            ventRollbackOwners(setOf(doorB), setOf(doorB), emptySet()))
    }

    @Test
    fun productionRollbackGoesThroughSingleMutationPoint() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/WindowDoors.kt").readText()
        // Ищем ветку неподтверждённого закрытия и убеждаемся, что откат идёт через mutateOwners.
        val unconfirmed = src.substringAfter("result=closed pos_after=", "")
        assertTrue("ветка есть", unconfirmed.isNotEmpty())
        val branch = unconfirmed.take(2000)
        assertTrue("откат использует mutateOwners", branch.contains("mutateOwners {"))
        assertTrue("решение принимает чистая функция", branch.contains("ventRollbackOwners("))
    }

    @Test
    fun abaWithSameContentButNewerGenerationIsNotRolledBack() {
        val doorA = 0x1
        val doorB = 0x2
        val before = setOf(doorA, doorB)
        val installed = setOf(doorA, doorB)
        // Содержимое «как мы записали», но поколение другое: пока летела команда VHAL, набор
        // успел измениться и вернуться к тому же виду (A→B→A). Откат стёр бы живую мутацию.
        assertNull(
            "одинаковое содержимое при другом поколении — не повод откатывать",
            ventRollbackOwners(installed, installed, emptySet(), genNow = 7L, genInstalled = 5L)
        )
        assertEquals(
            "поколение не менялось — откат своей записи разрешён",
            emptySet<Int>(),
            ventRollbackOwners(installed, installed, emptySet(), genNow = 5L, genInstalled = 5L)
        )
        // Без переданных поколений поведение прежнее (совместимость вызовов).
        assertEquals(emptySet<Int>(), ventRollbackOwners(installed, installed, emptySet()))
    }
}
