package com.example.voiceapp3.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.car.Car
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Иконки статус-бара Geely — порт w0.kg.j из CentralEXAuto.
 *
 * Совместимость API (используется ToolsBackend/VoiceAssistantService/UiBridge):
 *  - PREF_BATTERY / PREF_EXT_TEMP / PREF_WIFI — prefs-ключи для тумблеров панели уведомлений
 *  - PREF_HVAC_BATT / PREF_HVAC_TEMP / PREF_HVAC_EXT_TEMP — prefs-ключи для тумблеров
 *    "цифровой индикации" климата в статус-баре (через persist.sys.hvacdigital.*)
 *  - syncAll(context) — безаргументная, читает prefs сама
 *  - startPeriodicSync(context) / stopPeriodicSync() / cancelAll(context)
 *  - setHvacDigital(context, pref, value) / getHvacDigital(context, pref)
 *  - bootRestoreHvacDigital(context)
 *
 * Два отдельных notification-id: 15020 (батарея) и 15021 (температура) с разными
 * каналами — поэтому иконки больше не сливаются в одну.
 */
object StatusIcons {
    private const val TAG = "GeelyIcons"
    const val PREF_BATTERY = "status_icon_battery"
    const val PREF_EXT_TEMP = "status_icon_temp_ext"
    const val PREF_WIFI = "status_icon_wifi"

    /** Цифровая индикация в статус-баре (плагин GeelyAutoSystemUIPlugin-digital.apk).
     *  Флаги — persist.sys.hvacdigital.{batt,temp,temp_ext} (0/1). */
    const val PREF_HVAC_BATT = "hvacdigital_batt"
    const val PREF_HVAC_TEMP = "hvacdigital_temp"
    const val PREF_HVAC_EXT_TEMP = "hvacdigital_temp_ext"
    private const val SYS_HVAC_BATT = "persist.sys.hvacdigital.batt"
    private const val SYS_HVAC_TEMP = "persist.sys.hvacdigital.temp"
    private const val SYS_HVAC_EXT = "persist.sys.hvacdigital.temp_ext"

    private const val CHANNEL_BATT = "central_ex_status_batt"
    private const val CHANNEL_TEMP = "central_ex_status_temp"
    private const val CHANNEL_WIFI = "central_ex_status_wifi"
    private const val NOTIF_BATT = 15020
    private const val NOTIF_TEMP = 15021
    private const val NOTIF_WIFI = 15024
    private const val RANK_BATT = 10
    private const val RANK_TEMP = 20
    private const val RANK_WIFI = 31

    private val AREAS = intArrayOf(0, 1, 2, 3, 4, 7, 0x10, 0x20, 0x30)
    private val RANGES = arrayOf(0x0..0x4FF, 0x11400000..0x117FFFFF, 0x15400000..0x167FFFFF)
    private val PRECISE_COMMON = intArrayOf(0x157, 0x11600005, 0x11600207, 0x11600208,
        0x16400017, 0x21600017, 0x16400001, 0x15500703, 0x11500703, 0x11600703,
        0x16400703, 0x21600703, 0x11600502, 0x11600204, 0x11400204)

    @Volatile private var cpm: android.car.hardware.property.CarPropertyManager? = null
    @Volatile private var carObj: Car? = null
    @Volatile private var h: Handler? = null
    @Volatile private var dumpLogged = false

    @Volatile private var socProp = 0; @Volatile private var socArea = 0
    @Volatile private var tmpProp = 0; @Volatile private var tmpArea = 0

    // ─── Prefs helper ───
    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("voiceapp3", Context.MODE_PRIVATE)

    // ─── Normalization (w0.y7.N Float; Int halves+80 w0.y7.P) ───
    private fun normFloat(raw: Float?, lo: Float, hi: Float): Float? {
        if (raw == null || raw.isNaN() || raw == 0f) return null
        var v = raw; if (v > 100f) v /= 10f
        return if (v in lo..hi) v else null
    }
    private fun normInt80(raw: Int?): Int? {
        if (raw == null || raw == 0) return null
        val t = (raw - 80) / 2
        return if (t in -50..70) t else null
    }

    // ─── VHAL scan ───
    private fun ensureCar(ctx: Context) {
        if (cpm != null) return
        val app = ctx.applicationContext
        try {
            carObj = Car.createCar(app, null, 0L) { c: Car, ready: Boolean ->
                if (ready) try {
                    cpm = c.getCarManager(Car.PROPERTY_SERVICE) as? android.car.hardware.property.CarPropertyManager
                    Log.i(TAG,"CarPropertyManager ready=${cpm != null}")
                    if (!dumpLogged && cpm != null) { dumpLogged = true; dumpKnownProps() }
                } catch (e: Exception) { Log.w(TAG,"getCarManager err",e) }
            }
            carObj?.connect()
        } catch (e: Exception) { Log.w(TAG,"Car.createCar: ${e.message}") }
    }

