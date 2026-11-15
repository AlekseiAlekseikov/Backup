package com.example.voiceapp3.tools

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter53 §8.3 — продолжение после исчерпания бюджета проходов.
 *
 * Дефект: `TTL_PASS stop reason=budget … action=pending_until_next_trigger` обещал продолжение
 * «на следующем поводе», которого в стабильной сети могло не быть вовсе. Теперь продолжение —
 * реально запланированная задача (с внедряемым планировщиком), с coalescing: пока задача
 * запланирована, новые поводы не создают вторую.
 */
class Iter53TtlTest {

    private class Store(var want: Boolean = true) : TtlRunner.Store {
        val gen = AtomicInteger(1)
        override fun desired(): Boolean? = want
        override fun intentGen(): Long = gen.get().toLong()
        override fun beginIntent(want: Boolean): Long = gen.incrementAndGet().toLong()
        override fun markApplied(ok: Boolean, want: Boolean, gen: Long, current: Boolean, reason: String) {}
    }

    /**
     * Транспорт, который НИКОГДА не подтверждает: работа всегда остаётся.
     *
     * `nested` имитирует РЕАЛЬНЫЙ сценарий из комментария TtlRunner: повод приходит ВО ВРЕМЯ
     * прохода (сетевое событие внутри транспорта). Тогда счётчик поводов не пустеет и цикл
     * доходит до бюджета — именно этот путь и проверяется.
     */
    private class NeverConfirmed : TtlRunner.Transport {
        val calls = AtomicInteger()
        var nested: (() -> Unit)? = null
        override fun apply(want: Boolean): TtlRunner.Outcome {
            calls.incrementAndGet()
            nested?.invoke()
            return TtlRunner.Outcome(false, "channel_not_confirmed")
        }
    }

    private class Recorder {
        val lines = mutableListOf<String>()
        operator fun invoke(s: String) { synchronized(lines) { lines += s } }
        fun has(part: String) = synchronized(lines) { lines.any { it.contains(part) } }
    }

    @Test
    fun budgetWithoutSchedulerIsReportedHonestly() {
        val log = Recorder()
        val t = NeverConfirmed()
        val runner = TtlRunner(t, Store(), log::invoke, maxPasses = 1)
        runner.continuationScheduler = null
        // Бюджет достигается только если повод приходит во время прохода.
        t.nested = { runner.onBackgroundTrigger("nested") }
        runner.onBackgroundTrigger("net")
        assertEquals("планировщик не внедрён — «продолжим сами» не обещается", false, log.has("action=scheduled"))
        assertTrue("честный исход: ${log.lines.takeLast(3)}",
            log.has("continuation=") || log.has("action=pending_continuation"))
    }

    @Test
    fun budgetSchedulesRealContinuationAndCoalesces() {
        val log = Recorder()
        val t = NeverConfirmed()
        val runner = TtlRunner(t, Store(), log::invoke, maxPasses = 1)
        t.nested = { runner.onBackgroundTrigger("nested") }
        val tasks = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()
        runner.continuationScheduler = { delay, task ->
            synchronized(tasks) { delays += delay; tasks += Runnable { task() } }
        }
        // Много поводов подряд: цикл упирается в жёсткий предел.
        runner.onBackgroundTrigger("net")
        val scheduled = synchronized(tasks) { tasks.size }
        assertTrue("продолжение запланировано: ${log.lines.takeLast(2)}", log.has("action=scheduled"))
        assertEquals("запланирована РОВНО одна задача (coalescing)", 1, scheduled)

        // Задача реально исполняется и снова доходит до транспорта (это не «обещание»).
        val before = runner.pendingWork()
        synchronized(tasks) { tasks.first().run() }
        assertTrue("продолжение подняло повод: pending=$before", runner.pendingWork() >= 1)
        assertTrue("continuationsRun=${runner.continuationsRun}", runner.continuationsRun >= 1)
        assertTrue("после продолжения снова был проход", log.has("TTL_PASS"))
    }

