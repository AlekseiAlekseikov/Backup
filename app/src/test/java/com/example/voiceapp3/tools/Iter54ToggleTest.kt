package com.example.voiceapp3.tools

import com.google.gson.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter54 §10 — ТУМБЛЕРЫ: НАМЕРЕНИЕ, СОХРАНЕНИЕ И ПРИМЕНЕНИЕ — РАЗНЫЕ ФАКТЫ.
 *
 * Проверяется тот же production-код, что исполняет живой путь:
 *  • [ToggleIntent] — разбор факта сохранения намерения и факта применения;
 *  • [ToolsStateCausality.decide] — решение «держать/снять выбор владельца»;
 *  • [ToolsSnapshotFold] — разбор настоящего `tools.state` (JSON собирается как в
 *    `UiBridge.attachCausality`: `rev` + `$acks` + `<key>State`);
 *  • [ToolsStateCausality.acksJson]/[ToolsStateCausality.readAcks] — реальный round-trip
 *    подтверждений через JSON снимка.
 *
 * Что воспроизводится из дефекта (аудит §5/§10): успех ОБЩЕГО действия выдавался за
 * подтверждение записи намерения. TTL ON, сохранённый и ожидающий uplink (PARTIAL), показывался
 * как «запись не удалась»; неизвестное чтение хранилища — как OFF.
 */
class Iter54ToggleTest {

    @After
    fun tearDown() = ToolsStateCausality.resetForTest()

    // ───────────────── (a)+(b): факт сохранения намерения ─────────────────

    @Test
    fun storedValueEqualToChoiceIsWritten() {
        assertEquals(ToggleIntent.Intent.WRITTEN,
            ToggleIntent.intentOf(want = true, stored = true, saveConfirmed = true))
        assertEquals(ToggleIntent.Intent.WRITTEN,
            ToggleIntent.intentOf(want = false, stored = false, saveConfirmed = true))
    }

    @Test
    fun storedValueDifferentFromChoiceIsAFailedWrite() {
        assertEquals("хранилище вернуло другое значение — намерение НЕ сохранено",
            ToggleIntent.Intent.WRITE_FAILED,
            ToggleIntent.intentOf(want = true, stored = false, saveConfirmed = true))
    }

    @Test
    fun unreadableStorageIsUnknownNotFalse() {
        // Ключа не прочитать (конфликт CPS/DPS, нет прав, миграция): это НЕ «выключено».
        assertEquals(ToggleIntent.Intent.UNKNOWN,
            ToggleIntent.intentOf(want = true, stored = null, saveConfirmed = true))
        // Запись сама сообщила о неуспехе и прочитать нечего — тогда это отказ записи.
        assertEquals(ToggleIntent.Intent.WRITE_FAILED,
            ToggleIntent.intentOf(want = true, stored = null, saveConfirmed = false))
    }

    @Test
    fun valueInPlaceButWriteNotConfirmedIsUnknown() {
        // Значение видно, но запись не подтвердила durability (commit не подтверждён).
        assertEquals(ToggleIntent.Intent.UNKNOWN,
            ToggleIntent.intentOf(want = true, stored = true, saveConfirmed = false))
    }

    @Test
    fun choiceForUnknownKeyIsUnknown() {
        assertEquals(ToggleIntent.Intent.UNKNOWN,
            ToggleIntent.intentOf(want = null, stored = true, saveConfirmed = true))
    }

    // ───────────────── (c): факт применения ─────────────────

    @Test
    fun applyStatusIsNotAWriteVerdict() {
        assertEquals(ToggleIntent.Applied.APPLIED, ToggleIntent.Applied.ofStatus(ToolsStep.Status.OK))
        // Нет uplink/канала: применение ждёт. Это не отказ записи и не «выключено».
        assertEquals(ToggleIntent.Applied.PENDING, ToggleIntent.Applied.ofStatus(ToolsStep.Status.PARTIAL))
        assertEquals(ToggleIntent.Applied.PENDING, ToggleIntent.Applied.ofStatus(ToolsStep.Status.BLOCKED))
        assertEquals(ToggleIntent.Applied.FAILED, ToggleIntent.Applied.ofStatus(ToolsStep.Status.FAILED))
        assertEquals(ToggleIntent.Applied.UNKNOWN, ToggleIntent.Applied.ofStatus(null))
    }

    @Test
    fun summaryWireStatusIsNotAWriteVerdict() {
        // production берёт факт применения из `ToolsResult.statusWire`
        assertEquals(ToggleIntent.Applied.APPLIED, ToggleIntent.Applied.ofWire("ok"))
        assertEquals(ToggleIntent.Applied.PENDING, ToggleIntent.Applied.ofWire("partial"))
        assertEquals(ToggleIntent.Applied.PENDING, ToggleIntent.Applied.ofWire("blocked"))
        assertEquals(ToggleIntent.Applied.FAILED, ToggleIntent.Applied.ofWire("failed"))
        assertEquals(ToggleIntent.Applied.UNKNOWN, ToggleIntent.Applied.ofWire(null))
        assertEquals(ToggleIntent.Applied.UNKNOWN, ToggleIntent.Applied.ofWire("нечто"))
    }

