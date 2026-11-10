package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p-fix1 §2 (REVIEW замечание 2) — санация `ip rule` не имеет права удалять чужие правила.
 *
 * Прежняя версия выбирала строки `oif wlan0`, но удаляла по `priority`. Если одинаковый priority
 * имеют разные правила (или список изменился между чтением и удалением), сносилось чужое правило.
 * Теперь решение — чистая функция [JitNet.sanitizePlan] (её же повторяет on-device скрипт):
 * удаляем только при однозначной идентификации полным набором селекторов, иначе отказ.
 */
class NetSanitizePlanTest {

    private val src: String
        get() = listOf(
            File("src/main/java/com/example/voiceapp3/tools/JitNet.kt"),
            File("app/src/main/java/com/example/voiceapp3/tools/JitNet.kt")
        ).first { it.isFile }.readText()

    // 1. Разбор строк `ip rule show`.
    @Test
    fun parsesRuleLines() {
        val rules = JitNet.parseRules(
            """
            0:	from all lookup local
            10000:	from all fwmark 0x10000/0x10000 lookup 1001
            11000:	from all oif wlan0 lookup 1022
            """.trimIndent()
        )
        assertEquals(3, rules.size)
        assertEquals(10000, rules[1].priority)
        assertEquals("from all fwmark 0x10000/0x10000 lookup 1001", rules[1].spec)
        assertEquals("from all oif wlan0 lookup 1022", rules[2].spec)
    }

    // 2. Чужое правило (без oif wlan0) в план удаления не попадает.
    @Test
    fun foreignRulesNeverDeleted() {
        val plan = JitNet.sanitizePlan(
            """
            0:	from all lookup local
            10000:	from all fwmark 0x10000/0x10000 lookup 1001
            """.trimIndent()
        )
        assertTrue("ни одного удаления", plan.none { it.action == JitNet.RuleAction.DELETE })
        assertTrue("все — SKIP_NOT_OIF_WLAN0",
            plan.all { it.action == JitNet.RuleAction.SKIP_NOT_OIF_WLAN0 })
    }

    // 3. Однозначное правило → удаление ПОЛНЫМ набором селекторов, а не по priority.
    @Test
    fun exactRuleIsDeletedByFullSpec() {
        val plan = JitNet.sanitizePlan("11000:\tfrom all oif wlan0 lookup 1022")
        val d = plan.single()
        assertEquals(JitNet.RuleAction.DELETE, d.action)
        assertEquals("ip rule del from all oif wlan0 lookup 1022", d.command)
        assertFalse("удаление только по priority запрещено",
            d.command!!.matches(Regex("^ip rule del priority \\d+$")))
    }

    // 4. Дубликат priority → отказ (даже если селекторы выглядят нормально).
    @Test
    fun duplicatePriorityIsRefused() {
        val plan = JitNet.sanitizePlan(
            """
            11000:	from all oif wlan0 lookup 1022
            11000:	from all fwmark 0x1 lookup 1033
            """.trimIndent()
        )
        val oif = plan.first { it.rule.spec.contains("oif wlan0") }
        assertEquals(JitNet.RuleAction.SKIP_DUPLICATE_PRIORITY, oif.action)
        assertEquals("команды быть не должно", null, oif.command)
        assertTrue("ни одного DELETE", plan.none { it.action == JitNet.RuleAction.DELETE })
    }

    // 5. Несколько `oif` в одной строке → отказ.
    @Test
    fun multipleOifIsRefused() {
        val plan = JitNet.sanitizePlan("11000:\tfrom all oif wlan0 oif usb0 lookup 1022")
        assertEquals(JitNet.RuleAction.SKIP_MULTIPLE_OIF, plan.single().action)
        assertEquals(null, plan.single().command)
    }

    // 6. Похожий, но ЧУЖОЙ интерфейс (`wlan0.1`) не удаляем.
    @Test
    fun similarInterfaceIsRefused() {
        val plan = JitNet.sanitizePlan("11000:\tfrom all oif wlan0.1 lookup 1022")
        assertEquals(JitNet.RuleAction.SKIP_NOT_OIF_WLAN0, plan.single().action)
    }

    // 7. Скрипт повторно читает список и отказывается при неоднозначности (защита от устаревания).
    @Test
    fun scriptReReadsAndRefusesAmbiguity() {
        val s = src
        val script = s.substringAfter("internal fun sanitizeScript()").substringBefore("// ──")
        assertTrue("список перечитывается в цикле", script.contains("ip rule show 2>/dev/null | grep 'oif wlan0'"))
        assertTrue("проверка дубликата priority", script.contains("duplicate_priority"))
        assertTrue("проверка нескольких oif", script.contains("multiple_oif"))
        assertTrue("проверка чужого интерфейса", script.contains("not_wlan0") || script.contains("why=not_wlan0"))
        assertTrue("удаление по полному spec", script.contains("ip rule del \${D}spec"))
        assertTrue("удаления только по priority нет", !script.contains("ip rule del priority"))
        assertTrue("итог виден в логе", script.contains("scope=oif_wlan0"))
        for (bad in listOf("flush", "iptables", "ip6tables", "oif wlan1", "oif usb0")) {
            assertTrue("санация не трогает: $bad", !script.contains(bad))
        }
    }
}
