package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ядро JIT — проверка «поведение = референс», а не «есть похожие строки».
 *
 * Ориентиры — DEX/smali `CentralEXAuto_v3.3.9.apk` (md5 `c3f1664125953b9851bf4483b265135f`):
 * `w0/j9.smali` (клик), `w0/la.smali` (скрипт), `CentralWatchdogService.smali` (`onCreate`, `U`),
 * `w0/e2.smali` (возврат сети), `w0/bb.smali` + `w0/r7.smali` (сверка).
 */
class JitRefTest {

    // ─────────────────────────── 1. скрипт: литерал DEX ───────────────────────────

    @Test
    fun chainScriptIsReferenceLiteralByteForByte() {
        // Литерал скопирован из `w0/la.smali` (ветка `pswitch_10e`) без единого изменения.
        val dex = "\n        svc wifi disable\n" +
            "        _n=0\n" +
            "        while [ \$_n -lt 20 ]; do\n" +
            "          [ \"\$(ip rule show | grep -c 'oif wlan0')\" = \"0\" ] && break\n" +
            "          sleep 1; _n=\$((_n + 1))\n" +
            "        done\n" +
            "        for _m in \$(grep ' /data/app/' /proc/mounts | awk '{print \$2}'); do\n" +
            "          umount -l \"\$_m\" 2>/dev/null\n" +
            "        done\n" +
            "        setprop dalvik.vm.usejit true\n" +
            "        setprop ctl.restart zygote\n    "
        assertEquals("литерал референса (с шагом монтирований)", dex, JitRef.referenceScript(true))
        assertEquals(392, JitRef.referenceScript(true).length)
    }

    @Test
    fun executedChainDiffersOnlyByMountStepAndValue() {
        val full = JitRef.referenceScript(true)
        val executed = JitRef.chainScript(true)
        // Шаг монтирований в исполняемой цепочке отсутствует — это заявлено, а не спрятано.
        assertFalse(executed.contains("umount"))
        assertFalse(JitRef.MOUNT_STEP)
        assertTrue(JitRef.mountStepNote().contains("reason=" + JitRef.MOUNT_STEP_REASON))
        assertTrue(JitRef.mountStepNote().contains("need=owner_decision"))
        // Всё остальное совпадает символ в символ.
        val withoutMount = full.replace(
            "        for _m in \$(grep ' /data/app/' /proc/mounts | awk '{print \$2}'); do\n" +
                "          umount -l \"\$_m\" 2>/dev/null\n" +
                "        done\n",
            ""
        )
        assertEquals("отличие только в шаге монтирований", withoutMount, executed)
        assertEquals(
            "ветка выключения отличается только значением свойства",
            executed.replace("usejit true", "usejit false"),
            JitRef.chainScript(false)
        )
    }

    @Test
    fun scriptOrderAndBoundaries() {
        val s = JitRef.chainScript(true)
        val lines = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("svc wifi disable", lines.first())
        assertEquals("setprop ctl.restart zygote", lines.last())
        assertTrue("слив ждёт не больше 20 итераций", s.contains("while [ \$_n -lt 20 ]"))
        assertTrue("счётчик правила — read-only", s.contains("ip rule show | grep -c 'oif wlan0'"))
        assertFalse("никакого ip rule del", s.contains("ip rule del"))
        assertFalse("никакого flush", s.contains("flush"))
        assertFalse("никакого ndc", s.contains("ndc"))
        assertFalse("никакого settings", s.contains("settings "))
        assertFalse("никакого persist.*", s.contains("persist."))
        assertFalse("никакого usejitprofiles", s.contains("usejitprofiles"))
        assertEquals("одно setprop свойства", 1,
            Regex("setprop dalvik\\.vm\\.usejit").findAll(s).count())
        assertEquals("один рестарт фреймворка", 1,
            Regex("ctl\\.restart zygote").findAll(s).count())
    }

