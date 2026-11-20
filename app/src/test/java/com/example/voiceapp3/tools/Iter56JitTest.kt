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
        assertTrue("claim A записан", sh(JitOwn.claimScriptForTest(a, b), "claimA.sh").second
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
        val (_, outD) = sh(JitOwn.claimScriptForTest(d, b), "claimD.sh")
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
        val (_, outD2) = sh(JitOwn.claimScriptForTest(d, b), "claimD2.sh")
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
        val (_, out) = sh(JitOwn.claimScriptForTest(d, b), "claim_newl.sh")
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
        val (_, out) = sh(JitOwn.claimScriptForTest(n, b), "claim_n2.sh")
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
    fun foreignLockIsNeverMovedOrRemovedByTakeover() {
        // iter57 §7.3: прежняя схема пришпиливала inode и делала `mv lock → tmp`, то есть
        // ВРЕМЕННО уносила имя — и если между pin и mv имя занимал ЖИВОЙ владелец, его lock
        // исчезал из пространства имён. Теперь смена объекта выполняется только под админ-файлом
        // и только если содержимое ВСЁ ЕЩЁ равно строке, смерть которой доказана.
        val real = JitOwn.guard(op("Z", 1_700_100_400_000L, seq = 1), b)
        assertTrue("в сгенерированном скрипте есть сериализация захвата",
            real.contains("_jit_lock_acquire") && real.contains("_jit_admin_enter"))
        assertFalse("перемещения lock (`mv`) больше нет", real.contains(".dead."))

        // Живой чужой lock: pid=1 (init) существует всегда — смерть НЕ доказана.
        // pstart берём ровно так, как его считает скрипт (`awk` поле 22 /proc/<pid>/stat),
        // иначе проверка «pid переиспользован» справедливо сочтёт владельца мёртвым.
        val initStart = java.io.File("/proc/1/stat").readText().trim().split(' ')[21]
        val live = "op=LIVE;ts_ms=1700100400000;ts_s=1700100400;want=1;inst=live-1;" +
            "pid=1;pstart=$initStart;boot=${File("/proc/sys/kernel/random/boot_id").readText().trim()}"
        File(JitOwn.lockPath(b)).writeText(live)
        val script = JitOwn.guard(op("Z", 1_700_100_400_000L, seq = 1), b)
        // Запускаем ТОЛЬКО функции из настоящего скрипта (ранние проверки не исполняем).
        // iter67c §2: комментарии больше НЕ уходят на устройство (это и был дефект размера),
        // поэтому границы блока ищутся по исполняемым строкам, а не по заголовку-комментарию.
        val from = script.indexOf("_me_pid=")
        val to = script.indexOf("_me_line=")
        assertTrue("блок общих функций найден", from >= 0 && to > from)
        val call = script.substring(from, to) +
            "\n_me_line=\"op=Z;inst=Z-1;pid=${'$'}${'$'};pstart=1;boot=x\"\n" +
            "_me_inst=Z-1\n_me_pid=${'$'}${'$'}\n" +
            "_me_pid=${'$'}${'$'}; _me_pstart=${'$'}(_me_psof \"${'$'}_me_pid\"); _me_boot=${'$'}(cat /proc/sys/kernel/random/boot_id)\n" +
            "_jit_owner_dead \"${'$'}(cat '${"${base.absolutePath}"}/voiceapp3_jit.lock')\"; echo \"DEAD=${'$'}?\"\n" +
            "_jit_may_takeover '${"${base.absolutePath}"}/voiceapp3_jit.lock'; echo \"MAY=${'$'}?\"\n" +
            "_jit_lock_acquire '${"${base.absolutePath}"}/voiceapp3_jit.lock' 'op=LIVE;ts_ms=1700100400000;ts_s=1700100400;want=1;inst=live-1;pid=1;pstart=$initStart;boot=x'; echo \"ACQ=${'$'}?\"\n"
        val (_, out) = sh(call, "takeover_probe.sh")
        assertTrue("перехват живого lock запрещён: $out", out.contains("MAY=1"))
        assertTrue("взятия имени живого lock не произошло: $out", out.contains("ACQ=1"))
        assertEquals("живой lock не изменён и не удалён", live, lockText())
    }

    // ───────── 3b. iter57 §7.3: перехват разрешён только при доказанном конце ЦЕПОЧКИ ─────────

    @Test
    fun claimShellDeathAloneIsNotProofThatThePayloadEnded() {
        // lock записан shell'ом claim (pid уже мёртв), но payload этого исполнения УЖЕ НАЧАЛ
        // работу (START в terminal) и ещё не закончил. Смерть claim-процесса ничего не говорит о
        // payload'е: перехват запрещён, имя живого владельца не трогается.
        val ts = 1_700_100_600_000L
        val inst = "PP-1"
        File(JitOwn.termPath("PP", b)).writeText("START ts=1 op=PP inst=$inst\n")
        File(JitOwn.lockPath(b)).writeText(
            "op=PP;ts_ms=$ts;ts_s=1700100600;want=1;inst=$inst;pid=999998;pstart=1;boot=deadboot"
        )
        val script = JitOwn.guard(op("NEWPP", ts + 1_000L, seq = 9), b)
        // iter67c §2: комментарии больше НЕ уходят на устройство (это и был дефект размера),
        // поэтому границы блока ищутся по исполняемым строкам, а не по заголовку-комментарию.
        val from = script.indexOf("_me_pid=")
        val to = script.indexOf("_me_line=")
        val call = script.substring(from, to) + "\n_me_inst=N2-1\n_me_pid=${'$'}${'$'}\n" +
            "_me_pstart=${'$'}(_me_psof \"${'$'}_me_pid\")\n_me_boot=${'$'}(cat /proc/sys/kernel/random/boot_id)\n" +
            "_jit_may_takeover \"${'$'}(cat '${"${base.absolutePath}"}/voiceapp3_jit.lock')\"; " +
            "echo \"MAY=${'$'}?\"\n"
        val (_, out) = sh(call, "payload_running_probe.sh")
        assertTrue("перехват запрещён, пока payload мог работать: $out", out.contains("MAY=2"))
        assertTrue("lock владельца не изменён",
            lockText().contains("inst=$inst") && !lockText().contains("ppid="))
    }

    @Test
    fun payloadIdentityDeathAllowsTakeoverOfAKilledRun() {
        // Payload-identity записана в lock (`ppid=`) и этот процесс доказанно мёртв → перехват
        // разрешён: иначе убитый payload блокировал бы JIT навсегда.
        val ts = 1_700_100_700_000L
        File(JitOwn.termPath("KIL", b)).writeText("START ts=1 op=KIL inst=KIL-1\n")
        File(JitOwn.lockPath(b)).writeText(
            "op=KIL;ts_ms=$ts;ts_s=1700100700;want=1;inst=KIL-1;pid=999997;pstart=1;boot=deadboot;" +
                "ppid=999996;ppstart=1"
        )
        val script = JitOwn.guard(op("NEWK", ts + 1_000L, seq = 10), b)
        // iter67c §2: комментарии больше НЕ уходят на устройство (это и был дефект размера),
        // поэтому границы блока ищутся по исполняемым строкам, а не по заголовку-комментарию.
        val from = script.indexOf("_me_pid=")
        val to = script.indexOf("_me_line=")
        val call = script.substring(from, to) + "\n_me_inst=N3-1\n_me_pid=${'$'}${'$'}\n" +
            "_me_pstart=${'$'}(_me_psof \"${'$'}_me_pid\")\n_me_boot=${'$'}(cat /proc/sys/kernel/random/boot_id)\n" +
            "_jit_may_takeover \"${'$'}(cat '${"${base.absolutePath}"}/voiceapp3_jit.lock')\"; " +
            "echo \"MAY=${'$'}?\"\n"
        val (_, out) = sh(call, "killed_payload_probe.sh")
        assertTrue("мёртвый payload не блокирует навсегда: $out", out.contains("MAY=0"))
    }

    // ───────── 4. Порядок намерений: монотонное поколение, не часы ─────────

    @Test
    fun newerIntentWinsEvenWhenTheClockWentBackwards() {
        val t = 1_700_100_500_000L
        // Прежнее намерение записано с БОЛЬШИМ временем (часы «вперёд»).
        val first = op("K1", t + 60_000L, seq = 20, inst = JitOwn.newInstance("K1", t + 60_000L, nonce = 5))
        assertTrue(sh(JitOwn.claimScriptForTest(first, b), "k1.sh").second.contains("CL_STATUS=taken"))
        // Новый клик после перевода часов НАЗАД: время меньше, поколение больше.
        val second = op("K2", t - 120_000L, seq = 21, inst = JitOwn.newInstance("K2", t - 120_000L, nonce = 6))
        val (_, outSecond) = sh(JitOwn.claimScriptForTest(second, b), "k2.sh")
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
        assertTrue(sh(JitOwn.claimScriptForTest(newer, b), "m2.sh").second.contains("CL_STATUS=taken"))
        // Устаревший клик доставлен поздно: часы у него ПОЗЖЕ, поколение МЕНЬШЕ.
        val older = op("M1", t + 30_000L, seq = 39, inst = JitOwn.newInstance("M1", t + 30_000L, nonce = 8))
        val (_, outOld) = sh(JitOwn.claimScriptForTest(older, b), "m1.sh")
        assertTrue("устаревшее намерение уступило: $outOld", outOld.contains("CL_STATUS=stale"))
        assertTrue("причина по seq: $outOld",
            outOld.contains("rule=seq") && outOld.contains("verdict=older_intent"))
        assertTrue("более новое намерение не перезаписано", claimText().startsWith("op=M2;"))
    }

    @Test
    fun equalGenerationsAreATieAndDoNotOverwriteByChance() {
        val t = 1_700_100_700_000L
        assertTrue(sh(JitOwn.claimScriptForTest(op("P1", t, seq = 50, inst = JitOwn.newInstance("P1", t, nonce = 9)), b),
            "p1.sh").second.contains("CL_STATUS=taken"))
        // Второй клик с тем же поколением (два процесса выдали одно значение): угадывать нельзя.
        val (_, outTie) = sh(
            JitOwn.claimScriptForTest(op("P2", t + 1, seq = 50, inst = JitOwn.newInstance("P2", t + 1, nonce = 10)), b),
            "p2.sh"
        )
        assertTrue("равные поколения — уступка, а не победа по случайности: $outTie",
            outTie.contains("CL_STATUS=stale") && outTie.contains("seq_tie"))
        assertTrue("прежнее намерение не перезаписано", claimText().startsWith("op=P1;"))
        // Следующий клик получит бо́льшее поколение и пройдёт — «вечного pending» нет.
        val (_, outNext) = sh(
            JitOwn.claimScriptForTest(op("P3", t + 2, seq = 51, inst = JitOwn.newInstance("P3", t + 2, nonce = 11)), b),
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
        val (_, out) = sh(JitOwn.claimScriptForTest(op("NEWQ", t, seq = 3, inst = JitOwn.newInstance("NEWQ", t, nonce = 12)), b),
            "newq.sh")
        assertTrue("легаси-запись: порядок по времени с явным правилом: $out",
            out.contains("rule=ts_legacy") && out.contains("CL_STATUS=taken"))
    }

    // ───────── 5. Claim-строка несёт seq (наблюдаемость и разбор) ─────────

    @Test
    fun claimLineCarriesGenerationAndIdentity() {
        val o = op("Q1", 1_700_100_900_000L, seq = 77, inst = JitOwn.newInstance("Q1", 1_700_100_900_000L, nonce = 13))
        val (_, out) = sh(JitOwn.claimScriptForTest(o, b), "q1.sh")
        assertTrue("claim записан: $out", out.contains("CL_STATUS=taken"))
        val line = claimText()
        assertNotNull(line)
        assertTrue("поколение в claim: $line", line.contains("seq=77;"))
        assertTrue("identity владельца в claim: $line", line.contains("pid=") && line.contains("pstart="))
        assertTrue("op присутствует", line.startsWith("op=Q1;"))
    }
}
