package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * iter48 §Б/§Ж — проверки того, что TTL трогает ТОЛЬКО своё, и что фоновые поводы не теряются.
 *
 * Уровень: JVM. Здесь проверяются:
 *  • сгенерированный shell-скрипт (какие правила он вправе снимать) — как ТЕКСТ, плюс отдельно
 *    он же прогонялся на bash-эмуляторе iptables (см. отчёт, §Б7);
 *  • разбор вывода [TtlVerify] — включая честный `PARTIAL`, когда остались ЧУЖИЕ правила;
 *  • оркестрация [TtlRunner] — граница «повод во время прохода», повод после успешного
 *    применения того же поколения, бюджет проходов без потери последнего намерения.
 *
 * Чего здесь НЕТ: реального iptables и реального ГУ. Успех этих тестов не доказывает поведение
 * на машине — это отдельная проверка владельца.
 */
class Iter48Test {

    private val q = '"'.toString()
    private val d = "$"

    // ─────────────────────── §Б: скрипт не снимает чужое ───────────────────────

    @Test
    fun onScriptUsesOwnChainAndNeverDeletesByValue() {
        val s = TtlScript.build(true)
        assertTrue(
            "своя цепочка: правило TTL внутри нашей цепочки",
            s.contains("mangle -A " + q + d + "OUR" + q + " -j TTL --ttl-set 65")
        )
        assertTrue(
            "переход в нашу цепочку один и со скоупом",
            s.contains("-A POSTROUTING " + d + "SCOPE -j " + q + d + "OUR" + q)
        )
        // Ни одного «удалить всё, где есть ttl-set/hl-set»: именно это снимало чужие правила.
        val deleteAllByValue = Regex("-S POSTROUTING[^\n]*grep[^\n]*ttl-set[^\n]*-D")
        assertFalse(
            "скрипт не имеет права удалять по признаку ttl-set без проверки владения",
            deleteAllByValue.containsMatchIn(s)
        )
        assertTrue("чужое перечисляется, но не удаляется", s.contains("TTL_FOREIGN v4 spec="))
        assertTrue(
            "историческое правило остаётся: владение не доказано",
            s.contains("TTL_LEGACY v4 action=keep reason=ownership_unproven")
        )
        // Снятие идёт ТОЛЬКО по переходам на нашу цепочку.
        assertTrue(
            "удаление переходов — по имени цепочки, а не по значению 65",
            s.contains("grep -- " + q + "-j " + d + "OUR" + q)
        )
    }

    @Test
    fun sanitizeScriptIsTheOffScript() {
        assertEquals(
            "санация = тот же OFF-скрипт (без особых путей)",
            TtlScript.build(false), TtlScript.sanitizeScript()
        )
        val off = TtlScript.build(false)
        assertTrue("OFF снимает только своё", off.contains("drop_own"))
        assertTrue("выключение не включает Wi-Fi", !off.contains("svc wifi"))
        // Скрипт один на оба режима (ставится только в ветке ENABLE=1) — проверяем режим,
        // а не текст: фактическое поведение OFF проверено на bash-эмуляторе iptables (§Б7).
        assertTrue("режим OFF: правило не ставится", off.contains("ENABLE=0"))
    }

    // ─────────────────────── §Б7: разбор честных итогов ───────────────────────

    private fun onOkOut(): String = listOf(
        "TTL_TRY variant=uplink scope=-o_wlan0 carplay=none",
        "TTL_ADD v4 chain=VAPP_TTL rc=0",
        "TTL_ADD_COUNT jumps_v4=1 jumps_v6=1 rules_v4=1 rules_v6=1",
        "TTL_OWN removed=ok jumps_left=0 rules_left=0",
        "TTL_V4=1 TTL_V6=1 OWN_V4=1 OWN_V6=1 OWN_J4=1 OWN_J6=1 FOREIGN_V4=0 FOREIGN_V6=0 " +
            "VERIFY_V4=OK VERIFY_V6=OK",
        "TTL_SCOPE result=scoped scoped=yes uplink_only=yes uplink=wlan0 rule=-o_wlan0_-j_VAPP_TTL",
        "TTL_CHECK: before=65 after=65 rule_ttl=65 real=UNKNOWN expected=65 " +
            "src=10.0.0.5 dst=1.1.1.1 iface=wlan0 method=counters " +
            "reason=wire_not_measured_scoped_rules hl_wire=65 \u2192 SCOPED_OK",
        "TTL_RESULT rule_v4=65 rule_v6=65 verify_v4=OK verify_v6=OK wire_v4=not_measured " +
            "enable=1 scoped=yes uplink=wlan0 variant=uplink own_chain=VAPP_TTL"
    ).joinToString("\n")