    @Test
    fun constantsMatchDex() {
        assertEquals(20, JitRef.DRAIN_LOOP_MAX)              // while [ $_n -lt 20 ]
        assertEquals(40_000, JitRef.CHAIN_TIMEOUT_MS)        // H1(0x9c40)
        assertEquals(15_000L, JitRef.RESTORE_DELAY_MS)       // postDelayed(…, 0x3a98)
        assertEquals(8_000, JitRef.GETPROP_TIMEOUT_MS)       // H1(0x1f40)
        assertEquals(25L, JitRef.AP_LATCH_TIMEOUT_S)         // await(0x19, SECONDS)
        assertEquals(20_000L, JitRef.AP_POLL_TOTAL_MS)       // if-ge v0, 0x4e20
        assertEquals(1_000L, JitRef.AP_POLL_STEP_MS)         // sleep(0x3e8)
        assertEquals(3, JitRef.AP_ATTEMPTS)                  // if-ge p2, 0x3
        assertEquals(2_000L, JitRef.AP_RETRY_PAUSE_MS)       // sleep(0x7d0)
        assertEquals("jit_pedido", JitRef.KEY_PEDIDO)
        assertEquals("perf_auto_otimizar", JitRef.KEY_AUTO)
        assertEquals("jit_restaurar", JitRef.KEY_RESTORE)
        assertEquals("dalvik.vm.usejit", JitRef.PROP_USEJIT)
    }

    // ─────────────────────────── 2. снимок сети и его разбор ───────────────────────────

    @Test
    fun parseRestoreIsExactReferenceFormat() {
        assertEquals(JitRef.Net(true, false), JitRef.parseRestore("wifi=1,ap=0"))
        assertEquals(JitRef.Net(false, true), JitRef.parseRestore("wifi=0,ap=1"))
        assertEquals(JitRef.Net(true, true), JitRef.parseRestore("wifi=1,ap=1"))
        assertEquals(JitRef.Net(false, false), JitRef.parseRestore("wifi=0,ap=0"))
        assertNull("null — снимка нет", JitRef.parseRestore(null))
        assertNull("пробелы: Matcher.matches() по сырой строке", JitRef.parseRestore(" wifi=1,ap=0"))
        assertNull("хвост запрещён", JitRef.parseRestore("wifi=1,ap=0\n"))
        assertNull("чужая строка", JitRef.parseRestore("wifi=true,ap=false"))
        assertNull("обрезанная строка", JitRef.parseRestore("wifi=1"))
    }

    @Test
    fun netWireFormatIsReference() {
        assertEquals("wifi=1,ap=0", JitRef.Net(true, false).wire())
        assertEquals("wifi=0,ap=1", JitRef.Net(false, true).wire())
    }

    // ─────────────────────────── 3. сверка `bb` + `r7` ───────────────────────────

    @Test
    fun onlyTrueAndFalseAreFacts() {
        assertEquals(JitRef.Prop.TRUE, JitRef.parseProp(0, "true\n").value)
        assertEquals(JitRef.Prop.FALSE, JitRef.parseProp(0, "false").value)
        // прочитать нельзя — это НЕ «выключено» (отличие от референса, см. §3 отчёта)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(137, "ERR_ADB_TIMEOUT").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(1, "").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "a\nb").value)
        assertEquals(JitRef.Prop.UNKNOWN, JitRef.parseProp(0, "yes").value)
        assertEquals("многострочность проверяется на обрезанном выводе", JitRef.Prop.TRUE,
            JitRef.parseProp(0, "  true \n").value)
    }

