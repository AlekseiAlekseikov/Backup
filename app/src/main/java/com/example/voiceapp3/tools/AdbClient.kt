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

/**
 * Локальный ADB клиент для связи с adbd на 127.0.0.1:5555.
 * Точный порт из kotlinx.coroutines.scheduling.h (CentralEXAuto v3.3.9).
 */
object AdbClient {
    private const val TAG = "AdbClient"
    private const val ADB_PORT = 5555
    private const val DEFAULT_TIMEOUT_MS = 15000

    private class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private fun readMessage(dis: DataInputStream): AdbMessage {
        val header = ByteArray(24)
        dis.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.getInt()
        val arg0 = buf.getInt()
        val arg1 = buf.getInt()
        val dataLen = buf.getInt()
        val magic = buf.getInt()
        if (cmd.inv() != buf.getInt()) {
            throw IOException("Неверный заголовок ADB пакета")
        }
        val payload = ByteArray(dataLen)
        if (dataLen > 0) {
            dis.readFully(payload)
        }
        var sum = 0
        for (b in payload) {
            sum += b.toInt() and 0xFF
        }
        if (sum != magic) {
            throw IOException("Неверная контрольная сумма ADB пакета")
        }
        return AdbMessage(cmd, arg0, arg1, payload)
    }

    private fun writeMessage(os: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        var sum = 0
        for (b in payload) {
            sum += b.toInt() and 0xFF
        }
        buf.putInt(sum)
        buf.putInt(cmd.inv())
        os.write(buf.array())
        os.write(payload)
        os.flush()
    }

    fun execute(command: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Pair<Int, String> {
        // 1) Попытка через локальный сокет ADB (root на порту 5555)
        try {
            val res = executeAdbSocket(command, timeoutMs)
            return Pair(0, res)
        } catch (e: Exception) {
            Log.w(TAG, "ADB сокет недоступен ($e), откат на Runtime.exec(sh)...")
        }

        // 2) Фолбэк на sh/su
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            Pair(code, (out + "\n" + err).trim())
        } catch (e: Throwable) {
            Pair(1, e.message ?: "exec failure")
        }
    }

    private fun executeAdbSocket(command: String, timeoutMs: Int): String {
        val payload = ("shell:$command\u0000").toByteArray(StandardCharsets.UTF_8)
        if (payload.size > 4096) {
            throw IOException("Команда shell превышает лимит ADB 4096 байт")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", ADB_PORT), 3000)
            socket.soTimeout = timeoutMs
            val dis = DataInputStream(socket.getInputStream())
            val os = socket.getOutputStream()

            val hostReq = "host::central-geely\u0000".toByteArray(StandardCharsets.UTF_8)
            writeMessage(os, 0x4e595343 /* CNXN */, 0x01000000 /* VERSION */, 4096, hostReq)

            var resp: AdbMessage
            do {
                resp = readMessage(dis)
                if (resp.command == 0x48545541 /* AUTH */) {
                    throw IOException("adbd требует аутентификацию ключом")
                }
            } while (resp.command != 0x4e595343 /* CNXN */)

            // Открываем shell поток
            writeMessage(os, 0x4e45504f /* OPEN */, 1, 0, payload)
            val output = ByteArrayOutputStream()
            var localId = 0

            while (System.currentTimeMillis() < deadline) {
                val msg = readMessage(dis)
                when (msg.command) {
                    0x4b414b4f /* OKAY */ -> {
                        localId = msg.arg0
                    }
                    0x54495257 /* WRTE */ -> {
                        output.write(msg.payload)
                        writeMessage(os, 0x4b414b4f /* OKAY */, 1, msg.arg0, ByteArray(0))
                    }
                    0x534f4c43 /* CLSE */ -> {
                        writeMessage(os, 0x534f4c43 /* CLSE */, 1, localId, ByteArray(0))
                        return output.toString("UTF-8").trim()
                    }
                }
            }
            throw IOException("Таймаут выполнения команды в ADB")
        }
    }
}
