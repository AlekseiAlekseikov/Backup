package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33n §3 — `JIT_BOOT` печатается ВСЕГДА (на каждом старте и после ребута).
 *
 * Лог 08.11: единственная строка про JIT была `JIT_VERIFY: skipped reason=zygote_restart_removed`
 * — состояние JIT после перезагрузки ГУ по логу восстановить нельзя.
 */
class JitBootTest {

    private fun src(rel: String): String {
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error("не найден $rel (cwd=${File(".").absolutePath})")
        return f.readText()
    }

    @Test
    fun jitBootLineIsUnconditional() {
        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val i = perf.indexOf("fun jitBootProbe(")
        assertTrue("нужна отдельная проба JIT_BOOT", i > 0)
        val body = perf.substring(i, minOf(perf.length, i + 2600))
        // до строки JIT_BOOT нет ранних выходов по префу/каналу
        val beforeLine = body.substring(0, body.indexOf("JIT_BOOT runtime="))
        assertTrue("ранний выход по префу запрещён", !beforeLine.contains("isAutoOptimizeEnabled(app) {"))
        assertTrue("нет `if (!isAutoOptimizeEnabled", !beforeLine.contains("if (!isAutoOptimizeEnabled"))
        for (f in listOf("runtime=", "persist=", "profiles=", "init=", "expect_runtime_true_after_reboot=")) {
            assertTrue("в JIT_BOOT нет поля $f", body.contains(f))
        }
    }

    @Test
    fun keyIsPersistDalvikVmUsejit() {
        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue("init-ключ остаётся persist.dalvik.vm.usejit",
            perf.contains("PROP_PERSIST_USEJIT_INIT = \"persist.dalvik.vm.usejit\""))
        assertTrue("runtime-ключ dalvik.vm.usejit", perf.contains("PROP_USEJIT = \"dalvik.vm.usejit\""))
        assertTrue("profiles читается", perf.contains("dalvik.vm.usejitprofiles"))
        assertTrue("причина init_did_not_carry", perf.contains("reason=init_did_not_carry"))
    }

    @Test
    fun calledOnEveryStart() {
        val app = src("src/main/java/com/example/voiceapp3/App.kt")
        assertTrue("проба вызывается из App.onCreate через BgWorker",
            app.contains("AppPerformance.jitBootProbe(this)"))
    }

