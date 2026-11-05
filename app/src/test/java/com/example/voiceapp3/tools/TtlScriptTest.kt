package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33h G1 — клик TTL не устанавливал правило, потому что в скрипт уходило ИМЯ переменной
 * (`enable=$enableBit`) вместо значения. Тест фиксирует подстановку `ENABLE=<0|1>`, наличие
 * честной строки результата `TTL_RESULT …` и строку `ROUTE_SRC=none reason=…`.
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

    // 2. Одна сводка на клик: правило/верификация/провод.
    @Test
    fun resultLinePresent() {
        val s = TtlScript.build(true)
        assertTrue("нужна строка TTL_RESULT:\n$s", s.contains("TTL_RESULT rule_v4="))
        for (f in listOf("rule_v6=", "verify_v4=", "verify_v6=", "wire_v4=", "enable=")) {
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
}