    @Test
    fun budgetStopNeverClaimsApplied() {
        val log = Recorder()
        val transport = NeverConfirmed()
        val runner = TtlRunner(transport, Store(), log::invoke, maxPasses = 1)
        transport.nested = { runner.onBackgroundTrigger("nested") }
        runner.onBackgroundTrigger("net")
        val stop = log.lines.filter { it.contains("reason=budget") }
        assertTrue("бюджет исчерпан и это названо", stop.isNotEmpty())
        for (l in stop) {
            assertFalse("«применено» не заявляется: $l", l.contains("confirmed=true"))
            assertTrue("намерение сохранено и продолжение названо: $l",
                l.contains("continuation="))
        }
    }

    // ---------- §8.1: доказуемое владение и миграция ----------

    private fun seedBase(st: Iter50Stand) {
        st.mountinfo.writeText(
            "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
                "2 1 253:0 /local /data rw - f2fs /dev/block/sda1 rw\n"
        )
    }

    private fun on() = TtlScript.build(true)
    private fun off() = TtlScript.build(false)
    private fun line(out: String, prefix: String) =
        out.lineSequence().firstOrNull { it.startsWith(prefix) } ?: ""

    @Test
    fun legacyUnmarkedChainIsMigratedNotBlindlyDeleted() {
        // Цепочка, созданная ДО iter53: имя наше, правило совпадает байт в байт, метки нет.
        val st = Iter50Stand(); seedBase(st)
        st.seedRule("iptables", "VAPP_TTL", "-j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j VAPP_TTL")
        val (rc, out) = st.exec(on(), "on.sh")
        assertEquals("скрипт дошёл до конца: $out", 0, rc)
        assertTrue("состояние названо: ${line(out, "TTL_OWNERSHIP")}",
            line(out, "TTL_OWNERSHIP").contains("v4=legacy_unmarked"))
        assertTrue("миграция названа: ${line(out, "TTL_MIGRATE")}",
            line(out, "TTL_MIGRATE").contains("from=legacy_unmarked"))
        assertTrue("итог: ${line(out, "TTL_CHECK")}",
            line(out, "TTL_CHECK").contains("SCOPED_OK") || line(out, "TTL_CHECK").contains("SCOPED_V4_ONLY"))
        // После прохода правило обязано нести метку владения — иначе владение недоказуемо.
        assertEquals(
            "ровно одно помеченное правило и один переход",
            listOf(
                "VAPP_TTL -m comment --comment voiceapp3:ttl:default -j TTL --ttl-set 65",
                "POSTROUTING -o wlan0 -j VAPP_TTL"
            ),
            st.rules("iptables")
        )
    }

    @Test
    fun ourChainReferencedByAnotherChainIsKeptAndReferenceNamed() {
        val st = Iter50Stand(); seedBase(st)
        st.seedRule("iptables", "VAPP_TTL", "-m comment --comment voiceapp3:ttl:default -j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j VAPP_TTL")
        st.seedRule("iptables", "MYCHAIN", "-j VAPP_TTL")
        val (_, out) = st.exec(off(), "off.sh")
        assertTrue("ссылка названа: $out", out.contains("TTL_REF_KEEP"))
        assertTrue("чужое перечислено: $out", out.contains("TTL_OWN family=v4 state=referenced"))
        assertTrue("наша цепочка НЕ удалена", st.rules("iptables").contains("MYCHAIN -j VAPP_TTL"))
        assertTrue("снятие не подтверждено: ${line(out, "TTL_OFF")}",
            line(out, "TTL_OFF").contains("result=PARTIAL"))
        assertFalse("цепочка не удалялась по факту ссылки",
            st.argv().any { it.contains("<-X> <VAPP_TTL>") })
    }

    @Test
    fun gotoFromPostroutingIsForeignReferenceAndBlocksDeletion() {
        val st = Iter50Stand(); seedBase(st)
        st.seedRule("iptables", "VAPP_TTL", "-m comment --comment voiceapp3:ttl:default -j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-g VAPP_TTL")
        val (_, out) = st.exec(off(), "off.sh")
        assertTrue("goto увиден как чужой/иной способ ссылки: $out",
            out.contains("TTL_REF_KEEP") || out.contains("TTL_REF_FOREIGN"))
        assertTrue("цепочка сохранена", st.rules("iptables").contains("POSTROUTING -g VAPP_TTL"))
    }

