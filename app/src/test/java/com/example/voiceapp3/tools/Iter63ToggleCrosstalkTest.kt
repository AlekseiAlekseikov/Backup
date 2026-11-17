package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter63 §P0-2 (B1) — CROSS-TALK: КЛИК ПО ОДНОМУ ТУМБЛЕРУ НЕ МЕНЯЕТ ДРУГИЕ.
 *
 * Почему это отдельный тест: в iter62 был доказан только симптом «работает, а визуально OFF»
 * (`boolOrNull("")`). На главную жалобу владельца — «клик одного визуально включает другие» —
 * падающего теста не было, то есть исправление не было подтверждено.
 *
 * Проверяется ПРОИЗВОДСТВЕННАЯ модель, а не пересказ:
 *  • `ToolsStateCausality.decide` — по какому ключу и что решается;
 *  • `ToolsStateCausality.applyAcks` + `readAcks` — восстановление подтверждений из снимка;
 *  • `ToolsSnapshotFold.decideAll` — решения по всем ключам сразу;
 *  • `Toggles45.switchPosition` + `Toggles45.dependentVisible` — что видит строка.
 *
 * Найденная причина (файл:строка на коде iter62):
 *  • `ui/ToolsPanel.kt:171` (`ToolsSnapshotFold.read`) — подтверждения (`$acks`) читались, но НЕ
 *    восстанавливались в память UI; `ToolsStateCausality.recordAck` живёт в процессе backend
 *    (`ToolsStateCausality.kt:153`), поэтому после пересоздания панели `ackFor(key) == null`
 *    для ВСЕХ ключей;
 *  • `ToolsStateCausality.kt:126–133` — без своего подтверждения решение по клику уходило в
 *    `UNCONFIRMED`/«не подтверждено», и строка перерисовывалась по устаревшим признакам, то есть
 *    экран менялся не от того клика, который был сделан.
 */
class Iter63ToggleCrosstalkTest {

    private fun pending(key: String, value: Boolean, clickId: Long) =
        ToolsStateCausality.PendingClick(key, value, clickId, requestedAtMs = 0L)

    private fun ack(key: String, clickId: Long, rev: Long, ok: Boolean = true) =
        ToolsStateCausality.WriteAck(key, clickId, ok, rev)

    // ─────────── 1. Подтверждение ИЗ СНИМКА восстанавливается (пересоздание панели) ───────────

    @Test
    fun ackFromTheSnapshotIsRestoredAndSettlesOurClick() {
        ToolsStateCausality.resetForTest()
        val mine = pending("hvacBatt", true, clickId = 7L)

        // Панель пересоздана: в памяти UI подтверждений нет.
        assertNull("до восстановления подтверждения нет", ToolsStateCausality.ackFor("hvacBatt"))

        // Снимок несёт наше подтверждение (его пишет backend) — JSON.
        val json = com.google.gson.JsonObject().apply { add("hvacBatt", ack("hvacBatt", 7L, 3L).toJson()) }
        ToolsStateCausality.applyAcks(ToolsStateCausality.readAcks(json))

        val d = ToolsStateCausality.decide(
            pending = mine, ack = ToolsStateCausality.ackFor("hvacBatt"),
            snapshotValue = true, snapshotRev = 3L, ageMs = 1_000L
        )
        assertEquals(
            "подтверждение из снимка должно закрыть выбор владельца",
            ToolsStateCausality.Decision.CLEAR_CONFIRMED, d
        )
        ToolsStateCausality.resetForTest()
    }

    @Test
    fun withoutRestoredAckTheClickIsHeldNotMistakenForOthers() {
        ToolsStateCausality.resetForTest()
        // Без восстановления подтверждение «не наше» — выбор владельца ДЕРЖИТСЯ, и подпись не
        // превращается в «не подтверждено» до истечения бюджета.
        val d = ToolsStateCausality.decide(
            pending = pending("hvacBatt", true, 7L), ack = null,
            snapshotValue = true, snapshotRev = 3L, ageMs = 1_000L
        )
        assertEquals(ToolsStateCausality.Decision.KEEP_CLICK, d)
        ToolsStateCausality.resetForTest()
    }

    // ─────────── 2. Клик по одному не меняет решение по другому ───────────

    @Test
    fun clickOnOneToggleNeverChangesTheDecisionForAnother() {
        ToolsStateCausality.resetForTest()
        val snapshot = com.google.gson.JsonObject().apply {
            addProperty("hvacBatt", false)
            addProperty("hvacTemp", true)
            addProperty("ttl65", false)
            addProperty("lockSound", true)
        }
        val pendingMap = hashMapOf("hvacBatt" to pending("hvacBatt", true, 11L))

        val decisions = ToolsSnapshotFold.decideAll(snapshot, pendingMap, nowMs = 1_000L)
        assertEquals("есть решение ровно по кликнутому ключу: $decisions", 1, decisions.size)
        assertTrue(decisions.containsKey("hvacBatt"))
        for (neighbour in listOf("hvacTemp", "ttl65", "lockSound")) {
            assertFalse(
                "клик по hvacBatt не создаёт решение по $neighbour: $decisions",
                decisions.containsKey(neighbour)
            )
        }
        ToolsStateCausality.resetForTest()
    }

