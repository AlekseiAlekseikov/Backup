package com.example.voiceapp3.tools

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
}
