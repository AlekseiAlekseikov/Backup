package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p §3 — AVAS по механизму референса: режим (`getAVASMode`/`setAVASMode`) + sound type
 * (`CarPropertyManager` по областям {0,1}), восстановление НЕ сразу, а по расписанию
 * 5 с → 12 с → ретраи 30 с ×5.
 *
 * Правило владельца 09.11: пишем ТОЛЬКО то, что сами прочитали. Тест держит:
 *  1. решение «ничего / уже ок / вернуть / не смогли» и вердикт попытки (успех — по read-back);
 *  2. расписание попыток и формат строк лога;
 *  3. границы: `setAVASMode` и `setIntProperty` — только в `AvasRestore.kt` и только с сохранёнными
 *     значениями; муты/чужих значений/звуковых файлов нет;
 *  4. точки вызова — ровно две (§3.4).
 */
class AvasRestoreTest {

    private fun src(rel: String): File =
        listOf(File(rel), File("app/$rel")).first { it.isFile }

    private fun read(rel: String): String = src(rel).readText()

    private fun mainRoot(): File = listOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    /** Тело функции по балансу скобок (окно по indexOf давало ложные срабатывания). */
    private fun bodyOf(source: String, fn: String): String {
        val i = source.indexOf(fn)
        if (i < 0) return ""
        val j = source.indexOf('{', i)
        if (j < 0) return ""
        var depth = 0
        for (k in j until source.length) {
            when (source[k]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(i, k + 1)
                }
            }
        }
        return source.substring(i)
    }

    // 1. Сохранённого нет — ничего не делаем.
    @Test
    fun withoutSavedNothingHappens() {
        assertEquals(AvasRestore.Action.NONE, AvasRestore.decide(null, 3))
        assertEquals(AvasRestore.Action.NONE, AvasRestore.decide(null, null))
    }

    // 2. Режим совпал — только лог, никакой записи.
    @Test
    fun alreadyOkIsSkipped() {
        assertEquals(AvasRestore.Action.ALREADY_OK, AvasRestore.decide(3, 3))
    }

    // 3. Отличается — вернуть сохранённое (единственный «пишущий» исход).
    @Test
    fun differentModeIsRestored() {
        assertEquals(AvasRestore.Action.RESTORE, AvasRestore.decide(3, 0))
        assertEquals(AvasRestore.Action.RESTORE, AvasRestore.decide(0, 3))
    }

    // 4. Сохранённое есть, а прочитать не смогли — честный fail без падения.
    @Test
    fun unreadableModeIsFailNotCrash() {
        assertEquals(AvasRestore.Action.FAIL, AvasRestore.decide(2, null))
        assertEquals(
            "AVAS_RESTORE skipped reason=no_getAVASMode mode=2 where=app_start",
            AvasRestore.skipLine("no_getAVASMode", AvasRestore.Where.APP_START, 2)
        )
    }

    /**
     * 5. Расписание (§3.2): первая попытка ~5 с, повтор ~12 с, дальше до 5 ретраев по 30 с.
     * Ложный успех запрещён: `ok` только когда после записи факт совпал с сохранённым.
     */
    @Test
    fun scheduleMatchesReference() {
        val s = AvasRestore.ATTEMPT_SCHEDULE_MS
        assertEquals("первая попытка ~5 с", 5_000L, s[0])
        assertEquals("повтор ~12 с", 12_000L, s[1])
        assertEquals("всего 7 попыток (2 быстрые + 5 ретраев)", 7, s.size)
        for (i in 2 until s.size) {
            assertEquals("ретрай №${i - 1} через 30 с", 30_000L, s[i] - s[i - 1])
        }
        assertEquals("последний ретрай 162 с", 162_000L, s.last())
    }

    // 6. Вердикт попытки: пишем только сохранённое, успех — строго по read-back.
    @Test
    fun verdictRequiresReadBack() {
        assertEquals(
            "уже совпало — писать нечего",
            AvasRestore.Verdict.ALREADY_OK,
            AvasRestore.verdict(3, 3, 3, soundOk = true, wrote = false)
        )
        assertEquals(
            "записали и перечитали сохранённое",
            AvasRestore.Verdict.OK,
            AvasRestore.verdict(3, 0, 3, soundOk = true, wrote = true)
        )
        assertEquals(
            "read-back не совпал ⇒ failed",
            AvasRestore.Verdict.FAILED,
            AvasRestore.verdict(3, 0, 1, soundOk = true, wrote = true)
        )
        assertEquals(
            "sound type не вернулся ⇒ failed",
            AvasRestore.Verdict.FAILED,
            AvasRestore.verdict(3, 0, 3, soundOk = false, wrote = true)
        )
        assertEquals(
            "режим не читается ⇒ failed",
            AvasRestore.Verdict.FAILED,
            AvasRestore.verdict(3, null, null, soundOk = true, wrote = false)
        )
    }

    // 7. Форматы строк лога (§3.1/§3.2).
    @Test
    fun logLinesFollowReference() {
        val sound = listOf(AvasRestore.SoundValue(0x2140, 0, 2), AvasRestore.SoundValue(0x2140, 1, 2))
        assertEquals(
            "AVAS_SAVE mode=3 sound_type=2@0,2@1 prop=8512 where=jit_toggle",
            AvasRestore.saveLine(3, sound, AvasRestore.Where.JIT_TOGGLE)
        )
        assertTrue(
            "нет sound type — честный skipped-признак",
            AvasRestore.saveLine(3, emptyList(), AvasRestore.Where.JIT_TOGGLE)
                .contains("sound_type=unavailable")
        )
        assertTrue(
            "метода нет — пропуск без падения",
            AvasRestore.saveLine(null, emptyList(), AvasRestore.Where.JIT_TOGGLE)
                .startsWith("AVAS_SAVE skipped reason=")
        )
        assertEquals(
            "AVAS_RESTORE attempt=1 mode_saved=3 mode_now=3 sound_saved=2@0 sound_now=0@0 " +
                "result=ok where=jit_toggle",
            AvasRestore.attemptLine(
                1, 3, 3, "2@0", "0@0", AvasRestore.Verdict.OK, AvasRestore.Where.JIT_TOGGLE
            )
        )
        assertTrue(
            "attempt обязателен в строке",
            AvasRestore.attemptLine(
                3, 3, 1, "2@0", "0@0", AvasRestore.Verdict.FAILED, AvasRestore.Where.APP_START
            ).contains("attempt=3")
        )
    }

    // 8. `setAVASMode`/`setIntProperty` — только в AvasRestore.kt и только с сохранёнными значениями.
    @Test
    fun writesOnlyInAvasRestoreAndOnlySavedValues() {
        val hits = mainRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("setAVASMode") }
            .map { it.name }.toList()
        assertEquals("запись режима AVAS допустима только в AvasRestore.kt", listOf("AvasRestore.kt"), hits)

        // Общий `setIntProperty` есть и в других модулях (окна, VHAL) — поэтому границу держим
        // по AVAS-специфичным маркерам, а не по имени метода Car API.
        val hitsSound = mainRoot().walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    (it.readText().contains("restoreSavedAvasSoundType") ||
                        it.readText().contains("avas_sound_saved"))
            }
            .map { it.name }.toList()
        assertEquals("sound type AVAS пишется только в AvasRestore.kt",
            listOf("AvasRestore.kt"), hitsSound)

        val s = read("src/main/java/com/example/voiceapp3/tools/AvasRestore.kt")
        assertTrue(
            "setAVASMode вызывается только с сохранённым значением",
            s.contains("getMethod(\"setAVASMode\", Int::class.javaPrimitiveType)") &&
                s.contains("m.invoke(audio, value)")
        )
        for (bad in listOf("setAVASMode(0)", "setAVASMode(1)", "setAVASMode(2)", "setAVASMode(-1)")) {
            assertTrue("константа в setAVASMode запрещена: $bad", !s.contains(bad))
        }
        // Мута/чужие значения/звуковые файлы — запрещены. Ищем ТОЛЬКО в коде: упоминание
        // запрета в KDoc («чего мы не делаем») операцией не является.
        val code = s.lineSequence()
            .filterNot {
                it.trimStart().startsWith("*") || it.trimStart().startsWith("//") ||
                    it.trimStart().startsWith("/*")
            }
            .joinToString("\n")
        for (bad in listOf("setSoundType", "audioserver", "avas_muted_saved", ".wav", "sound_type_")) {
            assertTrue("запрещённая операция в коде AvasRestore: $bad", !code.contains(bad))
        }
        assertTrue("нужен честный skip no_getAVASMode", s.contains("no_getAVASMode"))
        assertTrue("sound type берётся из CarPropertyManager", s.contains("getIntProperty"))
        // Области — как в байткоде референса (w0/u.c = {0, 1}).
        assertTrue("области {0, 1}", s.contains("AREAS = intArrayOf(0, 1)"))
    }

    // 9. Точки вызова — ровно две (§3.4): цепочка тумблера JIT и старт приложения.
    @Test
    fun callSitesAreOnlyAllowed() {
        val hits = mainRoot().walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    (it.readText().contains("AvasRestore.saveBeforeRestart") ||
                        it.readText().contains("AvasRestore.scheduleRestore") ||
                        it.readText().contains("fun scheduleRestore("))
            }
            .map { it.name }.toList()
        assertTrue("кроме определения остаётся только AppPerformance.kt: $hits",
            hits.containsAll(listOf("AvasRestore.kt", "AppPerformance.kt")) && hits.size == 2)

        val perf = read("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        // (а) цепочка тумблера JIT — сохранение ДО рестарта.
        val apply = bodyOf(perf, "fun jitApplyWithZygote")
        assertTrue("сохранение AVAS обязано быть в цепочке рестарта",
            apply.contains("AvasRestore.saveBeforeRestart"))
        assertTrue("сохранение ДО записи watcher-а",
            apply.indexOf("AvasRestore.saveBeforeRestart") < apply.indexOf("jitZygoteWatcherScript()"))
        // (б) восстановление — по расписанию, в pending-пути и на обычном старте.
        val pending = bodyOf(perf, "fun jitPendingVerify")
        assertTrue("после подъёма планируется возврат AVAS", pending.contains("AvasRestore.scheduleRestore"))
        val probe = bodyOf(perf, "fun jitBootProbe")
        assertTrue("на обычном старте — то же расписание", probe.contains("AvasRestore.scheduleRestore"))
        assertTrue("AVAS не возвращается «сразу» (без расписания)",
            !perf.contains("AvasRestore.restoreAttempt("))
    }
}
