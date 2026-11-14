package com.example.voiceapp3.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter52 §7 — JIT: взаимное исключение ДО первого опасного действия, отказ, который ВИДЕН,
 * и разбор terminal'а строго по id операции.
 *
 * Дефекты (аудит §7):
 *  1. `claim` писался отдельной короткой командой, поэтому между записью claim и запуском
 *     payload было окно: две операции могли обе дойти до `svc wifi disable`/`setprop`. Гвардия
 *     ловила только ПОЗДНИЙ старт, но не одновременный;
 *  2. отказ payload'а (`JIT_REFUSED`) до записи `START` нигде на устройстве не оставался:
 *     если stdout не доехал, факт отказа терялся;
 *  3. `parseTerminal` принимал любые строки `START`/`END` без проверки `op=<id>` — `END rc=0`
 *     от прошлой операции с тем же id считался завершением текущей;
 *  4. `mayRestoreWifi` существовал, но не вызывался ниоткуда: включение Wi-Fi по старому снимку
 *     не проверяло, не нажал ли владелец тумблер снова.
 *
 * Проверяется на настоящем `/bin/sh` и настоящих файлах (не «на глаз по логу»).
 * Чего тесты НЕ доказывают: реальную JIT-компиляцию, фактический рестарт zygote и поведение
 * `/system/bin/sh` на ГУ.
 */
class Iter52JitOwnTest {

    private val base: File = File.createTempFile("jit52", "").let { it.delete(); it.mkdirs(); it }

    private val sh: String = listOf("/bin/sh", "/usr/bin/sh", "sh")
        .firstOrNull { File(it).canExecute() } ?: throw IllegalStateException("нет /bin/sh")

