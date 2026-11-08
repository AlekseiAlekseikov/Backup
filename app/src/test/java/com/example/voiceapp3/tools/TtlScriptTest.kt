package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33h G1 — клик TTL не устанавливал правило, потому что в скрипт уходило ИМЯ переменной
 * (`enable=$enableBit`) вместо значения.
 *
 * iter33k §3 — скрипт переписан на ПРАВИЛА СО СКОУПОМ (глобальное правило ломало CarPlay),
 * измерение по проводу (tcpdump) убрано: проверка — счётчики правил.
 *
 * iter33m §1 — удаление БЕЗ word-splitting (`while IFS= read -r spec`), скоуп ТОЛЬКО `-o <uplink>`
 * (fallback `! -o p2p0`/`exclude_carplay` удалён: групповой `p2p-p2p0-0` он не покрывал и ломал
 * CarPlay), санация `TTL_SANITIZED`, честный итог OFF (`TTL_V4=0 && TTL_V6=0`).
 */
class TtlScriptTest {

    // 1. Значение подставляется, имя переменной в скрипте не остаётся.
    @Test
    fun enableValueSubstituted() {
        val on = TtlScript.build(true)
        val off = TtlScript.build(false)
        assertTrue("в ON-скрипте должно быть ENABLE=1:\n$on", on.contains("ENABLE=1"))
        assertTrue("в OFF-скрипте должно быть ENABLE=0", off.contains("ENABLE=0"))
        assertFalse("имя переменной не должно попадать в шелл", on.contains("enableBit"))
        assertTrue("условие по ENABLE обязано быть", on.contains("if [ \"\$ENABLE\" = \"1\" ]"))
        assertFalse("старое условие по enableBit запрещено", off.contains("if [ \"\$enableBit\""))
    }

    // 2. Одна сводка на клик + честная отметка, что провод не измерялся.
    @Test
    fun resultLinePresent() {
        val s = TtlScript.build(true)
        assertTrue("нужна строка TTL_RESULT:\n$s", s.contains("TTL_RESULT rule_v4="))
        for (f in listOf("rule_v6=", "verify_v4=", "verify_v6=", "wire_v4=not_measured", "enable=", "scoped=", "variant=")) {
            assertTrue("в TTL_RESULT нет поля $f", s.contains(f))
        }
        assertTrue("финальная строка TTL_CHECK обязана остаться", s.contains("TTL_CHECK:"))
    }

    // 3. Честная причина, когда ни маршрута, ни ip нет.
    @Test
    fun routeSourceReason() {
        val s = TtlScript.build(true)
        assertTrue(s.contains("ROUTE_SRC=none reason=no_ip_binary"))
        assertTrue(s.contains("ROUTE_SRC=none reason=no_default_route"))
    }

    // 4. iter33m §1.1 — удаление правил БЕЗ word-splitting.
    @Test
    fun delAllHasNoWordSplitting() {
        val off = TtlScript.build(false)
        assertTrue("нужен цикл while IFS= read -r spec (без разбиения по пробелам):\n$off",
            off.contains("while IFS= read -r spec"))
        assertTrue("спецификатор правила отдаёт sed '-A POSTROUTING ' → ''",
            off.contains("sed -n 's/^-A POSTROUTING //p'"))
        assertTrue("удаление точным спецификатором", off.contains("-D POSTROUTING \$spec"))
        assertFalse("word-splitting через for spec in \$(…) запрещён", off.contains("for spec in \$(iptables"))
        assertFalse("word-splitting через for spec in \$(…) запрещён (v6)", off.contains("for spec in \$(ip6tables"))
        assertTrue("эталонный цикл -D … || break обязан остаться", off.contains("|| break"))
    }

    // 5. iter33m §1.2 — скоуп ТОЛЬКО uplink, вариант «всё кроме CarPlay» удалён.
    @Test
    fun scopeIsUplinkOnly() {
        val s = TtlScript.build(true)
        assertTrue("правило ставится с -o \$UPLINK", s.contains("-o \$UPLINK"))
        assertFalse("fallback 'всё кроме CarPlay' запрещён (ломал CarPlay)", s.contains("! -o \$CP_FIRST"))
        assertFalse("вариант exclude_carplay удалён", s.contains("exclude_carplay"))
        assertFalse("причина no_uplink_no_carplay удалена", s.contains("no_uplink_no_carplay"))
        assertTrue("uplink-CarPlay отбраковывается явно", s.contains("TTL_UPLINK_REJECT"))
        assertTrue("строка TTL_TRY остаётся", s.contains("TTL_TRY variant="))
        assertTrue("строка TTL_SCOPE остаётся", s.contains("TTL_SCOPE result="))
        assertTrue("вердикт учитывает uplink_only", s.contains("SCOPE_UPLINK_ONLY=yes"))
    }

