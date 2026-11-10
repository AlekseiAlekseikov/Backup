package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p (1.0.61) — AVAS отделён от JIT.
 *
 * В референсе mute (`w0/f2`) и выбор звука (`w0/r1`) — самостоятельные функции: `w0/la.run`
 * (цепочка JIT) и `w0/e2.run` (возврат Wi-Fi/AP) их не вызывают. Поэтому привязанные к JIT
 * сохранение/восстановление AVAS (`AvasRestore`/`AvasCore`) удалены целиком, а не «отключены».
 *
 * Тест держит границу и исполняемо (в цепочке нет ни одной AVAS-команды), и статически
 * (JIT-код не ссылается на AVAS-классы).
 */
class AvasIndependenceTest {

    private fun src(rel: String): File? =
        listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }

    private fun code(rel: String): String = src(rel)!!.readLines()
        .filterNot { l ->
            val t = l.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("#")
        }.joinToString("\n")

    @Test
    fun avasMechanismRemoved() {
        assertTrue("AvasCore.kt удалён",
            src("src/main/java/com/example/voiceapp3/tools/AvasCore.kt") == null)
        assertTrue("AvasRestore.kt удалён",
            src("src/main/java/com/example/voiceapp3/tools/AvasRestore.kt") == null)
    }

    @Test
    fun jitCodeDoesNotReferenceAvas() {
        for (f in listOf("JitNetCore.kt", "JitNet.kt")) {
            val c = code("src/main/java/com/example/voiceapp3/tools/$f")
            assertFalse("$f не ссылается на AvasCore", c.contains("AvasCore"))
            assertFalse("$f не ссылается на AvasRestore", c.contains("AvasRestore"))
            assertFalse("$f не трогает audio", c.contains("audioserver"))
            assertFalse("$f не пишет 0x21407057", c.contains("0x21407057"))
        }
    }

    @Test
    fun chainHasNoAvasCommands() {
        val stand = JitIsoStand(rules = 0)
        stand.exec(JitRef.chainScript(want = true, mountStep = true))
        val text = stand.callsText()
        for (bad in listOf("audio", "avas", "am ", "cmd car", "service call")) {
            assertFalse("в цепочке нет «$bad»", text.contains(bad))
        }
        assertEquals("только сеть/правила/свойства/монтирования",
            setOf("svc", "ip", "setprop", "umount"),
            stand.calls().map { it.substringBefore(' ') }.toSet())
    }

    @Test
    fun leftoverSnapshotIsNotApplied() {
        // Остаточный снимок AVAS старых версий: наш JIT его не читает и не применяет.
        val c = code("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        assertTrue("остаток только фиксируется строкой", c.contains("AVAS_LEGACY snapshot=present"))
        assertTrue("и помечен как чужое решение", c.contains("need=owner_decision"))
        assertFalse("никаких записей в Car API из JIT-кода", c.contains("AvasCore"))
        assertFalse("файл не удаляется", c.contains("avas_snapshot\").delete"))
    }
}
