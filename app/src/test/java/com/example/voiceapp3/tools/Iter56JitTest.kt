package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * iter56 §4 — JIT: отказ replay'я того же `inst` не является смертью владельца, перехват не теряет
 * чужой живой lock, а порядок намерений — монотонное поколение, а не показание часов.
 *
 * Каждый тест исполняет НАСТОЯЩИЙ сгенерированный `JitOwn.guard`/`JitOwn.claimCmd` настоящими
 * `/bin/sh` процессами в временном каталоге. Никаких `setprop`, `svc`, `zygote` и сети.
 *
 * Что было дефектом (аудит iter55, приложение A §2):
 *  1. повторная доставка ТОГО ЖЕ payload (тот же `op`+`inst`) писала `REFUSED … inst=I` и
 *     `END … inst=I` в terminal владельца; `_jit_owner_dead` принимал их за смерть владельца и
 *     разрешал перехват ЕГО ЖИВОГО lock → второй side effect;
 *  2. проверка обратного переименования (`mv lock.dead → lock`) могла заменить уже созданный
 *     живой lock, а неудачный возврат сопровождался `|| true` и удалением копии;
 *  3. порядок намерений сравнивался по `ts_ms`, поэтому перевод часов назад делал НОВЫЙ клик
 *     «старым», а совпадение миллисекунд — «ничьей».
 */
class Iter56JitTest {

    private val base: File = File.createTempFile("jit56", "").let { it.delete(); it.mkdirs(); it }
    private val b: String get() = base.absolutePath

    private fun op(id: String, tsMs: Long, want: Boolean = true, seq: Long = 0L,
                   inst: String? = JitOwn.newInstance(id, tsMs)) = JitOwn.Op(
        id, tsMs, want, JitOwn.State.CLAIMED,
        identity = inst?.let { JitOwn.ClaimIdentity(it, "", "", "") },
        seq = seq
    )

    private fun write(script: String, name: String): String {
        val f = File(base, name); f.writeText(script); return f.absolutePath
    }

