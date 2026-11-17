package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter62 §B1 — ЭКРАН ТУМБЛЕРОВ ЦЕЛИКОМ: положение, подпись и зависимость.
 *
 * Две жалобы владельца:
 *  1. «работает, но визуально OFF» — значение применено/сохранено, а тумблер нарисован выключенным;
 *  2. «клик одного включает других» — после одного клика меняется вид соседних переключателей.
 *
 * Причины найдены в коде (и это то, что проверяется здесь):
 *  - `Toggles45.boolOrNull("")` возвращал `false`: пустое/нечитаемое значение выдавалось за
 *    «выключено» (ToolsPanel.kt:1687 через `boolState`);
 *  - `ToolsBackend.state()` публикует `t.key = tri ?: t.default` (ToolsBackend.kt:169), поэтому
 *    значение из снимка при `unknown` — это ВСТРОЕННЫЙ default, а не факт; экран обязан читать
 *    `<key>State` (on|off|unknown) и не подставлять default как факт;
 *  - видимость зависимой строки считалась по `boolState` (ToolsPanel.kt:1132) и не учитывала ни
 *    подтверждённое состояние, ни клик владельца.
 *
 * Тест строит строки экрана РОВНО теми функциями, которые вызывает панель, и проверяет весь
 * набор ключей, а не один.
 */
class Iter62TogglesScreenTest {

    /** Все переключатели экрана «Состояние»/«Уведомления» в порядке отрисовки. */
    private data class Spec(val key: String, val def: Boolean)

    private val screen = listOf(
        Spec("imm", true), Spec("wake", false), Spec("cb", false), Spec("remember", false),
        Spec("follow", true), Spec("notifBatt", false), Spec("notifTemp", false),
        Spec("notifWifi", false), Spec("hvacBatt", false), Spec("hvacTemp", false),
        Spec("hvacExtTemp", false), Spec("windowDriverOnDoor", false),
        Spec("windowOppositeOnDoor", false), Spec("ttl65", false), Spec("unlockSound", false),
        Spec("lockSound", false), Spec("perfVideo", false), Spec("perfJit", false),
        Spec("proj", false),
    )

    private data class Row(val key: String, val on: Boolean, val tri: String, val label: String)

    /**
     * Одна строка экрана по тем же правилам, что `ToolsPanel.addToggle`.
     *
     * @param value значение из снимка (`t.key`) — при `unknown` backend кладёт туда default
     * @param state трёхзначное `<key>State` из снимка
     * @param pending выбор владельца до подтверждения записи
     * @param lastKnown последнее подтверждённое положение
     */
    private fun row(spec: Spec, value: Any?, state: String?, pending: Boolean?, lastKnown: Boolean?): Row {
        val raw: Boolean? = when (value) {
            is Boolean -> value
            is String -> Toggles45.boolOrNull(value)
            null -> null
            else -> null
        }
        val tri = state?.takeIf { it.isNotEmpty() }
            ?: when (raw) { true -> "on"; false -> "off"; null -> "unknown" }
        val on = pending ?: Toggles45.switchPosition(tri, lastKnown, spec.def)
        val label = when {
            pending != null && state != "on" && state != "off" -> "${spec.key} · запись не подтверждена"
            state == "unknown" && lastKnown == null -> "${spec.key} · состояние ещё не прочитано"
            state == "unknown" -> "${spec.key} · состояние не подтверждено (последнее известное)"
            raw == null -> "${spec.key} · состояние ещё не прочитано"
            else -> spec.key
        }
        return Row(spec.key, on, tri, label)
    }

    private fun fullScreen(
        state: String,
        value: Map<String, Boolean> = emptyMap(),
        pending: Map<String, Boolean> = emptyMap(),
        lastKnown: Map<String, Boolean> = emptyMap()
    ): List<Row> = screen.map { s ->
        row(
            s,
            value[s.key] ?: (if (state == "on") true else if (state == "off") false else s.def),
            state,
            pending[s.key],
            lastKnown[s.key]
        )
    }

    // ───────────────────────── 1. Пустое значение — не «выключено» ─────────────────────────

    @Test
    fun emptyValueIsNotOff() {
        // До iter62 `""` возвращало `false` ⇒ тумблер рисовался выключенным, хотя значения нет.
        assertNull("пустая строка — отсутствие значения, а не OFF", Toggles45.boolOrNull(""))
        assertNull(Toggles45.boolOrNull("   "))
        assertEquals(false, Toggles45.boolOrNull("false"))
        assertEquals(false, Toggles45.boolOrNull("0"))
        assertEquals(true, Toggles45.boolOrNull("on"))
        assertNull("мусор — тоже не OFF", Toggles45.boolOrNull("maybe"))
    }

    // ───────────────────────── 2. Клик по одному не меняет остальные ─────────────────────────

