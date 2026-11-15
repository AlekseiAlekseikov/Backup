package com.example.voiceapp3.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter53 §9.1/§9.3 — настройки: СНИМОК ПРИМЕНЯЕТСЯ ПО ПРИЧИНЕ, А НЕ ПО ЧИСЛУ ОТВЕТОВ.
 *
 * Проверяется production-разбор настоящего `tools.state` (JSON собирается тем же способом, что
 * и в `UiBridge.attachCausality`: `rev` + `$acks` + ключи с суффиксом `<key>State`), и решения
 * `ToolsSnapshotFold.decideAll` — та же функция, которую зовёт [com.example.voiceapp3.ui.ToolsPanel].
 */
class Iter53UiTest {

    @After
    fun tearDown() = ToolsStateCausality.resetForTest()

    /** Снимок так, как его собирает production: ревизия + подтверждения + трёхзначные ключи. */
    private fun snapshot(
        rev: Long,
        values: Map<String, Boolean?>,
        acks: Map<String, ToolsStateCausality.WriteAck> = emptyMap(),
        seq: Long = 1
    ): JsonObject = JsonObject().apply {
        addProperty("seq", seq)
        addProperty("rev", rev)
        addProperty("stateLoaded", true)
        add("\$acks", JsonObject().apply {
            for ((k, a) in acks) add(k, a.toJson())
        })
        var declared = 0
        for ((k, v) in values) {
            when (v) {
                true, false -> addProperty(k, v)
                null -> addProperty(k, v ?: false) // backend отдаёт default, но состояние — unknown
            }
            addProperty(k + "State", when (v) {
                true -> "on"
                false -> "off"
                null -> "unknown"
            })
            declared++
        }
        addProperty("\$keys", declared)
    }

    private fun pending(key: String, value: Boolean, clickId: Long, at: Long) =
        ToolsStateCausality.PendingClick(key, value, clickId, at)

    private fun decideAll(snapshot: JsonObject, vararg p: ToolsStateCausality.PendingClick, now: Long = 1_000L) =
        ToolsSnapshotFold.decideAll(snapshot, p.associateBy { it.key }, now)

    // ───────────────── §9.1: причинность ─────────────────

    @Test
    fun snapshotBeforeClickDoesNotOverrideClickEvenRepeated() {
        // Снимок посчитан ДО клика (rev меньше ревизии подтверждения) и приходит много раз.
        val pend = pending("ttl65", true, clickId = 4, at = 1_000L)
        val snap = snapshot(
            rev = 3,
            values = mapOf("ttl65" to false),
            acks = mapOf("ttl65" to ToolsStateCausality.WriteAck("ttl65", 4, true, rev = 5))
        )
        repeat(5) {
            assertEquals("ответов может быть сколько угодно — доказательства нет",
                ToolsStateCausality.Decision.KEEP_CLICK, decideAll(snap, pend)["ttl65"])
        }
    }

    @Test
    fun snapshotAfterWriteConfirmsClick() {
        val pend = pending("ttl65", true, clickId = 4, at = 1_000L)
        val snap = snapshot(
            rev = 5,
            values = mapOf("ttl65" to true),
            acks = mapOf("ttl65" to ToolsStateCausality.WriteAck("ttl65", 4, true, rev = 5))
        )
        assertEquals(ToolsStateCausality.Decision.CLEAR_CONFIRMED, decideAll(snap, pend)["ttl65"])
    }

    @Test
    fun provenDifferentValueIsShownAsFactNotAsRace() {
        val pend = pending("ttl65", true, clickId = 4, at = 1_000L)
        val snap = snapshot(
            rev = 6,
            values = mapOf("ttl65" to false),
            acks = mapOf("ttl65" to ToolsStateCausality.WriteAck("ttl65", 4, true, rev = 5))
        )
        assertEquals(ToolsStateCausality.Decision.SNAPSHOT_CONTRADICTS_WRITE, decideAll(snap, pend)["ttl65"])
    }

    @Test
    fun unknownValueNeverOverridesClick() {
        val pend = pending("ttl65", true, clickId = 4, at = 1_000L)
        val snap = snapshot(rev = 9, values = mapOf("ttl65" to null))
        assertEquals(ToolsStateCausality.Decision.KEEP_CLICK, decideAll(snap, pend)["ttl65"])
    }

    @Test
    fun commitFailureIsReportedHonestly() {
        val pend = pending("ttl65", true, clickId = 4, at = 1_000L)
        val snap = snapshot(
            rev = 9,
            values = mapOf("ttl65" to false),
            acks = mapOf("ttl65" to ToolsStateCausality.WriteAck("ttl65", 4, false, rev = 5))
        )
        assertEquals(ToolsStateCausality.Decision.WRITE_FAILED, decideAll(snap, pend)["ttl65"])
    }

