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
    // ─────────────── §В: владение слоем монтирования по mountinfo ───────────────

    private fun mountinfo(): String = listOf(
        // базовая ФС /vendor — корень "/", это НЕ слой приложения
        "35 1 253:4 / /vendor rw,relatime - ext4 /dev/block/dm-4 rw",
        // наш bind: корень = staging-файл, цель = файл в /vendor/etc/carlock
        "412 35 253:4 /data/local/tmp/carlock_custom_g7_ab12.wav " +
            "/vendor/etc/carlock/xingguangshanyao.wav rw,relatime - ext4 /dev/block/dm-4 rw",
        // чужой bind на другом файле той же папки
        "413 35 253:4 /data/local/tmp/other_app_lock.wav " +
            "/vendor/etc/carlock/other.wav rw,relatime - ext4 /dev/block/dm-4 rw"
    ).joinToString("\n")

    @Test
    fun mountinfoProvesOwnershipByRootNotByDevice() {
        val mi = mountinfo()
        val ours = "/vendor/etc/carlock/xingguangshanyao.wav"
        val foreign = "/vendor/etc/carlock/other.wav"
        val base = "/vendor"
        assertEquals(
            "наш слой узнаётся по КОРНЮ монтирования",
            MountInfo.Owner.OURS, MountInfo.topLayer(mi, ours)!!.let { MountInfo.ownerOf(it) }
        )
        assertEquals(
            "чужой слой не может считаться нашим",
            MountInfo.Owner.FOREIGN, MountInfo.topLayer(mi, foreign)!!.let { MountInfo.ownerOf(it) }
        )
        assertEquals(
            "базовое монтирование ФС — не слой приложения",
            MountInfo.Owner.BASE, MountInfo.parse(mi).first { it.target == base }.let { MountInfo.ownerOf(it) }
        )
        assertTrue("наш слой доказан", MountInfo.ownLayerProven(mi, ours, "/data/local/tmp/carlock_custom_g7_ab12.wav"))
        assertFalse("чужой слой не доказан как наш", MountInfo.ownLayerProven(mi, foreign))
        assertTrue("чужой слой на цели виден", MountInfo.foreignOnTarget(mi, foreign))
        assertFalse("на нашей цели чужого нет", MountInfo.foreignOnTarget(mi, ours))
        assertNull("на пути без слоя верхнего слоя нет",
            MountInfo.topLayer(mi, "/vendor/etc/carlock/no_such.wav"))
    }

    @Test
    fun mountinfoFieldsAreParsedIncludingOctalEscapes() {
        val line = "78 35 0:45 /data/local/tmp/my\\040file.wav /vendor/etc/carlock/a\\040b.wav " +
            "rw,nosuid - tmpfs tmpfs rw"
        val l = MountInfo.parseLine(line)
        assertNotNull(l)
        assertEquals("78", l!!.id)
        assertEquals("35", l.parentId)
        assertEquals("0:45", l.majMin)
        assertEquals("/data/local/tmp/my file.wav", l.root)
        assertEquals("/vendor/etc/carlock/a b.wav", l.target)
        assertEquals("tmpfs", l.fstype)
    }

    @Test
    fun restoreMarkersDistinguishProvenForeignAndUnproven() {
        val out = listOf(
            "MOUNT_NS self=mnt:[4026531856] init=mnt:[4026531856]",
            "UMOUNTED_OK:/vendor/etc/carlock/x.wav mid=412",
            "STOCK_OK:/vendor/etc/carlock/x.wav",
            "FOREIGN_MOUNT:/vendor/etc/carlock/y.wav mid=500 root=/tmp/other",
            "STOCK_FOREIGN_LEFT:/vendor/etc/carlock/y.wav layers=1 reason=foreign_layer_above",
            "UMOUNT_FAILED:/vendor/etc/carlock/z.wav mid=600 rc=1 lazy_rc=1",
            "STOCK_UNPROVEN:/vendor/etc/carlock/z.wav reason=umount_not_confirmed rc=4"
        ).joinToString("\n")
        val r = MountInfo.parseRestore(out)
        assertEquals(listOf("/vendor/etc/carlock/x.wav"), r.proven)
        assertEquals(listOf("/vendor/etc/carlock/y.wav"), r.foreignLeft)
        assertEquals(listOf("/vendor/etc/carlock/z.wav"), r.unproven)
    }

    @Test
    fun bindScriptUsesMountinfoAndNeverClaimsSuccessAfterFailedUmount() {
        val s = LockMountScripts.bindScript("/data/local/tmp/carlock_custom_g1_aa.wav")
        assertFalse("первое поле /proc/mounts — устройство, а не путь: так нельзя",
            s.contains("/proc/mounts"))
        assertTrue("владение — по mountinfo", s.contains("/proc/self/mountinfo"))
        assertTrue("проверяется пространство монтирования", s.contains("/proc/self/ns/mnt") &&
            s.contains("/proc/1/ns/mnt"))
        assertTrue("несовпадение namespace не выдаётся за успех", s.contains("MOUNT_NS_MISMATCH"))
        assertTrue("чужой слой только диагностируется", s.contains("FOREIGN_MOUNT"))
        val umountLines = s.lines().filter { l -> l.trim().startsWith("umount") || l.contains(" umount ") }
        assertTrue("umount в скрипте есть", umountLines.isNotEmpty())
        for (l in umountLines) {
            assertFalse("после umount не должно быть || true: это скрывает провал", l.contains("|| true"))
        }
        assertTrue("снятие проверяется перечитыванием mountinfo", s.contains("after=\$(mi_count"))
        assertTrue("на чужой слой не монтируем", s.contains("reason=foreign_layer_present"))
        assertTrue("успех — только по readback корня и inode",
            s.contains("MOUNT_VERIFY_FAIL") && s.contains("MOUNTED_OK:"))
    }

    @Test
    fun restoreScriptReportsPerTargetAndKeepsStageWhileLayersLeft() {
        val s = LockMountScripts.restoreScript(
            listOf("/vendor/etc/carlock/a.wav", "/vendor/etc/carlock/b.wav")
        )
        assertTrue(s.contains("STOCK_OK:/vendor/etc/carlock/a.wav"))
        assertTrue(s.contains("STOCK_UNPROVEN:/vendor/etc/carlock/b.wav"))
        assertTrue(s.contains("STOCK_FOREIGN_LEFT:/vendor/etc/carlock/b.wav"))
        assertTrue("реестр/стейдж не чистится без доказательства", s.contains("OWN_LAYERS_TOTAL="))
        assertFalse("никакого /proc/mounts", s.contains("/proc/mounts"))
        assertFalse("никакого || true после umount",
            s.lines().any { it.contains("umount ") && it.contains("|| true") })
    }
    // ─────────────── §Д: время старта zygote читается и доказывает только факт ───────────────

    @Test
    fun zygoteStartIsParsedFromProcFields() {
        // /proc/<pid>/stat: имя в скобках может содержать пробелы, starttime — 22-е поле.
        val stat = "1234 (zygote) S 1 1234 1234 0 -1 4194560 100 0 0 0 5 3 0 0 20 0 1 0 98765 1234 56"
        assertEquals(98765L, ZygoteStart.parseStarttimeTicks(stat))
        assertEquals(1234.5, ZygoteStart.parseUptimeSec("1234.50 5000.10")!!, 0.001)
        assertTrue(ZygoteStart.isZygoteCmdline("zygote64"))
        assertTrue(ZygoteStart.isZygoteCmdline("/system/bin/zygote"))
        assertFalse(ZygoteStart.isZygoteCmdline("zygote_helper"))
        assertFalse(ZygoteStart.isZygoteCmdline(""))
        // btime = now - uptime; старт = btime + ticks/hz
        val now = 1_800_000_000_000L
        val started = ZygoteStart.startMsFrom(1000L, 100.0, now, hz = 100L)
        assertEquals(now - 100_000L + 10_000L, started)
        assertNull("без uptime не догадываемся", ZygoteStart.startMsFrom(1000L, null, now))
        assertNull("без ticks не догадываемся", ZygoteStart.startMsFrom(null, 100.0, now))
    }

    @Test
    fun zygoteEvidenceRequiresStrictlyLaterStartWithoutTolerance() {
        val now = 1_800_000_000_000L
        val mark = JitRef.OpMark.of("op", true, JitRef.OpMark.UNDEFINED, now - 120_000L)
        // iter47 засчитывал старт «за 60 с ДО отметки» как доказательство — это и убрано.
        assertFalse(
            "старт zygote ДО отметки не доказывает нашу команду (перенос/очередь/pid reuse)",
            JitOp.zygoteRestartEvidence(mark, now - 180_000L, now)
        )
        assertFalse(
            "равенство времени тоже не доказательство",
            JitOp.zygoteRestartEvidence(mark, mark.ts, now)
        )
        assertTrue(
            "строго позже — признак есть (но только для снятия блокировки нового клика)",
            JitOp.zygoteRestartEvidence(mark, now - 60_000L, now)
        )
        val old = JitRef.OpMark.of("op2", true, JitRef.OpMark.UNDEFINED, now - 60 * 60_000L)
        assertFalse(
            "отметка старше окна: поздний старт zygote мог быть чем угодно",
            JitOp.zygoteRestartEvidence(old, now - 1_000L, now)
        )
    }

    // ─────────────── §Е: ротация лога не теряет .1 и не теряет строку ───────────────

    private fun tmpLogDir(): java.io.File =
        java.io.File.createTempFile("iter48log", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun rotationKeepsPreviousPartAndAppendsNewLine() {
        val dir = tmpLogDir()
        val name = "app.log"
        val active = java.io.File(dir, name)
        active.writeText("OLD-PART-1\nOLD-PART-2\n")
        java.io.File(dir, "$name.1").writeText("ARCHIVE-OLDER\n")
        val r = FileLogCore.append(dir, name, "NEW-LINE\n", maxBytes = 4L)
        assertTrue("запись должна пройти: ${r.reason}", r.written)
        assertTrue("ротация заявлена", r.rotated)
        assertEquals("активный файл содержит новую строку", "NEW-LINE\n", active.readText())
        val bak = java.io.File(dir, "$name.1")
        assertTrue("прежняя активная часть сохранена в .1", bak.readText().contains("OLD-PART-1"))
        assertFalse("ротация не обнуляет .new", java.io.File(dir, "$name.new").exists())
        dir.deleteRecursively()
    }

    @Test
    fun existingNewFileIsNotTruncated() {
        val dir = tmpLogDir()
        val name = "app.log"
        java.io.File(dir, name).writeText("X".repeat(64))
        // `.new` остался от прошлой неудачной ротации: данные в нём НЕ обнуляются.
        java.io.File(dir, "$name.new").writeText("PRESERVED-FROM-EARLIER\n")
        val r = FileLogCore.append(dir, name, "TAIL\n", maxBytes = 8L)
        assertTrue(r.written)
        val merged = java.io.File(dir, name).readText()
        assertTrue("старый .new сохранён, а не затёрт: «$merged»",
            merged.contains("PRESERVED-FROM-EARLIER") && merged.contains("TAIL"))
        dir.deleteRecursively()
    }

    @Test
    fun busyJvmLockDoesNotLoseTheLine() {
        val dir = tmpLogDir()
        val name = "app.log"
        val active = java.io.File(dir, name)
        // Путь «JVM-лок занят» больше не теряет строку: она уходит в spill этого процесса.
        // Проверяем именно этот вспомогательный путь (взять JVM-лок снаружи API нельзя).
        assertTrue("spill этого процесса пишется", FileLogCore.spillAppend(active, "LINE-A\n"))
        val spill = FileLogCore.spillFor(active)
        assertTrue("spill содержит строку", spill.readText().contains("LINE-A"))
        val drained = FileLogCore.drainSpill(dir, name)
        assertTrue("слияние выполнено (перенесено строк: $drained)", drained >= 0)
        assertTrue("строка в основном журнале", active.readText().contains("LINE-A"))
        assertFalse("spill убран после успешного слияния", spill.exists())
        dir.deleteRecursively()
    }
}
