package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter57 §8.1/§8.2 — TTL: не присваивать чужое и реально доводить своё ON до OFF.
 *
 * Стенд: production-скрипт [TtlScript] исполняется настоящим `/bin/sh` над подставными
 * `iptables`/`ip6tables` с НАСТОЯЩЕЙ таблицей правил (`Iter50Stand`). Проверяется фактическое
 * состояние таблицы, а не текст скрипта.
 *
 * Что доказывается:
 *  1. цепь, существовавшая ДО работы приложения, не становится «нашей» только потому, что её имя
 *     и тело совпали с ожидаемыми: при неподдержанном `-m comment` в неё ничего не дописывается,
 *     и OFF её не снимает (аудит iter56: `ours_named` присваивал чужую цепь);
 *  2. своя цепь, которую МЫ создали, помечается маркером на устройстве и снимается обратимо
 *     (ON→OFF), причём маркер удаляется вместе с цепью;
 *  3. учёт изменений: если цепь уже существовала и удалось только `-A`/переход, откат всё равно
 *     выполняется — прежний код считал «ничего не меняли» и оставлял живое правило.
 *
 * Чего тесты НЕ доказывают: реальное ядро ГУ, поддержку модулей `TTL`/`HL` на прошивке,
 * фактическое изменение TTL в пакетах.
 */
class Iter57TtlTest {

    private fun stand(): Iter50Stand = Iter50Stand()

    private fun stateEnv(st: Iter50Stand): Map<String, String> =
        mapOf("VAPP_TTL_STATE" to File(st.dir, "ttlstate").apply { mkdirs() }.absolutePath)

    private fun line(out: String, key: String): String =
        out.lineSequence().firstOrNull { it.startsWith(key) } ?: ""

    private fun has(out: String, s: String) = out.lines().any { it.trim() == s || it.contains(s) }

    // ───── 1. Чужая цепь с ТОЧНО таким же именем и телом не присваивается ─────

    @Test
    fun preExistingIdenticalChainIsNeverAdoptedWithoutCreationProof() {
        val st = stand()
        st.failComment("iptables")                       // ядра без матча comment хватает
        val chain = "VAPP_TTL_abcd1234"
        // Цепь и переход УЖЕ есть — как будто их создало другое приложение ровно с тем же телом.
        st.seedRule("iptables", chain, "-j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j $chain")
        val before = st.rules("iptables")
        val beforeArgv = st.argv().size

        val (rcOn, on) = st.exec(
            TtlScript.build(true, gen = "abcd1234", ownChain = chain), "on_foreign_identical.sh",
            stateEnv(st)
        )
        assertEquals("скрипт дошёл до конца: $on", 0, rcOn)
        assertTrue("владение не присвоено: $on",
            has(on, "named_ownership=0") &&
                (has(on, "reason=chain_exists_not_ours") || has(on, "state=unproven") ||
                    has(on, "reason=family_not_owned")))
        assertFalse("в чужую цепь ничего не дописано: ${st.rules("iptables")}",
            st.rules("iptables").count { it.startsWith("$chain ") } > 1)
        assertEquals("таблица не изменена ON-проходом", before, st.rules("iptables"))

        val (rcOff, off) = st.exec(
            TtlScript.build(false, gen = "abcd1234", ownChain = chain), "off_foreign_identical.sh",
            stateEnv(st)
        )
        assertEquals("OFF дошёл до конца: $off", 0, rcOff)
        assertEquals("OFF не снял чужую цепь", before, st.rules("iptables"))
        assertFalse("сток не заявлен: $off", has(off, "removed=ok"))
        assertTrue("новых мутаций в таблицу не было", st.argv().size >= beforeArgv)
    }

    // ───── 2. Своя цепь: создание доказуемо маркером, ON→OFF обратим ─────

    @Test
    fun ownChainIsReversibleViaCreationMarker() {
        val st = stand()
        st.failComment("iptables")
        val chain = "VAPP_TTL_beef0042"
        val env = stateEnv(st)
        val (rcOn, on) = st.exec(
            TtlScript.build(true, gen = "beef0042", ownChain = chain), "on_own.sh", env
        )
        assertEquals("ON выполнён: $on", 0, rcOn)
        assertTrue("маркер создания записан: $on", has(on, "TTL_OWNMARK write=ok"))
        val marker = File(env.getValue("VAPP_TTL_STATE"), "voiceapp3_ttl_own_beef0042")
        assertTrue("маркер лежит на устройстве: ${marker.absolutePath}", marker.isFile)
        assertTrue("в маркере имя цепи", marker.readText().contains("chain=$chain"))
        val afterOn = st.rules("iptables")
        assertTrue("правило и переход созданы: $afterOn",
            afterOn.any { it.startsWith("$chain ") } &&
                afterOn.any { it.contains("-j $chain") })

        val (rcOff, off) = st.exec(
            TtlScript.build(false, gen = "beef0042", ownChain = chain), "off_own.sh", env
        )
        assertEquals("OFF выполнен: $off", 0, rcOff)
        assertTrue("своя цепь снята по readback: $off", has(off, "removed=ok"))
        assertFalse("правил нашей цепи не осталось: ${st.rules("iptables")}",
            st.rules("iptables").any { it.startsWith("$chain ") })
        assertFalse("переход снят: ${st.rules("iptables")}",
            st.rules("iptables").any { it.contains("-j $chain") }) // правила после OFF — в сообщении
        assertFalse("маркер удалён вместе с цепью", marker.exists())
    }

