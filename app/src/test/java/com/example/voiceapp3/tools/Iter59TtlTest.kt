package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter59 §6 — ПОСТОЯННЫЙ TTL: владение ПО СЕМЕЙСТВУ, durable identity попытки, повтор без `Pair`.
 *
 * Дефекты iter58, закрытые здесь:
 *  1. `TtlScript.kt:129–138` — `ownmark_ok` всегда читал v4-таблицу: своя v4-цепь «доказывала» и
 *     одноимённую v6-цепь, которую мог создать кто-то другой. Теперь владение доказывается
 *     отдельно для каждого семейства (строка `v4=created`/`v6=created` + чтение ТЕМ ЖЕ
 *     инструментом).
 *  2. `TtlScript.kt:168–172` — один общий `NAMED_OWN` для двух семейств: имя цепи считалось
 *     доказательством сразу для обоих. Теперь `NAMED_OWN_V4`/`NAMED_OWN_V6`, и `chain_state`
 *     берёт флаг того семейства, чью таблицу читает.
 *  3. `TtlScript.kt:657–662` — отказ записи строки маркера v6 только логировался: правило
 *     добавлялось, а цепь оставалась без доказанной identity (асимметрично v4). Теперь проход
 *     останавливается, правила снимаются, `rc` снятия (`chain_delete_rc`) УЧИТЫВАЕТСЯ и печатается.
 *  4. `NetworkTtl.kt:363–387` — повтор через `Pair` (`executeScript`) не отличал «не отправлено» /
 *     «неизвестно» / «завершено», а identity попытки до отправки нигде не сохранялась.
 *
 * Скрипт исполняется НАСТОЯЩИМ `/bin/sh` в подставном окружении ([Iter50Stand]: настоящие argv
 * подставных `iptables`/`ip6tables`, настоящая таблица в файле). Проверяются файл маркера, argv и
 * вывод, а не намерения кода.
 *
 * Чего тесты НЕ доказывают (в отчёте 🟡/⛔): реальные `iptables`/`ip6tables` на ГУ, фактический
 * TTL в тракте, поведение с раздачей/CarPlay, переживание маркера перезагрузкой.
 */
class Iter59TtlTest {

    private fun ourChainMutations(st: Iter50Stand, chain: String, family: String): List<String> =
        st.argv().filter { line ->
            line.contains("ME=$family") && line.contains("<$chain>")
        }

    private fun stateDirWithMarker(st: Iter50Stand, token: String, chain: String, families: String): File {
        val dir = File(st.dir, "ttlstate_$token").apply { mkdirs() }
        File(dir, "voiceapp3_ttl_own_$token").writeText("chain=$chain\ngen=$token\ncreated=1\n$families")
        return dir
    }

    // ───────── 1. Чужая v6-цепь не становится «своей» из-за своей v4 ─────────

    @Test
    fun foreignV6ChainIsNotOursEvenWhenV4IsProvenOurs() {
        val st = Iter50Stand()
        val token = "a11c0059"
        val chain = TtlOwnership.chainFor(token)
        // v4 — НАША: цепь с маркером в правиле + переход.
        st.seedRule("iptables", chain,
            "-m comment --comment voiceapp3:ttl:$token -j TTL --ttl-set 65")
        st.seedRule("iptables", "POSTROUTING", "-o wlan0 -j $chain")
        // v6 — ЧУЖАЯ, но с ТЕМ ЖЕ именем и телом (владельца не доказывает).
        st.seedRule("ip6tables", chain, "-j HL --hl-set 65")
        // Маркер: создание записано только для v4 (v6 этим приложением не создавался).
        val stateDir = stateDirWithMarker(st, token, chain, "v4=created\n")

        val script = TtlScript.build(false, gen = token, ownChain = chain,
            legacyChain = TtlOwnership.LEGACY_CHAIN)
        assertEquals("синтаксис production-скрипта", 0, st.syntaxCheck(script, "fam59.sh").first)
        val (rc, out) = st.exec(script, "fam59.sh", mapOf("VAPP_TTL_STATE" to stateDir.absolutePath))
        assertEquals("скрипт дошёл до конца: $out", 0, rc)

        // Обе семьи названы, и v6 — НЕ наша.
        assertTrue("состояния названы: $out", out.contains("TTL_OWNERSHIP v4=") && out.contains("v6="))
        assertFalse("v6 не признана нашей: $out", out.contains("v6=ours_named") || out.contains("v6=ours"))
        assertTrue("v6 сохранена с причиной: $out",
            out.contains("TTL_OWN family=v6") && out.contains("action=keep"))
        assertFalse("v4 не удаляется вместе с чужой v6: $out", out.contains("reason=skip_all"))

        // Ни одного изменения чужой v6-цепи.
        // Чтение таблицы (`-S`) — это проверка владения, а не изменение: учитываем только мутации.
        val v6mut = st.argv().filter {
            it.contains("ME=ip6tables") && it.contains("<$chain>") &&
                listOf("<-A>", "<-I>", "<-D>", "<-F>", "<-X>", "<-N>").any { m -> it.contains(m) }
        }
        assertTrue("v6-цепь не тронута: $v6mut", v6mut.isEmpty())
        assertTrue("чужая v6-цепь на месте: ${st.rules("ip6tables")}",
            st.rules("ip6tables").any { it.contains("HL --hl-set 65") })
        // v4 при этом отработана независимо (своя цепь снята, доказательство сохранено).
        assertTrue("v4 снята: $out", out.contains("TTL_OWN family=v4 state=absent"))
        assertFalse("чужая цепь не стала «своей по имени»: $out",
            out.contains("TTL_ADD family=v6 chain=$chain action=reuse reason=chain_exists_ours"))
    }