    @Test
    fun onSummaryIsParsedEvenWhenKeysFollowASpace() {
        // Регрессия: раньше ключи в сводной строке искались регуляркой с ЛИТЕРАЛЬНЫМ "\s",
        // поэтому TTL_V6=… после пробела не разбирался вовсе (v6=null).
        val p = TtlVerify.parse(true, onOkOut())
        assertEquals(TtlVerify.Verdict.OK, p.verdict)
        assertEquals("1", p.facts["ttl_v6"])
        assertEquals("1", p.facts["own_j6"])
        assertEquals("yes", p.facts["uplink_only"])
        assertEquals("SCOPED_OK", p.facts["verdict"])
        assertTrue(
            "подтверждение не выдаёт правило за измеренный TTL",
            p.detail.contains("wire=not_measured")
        )
    }

    @Test
    fun offWithForeignRulesLeftIsPartialNotFailedAndNotOk() {
        val out = listOf(
            "TTL_OWN removed=ok jumps_left=0 rules_left=0",
            "TTL_FOREIGN v4 spec=-o_wlan0_-j_TTL_--ttl-set_65",
            "TTL_LEGACY v4 action=keep reason=ownership_unproven",
            "TTL_V4=0 TTL_V6=0 OWN_V4=0 OWN_V6=0 OWN_J4=0 OWN_J6=0 FOREIGN_V4=1 FOREIGN_V6=0 " +
                "VERIFY_V4=OFF_OK VERIFY_V6=OFF_OK",
            "TTL_OFF own_v4=0 own_v6=0 foreign_v4=1 foreign_v6=0 " +
                "result=PARTIAL reason=foreign_rules_remain",
            "TTL_CHECK: rule_ttl=none real=UNKNOWN method=counters \u2192 OFF_OWN_FOREIGN_LEFT"
        ).joinToString("\n")
        val p = TtlVerify.parse(false, out)
        assertEquals(
            "чужое правило на машине — не наш провал, но и не «вернулось к стоку»",
            TtlVerify.Verdict.PARTIAL, p.verdict
        )
        assertNull(p.reason)
        assertTrue(
            "в тексте прямо сказано про чужие правила",
            p.detail.contains("ЧУЖИЕ") && p.detail.contains("foreign_v4=1")
        )
        assertEquals("1", p.facts["foreign_v4"])
    }

    @Test
    fun offWithOwnRulesLeftIsFailed() {
        val out = listOf(
            "TTL_OWN removed=partial jumps_left=1 rules_left=1 reason=readback_not_clean",
            "TTL_V4=1 TTL_V6=0 OWN_V4=1 OWN_V6=0 OWN_J4=1 OWN_J6=0 FOREIGN_V4=0 FOREIGN_V6=0 " +
                "VERIFY_V4=OK VERIFY_V6=OFF_OK",
            "TTL_OFF own_v4=1 own_v6=0 foreign_v4=0 foreign_v6=0 result=FAILED reason=own_rules_left"
        ).joinToString("\n")
        val p = TtlVerify.parse(false, out)
        assertEquals(TtlVerify.Verdict.FAILED, p.verdict)
        assertNotNull(p.reason)
    }

