package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33p-fix1 §1 (REVIEW замечание 1) — сеть обязана быть возвращена в ЛЮБОМ сценарии.
 *
 * Держим то, чего не хватало в 1.0.61:
 *  1. без надёжно сохранённого состояния сеть не выключается вообще (операция прерывается);
 *  2. отказ ПОСЛЕ отключения Wi-Fi компенсируется в той же операции (без рестарта zygote);
 *  3. возврат сети не привязан к успешной JIT-верификации (в т.ч. STALE и «канала нет»);
 *  4. сохранённое состояние не удаляется, пока возврат не подтверждён;
 *  5. на обычном старте сети не касаемся.
 */
class NetRestoreTest {

    private fun src(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private val net = "src/main/java/com/example/voiceapp3/tools/JitNet.kt"
    private val perf = "src/main/java/com/example/voiceapp3/tools/AppPerformance.kt"

    private fun bodyOf(source: String, fn: String): String {
        val i = source.indexOf(fn)
        if (i < 0) return ""
        val j = source.indexOf('{', i)
        if (j < 0) return ""
        var depth = 0
        for (k in j until source.length) {
            when (source[k]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(i, k + 1)
                }
            }
        }
        return source.substring(i)
    }

    // 1. Формат сохранённого состояния: wifi/ap + метка времени, битое — null.
    @Test
    fun savedStateRoundTrip() {
        val st = JitNet.State(true, false, 1_000L)
        assertEquals("wifi=1,ap=0,ts=1000", st.wire())
        assertEquals(st, JitNet.parse(st.wire()))
        assertEquals(null, JitNet.parse("ap=1"))
        assertEquals(null, JitNet.parse(null))
        assertEquals(null, JitNet.parse(""))
    }

    // 2. Просроченное состояние не применяем (иначе откатим ручные изменения владельца).
    @Test
    fun expiredStateIsNotApplied() {
        val st = JitNet.State(true, true, 0L)
        assertTrue("10 минут — граница", JitNet.isExpired(st.copy(tsMs = 1_000L), 1_000L + JitNet.SAVED_TTL_MS + 1))
        assertFalse(JitNet.isExpired(st.copy(tsMs = 1_000L), 1_000L + 1_000L))
        assertFalse("без метки времени не считаем просроченным", JitNet.isExpired(st, Long.MAX_VALUE / 2))
    }

    // 3. Без подтверждённого сохранения — операция прерывается ДО сетевых изменений.
    @Test
    fun abortWhenStateNotSaved() {
        val s = src(net)
        val save = bodyOf(s, "fun saveBeforeRestart")
        assertTrue("состояние читается до записи", save.indexOf("readState(") in 0 until save.indexOf("Prefs2.putString"))
        assertTrue("сохранение подтверждается чтением обратно", save.contains("savedState(app)"))
        assertTrue("не подтвердилось — не трогаем сеть", save.contains("(сеть не трогаем)"))

        val apply = bodyOf(src(perf), "fun jitApplyWithZygote")
        assertTrue("нет проверки на null", apply.contains("if (netSaved == null)"))
        assertTrue("прерывание до подготовки сети",
            apply.indexOf("if (netSaved == null)") < apply.indexOf("JitNet.runPreRestart"))
        val abort = bodyOf(apply.substring(apply.indexOf("if (netSaved == null)")), "if")
            .lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue("в отказе нет сетевых команд",
            !abort.contains("svc wifi disable") && !abort.contains("JitNet."))
        assertTrue("отказ логируется", apply.contains("aborted reason=no_net_state"))
    }

