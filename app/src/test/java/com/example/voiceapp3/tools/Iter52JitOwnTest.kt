package com.example.voiceapp3.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /** iter53 §4.1: ответ устройства в РЕАЛЬНОМ протоколе чтения (без кавычек). */
    private fun reply(claim: String?, term: String?): String {
        val b = StringBuilder("CS_BEGIN\n")
        if (claim.isNullOrBlank()) b.append("CLAIM_STATUS=absent\n")
        else b.append("CLAIM_STATUS=present\nCLAIM_VALUE=").append(claim).append("\n")
        if (term.isNullOrBlank()) b.append("TERM_STATUS=absent\n")
        else b.append("TERM_STATUS=present\nTERM_VALUE=").append(term).append("\n")
        b.append("CS_END")
        return b.toString()
    }

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
        // iter54 §4: в строке отказа появилась идентичность экземпляра (`inst=`), поэтому
        // проверяются её обязательные части, а не точная склейка.
        assertTrue("отказ записан в terminal: «$t»",
            t.contains("REFUSED op=a") && t.contains("reason=lock_held"))
        assertTrue("и закрыт END, чтобы фаза не осталась «идёт»", t.contains("END rc=0 op=a"))
        assertFalse("START не писался (опасных действий не было)", t.contains("START"))
    }

    @Test
    fun ageAloneNeverTakesOverLock() {
        // iter53 §5.1/§5.2: возраст блокировки НЕ доказывает смерть shell. Старая запись без
        // идентичности владельца (так писала прошлая версия) не перехватывается НИКОГДА.
        claim().writeText("op=a;ts_ms=1700000000000;want=1")
        val oldS = System.currentTimeMillis() / 1000 - (JitOwn.LOCK_AGE_NOTE_S + 3600)
        lock().writeText("op=dead;ts_s=$oldS")
        val effect = File(base, "EFFECT_STALE")
        val (rc, out) = run(payload("a", effect))
        assertEquals(0, rc)
        assertTrue("отказ назван: $out", out.contains("JIT_REFUSED op=a reason=lock_held"))
        assertTrue("возраст только сообщается: $out", !out.contains("JIT_LOCK_TAKEOVER"))
        assertFalse("устаревшая команда НЕ выполнена", effect.exists())
        assertTrue("чужую блокировку не сняли", lock().exists())
        assertTrue("и не перезаписали", lock().readText().startsWith("op=dead;"))
        assertTrue("START не писался", !term("a").readText().contains("START"))
    }

    @Test
    fun lockOfProvablyDeadOwnerIsTakenOverWithProof() {
        // Доказательство смерти — pid из lock и его отсутствие в /proc (+ стартовая метка).
        claim().writeText("op=a;ts_ms=1700000000000;want=1")
        val deadPid = firstFreePid()
        assertNotNull("не нашли свободный pid для теста", deadPid)
        lock().writeText(
            "op=dead;ts_ms=1700000000000;ts_s=1700000000;inst=dead-1;pid=$deadPid;" +
                "pstart=123;boot=other-boot"
        )
        val effect = File(base, "EFFECT_TO")
        val (rc, out) = run(payload("a", effect))
        assertEquals(0, rc)
        assertTrue("перехват с ДОКАЗАТЕЛЬСТВОМ назван: $out", out.contains("JIT_LOCK_TAKEOVER proof=owner_dead"))
        assertTrue("действие выполнено ПОСЛЕ доказанной смерти", effect.exists())
        assertFalse("своя блокировка освобождена", lock().exists())
    }

    @Test
    fun lockOfLiveOwnerBlocksAndSecondPayloadDoesNotRun() {
        org.junit.Assume.assumeTrue("нужен /proc для проверки живости", File("/proc/1").isDirectory)
        claim().writeText("op=a;ts_ms=1700000000000;want=1")
        // Владелец — этот же тестовый процесс: он жив, значит перехвата быть не должно.
        val mePid = ProcessHandle.current().pid()
        val pst = pstartOf(mePid) ?: run { org.junit.Assume.assumeTrue(false); return }
        val boot = bootId() ?: "-"
        lock().writeText("op=b;ts_ms=1700000000000;ts_s=1700000000;inst=b-1;pid=$mePid;" +
            "pstart=$pst;boot=$boot")
        val effect = File(base, "EFFECT_LIVE")
        val (rc, out) = run(payload("a", effect))
        assertEquals(0, rc)
        assertFalse("второй payload не выполнил действие", effect.exists())
        assertTrue("причина названа: $out", out.contains("reason=lock_held"))
        assertTrue("живую блокировку не тронули", lock().readText().startsWith("op=b;"))
    }

    @Test
    fun ownerEndInTerminalProvesCompletionAndAllowsTakeover() {
        // Даже без pid: собственный END владельца в ЕГО terminal-файле — доказательство
        // завершения (это не таймер), и путь к следующему клику открывается.
        claim().writeText("op=a;ts_ms=1700000000000;want=1")
        lock().writeText("op=prev;ts_ms=1700000000000;ts_s=1700000000")
        term("prev").writeText("START ts=1 op=prev\nEND rc=0 op=prev\n")
        val effect = File(base, "EFFECT_END")
        val (rc, out) = run(payload("a", effect))
        assertEquals(0, rc)
        assertTrue("перехват по факту END: $out", out.contains("JIT_LOCK_TAKEOVER proof=owner_dead"))
        assertTrue(effect.exists())
    }

    @Test
    fun sameOpIdSecondInstanceIsNotOwner() {
        // Повтор ТОГО ЖЕ opId при уже работающем экземпляре не становится владельцем.
        org.junit.Assume.assumeTrue("нужен /proc для проверки живости", File("/proc/1").isDirectory)
        val opId = "dup-op"
        claim().writeText("op=$opId;ts_ms=1700000000000;want=1")
        val mePid = ProcessHandle.current().pid()
        val pst = pstartOf(mePid) ?: run { org.junit.Assume.assumeTrue(false); return }
        lock().writeText("op=$opId;ts_ms=1700000000000;ts_s=1700000000;inst=$opId-1;" +
            "pid=$mePid;pstart=$pst;boot=${bootId() ?: "-"}")
        val effect = File(base, "EFFECT_DUP")
        val (rc, out) = run(payload(opId, effect))
        assertEquals(0, rc)
        assertFalse("второй экземпляр ничего не сделал", effect.exists())
        assertTrue("причина: $out", out.contains("reason=same_op_live"))
    }

    @Test
    fun claimChangedWhileWaitingForLockIsRefused() {
        // Ровно гонка из аудита §5.2: payload прочитал claim=A, «задержался», затем claim
        // переписан на B, а lock свободен. Проверка ПОД lock обязана это увидеть.
        claim().writeText("op=a;ts_ms=1700000000000;want=1")
        val effect = File(base, "EFFECT_TOCTOU")
        // Подкладываем «медленный» старт: gвардия сгенерирована, но перед запуском claim меняется.
        val script = payload("a", effect)
        claim().writeText("op=b;ts_ms=1700000000001;want=0")
        val (rc, out) = run(script)
        assertEquals(0, rc)
        assertTrue("отказ назван: $out", out.contains("JIT_REFUSED op=a reason=superseded"))
        assertFalse("устаревшее действие не выполнено", effect.exists())
        assertFalse("lock не остался нашим", lock().exists())
        assertTrue("START не писался", !term("a").readText().contains("START"))
    }

    @Test
    fun concurrentPayloadsAreSerialisedByDeviceLock() {
        // Настоящая одновременность: первый payload держит lock (sleep внутри «опасной» части),
        // второй стартует в этот момент и обязан отказаться — второй опасный шаг не идёт
        // параллельно. Это проверка ФАКТИЧЕСКОГО числа side effect, а не текста лога.
        claim().writeText("op=a;ts_ms=1700000000000;want=1")
        val e1 = File(base, "EFFECT_C1")
        val e2 = File(base, "EFFECT_C2")
        val slow = payload("a", e1).replace(
            "touch '${e1.absolutePath}'",
            "sleep 2; touch '${e1.absolutePath}'"
        )
        val f1 = File(base, "c1.sh").also { it.writeText(slow) }
        val f2 = File(base, "c2.sh").also { it.writeText(payload("a", e2)) }
        val p1 = ProcessBuilder(sh, f1.absolutePath).redirectErrorStream(true).start()
        Thread.sleep(400)
        val p2 = ProcessBuilder(sh, f2.absolutePath).redirectErrorStream(true).start()
        val o2 = p2.inputStream.bufferedReader().readText(); p2.waitFor()
        val o1 = p1.inputStream.bufferedReader().readText(); p1.waitFor()
        assertTrue("первый выполнил действие (o1=«$o1»)", e1.exists())
        assertFalse("второй НЕ выполнил действие параллельно (o2=«$o2»)", e2.exists())
        assertTrue("второй отказался с причиной: «$o2»",
            o2.contains("reason=lock_held") || o2.contains("reason=same_op_live"))
        assertFalse("lock освобождён после штатного завершения", lock().exists())
    }

    /** Поле 22 `/proc/<pid>/stat` — стартовая метка процесса (идентичность pid). */
    private fun pstartOf(pid: Long): String? = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
    }.getOrNull()

    private fun bootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Первый pid, которого нет в /proc: «процесс доказанно мёртв». */
    private fun firstFreePid(): Long? {
        if (!File("/proc/1").isDirectory) return null
        for (p in listOf(99_999L, 99_998L, 123_457L, 654_321L)) {
            if (!File("/proc/$p").exists()) return p
        }
        return null
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
        val out = reply("op=a;ts=1700000000;want=1", "START ts=1 op=zzz|END rc=0 op=zzz|")
        val t = JitOwn.parseTerminal("a", out)
        assertTrue("terminal прочитан", t != null)
        assertFalse("чужой START не наш", t!!.started)
        assertFalse("чужой END не наше завершение", t.finished)
        assertNull("rc не приписан", t.rc)
        assertTrue("это видно в примечании: ${t.note}", t.note.contains("term_lines_other_op"))
    }

    @Test
    fun terminalEndWithoutRcIsNotFinished() {
        val out = reply("op=a;ts=1700000000;want=1", "START ts=1 op=a|END op=a|")
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
        // iter53 §4.1: вывод берётся в РЕАЛЬНОМ протоколе чтения (без кавычек — их печатает
        // только сам тест, а shell в stdout их не выводит). Прежний вариант теста «доказывал»
        // разбор на выводе, которого устройство не производит.
        val out = "CS_BEGIN\n" +
            "CLAIM_STATUS=present\nCLAIM_VALUE=" + claim().readText().trim() + "\n" +
            "TERM_STATUS=present\nTERM_VALUE=" +
            term("a").readText().trim().replace("\n", "|") + "\n" +
            "CS_END"
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
            false to "claim_foreign:b", JitOwn.claimAllowsRestore("a", "op=b;ts=1;want=0"))
        // iter53 §4.2: отсутствие claim НЕ разрешает возврат (гвардия payload в этом случае
        // отказывается работать, значит наша цепочка Wi-Fi не выключала).
        assertEquals("claim подтверждённо отсутствует — НЕЛЬЗЯ",
            false to "claim_confirmed_absent_payload_refused",
            JitOwn.claimAllowsRestore("a", null))
        assertEquals("испорченный claim — неизвестность, а не «всё чисто»",
            false to "claim_unknown:claim_value_invalid", JitOwn.claimAllowsRestore("a", "мусор"))
    }

    @Test
    fun mayRestoreWifiIsStrictAboutOwnership() {
        assertTrue(JitOwn.mayRestoreWifi("a", "op=a;ts=1;want=1"))
        assertFalse(JitOwn.mayRestoreWifi("a", "op=b;ts=1;want=1"))
        assertFalse(JitOwn.mayRestoreWifi("a", null))
    }
}