    private fun run(script: String): Pair<Int, String> {
        val f = File(base, "payload.sh")
        f.writeText(script)
        val p = ProcessBuilder(sh, f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return Pair(p.exitValue(), out)
    }

    private fun term(opId: String) = File(base, "voiceapp3_jit_$opId.term")
    private fun claim() = File(base, "voiceapp3_jit.claim")
    private fun lock() = File(base, "voiceapp3_jit.lock")

    private fun op(id: String, want: Boolean = true) =
        JitOwn.Op(id, 1700000000L, want, JitOwn.State.CLAIMED)

    /** Payload с настоящей гвардией и «опасным действием», которое пишет след. */
    private fun payload(id: String, effect: File = File(base, "EFFECT")): String =
        JitOwn.guard(op(id), base.absolutePath) +
            "\ntouch '${effect.absolutePath}'\n"

    // ───────────── §7.1: взаимное исключение ─────────────

    @Test
    fun lockIsTakenAtomicallyBeforeDangerousActionAndReleasedAfter() {
        claim().writeText("op=a;ts=1700000000;want=1")
        val effect = File(base, "EFFECT")
        val (rc, out) = run(payload("a", effect))
        assertEquals("payload завершился штатно: $out", 0, rc)
        assertTrue("опасное действие выполнено", effect.exists())
        assertTrue("START записан", term("a").readText().contains("START"))
        assertTrue("END с rc записан", term("a").readText().contains("END rc=0 op=a"))
        assertFalse("блокировка освобождена СВОИМ trap'ом", lock().exists())
    }

    @Test
    fun lockOfAnotherOperationBlocksBeforeAnySideEffect() {
        // Claim НАШ (иначе сработал бы более ранний барьер «устаревшая операция»),
        // а блокировка принадлежит другой живой операции.
        claim().writeText("op=a;ts=1700000000;want=1")
        val nowS = System.currentTimeMillis() / 1000
        lock().writeText("op=b;ts=$nowS")
        val effect = File(base, "EFFECT_LEAK")
        val (rc, out) = run(payload("a", effect))

        assertEquals(0, rc)
        assertTrue("отказ назван в stdout: $out", out.contains("JIT_REFUSED op=a reason=lock_held"))
        assertFalse("НИ ОДНОГО side effect", effect.exists())
        assertTrue("чужую блокировку не сняли", lock().exists())
        assertTrue("чужую блокировку не перезаписали", lock().readText().startsWith("op=b;"))
        val t = term("a").readText()
        assertTrue("отказ записан в terminal: «$t»", t.contains("REFUSED op=a reason=lock_held"))
        assertTrue("и закрыт END, чтобы фаза не осталась «идёт»", t.contains("END rc=0 op=a"))
        assertFalse("START не писался (опасных действий не было)", t.contains("START"))
    }

    @Test
    fun staleLockIsTakenOverAndThatIsWrittenDown() {
        claim().writeText("op=a;ts=1700000000;want=1")
        val old = System.currentTimeMillis() / 1000 - (JitOwn.LOCK_STALE_S + 60)
        lock().writeText("op=dead;ts=$old")
        val effect = File(base, "EFFECT_STALE")
        val (rc, out) = run(payload("a", effect))
        assertEquals(0, rc)
        assertTrue("перехват брошенной блокировки назван: $out", out.contains("JIT_LOCK_STALE owner="))
        assertTrue("действие выполнено после перехвата", effect.exists())
        assertFalse("своя блокировка освобождена", lock().exists())
    }

    @Test
    fun supersededOpRefusesEvenWithoutLock() {
        // claim уже переписан новой операцией — этот payload устарел.
        claim().writeText("op=next;ts=1700000005;want=0")
        val effect = File(base, "EFFECT_SUPERSEDED")
        val (rc, out) = run(payload("a", effect))
        assertEquals(0, rc)
        assertTrue(out.contains("JIT_REFUSED op=a reason=superseded"))
        assertFalse("ничего не сделано", effect.exists())
        assertTrue("отказ в terminal", term("a").readText().contains("reason=superseded"))
        assertFalse("даже блокировку не пытались брать", lock().exists())
    }

    @Test
    fun guardDoesNotCreateLockForRefusedPayload() {
        claim().writeText("op=other;ts=1700000001;want=1")
        run(payload("a"))
        assertFalse(lock().exists())
        assertFalse("файла эффекта нет", File(base, "EFFECT").exists())
    }

    // ───────────── §7.2: разбор terminal строго по id ─────────────

    @Test
    fun terminalLinesOfAnotherOpAreNotCountedAsOurs() {
        val out = "CLAIM=\"op=a;ts=1700000000;want=1\"\nTERM=\"START ts=1 op=zzz|END rc=0 op=zzz|\""
        val t = JitOwn.parseTerminal("a", out)
        assertTrue("terminal прочитан", t != null)
        assertFalse("чужой START не наш", t!!.started)
        assertFalse("чужой END не наше завершение", t.finished)
        assertNull("rc не приписан", t.rc)
        assertTrue("это видно в примечании: ${t.note}", t.note.contains("term_lines_other_op"))
    }

    @Test
    fun terminalEndWithoutRcIsNotFinished() {
        val out = "CLAIM=\"op=a;ts=1700000000;want=1\"\nTERM=\"START ts=1 op=a|END op=a|\""
        val t = JitOwn.parseTerminal("a", out)!!
        assertTrue("START наш", t.started)
        assertFalse("END без rc — не доказательство завершения", t.finished)
        assertNull(t.rc)
    }

    @Test
    fun refusalFromStdoutIsRecognisedWhenTerminalUnreadable() {
        val out = "JIT_REFUSED op=a reason=lock_held claim=op=b;ts=1;want=1 lock=op=b;ts=2"
        assertTrue(JitOwn.refusedInOutput("a", out))
        assertFalse("чужой id не считается нашим отказом", JitOwn.refusedInOutput("b", out))
        assertNull("terminal не прочитан", JitOwn.parseTerminal("a", "ERR_SCRIPT_UPLOAD", 0))
    }

    @Test
    fun classifyUsesFactsNotTransportAck() {
        claim().writeText("op=a;ts=1700000000;want=1")
        // payload реально исполняется — START/END появляются на диске
        run(JitOwn.guard(op("a"), base.absolutePath) + "\nexit 3\n")
        val out = "CLAIM=\"${claim().readText().trim()}\"\nTERM=\"${
            term("a").readText().trim().replace("\n", "|")
        }|\""
        val t = JitOwn.parseTerminal("a", out)!!
        assertTrue("END есть", t.finished)
        assertEquals("rc из END, а не из транспорта", 3, t.rc)
        assertEquals(JitOwn.State.FINISHED, JitOwn.classify(sentAck = false, term = t))
    }

    // ───────────── §7.4: возврат Wi-Fi только своим владением ─────────────

    @Test
    fun wifiRestoreIsAllowedOnlyForOwnClaim() {
        assertEquals("claim нашей операции", true to "claim_ours",
            JitOwn.claimAllowsRestore("a", "op=a;ts=1;want=1"))
        assertEquals("claim другой операции — НЕЛЬЗЯ (затрём новое решение)",
            false to "claim_foreign", JitOwn.claimAllowsRestore("a", "op=b;ts=1;want=0"))
        assertEquals("claim отсутствует — конкурирующих нет", true to "claim_absent",
            JitOwn.claimAllowsRestore("a", null))
        assertEquals("испорченный claim — неизвестность, а не «всё чисто»",
            false to "claim_unparsable", JitOwn.claimAllowsRestore("a", "мусор"))
    }

    @Test
    fun mayRestoreWifiIsStrictAboutOwnership() {
        assertTrue(JitOwn.mayRestoreWifi("a", "op=a;ts=1;want=1"))
        assertFalse(JitOwn.mayRestoreWifi("a", "op=b;ts=1;want=1"))
        assertFalse(JitOwn.mayRestoreWifi("a", null))
    }
}