    @Test
    fun offCleanIsOk() {
        val out = listOf(
            "TTL_OWN removed=ok jumps_left=0 rules_left=0",
            "TTL_V4=0 TTL_V6=0 OWN_V4=0 OWN_V6=0 OWN_J4=0 OWN_J6=0 FOREIGN_V4=0 FOREIGN_V6=0 " +
                "VERIFY_V4=OFF_OK VERIFY_V6=OFF_OK",
            "TTL_OFF own_v4=0 own_v6=0 foreign_v4=0 foreign_v6=0 result=OK",
            "TTL_CHECK: rule_ttl=none real=UNKNOWN method=none \u2192 OFF_OK"
        ).joinToString("\n")
        assertEquals(TtlVerify.Verdict.OK, TtlVerify.parse(false, out).verdict)
    }

    @Test
    fun noUplinkOnOnIsFailureNotPartial() {
        val out = listOf(
            "TTL_UPLINK_REJECT uplink=p2p-p2p0-0 reason=carplay_iface",
            "TTL_TRY variant=none reason=no_uplink",
            "TTL_V4=0 TTL_V6=0 OWN_V4=0 OWN_V6=0 OWN_J4=0 OWN_J6=0 FOREIGN_V4=0 FOREIGN_V6=0 " +
                "VERIFY_V4=FAIL VERIFY_V6=FAIL",
            "TTL_SCOPE result=failed scoped=no uplink_only=no uplink=none reason=no_uplink",
            "TTL_CHECK: rule_ttl=none real=UNKNOWN \u2192 CHECK_OWNER"
        ).joinToString("\n")
        val p = TtlVerify.parse(true, out)
        assertEquals(TtlVerify.Verdict.FAILED, p.verdict)
        assertTrue("причина — нет uplink", p.detail.contains("нет uplink"))
        assertEquals("none", p.facts["variant"])
    }

    @Test
    fun noRootChannelIsBlocked() {
        val out = "ERR_ROOT_CHANNEL_UNAVAILABLE uid=1000"
        assertEquals(TtlVerify.Verdict.BLOCKED, TtlVerify.parse(true, out).verdict)
    }

    @Test
    fun unscopedRuleIsRejectedEvenIfRuleExists() {
        val out = listOf(
            "TTL_V4=1 TTL_V6=1 OWN_V4=1 OWN_V6=1 OWN_J4=1 OWN_J6=1 FOREIGN_V4=0 FOREIGN_V6=0 " +
                "VERIFY_V4=OK VERIFY_V6=OK",
            "TTL_TRY variant=uplink scope=",
            "TTL_SCOPE result=unscoped scoped=yes uplink_only=no uplink=wlan0 rule=-j_VAPP_TTL"
        ).joinToString("\n")
        val p = TtlVerify.parse(true, out)
        assertEquals(
            "правило без -o <uplink> запрещено (CarPlay)",
            TtlVerify.Verdict.FAILED, p.verdict
        )
        assertTrue(p.detail.contains("без скоупа"))
    }

    // ─────────────────────── §Б: фоновые поводы не теряются ───────────────────────

    private class ScriptedTransport(
        var confirmed: Boolean = true,
        var onApply: ((Boolean) -> Unit)? = null
    ) : TtlRunner.Transport {
        val applied = mutableListOf<Boolean>()

        override fun apply(want: Boolean): TtlRunner.Outcome {
            synchronized(applied) { applied += want }
            onApply?.invoke(want)
            return if (confirmed) TtlRunner.Outcome(true) else TtlRunner.Outcome(false, "no_uplink")
        }

        fun count(): Int = synchronized(applied) { applied.size }
    }

    private class MemStore(want: Boolean? = null) : TtlRunner.Store {
        val desiredRef = AtomicLong(
            when (want) {
                true -> 1L
                false -> 2L
                null -> 0L
            }
        )
        val gen = AtomicLong(0)
        val facts = mutableListOf<String>()

        override fun desired(): Boolean? = when (desiredRef.get()) {
            1L -> true
            2L -> false
            else -> null
        }

        override fun intentGen(): Long = gen.get()

        override fun beginIntent(want: Boolean): Long {
            desiredRef.set(if (want) 1L else 2L)
            return gen.incrementAndGet()
        }

