package com.example.voiceapp3.tools

import java.io.File

/**
 * iter52 §4 — НАСТОЯЩИЙ `/bin/sh` как «устройство» для тестов доставки.
 *
 * Почему это важно: независимый аудит iter51 показал, что fake-shell, возвращающий `rc` для
 * ЦЕЛОЙ команды, не моделирует семантику `;` — а именно на ней ломался учёт `rm`
 * (`rm … ; echo RM_DONE` → rc от `echo`). Здесь команды исполняет настоящий `sh`, поэтому
 * проверяется фактическая семантика shell, а не наша фантазия о ней.
 *
 * Модель устройства:
 *  • `/data/local/tmp` подменяется на временный каталог (пути переписываются в команде);
 *  • «потеря ответа» моделируется ЧЕСТНО: команда реально исполняется, но вызывающему
 *    возвращается таймаут (`rc=137`, `ERR_ADB_TIMEOUT`), как у настоящего ADB;
 *  • «отказ канала» моделируется ДО исполнения (команда не выполняется вовсе).
 *
 * Уровень: локальный `sh` + локальная ФС. Это не `/system/bin/sh` ГУ и не её toybox — но это
 * настоящий shell и настоящая семантика `rm`, `cksum`, `set -C`, `wc`.
 */
internal class RealShellDevice(val root: File = createRoot()) {

    /** Префикс «устройства», который переписывается в [root]. */
    var deviceDir: String = "/data/local/tmp"

    /** Сколько команд уже исполнено. */
    var calls: Int = 0
        private set

    /** Номера команд (с 1), которые НЕ доходят до устройства (обрыв канала до исполнения). */
    val denyBefore: MutableSet<Int> = mutableSetOf()

    /** Номера команд, у которых ответ ТЕРЯЕТСЯ после фактического исполнения. */
    val loseResponseAfter: MutableSet<Int> = mutableSetOf()

    /** Номера команд, исполнение которых надо «оборвать» по таймауту (аналог late execution). */
    val timeoutWhileRunning: MutableSet<Int> = mutableSetOf()

    /** Все исполненные команды (для проверки argv/путей). */
    val executed: MutableList<String> = mutableListOf()

    companion object {
        fun createRoot(): File {
            val base = File(System.getProperty("java.io.tmpdir"), "iter52dev_" + System.nanoTime())
            File(base, "data/local/tmp").mkdirs()
            return base
        }
    }

    /** Путь на «устройстве» → локальный путь. */
    fun local(path: String): File = File(root, path.removePrefix("/")).let {
        // /data/local/tmp/<name> → root/data/local/tmp/<name>
        File(root, path.trimStart('/'))
    }

    fun file(name: String): File = File(root, "data/local/tmp/$name")

    private fun rewrite(cmd: String): String = cmd.replace(deviceDir, File(root, "data/local/tmp").absolutePath)

    /** Исполнитель команд с сигнатурой [ScriptDelivery] / `AdbClient.execute`. */
    fun exec(cmd: String, timeoutMs: Int, expectRoot: Boolean): Pair<Int, String> {
        calls++
        val n = calls
        if (n in denyBefore) return 137 to "ERR_ADB_TIMEOUT"
        executed += cmd
        val real = rewrite(cmd)
        val proc = ProcessBuilder("/bin/sh", "-c", real)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val rc = proc.waitFor()
        if (n in loseResponseAfter) return 137 to "ERR_ADB_TIMEOUT"
        if (n in timeoutWhileRunning) return 137 to "ERR_ADB_TIMEOUT"
        return rc to out
    }

    /** Прямой запуск (для проверки сгенерированных команд без [ScriptDelivery]). */
    fun sh(cmd: String): Pair<Int, String> = exec(cmd, 10_000, true)

    fun cleanup() {
        runCatching { root.deleteRecursively() }
    }
}