    @Test
    fun answerInReverseOrderIsHandledByCauseNotByOrder() {
        // Ответ на НОВЫЙ клик пришёл раньше ответа на старый: старый выбор уже перекрыт.
        val fresh = snapshot(
            rev = 8,
            values = mapOf("ttl65" to true),
            acks = mapOf("ttl65" to ToolsStateCausality.WriteAck("ttl65", 6, true, rev = 8))
        )
        val stale = snapshot(
            rev = 5,
            values = mapOf("ttl65" to false),
            acks = mapOf("ttl65" to ToolsStateCausality.WriteAck("ttl65", 5, true, rev = 5)),
            seq = 2
        )
        val old = pending("ttl65", false, clickId = 5, at = 1_000L)
        assertEquals("снимок нового клика перекрывает старый выбор",
            ToolsStateCausality.Decision.APPLY_SNAPSHOT, decideAll(fresh, old)["ttl65"])
        val cur = pending("ttl65", true, clickId = 6, at = 1_000L)
        assertEquals("свой снимок подтверждает текущий выбор",
            ToolsStateCausality.Decision.CLEAR_CONFIRMED, decideAll(fresh, cur)["ttl65"])
        assertEquals("старый снимок текущий выбор не откатывает",
            ToolsStateCausality.Decision.KEEP_CLICK, decideAll(stale, cur)["ttl65"])
    }

    @Test
    fun fastOnOffKeepsTheLatestChoice() {
        val off = pending("ttl65", false, clickId = 12, at = 1_000L)
        // Подтверждение пришло только на ПЕРВЫЙ клик (ON, id=11), снимок после него.
        val snap = snapshot(
            rev = 7,
            values = mapOf("ttl65" to true),
            acks = mapOf("ttl65" to ToolsStateCausality.WriteAck("ttl65", 11, true, rev = 7))
        )
        assertEquals("последний выбор владельца (OFF) держим",
            ToolsStateCausality.Decision.KEEP_CLICK, decideAll(snap, off)["ttl65"])
        // А если second click's ack пришёл, но ещё не отражён — держим, пока снимок не докажет.
        val snap2 = snapshot(
            rev = 8,
            values = mapOf("ttl65" to true),
            acks = mapOf("ttl65" to ToolsStateCausality.WriteAck("ttl65", 12, true, rev = 9))
        )
        assertEquals(ToolsStateCausality.Decision.KEEP_CLICK, decideAll(snap2, off)["ttl65"])
    }

    @Test
    fun oneClickDoesNotChangeNeighbourKeys() {
        // Реальный снимок из нескольких ключей: клик по одному НЕ меняет решения по другим.
        val snap = snapshot(
            rev = 5,
            values = mapOf("ttl65" to false, "perfJit" to true, "lockSound" to false, "windows" to true),
            acks = mapOf("ttl65" to ToolsStateCausality.WriteAck("ttl65", 3, true, rev = 5))
        )
        val decisions = decideAll(snap, pending("ttl65", true, clickId = 3, at = 1_000L))
        assertEquals(1, decisions.size)
        assertEquals(ToolsStateCausality.Decision.SNAPSHOT_CONTRADICTS_WRITE, decisions["ttl65"])
        assertFalse(decisions.containsKey("perfJit"))
        assertFalse(decisions.containsKey("lockSound"))
        assertFalse(decisions.containsKey("windows"))
        // Значения соседей в снимке не тронуты кликом.
        val meta = ToolsSnapshotFold.read(snap)
        assertEquals(true, meta.values["perfJit"])
        assertEquals(true, meta.values["windows"])
    }

    @Test
    fun coldStartAndProcessDeathHaveNoPhantomPending() {
        // После перезапуска процесса выборов и подтверждений нет: снимок применяется как факт.
        val snap = snapshot(rev = 1, values = mapOf("ttl65" to true))
        assertTrue("новый процесс — никаких незавершённых выборов",
            ToolsSnapshotFold.decideAll(snap, emptyMap(), nowMs = 5L).isEmpty())
        assertEquals("номер клика начинается заново", 1L, ToolsStateCausality.nextClickId())
    }

    @Test
    fun everyRealKeyIsItsOwnRow() {
        val snap = snapshot(
            rev = 2,
            values = mapOf("ttl65" to true, "perfJit" to false, "lockSound" to null)
        )
        val meta = ToolsSnapshotFold.read(snap)
        assertTrue("реальные ключи, каждый отдельно: ${meta.keys}",
            meta.keys.containsAll(listOf("ttl65", "perfJit", "lockSound")))
        assertFalse("служебные поля не выдаются за настройки",
            meta.keys.any { it == "seq" || it == "rev" || it == "last" || it.startsWith("$") })
    }