    private fun dumpKnownProps() {
        val m = cpm ?: return
        Log.i(TAG,"=== VHAL dump start (look for BATT/TEMP raw values here) ===")
        for (p in PRECISE_COMMON) for (a in AREAS) {
            try { val v = m.getFloatProperty(p,a); if (!v.isNaN()) Log.i(TAG,String.format("DUMP F 0x%08X@%d = %f",p,a,v)) } catch(_:Exception){}
            try { val v = m.getIntProperty(p,a); if (v != 0) Log.i(TAG,String.format("DUMP I 0x%08X@%d = %d",p,a,v)) } catch(_:Exception){}
        }
        Log.i(TAG,"=== VHAL dump end ===")
    }

    private fun readSocScan(): Int? {
        val m = cpm ?: return null
        if (socProp != 0) {
            for (a in AREAS) {
                try { normFloat(m.getFloatProperty(socProp,a),0f,101f)?.let { return Math.round(it).coerceIn(0,100)} } catch(_:Exception){}
                try { normFloat(m.getIntProperty(socProp,a).toFloat(),0f,101f)?.let { return Math.round(it).coerceIn(0,100)} } catch(_:Exception){}
            }
            socProp = 0
        }
        for (r in RANGES) for (p in r step 256) for (a in AREAS) {
            try { normFloat(m.getFloatProperty(p,a),0f,101f)?.let { socProp=p;socArea=a; Log.i(TAG,String.format("SOC HIT F 0x%08X@%d raw=%f -> %d%%",p,a,m.getFloatProperty(p,a),Math.round(it))); return Math.round(it).coerceIn(0,100)} } catch(_:Exception){}
            try { normFloat(m.getIntProperty(p,a).toFloat(),0f,101f)?.let { socProp=p;socArea=a; Log.i(TAG,String.format("SOC HIT I 0x%08X@%d raw=%d -> %d%%",p,a,m.getIntProperty(p,a),Math.round(it))); return Math.round(it).coerceIn(0,100)} } catch(_:Exception){}
        }
        for (p in PRECISE_COMMON) for (a in AREAS) {
            try { normFloat(m.getFloatProperty(p,a),0f,101f)?.let { socProp=p; Log.i(TAG,String.format("SOC p F 0x%08X@%d -> %d%%",p,a,Math.round(it))); return Math.round(it).coerceIn(0,100)} } catch(_:Exception){}
            try { normFloat(m.getIntProperty(p,a).toFloat(),0f,101f)?.let { socProp=p; Log.i(TAG,String.format("SOC p I 0x%08X@%d -> %d%%",p,a,Math.round(it))); return Math.round(it).coerceIn(0,100)} } catch(_:Exception){}
        }
        return null
    }

    private fun readTempScan(): Int? {
        val m = cpm ?: return null
        if (tmpProp != 0) {
            for (a in AREAS) {
                try { normFloat(m.getFloatProperty(tmpProp,a),-50f,70f)?.let { return Math.round(it).coerceIn(-50,70)} } catch(_:Exception){}
                try { normInt80(m.getIntProperty(tmpProp,a))?.let { return it } } catch(_:Exception){}
            }
            tmpProp = 0
        }
        // Float °C
        for (r in RANGES) for (p in r step 256) for (a in AREAS) {
            try { normFloat(m.getFloatProperty(p,a),-50f,70f)?.let { tmpProp=p;tmpArea=a; Log.i(TAG,String.format("TEMP HIT F 0x%08X@%d raw=%f -> %d°C",p,a,m.getFloatProperty(p,a),Math.round(it))); return Math.round(it).coerceIn(-50,70)} } catch(_:Exception){}
        }
        // Int (raw-80)/2
        for (r in RANGES) for (p in r step 256) for (a in AREAS) {
            try { normInt80(m.getIntProperty(p,a))?.let { tmpProp=p;tmpArea=a; Log.i(TAG,String.format("TEMP HIT I 0x%08X@%d raw=%d -> %d°C",p,a,m.getIntProperty(p,a),it)); return it } } catch(_:Exception){}
        }
        for (p in PRECISE_COMMON) for (a in AREAS) {
            try { normFloat(m.getFloatProperty(p,a),-50f,70f)?.let { tmpProp=p; Log.i(TAG,String.format("TEMP p F 0x%08X@%d -> %d°C",p,a,Math.round(it))); return Math.round(it).coerceIn(-50,70)} } catch(_:Exception){}
            try { normInt80(m.getIntProperty(p,a))?.let { tmpProp=p; Log.i(TAG,String.format("TEMP p I 0x%08X@%d -> %d°C",p,a,it)); return it } } catch(_:Exception){}
        }
        return null
    }

