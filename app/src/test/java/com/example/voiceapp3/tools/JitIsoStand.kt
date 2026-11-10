package com.example.voiceapp3.tools

import java.io.File

/**
 * Изолированный стенд: настоящий `/bin/sh` + подставные команды устройства.
 *
 * Нужен, чтобы исполнять РЕАЛЬНЫЙ текст цепочки (`JitRef.chainScript`) без ГУ. Каждая подставная
 * команда пишет свой вызов в `calls.log`, поэтому проверяется фактический порядок, а не совпадение
 * строк в исходнике. `grep` подменяет только путь `/proc/mounts` — шаблон остаётся из референса.
 */
class JitIsoStand(rules: Int = 0) {

    val dir: File = File.createTempFile("jit-iso", "").let { it.delete(); it.mkdirs(); it }
    val callsFile: File = File(dir, "calls.log")
    private val rulesLeft: File = File(dir, "rules_left")
    private val mountsFile: File = File(dir, "mounts.fake")

    private val sh: String = listOf("/bin/sh", "/usr/bin/sh", "sh")
        .firstOrNull { File(it).canExecute() }
        ?: throw IllegalStateException("нет /bin/sh для прогона скрипта")

    init {
        callsFile.writeText("")
        rulesLeft.writeText(rules.toString())
        mountsFile.writeText(
            "rootfs / rootfs ro 0 0\n" +
                "/dev/block/sda1 /data/app/com.foo/base.apk ext4 ro 0 0\n" +
                "/dev/block/sda1 /data/app/com.bar/base.apk ext4 ro 0 0\n" +
                "/dev/block/sda1 /data/data ext4 rw 0 0\n"
        )
        put("svc", "echo \"svc \$*\" >> \"\$CALLS\"")
        put("sleep", "echo \"sleep \$*\" >> \"\$CALLS\"")          // без реальной паузы
        put("umount", "echo \"umount \$*\" >> \"\$CALLS\"")
        put("getprop", "echo \"getprop \$*\" >> \"\$CALLS\"; echo \"\$FAKE_GETPROP\"")
        put("setprop", "echo \"setprop \$*\" >> \"\$CALLS\"")
        put("grep", """
            case "${'$'}1" in
              -*) exec /bin/grep "${'$'}@" ;;
            esac
            pat="${'$'}1"; shift
            for f in "${'$'}@"; do
              case "${'$'}f" in
                /proc/mounts) f="${'$'}FAKE_MOUNTS" ;;
              esac
              /bin/grep -- "${'$'}pat" "${'$'}f"
            done
        """.trimIndent())
        put("ip", """
            echo "ip ${'$'}*" >> "${'$'}CALLS"
            if [ "${'$'}1" = "rule" ]; then
              n=${'$'}(cat "${'$'}RULES_LEFT")
              if [ "${'$'}n" -gt 0 ]; then
                echo "${'$'}((n - 1))" > "${'$'}RULES_LEFT"
                i=0
                while [ "${'$'}i" -lt "${'$'}n" ]; do echo "1000: from all oif wlan0 lookup wlan0"; i=${'$'}((i + 1)); done
              fi
            fi
        """.trimIndent())
    }

    private fun put(name: String, body: String) {
        val f = File(dir, name)
        f.writeText("#!/bin/sh\n$body\n")
        f.setExecutable(true)
    }

    fun calls(): List<String> = callsFile.readLines().map { it.trim() }.filter { it.isNotBlank() }
    fun callsText(): String = callsFile.readText()
    fun resetCalls() = callsFile.writeText("")

    /** Исполнить скрипт подставным окружением. */
    fun exec(script: String, getprop: String = "true"): Pair<Int, String> {
        val f = File(dir, "chain.sh")
        f.writeText(script)
        val pb = ProcessBuilder(sh, f.absolutePath).redirectErrorStream(true)
        pb.environment()["PATH"] = "${dir.absolutePath}:${System.getenv("PATH") ?: "/bin:/usr/bin"}"
        pb.environment()["CALLS"] = callsFile.absolutePath
        pb.environment()["RULES_LEFT"] = rulesLeft.absolutePath
        pb.environment()["FAKE_MOUNTS"] = mountsFile.absolutePath
        pb.environment()["FAKE_GETPROP"] = getprop
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return Pair(p.exitValue(), out)
    }

    /** [JitShell] поверх стенда: то, что на устройстве делает `AdbClient`. */
    inner class Shell(private val getprop: String = "true", private val cache: Boolean? = true) : JitShell {
        val executed = mutableListOf<String>()
        override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
            executed += command
            return this@JitIsoStand.exec(command, getprop)
        }
        override fun cacheVisible(): Boolean? = cache
    }
}
