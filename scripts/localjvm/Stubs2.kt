package com.example.voiceapp3.tools

/** Заглушки ТОЛЬКО для локального прогона тестов (реальные файлы зависят от android.net.LocalSocket и т.п.). */
object AppLog {
    fun i(tag: String, msg: String) {}
    fun w(tag: String, msg: String) {}
    fun e(tag: String, msg: String) {}
    fun d(tag: String, msg: String) {}
}

object AdbClient {
    fun execute(cmd: String, timeoutMs: Int, expectRoot: Boolean = false): Pair<Int, String> = 0 to ""
    fun executeScript(name: String, script: String, timeoutMs: Int): Pair<Int, String> = 0 to ""
    fun isRootAvailable(): Boolean = true
}
