package android.content

import java.io.File

open class Context {
    open val applicationContext: Context get() = this
    open val filesDir: File get() = File("/tmp")
    open val cacheDir: File get() = File("/tmp")
    open val packageName: String get() = ""
    open val packageManager: android.content.pm.PackageManager get() = TODO()
    open val contentResolver: Any get() = Any()
    open val assets: android.content.res.AssetManager get() = TODO()
    open fun getSystemService(name: String): Any? = null
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = TODO()
    open fun createDeviceProtectedStorageContext(): Context = this
    open fun getExternalFilesDir(t: String?): File? = null
    open fun startActivity(i: Intent) {}
    open fun sendBroadcast(i: Intent) {}
    open fun registerReceiver(r: BroadcastReceiver?, f: IntentFilter): Intent? = null
    open fun unregisterReceiver(r: BroadcastReceiver) {}
    companion object { const val WIFI_SERVICE = "wifi"; const val MODE_PRIVATE = 0 }
}

open class Intent(s: String? = null)

open class BroadcastReceiver { open fun onReceive(c: Context, i: Intent) {} }

class IntentFilter(a: String? = null)

interface SharedPreferences {
    fun getBoolean(k: String, d: Boolean): Boolean
    fun getString(k: String, d: String?): String?
    fun getInt(k: String, d: Int): Int
    fun getLong(k: String, d: Long): Long
    fun getStringSet(k: String, d: MutableSet<String>?): MutableSet<String>?
    fun contains(k: String): Boolean
    fun getAll(): MutableMap<String, *>
    fun edit(): Editor
    interface Editor {
        fun putBoolean(k: String, v: Boolean): Editor
        fun putString(k: String, v: String?): Editor
        fun putInt(k: String, v: Int): Editor
        fun putLong(k: String, v: Long): Editor
        fun putStringSet(k: String, v: MutableSet<String>?): Editor
        fun remove(k: String): Editor
        fun commit(): Boolean
        fun apply()
    }
}