    @Test
    fun v4AndV6OwnershipAreDecidedIndependently() {
        val st = Iter50Stand()
        val token = "b22d0059"
        val chain = TtlOwnership.chainFor(token)
        st.seedRule("iptables", chain,
            "-m comment --comment voiceapp3:ttl:$token -j TTL --ttl-set 65")
        st.seedRule("ip6tables", chain,
            "-m comment --comment voiceapp3:ttl:$token -j HL --hl-set 65")
        val stateDir = stateDirWithMarker(st, token, chain, "v4=created\nv6=created\n")

        val script = TtlScript.build(false, gen = token, ownChain = chain,
            legacyChain = TtlOwnership.LEGACY_CHAIN)
        val (_, out) = st.exec(script, "both59.sh", mapOf("VAPP_TTL_STATE" to stateDir.absolutePath))

        assertTrue("v4 своя: $out", out.contains("TTL_OWN family=v4 state=absent"))
        assertTrue("v6 своя: $out", out.contains("TTL_OWN family=v6 state=absent"))
        assertTrue("обе сняты: $out", out.contains("clear_family=v4") && out.contains("clear_family=v6"))
    }

    // ───────── 2. Маркер семейства v6 не подтвердился ⇒ v6-цепь убрана, rc учтён ─────────

    @Test
    fun unpersistedV6MarkerStopsThePassAndRemovesTheV6Chain() {
        val st = Iter50Stand()
        val token = "c33e0059"
        val chain = TtlOwnership.chainFor(token)
        // Маркер уже есть для v4, но файл доступен только на чтение: строка `v6=created`
        // записаться не может (реальная причина — переполненный/защищённый раздел).
        val stateDir = stateDirWithMarker(st, token, chain, "v4=created\n")
        val marker = File(stateDir, "voiceapp3_ttl_own_$token")
        marker.setWritable(false)

        val script = TtlScript.build(true, gen = token, ownChain = chain,
            legacyChain = TtlOwnership.LEGACY_CHAIN)
        val (_, out) = st.exec(script, "v6mark.sh", mapOf("VAPP_TTL_STATE" to stateDir.absolutePath))
        marker.setWritable(true)

        assertTrue("отказ назван: $out", out.contains("TTL_OWNMARK write=failed") &&
            out.contains("family=v6"))
        assertTrue("проход остановлен: $out", out.contains("reason=ownmark_not_persisted"))
        assertTrue("rc снятия цепи учтён и напечатан: $out",
            Regex("chain_delete_rc=\\d+").containsMatchIn(out))
        assertTrue("v6-правила сняты (откат): $out",
            out.contains("TTL_ROLLBACK") || out.contains("chain_flush_rc="))
        assertFalse("применение не заявлено: $out", out.contains("VERIFY_V6=OK"))
        val left = runCatching { marker.readText() }.getOrDefault("")
        assertFalse("маркер v6 не появился: $left", left.contains("v6=created"))
    }

    // ───────── 3. Durable identity попытки и правило повтора (без Pair) ─────────

