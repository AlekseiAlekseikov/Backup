package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter55 §6 — TTL: не создавать объект, которым не умеешь управлять, и не объявлять откат
 * без фактического readback.
 *
 * Дефекты iter54 (аудит §2):
 *  1. при неподдержанном `-m comment` скрипт ВСЁ РАВНО создавал правило без маркера, а затем
 *     `chain_state` называл его `unproven` и OFF отказывался его снимать → активный TTL после OFF;
 *  2. `RB_V4=absent` был КОНСТАНТОЙ: отказ `-X` при уже снятом переходе давал ложный
 *     `ROLLED_BACK=yes`;
 *  3. rc `-N` не проверялся: если цепь с нашим именем уже существовала (конкурент/чужой объект),
 *     следующий `-A` дописывал правило в ЧУЖУЮ цепь.
 *
 * Исполняется НАСТОЯЩИЙ сгенерированный `TtlScript` на стенде [Iter50Stand]: настоящий `/bin/sh`
 * + stateful fake `iptables`/`ip6tables` с журналом argv и таблицей правил до/после.
 * Никаких настоящих сетевых команд хоста не выполняется.
 */
class Iter55TtlTest {

    private fun stand() = Iter50Stand().also {
        it.mountinfo.writeText(
            "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
                "2 1 253:0 /local /data rw - f2fs /dev/block/sda1 rw\n"
        )
    }

    private fun line(out: String, prefix: String): String =
        out.lineSequence().firstOrNull { it.startsWith(prefix) } ?: ""

    private fun has(out: String, text: String) = out.lineSequence().any { it.contains(text) }

    // ───── 1. `-m comment` не поддержан: ON → OFF и таблица возвращается к исходной ─────

    @Test
    fun withoutCommentSupportNamedOwnershipMakesOnAndOffReversible() {
        val st = stand()
        st.failComment("iptables")          // ядро без матча comment
        val before = st.rules("iptables")

        val (rcOn, on) = st.exec(
            TtlScript.build(true, gen = "abcd1234", ownChain = "VAPP_TTL_abcd1234"),
            "on.sh"
        )
        assertEquals("скрипт дошёл до конца: $on", 0, rcOn)
        assertTrue("поддержка comment отсутствует и это названо: ${line(on, "TTL_IDENTITY")}",
            line(on, "TTL_IDENTITY").contains("comment_supported=0"))
        assertTrue("владение по имени установки включено: ${line(on, "TTL_IDENTITY")}",
            line(on, "TTL_IDENTITY").contains("named_ownership=1"))
        // Правило СОЗДАНО (иначе «рабочий TTL» превращается в вечный отказ) и помечено в отчёте.
        assertTrue("правило создано: $on", has(on, "TTL_ADD family=v4 chain=VAPP_TTL_abcd1234"))
        assertTrue("цепь и переход реально есть: ${st.rules("iptables")}",
            st.rules("iptables").any { it.startsWith("VAPP_TTL_abcd1234 ") } &&
                st.rules("iptables").any { it.contains("-j VAPP_TTL_abcd1234") })

        // OFF: своя цепь доказана ИМЕНЕМ установки (маркера нет) и снимается.
        val (rcOff, off) = st.exec(
            TtlScript.build(false, gen = "abcd1234", ownChain = "VAPP_TTL_abcd1234"),
            "off.sh"
        )
        assertEquals("OFF дошёл до конца: $off", 0, rcOff)
        assertTrue("владение названо основанием, а не выдумкой: $off",
            has(off, "state=ours_named") && has(off, "ownership=unique_install_chain_name"))
        assertTrue("своя цепь снята (readback): $off", has(off, "removed=ok"))
        assertEquals("таблица вернулась к исходному состоянию: ${st.rules("iptables")}",
            before, st.rules("iptables"))
    }

    // ───── 2. Без comment и без подтверждённого имени: НИ ОДНОГО изменения таблицы ─────

