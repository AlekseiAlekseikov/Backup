package com.example.voiceapp3

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * SYSTEM_ALERT_WINDOW без adb и без экрана настроек.
 *
 * В AAOS Settings.ACTION_MANAGE_OVERLAY_PERMISSION не существует,
 * appop слетает при переустановке, обычный пользователь его не включит.
 *
 * Порядок:
 *  1. Уже выдано — ничего не делаем.
 *  2. Платформенная подпись / priv-app — выставляем appop сами через
 *     AppOpsManager.setMode. Пользователь ничего не нажимает.
 *  3. Иначе — false, сервис покажет OverlayActivity (разрешений не нужно).
 */
object OverlayPermission {

    private const val TAG = "OverlayPermission"

    /** AppOpsManager.OP_SYSTEM_ALERT_WINDOW, публичной константы нет. */
    private const val OP_SYSTEM_ALERT_WINDOW = 24

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * @return true — можно рисовать системный оверлей.
     *         false — используйте OverlayActivity.
     */
    fun ensure(context: Context): Boolean {
        if (canDraw(context)) return true
        if (grantViaAppOps(context)) {
            Log.i(TAG, "SYSTEM_ALERT_WINDOW выдан приложением самостоятельно")
            return true
        }
        Log.i(TAG, "Системный оверлей недоступен — будет OverlayActivity")
        return false
    }

    private fun grantViaAppOps(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val setMode = AppOpsManager::class.java.getMethod(
            "setMode",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType
        )
        setMode.invoke(
            appOps,
            OP_SYSTEM_ALERT_WINDOW,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_ALLOWED
        )
        canDraw(context)
    }.onFailure {
        Log.d(TAG, "AppOps.setMode недоступен: ${it.javaClass.simpleName}")
    }.getOrDefault(false)
}