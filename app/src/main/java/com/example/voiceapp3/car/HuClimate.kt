package com.example.voiceapp3.car

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Кнопки «быстрый обогрев / охлаждение» на ГУ Flyme Auto.
 *
 * VHAL климат меняет, иконки пресета не загораются: их рисует
 * com.flyme.auto.hvac / systemuiplugin, не CarPropertyManager.
 * Как спираль через виджеты Sapo — стучимся в приложение климата.
 *
 * API 28: sendBroadcast + startService. Панель не открываем.
 */
object HuClimate {
    private const val TAG = "HuClimate"

    private val PACKAGES = arrayOf(
        "com.flyme.auto.hvac",
        "com.flyme.auto.systemuiplugin",
        "com.flyme.auto.launcher",
    )

    private val ACTIONS = arrayOf(
        "com.flyme.auto.hvac.action.SWITCH_FUNCTION",
        "com.flyme.auto.hvac.action.SET_FUNCTION",
        "com.flyme.auto.hvac.action.FUNCTION",
        "com.flyme.auto.hvac.ACTION_ITEM_CLICK",
        "com.flyme.auto.systemuiplugin.action.HVAC_FUNCTION",
    )

    private val SERVICES = arrayOf(
        "com.flyme.auto.hvac.service.HvacService",
        "com.flyme.auto.hvac.HvacService",
        "com.flyme.auto.systemuiplugin.hvac.HvacService",
    )

    fun setQuick(context: Context, heat: Boolean, on: Boolean): Boolean {
        val value = if (on) 1 else 0
        val names = if (heat) {
            arrayOf("fast_heat", "quick_heat", "max_heat", "fastHeat", "quickHeat")
        } else {
            arrayOf("fast_cool", "quick_cool", "max_ac", "fastCool", "quickCool", "maxAc")
        }
        val id = if (heat) 10 else 11
        Log.i(TAG, "HU quick heat=$heat on=$on")
        var any = false
        val app = context.applicationContext
        for (pkg in PACKAGES) {
            for (action in ACTIONS) {
                if (broadcast(app, action, pkg, names, id, value, on)) any = true
            }
            if (startHvacService(app, pkg, names[0], id, value, on)) any = true
        }
        for (action in ACTIONS) {
            if (broadcast(app, action, null, names, id, value, on)) any = true
        }
        return any
    }

    private fun fill(intent: Intent, names: Array<String>, id: Int, value: Int, on: Boolean) {
        intent.putExtra("function", names[0])
        intent.putExtra("function_name", names[0])
        intent.putExtra("name", names[0])
        intent.putExtra("item", names[0])
        intent.putExtra("function_id", id)
        intent.putExtra("id", id)
        intent.putExtra("type", id)
        intent.putExtra("value", value)
        intent.putExtra("state", value)
        intent.putExtra("on", on)
        for (n in names) {
            intent.putExtra(n, on)
        }
    }

    private fun broadcast(
        context: Context,
        action: String,
        pkg: String?,
        names: Array<String>,
        id: Int,
        value: Int,
        on: Boolean,
    ): Boolean {
        val intent = Intent(action)
        if (pkg != null) intent.setPackage(pkg)
        fill(intent, names, id, value, on)
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        return try {
            context.sendBroadcast(intent)
            Log.i(TAG, "broadcast $action pkg=$pkg fn=${names[0]} v=$value")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "broadcast $action pkg=$pkg: ${t.message}")
            false
        }
    }

    private fun startHvacService(
        context: Context,
        pkg: String,
        function: String,
        id: Int,
        value: Int,
        on: Boolean,
    ): Boolean {
        var any = false
        for (cls in SERVICES) {
            if (!cls.startsWith(pkg)) continue
            val intent = Intent()
            intent.setClassName(pkg, cls)
            fill(intent, arrayOf(function), id, value, on)
            try {
                context.startService(intent)
                Log.i(TAG, "startService $pkg/$cls fn=$function v=$value")
                any = true
            } catch (t: Throwable) {
                Log.w(TAG, "startService $cls: ${t.message}")
            }
        }
        return any
    }
}
