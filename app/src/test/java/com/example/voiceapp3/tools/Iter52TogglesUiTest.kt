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

    // ───────────────── §8.2: удержание клика ─────────────────

    @Test
    fun withoutClickSnapshotIsApplied() {
        assertEquals(Toggles45.HoldOutcome.APPLY_SNAPSHOT,
            Toggles45.holdAfterSnapshot(null, true))
        assertEquals(Toggles45.HoldOutcome.APPLY_SNAPSHOT,
            Toggles45.holdAfterSnapshot(null, null))
    }

    @Test
    fun unknownSnapshotNeverOverridesClick() {
        val hold = Toggles45.ClickHold(value = true)
        assertEquals("неизвестность — не факт, клик держим",
            Toggles45.HoldOutcome.KEEP_CLICK, Toggles45.holdAfterSnapshot(hold, null))
        assertEquals(Toggles45.HoldOutcome.KEEP_CLICK,
            Toggles45.holdAfterSnapshot(hold.copy(disagreements = 5), null))
    }

    @Test
    fun lateSnapshotDoesNotRollClickBack() {
        val hold = Toggles45.ClickHold(value = true)
        assertEquals("первый противоречащий снимок может быть старым",
            Toggles45.HoldOutcome.KEEP_CLICK, Toggles45.holdAfterSnapshot(hold, false))
        assertEquals("снимок согласился — удержание снимается",
            Toggles45.HoldOutcome.CLEAR_CONFIRMED, Toggles45.holdAfterSnapshot(hold, true))
    }

    @Test
    fun secondDisagreementShowsTheFactAndSaysSo() {
        val hold = Toggles45.ClickHold(value = true, disagreements = 1)
        assertEquals("второй раз — это факт, а не гонка",
            Toggles45.HoldOutcome.APPLY_SNAPSHOT_CONFLICT, Toggles45.holdAfterSnapshot(hold, false))
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
    fun panelReallyUsesClickHoldDecision() {
        val p = code(panel)
        assertTrue("решение принимается production-функцией",
            p.contains("Toggles45.holdAfterSnapshot("))
        assertTrue("удержание ставится на клике", p.contains("clickHold[key] = "))
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