    // 6. Нет uplink — правило не ставится, тумблер получает честную причину.
    @Test
    fun noUplinkIsHonestFail() {
        val s = TtlScript.build(true)
        assertTrue("нужна попытка с variant=none", s.contains("TTL_TRY variant=none"))
        assertTrue("причина — no_uplink", s.contains("reason=no_uplink"))
        assertTrue("скоуп печатает failed при отсутствии uplink",
            s.contains("TTL_SCOPE result=failed"))
    }

    // 7. Санация: сколько сняли + чужое правило в лог.
    @Test
    fun sanitizeReportsRemovedCount() {
        for (s in listOf(TtlScript.build(true), TtlScript.build(false))) {
            assertTrue("нужна строка TTL_SANITIZED removed=", s.contains("TTL_SANITIZED removed="))
            assertTrue("санация считает v4_removed", s.contains("v4_removed="))
            assertTrue("санация считает v6_removed", s.contains("v6_removed="))
            assertTrue("судьба правила печатается (TTL_DEL)", s.contains("TTL_DEL v4 spec="))
            assertTrue("чужое правило ttl-set логируется", s.contains("TTL_FOREIGN"))
        }
    }

    // 8. iter33m §1.3 — OFF честен только при нуле правил; VERIFY у OFF отдельным маркером.
    @Test
    fun offVerdictNeedsZeroRules() {
        val off = TtlScript.build(false)
        assertTrue("TTL_OFF печатается только при выключении", off.contains("TTL_OFF v4="))
        assertTrue("result=OK только при TTL_V4=0 и TTL_V6=0",
            off.contains("TTL_OFF v4=\$TTL_V4 v6=\$TTL_V6 result="))
        assertTrue("при выключении правило не добавляется",
            !off.contains("mangle -A POSTROUTING -o "))
        assertTrue("при включении правило добавляется ровно со скоупом uplink",
            TtlScript.build(true).contains("mangle -A POSTROUTING \$SCOPE -j TTL --ttl-set 65"))
        // OFF-маркер не должен выглядеть как «правило стоит»
        assertTrue("VERIFY_V4=OFF_OK для выключения", off.contains("VERIFY_V4=OFF_OK"))
        assertTrue("OFF_OK попадает в вердикт", off.contains("VERDICT=OFF_OK"))
        val on = TtlScript.build(true)
        assertTrue("для включения нужен VERIFY_V4=OK", on.contains("VERIFY_V4=OK"))
        assertTrue("--ttl-set 65 остаётся значением правила", on.contains("--ttl-set 65"))
    }

    // 9. iter33n §4.4 — после ON правило РОВНО одно: счётчик печатается, лишние копии снимаются.
    @Test
    fun onKeepsSingleRule() {
        val on = TtlScript.build(true)
        assertTrue("нужна строка TTL_ADD_COUNT n=", on.contains("TTL_ADD_COUNT n="))
        assertTrue("дедупликация лишних копий", on.contains("dedupe()"))
        assertTrue("снимаем копию через -D POSTROUTING \$SCOPE",
            on.contains("-D POSTROUTING \$SCOPE -j TTL --ttl-set 65"))
    }

    // 10. iter33m/n §1.3 — OFF печатает OK при нуле правил (парс-баг v6=null исправлен).
    @Test
    fun offMessageIsOkWhenNoRules() {
        val off = TtlScript.build(false)
        assertTrue("TTL_OFF result=OK строится из счётчиков",
            off.contains("result=\$([ \"\$TTL_V4\" = \"0\" ]"))
        assertTrue("VERIFY_V4/V6 = OFF_OK", off.contains("VERIFY_V4=OFF_OK") && off.contains("VERIFY_V6=OFF_OK"))
    }
}
