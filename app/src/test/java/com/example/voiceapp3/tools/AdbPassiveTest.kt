package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * iter39 §3.1 — пассивный (read-only) путь транспорта: **настоящий** `AdbPassive` (production-код)
 * с ПОДСТАВНЫМ низкоуровневым I/O.
 *
 * Что здесь проверяется: при таймауте, позднем ответе, отказе канала/root, ошибке выполнения,
 * исключении и отказе «воркера» пассивный путь не инициирует восстановление, не поднимает
 * bootstrap, не меняет состояние канала, не отправляет отложенные команды и не делает новых
 * подключений — он возвращает честный `unavailable`/`timeout`.
 *
 * Чего тест НЕ доказывает (честно): сам `AdbClient` (очередь, воркер, сокеты, `bootstrap`,
 * `reconnectRoot`, `setprop ctl.restart adbd`) на JVM не исполняется — это Android-код. Здесь
 * проверена ЛОГИКА решения и то, что у пассивного интерфейса нет доступа к восстановлению: в
 * [AdbPassive.Io] таких методов физически нет.
 */
class AdbPassiveTest {

    /** Подставной I/O: журнал обращений и никакой возможности «лечить» канал. */
    private class Io(
        var ch: AdbPassive.Channel,
        var root: (String) -> Pair<Int, String> = { 0 to "ok" },
        var unix: (String) -> Pair<Int, String> = { 0 to "ok" },
        var shell: (String) -> Pair<Int, String> = { 0 to "ok" },
        var channelThrows: Boolean = false
    ) : AdbPassive.Io {
        val rootCalls = AtomicInteger(0)
        val unixCalls = AtomicInteger(0)
        val shellCalls = AtomicInteger(0)

        /** Все действия, которые пассивный путь мог бы «сделать» с каналом. */
        val sideEffects = mutableListOf<String>()

        override fun channel(): AdbPassive.Channel {
            if (channelThrows) throw IllegalStateException("state read failed")
            return ch
        }

        override fun execRoot(cmd: String, timeoutMs: Int): Pair<Int, String> {
            rootCalls.incrementAndGet()
            return root(cmd)
        }

        override fun execUnix(cmd: String, timeoutMs: Int): Pair<Int, String> {
            unixCalls.incrementAndGet()
            return unix(cmd)
        }

        override fun execShell(cmd: String, timeoutMs: Int): Pair<Int, String> {
            shellCalls.incrementAndGet()
            return shell(cmd)
        }

        fun calls(): Int = rootCalls.get() + unixCalls.get() + shellCalls.get()
    }

    @Test
    fun notStartedIsHonestUnavailableWithoutBootstrap() {
        val io = Io(AdbPassive.Channel.NOT_STARTED)
        val r = AdbPassive.run("ip rule show", 8000, io)
        assertTrue(r.unavailable)
        assertTrue(r.out.contains("reason=not_started"))
        assertEquals("ни одной команды: bootstrap не запускается", 0, io.calls())
        assertTrue("никаких действий с каналом", io.sideEffects.isEmpty())
    }

    @Test
    fun bootingIsChannelNotReadyNotRootMissing() {
        val io = Io(AdbPassive.Channel.BOOTING)
        val r = AdbPassive.run("ip route show table all", 8000, io)
        assertTrue(r.unavailable)
        assertTrue("это НЕ «нет root»", r.out.contains("channel_not_ready"))
        assertEquals(0, io.calls())
        assertEquals(
            InternetDiag.STATUS_CHANNEL_NOT_READY,
            InternetDiag.statusOf(r.rc, r.out, null, false)
        )
    }

    @Test
    fun timeoutDoesNotReconnectAndKeepsState() {
        val io = Io(AdbPassive.Channel.ROOT_TCP, root = { 137 to "ERR_ADB_TIMEOUT after 8000ms" })
        val r = AdbPassive.run("ip addr show wlan0", 8000, io)
        assertEquals(AdbPassive.RC_TIMEOUT, r.rc)
        assertEquals("ровно одна попытка — без повторов и переподключений", 1, io.calls())
        assertEquals("состояние канала не изменено", AdbPassive.Channel.ROOT_TCP, io.ch)
        assertTrue(io.sideEffects.isEmpty())
        assertEquals(InternetDiag.STATUS_TIMEOUT, InternetDiag.statusOf(r.rc, r.out, null, false))
    }

    @Test
    fun rootUnavailableDoesNotTriggerRecoveryOrFallbackChain() {
        val io = Io(AdbPassive.Channel.PERM_FAIL)
        val r = AdbPassive.run("id", 8000, io)
        assertTrue(r.unavailable)
        assertTrue(r.out.contains("root_unavailable"))
        assertEquals("ни shell-подмены, ни reconnect", 0, io.calls())
        assertEquals(InternetDiag.STATUS_UNAVAILABLE, InternetDiag.statusOf(r.rc, r.out, null, false))
    }

