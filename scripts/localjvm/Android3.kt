package android.os
class Handler(l: Looper?) { constructor(l: Looper?, c: Handler.Callback?) : this(l)
    fun post(r: Runnable): Boolean = true
    fun postDelayed(r: Runnable, d: Long): Boolean = true
    fun removeCallbacks(r: Runnable) {}
    interface Callback { fun handleMessage(m: Message): Boolean } }
class Message
class Looper { companion object { fun myLooper(): Looper? = null; fun getMainLooper(): Looper = Looper(); fun prepare() {}; fun loop() {} } }
class HandlerThread(n: String) : Thread(n) { fun getLooper(): Looper = Looper() }
object SystemClock { fun uptimeMillis(): Long = 0L; fun elapsedRealtime(): Long = 0L; fun sleep(ms: Long) {} }
object Process { fun myPid(): Int = 0; fun myUid(): Int = 0 }
object BuildVersion { const val SDK_INT = 28 }
object Build { const val MANUFACTURER = ""; const val MODEL = ""; const val BRAND = ""; const val BOARD = ""; val VERSION: Any get() = BuildVersion }
object Environment { fun getExternalStorageDirectory(): java.io.File = java.io.File("/tmp") }
