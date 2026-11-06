package com.example.voiceapp3.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter33k fix1 §1.5 — в приложении НЕТ собственных механизмов применения слотов:
 *
 *  • нет кнопки «Применить слоты (SystemUI)» (`ToolsPanel`, `ToolsBackend`);
 *  • нет `kill $(pidof com.flyme.auto.systemuiplugin)` — референс процесс плагина не убивает;
 *  • перезапуск панели делает только recarga (`pidof com.android.systemui`) из клика тумблера
 *    и монтажный скрипт установщика (аналог референса).
 *
 * Тест читает исходники — так же, как grep-чек-лист мандата §3.
 */
class NoApplyButtonTest {

    private fun src(rel: String): String {
        // тесты запускаются из каталога модуля `app`, но на всякий случай проверяем два корня
        val candidates = listOf(File(rel), File("app/$rel"))
        val f = candidates.firstOrNull { it.isFile }
            ?: error("не найден исходник $rel (cwd=${File(".").absolutePath})")
        return f.readText()
    }

    @Test
    fun noApplyButtonInUi() {
        val panel = src("src/main/java/com/example/voiceapp3/ui/ToolsPanel.kt")
        val backend = src("src/main/java/com/example/voiceapp3/tools/ToolsBackend.kt")
        assertFalse("кнопка «Применить слоты» выдумана — её быть не должно", panel.contains("Применить слоты"))
        assertFalse(panel.contains("hvac_apply_sysui_btn"))
        assertFalse(backend.contains("hvac_apply_sysui_btn"))
        assertFalse(backend.contains("applySlotsSystemUi"))
    }

    @Test
    fun pluginProcessNeverKilled() {
        val root = File("src/main/java/com/example/voiceapp3/tools")
        assertTrue("нет каталога с исходниками: ${root.absolutePath}", root.isDirectory)
        val killers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val t = f.readText()
                // в дексе референса нет ни pidof пакета плагина, ни kill по нему
                t.contains("pidof com.flyme.auto.systemuiplugin") || t.contains("pluginRestartScript")
            }
            .map { it.name }
            .toList()
        assertTrue("kill процесса плагина запрещён (референс так не делает): $killers", killers.isEmpty())
    }

    @Test
    fun recargaIsTheOnlyPanelRestart() {
        val apply = src("src/main/java/com/example/voiceapp3/tools/HvacFlagsApply.kt")
        assertTrue("в recarga-скрипте должна быть панель", apply.contains("pidof com.android.systemui"))
        assertTrue("строка применения — bar_recarga", apply.contains("method=bar_recarga"))
    }
}
