package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33h G1 — клик TTL не устанавливал правило, потому что в скрипт уходило ИМЯ переменной
 * (`enable=$enableBit`) вместо значения.
 *
 * iter33k §3 — скрипт переписан на ПРАВИЛА СО СКОУПОМ (глобальное правило ломало CarPlay),
 * измерение по проводу (tcpdump) убрано: проверка — счётчики правил. Тест держит это.
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
        for (f in listOf("rule_v6=", "verify_v4=", "verify_v6=", "wire_v4=not_measured", "enable=", "scoped=")) {
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

    // 4. iter33k §3: скоуп обязателен, tcpdump больше не нужен.
    @Test
    fun scopedOnlyNoTcpdump() {
        val s = TtlScript.build(true)
        assertFalse("tcpdump/снятие по проводу убрано:\n$s", s.contains("tcpdump"))
        assertFalse(s.contains("run_v4_capture"))
        assertTrue("нужна диагностика TTL_NET", s.contains("TTL_NET ifaces="))
        assertTrue("нужна строка попытки TTL_TRY", s.contains("TTL_TRY variant="))
        assertTrue("нужна строка итога скоупа TTL_SCOPE", s.contains("TTL_SCOPE result="))
        assertTrue("отказ без uplink и без CarPlay печатается явно",
            s.contains("TTL_TRY variant=none"))
        assertTrue("глобальное правило запрещено — в скрипте есть отказ",
            s.contains("reason=no_uplink_no_carplay"))
        // вариант «только со скоупом»: правило всегда с -o
        assertTrue(s.contains("-o \$UPLINK"))
        // вариант «всё кроме CarPlay»: первый найденный интерфейс CarPlay (CP_FIRST)
        assertTrue("нет исключения CarPlay в скоупе", s.contains("! -o \$CP_FIRST"))
    }

    // 5. Выключение — эталонный цикл 1..5 и generic-удаление; строка TTL_OFF только в OFF.
    @Test
    fun offRemovesRules() {
        val off = TtlScript.build(false)
        assertTrue(off.contains("TTL_OFF v4="))
        assertTrue("эталонный цикл удаления 1..5", off.contains("for i in 1 2 3 4 5"))
        assertTrue("generic-удаление по iptables -S", off.contains("iptables -t mangle -S POSTROUTING"))
        assertFalse("при выключении правило не добавляется", off.contains("-A POSTROUTING -o "))
    }

    // 6. Вердикт требует ≥1 правила и скоуп.
    @Test
    fun verdictRequiresScopedRule() {
        val s = TtlScript.build(true)
        assertTrue(s.contains("\"\$TTL_V4\" -ge 1"))
        assertTrue(s.contains("VERDICT=SCOPED_OK"))
        assertTrue(s.contains("VERDICT=OFF_OK"))
    }
}
