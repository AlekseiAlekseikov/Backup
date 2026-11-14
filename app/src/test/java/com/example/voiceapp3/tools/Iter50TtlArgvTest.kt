package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter50 §5.1 — дефект передачи параметров TTL и его исправление, проверенные на
 * СГЕНЕРИРОВАННОМ production-скрипте в настоящем `/bin/sh` со строгим fake iptables.
 *
 * Дефект (до исправления): `drop_family` перебирала спецификации, временно ставя
 * `IFS=<newline>`; внутри того же цикла `iptables -D POSTROUTING $spec` получал спецификацию
 * ОДНИМ аргументом → реальный iptables отвергает вызов → наше правило остаётся на месте, а OFF
 * заявляет FAILED (или, хуже, при «rc не проверяем» — ложный успех).
 */
class Iter50TtlArgvTest {

    private fun on() = TtlScript.build(true)
    private fun off() = TtlScript.build(false)

    private fun seedBase(st: Iter50Stand) {
        // /data — отдельная ФС (root=/local), как на реальных Android-устройствах.
        st.mountinfo.writeText(
            "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
                "2 1 253:0 /local /data rw - f2fs /dev/block/sda1 rw\n"
        )
    }

    @Test
    fun onThenOffUsesSeparateArgvAndRemovesOurRules() {
        val st = Iter50Stand()
        seedBase(st)
        val (rcOn, outOn) = st.exec(on(), "on.sh")
        assertEquals("ON-скрипт завершился без ошибок: $outOn", 0, rcOn)
        assertTrue("ON подтверждён", outOn.contains("VERIFY_V4=OK"))
        assertFalse("склейки argv нет", outOn.contains("GLUED_ARGV"))

        st.resetLogs()
        val (rcOff, outOff) = st.exec(off(), "off.sh")
        assertEquals(0, rcOff)
        assertFalse("OFF не получил склеенный argv: $outOff", outOff.contains("GLUED_ARGV"))
        assertTrue(
            "удаление выполнено, а не «отказано»: $outOff",
            outOff.contains("TTL_DEL family=v4 spec=\"-o wlan0 -j VAPP_TTL\" rc=0")
        )
        assertTrue("OFF подтверждён", outOff.contains("result=OK"))
        assertEquals("наши правила сняты", emptyList<String>(), st.rules("iptables"))

        // Главное: каждый argv передан отдельно (проверяется по журналу fake iptables).
        val del = st.argv().firstOrNull { it.contains("<-D>") }
        assertTrue("вызов -D был", del != null)
        assertTrue(
            "argv раздельные: <-o> <wlan0> <-j> <VAPP_TTL>, получено: $del",
            del!!.contains("<-D> <POSTROUTING> <-o> <wlan0> <-j> <VAPP_TTL>")
        )
    }

    @Test
    fun repeatedOnKeepsExactlyOneJumpAndOneRule() {
        val st = Iter50Stand()
        seedBase(st)
        st.exec(on(), "on.sh")
        st.exec(on(), "on2.sh")
        assertEquals(
            "ровно одно наше правило в цепочке и один переход",
            listOf("VAPP_TTL -j TTL --ttl-set 65", "POSTROUTING -o wlan0 -j VAPP_TTL"),
            st.rules("iptables")
        )
    }

    @Test
    fun foreignRulesSurviveByteForByte() {
        val st = Iter50Stand()
        seedBase(st)
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j TTL --ttl-set 64")
        st.seedRule("iptables", "XOTHER", "-j TTL --ttl-set 63")
        st.seedRule("iptables", "POSTROUTING", "-j XOTHER")
        val before = st.rules("iptables")
        st.exec(on(), "on.sh")
        st.exec(off(), "off.sh")
        assertEquals("чужие правила не изменены", before, st.rules("iptables"))
    }

    @Test
    fun removalFailureStopsOnInsteadOfDuplicating() {
        val st = Iter50Stand()
        seedBase(st)
        st.exec(on(), "on.sh")
        st.resetLogs()
        // Теперь `-D` отказывает: снять своё правило нельзя.
        st.failDelete("iptables")
        val (_, out) = st.exec(on(), "on2.sh")
        assertTrue("политика — остановиться, а не продолжать", out.contains("TTL_TRY action=stop"))
        assertTrue("вердикт честный (TTL_CHECK … OWN_LEFT_STOP): $out", out.contains("OWN_LEFT_STOP"))
        // Даже при корректном «старом» правиле в таблице разбор для UI обязан быть FAILED:
        // «включено сейчас» не доказано — своё снять не смогли.
        val parsed = TtlVerify.parse(true, out)
        assertEquals("остановка не выдаётся за успех: ${parsed.detail}", TtlVerify.Verdict.FAILED, parsed.verdict)
        assertTrue(
            "причина названа: ${parsed.reason}",
            parsed.reason?.contains("снять не удалось") == true
        )
        // Главное: рядом со старым правилом не появилось второе.
        assertEquals(
            "дубликатов нет: второе -A/-N после неудачного -D не выполнено",
            listOf("VAPP_TTL -j TTL --ttl-set 65", "POSTROUTING -o wlan0 -j VAPP_TTL"),
            st.rules("iptables")
        )
        assertTrue(
            "ни одного прибавления после отказа: ${st.argv()}",
            st.argv().none { it.contains("<-A>") || it.contains("<-N>") }
        )
    }

    @Test
    fun readFailureIsUnknownNotZeroRules() {
        val st = Iter50Stand()
        seedBase(st)
        File(st.dir, "state/fail_read.iptables").writeText("1")
        val (_, out) = st.exec(off(), "off.sh")
        assertTrue("отказ чтения виден", out.contains("READ_V4=failed"))
        assertTrue("результат — UNKNOWN, а не OK", out.contains("result=UNKNOWN"))
        assertFalse("не «правил нет»", out.contains("result=OK"))
    }

    @Test
    fun quotedForeignJumpIsRefusedAndOwnChainKept() {
        val st = Iter50Stand()
        seedBase(st)
        st.seedRule("iptables", "VAPP_TTL", "-j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -m comment --comment \"a b\" -j VAPP_TTL")
        val (_, out) = st.exec(off(), "off.sh")
        assertTrue(
            "сложная спецификация не исполняется: $out",
            out.contains("rc=refused reason=quoted_spec_needs_owner") ||
                out.contains("rc=refused reason=spec_outside_our_format")
        )
        assertFalse("чужой переход не удалён", st.rules("iptables").none { it.contains("comment") })
    }

    @Test
    fun gotoJumpIsNotTreatedAsOurJump() {
        val st = Iter50Stand()
        seedBase(st)
        st.seedRule("iptables", "VAPP_TTL", "-j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -g VAPP_TTL")
        val (_, out) = st.exec(off(), "off.sh")
        // `-g` не считается нашим переходом: цепочка «наша», но переход остаётся → честный pending.
        assertFalse("goto не удаляется как наше правило", out.contains("result=OK"))
        assertTrue(
            "goto остался в таблице (чужое не трогаем)",
            st.rules("iptables").any { it.contains("-g VAPP_TTL") }
        )
    }

    @Test
    fun noUplinkKeepsIntentWithoutGlobalRule() {
        val st = Iter50Stand()
        seedBase(st)
        // нет default route → uplink не определён; production-скрипт берёт ip route get
        val (_, out) = st.exec(on(), "on.sh")
        assertTrue(
            "без uplink либо «нет uplink», либо правило со скоупом; глобального правила быть не должно: $out",
            st.rules("iptables").none { it == "POSTROUTING -j VAPP_TTL" }
        )
    }
}