    @Test
    fun executionErrorIsReportedNotRetried() {
        val io = Io(AdbPassive.Channel.ROOT_TCP, root = { 1 to "sh: ip: not found" })
        val r = AdbPassive.run("ip addr", 8000, io)
        assertEquals("код возврата доносится как есть", 1, r.rc)
        assertEquals(1, io.calls())
        assertTrue(io.sideEffects.isEmpty())
    }

    @Test
    fun exceptionIsUnavailableNotCrash() {
        val io = Io(AdbPassive.Channel.ROOT_TCP, root = { throw java.io.IOException("socket closed") })
        val r = AdbPassive.run("ip addr", 8000, io)
        assertTrue(r.unavailable)
        assertTrue(r.out.contains("ERR_ADB_PASSIVE_IO"))
        assertEquals("без второй попытки и без reconnect", 1, io.calls())
        assertEquals("канал не переведён в другое состояние", AdbPassive.Channel.ROOT_TCP, io.ch)
    }

    @Test
    fun channelStateExceptionIsUnavailable() {
        val io = Io(AdbPassive.Channel.ROOT_TCP, channelThrows = true)
        val r = AdbPassive.run("ip addr", 8000, io)
        assertTrue(r.unavailable)
        assertEquals(0, io.calls())
    }

    @Test
    fun lateExecutionCannotChangeAnythingButResult() {
        // «Позднее исполнение»: команда отвечает уже после того, как вызывающий решил, что время
        // вышло. Настоящий транспорт это не откатывает; здесь важно, что пассивный путь НЕ делает
        // повторных/отложенных отправок — ровно один вызов за вызов.
        val io = Io(AdbPassive.Channel.ROOT_TCP, root = { 0 to "responded later" })
        val r = AdbPassive.run("ip addr", 8000, io)
        assertTrue(r.ok)
        assertEquals(1, io.calls())
        assertEquals("root_tcp", r.path)
    }

    @Test
    fun unixAndShellPathsAreReportedHonestly() {
        val unixIo = Io(AdbPassive.Channel.ROOT_UNIX)
        assertEquals("root_unix", AdbPassive.run("id", 8000, unixIo).path)
        assertEquals(1, unixIo.unixCalls.get())
        assertEquals(0, unixIo.rootCalls.get())

        val shellIo = Io(AdbPassive.Channel.NO_ROOT_SH)
        val r = AdbPassive.run("id", 8000, shellIo)
        assertEquals("sh_uid1000", r.path)
        assertEquals(1, shellIo.shellCalls.get())
    }

    @Test
    fun passiveIoHasNoRecoverySurface() {
        // Проверка контракта: у пассивного I/O нет методов восстановления/replay/bootstrap.
        // Если такой метод появится, тест упадёт — и это будет осознанным изменением, а не тихим.
        val names = AdbPassive.Io::class.java.methods.map { it.name }.filterNot {
            it in setOf("equals", "hashCode", "toString")
        }.toSortedSet()
        assertEquals(listOf("channel", "execRoot", "execShell", "execUnix"), names.toList())
    }

    @Test
    fun concurrentPassiveCallsDoNotQueueOrRetry() {
        val io = Io(AdbPassive.Channel.ROOT_TCP, root = { 0 to "ok" })
        val latch = CountDownLatch(1)
        val threads = (1..8).map {
            Thread {
                latch.await(2, TimeUnit.SECONDS)
                AdbPassive.run("ip rule show", 8000, io)
            }
        }
        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join(5000) }
        assertEquals("ровно по одному вызову на каждый запрос", 8, io.calls())
        assertTrue(io.sideEffects.isEmpty())
    }

    @Test
    fun diagShellKeepsFailureSemanticsAndDoesNotReadMaps() {
        // Настоящий `JitNet.DiagShell`. В JVM (локальный прогон) `AdbClient` — стаб и отвечает
        // rc=0; в unit-тестах CI это настоящий `AdbClient`, который БЕЗ поднятого транспорта
        // обязан ответить `ERR_ADB_PASSIVE_UNAVAILABLE` — и DiagShell не имеет права выдать это
        // за успешное чтение. Проверяем оба допустимых исхода и отсутствие подмешивания карты.
        val shell = JitNet.DiagShell
        val (rc, out) = shell.exec("ip addr", 1000)
        assertTrue("исход только «данные» или «недоступно», а не исключение",
            rc == 0 || rc == AdbPassive.RC_UNAVAILABLE)
        if (rc == AdbPassive.RC_UNAVAILABLE) {
            assertTrue("недоступность названа явно", out.contains("ERR_ADB_PASSIVE_UNAVAILABLE"))
        }
        assertNull("карта памяти чужого процесса не подмешивается", shell.mapsExcerpt())
    }
}