    @Test
    fun attemptIdentityIsPersistedAndReadBackBeforeSend() {
        val f = File(Iter50Stand().dir, "ttl_attempt")
        assertFalse("пустой путь не считается попыткой", TtlAttempt.record(f, "", true, 1L))
        assertFalse("файла нет — записи нет", f.exists())

        assertTrue("запись подтверждена чтением", TtlAttempt.record(f, "/data/local/tmp/a.sh", true, 7L))
        val back = TtlAttempt.last(f)
        assertNotNull(back)
        assertEquals("/data/local/tmp/a.sh", back!!.path)
        assertTrue(back.want)
        assertEquals(7L, back.ts)
        assertTrue("поля читаются из файла: ${f.readText()}", back.evidence().contains("want=on"))

        // Повторная запись заменяет identity (новая попытка = новый nonce/путь).
        assertTrue(TtlAttempt.record(f, "/data/local/tmp/b.sh", false, 8L))
        assertEquals("/data/local/tmp/b.sh", TtlAttempt.last(f)!!.path)
        assertFalse(TtlAttempt.last(f)!!.want)

        // Повреждённая запись не выдаётся за подтверждённую.
        f.writeText("path=\nwant=on\nts=1\n")
        assertNull("пустой path не разбирается", TtlAttempt.parse(f.readText()))
    }

    @Test
    fun retryIsAllowedOnlyAfterAProvenNoUplinkRun() {
        val noUplink = "TTL_TRY variant=none scope=\"\" reason=no_uplink"
        val applied = "TTL_TRY variant=uplink scope=\"-o wlan0\"\nVERIFY_V4=OK"

        assertEquals("завершён без uplink — повтор разрешён", TtlAttempt.Decision.RETRY,
            TtlAttempt.decide(ScriptDelivery.DeliveryOutcome.Finished("op", 0), noUplink, 1, 2))
        assertEquals("завершён с применением — повтора нет", TtlAttempt.Decision.DONE,
            TtlAttempt.decide(ScriptDelivery.DeliveryOutcome.Finished("op", 0), applied, 1, 2))
        assertEquals("исход неизвестен — повтора НЕТ (мог примениться)",
            TtlAttempt.Decision.STOP,
            TtlAttempt.decide(
                ScriptDelivery.DeliveryOutcome.Unknown(
                    "op", ScriptDelivery.EvidenceRef("/data/local/tmp/x"), "state_unknown"
                ), noUplink, 1, 2
            ))
        assertEquals("не отправлено — повтор делает вызывающий проход",
            TtlAttempt.Decision.STOP,
            TtlAttempt.decide(ScriptDelivery.DeliveryOutcome.NotSent("upload_incomplete"), noUplink, 1, 2))
        assertEquals("лимит попыток соблюдается", TtlAttempt.Decision.STOP,
            TtlAttempt.decide(ScriptDelivery.DeliveryOutcome.Finished("op", 0), noUplink, 2, 2))
    }

    @Test
    fun productionRetryUsesTypedOutcomeAndPreparesANewIdentity() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt").readText()
        assertTrue("решение о повторе — по типизированному исходу",
            src.contains("TtlAttempt.decide(current, output, attempt, TTL_UPLINK_RETRIES)"))
        assertFalse("повтор больше не идёт через Pair (executeScript)",
            Regex("AdbClient\\.executeScript\\(\\s*\"ttl_script\"").containsMatchIn(src))
        // iter60 §4: identity записывается вместе с поколением намерения владельца.
        assertTrue("identity подтверждается ДО отправки",
            src.indexOf("if (!recordAttempt(prep.evidencePath, want, desire.generation))") in
                1 until src.indexOf("AdbClient.executePrepared(prep, 40000)"))
        assertTrue("перед записью попытки выполняется fence прошлой попытки " +
                "(и ДО fence — миграция legacy-записи: iter61 §4)",
            src.indexOf("if (!migrateLegacyBeforeFence(") in
                1 until src.indexOf("if (!resolvePreviousAttempts(") )
        assertFalse("попытка не отправляется без подтверждённой phase-записи",
            src.indexOf("if (!resolvePreviousAttempts(") < 0 ||
                !src.contains("if (!recordAttempt("))
        assertTrue("fence прошлой попытки идёт до подготовки новой отправки",
            src.indexOf("if (!resolvePreviousAttempts(") in
                1 until src.indexOf("val prep = ScriptDelivery.prepare("))
        assertTrue("каждая повторная попытка получает свою identity",
            src.contains("val nextPrep = ScriptDelivery.prepare(\"ttl_script\", script)"))
        assertTrue("неизвестный исход повтора не выдаётся за применение",
            src.contains("retry_unknown"))
    }

    /** Намерение владельца (desired ON) автоматика не сбрасывает — проверяется по production-коду. */
    @Test
    fun desiredOnIsNeverResetByFailedApplication() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt").readText()
        assertFalse("применение не пишет намерение владельца",
            Regex("Prefs2\\.putString\\([^)]*PREF_TTL_65_ACTIVE").containsMatchIn(src))
        assertTrue("пометка «применено» отделена от намерения: ${src.contains("markAppliedInternal")}",
            src.contains("PREF_TTL_65_APPLIED") && src.contains("(намерение владельца не меняется автоматикой)"))
    }
}