    @Test
    fun withoutAnyProvenIdentityTheTableIsNotTouchedAtAll() {
        val st = stand()
        st.failComment("iptables")
        val before = st.rules("iptables")
        // Историческое имя (ownChain == legacy) ⇒ именем установки владение не доказывается.
        val (rc, out) = st.exec(
            TtlScript.build(true, gen = "default", ownChain = TtlScript.OUR_CHAIN),
            "on_named_off.sh"
        )
        assertEquals("скрипт дошёл до конца: $out", 0, rc)
        assertTrue("отказ назван: $out", has(out, "action=stop reason=no_safe_identity"))
        // Проба `-m comment` использует собственную временную цепь VAPP_TTLPROBE — она не в счёт.
        assertFalse("ни одного -A в нашу цепь и ни одного -N нашей цепи: $out",
            st.argv().any { it.contains("<-A> <VAPP_TTL>") || it.contains("<-N> <VAPP_TTL>") })
        assertEquals("таблица не изменена", before, st.rules("iptables"))
        assertFalse("ON не выдан за применённый: ${line(out, "TTL_CHECK")}",
            line(out, "TTL_CHECK").contains("SCOPED_OK"))
    }

    @Test
    fun unknownRegistryMeansNoTableChangeEvenWithUniqueNames() {
        val st = stand()
        st.failComment("iptables")
        val before = st.rules("iptables")
        val (_, out) = st.exec(
            TtlScript.build(true, gen = "abcd1234", ownChain = "VAPP_TTL_abcd1234", registryUnknown = true),
            "on_unknown_registry.sh"
        )
        assertTrue("причина названа: $out", has(out, "registry_unknown"))
        assertEquals("таблица не изменена", before, st.rules("iptables"))
    }

    // ───── 3. `-X` отказал при уже снятом переходе ⇒ НЕ ROLLED_BACK=yes ─────

    @Test
    fun failedChainDeleteIsNotReportedAsRolledBack() {
        val st = stand()
        st.failAdd("iptables")       // правило не встало → пойдёт откат
        st.failChainDelete("iptables") // цепь остаётся после -F
        val (_, out) = st.exec(
            TtlScript.build(true, gen = "abcd1234", ownChain = "VAPP_TTL_abcd1234"),
            "on_fail_x.sh"
        )
        // Переход снят/не появлялся (jumps=0), но ЦЕПЬ осталась — прежний код объявлял
        // `RB_V4=absent` константой и выдавал ROLLED_BACK=yes.
        assertTrue("откат шёл: $out", has(out, "TTL_ROLLBACK"))
        assertTrue("остаток цепи назван: $out",
            has(out, "v4_chain=empty") || has(out, "v4_chain=collision") || has(out, "v4_chain=unproven"))
        assertFalse("откат не доказан: $out", has(out, "TTL_ROLLBACK result=proved"))
        assertTrue("причина названа: $out", has(out, "result=unproven"))
        assertFalse("ROLLED_BACK не заявлен: $out", has(out, "ROLLED_BACK=yes"))
    }

    // ───── 4. Чужая цепь с нашим именем: правило в неё не дописывается ─────

    @Test
    fun existingForeignChainWithOurNameIsNeverAppendedTo() {
        val st = stand()
        st.failComment("iptables")
        // Чужая цепь с точно таким именем и ДРУГИМ телом: владение не доказано.
        st.seedRule("iptables", "VAPP_TTL_abcd1234", "-j TTL --ttl-set 128")
        val before = st.rules("iptables")
        val (_, out) = st.exec(
            TtlScript.build(true, gen = "abcd1234", ownChain = "VAPP_TTL_abcd1234"),
            "on_foreign_chain.sh"
        )
        assertTrue("чужое состояние названо: $out",
            has(out, "v4=collision") || has(out, "reason=family_not_owned"))
        assertFalse("в чужую цепь ничего не дописано: ${st.rules("iptables")}",
            st.rules("iptables").any { it.startsWith("VAPP_TTL_abcd1234 -j TTL --ttl-set 65") })
        assertEquals("таблица не изменена", before, st.rules("iptables"))
    }

    // ───── 5. Чтение таблицы недоступно ⇒ откат не объявляется доказанным ─────

    @Test
    fun unreadableRollbackReadbackIsNeverProofOfRollback() {
        val st = stand()
        st.seedRule("iptables", "VAPP_TTL_abcd1234", "-j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j VAPP_TTL_abcd1234")
        // Создание правила провалится (fail_add), после чего пойдёт откат; чтение — недоступно.
        File(st.dir, "state/fail_add.iptables").writeText("1")
        st.failRead("iptables")
        val (_, out) = st.exec(
            TtlScript.build(true, gen = "abcd1234", ownChain = "VAPP_TTL_abcd1234"),
            "on_read_fail.sh"
        )
        assertFalse("откат не доказан без readback: $out", has(out, "TTL_ROLLBACK result=proved"))
        assertTrue("неизвестность названа: $out",
            has(out, "result=unproven") || has(out, "read=failed") || has(out, "v4_chain=unknown"))
    }

}
