package com.example.voiceapp3

import android.app.Application
import com.example.voiceapp3.tools.AppLog
import com.example.voiceapp3.tools.BgWorker
import com.example.voiceapp3.tools.ScreenBrightness
import com.example.voiceapp3.tools.WindowDoors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val tAppOnCreate = android.os.SystemClock.uptimeMillis()
        // Инициализируем логгер ПЕРВЫМ делом — до любых других модулей.
        AppLog.init(this)
        // Явная строка версии — чтобы владелец мгновенно видел в логе, какая
        // сборка запущена (без гаданий по размеру APK).
        AppLog.i("App", "BUILD: versionCode=${BuildConfig.VERSION_CODE} versionName=${BuildConfig.VERSION_NAME} commit=${BuildConfig.GIT_COMMIT}")
        AppLog.i("App", "=== Application.onCreate === (uptime=${android.os.SystemClock.elapsedRealtime()}ms)")
        // Итерация 12: ensureKeepAwake при старте процесса (чтобы при ребуте
        // или прибитии процесса системой фича «Не гасить экран» применилась
        // автоматически — не дожидаясь входа в UI).
        // iter16: ensureKeepAwake — на BgWorker, не на main.
        BgWorker.post("App.ensureKeepAwake") { ScreenBrightness.ensureKeepAwake(this) }
        // iter16: инициализируем наблюдателя дверей (для механизма "стекло при
        // открытии двери") ВНЕ зависимости от EXTRA_BOOT_RESTORE — раньше он
        // вызывался только из boot-ветки, поэтому при обычном запуске не работал.
        BgWorker.post("App.windowDoors") { WindowDoors.maybeInit(this) }
        // Фоновая диагностика при старте: uid/SELinux/su-бинарники/sysfs battery/temperature props
        Thread { rootDiagnostic() }.start()
        AppLog.i("App", "Application.onCreate done in ${android.os.SystemClock.uptimeMillis() - tAppOnCreate}ms")
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
                [ -e "${'$'}f" ] && echo "${'$'}f = ${'$'}(cat ${'$'}f 2>&1)"
            done
            echo "=== outside temp props ==="
            getprop 2>&1 | grep -iE "outside|exttemp|ambient_temp|out_temp|hvac|android.car.hvac.demo" | head -20
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
