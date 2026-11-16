package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter58 §5 — ПОСТОЯННЫЙ TTL: доказательство владения на устройстве.
 *
 * Дефекты iter57, закреплённые здесь как регрессии:
 *  1. `TtlScript.kt:546–549` — `… || echo "TTL_OWNMARK write=failed"` и СЛЕДУЮЩАЯ строка
 *     безусловно печатала `write=ok`: отказ записи маркера маскировался «успехом», и дальше
 *     добавлялись правило и переход. Свои объекты оставались без подтверждённой identity, а при
 *     недоступном `-m comment` их уже не отличить от чужих;
 *  2. `TtlScript.kt:443–447` — общий маркер v4/v6: успешный OFF одного семейства удалял файл и
 *     стирал доказательство владения ДРУГИМ.
 *
 * Скрипт исполняется НАСТОЯЩИМ `/bin/sh` в подставном окружении ([Iter50Stand]: настоящие argv
 * подставных `iptables`/`ip6tables`, настоящая таблица в файле). Проверяются файл, argv и вывод —
 * не намерения кода.
 *
 * Чего тесты НЕ доказывают (в отчёте 🟡): реальные `iptables` на ГУ, переживание маркера
 * перезагрузкой, фактический TTL в тракте и совместная работа с раздачей/CarPlay.
 */
class Iter58TtlTest {

    private fun mutatingOurChain(st: Iter50Stand, chain: String): List<String> =
        st.argv().filter { line ->
            (line.contains("<-A>") || line.contains("<-I>")) && line.contains("<$chain>")
        }

    /** 1. Маркер не записался ⇒ ни правила, ни перехода, ни «успеха». */
    @Test
    fun unpersistedOwnMarkerStopsThePassAndRemovesTheChainWeMade() {
        val st = Iter50Stand()
        val token = "beef0058"
        val chain = TtlOwnership.chainFor(token)
        val script = TtlScript.build(true, gen = token, ownChain = chain,
            legacyChain = TtlOwnership.LEGACY_CHAIN)
        assertEquals("синтаксис production-скрипта", 0, st.syntaxCheck(script, "mark_fail.sh").first)

        val (rc, out) = st.exec(script, "mark_fail.sh",
            mapOf("VAPP_TTL_STATE" to File(st.dir, "no_such_dir").absolutePath))
        assertEquals("скрипт дошёл до конца: $out", 0, rc)

        assertTrue("причина названа: $out", out.contains("TTL_OWNMARK write=failed"))
        assertFalse("отказ записи не выдан за успех: $out", out.contains("TTL_OWNMARK write=ok"))
        assertTrue("проход остановлен с причиной: $out",
            out.contains("reason=ownmark_not_persisted"))
        assertFalse("правило в нашу цепь не добавлено: ${st.argv()}",
            mutatingOurChain(st, chain).isNotEmpty())
        assertFalse("переход не поставлен",
            st.rules("iptables").any { it.contains("-j $chain") })
        assertFalse("состояние не выдано за применённое: $out", out.contains("VERIFY_V4=OK"))
        assertFalse("созданная нами цепь не осталась сиротой: ${st.rules("iptables")}",
            st.rules("iptables").any { it.startsWith("$chain ") })
    }

    /** 2. Маркер прошлого поколения не доказывает владение. */
    @Test
    fun markerOfAnotherGenerationIsNotProofOfOwnership() {
        val st = Iter50Stand()
        val token = "11112222"
        val chain = TtlOwnership.chainFor(token)
        // Цепь с нашим ИМЕНЕМ и совпадающим телом, но БЕЗ комментария идентичности: имя и тело
        // владение не доказывают — единственным доказательством здесь был бы маркер создания.
        st.seedRule("iptables", chain, "-j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j $chain")
        val stateDir = File(st.dir, "ttlstate_gen").apply { mkdirs() }
        File(stateDir, "voiceapp3_ttl_own_$token").writeText(
            "chain=$chain\ngen=dead0000\ncreated=1\nv4=created\n"
        )
        val before = st.rules("iptables")

        for (enable in listOf(true, false)) {
            st.resetLogs()
            val script = TtlScript.build(enable, gen = token, ownChain = chain,
                legacyChain = TtlOwnership.LEGACY_CHAIN)
            val (_, out) = st.exec(script, "mark_gen.sh",
                mapOf("VAPP_TTL_STATE" to stateDir.absolutePath))
            assertFalse("чужое поколение не заявлено своим (enable=$enable): $out",
                out.contains("VERIFY_V4=OK"))
            assertTrue("состояние названо честно (enable=$enable): $out",
                out.contains("v4=unproven") || out.contains("v4=collision") ||
                    out.contains("v4=absent"))
            assertFalse("цепь не очищалась и не удалялась (enable=$enable): ${st.argv()}",
                st.argv().any {
                    (it.contains("<-F>") || it.contains("<-X>")) && it.contains("<$chain>")
                })
            assertEquals("таблица не изменилась (enable=$enable)", before, st.rules("iptables"))
        }
    }

    /** 3. OFF одного семейства не стирает маркер другого. */
    @Test
    fun removingOneFamilyKeepsTheOtherFamilysOwnershipRecord() {
        val st = Iter50Stand()
        val token = "cafe0058"
        val chain = TtlOwnership.chainFor(token)
        // Состояние «v4 установлен нами»: цепь с НАШИМ маркером идентичности + переход.
        st.seedRule("iptables", chain,
            "-m comment --comment voiceapp3:ttl:$token -j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j $chain")
        val stateDir = File(st.dir, "ttlstate_fam").apply { mkdirs() }
        val marker = File(stateDir, "voiceapp3_ttl_own_$token")
        marker.writeText("chain=$chain\ngen=$token\ncreated=1\nv4=created\nv6=created\n")

        val script = TtlScript.build(false, gen = token, ownChain = chain,
            legacyChain = TtlOwnership.LEGACY_CHAIN)
        val (_, out) = st.exec(script, "fam_off.sh",
            mapOf("VAPP_TTL_STATE" to stateDir.absolutePath))

        assertTrue("снятие названо: $out", out.contains("TTL_OWN family=v4 state=absent"))
        // Цепь снята ⇒ строка семейства v4 уходит, а доказательство владения v6 ОСТАЁТСЯ.
        // Дефект iter57 удалял файл целиком (`rm -f`), и последующий OFF v6 уже не мог отличить
        // свою цепь от чужой.
        assertTrue("снятие семейства названо: $out", out.contains("clear_family=v4"))
        assertTrue("файл маркера сохранён (им владеет v6): $out", out.contains("remaining=yes"))
        assertTrue("файл на месте", marker.exists())
        val left = marker.readText()
        assertFalse("строка v4 удалена: $left", left.contains("v4=created"))
        assertTrue("строка v6 на месте: $left", left.contains("v6=created"))
        assertTrue("шапка маркера цела: $left", left.contains("chain=$chain") && left.contains("gen=$token"))
    }

    /** 4. Синтаксис production-скрипта после правок — реальный `/bin/sh -n`. */
    @Test
    fun productionScriptStillPassesShellSyntaxCheck() {
        val st = Iter50Stand()
        for (enable in listOf(true, false)) {
            val token = if (enable) "abcd0058" else "abcd0059"
            val (rc, err) = st.syntaxCheck(
                TtlScript.build(enable, gen = token, ownChain = TtlOwnership.chainFor(token),
                    legacyChain = TtlOwnership.LEGACY_CHAIN), "syntax58.sh"
            )
            assertEquals("синтаксис (enable=$enable): $err", 0, rc)
        }
    }
}
