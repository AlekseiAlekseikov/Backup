package com.example.voiceapp3

import android.app.Application
import com.example.voiceapp3.tools.AppLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Инициализируем логгер ПЕРВЫМ делом — до любых других модулей, чтобы
        // каждый вызов i/d/w/e попадал в /sdcard/Android/data/com.example.voiceapp3/files/voiceapp3.log
        AppLog.init(this)
        AppLog.i("App", "=== Application.onCreate (build 2026-10-25) ===")
        // Фоновая диагностика при старте: uid/SELinux/su-бинарники/sysfs battery/temperature props
        Thread { rootDiagnostic() }.start()
    }

    private fun rootDiagnostic() {
        val script = """
            echo "=== uid/context ==="
            id
            echo "=== SELinux ==="
            getenforce 2>&1 || echo "no getenforce"
            echo "=== su binaries ==="
            for p in /sbin/su /system/bin/su /system/xbin/su /su /magisk/.core/bin/su /debug_ramdisk/su /product/bin/su /vendor/bin/su; do
                if [ -e "${'$'}p" ]; then ls -laZ "${'$'}p" 2>&1; else echo "${'$'}p: MISSING"; fi
            done
            echo "=== test su variants (uid=0?) ==="
            runTest() { echo "--- ${'$'}1 ---"; eval "${'$'}1 id" 2>&1 | head -2 || echo "(failed)"; }
            runTest "/sbin/su 0 sh -c"
            runTest "/system/xbin/su 0 sh -c"
            runTest "/system/bin/su 0 sh -c"
            runTest "/system/bin/su -c"
            runTest "/system/xbin/su -c"
            runTest "su -c"
            echo "=== sysfs battery ==="
            for f in /sys/class/power_supply/*/capacity; do
                [ -e "${'$'}f" ] && echo "${'$'}f = $(cat ${'$'}f 2>&1)"
            done
            echo "=== outside temp props ==="
            getprop 2>&1 | grep -iE "outside|exttemp|ambient_temp|out_temp|hvac" | head -20
            echo "=== ttl /proc ==="
            cat /proc/sys/net/ipv4/ip_default_ttl 2>&1
        """.trimIndent()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", script))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            AppLog.i("App", "startup diagnostic (plain sh):\n$out$err")
        } catch (t: Throwable) {
            AppLog.e("App", "startup diagnostic failed", t)
        }
    }
}
