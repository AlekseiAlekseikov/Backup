package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p (1.0.61) — JIT не пишет persist-свойства и не трогает `usejitprofiles`.
 *
 * В референсе (`w0/la.run`) есть ровно `setprop dalvik.vm.usejit <bool>` — и всё. Прежние
 * `persist.dalvik.vm.usejit`, `persist.sys.dalvik.vm.usejit`, `dalvik.vm.usejitprofiles` удалены
 * вместе с boot-backfill-ом; на ГУ такие остатки ТОЛЬКО читаются (мандат §1.2).
 */
class JitNoPersistTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readLines()
            .filterNot { l ->
                val t = l.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("#")
            }.joinToString("\n")

    @Test
    fun noPersistWritesAnywhereInJitCode() {
        for (f in listOf("JitNetCore.kt", "JitNet.kt", "AppPerformance.kt")) {
            val c = code("src/main/java/com/example/voiceapp3/tools/$f")
            assertFalse("$f: нет записи persist.dalvik.vm.usejit", c.contains("setprop \$PROP_PERSIST_USEJIT"))
            assertFalse("$f: нет записи маркера", c.contains("setprop \$PROP_PERSIST_USEJIT_INIT"))
            assertFalse("$f: нет записи usejitprofiles", c.contains("setprop \$PROP_USEJIT_PROFILES"))
            assertFalse("$f: нет литерала persist-записи",
                c.contains("setprop persist.dalvik.vm.usejit") ||
                    c.contains("setprop persist.sys.dalvik.vm.usejit"))
        }
    }

    @Test
    fun legacyValuesAreOnlyRead() {
        val c = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue("остатки читаются", c.contains("getprop \$PROP_PERSIST_USEJIT_INIT"))
        assertTrue("и это явно помечено как чтение",
            c.contains("need=owner_decision") && c.contains("write=none"))
        assertFalse("старые значения не очищаются", c.contains("clearLegacy"))
    }

    @Test
    fun chainHasSingleUsejitWrite() {
        val script = JitRef.chainScript(want = true)
        assertTrue(script.contains("setprop dalvik.vm.usejit true"))
        assertFalse(script.contains("persist."))
        assertFalse(script.contains("usejitprofiles"))
    }
}