    // ─── Notification drawing (w0.kg.j) ───
    private fun makeTextIcon(ctx: Context, big: String, small: String, col: Int): Icon {
        val dp = fun(i: Int) = (i * ctx.resources.displayMetrics.density + 0.5f).toInt()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; typeface = Typeface.DEFAULT; textSize = dp(56).toFloat() }
        val bigW = p.measureText(big)
        var smallW = 0f; var smSz = 0f
        if (small.isNotEmpty()) { smSz = 0.55f * dp(56); p.textSize = smSz; smallW = p.measureText(small); p.textSize = dp(56).toFloat() }
        val fm = p.fontMetrics; val h = ((fm.descent-fm.ascent)/0.61f).toInt().coerceAtLeast(1)
        val pad = dp(5); val w = (pad*2 + bigW + smallW + dp(3)).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val y = ((h-(fm.descent-fm.ascent))/2f) - fm.ascent
        cv.drawText(big, pad.toFloat(), y, p)
        if (small.isNotEmpty()) { p.textSize = smSz; cv.drawText(small, pad+bigW+dp(2), y, p) }
        return Icon.createWithBitmap(bmp)
    }

    private fun postNotif(ctx: Context, id: Int, channelId: String, icon: Icon, title: String, text: String, rank: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, title, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val pi = PendingIntent.getActivity(ctx, id, Intent().apply { setClassName(ctx, "com.example.voiceapp3.MainActivity") }, 201326592)
        val n = Notification.Builder(ctx, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title).setContentText(text).setContentIntent(pi)
            .setOngoing(true).setShowWhen(false).build()
        n.extras.putBoolean("flag_status_icon_notification", true)
        n.extras.putInt("flag_status_icon_id", id)
        n.extras.putString("flag_status_icon_describe", "centralex_$id")
        n.extras.putParcelable("flag_status_icon_icon", icon)
        n.extras.putParcelable("flag_status_icon_pressed_icon", icon)
        n.extras.putBoolean("flag_status_icon_hide", false)
        n.extras.putInt("flag_status_icon_rank", rank)
        n.extras.putInt("flag_status_icon_space_x", 1)
        n.extras.putBoolean("flag_status_icon_is_pick_on", false)
        n.extras.putInt("flag_status_icon_specific_width", 0)
        try { nm.notify(id, n); Log.d(TAG,"post notif id=$id rank=$rank text=\"$text\"") }
        catch (t: Throwable) { Log.e(TAG,"notify $id err", t) }
    }

    private fun cancel(ctx: Context, id: Int) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id) } catch(_:Throwable){}
    }

    // ─── Public API (совместимый с существующими callers) ───
    @JvmStatic fun syncAll(ctx: Context) {
        val app = ctx.applicationContext; ensureCar(app)
        val showBatt = prefs(app).getBoolean(PREF_BATTERY, false)
        val showTemp = prefs(app).getBoolean(PREF_EXT_TEMP, false)
        val showWifi = prefs(app).getBoolean(PREF_WIFI, false)
        Log.d(TAG,"syncAll batt=$showBatt temp=$showTemp wifi=$showWifi cpm=${cpm!=null}")
        if (showBatt) {
            val soc = readSocScan()
            if (soc != null) postNotif(app, NOTIF_BATT, CHANNEL_BATT, makeTextIcon(app, "$soc", "%", Color.WHITE), "Батарея", "$soc%", RANK_BATT)
            else { cancel(app, NOTIF_BATT); Log.w(TAG,"SOC null") }
        } else cancel(app, NOTIF_BATT)
        if (showTemp) {
            val t = readTempScan()
            if (t != null) postNotif(app, NOTIF_TEMP, CHANNEL_TEMP, makeTextIcon(app, "$t", "°C", Color.WHITE), "Снаружи", "$t °C", RANK_TEMP)
            else { cancel(app, NOTIF_TEMP); Log.w(TAG,"TEMP null") }
        } else cancel(app, NOTIF_TEMP)
        if (showWifi) {
            try {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val state = when { !wm.isWifiEnabled -> "off"; wm.connectionInfo?.networkId ?: -1 != -1 -> "on"; else -> "disc" }
                postNotif(app, NOTIF_WIFI, CHANNEL_WIFI, makeTextIcon(app, "wifi", "", Color.WHITE), "Wi-Fi", state, RANK_WIFI)
            } catch (e: Exception) { Log.w(TAG,"wifi err: ${e.message}") }
        } else cancel(app, NOTIF_WIFI)
    }

    @JvmStatic fun cancelAll(ctx: Context) {
        cancel(ctx, NOTIF_BATT); cancel(ctx, NOTIF_TEMP); cancel(ctx, NOTIF_WIFI)
    }

    @JvmStatic fun startPeriodicSync(ctx: Context) {
        h?.removeCallbacksAndMessages(null)
        val hh = Handler(Looper.getMainLooper()); h = hh
        hh.post(object : Runnable {
            override fun run() {
                try { syncAll(ctx) } catch (t: Throwable) { Log.e(TAG,"tick err",t) }
                hh.postDelayed(this, 15000L)
            }
        })
        Log.i(TAG,"startPeriodicSync (15s)")
    }
    @JvmStatic fun stopPeriodicSync() { h?.removeCallbacksAndMessages(null); h=null; Log.i(TAG,"stopPeriodicSync") }

    // ─── HVAC digital (флаги persist.sys.hvacdigital.*) ───
    /**
     * Цифровая индикация климата в статус-баре — включает соответствующую
     * цифровую плашку плагина GeelyAutoSystemUIPlugin через setprop:
     *   persist.sys.hvacdigital.batt / .temp / .temp_ext
     *
     * Плагин монтируется AppPerformance.setVideo60Fps НЕ занимается — плагин
     * лежит в assets/GeelyAutoSystemUIPlugin-digital.apk и его bind-mount /
     * подмена APK делает отдельная функция (здесь только переключатель флагов).
     */
    fun sysPropFor(pref: String): String = when (pref) {
        PREF_HVAC_BATT -> SYS_HVAC_BATT
        PREF_HVAC_TEMP -> SYS_HVAC_TEMP
        PREF_HVAC_EXT_TEMP -> SYS_HVAC_EXT
        else -> ""
    }

    @JvmStatic fun setHvacDigital(ctx: Context, pref: String, value: Boolean): ToolsResult {
        val t0 = android.os.SystemClock.uptimeMillis()
        prefs(ctx).edit().putBoolean(pref, value).commit()
        val prop = sysPropFor(pref)
        val cmd = "setprop $prop ${if(value)1 else 0}; echo $prop=\$(getprop $prop)"
        val (exit, out) = AdbClient.execute(cmd, 5000)
        val applied = out.contains("$prop=1") || (value && exit == 0)
        val step = ToolsStep(
            step = "hvacdigital.$pref",
            command = cmd,
            startedAtMs = t0, finishedAtMs = android.os.SystemClock.uptimeMillis(),
            exitCode = exit, stdout = out, stderr = "",
            expected = "$prop=${if(value)1 else 0}",
            actual = out.lines().firstOrNull { it.startsWith(prop) } ?: out,
            status = if ((value && applied) || (!value && exit==0)) ToolsStep.Status.OK else ToolsStep.Status.PARTIAL
        )
        return ToolsResult(
            action = "hvacdigital",
            title = "Цифровая индикация климата",
            steps = listOf(step)
        )
    }

    @JvmStatic fun getHvacDigital(ctx: Context, pref: String): Boolean =
        prefs(ctx).getBoolean(pref, false)

    @JvmStatic fun bootRestoreHvacDigital(ctx: Context) {
        val p = prefs(ctx)
        val sb = StringBuilder()
        for ((k,sp) in listOf(PREF_HVAC_BATT to SYS_HVAC_BATT, PREF_HVAC_TEMP to SYS_HVAC_TEMP, PREF_HVAC_EXT_TEMP to SYS_HVAC_EXT)) {
            val v = if (p.getBoolean(k, false)) 1 else 0
            sb.append("setprop $sp $v; ")
        }
        sb.append("echo batt=\$(getprop $SYS_HVAC_BATT) temp=\$(getprop $SYS_HVAC_TEMP) ext=\$(getprop $SYS_HVAC_EXT)")
        val (_,out) = AdbClient.execute(sb.toString(), 5000)
        Log.i(TAG,"bootRestoreHvacDigital: $out")
    }
}