    @Test
    fun neutralNoteNeverCallsSavedIntentOff() {
        val pendingNote = ToggleIntent.note(ToggleIntent.Intent.WRITTEN, ToggleIntent.Applied.PENDING)
        assertEquals("сохранено, ждёт применения", pendingNote)
        val unknownNote = ToggleIntent.note(ToggleIntent.Intent.UNKNOWN, ToggleIntent.Applied.UNKNOWN)
        assertFalse("неизвестность не описывается как выключение: $unknownNote",
            unknownNote.contains("выключ"))
        assertFalse("неизвестность не описывается как ошибка записи: $unknownNote",
            unknownNote.contains("не сохранено"))
    }

    // ───────────────── (d): решение по снимку ─────────────────

    private fun snapshot(
        rev: Long,
        values: Map<String, Boolean?>,
        acks: Map<String, ToolsStateCausality.WriteAck> = emptyMap(),
        seq: Long = 1
    ): JsonObject = JsonObject().apply {
        addProperty("seq", seq)
        addProperty("rev", rev)
        addProperty("stateLoaded", true)
        add("\$acks", JsonObject().apply { for ((k, a) in acks) add(k, a.toJson()) })
        for ((k, v) in values) {
            addProperty(k, v ?: false)
            addProperty(k + "State", when (v) { true -> "on"; false -> "off"; null -> "unknown" })
        }
        addProperty("\$keys", values.size)
    }

    private fun pending(key: String, value: Boolean, clickId: Long, at: Long) =
        ToolsStateCausality.PendingClick(key, value, clickId, at)

    private fun decide(snap: JsonObject, p: ToolsStateCausality.PendingClick, now: Long = 1_500L) =
        ToolsSnapshotFold.decideAll(snap, mapOf(p.key to p), now)[p.key]

    @Test
    fun savedIntentWithPendingSideEffectReleasesTheHold() {
        // TTL ON: намерение СОХРАНЕНО (stored == true), сеть ещё не применила (PARTIAL).
        val pend = pending("ttl65", true, clickId = 7, at = 1_000L)
        val snap = snapshot(
            rev = 12,
            values = mapOf("ttl65" to true),
            acks = mapOf(
                "ttl65" to ToolsStateCausality.WriteAck(
                    key = "ttl65", clickId = 7, ok = true, rev = 10,
                    detail = "intent=written applied=pending",
                    intent = ToggleIntent.Intent.WRITTEN,
                    applied = ToggleIntent.Applied.PENDING
                )
            )
        )
        assertEquals("сохранённое намерение подтверждает выбор владельца",
            ToolsStateCausality.Decision.CLEAR_CONFIRMED, decide(snap, pend))
        assertEquals("подпись — нейтральная",
            "сохранено, ждёт применения", snap.getAsJsonObject("\$acks").getAsJsonObject("ttl65")
                .let {
                    ToggleIntent.note(
                        ToggleIntent.Intent.of(it.get("intent").asString)!!,
                        ToggleIntent.Applied.of(it.get("applied").asString)!!
                    )
                })
    }

    @Test
    fun oldSnapshotWithoutFactsIsReadThroughOk() {
        // Совместимость: снимок старого формата несёт только `ok`.
        val old = JsonObject().apply {
            addProperty("clickId", 3)
            addProperty("ok", true)
            addProperty("rev", 5)
        }
        val ack = ToolsStateCausality.readAcks(JsonObject().apply { add("ttl65", old) })["ttl65"]!!
        assertEquals(ToggleIntent.Intent.WRITTEN, ack.intent)
        assertEquals(ToggleIntent.Applied.UNKNOWN, ack.applied)
    }

    @Test
    fun unknownWriteIsNotTreatedAsFailure() {
        // Хранилище не прочиталось: держим выбор (не «не применено»), а после бюджета честно
        // называем его неподтверждённым.
        val pend = pending("lockSound", false, clickId = 5, at = 1_000L)
        val snap = snapshot(
            rev = 9,
            values = mapOf("lockSound" to null),
            acks = mapOf(
                "lockSound" to ToolsStateCausality.WriteAck(
                    key = "lockSound", clickId = 5, ok = false, rev = 8,
                    intent = ToggleIntent.Intent.UNKNOWN,
                    applied = ToggleIntent.Applied.UNKNOWN
                )
            )
        )
        assertEquals(ToolsStateCausality.Decision.KEEP_CLICK, decide(snap, pend, now = 1_500L))
        assertEquals(ToolsStateCausality.Decision.UNCONFIRMED,
            decide(snap, pend, now = 1_000L + ToolsStateCausality.UNCONFIRMED_AFTER_MS + 1))
    }