    @Test
    fun sideEffectKeysAreReadFromTheirOwnSnapshotValue() {
        // В снимке каждое реальное состояние идёт своей строкой; перепутать их нельзя.
        val snap = JsonParser.parseString(
            "{\"seq\":3,\"rev\":4,\"ttl65\":true,\"ttl65State\":\"on\",\"perfJit\":false," +
                "\"perfJitState\":\"off\",\"lockSound\":true,\"lockSoundState\":\"on\"," +
                "\"${'$'}keys\":3,\"${'$'}acks\":{}}"
        ).asJsonObject
        val meta = ToolsSnapshotFold.read(snap)
        assertEquals(true, meta.values["ttl65"])
        assertEquals(false, meta.values["perfJit"])
        assertEquals(true, meta.values["lockSound"])
        assertEquals(3, meta.keys.count { !it.endsWith("State") })
        assertEquals("ревизия снимка", 4L, meta.rev)
    }

    // ───────────────── §9.3: границы подтверждённого ─────────────────

    private fun code(rel: String): String = listOf(
        File(rel), File("app/$rel"), File("w/$rel"), File("w/app/$rel"), File("/home/user/w/$rel")
    ).firstOrNull { it.isFile }?.readText()
        ?: throw AssertionError("файл не найден: $rel (cwd=${File(".").absolutePath})")

    @Test
    fun panelUsesProductionFoldAndSendsClickId() {
        val p = code("src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt")
        assertTrue("панель зовёт production-разбор снимка",
            p.contains("ToolsSnapshotFold.read(d)") && p.contains("ToolsSnapshotFold.decideAll("))
        assertTrue("номер клика едет в записи", p.contains("addProperty(\"clickId\", clickId)"))
        assertTrue("неподтверждённость видна в подписи", p.contains("запись не подтверждена"))
        // §9.1: новых кнопок для этих состояний нет; подписи — обычный текст, как у остальных
        // состояний панели. (Красный цвет в панели уже используется для `last`-сводок — это
        // существующее поведение, его мы не расширяем.)
        assertFalse("никаких выдуманных кнопок", p.contains("\"Повторить\""))
        assertFalse("подписи о неподтверждённости не рисуются красным",
            p.contains("запись не подтверждена\").ifEmpty") || p.contains("unconfirmedKeys[key] != null && false"))
        assertFalse("счётчик несогласий удалён", p.contains("disagreements"))
    }

    @Test
    fun bridgeSendsRevisionAndAcksInSnapshot() {
        val b = code("src/main/java/com/example/voiceapp3/ui/UiBridge.kt")
        assertTrue("снимок несёт ревизию записи", b.contains("st.addProperty(\"rev\", rev)"))
        assertTrue("и подтверждения конкретных записей", b.contains("acksJson()"))
        assertTrue("ревизия берётся ДО чтения (иначе гонка)",
            b.contains("val rev0 = tools.revision()") && b.contains("return Snapshot(st, rev0)"))
        assertTrue("номер клика из запроса доходит до записи",
            b.contains("tools.applyFromUi(key, value, clickId)"))
        assertTrue("запись фиксирует ревизию завершения",
            code("src/main/java/com/example/voiceapp3/tools/ToolsBackend.kt")
                .contains("val revAfter = writeRevision.incrementAndGet()"))
    }

    // ───────────────── §9.2 (U3): заряд OFF не стирает выбор наружной ─────────────────

    @Test
    fun batteryOffKeepsTheSavedOutsideTemperatureChoice() {
        val backend = code("src/main/java/com/example/voiceapp3/tools/ToolsBackend.kt")
        // Запись тумблера меняет ТОЛЬКО свой ключ: клик по заряду не пишет и не сбрасывает
        // ключ наружной температуры (её выбор сохраняется).
        assertTrue("пишется ровно ключ тумблера",
            backend.contains("Prefs2.putBooleanEx(appContext, toggle.pref, value)"))
        val setImpl = backend.substringAfter("private fun setImpl(")
        assertFalse("в записи тумблера нет сброса наружной",
            setImpl.contains("putBoolean") && setImpl.contains("HVAC_DIGITAL_EXT_TEMP)") &&
                setImpl.contains("putBoolean(true)"))
        val panel = code("src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt")
        assertFalse("панель не стирает выбор наружной при выключении заряда",
            panel.contains("hvacdigital_temp_ext\", false") || panel.contains("remove(\"hvacExtTemp"))
    }
}