    @Test
    fun verifyFormulaIsFromR7() {
        fun fact(v: JitRef.Prop) = JitRef.PropFact(v, v.name.lowercase(), 0, "ok")
        // ok = (pedido == getprop) && (!pedido || maps != FALSE)
        assertEquals(JitRef.Verify.OK, JitRef.verify(true, fact(JitRef.Prop.TRUE), true))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(true, fact(JitRef.Prop.FALSE), true))
        assertEquals(JitRef.Verify.OK, JitRef.verify(false, fact(JitRef.Prop.FALSE), true))
        assertEquals(JitRef.Verify.OK, JitRef.verify(false, fact(JitRef.Prop.FALSE), null))
        assertEquals(JitRef.Verify.CACHE_MISSING, JitRef.verify(false, fact(JitRef.Prop.FALSE), false))
        assertEquals(JitRef.Verify.NOT_APPLIED, JitRef.verify(false, fact(JitRef.Prop.TRUE), true))
        // нечитаемое свойство: результата нет (в референсе было бы false)
        assertEquals(JitRef.Verify.UNKNOWN, JitRef.verify(true,
            JitRef.PropFact(JitRef.Prop.UNKNOWN, "", 137, "rc_nonzero"), true))
    }

    @Test
    fun verifyNeverCallsUnknownSuccess() {
        for (v in JitRef.Verify.values()) {
            val label = JitRef.verifyLabel(v)
            if (v == JitRef.Verify.UNKNOWN) assertEquals("unknown", label)
            if (v == JitRef.Verify.OK) assertEquals("ok", label)
            assertFalse("нет ничего, кроме четырёх исходов", label.contains("success"))
        }
    }

    // ─────────────────────────── 4. тумблер ───────────────────────────

    @Test
    fun confirmedStateFollowsFactOnly() {
        fun f(v: JitRef.Prop) = JitRef.PropFact(v, "", 0, "ok")
        assertEquals("on", JitRef.confirmedState(f(JitRef.Prop.TRUE)))
        assertEquals("off", JitRef.confirmedState(f(JitRef.Prop.FALSE)))
        assertEquals("unknown", JitRef.confirmedState(f(JitRef.Prop.UNKNOWN)))
        assertEquals("unknown", JitRef.confirmedState(null))
    }

    // ─────────────────────────── 5. итог запуска ───────────────────────────

    @Test
    fun chainOutcomeDoesNotClaimRestart() {
        assertEquals(JitRef.ChainOutcome.REPLY_RECEIVED, JitRef.chainOutcome(0, "ok"))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST,
            JitRef.chainOutcome(137, "ERR_ADB_TIMEOUT after 40000ms"))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST,
            JitRef.chainOutcome(0, "ERR_ROOT_CHANNEL_UNAVAILABLE"))
        assertEquals(JitRef.ChainOutcome.CHANNEL_LOST, JitRef.chainOutcome(1, ""))
        assertEquals("reply_received", JitRef.chainOutcomeLabel(JitRef.ChainOutcome.REPLY_RECEIVED))
        assertEquals("channel_lost", JitRef.chainOutcomeLabel(JitRef.ChainOutcome.CHANNEL_LOST))
    }

    // ─────────────────────────── 6. чего в ядре быть не должно ───────────────────────────

    @Test
    fun noOwnMechanismsLeftInCore() {
        val core = java.io.File("src/main/java/com/example/voiceapp3/tools/JitNetCore.kt")
            .let { if (it.isFile) it else java.io.File("app/src/main/java/com/example/voiceapp3/tools/JitNetCore.kt") }
            .readLines()
            .filterNot { val s = it.trim(); s.startsWith("*") || s.startsWith("//") || s.startsWith("/*") }
            .joinToString("\n")
        for (bad in listOf(
            "jit_op", "enum class Phase", "enum class Shell", "StartDecision", "ClickGate",
            "HOLD_UNSAFE_PHASE", "opGate", "BUSY", "bootTag", "boot_id", "judgeWrite",
            "putStringConfirmed", "persist.dalvik", "usejitprofiles", "watchdog", "cancelFile"
        )) {
            assertFalse("в ядре не должно быть «$bad»", core.contains(bad))
        }
        assertTrue("снимок — ровно три ключа референса",
            core.contains("KEY_PEDIDO") && core.contains("KEY_AUTO") && core.contains("KEY_RESTORE"))
    }
}
