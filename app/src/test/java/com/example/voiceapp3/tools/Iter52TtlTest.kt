package com.example.voiceapp3.tools

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter52 §6 — TTL: владение доказывается по ВСЕМ ссылкам на нашу цепочку (`-j` и `-g`, любая
 * цепь mangle, оба семейства), а «IPv6 отсутствует» — по флагу `v6_supported=0`, не по
 * отсутствию строки в выводе.
 *
 * Дефекты, которые закрываются (аудит §6):
 *  1. `jump_specs` смотрел только POSTROUTING и только `-j`: переход из другой цепи или `-g`
 *     не учитывался, `-F/-X` рвал чужое поведение, а TTL мог остаться применимым мимо нашей
 *     цепочки — при этом лог говорил «наших ссылок нет»;
 *  2. `VERIFY_V4=OK` не требовал отсутствия посторонних ссылок: правило работало шире, чем
 *     ставили, и это заявлялось полным успехом;
 *  3. в `TtlVerify` `VERIFY_V6` не разобран (`null`) трактовался как «IPv6 нет в системе» —
 *     догадка вместо факта.
 *
 * Проверяется на настоящем `/bin/sh` со строгим подставным iptables (каждый argv отдельно) и
 * production-парсером `TtlVerify.parse`. Чего тесты НЕ доказывают: TTL на проводе
 * (`wire=not_measured`), поддержку target `TTL` ядром ГУ и поведение её xtables.
 */
class Iter52TtlTest {

    private fun seedBase(st: Iter50Stand) {
        st.mountinfo.writeText(
            "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
                "2 1 253:0 /local /data rw - f2fs /dev/block/sda1 rw\n"
        )
    }

    private fun chainsOf(st: Iter50Stand, family: String = "iptables"): List<String> =
        File(st.dir, "state/chains.$family").let { if (it.exists()) it.readLines().map { s -> s.trim() }
            .filter { it.isNotEmpty() } else emptyList() }

    // ───────────────── §6.1: ссылки на нашу цепочку ─────────────────

    @Test
    fun gotoFromPostroutingStopsChainDeletion() {
        val st = Iter50Stand()
        seedBase(st)
        val (_, on) = st.exec(TtlScript.build(true), "on.sh")
        assertTrue("ON выполнен: $on", on.contains("VERIFY_V4=OK"))

        // Кто-то (или старый код) поставил goto на нашу цепочку. `-j`-only поиск этого не видит.
        st.seedRule("iptables", "POSTROUTING", "-g VAPP_TTL")

        val (rc, off) = st.exec(TtlScript.build(false), "off.sh")
        assertEquals(0, rc)
        assertTrue("ссылка через -g найдена: $off", off.contains("TTL_REF_KEEP"))
        assertTrue("цепочка НЕ удалена: $off", off.contains("reason=referenced_elsewhere"))
        assertTrue("наша цепочка на месте", chainsOf(st).contains("VAPP_TTL"))
        assertTrue("правило цепочки не стёрто",
            st.rules("iptables").any { it.startsWith("VAPP_TTL ") })
        assertTrue("goto не удалён", st.rules("iptables").contains("POSTROUTING -g VAPP_TTL"))
        assertFalse("снятие НЕ заявлено как OK: $off", off.contains("TTL_OFF own_v4=0 own_v6=0 foreign_v4=0 foreign_v6=0 result=OK"))
        // Цепочка сохранена → наше правило осталось → снятие НЕ названо OK. Любой из честных
        // исходов (PARTIAL/FAILED own_rules_left/CHECK_OWNER) допустим, а «OK» — нет.
        assertTrue("исход назван честно: $off",
            off.contains("result=PARTIAL") || off.contains("result=FAILED reason=own_rules_left") ||
                off.contains("CHECK_OWNER"))
        assertFalse("состояние «снято» не заявлено: $off", off.contains("VERIFY_V4=OFF_OK"))
    }

    @Test
    fun referenceFromAnotherChainBlocksChainDeletion() {
        val st = Iter50Stand()
        seedBase(st)
        st.exec(TtlScript.build(true), "on.sh")
        // Другая (пользовательская) цепь тоже ведёт в нашу.
        st.seedRule("iptables", "MYCHAIN", "-j VAPP_TTL")

        val (_, off) = st.exec(TtlScript.build(false), "off.sh")
        assertTrue("ссылка из чужой цепи найдена: $off", off.contains("TTL_REF_KEEP family=v4"))
        assertTrue("цепочка сохранена", chainsOf(st).contains("VAPP_TTL"))
        assertTrue("чужая цепь не изменена", st.rules("iptables").contains("MYCHAIN -j VAPP_TTL"))
    }

    @Test
    fun foreignReferencePreventsFullSuccessOnEnable() {
        val st = Iter50Stand()
        seedBase(st)
        st.seedRule("iptables", "MYCHAIN", "-j VAPP_TTL")
        // Цепочки ещё нет — ссылка появится только после -N; поэтому ON выполним дважды.
        st.exec(TtlScript.build(true), "on1.sh")
        val (_, on) = st.exec(TtlScript.build(true), "on2.sh")
        assertTrue("посторонняя ссылка названа: $on", on.contains("TTL_REF_FOREIGN"))
        // Полного успеха нет: либо прямо REF_ELSEWHERE, либо (что точнее) остановка, потому что
        // своё состояние снять нельзя — цепочка нужна чужой цепи.
        assertTrue("полного успеха нет: $on",
            on.contains("REF_ELSEWHERE") || on.contains("OWN_LEFT_STOP"))
        assertFalse("VERIFY_V4 не OK при ссылке из другой цепи: $on", on.contains("VERIFY_V4=OK"))
        // Цепочка с нашим правилом СОХРАНЕНА (на неё ссылается чужая цепь) — её нельзя ни
        // чистить, ни удалять, иначе сломается чужое поведение.
        assertTrue("наша цепочка сохранена", chainsOf(st).contains("VAPP_TTL"))
        assertTrue("правило цепочки не стёрто",
            st.rules("iptables").any { it.startsWith("VAPP_TTL ") })
        assertTrue("чужая цепь не тронута", st.rules("iptables").contains("MYCHAIN -j VAPP_TTL"))
        assertTrue("исход не заявлен успехом: $on",
            on.contains("OWN_LEFT_STOP") || on.contains("REF_ELSEWHERE") || on.contains("VERIFY_V4=FAIL"))
    }

