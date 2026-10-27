package com.example.voiceapp3.tools

import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object AdbClient {
    private const val TAG = "AdbLocal"
    private const val DEFAULT_TIMEOUT_MS = 15000
    @Volatile private var workingPort: Int = 0
    @Volatile private var migrationAttempted: Boolean = false

    private data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun readMessage(dis: DataInputStream): AdbMessage {
        val hdr = ByteArray(24); dis.readFully(hdr)
        val buf = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.getInt(); val a0 = buf.getInt(); val a1 = buf.getInt()
        val len = buf.getInt(); val magic = buf.getInt()
        if (cmd.inv() != buf.getInt()) throw IOException("bad adb header")
        val payload = ByteArray(len)
        if (len > 0) dis.readFully(payload)
        var sum = 0; for (b in payload) sum += b.toInt() and 0xFF
        if (sum != magic) throw IOException("bad adb checksum")
        return AdbMessage(cmd, a0, a1, payload)
    }

    private fun writeMessage(os: OutputStream, cmd: Int, a0: Int, a1: Int, payload: ByteArray) {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd); buf.putInt(a0); buf.putInt(a1); buf.putInt(payload.size)
        var sum = 0; for (b in payload) sum += b.toInt() and 0xFF
        buf.putInt(sum); buf.putInt(cmd.inv())
        os.write(buf.array()); os.write(payload); os.flush()
    }

    fun execute(command: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Pair<Int, String> {
        val trimmed = command.trim()
        val deadline = System.currentTimeMillis() + timeoutMs
        val candidates = ArrayList<Int>(3)
        if (workingPort != 0) candidates.add(workingPort)
        for (p in intArrayOf(65010, 5555)) if (p !in candidates) candidates.add(p)
        for (port in candidates) {
            val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(1000)
            if (port == 65010 && !migrationAttempted) { ensurePortMigrated(); migrationAttempted = true }
            try {
                val out = socketExec(trimmed, remain.toInt(), port)
                if (workingPort != port) { workingPort = port; Log.i(TAG, "=== ROOT via loopback ADB port $port ===") }
                return 0 to out
            } catch (e: Exception) { Log.w(TAG, "port $port not ready yet: ${e.message}") }
        }
        Log.w(TAG, "не смогли поднять adb на 65010 — sh-fallback")
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", trimmed))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            var rc = -1
            val wd = System.currentTimeMillis() + timeoutMs.toLong().coerceAtMost(5000)
            while (System.currentTimeMillis() < wd) { try { rc = p.exitValue(); break } catch (_: IllegalThreadStateException) { Thread.sleep(80) } }
            if (rc == -1) p.destroyForcibly()
            Log.w(TAG, "[sh-fallback] rc=$rc cmd=${trimmed.take(80)}")
            rc to (out + "\n" + err).trim()
        } catch (e: Throwable) { 1 to (e.message ?: "exec failure") }
    }

    fun executeSh(command: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Pair<Int, String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor() to (out + "\n" + err).trim()
    } catch (e: Throwable) { 1 to (e.message ?: "exec failure") }

    private fun socketExec(command: String, timeoutMs: Int, port: Int): String {
        val payload = ("shell:$command\u0000").toByteArray(StandardCharsets.UTF_8)
        if (payload.size > 4096) throw IOException("command > 4096 bytes")
        val deadline = System.currentTimeMillis() + timeoutMs
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs.coerceAtMost(4000))
            s.soTimeout = timeoutMs
            val dis = DataInputStream(s.getInputStream())
            val os = s.getOutputStream()
            writeMessage(os, 0x4e595343, 0x01000000, 4096, "host::central-geely\u0000".toByteArray(StandardCharsets.UTF_8))
            var resp: AdbMessage
            do { resp = readMessage(dis); if (resp.command == 0x48545541) throw IOException("adbd auth required") } while (resp.command != 0x4e595343)
            writeMessage(os, 0x4e45504f, 1, 0, payload)
            val out = ByteArrayOutputStream(); var localId = 0
            while (System.currentTimeMillis() < deadline) {
                s.soTimeout = (deadline - System.currentTimeMillis()).coerceAtLeast(50).toInt()
                val m = readMessage(dis)
                when (m.command) {
                    0x4b414b4f -> localId = m.arg0
                    0x57525445 -> { out.write(m.payload); writeMessage(os, 0x4b414b4f, 1, m.arg1, ByteArray(0)) }
                    0x434c5345 -> { writeMessage(os, 0x434c5345, 1, localId, ByteArray(0)); return out.toString("UTF-8").trim() }
                }
            }
            throw IOException("adb timeout")
        }
    }

    private fun ensurePortMigrated() {
        Log.i(TAG, "ensurePortMigrated: ставлю adbd на 65010")
        val D = "$"
        val check = "cur=\"${D}(getprop service.adb.tcp.port 2>/dev/null)\"; per=\"${D}(getprop persist.adb.tcp.port 2>/dev/null)\"; if [ \"${D}cur\" = \"65010\" ] && [ \"${D}per\" = \"65010\" ]; then echo OK; else echo MIGRATE; fi"
        val (rc0, out0) = executeSh(check, 4000)
        if (rc0 == 0 && out0.contains("OK")) { Log.i(TAG, "adbd уже на 65010"); return }
        val refOk = sp("persist.adb.tcp.port", "65010") && sp("service.adb.tcp.port", "65010")
        if (!refOk) executeSh("setprop persist.adb.tcp.port 65010; setprop service.adb.tcp.port 65010", 4000)
        val rst = sp("ctl.restart", "adbd")
        if (!rst) executeSh("trap '' HUP; (sleep 2; setprop ctl.restart adbd) </dev/null >/dev/null 2>&1 & disown", 4000)
        Log.i(TAG, "props set reflect=$refOk, restart reflect=$rst")
        val t0 = SystemClock.elapsedRealtime()
        val dl = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < dl) {
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 65010), 800); it.close() }
                Log.i(TAG, "adbd поднялся на 65010 за ${SystemClock.elapsedRealtime()-t0} мс"); return
            } catch (_: IOException) { Thread.sleep(300) }
        }
        Log.w(TAG, "adbd не поднялся на 65010 за 8с")
    }

    private fun sp(k: String, v: String): Boolean = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("set", String::class.java, String::class.java).invoke(null, k, v); true
    } catch (t: Throwable) { Log.w(TAG, "SystemProperties.set($k) reflect failed: ${t.javaClass.simpleName}"); false }
}