    @Test
    fun quotedSpecIsRefusedInsteadOfBlindDelete() {
        val st = Iter50Stand(); seedBase(st)
        st.seedRule("iptables", "VAPP_TTL", "-m comment --comment voiceapp3:ttl:default -j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o 'wlan0' -j VAPP_TTL")
        val (_, out) = st.exec(off(), "off.sh")
        assertTrue("разбор спецификации с кавычками не удаляет вслепую: $out",
            out.contains("quoted_spec_needs_owner"))
        assertTrue("правило осталось", st.rules("iptables").contains("POSTROUTING -o 'wlan0' -j VAPP_TTL"))
    }

    @Test
    fun readableFailureIsUnknownNotAbsent() {
        val st = Iter50Stand(); seedBase(st)
        st.seedRule("iptables", "VAPP_TTL", "-m comment --comment voiceapp3:ttl:default -j TTL --ttl-set 65")
        st.failRead("iptables")
        val (_, out) = st.exec(off(), "off.sh")
        assertTrue("чтение названо отказом: ${line(out, "TTL_OWNERSHIP")}",
            line(out, "TTL_OWNERSHIP").contains("read_v4=failed"))
        assertTrue("«не прочитано» ≠ «нет правил»: ${line(out, "TTL_OFF")}",
            line(out, "TTL_OFF").contains("result=UNKNOWN"))
        assertTrue("правило не тронуто", st.rules("iptables").isNotEmpty())
    }

    @Test
    fun v6FailureRollsBackV4AndLeavesNothingHalfApplied() {
        val st = Iter50Stand(); seedBase(st)
        File(st.dir, "state/fail_add.ip6tables").writeText("1")
        val (_, out) = st.exec(on(), "on.sh")
        assertTrue("откат назван: $out", out.contains("TTL_ROLLBACK"))
        assertEquals("v4 не осталась изменённой", emptyList<String>(), st.rules("iptables"))
        assertTrue("итог честный: ${line(out, "TTL_CHECK")}",
            line(out, "TTL_CHECK").contains("APPLY_FAILED") || line(out, "TTL_CHECK").contains("CHECK_OWNER"))
    }

    @Test
    fun fastOnOffOnConvergesToSingleOwnState() {
        val st = Iter50Stand(); seedBase(st)
        st.exec(on(), "on1.sh"); st.exec(off(), "off.sh"); val (_, out) = st.exec(on(), "on2.sh")
        assertEquals(
            "сошлось к одному нашему правилу и одному переходу",
            listOf(
                "VAPP_TTL -m comment --comment voiceapp3:ttl:default -j TTL --ttl-set 65",
                "POSTROUTING -o wlan0 -j VAPP_TTL"
            ),
            st.rules("iptables")
        )
        assertTrue("итог: ${line(out, "TTL_CHECK")}",
            line(out, "TTL_CHECK").contains("SCOPED_OK") || line(out, "TTL_CHECK").contains("SCOPED_V4_ONLY"))
    }

    @Test
    fun productionInjectsARealContinuationScheduler() {
        // §8.3: без внедрённого планировщика продолжение остаётся обещанием. Проверяем, что
        // production-путь (NetworkTtl, единственное место создания runner) его внедряет.
        val f = listOf(
            java.io.File("src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt"),
            java.io.File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt"),
            java.io.File("/home/user/w/app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt")
        ).first { it.isFile }
        val src = f.readText()
        assertTrue("планировщик внедряется в production: ${f.absolutePath}",
            src.contains("r.continuationScheduler = { delayMs, task ->"))
        assertTrue("продолжение ставится в очередь BgWorker",
            src.contains("BgWorker.postDelayed(\"ttlContinuation\", delayMs, task)"))
        assertFalse("никаких обещаний без планировщика в production",
            src.contains("continuationScheduler = null"))
    }
}
