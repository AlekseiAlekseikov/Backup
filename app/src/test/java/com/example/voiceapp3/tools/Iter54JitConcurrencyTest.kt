package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter54 §4 — ВЛАДЕНИЕ JIT-ОПЕРАЦИЕЙ НАСТОЯЩИМИ ПАРАЛЛЕЛЬНЫМИ shell-процессами.
 *
 * Почему именно так: прошлые проверки владения были либо «текстовыми» (искали подстроки в
 * сгенерированном скрипте), либо последовательными (A, потом B). Ни то, ни другое не проверяет
 * то, что происходит на устройстве: два `su -c` — это ДВА независимых shell, и именно их
 * одновременный запуск решает, будет ли опасное действие выполнено один раз или дважды.
 *
 * Здесь запускаются реальные `/bin/sh` процессы A, B, C, D с БАРЬЕРОМ (файл-gate): они
 * стартуют одновременно, а не «по очереди в тесте».
 *
 * Что проверяется:
 *  1. A и B одновременно: claim принадлежит одному из них, второй отказывается сам — ровно один
 *     side effect;
 *  2. поздний claim СТАРОГО намерения не отменяет более новое (уступает: `stale`);
 *  3. уступка более новому возможна только когда тот ДОКАЗАННО завершился (END в terminal);
 *  4. тот же opId, ДРУГОЙ экземпляр (`inst`): его отказ не подменяет terminal первого;
 *  5. `ClaimWrite.Failed` → payload не отправляется (решение [JitOwn.claimGate]).
 *
 * Чего эти тесты НЕ доказывают: поведение реального `su`/SELinux на ГУ, видимость каталога
 * `/data/local/tmp` из shell и то, что команды дойдут по каналу.
 */
class Iter54JitConcurrencyTest {

    private val base: File = File.createTempFile("jit54", "").let { it.delete(); it.mkdirs(); it }
    private val b: String get() = base.absolutePath

    private fun op(
        id: String,
        tsMs: Long,
        want: Boolean = true,
        inst: String? = JitOwn.newInstance(id, tsMs)
    ) = JitOwn.Op(
        id, tsMs, want, JitOwn.State.CLAIMED,
        identity = inst?.let { JitOwn.ClaimIdentity(it, "", "", "") }
    )