    @Test
    fun realWriteFailureIsReportedEvenWhenSideEffectWorked() {
        // Обратная ошибка: side effect удался, запись намерения — нет. Ложного успеха нет.
        val pend = pending("windowDriverOnDoor", true, clickId = 6, at = 1_000L)
        val snap = snapshot(
            rev = 20,
            values = mapOf("windowDriverOnDoor" to false),
            acks = mapOf(
                "windowDriverOnDoor" to ToolsStateCausality.WriteAck(
                    key = "windowDriverOnDoor", clickId = 6, ok = false, rev = 15,
                    intent = ToggleIntent.Intent.WRITE_FAILED,
                    applied = ToggleIntent.Applied.APPLIED
                )
            )
        )
        assertEquals(ToolsStateCausality.Decision.WRITE_FAILED, decide(snap, pend))
    }

    @Test
    fun lateAckOfOlderClickDoesNotClearNewerChoice() {
        val pend = pending("ttl65", false, clickId = 9, at = 1_000L)
        val snap = snapshot(
            rev = 4,
            values = mapOf("ttl65" to true),
            acks = mapOf(
                "ttl65" to ToolsStateCausality.WriteAck(
                    key = "ttl65", clickId = 8, ok = true, rev = 3,
                    intent = ToggleIntent.Intent.WRITTEN,
                    applied = ToggleIntent.Applied.APPLIED
                )
            )
        )
        assertEquals("подтверждение СТАРОГО клика не снимает новый выбор",
            ToolsStateCausality.Decision.KEEP_CLICK, decide(snap, pend))
    }

    @Test
    fun coldStartWithoutAcksKeepsTheChoiceUnknown() {
        val pend = pending("ttl65", true, clickId = 1, at = 1_000L)
        val snap = snapshot(rev = 2, values = mapOf("ttl65" to false))
        assertEquals(ToolsStateCausality.Decision.KEEP_CLICK, decide(snap, pend))
    }

    // ───────────────── один тумблер не меняет другие ─────────────────

    @Test
    fun decisionForOneKeyDoesNotTouchTheOthers() {
        val pends = mapOf(
            "ttl65" to pending("ttl65", true, clickId = 2, at = 1_000L),
            "lockSound" to pending("lockSound", false, clickId = 3, at = 1_000L)
        )
        val snap = snapshot(
            rev = 30,
            values = mapOf("ttl65" to true, "lockSound" to true, "ttlUnrelated" to true),
            acks = mapOf(
                "ttl65" to ToolsStateCausality.WriteAck(
                    key = "ttl65", clickId = 2, ok = true, rev = 29,
                    intent = ToggleIntent.Intent.WRITTEN,
                    applied = ToggleIntent.Applied.PENDING
                )
            )
        )
        val decisions = ToolsSnapshotFold.decideAll(snap, pends, 1_500L)
        assertEquals("решение есть только по кликнутым ключам", setOf("ttl65", "lockSound"), decisions.keys)
        assertEquals(ToolsStateCausality.Decision.CLEAR_CONFIRMED, decisions["ttl65"])
        assertEquals("у второго ключа своего подтверждения нет — выбор держится",
            ToolsStateCausality.Decision.KEEP_CLICK, decisions["lockSound"])
        // Снимок остальных ключей не тронут: посторонний ключ читается как есть.
        assertEquals(true, ToolsSnapshotFold.read(snap).values["ttlUnrelated"])
    }

    @Test
    fun partialSnapshotLeavesMissingKeyUnreadInsteadOfFalse() {
        // Ключа в снимке нет — он должен остаться «не прочитан» (unknown), а не OFF.
        val snap = snapshot(rev = 7, values = mapOf("ttl65" to true))
        val meta = ToolsSnapshotFold.read(snap)
        assertTrue(meta.keys.contains("ttl65"))
        assertFalse("отсутствующий ключ не выдумывается", meta.keys.contains("lockSound"))
        assertNull(meta.values["lockSound"])
    }

    // ───────────────── round-trip через настоящий JSON снимка ─────────────────

    @Test
    fun ackSurvivesRealJsonRoundTripWithFacts() {
        ToolsStateCausality.recordAck(
            ToolsStateCausality.WriteAck(
                key = "ttl65", clickId = 11, ok = true, rev = 40,
                detail = "intent=written applied=pending",
                intent = ToggleIntent.Intent.WRITTEN,
                applied = ToggleIntent.Applied.PENDING
            )
        )
        val json = JsonObject().apply { add("\$acks", ToolsStateCausality.acksJson()) }
        val back = ToolsSnapshotFold.read(
            JsonObject().apply {
                addProperty("rev", 41)
                add("\$acks", json.getAsJsonObject("\$acks"))
                addProperty("ttl65", true)
                addProperty("ttl65State", "on")
            }
        ).acks["ttl65"]!!
        assertEquals(11L, back.clickId)
        assertEquals(ToggleIntent.Intent.WRITTEN, back.intent)
        assertEquals(ToggleIntent.Applied.PENDING, back.applied)
        assertTrue("сводка ok означает подтверждённое намерение", back.ok)
        assertEquals("сохранено, ждёт применения", back.note())
    }
}
