package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter45 §4/§5/§6/§7 — регрессии на уровне КОДА (как `JitUiStateTest`/`JitRefTest`).
 *
 * Уровень доказательности: **код**. Тесты читают production-исходники и падают, если
 * исправление откатили (снова появилось авто-выключение тумблера, пропал запрос снимка
 * состояния, вернулась бессрочная блокировка JIT). Android-интеграция и ГУ здесь НЕ проверяются.
 */
class Toggles45UiTest {

    private fun code(rel: String): String =
        listOf(File(rel), File("app/$rel")).first { it.isFile }.readText()

    private val panel = "src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt"
    private val bridge = "src/main/java/com/example/voiceapp3/ui/UiBridge.kt"
    private val activity = "src/main/java/com/example/voiceapp3/MainActivity.kt"
    private val ttl = "src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt"
    private val lock = "src/main/java/com/example/voiceapp3/tools/LockSound.kt"
    private val jit = "src/main/java/com/example/voiceapp3/tools/JitNetCore.kt"
    private val fileLog = "src/main/java/com/example/voiceapp3/tools/AppFileLogger.kt"

    // ───────────────────── §4: холодное открытие показывает сохранённое ─────────────────────

    @Test
    fun panelAsksForStateWhenOpened() {
        val p = code(panel)
        assertTrue("панель просит снимок при показе", p.contains("onNeedState(\"panel_open\")"))
        assertTrue("и при возврате в неё", p.contains("onNeedState(\"panel_open_refresh\")"))
        assertTrue("значение по умолчанию больше не выдаётся за факт",
            p.contains("состояние ещё не прочитано"))
    }

    @Test
    fun bridgeCanServeStateOnRequestInBackground() {
        val b = code(bridge)
        assertTrue("снимок по запросу существует", b.contains("fun requestToolsState("))
        assertTrue("считается в фоне, а не на UI-потоке", b.contains("BgWorker.run(\"UiBridge.toolsState/"))
        assertTrue("снимки нумеруются", b.contains("toolsStateSeq"))
        assertTrue("событие страницы обрабатывается", b.contains("\"tools.refresh\""))
        val a = code(activity)
        assertTrue("старт: снимок до первого клика", a.contains("requestToolsState(\"startup\")"))
        assertTrue("возврат в приложение: снимок заново", a.contains("requestToolsState(\"resume\")"))
    }

    @Test
    fun staleSnapshotCannotRollbackToggle() {
        assertFalse("старый номер отклоняется", Toggles45.acceptsSeq(3, 4))
        assertTrue("новый принимается", Toggles45.acceptsSeq(5, 4))
    }

    // ───────────────────── §5: TTL не выключается автоматикой ─────────────────────

    @Test
    fun ttlNeverTurnsItselfOff() {
        val t = code(ttl)
        // Любая автоматическая запись false в намерение владельца = откат исправления.
        assertFalse("авто-выключение TTL запрещено",
            t.contains("putBoolean(context, PREF_TTL_65_ACTIVE, false)") ||
                t.contains("putBoolean(rcvCtx, PREF_TTL_65_ACTIVE, false)"))
        assertTrue("факт применения отделён от намерения", t.contains("PREF_TTL_65_APPLIED"))
        assertTrue("поколение намерения защищает от старого результата", t.contains("intentGen"))
        assertTrue("решение о состоянии — в чистых правилах", t.contains("Toggles45.appliedOutcome"))
        assertTrue("факт виден интерфейсу", code("src/main/java/com/example/voiceapp3/tools/ToolsBackend.kt")
            .contains("\"ttl65Applied\""))
    }

    // ───────────────────── §6: звук замка не выключается сам ─────────────────────

    @Test
    fun lockSoundFailureKeepsOwnerIntent() {
        val l = code(lock)
        val body = l.substring(l.indexOf("private fun recordBindFailure"),
            l.indexOf("private fun restoreSystemLockSoundBind"))
        assertFalse("при отказе bind намерение владельца не выключается",
            body.contains("PREF_LOCK_SOUND_ENABLED, false"))
        assertTrue("отказ виден как pending", body.contains("markApplied(ctx, false, reason)"))
        assertTrue("неудачный поиск на USB не снимает работающий звук при ON",
            l.contains("if (!isEnabled(ctx)) restoreSystemLockSoundBind(ctx)"))
        assertTrue("факт виден интерфейсу",
            code("src/main/java/com/example/voiceapp3/tools/ToolsBackend.kt")
                .contains("\"lockSoundApplied\""))
    }

    // ───────────────────── §7: JIT не блокируется навсегда ─────────────────────

    @Test
    fun undefinedOperationExpiresInsteadOfBlockingForever() {
        val j = code(jit)
        assertTrue("граница окна операции задана", j.contains("STALE_MARK_MS"))
        assertTrue("решение по правилу Toggles45", j.contains("Toggles45.isStale"))
        // iter49 §7: текст заменён на точный — истечение окна/старт zygote НЕ доказывают, что
        // команда не выполнится позже. Политика: выход из неопределённости называется явно.
        assertTrue("истечение не выдаётся за успех", j.contains("JIT_OP_ABANDONED"))
        assertTrue("возможность позднего исполнения не отрицается",
            j.contains("late_answer_possible=1"))
        assertTrue("устаревший снимок не применяется на старте", j.contains("stale_snapshot"))
        assertTrue("старый снимок не возвращает Wi-Fi", j.contains("wifi_restore=none"))
    }

    // ───────────────────── §10: повреждённый лог не замалчивается ─────────────────────

    @Test
    fun corruptedLogTailIsDetectedAndReported() {
        // §8.1/§8.2 iter46: лок/rotate/fsync живут в FileLogCore (проверяется реальными
        // файлами в Iter46Test), а AppFileLogger обязан СООБЩАТЬ о проблеме.
        val core = code("src/main/java/com/example/voiceapp3/tools/FileLogCore.kt")
        assertTrue("межпроцессный лок на стабильном файле", core.contains("tryLock()"))
        assertTrue("fsync после записи", core.contains("fd.sync()"))
        assertTrue("ошибка rotate не глотается", core.contains("rotate_rename_failed"))
        val f = code(fileLog)
        assertTrue("повреждение хвоста сообщается", f.contains("LOG_TAIL_NUL_DETECTED"))
        assertTrue("провал записи не маскируется", f.contains("LOG_WRITE_FAILED"))
        assertTrue("запись без лока не выдаётся за норму", f.contains("LOG_WRITE_UNLOCKED"))
        assertTrue("правило подсчёта — в Toggles45", core.contains("Toggles45.trailingNulBytes"))
    }
}
