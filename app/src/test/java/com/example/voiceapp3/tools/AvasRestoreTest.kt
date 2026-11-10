package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p-fix1 §3/§4 (REVIEW замечания 3 и 4) — AVAS: доказанный id свойства, вердикт только по
 * read-back, контрольные окна после раннего совпадения, планирование на каждый снимок.
 *
 * Тесты бьют по ПОВЕДЕНИЮ чистых функций (план попытки, вердикт, снимок, TTL, расписание), а не
 * только по тексту файла.
 */
class AvasRestoreTest {

    private fun root(): File = listOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun src(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private val file = "src/main/java/com/example/voiceapp3/tools/AvasRestore.kt"

    /** Тело функции по её сигнатуре (баланс скобок) — проверяем поведение, а не соседний текст. */
    private fun bodyOf(source: String, signature: String): String {
        val i = source.indexOf(signature)
        if (i < 0) return ""
        var depth = 0
        for (k in source.indexOf('{', i) until source.length) {
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

    private val sound = listOf(
        AvasRestore.SoundValue(AvasRestore.PROP_SOUND_TYPE, 0, 2),
        AvasRestore.SoundValue(AvasRestore.PROP_SOUND_TYPE, 1, 2)
    )

    private fun snap(mode: Int = 3, snd: List<AvasRestore.SoundValue> = sound, ts: Long = 1_000L) =
        AvasRestore.Snapshot(id = ts, tsMs = ts, bootTag = "boot-A", mode = mode, sound = snd)

    // 1. Доказанный id свойства sound type из референса (`Politica.h(7)`).
    @Test
    fun soundPropIdIsProvenAndDocumented() {
        assertEquals("id из нативной таблицы референса", 0x21407057, AvasRestore.PROP_SOUND_TYPE)
        assertEquals("области как в байткоде w0/u.c", listOf(0, 1), AvasRestore.AREAS.toList())

        val s = src(file)
        assertTrue("в KDoc должно быть указано происхождение id", s.contains("Politica.h(7)"))
        assertTrue("должна быть ссылка на воспроизводимый скрипт", s.contains("scripts/derive_avas_sound_prop.py"))
        // Никакого «угадывания»: префы/поиск по имени больше не участвуют в записи.
        assertFalse("преф-подсказка id запрещена", s.contains("avas_sound_prop_id"))
        assertFalse("поиск свойства по имени запрещён", s.contains("getPropertyList"))
        assertTrue("области перечислены явно", s.contains("AREAS = intArrayOf(0, 1)"))
    }

    // 2. Снимок: атомарный единый формат, кодирование/декодирование без потерь.
    @Test
    fun snapshotRoundTrip() {
        val s = snap()
        val text = AvasRestore.encode(s)
        assertTrue("режим в одном файле со звуком", text.contains("mode=3") && text.contains("sound="))
        val back = AvasRestore.decode(text)
        assertNotNull(back)
        assertEquals(s.mode, back!!.mode)
        assertEquals(s.sound, back.sound)
        assertEquals(s.bootTag, back.bootTag)
        assertEquals(s.id, back.id)
        assertNull("битый снимок не разбирается", AvasRestore.decode("v=1 mode=3"))
        assertNull(AvasRestore.decode(null))
    }

    // 3. Просроченный снимок не применяем (иначе откат ручных изменений через 10 минут).
    @Test
    fun snapshotExpires() {
        val s = snap(ts = 1_000L)
        assertFalse(AvasRestore.isExpired(s, 1_000L + AvasRestore.SNAPSHOT_TTL_MS - 1))
        assertTrue(AvasRestore.isExpired(s, 1_000L + AvasRestore.SNAPSHOT_TTL_MS + 1))
        assertEquals("TTL 10 минут", 600_000L, AvasRestore.SNAPSHOT_TTL_MS)
    }

    // 4. План попытки пишет ТОЛЬКО расхождения; нечитаемое не считается совпадением.
    @Test
    fun planWritesOnlyDifferences() {
        val s = snap()
        val none = AvasRestore.plan(s, 3, sound)
        assertFalse("совпало — не пишем режим", none.needMode)
        assertTrue("совпало — не пишем звук", none.writeSound.isEmpty())
        assertFalse(none.modeUnreadable)

        val drift = AvasRestore.plan(s, 0, listOf(AvasRestore.SoundValue(AvasRestore.PROP_SOUND_TYPE, 0, 0),
            AvasRestore.SoundValue(AvasRestore.PROP_SOUND_TYPE, 1, 2)))
        assertTrue("режим разошёлся — пишем", drift.needMode)
        assertEquals("пишем только разошедшуюся область", 1, drift.writeSound.size)
        assertEquals(0, drift.writeSound.first().area)
        assertEquals("пишем СОХРАНЁННОЕ значение", 2, drift.writeSound.first().value)

        val unreadableSound = AvasRestore.plan(s, 3, listOf(AvasRestore.SoundValue(AvasRestore.PROP_SOUND_TYPE, 0, 2)))
        assertTrue("нечитаемое не пишем", unreadableSound.writeSound.isEmpty())
        assertEquals("нечитаемое отмечено", 1, unreadableSound.soundUnreadable.size)

        val noMode = AvasRestore.plan(s, null, sound)
        assertTrue("режим не прочитался", noMode.modeUnreadable)
        assertFalse("и писать нечего", noMode.needMode)
    }

    // 5. Вердикт: успех — только по read-back; нечитаемое — unverified, а не «совпало».
    @Test
    fun verdictIsReadBackBased() {
        val s = snap()
        assertEquals(AvasRestore.Verdict.ALREADY_OK, AvasRestore.verdict(s, wrote = false, modeAfter = 3, soundAfter = sound))
        assertEquals(AvasRestore.Verdict.OK, AvasRestore.verdict(s, wrote = true, modeAfter = 3, soundAfter = sound))
        assertEquals("read-back режима не совпал", AvasRestore.Verdict.FAILED,
            AvasRestore.verdict(s, wrote = true, modeAfter = 1, soundAfter = sound))
        assertEquals("звук не вернулся", AvasRestore.Verdict.FAILED,
            AvasRestore.verdict(s, wrote = true, modeAfter = 3,
                soundAfter = listOf(AvasRestore.SoundValue(AvasRestore.PROP_SOUND_TYPE, 0, 0),
                    AvasRestore.SoundValue(AvasRestore.PROP_SOUND_TYPE, 1, 0))))
        assertEquals("звук не читается — это НЕ успех", AvasRestore.Verdict.UNVERIFIED,
            AvasRestore.verdict(s, wrote = false, modeAfter = 3, soundAfter = emptyList()))
        assertEquals("частичное чтение — тоже unverified", AvasRestore.Verdict.UNVERIFIED,
            AvasRestore.verdict(s, wrote = false, modeAfter = 3,
                soundAfter = listOf(AvasRestore.SoundValue(AvasRestore.PROP_SOUND_TYPE, 0, 2))))
    }

    // 6. Контрольные окна: 5 с → 12 с → 30 с ×5, и снимок НЕ снимается на первом совпадении.
    @Test
    fun controlWindowsSurviveEarlyMatch() {
        val sched = AvasRestore.ATTEMPT_SCHEDULE_MS
        assertEquals(5_000L, sched[0])
        assertEquals(12_000L, sched[1])
        assertEquals(7, sched.size)
        for (i in 2 until sched.size) assertEquals(30_000L, sched[i] - sched[i - 1])

        val s = src(file)
        val attempt = s.substringAfter("fun restoreAttempt(").substringBefore("Car API через рефлексию")
        assertTrue("снимок снимается только на последнем окне", attempt.contains("lastWindow"))
        assertTrue("условие снятия — подтверждение И последнее окно",
            attempt.contains("if (v == Verdict.OK || v == Verdict.ALREADY_OK)") &&
                attempt.indexOf("lastWindow = attempt >= ATTEMPT_SCHEDULE_MS.size") <
                attempt.indexOf("if (lastWindow) clear(app)"))
        assertTrue("при unverified снимок сохраняется", !attempt.contains("Verdict.UNVERIFIED) clear"))
    }

    // 7. Планирование — на каждый снимок (id), а не «один раз за процесс».
    @Test
    fun schedulingIsPerSnapshot() {
        val s = src(file)
        assertFalse("глобального одноразового флага больше нет", s.contains("scheduledOnce"))
        assertTrue("планируем по id снимка", s.contains("if (scheduledId == snap.id) return"))
        assertTrue("новый снимок — новое планирование", s.contains("scheduledId = snap.id"))
        val ctor = s.substringAfter("fun scheduleRestore(")
        assertTrue("просроченный снимок выбрасывается", ctor.contains("isExpired(snap, nowMs)"))
    }

    // 8. Запись: только сохранённые значения, void-API setIntProperty, никакой муты.
    @Test
    fun writesOnlySavedValues() {
        val users = root().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("setAVASMode") }
            .map { it.name }.toList()
        assertEquals("режим пишется только в AvasRestore.kt", listOf("AvasRestore.kt"), users)

        val s = src(file)
        assertTrue("setAVASMode вызывается с сохранённым значением",
            s.contains("getMethod(\"setAVASMode\", Int::class.javaPrimitiveType)") && s.contains("invoke(audio, value)"))
        for (bad in listOf("setAVASMode(0)", "setAVASMode(1)", "setAVASMode(2)", "setAVASMode(-1)")) {
            assertTrue("константа в setAVASMode запрещена: $bad", !s.contains(bad))
        }
        assertTrue("sound type пишется по доказанному id", s.contains("PROP_SOUND_TYPE"))
        assertTrue("void-API: успех не по возврату, а по read-back",
            s.contains("setIntProperty") && s.contains("**void**") && s.contains("read-back"))
        assertFalse("Boolean-возврат у void-метода больше не парсится", s.contains("as? Boolean ?: false"))

        val code = s.lineSequence()
            .filterNot {
                it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*")
            }.joinToString("\n")
        for (bad in listOf("setSoundType", "audioserver", "avas_muted_saved", ".wav", "sound_type_")) {
            assertTrue("запрещённая операция в коде: $bad", !code.contains(bad))
        }
    }

    // 9. Call-sites — ровно две (§3.4): цепочка тумблера и старт приложения.
    @Test
    fun callSitesAreOnlyTwo() {
        val hits = root().walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    (it.readText().contains("AvasRestore.saveBeforeRestart") ||
                        it.readText().contains("AvasRestore.scheduleRestore") ||
                        it.readText().contains("fun scheduleRestore("))
            }
            .map { it.name }.toList()
        assertTrue("ожидаются только AvasRestore.kt и AppPerformance.kt: $hits",
            hits.containsAll(listOf("AvasRestore.kt", "AppPerformance.kt")) && hits.size == 2)

        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val apply = perf.substringAfter("fun jitApplyWithZygote").substringBefore("JIT_ZYGOTE_END")
        assertTrue("сохранение в цепочке тумблера", apply.contains("AvasRestore.saveBeforeRestart"))
        assertTrue("сохранение ДО подготовки сети",
            apply.indexOf("AvasRestore.saveBeforeRestart") < apply.indexOf("JitNet.runPreRestart"))
        val probe = bodyOf(perf, "fun jitBootProbe(context: Context)")
        assertTrue("на обычном старте — только планирование", probe.contains("AvasRestore.scheduleRestore"))
        assertFalse("AVAS не восстанавливается «сразу»", perf.contains("AvasRestore.restoreAttempt("))
    }
}
