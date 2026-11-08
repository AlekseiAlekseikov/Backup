package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * iter33n-fix1 §1 + §4 — бандл плагина ОБЯЗАН быть сборкой референса 1.4.1
 * («exttemp-empilhado», CentralEXAuto `e0bd36c9`).
 *
 * Почему это критично: слоты в панели молчали не из-за нашего кода, а из-за СТАРОГО бандла
 * из 1.0.58 — он не читает `persist.sys.hvacdigital.*`. Сборка 1.4.1 читает три ключа,
 * поэтому тест держит и контрольные суммы, и наличие этих строк в dex.
 *
 * Тест читает файл из репозитория, сети не требует.
 */
class PluginAssetTest {

    private fun apk(): File = listOf(
        File("src/main/res/raw/geely_auto_systemui_plugin_digital.apk"),
        File("app/src/main/res/raw/geely_auto_systemui_plugin_digital.apk")
    ).first { it.isFile }

    private fun digest(algo: String, f: File): String {
        val md = MessageDigest.getInstance(algo)
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // 1. Размер 1.4.1.
    @Test
    fun sizeIs141Build() {
        assertEquals("размер плагина 1.4.1", 18_284_258L, apk().length())
    }

    // 2. md5 из мандата §1.1.
    @Test
    fun md5IsReference() {
        assertEquals("5aab306b34a2ce06d527399ed536cb35", digest("MD5", apk()))
    }

    // 3. sha256 из мандата §1.1.
    @Test
    fun sha256IsReference() {
        assertEquals(
            "fabc06a38cb588bae64f4cfc71e4848d21809b01bca43f6f85363839cc353d21",
            digest("SHA-256", apk())
        )
    }

    // 4. §1.2: константы кода обязаны совпадать с активом (иначе mount-проверки врут).
    @Test
    fun constantsMatchAsset() {
        val f = apk()
        assertEquals("PLUGIN_APK_SIZE", f.length(), HvacDigitalPlugin.PLUGIN_APK_SIZE)
        assertEquals("PLUGIN_APK_MD5", digest("MD5", f), HvacDigitalPlugin.PLUGIN_APK_MD5)
        assertEquals("PLUGIN_APK_SHA256", digest("SHA-256", f), HvacDigitalPlugin.PLUGIN_APK_SHA256)
    }

    // 5. §1: в dex бандла есть все три строки, которые читает панель.
    // Поиск идёт ПО БАЙТАМ: dex-строки — обычные ASCII-последовательности, а строковое
    // представление 18 МБ dex'а съело бы память теста впустую.
    @Test
    fun dexReadsHvacDigitalProps() {
        val names = listOf(
            "persist.sys.hvacdigital.batt",
            "persist.sys.hvacdigital.exttemp",
            "persist.sys.hvacdigital.temp"
        )
        val blobs = mutableListOf<ByteArray>()
        ZipFile(apk()).use { zip ->
            val entries = zip.entries().toList().filter {
                it.name.startsWith("classes") && it.name.endsWith(".dex")
            }
            assertTrue("в APK нет classes*.dex", entries.isNotEmpty())
            for (e in entries) blobs.add(zip.getInputStream(e).use { it.readBytes() })
        }
        for (n in names) {
            val needle = n.toByteArray(Charsets.US_ASCII)
            assertTrue("в dex нет строки $n (бандл не 1.4.1)",
                blobs.any { hay -> indexOf(hay, needle) >= 0 })
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in 0..(hay.size - needle.size)) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
