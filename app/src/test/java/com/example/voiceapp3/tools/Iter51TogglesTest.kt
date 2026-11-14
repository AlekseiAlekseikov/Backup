package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter51 §5 — тумблеры панели: «звук открытия» ≠ bind замка.
 *
 * Уровень проверки: статический инвариант исходников (JVM). `ToolsBackend`/`LockSound` нельзя
 * собрать в локальном стенде (Android Context), поэтому проверяется фактический текст
 * production-файлов и уникальность ключей реестром [ToolsBackend.Toggle]-подобной таблицы.
 */
class Iter51TogglesTest {

    private val backend = File("app/src/main/java/com/example/voiceapp3/tools/ToolsBackend.kt").readText()
    private val lockSound = File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()

    private fun toggles(): List<Pair<String, String>> =
        Regex("""([A-Z_0-9]+)\("([\w]+)", ([A-Za-z0-9_.]+), (true|false), "([^"]+)"\)""")
            .findAll(backend)
            .map { it.groupValues[1] to it.groupValues[3] }
            .toList()

    @Test
    fun lockAndUnlockSoundUseDifferentPrefConstants() {
        val map = toggles().toMap()
        assertEquals("Звук блокировки замка", "LockSound.PREF_LOCK_SOUND_ENABLED",
            map["LOCK_SOUND"])
        assertEquals("Звук открытия дверей", "LockSound.PREF_UNLOCK_SOUND_ENABLED",
            map["UNLOCK_SOUND"])
        assertNotEquals("один pref на два тумблера — связанные переключатели",
            map["LOCK_SOUND"], map["UNLOCK_SOUND"])
        // Сами константы объявлены с разными строками ключей.
        val a = Regex("""PREF_LOCK_SOUND_ENABLED = "([^"]+)"""").find(lockSound)?.groupValues?.get(1)
        val b = Regex("""PREF_UNLOCK_SOUND_ENABLED = "([^"]+)"""").find(lockSound)?.groupValues?.get(1)
        assertNotEquals("разные ключи SharedPreferences", a, b)
    }

    @Test
    fun unlockSoundDoesNotBindOrMount() {
        val body = lockSound.substringAfter("fun setUnlockSoundEnabled(").substringBefore("\n    fun ")
        assertTrue("тело найдено", body.isNotEmpty())
        assertFalse("ни bind, ни mount, ни скриптов из «звука открытия»: $body",
            body.contains("lock_bind") || body.contains("executeScript") ||
                body.contains("bindScript") || body.contains("mount"))
        assertTrue("настройка сохраняется", body.contains("putBoolean"))
        assertTrue("и проверяется чтением", body.contains("getBoolean"))
    }

    @Test
    fun toggleKeysAreUnique() {
        val keys = Regex("""\(("[\w]+"), [A-Za-z0-9_.]+, (?:true|false), "([^"]+)"\)""")
            .findAll(backend).map { it.groupValues[1] }.toList()
        assertTrue("реестр тумблеров найден: $keys", keys.size >= 15)
        assertEquals("ключи UI не дублируются", keys.size, keys.toSet().size)
    }

    @Test
    fun unlockSoundIsNotClaimedAsLockBindInTheBackend() {
        // Ветка тумблера «звук открытия» не должна вызывать путь замка.
        val branch = backend.substringAfter("Toggle.UNLOCK_SOUND ->").substringBefore("Toggle.")
        assertTrue("ветка найдена: $branch", branch.isNotEmpty())
        assertFalse("ветка звука открытия трогает bind: $branch", branch.contains("lock_bind"))
    }
}