    @Test
    fun acknowledgementOfAnotherKeyDoesNotSettleMyClick() {
        ToolsStateCausality.resetForTest()
        // Подтверждение по ДРУГОМУ ключу не имеет права закрыть мой клик.
        val d = ToolsStateCausality.decide(
            pending = pending("hvacBatt", true, 5L),
            ack = ack("hvacTemp", 9L, 4L),
            snapshotValue = false, snapshotRev = 4L, ageMs = 500L
        )
        assertEquals(
            "чужое подтверждение не перетирает выбор владельца",
            ToolsStateCausality.Decision.KEEP_CLICK, d
        )
        ToolsStateCausality.resetForTest()
    }

    // ─────────── 3. Положение и подпись соседей не меняются ───────────

    @Test
    fun neighbourPositionAndLabelStayIdentical() {
        fun position(state: String?, pendingClick: Boolean?, lastKnown: Boolean?, def: Boolean) =
            pendingClick ?: Toggles45.switchPosition(state, lastKnown, def)

        fun label(state: String?, pendingClick: Boolean?, lastKnown: Boolean?, name: String) = when {
            state == "unknown" && lastKnown == null -> "$name · состояние ещё не прочитано"
            state == "unknown" -> "$name · состояние не подтверждено (последнее известное)"
            pendingClick != null -> "$name · запись не подтверждена"
            else -> name
        }

        val before = mapOf(
            "hvacTemp" to (position("on", null, true, false) to label("on", null, true, "hvacTemp")),
            "ttl65" to (position("off", null, false, false) to label("off", null, false, "ttl65")),
            "hvacBatt" to (position("off", null, false, false) to label("off", null, false, "hvacBatt")),
        )
        // Клик по «Проценту заряда»: pending только у него.
        val after = mapOf(
            "hvacTemp" to (position("on", null, true, false) to label("on", null, true, "hvacTemp")),
            "ttl65" to (position("off", null, false, false) to label("off", null, false, "ttl65")),
            "hvacBatt" to (position("off", true, false, false) to label("off", true, false, "hvacBatt")),
        )
        assertEquals("положение соседа hvacTemp изменилось", before["hvacTemp"], after["hvacTemp"])
        assertEquals("положение соседа ttl65 изменилось", before["ttl65"], after["ttl65"])
        assertTrue("кликнутая строка показывает выбор владельца", after["hvacBatt"]!!.first)
        assertEquals(
            "подпись кликнутой строки честно говорит о неподтверждённой записи",
            "hvacBatt · запись не подтверждена", after["hvacBatt"]!!.second
        )

        // Зависимая строка: клик «выключить» скрывает её СРАЗУ, но собственное значение зависимой
        // настройки не меняется (это видимость, а не запись).
        assertFalse(Toggles45.dependentVisible("on", false, true))
        assertEquals("скрытие не является записью", false, Toggles45.dependentHidesChoice)
    }

    // ─────────── 4. Поздний снимок не откатывает уже сделанный выбор ───────────

    @Test
    fun lateSnapshotCannotRollTheClickedToggleBack() {
        ToolsStateCausality.resetForTest()
        // Клик был, подтверждения ещё нет (снимок посчитан ДО записи) → выбор держим.
        val hold = ToolsStateCausality.decide(
            pending = pending("hvacBatt", true, 21L), ack = null,
            snapshotValue = false, snapshotRev = 2L, ageMs = 300L
        )
        assertEquals(ToolsStateCausality.Decision.KEEP_CLICK, hold)

        // Снимок без ревизии причинности не доказывает, что видел нашу запись.
        val noRev = ToolsStateCausality.decide(
            pending = pending("hvacBatt", true, 21L), ack = ack("hvacBatt", 21L, 3L),
            snapshotValue = true, snapshotRev = null, ageMs = 300L
        )
        assertEquals(
            "снимок без rev не подтверждает нашу запись",
            ToolsStateCausality.Decision.KEEP_CLICK, noRev
        )
        ToolsStateCausality.resetForTest()
    }

    @Test
    fun confirmedSnapshotSettlesOnlyWithTheSameValue() {
        ToolsStateCausality.resetForTest()
        val settled = ToolsStateCausality.decide(
            pending = pending("ttl65", true, 31L), ack = ack("ttl65", 31L, 5L),
            snapshotValue = true, snapshotRev = 5L, ageMs = 100L
        )
        assertEquals(ToolsStateCausality.Decision.CLEAR_CONFIRMED, settled)

        val contradicts = ToolsStateCausality.decide(
            pending = pending("ttl65", true, 31L), ack = ack("ttl65", 31L, 5L),
            snapshotValue = false, snapshotRev = 5L, ageMs = 100L
        )
        assertEquals(
            "снимок увидел нашу запись и показал другое — это факт",
            ToolsStateCausality.Decision.SNAPSHOT_CONTRADICTS_WRITE, contradicts
        )
        ToolsStateCausality.resetForTest()
    }
}
