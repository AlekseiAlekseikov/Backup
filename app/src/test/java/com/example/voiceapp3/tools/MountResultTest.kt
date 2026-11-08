package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33j P0.2 — вердикт маунта честный: `OK` только при совпадении inode И md5.
 *
 * Старый баг «OK при inode:false» закрыт: строка `HVAC_MOUNT_RESULT` разбирается целиком,
 * а противоречивая комбинация (`verdict=OK` при `md5_match=false`) превращается в FAILED.
 */
class MountResultTest {

    @Test
    fun okRequiresBothMatches() {
        val out = "noise\nHVAC_MOUNT_RESULT mount=1 inode_match=true md5_match=true verdict=OK\nHVAC_DIGITAL_OK"
        val r = MountResult.parse(out)!!
        assertTrue(r.ok)
        assertTrue(r.mount && r.inodeMatch && r.md5Match)
        assertEquals("HVAC_MOUNT_RESULT mount=1 inode_match=true md5_match=true verdict=OK", r.line())
    }

    @Test
    fun md5MismatchIsFailed() {
        val out = "HVAC_MOUNT_RESULT mount=1 inode_match=true md5_match=false verdict=FAILED"
        val r = MountResult.parse(out)!!
        assertFalse("md5 не совпал → маунт не рабочий", r.ok)
        assertTrue(r.inodeMatch)
        assertFalse(r.md5Match)
    }

    @Test
    fun inodeMismatchIsFailed() {
        val r = MountResult.parse("HVAC_MOUNT_RESULT mount=1 inode_match=false md5_match=true verdict=FAILED")!!
        assertFalse(r.ok)
    }

    @Test
    fun contradictoryOkIsDowngraded() {
        // Скрипт напечатал OK, но md5 не совпал — верим флагам, а не слову.
        val r = MountResult.parse("HVAC_MOUNT_RESULT mount=1 inode_match=true md5_match=false verdict=OK")!!
        assertEquals("FAILED", r.verdict)
        assertFalse(r.ok)
    }

    @Test
    fun noMountIsFailed() {
        val r = MountResult.parse("HVAC_MOUNT_RESULT mount=0 inode_match=true md5_match=true verdict=FAILED")!!
        assertFalse(r.ok)
        assertFalse(r.mount)
    }

    @Test
    fun missingLineIsNull() {
        assertNull(MountResult.parse("BIND_FAILED\nHVAC_DIGITAL_FALHOU"))
    }

    @Test
    fun detailSizeParsed() {
        val out = "HVAC_MOUNT_DETAIL src=/data/local/hvac-digital/sysui.apk dst=/system/app/x.apk " +
            "src_inode=11 tgt_inode=11 src_md5=aa tgt_md5=aa src_size=18284258 tgt_size=18284258"
        assertEquals(18_284_258L, MountResult.detailSize(out, "tgt_size"))
        assertEquals(18_284_258L, MountResult.detailSize(out, "src_size"))
        assertNull(MountResult.detailSize("HVAC_MOUNT_DETAIL dst=x", "tgt_size"))
    }

    @Test
    fun stagingConflictIsNotSilent() {
        // Требование мандата: чужой файл в стейдже не затираем молча.
        val script = stagingFragment()
        assertTrue(script.contains("CONFLICT другой файл, не затираю молча"))
        assertTrue("сверка md5 обязательна", script.contains("md5sum"))
    }

    /** Фрагмент реального скрипта enable (регресс: строка CONFLICT не должна исчезнуть). */
    private fun stagingFragment(): String {
        val f = java.io.File("src/main/java/com/example/voiceapp3/tools/HvacDigitalPlugin.kt")
        assertTrue("HvacDigitalPlugin.kt не найден: ${f.absolutePath}", f.isFile)
        return f.readText()
    }
}
