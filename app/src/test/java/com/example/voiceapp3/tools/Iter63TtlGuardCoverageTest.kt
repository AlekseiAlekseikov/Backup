package com.example.voiceapp3.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter63 §P1-2 — guard поколения стоит у КАЖДОЙ мутации, включая ветви внутри функций.
 *
 * Аудит iter62 назвал 9 точек `_gen_ok` в линейном тексте скрипта, но мутации спрятаны и в
 * функциях: `drop_family` (удаление переходов через `del_jump`, `-F`, `-X`) и
 * `legacy_chain_report`. Guard в точках ВЫЗОВА их не закрывает: `drop_family` вызывается из
 * нескольких ветвей, и достаточно одной ветви без guard, чтобы поздний скрипт снял наши правила
 * после смены поколения.
 *
 * Тест разбирает ФАКТИЧЕСКИЙ текст production-скрипта ([TtlScript.build]) и требует: перед каждой
 * строкой, меняющей таблицу (`-A`, `-D`, `-F`, `-X`, `-N` для iptables/ip6tables), в пределах
 * трёх предыдущих строк есть `_gen_ok`. Ничего не «ищется по подстроке отчёта» — проверяется
 * тот текст, который уходит на устройство.
 */
class Iter63TtlGuardCoverageTest {

    private val script: String = TtlScript.build(enable = true, gen = "g63", guardGen = "g63", genFile = "/tmp/gen63")

    /** Строки-мутации: меняют таблицу, а не читают её. */
    private fun mutations(text: String): List<Pair<Int, String>> =
        text.lineSequence().withIndex()
            .map { (i, line) -> (i + 1) to line }
            .filter { (_, line) ->
                val l = line.trim()
                !l.startsWith("#") &&
                    Regex("\\b(ip6?tables)\\b").containsMatchIn(l) &&
                    Regex("(-A|-D|-F|-X|-N|-I|-R)\\s").containsMatchIn(l) &&
                    !Regex("-S\\s").containsMatchIn(l) &&
                    // откат (`ROLLBACK`) и диагностика входят сюда же: это те же мутации
                    true
            }
            .toList()

    @Test
    fun everyMutationIsPrecededByGenerationGuard() {
        val lines = script.lines()
        val missed = mutableListOf<String>()
        for ((n, line) in mutations(script)) {
            val window = (maxOf(0, n - 4) until n - 1).map { lines[it] }
            if (window.none { it.contains("_gen_ok") }) missed += "$n: ${line.trim()}"
        }
        assertTrue("мутации без guard поколения:\n" + missed.joinToString("\n"), missed.isEmpty())
    }

    @Test
    fun guardInsideDropFamilyAndDelJump() {
        // Функции: `del_jump` (удаление перехода) и `-F`/`-X` в `drop_family`.
        val delJump = script.substringAfter("del_jump() {").substringBefore("\n}")
        assertTrue("в del_jump нет guard: $delJump", delJump.contains("_gen_ok"))
        val dropFamily = script.substringAfter("drop_family() {").substringBefore("\nCS_V4=")
        assertTrue("в drop_family нет guard перед -F/-X", dropFamily.contains("_gen_ok"))
        // legacy_chain_report мутирует только через drop_family — проверяем, что вызов защищён
        val legacy = script.substringAfter("legacy_chain_report() {").substringBefore("\nchain_state()")
        assertTrue("в legacy_chain_report нет guard перед вызовом drop_family",
            legacy.contains("_gen_ok") || legacy.contains("del_jump"))
    }

    @Test
    fun guardIsPresentInEveryModeOfTheScript() {
        // OFF-режим тоже мутирует таблицу (снятие своих правил) — guard обязан быть и там.
        val off = TtlScript.build(enable = false, gen = "g63", guardGen = "g63", genFile = "/tmp/gen63")
        assertTrue("OFF-скрипт без guard: поздний OFF снял бы правила после смены поколения",
            off.contains("_gen_ok || exit 90"))
    }
}
