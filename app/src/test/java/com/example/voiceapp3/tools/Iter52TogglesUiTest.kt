package com.example.voiceapp3.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter52 §8.2 — тумблеры: поздний снимок не перетирает клик, `unknown` не превращается в OFF,
 * последнее известное положение переживает холодный старт.
 *
 * Дефекты (аудит §8.2):
 *  1. `lastKnownPos` был обычным `HashMap` в панели: после холодного старта при `unknown`
 *     положение падало в backend-default (у 17 из 19 ключей это `false`) — выглядело как выбор
 *     владельца, хотя это выдумка;
 *  2. снимок состояния, посчитанный ДО клика, приходил позже и откатывал тумблер назад: владелец
 *     видел, что его выбор «не приняли», хотя запись могла быть ещё в пути;
 *  3. `unknown` от backend не должен ни в одной ветке превращаться в запись OFF.
 *
 * Проверяется production-функция [Toggles45.holdAfterSnapshot] и факт того, что панель
 * использует её и хранит последнее известное в Prefs2 (инварианты по коду — они ловят
 * «функция есть, а вызова нет», что и было дефектом `mayRestoreWifi` в §7).
 */
class Iter52TogglesUiTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private val panel = "src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt"

    // ───────────────── §9.1: решение принимается по ПРИЧИНЕ, а не по числу ответов ─────────────────
    //
    // iter52 §8.2 держала клик по счётчику несогласий; iter53 §9.1 заменила счётчик причинной
    // моделью (номер клика + ревизия записи в снимке). Поведение «поздний снимок не откатывает
    // клик» сохранено, но доказательство теперь есть, поэтому тесты ниже проверяют его.

    private fun pending(id: Long = 7L, value: Boolean = true, age: Long = 0L) =
        ToolsStateCausality.PendingClick("ttl65", value, id, 0L) // requestedAtMs не важен: age задаём явно

    private fun ack(id: Long, rev: Long, ok: Boolean = true) =
        ToolsStateCausality.WriteAck("ttl65", id, ok, rev)

    private fun decide(
        pending: ToolsStateCausality.PendingClick?,
        ack: ToolsStateCausality.WriteAck?,
        snapshotValue: Boolean?,
        snapshotRev: Long?,
        age: Long = 0L
    ) = ToolsStateCausality.decide(pending, ack, snapshotValue, snapshotRev, age)

    @Test
    fun withoutClickSnapshotIsApplied() {
        assertEquals(ToolsStateCausality.Decision.APPLY_SNAPSHOT,
            decide(null, null, true, 5L))
        assertEquals(ToolsStateCausality.Decision.APPLY_SNAPSHOT,
            decide(null, ack(3, 5), false, 5L))
    }

    @Test
    fun unknownSnapshotNeverOverridesClick() {
        val p = pending()
        assertEquals("неизвестность — не факт, клик держим",
            ToolsStateCausality.Decision.KEEP_CLICK, decide(p, ack(7, 4), null, 9L))
        assertEquals(ToolsStateCausality.Decision.KEEP_CLICK,
            decide(p, null, null, 9L, age = 60_000L))
    }

    @Test
    fun staleSnapshotDoesNotRollClickBack() {
        // Снимок посчитан ДО записи: его ревизия меньше ревизии подтверждения.
        assertEquals("свежесть не доказана — держим выбор владельца",
            ToolsStateCausality.Decision.KEEP_CLICK, decide(pending(), ack(7, 9), false, 8L))
        // Снимок без ревизии ничего не доказывает.
        assertEquals(ToolsStateCausality.Decision.KEEP_CLICK,
            decide(pending(), ack(7, 9), false, null))
        // Записи ещё нет вовсе.
        assertEquals(ToolsStateCausality.Decision.KEEP_CLICK, decide(pending(), null, false, 99L))
        // Подтверждение есть и снимок прочитан ПОСЛЕ записи, значение совпало.
        assertEquals(ToolsStateCausality.Decision.CLEAR_CONFIRMED,
            decide(pending(), ack(7, 9), true, 9L))
    }

    @Test
    fun twoOldResponsesAreStillNotAFact() {
        // Главный дефект iter52: «второй несогласный снимок = факт». Теперь два (и двадцать)
        // старых снимков не перетирают клик — они одинаково НЕ доказывают, что видели запись.
        repeat(20) {
            assertEquals(ToolsStateCausality.Decision.KEEP_CLICK,
                decide(pending(), ack(7, 9), false, 8L))
        }
    }

    @Test
    fun provenContradictionIsShownAsFact() {
        // Запись подтверждена, снимок прочитан после неё и показывает ДРУГОЕ значение — факт.
        assertEquals(ToolsStateCausality.Decision.SNAPSHOT_CONTRADICTS_WRITE,
            decide(pending(), ack(7, 9), false, 9L))
    }

    @Test
    fun failedWriteIsReportedInsteadOfFakeSuccess() {
        assertEquals(ToolsStateCausality.Decision.WRITE_FAILED,
            decide(pending(), ack(7, 9, ok = false), true, 9L))
    }

    @Test
    fun ackOfNewerClickSupersedesOldOne() {
        // Владелец кликнул ещё раз (номер больше): старый клик больше не держим.
        assertEquals(ToolsStateCausality.Decision.APPLY_SNAPSHOT,
            decide(pending(id = 7), ack(8, 9), false, 9L))
    }

    @Test
    fun missingAckBecomesHonestUnconfirmed() {
        assertEquals("в пределах ожидания держим",
            ToolsStateCausality.Decision.KEEP_CLICK, decide(pending(), null, false, 9L, age = 100L))
        assertEquals("по истечении — честно «не подтверждено», а не выдуманный успех",
            ToolsStateCausality.Decision.UNCONFIRMED,
            decide(pending(), null, false, 9L, age = ToolsStateCausality.UNCONFIRMED_AFTER_MS))
    }

    @Test
    fun clickIdsAreMonotonicAndAcksKeepTheNewest() {
        ToolsStateCausality.resetForTest()
        val a = ToolsStateCausality.nextClickId()
        val b = ToolsStateCausality.nextClickId()
        assertTrue("номера кликов растут: $a → $b", b > a)
        ToolsStateCausality.recordAck(ack(b, 10))
        ToolsStateCausality.recordAck(ack(a, 11)) // старый клик не должен перетереть новый ack
        assertEquals(b, ToolsStateCausality.ackFor("ttl65")?.clickId)
        // «Чужой» отправитель без номера не создаёт подтверждения.
        ToolsStateCausality.recordAck(ack(-1, 12))
        assertEquals(b, ToolsStateCausality.ackFor("ttl65")?.clickId)
    }

    @Test
    fun acksSurviveWireRoundTrip() {
        ToolsStateCausality.resetForTest()
        ToolsStateCausality.recordAck(ack(5, 42))
        val json = ToolsStateCausality.acksJson()
        val back = ToolsStateCausality.readAcks(json)
        assertEquals(5L, back["ttl65"]?.clickId)
        assertEquals(42L, back["ttl65"]?.rev)
        assertEquals(true, back["ttl65"]?.ok)
        // Мусор вместо $acks — «подтверждений нет», а не падение снимка.
        assertTrue(ToolsStateCausality.readAcks(com.google.gson.JsonObject()).isEmpty())
        ToolsStateCausality.resetForTest()
    }

    // ───────────────── §8.2: холодный старт ─────────────────

    @Test
    fun coldStartUnknownFallsBackToLastKnownNotToDefaultOff() {
        // Подтверждённое ранее «включено» + сейчас unknown: показываем «включено»,
        // а не default (у большинства ключей это false).
        assertEquals(true, Toggles45.switchPosition("unknown", true, false))
        assertEquals("последнее подтверждённое положение запоминается",
            true, Toggles45.knownPosition("on"))
        // Нет никакой информации: положение = default, но это и есть «нет информации».
        assertEquals(false, Toggles45.switchPosition("unknown", null, false))
        assertEquals("и это НЕ записывается как известное", null, Toggles45.knownPosition("unknown"))
        // Явный факт сильнее последнего известного.
        assertEquals(false, Toggles45.switchPosition("off", true, true))
    }

    // ───────────────── §8.2: инварианты production-пути ─────────────────

    @Test
    fun panelPersistsLastKnownAcrossColdStart() {
        val p = code(panel)
        assertTrue("последнее известное читается из Prefs2",
            p.contains("Prefs2.getBooleanOrNull(activity, lastKnownKey(key))"))
        assertTrue("и записывается туда же", p.contains("Prefs2.putBoolean(activity, lastKnownKey(key), value)"))
        assertTrue("ключ привязан к тумблеру", p.contains("\"tools_lastknown_" + "\$key\""))
        assertFalse("никакой ветки, которая пишет настройку из «неизвестно»",
            p.contains("Prefs2.putBoolean(activity, key,"))
    }

    @Test
    fun panelReallyUsesCausalDecision() {
        val p = code(panel)
        assertTrue("решение принимается production-функцией причины, а не счётчиком",
            p.contains("ToolsStateCausality.decide("))
        assertFalse("эвристика «два ответа = факт» удалена",
            p.contains("holdAfterSnapshot(") || p.contains("clickHold"))
        assertTrue("номер клика ставится на клике", p.contains("ToolsStateCausality.nextClickId()"))
        assertTrue("номер клика уходит в запись", p.contains("addProperty(\"clickId\", clickId)"))
        assertTrue("и видно в журнале, почему снимок не применён", p.contains("TOGGLE_HOLD"))
        assertTrue("конфликт назван честно", p.contains("TOGGLE_CONFLICT"))
        // Красного текста и новых кнопок для этих состояний быть не должно (мандат §8.2).
        assertFalse("нет выдуманных кнопок для unknown", p.contains("\"Повторить\""))
        assertFalse("нет красного текста про unknown", p.contains("#E5484D\") // unknown"))
    }

    @Test
    fun unknownLabelIsHonestText() {
        val p = code(panel)
        assertTrue("подпись про непрочитанное состояние", p.contains("состояние ещё не прочитано"))
        assertTrue("подпись про неподтверждённое состояние", p.contains("состояние не подтверждено"))
        assertTrue("подпись про неизвестный JIT", p.contains("состояние неизвестно"))
    }
}
