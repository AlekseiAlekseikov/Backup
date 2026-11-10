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
        // iter33p-fix3/4: текст watcher-а перенесён в чистое ядро (`JitNetModel.watcherScript`),
        // поэтому литерал рестарта теперь живёт в JitNetCore.kt, а AppPerformance.kt содержит
        // только делегат. Проверяем ровно это — и что литерал ровно один.
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val withLiteral = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("ctl.restart zygote") }
            .map { it.name }.toList()
        assertEquals("рестарт фреймворка допустим только в скрипте watcher-а (чистое ядро)",
            listOf("JitNetCore.kt"), withLiteral)

        val core = src("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        val scriptAt = core.indexOf("internal fun watcherScript()")
        assertTrue("нет watcherScript() в ядре", scriptAt > 0)
        val scriptBody = core.substring(scriptAt, minOf(core.length, scriptAt + 5_000))
        assertTrue("литерал рестарта обязан быть в теле watcherScript()",
            scriptBody.contains("ctl.restart zygote"))
        assertEquals("рестарт фреймворка в тексте скрипта ровно один",
            1, Regex("setprop ctl\\.restart zygote").findAll(scriptBody).count())

        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue("AppPerformance не должен содержать сам литерал рестарта",
            !perf.contains("ctl.restart zygote"))
        assertTrue("AppPerformance обязан делегировать текст скрипта",
            perf.contains("JitNetModel.watcherScript()"))
        // Скрипт заливается на устройство ТОЛЬКО внутри блока JIT_ZYGOTE (цепочка тумблера).
        val begin = perf.indexOf("JIT_ZYGOTE_BEGIN")
        val end = perf.indexOf("JIT_ZYGOTE_END")
        assertTrue("нет маркеров блока JIT_ZYGOTE", begin > 0 && end > begin)
        var idx = perf.indexOf("JIT_ZYGOTE_WATCH_PATH")
        var count = 0
        while (idx >= 0) {
            count++
            assertTrue("запись/запуск скрипта вне блока JIT_ZYGOTE (позиция $idx)", idx in begin..end)
            idx = perf.indexOf("JIT_ZYGOTE_WATCH_PATH", idx + 1)
        }
        assertTrue("в блоке должна быть запись и запуск скрипта", count >= 2)

        assertTrue("тумблер JIT обязан вызывать jitApplyWithZygote",
            perf.contains("jitApplyWithZygote(context, enabled, rtBefore, psBefore)"))
        // iter33p §1: разрешённая точка ОДНА — клик тумблера (авто-восстановления при загрузке нет).
        for (fn in listOf("fun jitBootProbe", "fun bootRestoreJit", "fun verifyJitOnly",
                          "fun jitPendingVerify", "fun checkPendingZygoteVerify", "fun jitRound")) {
            val body = bodyOf(perf, fn)
            assertTrue("нет функции $fn", body.isNotEmpty())
            assertTrue("$fn не должен запускать рестарт фреймворка", !body.contains("jitApplyWithZygote("))
        }
        // Больше вызовов нет: 1 определение + ровно 1 вызов (клик тумблера).
        assertEquals("вызовов jitApplyWithZygote должно быть 1 (только тумблер)",
            2, Regex("jitApplyWithZygote\\(").findAll(perf).count())
    }

    /**
     * iter33p §1 — авто-восстановления JIT при загрузке БОЛЬШЕ НЕТ (решение владельца 09.11,
     * как в референсе: `setprop dalvik.vm.usejit` только в цепочке клика).
     *
     * Держим статически: в коде нет ни одной части прежнего механизма, а `App.onCreate` его
     * не планирует. Иначе скрытый рестарт фреймворка после загрузки снова сломает сеть.
     */
    @Test
    fun bootRestoreIsGone() {
        val perf = src("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        for (gone in listOf(
            "jitBootRestoreArm", "bootRestoreDecision", "JIT_BOOT_RESTORE_MARKER",
            "JIT_BOOT_RESTORE_DELAY_MS", "boot_restore", "JIT_BOOT_RESTORE"
        )) {
            assertTrue("остаток авто-восстановления в коде: $gone", !perf.contains(gone))
        }
        val app = src("src/main/java/com/example/voiceapp3/App.kt")
        assertTrue("App.onCreate не должен планировать авто-восстановление",
            !app.contains("jitBootRestoreArm"))
        // Единственный путь к рестарту — jitApplyWithZygote из клика тумблера.
        assertTrue("клик тумблера остаётся точкой вызова",
            perf.contains("jitApplyWithZygote(context, enabled, rtBefore, psBefore)"))
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