    private fun sh(script: String, name: String): Pair<Int, String> {
        val f = File(base, name); f.writeText(script)
        val p = ProcessBuilder("/bin/sh", f.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return p.exitValue() to out
    }

    /** Payload: гвардия + РЕАЛЬНЫЙ side effect. Ровно он и должен случиться один раз. */
    private fun payload(o: JitOwn.Op, tag: String, holdMs: Int = 500): String =
        JitOwn.guard(o, b) + "\necho \"$tag\" >> \"${b}/effects.log\"\n" +
            "sleep ${java.lang.String.format("%.3f", holdMs / 1000.0)}\n"

    /** Запуск с БАРЬЕРОМ: все процессы стартуют по одному и тому же файлу-gate. */
    private fun startAtGate(script: String, name: String, gate: File): Pair<Process, File> {
        val f = File(base, name); f.writeText(script)
        val w = File(base, "w_$name")
        w.writeText(
            "while [ ! -f '${gate.absolutePath}' ]; do sleep 0.02; done\n" +
                "exec /bin/sh '${f.absolutePath}'\n"
        )
        return ProcessBuilder("/bin/sh", w.absolutePath).redirectErrorStream(true).start() to f
    }

    private fun collect(p: Process): String {
        p.waitFor(20, TimeUnit.SECONDS)
        return p.inputStream.bufferedReader().readText()
    }

    private fun effects(): List<String> =
        File(base, "effects.log").let { if (it.exists()) it.readLines().filter { l -> l.isNotBlank() } else emptyList() }

    private fun term(id: String): File = File(JitOwn.termPath(id, b))

    private fun claimFile(): File = File(JitOwn.claimPath(b))

    private fun claimText(): String = claimFile().let { if (it.exists()) it.readText().trim() else "" }

    // ─────────────────────────── 1. A и B одновременно ───────────────────────────

    @Test
    fun twoRealShellsAtTheSameMomentProduceExactlyOneSideEffect() {
        val a = op("A", 1_700_000_001_000L)
        val bOp = op("B", 1_700_000_002_000L)

        // Оба пишут claim и оба запускают payload ОДНОВРЕМЕННО (барьер). Тот, чей claim
        // перезаписан, обязан отказаться сам — до первого опасного действия.
        val gate = File(base, "gate1")
        val (pa, _) = startAtGate(
            JitOwn.claimCmd(a, b) + "\n" + payload(a, "A"), "A.sh", gate
        )
        val (pb, _) = startAtGate(
            JitOwn.claimCmd(bOp, b) + "\n" + payload(bOp, "B"), "B.sh", gate
        )
        Thread.sleep(150)
        gate.writeText("go")
        val outA = collect(pa)
        val outB = collect(pb)

        val eff = effects()
        assertEquals("ровно ОДИН side effect, а не два: $eff (A:$outA | B:$outB)", 1, eff.size)
        // Кто именно выиграл, решает порядок; важно, что проигравший отказался ДО действия.
        val claimed = claimText()
        assertTrue("claim читается и указывает на одну из операций: «$claimed»",
            claimed.contains("op=A;") || claimed.contains("op=B;"))
        val loser = if (eff.first() == "A") "B" else "A"
        val loserTerm = term(loser).let { if (it.exists()) it.readText() else "" }
        assertTrue("проигравший отказался сам: «$loserTerm»",
            loserTerm.contains("REFUSED op=$loser") || loserTerm.contains("JIT_REFUSED op=$loser"))
        assertFalse("у проигравшего нет START (опасных действий не было)", loserTerm.contains("START"))
    }

    // ─────────────── 2. поздний claim старого намерения уступает новому ───────────────

    @Test
    fun lateClaimOfOlderIntentNeverCancelsNewerIntent() {
        val older = op("OLD", 1_700_000_000_000L)
        val newer = op("NEW", 1_700_000_009_000L)

        val (rcNew, outNew) = sh(JitOwn.claimCmd(newer, b), "new.sh")
        assertEquals("новое намерение записано: $outNew", 0, rcNew)
        assertTrue("taken: $outNew", outNew.contains("CL_STATUS=taken"))
        val afterNew = claimText()

        // «Догоняющий» устаревший claim приходит ПОЗЖЕ.
        val (rcOld, outOld) = sh(JitOwn.claimCmd(older, b), "old.sh")
        assertEquals(0, rcOld)
        assertTrue("устаревшее намерение уступает, а не перезаписывает: $outOld",
            outOld.contains("CL_STATUS=stale") && outOld.contains("verdict=older_intent"))
        assertEquals("claim остался за НОВЫМ намерением", afterNew, claimText())
    }

    // ─────── 3. уступка только ДОКАЗАННО завершившемуся более новому намерению ───────

    @Test
    fun olderIntentTakesOverOnlyAfterNewerProvedFinished() {
        val newer = op("NEW", 1_700_000_009_000L)
        sh(JitOwn.claimCmd(newer, b), "new.sh")
        // Новый владелец реально выполнился и написал END.
        val (_, outRun) = sh(payload(newer, "NEW", holdMs = 10), "new_payload.sh")
        assertTrue("новый payload выполнился: $outRun", !outRun.contains("JIT_REFUSED"))
        val t = term("NEW").let { if (it.exists()) it.readText() else "" }
        assertTrue("END нового владельца записан: «$t»", t.contains("END rc="))

        val older = op("OLD", 1_700_000_000_000L)
        val (_, outOld) = sh(JitOwn.claimCmd(older, b), "old.sh")
        assertTrue("после доказанного завершения владение свободно: $outOld",
            outOld.contains("CL_STATUS=taken") || outOld.contains("verdict=newer_finished"))
    }

    // ─────────── 4. тот же opId, ДРУГОЙ экземпляр: отказ не подменяет terminal ───────────

    @Test
    fun refusalOfAnotherInstanceDoesNotBecomeTerminalOfTheFirst() {
        val ts = 1_700_000_000_000L
        val i1 = op("SAME", ts, inst = JitOwn.newInstance("SAME", ts, nonce = 1))
        val i2 = op("SAME", ts, inst = JitOwn.newInstance("SAME", ts, nonce = 2))
        sh(JitOwn.claimCmd(i1, b), "claim1.sh")

        val gate = File(base, "gate4")
        val (p1, _) = startAtGate(payload(i1, "I1", holdMs = 800), "i1.sh", gate)
        Thread.sleep(100)                      // первый уже держит lock
        val (p2, _) = startAtGate(payload(i2, "I2", holdMs = 10), "i2.sh", gate)
        gate.writeText("go")
        val out1 = collect(p1)
        val out2 = collect(p2)

        assertEquals("действие выполнил ровно один экземпляр: ${effects()} ($out1 | $out2)",
            listOf("I1"), effects())
        val t = term("SAME").let { if (it.exists()) it.readText() else "" }.trim().lines()
        val starts = t.filter { it.startsWith("START") }
        // END отказа (refused=1) — факт ВТОРОГО экземпляра, а не завершение работы первого.
        val ends = t.filter { it.startsWith("END") && !it.contains("refused=1") }
        val refused = t.filter { it.startsWith("REFUSED") }
        assertEquals("START ровно один (у фактического исполнителя): $t", 1, starts.size)
        assertTrue("START принадлежит экземпляру I1: $starts",
            starts.first().contains("inst=${JitOwn.newInstance("SAME", ts, nonce = 1)}"))
        assertEquals("END ровно один (у фактического исполнителя): $t", 1, ends.size)
        assertEquals("отказ ровно один (у второго экземпляра): $t", 1, refused.size)
        assertTrue("отказ помечен СВОИМ экземпляром: $refused",
            refused.first().contains("inst=${JitOwn.newInstance("SAME", ts, nonce = 2)}"))
        assertTrue("причина отказа названа: $refused",
            refused.first().contains("instance_mismatch") || refused.first().contains("lock_held") ||
                refused.first().contains("same_op_live"))
    }

    // ─────────────── 5. ClaimWrite.Failed → payload не отправляется ───────────────

    @Test
    fun unconfirmedClaimStopsThePayloadAndKeepsIntentPending() {
        val taken = JitOwn.claimGate(JitOwn.ClaimWrite.Taken("op=A;ts_ms=1;want=1"))
        assertTrue("только подтверждённое владение разрешает отправку", taken.send)

        val failed = JitOwn.claimGate(JitOwn.ClaimWrite.Failed("transport_rc=137"))
        assertFalse("неподтверждённый claim НЕ разрешает payload", failed.send)
        assertEquals("claim_unproven", failed.code)

        val busy = JitOwn.claimGate(JitOwn.ClaimWrite.Busy("op=B;ts_ms=2"))
        assertFalse(busy.send)

        val stale = JitOwn.claimGate(JitOwn.ClaimWrite.Stale("op=NEW;ts_ms=9"))
        assertFalse(stale.send)
        assertEquals("superseded_by_newer_intent", stale.code)

        // Настоящий claim, который НЕ записался: каталог недоступен → Failed, и side effect
        // не появляется, потому что мы вообще не отправляем payload.
        val bad = "/proc/definitely-not-writable/voiceapp3"
        val o = op("NOPE", 1_700_000_000_000L)
        val (rc, out) = runCatching { sh(JitOwn.claimCmd(o, bad), "bad.sh") }
            .getOrElse { 0 to "" }
        val w = JitOwn.claimResult(rc, out)
        assertFalse("недоступный каталог не выдаётся за владение (${w})",
            JitOwn.claimGate(w).send)
    }

    // ─────────────── 6. production-путь действительно спрашивает гейт ───────────────

    @Test
    fun productionConsultsTheGateBeforeSendingTheChain() {
        val f = listOf(
            File("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt"),
            File("app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt"),
            File("/home/user/w/app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
        ).first { it.isFile }
        val src = f.readText()
        assertTrue("production использует ОДНО правило допуска: ${f.absolutePath}",
            src.contains("JitOwn.claimGate(claimWrite)"))
        assertFalse("неподтверждённый claim больше не «продолжаем»",
            Regex("action=proceed").findAll(src).count { i ->
                val line = src.substring(0, i.range.first).substringAfterLast('\n')
                !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
            } > 0)
        assertTrue("намерение сохраняется как честный pending",
            src.contains("action=stop pending=1 commands=0"))
        assertTrue("страховка перед отправкой цепочки", src.contains("JIT_CLAIM_GATE"))
        // Порядок: гейт вычисляется ДО отправки скрипта.
        val gateAt = src.indexOf("JitOwn.claimGate(claimWrite)")
        val sendAt = src.indexOf("JitRef.chainScript(want, JitOwn.guard(opState))")
        assertTrue("гейт стоит до отправки payload", gateAt in 0 until sendAt)
        assertNotNull("порядок найден", sendAt)
    }
}
