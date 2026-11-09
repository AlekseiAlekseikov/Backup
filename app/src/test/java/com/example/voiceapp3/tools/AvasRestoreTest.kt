package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33o §1 — восстановление AVAS по механизму референса `CentralWatchdogService.z`.
 *
 * Правило владельца 09.11: разрешён ТОЛЬКО возврат ПРЕЖДЕ СОХРАНЁННОГО режима.
 * Тест держит три вещи:
 *  1. решение «ничего / уже ок / вернуть / не смогли» (чистая функция [AvasRestore.decide]);
 *  2. формат строк лога (`AVAS_SAVE …`, `AVAS_RESTORE …`, `… skipped reason=…`);
 *  3. границы: `setAVASMode` вызывается РОВНО с сохранённым значением и только в `AvasRestore.kt`,
 *     а точки вызова (`saveBeforeRestart` / `restoreAfterRestart`) — только три разрешённых места.
 */
class AvasRestoreTest {

    private fun src(rel: String): File =
        listOf(File(rel), File("app/$rel")).first { it.isFile }

    private fun read(rel: String): String = src(rel).readText()

    private fun mainRoot(): File = listOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

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
        assertEquals(
            "AVAS_RESTORE skipped reason=already_ok mode=3 where=jit_toggle",
            AvasRestore.restoreSkipLine("already_ok", AvasRestore.Where.JIT_TOGGLE, 3)
        )
    }

    // 3. Отличается — вернуть сохранённое (и это единственный «пишущий» исход).
    @Test
    fun differentModeIsRestored() {
        assertEquals(AvasRestore.Action.RESTORE, AvasRestore.decide(3, 0))
        assertEquals(AvasRestore.Action.RESTORE, AvasRestore.decide(0, 3))
    }

    // 4. Сохранённое есть, а прочитать не смогли (Car API недоступен) — честный fail без падения.
    @Test
    fun unreadableModeIsFailNotCrash() {
        assertEquals(AvasRestore.Action.FAIL, AvasRestore.decide(2, null))
        assertEquals(
            "AVAS_RESTORE skipped reason=no_getAVASMode mode=2 where=app_start",
            AvasRestore.restoreSkipLine("no_getAVASMode", AvasRestore.Where.APP_START, 2)
        )
    }

    // 5. Форматы строк лога: сохранение и восстановление (успех только по read-back).
    @Test
    fun logLinesFollowReference() {
        assertEquals("AVAS_SAVE mode=3 where=jit_toggle",
            AvasRestore.saveLine(3, AvasRestore.Where.JIT_TOGGLE))
        assertTrue("skip при недоступном методе",
            AvasRestore.saveLine(null, AvasRestore.Where.BOOT_RESTORE)
                .startsWith("AVAS_SAVE skipped reason="))
        val ok = AvasRestore.restoreLine(3, 0, writeOk = true, after = 3, where = AvasRestore.Where.BOOT_RESTORE)
        assertEquals("AVAS_RESTORE mode_saved=3 mode_found=0 result=ok mode_after=3 where=boot_restore", ok)
        val failed = AvasRestore.restoreLine(3, 0, writeOk = true, after = 1, where = AvasRestore.Where.JIT_TOGGLE)
        assertTrue("read-back не совпал ⇒ failed (ложный успех запрещён)", failed.contains("result=failed"))
        val notWritten = AvasRestore.restoreLine(3, 0, writeOk = false, after = null, where = AvasRestore.Where.JIT_TOGGLE)
        assertTrue(notWritten.contains("result=failed") && notWritten.contains("mode_after=unknown"))
    }

    // 6. `setAVASMode` — только в AvasRestore.kt и только с сохранённым значением.
    @Test
    fun setAvasModeOnlyInAvasRestore() {
        val hits = mainRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("setAVASMode") }
            .map { it.name }.toList()
        assertEquals("запись режима AVAS допустима только в AvasRestore.kt", listOf("AvasRestore.kt"), hits)

        val s = read("src/main/java/com/example/voiceapp3/tools/AvasRestore.kt")
        // Единственный вызов — `setAVASMode(<параметр saved>)`, никаких констант/нулей.
        assertTrue("setAVASMode должен вызываться с сохранённым значением",
            s.contains("getMethod(\"setAVASMode\", Int::class.javaPrimitiveType)") &&
                s.contains("m.invoke(audio, value)"))
        for (bad in listOf("setAVASMode(0)", "setAVASMode(1)", "setAVASMode(2)", "setAVASMode(3)")) {
            assertTrue("константа в setAVASMode запрещена: $bad", !s.contains(bad))
        }
        // Мута/смена sound type/звуковой демон — запрещены. Ищем ТОЛЬКО в коде:
        // упоминание в KDoc («чего мы не делаем») — не операция и падать не должно.
        val code = s.lineSequence()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
        for (bad in listOf("setSoundType", "audioserver", "avas_muted_saved", "setAVASMode(-1)")) {
            assertTrue("запрещённая операция в коде AvasRestore: $bad", !code.contains(bad))
        }
        // Отсутствие метода — skip, без падения и без ретраев.
        assertTrue("нужен честный skip no_getAVASMode", s.contains("no_getAVASMode"))
        assertTrue("без retry-циклов", !s.contains("for (attempt") && !s.contains("while (attempt"))
    }

    // 7. Точки вызова — только три разрешённых места (§1.3).
    @Test
    fun callSitesAreOnlyAllowed() {
        val hits = mainRoot().walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    (it.readText().contains("saveBeforeRestart") || it.readText().contains("restoreAfterRestart"))
            }
            .map { it.name }.toList()
        assertTrue("кроме определения остаётся только AppPerformance.kt: $hits",
            hits.containsAll(listOf("AvasRestore.kt", "AppPerformance.kt")) && hits.size == 2)

        val perf = read("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        // (а) цепочка тумблера JIT: сохранение — внутри jitApplyWithZygote.
        val apply = perf.substringAfter("fun jitApplyWithZygote")
        assertTrue("сохранение AVAS обязано быть в цепочке рестарта",
            apply.substringBefore("val launch").contains("AvasRestore.saveBeforeRestart"))
        // (б) восстановление — в pending-пути и на обычном старте.
        val pending = perf.substringAfter("fun jitPendingVerify")
        assertTrue("восстановление AVAS в pending-пути",
            pending.substringBefore("// ═══════════ JIT_ZYGOTE_BEGIN").contains("AvasRestore.restoreAfterRestart"))
        val probe = perf.substringAfter("fun jitBootProbe")
        assertTrue("восстановление AVAS на обычном старте",
            probe.substringBefore("// ═══════════ iter33o").contains("AvasRestore.Where.APP_START"))
        // (в) boot-restore идёт тем же jitApplyWithZygote(route=boot_restore) — значит та же точка.
        assertTrue("boot-restore использует route=boot_restore",
            perf.contains("route = \"boot_restore\""))
    }
}
