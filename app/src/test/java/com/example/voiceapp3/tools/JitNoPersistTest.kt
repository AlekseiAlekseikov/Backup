package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JIT не пишет `persist.dalvik.vm.usejit`, `persist.sys.dalvik.vm.usejit`, `dalvik.vm.usejitprofiles`.
 * В референсе есть ровно `setprop dalvik.vm.usejit <bool>`; остатки прежних версий на ГУ только
 * читаются и требуют решения владельца.
 */
class JitNoPersistTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
            .filterNot {
                val t = it.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("#")
            }.joinToString("\n")

    @Test
    fun noPersistWrites() {
        for (f in listOf("JitNetCore.kt", "JitNet.kt", "AppPerformance.kt")) {
            val c = code("src/main/java/com/example/voiceapp3/tools/$f")
            assertFalse("$f: нет записи init-ключа", c.contains("setprop \$PROP_PERSIST_USEJIT_INIT"))
            assertFalse("$f: нет записи маркера", c.contains("setprop \$PROP_PERSIST_USEJIT"))
            assertFalse("$f: нет записи profiles", c.contains("setprop \$PROP_USEJIT_PROFILES"))
            assertFalse("$f: нет литералов persist-записи",
                c.contains("setprop persist.dalvik.vm.usejit") || c.contains("setprop persist.sys.dalvik.vm.usejit"))
        }
    }

    @Test
    fun chainHasSinglePropertyWrite() {
        val script = JitRef.chainScript(true, mountStep = true)
        assertTrue(script.contains("setprop dalvik.vm.usejit true"))
        assertFalse(script.contains("persist."))
        assertFalse(script.contains("usejitprofiles"))
    }

    @Test
    fun legacyValuesAreReadOnly() {
        val ap = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue(ap.contains("getprop \$PROP_PERSIST_USEJIT_INIT"))
        assertTrue(ap.contains("JIT_BOOT_LEGACY"))
        assertFalse("нет автоочистки старых значений", ap.contains("clearLegacy") || ap.contains("remove(\"persist"))
    }
}
