package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33l §3.1/§3.2 — рут лечится ПЕРЕПОДКЛЮЧЕНИЕМ, а не `ctl.restart adbd`.
 *
 * Лог 07.11: `ro.secure=0`, каждый свежий handshake даёт `channel_up → ROOT` мгновенно;
 * после деградации транспорта клиент уходил в `NO_ROOT_SH` и оставался там навсегда,
 * а кнопка «Восстановить root-канал» дёргала `ctl.restart adbd` (без root не выполняется).
 *
 * Тест держит: reconnect есть и состоит из тех же шагов, что старт (ADB_DEST → CNXN → проба `id`),
 * `ctl.restart adbd` — только крайняя мера ВНУТРИ reconnect, и есть строка `CHANNEL_LOST`.
 */
class ReconnectRecoveryTest {

    private fun adb(): String = listOf(
        File("src/main/java/com/example/voiceapp3/tools/AdbClient.kt"),
        File("app/src/main/java/com/example/voiceapp3/tools/AdbClient.kt")
    ).firstOrNull { it.isFile }!!.readText()

    @Test
    fun reconnectExistsAndRepeatsBootCycle() {
        val s = adb()
        assertTrue("нет функции reconnectRoot", s.contains("fun reconnectRoot(reason: String, maxTries: Int = 3): Boolean"))
        assertTrue("reconnect должен повторить цикл старта: localTargets", s.contains("val targets = localTargets()"))
        assertTrue("reconnect должен повторить цикл старта: discoverDestination", s.contains("discoverDestination(targets, attempts = 1)"))
        assertTrue("reconnect должен повторить цикл старта: fullProbe", s.contains("val pr = runCatching { fullProbe() }.getOrNull()"))
        assertTrue("строка отчёта мандата", s.contains("ADB_RECOVERY action=reconnect reason="))
        assertTrue("исход в строке", s.contains("root_after=root") && s.contains("root_after=none"))
    }

    @Test
    fun adbdRestartOnlyAsLastResortInsideReconnect() {
        val s = adb()
        val idxReconnect = s.indexOf("fun reconnectRoot(reason: String, maxTries: Int = 3): Boolean")
        assertTrue(idxReconnect > 0)
        val body = s.substring(idxReconnect, minOf(s.length, idxReconnect + 6000))
        val idx = body.indexOf("setprop ctl.restart adbd")
        assertTrue("ctl.restart adbd должен быть только крайней мерой в reconnect", idx > 0)
        assertTrue("перед крайней мерой — строка лога",
            body.substring(0, idx).contains("action=restart_adbd_after_reconnect"))
        // до крайней меры должен быть честный итог reconnect
        assertTrue("перед крайней мерой печатается итог reconnect",
            body.substring(0, idx).contains("root_after=none"))
    }

    @Test
    fun channelLossIsInstrumented() {
        val s = adb()
        assertTrue("нет строки CHANNEL_LOST", s.contains("CHANNEL_LOST last_cmd="))
        assertTrue("обрыв на сокете логирует команду", s.contains("channelLost(\"io:"))
        assertTrue("таймаут логирует команду", s.contains("channelLost(\"timeout:"))
        assertTrue("root-команда без канала инициирует reconnect",
            s.contains("scheduleReconnectOnce(\"root_cmd:"))
    }

    @Test
    fun buttonUsesReconnect() {
        val perf = listOf(
            File("src/main/java/com/example/voiceapp3/tools/AppPerformance.kt"),
            File("app/src/main/java/com/example/voiceapp3/tools/AppPerformance.kt")
        ).first { it.isFile }.readText()
        assertTrue("кнопка «Восстановить root-канал» должна звать reconnectRoot",
            perf.contains("AdbClient.reconnectRoot(\"owner_click:"))
    }

    @Test
    fun scriptUploadIsSerializedAndRetried() {
        val s = adb()
        assertTrue("заливка скриптов должна быть сериализована", s.contains("synchronized(scriptUploadLock)"))
        assertTrue("должен быть повтор при несовпадении размера", s.contains("SCRIPT_UPLOAD_TRIES"))
        assertTrue("честный отказ после всех попыток", s.contains("SCRIPT_UPLOAD GIVEUP"))
    }
}