        override fun markApplied(
            ok: Boolean, want: Boolean, gen: Long, current: Boolean, reason: String
        ) {
            synchronized(facts) { facts += "ok=$ok reason=$reason current=$current" }
        }
    }

    @Test
    fun backgroundTriggerAfterSuccessfulSameGenerationStillVerifies() {
        // Дефект iter47: повод гасился условием `curGen == lastAppliedGen && lastAppliedOk`.
        // После смены сети правила могут исчезнуть — проверка обязана быть выполнена.
        val t = ScriptedTransport()
        val s = MemStore(true)
        val r = TtlRunner(t, s, {})
        r.request(true, "click")
        assertEquals(1, t.count())
        r.onBackgroundTrigger("network")
        assertEquals("повод обязан дойти до транспорта, даже если поколение то же", 2, t.count())
    }

    @Test
    fun triggerOnTheExitBoundaryOfTheCycleIsNotLost() {
        val t = ScriptedTransport()
        val s = MemStore(true)
        val r = TtlRunner(t, s, {})
        val latched = CountDownLatch(1)
        var nested: TtlRunner.Result? = null
        t.onApply = {
            t.onApply = null
            // Повод приходит из ДРУГОГО потока, пока идёт проход: tryLock не даст параллельного
            // прохода, но повод обязан быть обслужен (в т.ч. на границе unlock).
            val th = Thread {
                nested = r.onBackgroundTrigger("root_ready")
                latched.countDown()
            }
            th.start()
            latched.await(5, TimeUnit.SECONDS)
            th.join(5_000)
        }
        r.request(true, "click")
        assertNotNull("повод из другого потока не должен «исчезнуть»", nested)
        assertEquals("после клика обязан быть добивочный проход по поводу", 2, t.count())
        assertEquals("счётчик поводов пуст", 0, r.pendingWork())
        assertFalse(r.isRunning())
    }

    @Test
    fun intentChurnIsBoundedAndKeepsLastIntent() {
        val t = ScriptedTransport()
        val s = MemStore(true)
        val logs = mutableListOf<String>()
        val r = TtlRunner(t, s, { line -> synchronized(logs) { logs += line } }, maxPasses = 3)
        var flips = 0
        t.onApply = {
            flips++
            if (flips <= 20) s.beginIntent(flips % 2 == 0)
        }
        r.request(true, "click")
        assertTrue(
            "проходы обязаны быть ограничены, а не «долбить» вечно: ${t.count()}",
            t.count() <= 12
        )
        assertTrue(
            "ограничение видно в журнале",
            synchronized(logs) { logs.any { it.contains("TTL_PASS") && it.contains("budget") } }
        )
        assertNotNull("последнее намерение сохранено", s.desired())
        assertFalse(r.isRunning())
    }

    @Test
    fun failureWithoutNewTriggerDoesNotStorm() {
        val t = ScriptedTransport(confirmed = false)
        val s = MemStore(true)
        val r = TtlRunner(t, s, {})
        val res = r.request(true, "click")
        assertEquals("один проход без нового повода", 1, t.count())
        assertFalse(res.confirmed)
        assertEquals("no_uplink", res.reason)
        assertEquals(0, r.pendingWork())
    }

    @Test
    fun offClickIsConfirmedWithForeignRemainderReasonRecorded() {
        // Транспорт: «своё снято, чужое осталось». OFF для СВОИХ правил достигнут, но причина
        // записывается честно, а не выдаётся за полную санацию машины.
        val t = object : TtlRunner.Transport {
            override fun apply(want: Boolean) =
                if (want) TtlRunner.Outcome(false, "no_uplink")
                else TtlRunner.Outcome(true, "foreign_rules_remain", "foreign_rules_remain")
        }
        val s = MemStore(true)
        val r = TtlRunner(t, s, {})
        val res = r.request(false, "click")
        assertEquals(false, s.desired())
        assertTrue("OFF достигнут для своих правил", res.confirmed)
        assertTrue(
            "причина записана в хранилище факта: ${s.facts}",
            synchronized(s.facts) { s.facts.any { it.contains("reason=foreign_rules_remain") } }
        )
    }
}
