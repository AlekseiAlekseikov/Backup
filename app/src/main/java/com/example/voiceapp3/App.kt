package com.example.voiceapp3

import android.app.Application
import com.example.voiceapp3.tools.AdbClient
import com.example.voiceapp3.tools.AppLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("CENTRAL_Init", "=== Application.onCreate (build 2026-10-28 fix/log-driven-fixes) ===")
        // Запускаем AdbClient bootstrap (порт 65010 / поиск root-канала) в фоне немедленно —
        // это не блокирует UI, и когда канал будет готов, команды из очереди выполнятся.
        AdbClient.start()
        // Лёгкая диагностика на фоне (только id/getenforce/adb props — не более 300мс через шелл).
        Thread({ quickDiagnostic() }, "App-diag").start()
    }

    private fun quickDiagnostic() {
        val script = """
            echo "uid=$(id)"
            echo "selinux=$(getenforce 2>&1 || echo unknown)"
            echo "adb_tcp=$(getprop service.adb.tcp.port)/$(getprop persist.adb.tcp.port)"
            echo "su_bin=$(which su 2>/dev/null || echo missing)"
        """.trimIndent()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh","-c",script))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            AppLog.i("CENTRAL_Init", "app.pre-diag (before adb root):\n$out")
        } catch (t: Throwable) {
            AppLog.w("CENTRAL_Init", "app.pre-diag failed: ${t.message}")
        }
    }
}