    // 4. Отказ после отключения Wi-Fi компенсируется в той же операции.
    @Test
    fun failureAfterDisableCompensates() {
        val apply = bodyOf(src(perf), "fun jitApplyWithZygote")
        val compIdx = apply.indexOf("JitNet.compensate")
        assertTrue("нужна компенсация", compIdx > 0)
        assertTrue("компенсация после подготовки сети", compIdx > apply.indexOf("JitNet.runPreRestart"))
        assertTrue("компенсация до выхода из функции", compIdx < apply.indexOf("JIT_APPLIED runtime="))
        assertTrue("в отказе нет рестарта zygote",
            !apply.substring(compIdx).substringBefore("return listOf").contains("ctl.restart"))

        val s = src(net)
        val comp = bodyOf(s, "fun compensate")
        assertTrue("компенсация = возврат по сохранённому состоянию", comp.contains("restoreAfterRestart"))
        assertTrue("в логе явно, что рестарта нет", comp.contains("zygote НЕ перезапускается"))
    }

    // 5. Возврат сети не зависит от JIT-верификации: выполняется сразу после решения о намерении.
    @Test
    fun restoreIsIndependentOfVerification() {
        val p = src(perf)
        val pending = bodyOf(p, "fun jitPendingVerify")
        val restoreIdx = pending.indexOf("restoreNetAndScheduleAvas(app, where, \"pending_verify\")")
        assertTrue("нужен ранний возврат сети", restoreIdx > 0)
        assertTrue("возврат ДО чтения props", restoreIdx < pending.indexOf("val read = {"))
        assertTrue("возврат ДО ожидания DONE",
            restoreIdx < pending.indexOf("while (!zr.contains(\"JIT_ZYGOTE_DONE\")"))
        assertTrue("STALE тоже возвращает сеть", pending.contains("restoreNetAndScheduleAvas(app, where, \"pending_stale\")"))
        assertTrue("в ветке «нет канала» сеть уже возвращена",
            pending.contains("net_restore=done_before_verify"))

        val helper = bodyOf(p, "fun restoreNetAndScheduleAvas")
        assertTrue("берём только сохранённое состояние", helper.contains("JitNet.savedState(app)"))
        assertTrue("просроченное — не применяем", helper.contains("JitNet.isExpired"))
        assertTrue("снимаем сохранение ТОЛЬКО по подтверждению", helper.contains("if (res?.allConfirmed == true)"))
        assertTrue("иначе сохраняем на следующий старт", helper.contains("keep_saved"))
    }

    // 6. На обычном старте сетевых действий нет: возврат живёт только в pending-пути.
    @Test
    fun noNetworkActionsOnPlainStart() {
        val p = src(perf)
        val probe = bodyOf(p, "fun jitBootProbe")
        assertFalse("jitBootProbe не трогает сеть",
            probe.contains("JitNet.") || probe.contains("svc wifi"))
        val pending = bodyOf(p, "fun jitPendingVerify")
        assertTrue("возврат только при наличии файла-намерения", pending.contains("if (!pendFile.isFile) return false"))
        assertTrue("возврат вызывается из pending-пути", pending.contains("restoreNetAndScheduleAvas"))
    }

    // 7. Лог различает запрос и фактическое состояние; Wi-Fi — до 3 попыток.
    @Test
    fun restoreLogsRequestVsState() {
        assertEquals("до 3 попыток", 3L, JitNet.WIFI_RESTORE_ATTEMPTS.toLong())
        assertEquals("пауза 3 с", 3_000L, JitNet.WIFI_RESTORE_PAUSE_MS)
        val s = src(net)
        assertTrue("запрос и состояние в одной строке", s.contains("request=\${request") || s.contains("request=\$request"))
        assertTrue("состояние интерфейса отдельно", s.contains("link=\$link"))
        assertTrue("причина отказа AP честная", s.contains("reason=\${err?") || s.contains("reason="))
        assertTrue("успех Wi-Fi — по факту, а не по запросу", s.contains("if (state == true) return true"))
        for (bad in listOf("setenforce", "pm install", "svc wifi enable")) {
            assertTrue("запрещено в JitNet: $bad", !s.contains(bad))
        }
    }
}