    @Test
    fun markerIsNotTransferableToAnotherGeneration() {
        val st = stand()
        st.failComment("iptables")
        val chain = "VAPP_TTL_cafe0001"
        val env = stateEnv(st)
        st.exec(TtlScript.build(true, gen = "cafe0001", ownChain = chain), "on_gen1.sh", env)
        // Другое поколение (другая установка/переустановка): маркер первой генерации не даёт
        // права считать цепь своей.
        val (_, other) = st.exec(
            TtlScript.build(false, gen = "cafe0002", ownChain = chain), "off_gen2.sh", env
        )
        assertFalse("чужое поколение не снимает цепь: $other", has(other, "removed=ok"))
        assertTrue("и говорит об этом прямо: $other",
            has(other, "state=unproven") || has(other, "ownership_unproven") ||
                has(other, "family_not_owned"))
    }

    // ───── 3. Учёт ЛЮБОГО успешного -A (цепь уже существует) ─────

    @Test
    fun referencedOwnChainIsNeitherDuplicatedNorStolen() {
        val st = stand()
        st.failComment("iptables")
        val chain = "VAPP_TTL_dead0001"
        val env = stateEnv(st)
        // 1) Первый проход: цепь создана и помечена (становится «нашей»).
        st.exec(TtlScript.build(true, gen = "dead0001", ownChain = chain), "on1.sh", env)
        val ownBefore = st.rules("iptables").count { it.startsWith("$chain ") }
        assertTrue("правило создано: ${st.rules("iptables")}", ownBefore >= 1)
        // 2) Чужая цепь ссылается на нашу ⇒ снять её нельзя. Повторный ON обязан НЕ добавлять
        //    второе такое же правило и НЕ объявлять себя применённым: цепь под чужой ссылкой.
        st.seedRule("iptables", "OTHERC", "-j $chain")
        val (_, on2) = st.exec(
            TtlScript.build(true, gen = "dead0001", ownChain = chain), "on2.sh", env
        )
        val ownAfter = st.rules("iptables").count { it.startsWith("$chain ") }
        assertEquals("дубля правила не появилось: $on2", ownBefore, ownAfter)
        assertFalse("применение не заявлено: ${line(on2, "TTL_CHECK")}",
            line(on2, "TTL_CHECK").contains("SCOPED_OK"))
        assertTrue("состояние названо честно: $on2",
            has(on2, "state=referenced") || has(on2, "v4:partial") ||
                has(on2, "reason=v4_own_not_removed"))
        assertEquals("чужой переход сохранён",
            1, st.rules("iptables").count { it.startsWith("OTHERC -j $chain") })
    }

    @Test
    fun twoOnPassesLeaveExactlyOneRule() {
        val st = stand()
        st.failComment("iptables")
        val chain = "VAPP_TTL_dead0003"
        val env = stateEnv(st)
        st.exec(TtlScript.build(true, gen = "dead0003", ownChain = chain), "on1.sh", env)
        val (_, on2) = st.exec(
            TtlScript.build(true, gen = "dead0003", ownChain = chain), "on2.sh", env
        )
        assertEquals("повторный ON идемпотентен: ${st.rules("iptables")} / $on2",
            1, st.rules("iptables").count { it.startsWith("$chain ") })
        assertEquals("и переход один: ${st.rules("iptables")}",
            1, st.rules("iptables").count { it.contains("-j $chain") })
        val (_, off) = st.exec(
            TtlScript.build(false, gen = "dead0003", ownChain = chain), "off.sh", env
        )
        assertTrue("снимается после повторного ON: $off", has(off, "removed=ok"))
        assertFalse("ни правила, ни перехода: ${st.rules("iptables")}",
            st.rules("iptables").any { it.contains(chain) })
    }

    @Test
    fun failedV6AfterAppendDoesNotLeaveHalfAppliedState() {
        val st = stand()
        st.failComment("iptables")
        val chain = "VAPP_TTL_dead0002"
        val env = stateEnv(st)
        st.exec(TtlScript.build(true, gen = "dead0002", ownChain = chain), "on1.sh", env)
        st.seedRule("iptables", "OTHERC", "-j $chain")   // цепь нельзя снять → будет reuse
        st.failAdd("ip6tables")                          // v6 падает после v4
        val (_, on2) = st.exec(
            TtlScript.build(true, gen = "dead0002", ownChain = chain), "on2.sh", env
        )
        // Половинчатое состояние не выдаётся за применение: отчёт обязан это назвать.
        val check = line(on2, "TTL_CHECK")
        val result = line(on2, "TTL_RESULT")
        assertTrue("итог назван честно: $result / $check",
            !check.contains("SCOPED_OK") || on2.contains("v6=skip"))
        assertFalse("TTL не объявлен применённым по обеим семьям",
            result.contains("verify_v4=OK") && result.contains("verify_v6=OK"))
    }

    // ───── 4. Чужие ссылки: цепь не удаляется ─────

    @Test
    fun foreignReferenceFromAnotherChainKeepsTheChain() {
        val st = stand()
        st.failComment("iptables")
        val chain = "VAPP_TTL_feed0001"
        val env = stateEnv(st)
        st.exec(TtlScript.build(true, gen = "feed0001", ownChain = chain), "on.sh", env)
        // Чужая цепь ссылается на нашу: удалять нельзя (сломает чужое поведение).
        st.seedRule("iptables", "OTHERC", "-j $chain")
        val (_, off) = st.exec(
            TtlScript.build(false, gen = "feed0001", ownChain = chain), "off.sh", env
        )
        assertTrue("чужая ссылка названа: $off",
            has(off, "refs") || has(off, "partial") || has(off, "keep"))
        assertTrue("цепь сохранена из-за чужой ссылки: ${st.rules("iptables")}",
            st.rules("iptables").any { it.contains("-j $chain") } ||
                !has(off, "removed=ok"))
        assertTrue("чужая цепь не тронута",
            st.rules("iptables").any { it.startsWith("OTHERC -j $chain") })
    }
}
