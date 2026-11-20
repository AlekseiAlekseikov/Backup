package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter55 §4 — JIT: смерть/терминал ТОЛЬКО конкретного экземпляра, сериализованный перехват,
 * порядок намерений ПОД lock и живой повтор доставки.
 *
 * Аудит iter54 показал два реальных дефекта production-скрипта:
 *  1. `_jit_owner_dead` искал `REFUSED`/`END` по одному `opId`, игнорируя `inst`. Отказ ВТОРОГО
 *     экземпляра той же операции объявлял живого владельца мёртвым → перехват живого lock;
 *  2. проверка давности намерения выполнялась ДО захвата lock, а запись claim — после, поэтому
 *     устаревшее намерение могло перезаписать более новое; ветка `newer_finished` возвращала
 *     старое намерение в силу только потому, что новое завершилось.
 *
 * Здесь исполняется НАСТОЯЩИЙ сгенерированный `JitOwn.guard`/`JitOwn.claimCmd` настоящими
 * `/bin/sh` процессами с файловыми барьерами. Никаких `setprop`, `zygote`, сети — только
 * временный каталог теста. Где нужен детерминизм, в копию production-скрипта вставляется
 * ОДИН барьер (`sleep`), и это явно названо в имени/комментарии теста.
 */
class Iter55JitTest {

    private val base: File = File.createTempFile("jit55", "").let { it.delete(); it.mkdirs(); it }
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

    private fun payload(o: JitOwn.Op, tag: String, holdMs: Int = 400): String =
        JitOwn.guard(o, b) + "\necho \"$tag\" >> \"${b}/effects.log\"\n" +
            "sleep ${java.lang.String.format("%.3f", holdMs / 1000.0)}\n"

    private fun start(script: String, name: String) =
        ProcessBuilder("/bin/sh", write(script, name)).redirectErrorStream(true).start()

    private fun write(script: String, name: String): String {
        val f = File(base, name); f.writeText(script); return f.absolutePath
    }

    private fun collect(p: Process): String {
        p.waitFor(30, TimeUnit.SECONDS)
        return p.inputStream.bufferedReader().readText()
    }

    private fun effects(): List<String> =
        File(base, "effects.log").let {
            if (it.exists()) it.readLines().filter { l -> l.isNotBlank() } else emptyList()
        }

    private fun term(id: String): String =
        File(JitOwn.termPath(id, b)).let { if (it.exists()) it.readText() else "" }

    private fun lockText(): String =
        File(JitOwn.lockPath(b)).let { if (it.exists()) it.readText().trim() else "" }

    private fun claimText(): String =
        File(JitOwn.claimPath(b)).let { if (it.exists()) it.readText().trim() else "" }