    private fun sh(script: String, name: String): Pair<Int, String> {
        val p = ProcessBuilder("/bin/sh", write(script, name))
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun start(script: String, name: String) =
        ProcessBuilder("/bin/sh", write(script, name)).redirectErrorStream(true).start()

    private fun collect(p: Process): String {
        p.waitFor(60, TimeUnit.SECONDS)
        return p.inputStream.bufferedReader().readText()
    }

    /** Guard + безопасный fake side effect (строка в файл) + удержание lock. */
    private fun payload(o: JitOwn.Op, tag: String, holdMs: Int = 400): String =
        JitOwn.guard(o, b) + "\necho \"$tag\" >> \"${b}/effects.log\"\n" +
            "sleep ${String.format("%.3f", holdMs / 1000.0)}\n"

    private fun effects(): List<String> =
        File(base, "effects.log").let {
            if (it.exists()) it.readLines().filter { l -> l.isNotBlank() } else emptyList()
        }

    private fun awaitEffects(n: Int, timeoutMs: Long = 10_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (effects().size < n && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    private fun term(id: String): String =
        File(JitOwn.termPath(id, b)).let { if (it.exists()) it.readText() else "" }

    private fun lockText(): String =
        File(JitOwn.lockPath(b)).let { if (it.exists()) it.readText().trim() else "" }

    private fun claimText(): String =
        File(JitOwn.claimPath(b)).let { if (it.exists()) it.readText().trim() else "" }

    // ───────── 1. Replay того же inst не делает живого владельца мёртвым ─────────

    @Test
    fun replayRefusalOfTheSameInstanceNeverFreesTheLiveOwner() {
        val ts = 1_700_100_000_000L
        val instA = JitOwn.newInstance("OPA", ts, nonce = 1)
        val a = op("OPA", ts, seq = 10, inst = instA)
        assertTrue("claim A записан", sh(JitOwn.claimCmd(a, b), "claimA.sh").second
            .contains("CL_STATUS=taken"))

        // A: настоящий shell, держит lock и ещё работает (fake side effect уже сделал).
        val pa = start(payload(a, "A", holdMs = 2_500), "A.sh")
        awaitEffects(1)
        val lockDuringA = lockText()
        assertTrue("lock принадлежит A: $lockDuringA", lockDuringA.contains("inst=$instA"))

        // 1) ТОЧНЫЙ replay ТОЙ ЖЕ попытки: тот же op и тот же inst (так выглядит повторная
        //    доставка одного payload транспортом).
        val (_, outReplay) = sh(payload(a, "REPLAY", holdMs = 10), "replay.sh")
        assertTrue("replay отказался: $outReplay",
            outReplay.contains("JIT_REFUSED") && outReplay.contains("duplicate_instance"))
        assertTrue("отказ записан своей identity: ${term("OPA")}",
            term("OPA").contains("REFUSED op=OPA inst=$instA"))
        assertFalse("отказ НЕ пишет END как завершение операции", term("OPA").contains("END rc=0"))
        assertTrue("A всё ещё жив после отказа replay'я", pa.isAlive)

        // 2) Пока A жив, новое намерение D вызывает ИМЕННО claimCmd (аудит: прежний тест
        //    запускал payload без claim и потому не проверял решение о смерти владельца).
        val d = op("OPD", ts + 3_000L, seq = 11, inst = JitOwn.newInstance("OPD", ts + 3_000L, nonce = 2))
        val (_, outD) = sh(JitOwn.claimCmd(d, b), "claimD.sh")
        assertTrue("claim нового намерения УСТУПИЛ живому владельцу: $outD",
            outD.contains("CL_STATUS=busy"))
        assertFalse("ложной смерти A не приписано: $outD",
            outD.contains("CL_TAKEOVER proof=owner_dead_exact_inst"))
        assertEquals("lock живого владельца не тронут", lockDuringA, lockText())
        assertEquals("claim живого владельца не перезаписан",
            "op=OPA;", claimText().substringBefore(";op=").take(7))
        assertTrue("side effect только у A", effects().equals(listOf("A")))

        // 3) После ДОКАЗАННОГО окончания A (его собственный END с точной identity) путь открыт.
        collect(pa)
        assertTrue("A написал свой END с identity: «${term("OPA")}»",
            Regex("^END rc=0 op=OPA inst=$instA pid=\\d+ pstart=", RegexOption.MULTILINE)
                .containsMatchIn(term("OPA")))
        assertFalse("lock освобождён A", File(JitOwn.lockPath(b)).exists())
        val (_, outD2) = sh(JitOwn.claimCmd(d, b), "claimD2.sh")
        assertTrue("после конца A новое намерение принимается: $outD2",
            outD2.contains("CL_STATUS=taken"))
    }

    // ───────── 2. Конец владельца доказывается ТОЧНОЙ identity, а не op+inst ─────────

    @Test
    fun endLineOfAnotherShellIsNotTheEndOfTheLiveHolder() {
        val ts = 1_700_100_100_000L
        val mePid = ProcessHandle.current().pid()
        val myStart = File("/proc/$mePid/stat").readText().substringAfterLast(')').trim().split(' ')[19]
        val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()
        // Живой владелец: этот процесс, точная identity в lock.
        File(JitOwn.lockPath(b)).writeText(
            "op=LIV;ts_ms=${ts - 9_000};seq=5;ts_s=1700100000;want=1;inst=liv-1;" +
                "pid=$mePid;pstart=$myStart;boot=$boot"
        )
        // В его terminal уже лежат: чужой END (тот же op+inst, ДРУГОЙ pid) — как от replay'я,
        // и REFUSED того же вида. Ни то, ни другое не является концом владельца.
        File(JitOwn.termPath("LIV", b)).writeText(
            "REFUSED op=LIV inst=liv-1 pid=999998 pstart=1 boot=$boot reason=duplicate_instance\n" +
                "END rc=0 op=LIV inst=liv-1 pid=999998 pstart=1 boot=$boot\n"
        )
        val d = op("NEWL", ts, seq = 6, inst = JitOwn.newInstance("NEWL", ts, nonce = 3))
        val (_, out) = sh(JitOwn.claimCmd(d, b), "claim_newl.sh")
        assertTrue("живой владелец не объявлен мёртвым чужим END: $out",
            out.contains("CL_STATUS=busy"))
        assertTrue("lock владельца на месте", lockText().contains("inst=liv-1"))
    }

    // ───────── 3. Перехват не теряет и не подменяет объект ─────────

    @Test
    fun takeoverNeverLosesOrReplacesTheObjectItDidNotProveDead() {
        // «Брошенный» lock с заведомо мёртвым PID и чужим boot — смерть доказана.
        File(JitOwn.lockPath(b)).writeText(
            "op=GONE2;ts_ms=1700100200000;seq=1;ts_s=1700100200;want=1;inst=gone2-1;" +
                "pid=999999;pstart=1;boot=deadboot"
        )
        val n = op("N2", 1_700_100_300_000L, seq = 2, inst = JitOwn.newInstance("N2", 1_700_100_300_000L, nonce = 4))
        val (_, out) = sh(JitOwn.claimCmd(n, b), "claim_n2.sh")
        assertTrue("перехват доказанно мёртвого lock разрешён: $out", out.contains("CL_STATUS=taken"))
        assertTrue("перехват назван по identity: $out", out.contains("proof=owner_dead_exact_inst"))
        // Ни одного «dead»/«pin» файла не осталось: временные объекты убираются за собой.
        val leftovers = base.listFiles()?.map { it.name }?.filter {
            it.contains(".dead.") || it.contains(".pin.")
        } ?: emptyList()
        assertEquals("временные файлы перехвата убраны: $leftovers", emptyList<String>(), leftovers)
        // claimCmd освобождает lock за собой (его берёт уже payload), поэтому новый владелец
        // проверяется по ЗАПИСАННОМУ claim'у, а не по lock'у.
        assertTrue("новый владелец в claim", claimText().contains("inst=${n.identity?.inst}"))
    }

    @Test
    fun restoreOfAMovedForeignLockNeverOverwritesTheNewOne() {
        // Проверяем сам примитив возврата: имя уже занято НОВЫМ живым lock, вернуть нужно
        // объект, который мы сняли ошибочно. `mv` перезаписал бы живой lock; `ln` — нет.
        // Файлы ВНЕ пространства имён lock: их не должен задеть trap самого скрипта.
        val zOp = op("Z", 1_700_100_400_000L, seq = 1)
        File(base, "foreign.lock").writeText("op=FRN;inst=frn-1;pid=1;pstart=1;boot=x")
        File(base, "live.lock").writeText("op=NEWLIVE;inst=nl-1;pid=1;pstart=1;boot=x")
        // Берём НАСТОЯЩИЙ сгенерированный скрипт целиком (примитивы — его часть) и вызываем
        // примитив возврата в его конце: так тест падает, если примитив исчезнет/изменится.
        val real = JitOwn.guard(zOp, b)
        assertTrue("в сгенерированном скрипте есть примитив возврата", real.contains("_jit_restore_dead"))
        // Claim и lock — наш, чтобы скрипт дошёл до конца (иначе ранние проверки завершат его).
        // Claim должен принадлежать ТОМУ ЖЕ экземпляру, что и запускаемый guard, иначе скрипт
        // законно откажется (`instance_mismatch`) и до примитива не дойдёт.
        File(JitOwn.claimPath(b)).writeText(
            "op=Z;ts_ms=1700100400000;want=1;inst=${zOp.identity?.inst}"
        )
        val call = real + "\n_jit_release\n" +
            "_jit_restore_dead '${base.absolutePath}/foreign.lock' '${base.absolutePath}/live.lock'\n" +
            "echo \"RC=$?\"\n"
        val (_, out) = sh(call, "restore_probe.sh")
        assertTrue("возврат на занятое имя не перезаписал живой lock: $out",
            File(base, "live.lock").readText().contains("NEWLIVE"))
        assertTrue("исходный объект СОХРАНЁН, а не удалён",
            File(base, "foreign.lock").exists() && File(base, "foreign.lock").readText().contains("FRN"))
        assertTrue("отказ возврата назван явно: $out", out.contains("CL_TAKEOVER_KEPT"))
    }

    // ───────── 4. Порядок намерений: монотонное поколение, не часы ─────────

    @Test
    fun newerIntentWinsEvenWhenTheClockWentBackwards() {
        val t = 1_700_100_500_000L
        // Прежнее намерение записано с БОЛЬШИМ временем (часы «вперёд»).
        val first = op("K1", t + 60_000L, seq = 20, inst = JitOwn.newInstance("K1", t + 60_000L, nonce = 5))
        assertTrue(sh(JitOwn.claimCmd(first, b), "k1.sh").second.contains("CL_STATUS=taken"))
        // Новый клик после перевода часов НАЗАД: время меньше, поколение больше.
        val second = op("K2", t - 120_000L, seq = 21, inst = JitOwn.newInstance("K2", t - 120_000L, nonce = 6))
        val (_, outSecond) = sh(JitOwn.claimCmd(second, b), "k2.sh")
        assertTrue("новое поколение принято несмотря на часы назад: $outSecond",
            outSecond.contains("CL_STATUS=taken"))
        assertTrue("порядок решён по seq и это названо: $outSecond",
            outSecond.contains("rule=seq") && outSecond.contains("me_seq=21"))
        assertTrue("claim принадлежит новому намерению", claimText().startsWith("op=K2;"))
    }

    @Test
    fun olderIntentArrivingLateYieldsEvenWithLaterClockReading() {
        val t = 1_700_100_600_000L
        val newer = op("M2", t, seq = 40, inst = JitOwn.newInstance("M2", t, nonce = 7))
        assertTrue(sh(JitOwn.claimCmd(newer, b), "m2.sh").second.contains("CL_STATUS=taken"))
        // Устаревший клик доставлен поздно: часы у него ПОЗЖЕ, поколение МЕНЬШЕ.
        val older = op("M1", t + 30_000L, seq = 39, inst = JitOwn.newInstance("M1", t + 30_000L, nonce = 8))
        val (_, outOld) = sh(JitOwn.claimCmd(older, b), "m1.sh")
        assertTrue("устаревшее намерение уступило: $outOld", outOld.contains("CL_STATUS=stale"))
        assertTrue("причина по seq: $outOld",
            outOld.contains("rule=seq") && outOld.contains("verdict=older_intent"))
        assertTrue("более новое намерение не перезаписано", claimText().startsWith("op=M2;"))
    }

    @Test
    fun equalGenerationsAreATieAndDoNotOverwriteByChance() {
        val t = 1_700_100_700_000L
        assertTrue(sh(JitOwn.claimCmd(op("P1", t, seq = 50, inst = JitOwn.newInstance("P1", t, nonce = 9)), b),
            "p1.sh").second.contains("CL_STATUS=taken"))
        // Второй клик с тем же поколением (два процесса выдали одно значение): угадывать нельзя.
        val (_, outTie) = sh(
            JitOwn.claimCmd(op("P2", t + 1, seq = 50, inst = JitOwn.newInstance("P2", t + 1, nonce = 10)), b),
            "p2.sh"
        )
        assertTrue("равные поколения — уступка, а не победа по случайности: $outTie",
            outTie.contains("CL_STATUS=stale") && outTie.contains("seq_tie"))
        assertTrue("прежнее намерение не перезаписано", claimText().startsWith("op=P1;"))
        // Следующий клик получит бо́льшее поколение и пройдёт — «вечного pending» нет.
        val (_, outNext) = sh(
            JitOwn.claimCmd(op("P3", t + 2, seq = 51, inst = JitOwn.newInstance("P3", t + 2, nonce = 11)), b),
            "p3.sh"
        )
        assertTrue("следующее поколение проходит: $outNext", outNext.contains("CL_STATUS=taken"))
    }

    @Test
    fun legacyClaimWithoutSeqFallsBackToTimeWithExplicitRule() {
        val t = 1_700_100_800_000L
        // Запись прежней версии (без seq) — порядок по времени, правило названо честно.
        File(JitOwn.claimPath(b)).writeText(
            "op=OLDL;ts_ms=${t - 5_000};ts_s=1700100800;want=0;inst=oldl-1;pid=1;pstart=1;boot=x"
        )
        val (_, out) = sh(JitOwn.claimCmd(op("NEWQ", t, seq = 3, inst = JitOwn.newInstance("NEWQ", t, nonce = 12)), b),
            "newq.sh")
        assertTrue("легаси-запись: порядок по времени с явным правилом: $out",
            out.contains("rule=ts_legacy") && out.contains("CL_STATUS=taken"))
    }

    // ───────── 5. Claim-строка несёт seq (наблюдаемость и разбор) ─────────

    @Test
    fun claimLineCarriesGenerationAndIdentity() {
        val o = op("Q1", 1_700_100_900_000L, seq = 77, inst = JitOwn.newInstance("Q1", 1_700_100_900_000L, nonce = 13))
        val (_, out) = sh(JitOwn.claimCmd(o, b), "q1.sh")
        assertTrue("claim записан: $out", out.contains("CL_STATUS=taken"))
        val line = claimText()
        assertNotNull(line)
        assertTrue("поколение в claim: $line", line.contains("seq=77;"))
        assertTrue("identity владельца в claim: $line", line.contains("pid=") && line.contains("pstart="))
        assertTrue("op присутствует", line.startsWith("op=Q1;"))
    }
}
