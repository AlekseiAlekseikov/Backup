package android.util
object Log {
    fun i(t: String, m: String): Int = 0
    fun w(t: String, m: String): Int = 0
    fun w(t: String, m: String, e: Throwable): Int = 0
    fun e(t: String, m: String): Int = 0
    fun e(t: String, m: String, e: Throwable): Int = 0
    fun d(t: String, m: String): Int = 0
    fun d(t: String, m: String, e: Throwable): Int = 0
    fun v(t: String, m: String): Int = 0
    fun v(t: String, m: String, e: Throwable): Int = 0
}
object Base64 {
    const val NO_WRAP = 2
    fun encodeToString(b: ByteArray, f: Int): String = ""
    fun decode(s: String, f: Int): ByteArray = ByteArray(0)
}