    private fun awaitEffects(n: Int, timeoutMs: Long = 10_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (effects().size < n && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    // ───────── 1. A держит lock; B отказал; C входит ПОСЛЕ отказа B; D — другое намерение ─────────

    @Test
    fun refusalOfAnotherInstanceNeverFreesTheLiveLockForLaterClaimants() {
        val ts = 1_700_000_100_000L
        val instA = JitOwn.newInstance("OP", ts, nonce = 1)
        val a = op("OP", ts, inst = instA)
        val outClaimA = sh(JitOwn.claimScriptForTest(a, b), "claimA.sh").second
        assertTrue("claim записан: $outClaimA", outClaimA.contains("CL_STATUS=taken"))

        // A: настоящий shell, держит lock и «работает» (безопасный fake side effect).
        val pa = start(payload(a, "A", holdMs = 1500), "A.sh")
        awaitEffects(1)
        assertEquals("A выполняет действие один раз", listOf("A"), effects())
        val lockDuringA = lockText()
        assertTrue("lock принадлежит A: $lockDuringA", lockDuringA.contains("inst=$instA"))

        // B: тот же opId, ДРУГОЙ экземпляр (как повторная доставка/ретрай реального транспорта).
        val i2 = JitOwn.newInstance("OP", ts, nonce = 2)
        val (_, outB) = sh(payload(op("OP", ts, inst = i2), "B", holdMs = 10), "B.sh")
        assertTrue("B отказался: $outB", outB.contains("JIT_REFUSED op=OP"))
        assertTrue("отказ B зафиксирован СВОИМ экземпляром: ${term("OP")}",
            term("OP").contains("REFUSED op=OP inst=$i2"))

        // C: ТОТ ЖЕ экземпляр, что у A (повторная доставка той же попытки) — запускается ПОСЛЕ
        // записи отказа B и ДО окончания A. Именно этот сценарий аудит называл недоказанным.
        assertTrue("A всё ещё жив на момент входа C", pa.isAlive)
        val (_, outC) = sh(payload(a, "C", holdMs = 10), "C.sh")
        assertTrue("C не выполнил действие: $outC", outC.contains("JIT_REFUSED op=OP"))
        assertTrue("причина C названа: $outC",
            outC.contains("same_op_live") || outC.contains("lock_held") ||
                outC.contains("duplicate_instance"))

        // D: ДРУГОЕ (более новое) намерение, пока A жив.
        val d = op("OP2", ts + 5_000L, inst = JitOwn.newInstance("OP2", ts + 5_000L, nonce = 3))
        val (_, outD) = sh(payload(d, "D", holdMs = 10), "D.sh")
        assertTrue("D не выполнил действие, пока A жив: $outD", outD.contains("JIT_REFUSED"))
        assertTrue("причина D названа: $outD", outD.contains("lock_held") || outD.contains("superseded"))

        // Ни один поздний претендент не тронул lock живого владельца и не добавил side effect.
        assertEquals("ровно один side effect за всю сцену", listOf("A"), effects())
        assertEquals("lock остался за A", lockDuringA, lockText())

        collect(pa)
        assertTrue("A завершился и освободил ТОЛЬКО свою блокировку: «${term("OP")}»",
            term("OP").contains("END rc=0 op=OP inst=$instA"))
        assertFalse("lock освобождён A", File(JitOwn.lockPath(b)).exists())

        // После завершения A путь к работе открыт: новое намерение проходит и выполняется.
        val d2 = op("OP3", ts + 9_000L, inst = JitOwn.newInstance("OP3", ts + 9_000L, nonce = 4))
        val (_, outClaimD2) = sh(JitOwn.claimScriptForTest(d2, b), "claimD2.sh")
        assertTrue("claim нового намерения принят: $outClaimD2", outClaimD2.contains("CL_STATUS=taken"))
        val (_, outRunD2) = sh(payload(d2, "D2", holdMs = 10), "D2.sh")
        assertFalse("новый владелец работает: $outRunD2", outRunD2.contains("JIT_REFUSED"))
        assertEquals("и его действие — второе и единственное новое", listOf("A", "D2"), effects())
    }

    // ───────── 2. Два конкурентных перехвата брошенного lock ─────────

    @Test
    fun concurrentTakeoversOfAnAbandonedLockNeverStealTheFreshLock() {
        val ts = 1_700_000_200_000L
        // Брошенный lock: заведомо мёртвый PID с «чужим» pstart → смерть доказана.
        File(JitOwn.lockPath(b)).writeText(
            "op=GONE;ts_ms=${ts - 9_000};ts_s=1700000000;inst=gone-1;pid=999999;pstart=1;boot=deadboot"
        )
        val one = op("ONE", ts, inst = JitOwn.newInstance("ONE", ts, nonce = 11))
        val two = op("TWO", ts + 1, inst = JitOwn.newInstance("TWO", ts + 1, nonce = 12))
        // Оба читают ОДИН брошенный lock и пытаются перехватить одновременно (барьер).
        val gate = File(base, "gate_takeover")
        val wrap = { script: String, name: String ->
            write(
                "while [ ! -f '${gate.absolutePath}' ]; do sleep 0.02; done\n" + script, name
            )
        }
        val p1 = ProcessBuilder("/bin/sh", wrap(JitOwn.claimScriptForTest(one, b), "one.sh"))
            .redirectErrorStream(true).start()
        val p2 = ProcessBuilder("/bin/sh", wrap(JitOwn.claimScriptForTest(two, b), "two.sh"))
            .redirectErrorStream(true).start()
        Thread.sleep(120)
        gate.writeText("go")
        val o1 = collect(p1)
        val o2 = collect(p2)

        // iter57 §7.1/§7.3: «оба claim'а сказали taken» — НЕ ошибка само по себе: владение
        // переходит ПОСЛЕДОВАТЕЛЬНО (сначала ONE, затем более позднее намерение TWO), и это и
        // есть сходимость к последнему выбору владельца. Проверять надо не число ответов
        // `taken`, а ФАКТ: выжило ровно одно намерение, а payload проигравшего не выполняется.
        val finalClaim = claimText()
        assertTrue("в claim ровно одно из двух намерений: «$finalClaim»",
            finalClaim.startsWith("op=ONE;") || finalClaim.startsWith("op=TWO;"))
        assertTrue("ни один претендент не остался без ответа: $o1 | $o2",
            listOf(o1, o2).all { it.contains("CL_STATUS=") })
        // Проигравший — тот, чьё намерение перезаписано. Его payload обязан отказаться сам.
        val loserIsOne = finalClaim.startsWith("op=TWO;")
        val loserOp = if (loserIsOne) one else two
        val loserOut = if (loserIsOne) o1 else o2
        // Ответ проигравшего на этапе claim мог быть `taken` (владение переходило последовательно);
        // доказательство уступки — поведение ЕГО payload ниже, а не текст ответа claim.
        assertTrue("проигравший получил ответ: $loserOut", loserOut.contains("CL_STATUS="))
        val (_, runLoser) = sh(payload(loserOp, "LOSER", holdMs = 10), "loser.sh")
        assertTrue("payload проигравшего НЕ выполнил действие: $runLoser",
            runLoser.contains("JIT_REFUSED"))
        assertFalse("и side effect'а не появилось", effects().contains("LOSER"))
    }

    // ───────── 3. Старый claim прочитал ДО lock, новый записан, старый продолжил ─────────

    @Test
    fun claimThatReadTheStateBeforeTheLockYieldsAfterANewerIntentIsWritten() {
        val oldOp = op("OLDX", 1_700_000_300_000L, inst = JitOwn.newInstance("OLDX", 1_700_000_300_000L, nonce = 21))
        val newOp = op("NEWX", 1_700_000_400_000L, inst = JitOwn.newInstance("NEWX", 1_700_000_400_000L, nonce = 22))
        // Захват lock свободен, claim пуст → ранняя проверка «free» пройдёт.
        assertFalse(File(JitOwn.claimPath(b)).exists())

        // Детерминированный барьер ровно между решением «free» и захватом lock: копия
        // production-скрипта с ОДНОЙ вставленной паузой (в production её нет).
        val script = JitOwn.claimScriptForTest(oldOp, b).replace(
            "echo \"CL_ORDER seen_op=",
            "sleep 0.5\necho \"CL_ORDER seen_op="
        )
        assertTrue("барьер вставлен в копию скрипта", script.contains("sleep 0.5"))
        val p = start(script, "old_delayed.sh")
        Thread.sleep(150) // старый уже прочитал состояние, но ещё не взял lock
        val (_, outNew) = sh(JitOwn.claimScriptForTest(newOp, b), "new.sh")
        assertTrue("новое намерение записано: $outNew", outNew.contains("CL_STATUS=taken"))
        val afterNew = claimText()
        val outOld = collect(p)

        assertTrue("старый claim уступил ПОД lock: $outOld",
            outOld.contains("CL_STATUS=stale") && outOld.contains("verdict=newer_intent"))
        assertTrue("решение перепроверено под lock'ом: $outOld", outOld.contains("CL_ORDER_UNDER_LOCK"))
        assertEquals("более новое намерение не перезаписано", afterNew, claimText())

        // И старый payload после этого не выполняется.
        val (_, outPayload) = sh(payload(oldOp, "OLD_EFFECT", holdMs = 10), "old_payload.sh")
        assertTrue("устаревший payload отказался: $outPayload", outPayload.contains("JIT_REFUSED"))
        assertEquals("side effect старого намерения не случился", emptyList<String>(), effects())
    }

    // ───────── 4. Порча lock, PID reuse и недоступная идентичность ─────────

    @Test
    fun corruptOrUnattributableLockIsNeverTreatedAsDead() {
        val ts = 1_700_000_500_000L
        // (а) пустой/мусорный lock: ни pid, ни inst — доказать смерть нечем.
        File(JitOwn.lockPath(b)).writeText("garbage-without-identity")
        val a = op("A5", ts, inst = JitOwn.newInstance("A5", ts, nonce = 31))
        sh(JitOwn.claimScriptForTest(a, b), "claim_a5.sh")
        val (_, outGarbage) = sh(payload(a, "GARBAGE", holdMs = 10), "garbage.sh")
        assertTrue("мусорный lock не перехвачен: $outGarbage", outGarbage.contains("JIT_REFUSED"))
        assertTrue("lock не тронут", lockText().startsWith("garbage-without-identity"))
        assertTrue("действия не было", effects().isEmpty())

        // (б) PID переиспользован: pid существует, но pstart другой → смерть доказана.
        val mePid = ProcessHandle.current().pid()
        val myStart = File("/proc/$mePid/stat").readText().substringAfterLast(')').trim()
            .split(' ')[19]
        val stalePstart = (myStart.toLongOrNull()?.plus(7))?.toString() ?: "1"
        File(JitOwn.lockPath(b)).writeText(
            "op=OLD5;ts_ms=${ts - 9_000};ts_s=1700000000;inst=old5-1;pid=$mePid;" +
                "pstart=$stalePstart;boot=${File("/proc/sys/kernel/random/boot_id").readText().trim()}"
        )
        val b5 = op("B5", ts + 1_000, inst = JitOwn.newInstance("B5", ts + 1_000, nonce = 32))
        val (_, outClaim) = sh(JitOwn.claimScriptForTest(b5, b), "claim_b5.sh")
        assertTrue("перехват по PID reuse разрешён: $outClaim",
            outClaim.contains("CL_STATUS=taken") || outClaim.contains("proof=owner_dead_exact_inst"))
    }

    @Test
    fun terminalOfAnotherInstanceIsNotTheTerminalOfTheLiveOwner() {
        // Живой владелец B6 (этот же процесс), но в ЕГО terminal-файле уже лежит END ДРУГОГО
        // экземпляра той же операции. Прежний код искал только `op=B6` и объявлял владельца
        // мёртвым → перехват живого lock. Теперь identity сравнивается целиком.
        val ts = 1_700_000_600_000L
        val mePid = ProcessHandle.current().pid()
        val myStart = File("/proc/$mePid/stat").readText().substringAfterLast(')').trim()
            .split(' ')[19]
        val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()
        File(JitOwn.lockPath(b)).writeText(
            "op=B6;ts_ms=${ts - 9_000};ts_s=1700000000;inst=b6-owner;pid=$mePid;" +
                "pstart=$myStart;boot=$boot"
        )
        File(JitOwn.termPath("B6", b)).writeText(
            "START ts=1 op=B6 inst=b6-other\nREFUSED op=B6 inst=b6-other reason=lock_held\n" +
                "END rc=0 op=B6 inst=b6-other refused=1\n"
        )
        File(JitOwn.termPath("A6", b)).writeText("END rc=0 op=A6 inst=a6-1\n")
        val c = op("C6", ts, inst = JitOwn.newInstance("C6", ts, nonce = 33))
        sh(JitOwn.claimScriptForTest(c, b), "claim_c6.sh")
        val (_, out) = sh(payload(c, "C6", holdMs = 10), "c6.sh")
        assertTrue("живой владелец не перехвачен чужим END: $out", out.contains("JIT_REFUSED"))
        assertTrue("lock владельца не тронут", lockText().contains("inst=b6-owner"))
        assertTrue("действия не было", effects().isEmpty())
    }
}
