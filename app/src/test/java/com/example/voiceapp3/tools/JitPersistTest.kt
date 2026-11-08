package com.example.voiceapp3.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33l §3.5 — JIT: init применяет `persist.X` к `X`, поэтому ОСНОВНОЙ ключ —
 * `persist.dalvik.vm.usejit` (из него при загрузке станет `dalvik.vm.usejit`).
 *
 * Лог 07.11: `JIT_PROBE: runtime=false persist=true desired=true` — маркер
 * `persist.sys.dalvik.vm.usejit` стоял, а runtime-проп не поднимался.
 */
class JitPersistTest {

    private fun perf(): String = listOf(
        File("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt"),
        File("app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
    ).first { it.isFile }.readText()

    @Test
    fun initKeyPresent() {
        val s = perf()
        assertTrue("нет const persist.dalvik.vm.usejit",
            s.contains("PROP_PERSIST_USEJIT_INIT = \"persist.dalvik.vm.usejit\""))
        assertTrue("старый ключ остаётся маркером",
            s.contains("PROP_PERSIST_USEJIT = \"persist.sys.dalvik.vm.usejit\""))
    }

    @Test
    fun backfillWritesBothKeys() {
        val s = perf()
        assertTrue("setprop init-ключа", s.contains("setprop \$PROP_PERSIST_USEJIT_INIT \$value"))
        assertTrue("setprop маркера", s.contains("setprop \$PROP_PERSIST_USEJIT \$value"))
        assertTrue("read-back обоих", s.contains("JIT_PERSIST_READBACK") && s.contains("JIT_PERSIST_MARKER"))
    }

    @Test
    fun bootProbePrintsThreeValues() {
        val s = perf()
        // в исходнике это `"echo PI=\\$(getprop $PROP_PERSIST_USEJIT_INIT)"` — проверяем обе части
        assertTrue("JIT_PROBE должен читать init-ключ",
            s.contains("echo PI=") && s.contains("getprop \$PROP_PERSIST_USEJIT_INIT"))
        assertTrue("boot-строка JIT_BOOT", s.contains("JIT_BOOT runtime="))
        assertTrue("вердикт «поднимется после ребута»", s.contains("expect_runtime_true_after_reboot="))
    }
}