    /**
     * iter33n-fix1 §2/§4 — правило владельца 08.11: рестарт фреймворка разрешён ТОЛЬКО в тумблере
     * JIT (по явному клику). Статический поиск call-sites: литерал есть ровно в одном файле и
     * только внутри блока `JIT_ZYGOTE_BEGIN … JIT_ZYGOTE_END`, а сам блок вызывается из
     * `setAutoOptimize` и НЕ вызывается из boot/фоновых путей.
     */
    @Test
    fun zygoteRestartOnlyInsideJitToggleBlock() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val withLiteral = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("ctl.restart zygote") }
            .map { it.name }.toList()
        assertEquals("рестарт фреймворка допустим только в AppPerformance.kt",
            listOf("AppPerformance.kt"), withLiteral)

        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val begin = perf.indexOf("JIT_ZYGOTE_BEGIN")
        val end = perf.indexOf("JIT_ZYGOTE_END")
        assertTrue("нет маркеров блока JIT_ZYGOTE", begin > 0 && end > begin)
        var idx = perf.indexOf("ctl.restart zygote")
        var count = 0
        while (idx >= 0) {
            count++
            assertTrue("литерал вне блока JIT_ZYGOTE (позиция $idx)", idx in begin..end)
            idx = perf.indexOf("ctl.restart zygote", idx + 1)
        }
        assertTrue("в блоке должен быть вызов рестарта", count >= 1)

        assertTrue("тумблер JIT обязан вызывать jitApplyWithZygote",
            perf.contains("jitApplyWithZygote(context, enabled, rtBefore, psBefore)"))
        // iter33o §1.5: разрешённых точек ДВЕ — клик тумблера и авто-восстановление при загрузке.
        for (fn in listOf("fun jitBootProbe", "fun bootRestoreJit", "fun verifyJitOnly",
                          "fun jitPendingVerify", "fun checkPendingZygoteVerify", "fun jitRound")) {
            val body = bodyOf(perf, fn)
            assertTrue("нет функции $fn", body.isNotEmpty())
            assertTrue("$fn не должен запускать рестарт фреймворка", !body.contains("jitApplyWithZygote("))
        }
        assertTrue("клик тумблера — разрешённая точка",
            perf.contains("jitApplyWithZygote(context, enabled, rtBefore, psBefore)"))
        assertTrue("boot-restore — вторая разрешённая точка",
            perf.contains("jitApplyWithZygote(app, true, rt2, ps2, route = \"boot_restore\")"))
        // Больше вызовов нет: 1 определение + ровно 2 вызова.
        assertEquals("вызовов jitApplyWithZygote должно быть 2 (тумблер + boot-restore)",
            3, Regex("jitApplyWithZygote\\(").findAll(perf).count())
    }

    // iter33o §1.5 — авто-восстановление JIT при загрузке: условия и guard «раз за загрузку».
    @Test
    fun bootRestoreOnlyWhenPrefOnAndRuntimeOff() {
        // Чистая функция решения (та же, что в коде).
        assertEquals("run", com.example.voiceapp3.tools.AppPerformance
            .bootRestoreDecision(prefOn = true, runtime = "false", doneTag = "", currentTag = "boot-1"))
        assertEquals("run", com.example.voiceapp3.tools.AppPerformance
            .bootRestoreDecision(prefOn = true, runtime = "empty", doneTag = "", currentTag = "boot-1"))
        assertEquals("skip_pref_off", com.example.voiceapp3.tools.AppPerformance
            .bootRestoreDecision(prefOn = false, runtime = "false", doneTag = "", currentTag = "boot-1"))
        assertEquals("skip_already_on", com.example.voiceapp3.tools.AppPerformance
            .bootRestoreDecision(prefOn = true, runtime = "true", doneTag = "", currentTag = "boot-1"))
        assertEquals("skip_same_boot", com.example.voiceapp3.tools.AppPerformance
            .bootRestoreDecision(prefOn = true, runtime = "false", doneTag = "boot-1", currentTag = "boot-1"))

        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        val arm = bodyOf(perf, "fun jitBootRestoreArm")
        assertTrue("нужна функция jitBootRestoreArm", arm.isNotEmpty())
        assertTrue("guard по boot_tag пишется ДО рестарта",
            arm.indexOf("Prefs2.putString(app, JIT_BOOT_RESTORE_MARKER, tag)") in 1 until
                arm.indexOf("jitApplyWithZygote("))
        assertTrue("маркер называется jit_boot_restore_done",
            perf.contains("JIT_BOOT_RESTORE_MARKER = \"jit_boot_restore_done\""))
        assertTrue("задержка ~30 с", perf.contains("JIT_BOOT_RESTORE_DELAY_MS = 30_000L"))
        assertTrue("отмена, если владелец выключил тумблер",
            arm.contains("reason=pref_off") && arm.contains("reason=already_on"))
        // Вызов из App.onCreate (единственная точка планирования).
        val app = src("src/main/java/com/example/voiceapp3/App.kt")
        assertTrue("App.onCreate обязан планировать boot-restore",
            app.contains("AppPerformance.jitBootRestoreArm(this)"))
    }

    // iter33o §2.3 — устаревшего текста «применится после перезагрузки ГУ» больше нет.
    @Test
    fun bootLineExplainsOffAfterReboot() {
        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue("нужен jit=off_after_reboot", perf.contains("jit=off_after_reboot"))
        assertTrue("нужна подсказка toggle_to_enable", perf.contains("toggle_to_enable"))
        assertTrue("старый текст убран",
            !perf.contains("apply=\\\"применится после перезагрузки ГУ\\\""))
    }

    /**
     * Тело функции по балансу фигурных скобок: `indexOf` + N символов давал ложные срабатывания
     * (соседняя функция попадала в окно).
     */
    private fun bodyOf(src: String, fn: String): String {
        val i = src.indexOf(fn)
        if (i < 0) return ""
        val j = src.indexOf('{', i)
        if (j < 0) return ""
        var depth = 0
        for (k in j until src.length) {
            when (src[k]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(i, k + 1)
                }
            }
        }
        return src.substring(i)
    }
}
