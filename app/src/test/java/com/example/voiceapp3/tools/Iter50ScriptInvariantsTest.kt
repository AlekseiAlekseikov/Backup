package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter50 — инварианты ВСЕХ сгенерированных shell-скриптов (TTL и слой carlock).
 *
 * Зачем отдельный тест: скрипты собираются строками в Kotlin, и опечатка в интерполяции
 * (`rc=$` вместо `rc=$?`, путь в `"..."` вместо `"$STAGE_PATH"`) даёт синтаксически валидный,
 * но ЛОЖНЫЙ скрипт: `rc` навсегда равен `$`, поэтому успешный `mount` считается отказом.
 * Именно такой класс ошибок и был найден стендом в §4.1 — здесь он закрыт общим правилом.
 */
class Iter50ScriptInvariantsTest {

    private fun scripts(): Map<String, String> = linkedMapOf(
        "ttl-on.sh" to TtlScript.build(true),
        "ttl-off.sh" to TtlScript.build(false),
        "mount-bind.sh" to LockMountScripts.bindScript("/data/local/tmp/carlock_custom_stage.wav"),
        "mount-restore.sh" to LockMountScripts.restoreScript(
            listOf("/vendor/etc/carlock/a.wav" to "/data/local/tmp/carlock_custom_stage.wav")
        )
    )

    /** `VAR=$` в конце строки — почти всегда забытый `?`: значение становится литеральным `$`. */
    private fun danglingDollar(script: String): List<String> =
        script.lines().filter { Regex("=\\\\\\$\\s*$").containsMatchIn(it) }

    @Test
    fun everyGeneratedScriptParsesInPosixShell() {
        val st = Iter50Stand()
        for ((name, script) in scripts()) {
            val (rc, out) = st.syntaxCheck(script, name)
            assertEquals("sh -n для $name: $out", 0, rc)
            assertTrue("$name не должен содержать незакрытую подстановку: $out", out.isBlank())
        }
    }

    @Test
    fun noScriptAssignsLiteralDollarAsExitCode() {
        for ((name, script) in scripts()) {
            val bad = danglingDollar(script)
            assertEquals("в $name есть присваивание, значение которого — литеральный \$: $bad", emptyList<String>(), bad)
        }
    }

    @Test
    fun stagePathIsNotEmbeddedInsideDoubleQuotes() {
        // shQuote даёт '...', поэтому вставка внутрь "..." превратила бы кавычки в часть пути.
        val script = LockMountScripts.bindScript("/data/local/tmp/carlock_custom_stage.wav")
        assertTrue(
            "путь staging-файла используется через \$STAGE_PATH",
            script.contains("mount -o bind \"\$STAGE_PATH\"")
        )
        assertTrue("кавычки не попадают в путь", !script.contains("\"'/data/local/tmp"))
    }

    @Test
    fun noBashOnlySyntaxInShScripts() {
        // `#!/bin/sh` на прошивке — это dash/mksh: `${var//x/y}`, `[[`, `$'...'` там не работают.
        // Проверяются именно КОНСТРУКЦИИ, а не подстроки: `//` законно встречается в sed-шаблонах.
        val bashisms = listOf(
            Regex("\\$\\{[A-Za-z_][A-Za-z0-9_]*//"), // ${v//a/b}
            Regex("(^|[^\\w])\\[\\["), // [[
            Regex("(^|[^\\w!])\\$'"), // $'...'
            Regex("(^|;|\\s)local\\s") // local x=…
        )
        for ((name, script) in scripts()) {
            for (re in bashisms) {
                val hit = script.lines().firstOrNull { line ->
                    !line.trimStart().startsWith("#") && re.containsMatchIn(line)
                }
                assertTrue("в $name найдена bash-конструкция ${re.pattern}: $hit", hit == null)
            }
        }
    }
}
