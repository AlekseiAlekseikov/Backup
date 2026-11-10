package com.example.voiceapp3.tools

import com.example.voiceapp3.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33p-fix1 — приёмка сборки: `BUILD: versionCode=62 versionName=1.0.62 commit=<X> ci_run=…`.
 *
 * Тест проверяет то, что печатается в лог при старте: версию проекта и источник SHA.
 * В CI `GITHUB_SHA`/`GITHUB_RUN_ID` подставляются Actions, локально — маркер
 * `app/buildinfo.properties` либо честное `local`. `git rev-parse` не используется.
 */
class BuildMarkerTest {

    @Test
    fun versionIs62() {
        assertEquals("versionCode", 62, BuildConfig.VERSION_CODE)
        assertEquals("versionName", "1.0.62", BuildConfig.VERSION_NAME)
    }

    @Test
    fun commitSourceIsHonest() {
        val c = BuildConfig.GIT_COMMIT.trim()
        assertTrue("commit пустой", c.isNotEmpty())
        assertTrue(
            "commit должен быть 7..40 hex или 'local', а не чужой/старый: '$c'",
            c == "local" || c.matches(Regex("[0-9a-f]{7,40}"))
        )
        assertTrue("ci_run пустой", BuildConfig.CI_RUN.trim().isNotEmpty())
    }

    /** Маркер в дереве обязан быть валидным: placeholder допускается только в рабочей копии агента. */
    @Test
    fun markerFileParsable() {
        val f = java.io.File("buildinfo.properties")
        if (!f.isFile) return
        val p = java.util.Properties()
        f.inputStream().use { p.load(it) }
        val sha = p.getProperty("SOURCE_SHA")?.trim().orEmpty()
        assertTrue("SOURCE_SHA пуст в маркере", sha.isNotEmpty())
    }
}