    @Test
    fun unreadableTableDuringDropIsUnprovenNotEmpty() {
        val st = Iter50Stand()
        seedBase(st)
        st.exec(TtlScript.build(true), "on.sh")
        st.failRead("iptables")
        val (_, off) = st.exec(TtlScript.build(false), "off.sh")
        assertTrue("отказ чтения назван: $off", off.contains("read_v4=failed") || off.contains("READ_V4=failed"))
        assertFalse("OFF не заявлен как OK: $off",
            off.contains("TTL_OFF own_v4=0 own_v6=0 foreign_v4=0 foreign_v6=0 result=OK"))
        assertTrue("состояние неизвестно, а не «правил нет»: $off",
            off.contains("result=UNKNOWN") || off.contains("READ_UNKNOWN"))
    }

    // ───────────────── §6.2: разбор результата ─────────────────

    private fun output(
        verifyV4: String,
        verifyV6: String,
        v6Supported: String = "1",
        refsOut4: String = "0",
        refsOut6: String = "0",
        verdict: String = "SCOPED_OK"
    ): String = """
        TTL_OWNERSHIP v4=ours v6=ours v6_supported=$v6Supported read_v4=ok read_v6=ok own_chain=VAPP_TTL
        TTL_TRY variant=uplink scope="-o wlan0" carplay=none
        TTL_ADD family=v4 chain=VAPP_TTL rc=0
        TTL_ADD family=v4 jump scope="-o wlan0" rc=0
        TTL_V4=1 TTL_V6=1 OWN_V4=1 OWN_V6=1 OWN_J4=1 OWN_J6=1 FOREIGN_V4=0 FOREIGN_V6=0
        VERIFY_V4=$verifyV4 VERIFY_V6=$verifyV6 CHAIN4=ours CHAIN6=ours READ_V4=ok READ_V6=ok
        TTL_SCOPE result=scoped scoped=yes uplink_only=yes uplink=wlan0 rule="-o wlan0 -j VAPP_TTL"
        TTL_CHECK: rule_v4=65 rule_v6=65 real=UNKNOWN expected=65 iface=wlan0 method=rules_not_wire → $verdict
        TTL_RESULT rule_v4=65 rule_v6=65 verify_v4=$verifyV4 verify_v6=$verifyV6 wire_v4=not_measured enable=1 scoped=yes uplink=wlan0 variant=uplink own_chain=VAPP_TTL foreign_v4=0 foreign_v6=0 chain_v4=ours chain_v6=ours read_v4=ok read_v6=ok refs_out4=$refsOut4 refs_any4=$refsOut4 refs_out6=$refsOut6 refs_any6=$refsOut6
    """.trimIndent()

    @Test
    fun missingV6VerifyLineIsUnknownNotUnsupported() {
        // Вывод без строки VERIFY_V6 и без v6_supported=0: это ОБРЕЗАННЫЙ/потерянный вывод.
        val out = output("OK", "OK").lineSequence()
            .filterNot { it.contains("VERIFY_V6=") }
            .joinToString("\n")
        val p = TtlVerify.parse(true, out)
        assertNotEquals("отсутствие строки ≠ «IPv6 нет»: ${p.detail}", TtlVerify.Verdict.OK, p.verdict)
        assertTrue("причина называет неизвестность: ${p.reason}",
            p.reason?.contains("НЕ РАЗОБРАН") == true)
    }

    @Test
    fun explicitV6UnsupportedKeepsV4OnlySuccess() {
        val out = output("OK", "SKIPPED", v6Supported = "0")
        val p = TtlVerify.parse(true, out)
        assertEquals("доказанное отсутствие IPv6 — не ошибка", TtlVerify.Verdict.OK, p.verdict)
        assertTrue("и это сказано прямо: ${p.detail}", p.detail.contains("ТОЛЬКО IPv4"))
    }

    @Test
    fun foreignReferencesAreNotFullSuccess() {
        val p = TtlVerify.parse(true, output("OK", "OK", refsOut4 = "1"))
        assertNotEquals("правило работает шире, чем ставили — не полный успех", TtlVerify.Verdict.OK, p.verdict)
        assertTrue("сказано, что ссылки посторонние: ${p.reason}",
            p.reason?.contains("ссылаются") == true)
        assertEquals("факт сохранён для отчёта", "1", p.facts["refs_out4"])
    }

    @Test
    fun unreadableReferencesAreUnknownNotZero() {
        val p = TtlVerify.parse(true, output("OK", "OK", refsOut4 = "-1"))
        assertNotEquals("нечитаемые ссылки ≠ «ссылок нет»", TtlVerify.Verdict.OK, p.verdict)
        assertTrue("причина честная: ${p.reason}", p.reason?.contains("прочитать не удалось") == true)
    }
}