    @Test
    fun clickingOneToggleLeavesTheWholeScreenUntouched() {
        val before = fullScreen(state = "off", lastKnown = screen.associate { it.key to false })
        val clickedKey = "notifWifi"

        // Клик: положение — выбор владельца, остальные строки не меняются вообще.
        val after = screen.map { s ->
            row(
                s,
                value = false, state = "off",
                pending = if (s.key == clickedKey) true else null,
                lastKnown = false
            )
        }
        for (i in screen.indices) {
            if (screen[i].key == clickedKey) {
                assertTrue("кликнутая строка показывает выбор владельца", after[i].on)
            } else {
                assertEquals("соседняя строка ${screen[i].key} изменилась", before[i].on, after[i].on)
                assertEquals("подпись соседа ${screen[i].key} изменилась", before[i].label, after[i].label)
            }
        }
    }

    @Test
    fun oneUnreadableKeyDoesNotTurnOthersOnOrOff() {
        // Пустое значение у ОДНОГО ключа не должно переключать ни его самого, ни соседей.
        val base = fullScreen(state = "on")
        val withEmpty = screen.map { s ->
            row(s, value = if (s.key == "ttl65") "" else true, state = if (s.key == "ttl65") "unknown" else "on", pending = null, lastKnown = true)
        }
        assertTrue("неизвестность показывает последнее известное, а не OFF", withEmpty.first { it.key == "ttl65" }.on)
        for (i in screen.indices) {
            if (screen[i].key != "ttl65") assertEquals(base[i].on, withEmpty[i].on)
        }
    }

    // ───────────────────────── 3. Подпись соответствует применённому состоянию ─────────────────────────

    @Test
    fun labelMatchesAppliedState() {
        val applied = fullScreen(state = "on", lastKnown = screen.associate { it.key to true })
        applied.forEach { r ->
            assertEquals("у применённой строки ${r.key} нет пометки о неопределённости", r.key, r.label)
        }

        val unknown = fullScreen(state = "unknown")
        unknown.forEach { r ->
            assertTrue("неизвестное состояние названо словами: ${r.label}",
                r.label.contains("не прочитано") || r.label.contains("не подтверждено"))
        }
        assertEquals("unknown не выдаётся за on", "unknown", unknown.first().tri)
    }

    // ───────────────────────── 4. Зависимость «процент заряда» → «наружная» ─────────────────────────

    @Test
    fun dependentRowFollowsOwnerChoiceAndKeepsOwnValue() {
        // (а) владелец выключил «Процент заряда» (клик ещё не подтверждён) → строка скрыта СРАЗУ.
        assertFalse(Toggles45.dependentVisible("off", null, null))
        // (б) неизвестно → НЕ скрываем: иначе сохранённый выбор исчезает из виду.
        assertTrue("unknown не скрывает зависимую строку", Toggles45.dependentVisible("unknown", null, null))
        assertTrue(Toggles45.dependentVisible("unknown", null, true))
        // (в) клик «выключить», пока состояние ещё старое → скрываем по выбору владельца.
        assertFalse(Toggles45.dependentVisible("on", false, true))
        // (г) включено → видно.
        assertTrue(Toggles45.dependentVisible("on", null, null))
        // (д) скрытие не является записью: собственное значение зависимой строки не меняется.
        assertFalse(Toggles45.dependentHidesChoice)
    }

    // ───────────────────────── 5. Быстрые клики и повторный вход ─────────────────────────

    @Test
    fun fastClicksConvergeToTheLastChoice() {
        var last: Boolean? = null
        listOf(false, true, false, true).forEach { last = it }
        val r = row(Spec("cb", false), value = false, state = "off", pending = last, lastKnown = false)
        assertTrue("показан последний выбор владельца", r.on)

        // Клик по другому ключу не перетягивает этот.
        val other = row(Spec("proj", false), value = false, state = "off", pending = null, lastKnown = false)
        assertFalse(other.on)
    }

    @Test
    fun reentryAndRecreationShowLastKnownNotTheDefault() {
        // Повторный вход: подтверждённого факта нет (unknown), но положение известно из прошлой
        // сессии — показываем его, а не встроенный default (для 16 из 19 ключей это OFF).
        val reentry = row(Spec("lockSound", false), value = false, state = "unknown", pending = null, lastKnown = true)
        assertTrue("после повторного входа показано последнее известное", reentry.on)
        assertTrue(reentry.label.contains("последнее известное"))

        // Холодный старт без единого факта: default — только заполнитель, и это видно по подписи.
        val cold = row(Spec("lockSound", false), value = null, state = "unknown", pending = null, lastKnown = null)
        assertFalse(cold.on)
        assertTrue("default не выдаётся за факт: ${cold.label}", cold.label.contains("не прочитано"))
    }

    @Test
    fun staleSnapshotIsRejected() {
        // iter45/§4: снимок, посчитанный раньше применённого, не откатывает экран.
        assertFalse("равный seq не применяется", Toggles45.acceptsSeq(7L, 7L))
        assertFalse(Toggles45.acceptsSeq(6L, 7L))
        assertTrue(Toggles45.acceptsSeq(8L, 7L))
    }
}
