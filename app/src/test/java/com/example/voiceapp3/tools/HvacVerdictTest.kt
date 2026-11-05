package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33c A3 — регресс-тест разбора вердикта плагина климата (JVM, без Android).
 *
 * Покрывает ровно те дефекты, которые нашлись при ревью второй пересборки:
 *  • A1: `inodeMatch` не срабатывал, потому что искали одну строку "SRC_INODE=n TGT_INODE=n",
 *        а скрипт печатает их раздельно;
 *  • A2: итоговый `VIA` читался первым вхождением без якорей и попадал в `via=pm_uninstall`
 *        из служебной строки `HVAC_UNSHADOW`.
 */
class HvacVerdictTest {

    /** Типичный УСПЕШНЫЙ вывод: затмение снято (внутри есть via=pm_uninstall), затем bind. */
    private val successOut = """
        id
        uid=0(root) gid=0(root)
        ###STAGE name=check status=BEGIN
        SELINUX=Enforcing
        PM_PATH=/data/app/com.flyme.auto.systemuiplugin-1/base.apk
        SRC_INODE=25004
        ###STAGE name=check status=OK reason=pm_path:/data/app/com.flyme.auto.systemuiplugin-1/base.apk
        ###STAGE name=unshadow status=BEGIN
        UNSHADOW_TRY1=Success
        HVAC_UNSHADOW: before=/data/app/com.flyme.auto.systemuiplugin-1/base.apk after=/system/priv-app/GeelyAutoSystemUIPlugin/GeelyAutoSystemUIPlugin.apk unshadow_via=pm_uninstall result=OK
        ###STAGE name=unshadow status=OK reason=pm_path:/system/priv-app/GeelyAutoSystemUIPlugin/GeelyAutoSystemUIPlugin.apk
        TGT_INODE=25004
        ###STAGE name=bind status=OK reason=inode_match:25004
        PROPS=batt:42.4,temp:9.3,exttemp:21.0
        VIA=bind
        HVAC_DIGITAL_OK
    """.trimIndent()

    /** Провал снятия затмения: скрипт печатает VIA=install и выходит с кодом 2. */
    private val shadowFailedOut = """
        PM_PATH=/data/app/com.flyme.auto.systemuiplugin-1/base.apk
        SRC_INODE=25004
        HVAC_UNSHADOW: before=/data/app/com.flyme.auto.systemuiplugin-1/base.apk after=/data/app/com.flyme.auto.systemuiplugin-1/base.apk unshadow_via=uninstall_system_updates result=FAILED
        ###STAGE name=unshadow status=FAILED reason=pm_path:/data/app/com.flyme.auto.systemuiplugin-1/base.apk
        VIA=install
        HVAC_DIGITAL_FALHOU
    """.trimIndent()

    @Test
    fun successVerdictIsBindWithInodeMatch() {
        val d = HvacVerdict.parse(successOut)
        assertEquals("bind", d.via)                       // A2: НЕ pm_uninstall, НЕ pm
        assertEquals("pm_uninstall", d.unshadowVia)        // служебное поле отдельно
        assertTrue("inodeMatch должен быть true", d.inodeMatch)   // A1
        assertEquals("25004", d.srcInode)
        assertEquals("25004", d.tgtInode)                  // tgt_inode больше не "-"
        assertEquals("OK", d.bindStage)
        // Статус успеха — OK, а не PARTIAL (третье следствие A1).
        assertEquals("OK", HvacVerdict.statusOf(d, mountOk = true, rc = 0))
        // И даже когда inode посчитать не удалось, mountOk + bind OK обязаны дать OK.
        assertEquals("OK", HvacVerdict.statusOf(d.copy(inodeMatch = false), mountOk = true, rc = 0))
    }

    @Test
    fun failedUnshadowIsInstallAndNoInodeMatch() {
        val d = HvacVerdict.parse(shadowFailedOut)
        assertEquals("install", d.via)
        assertFalse(d.inodeMatch)
        assertEquals("none", d.bindStage)   // до bind скрипт не дошёл — стадии нет вовсе
        assertTrue(d.failFlag)              // HVAC_DIGITAL_FALHOU
        // Явный провал + ненулевой rc ⇒ FAILED, а не «OK по /data/app».
        assertEquals("FAILED", HvacVerdict.statusOf(d, mountOk = false, rc = 2))
    }

    @Test
    fun underscoreViaNeverLeaksIntoVerdict() {
        // Служебное поле с подчёркиванием стоит ВЫШЕ итоговой строки — раньше оно и попадало в via.
        val out = """
            HVAC_UNSHADOW: before=/data/app/x after=/system/y unshadow_via=uninstall_system_updates result=OK
            SRC_INODE=777
            TGT_INODE=777
            ###STAGE name=bind status=OK reason=inode_match:777
            VIA=bind
        """.trimIndent()
        val d = HvacVerdict.parse(out)
        assertEquals("bind", d.via)
        assertEquals("uninstall_system_updates", d.unshadowVia)
        assertTrue(d.inodeMatch)
        // Только служебное поле, итоговой строки нет ⇒ via неизвестен, а не "uninstall".
        val noVerdict = HvacVerdict.parse("HVAC_UNSHADOW: unshadow_via=uninstall_system_updates result=OK")
        assertEquals("?", noVerdict.via)
    }

    @Test
    fun garbageOutputDoesNotThrow() {
        for (s in listOf("", "   ", "random text\nбез маркеров", "ERR_ADB_IO: null", "VIA=\nVIA=bind!!")) {
            val d = HvacVerdict.parse(s)
            assertFalse("inodeMatch для мусора: '$s'", d.inodeMatch)
            assertTrue("via должен быть строкой: '$s'", d.via.isNotEmpty())
        }
        assertEquals("?", HvacVerdict.parse("").via)
        assertEquals("?", HvacVerdict.parse("random text").via)
        // "VIA=bind!!" не проходит якорь `^VIA=([A-Za-z_]+)$` — это не итоговая строка.
        assertEquals("?", HvacVerdict.parse("VIA=bind!!").via)
    }

    @Test
    fun skippedBindCountsAsSuccess() {
        // Идемпотентный повтор: плагин уже смонтирован, скрипт печатает SKIP.
        val out = """
            SRC_INODE=900
            TGT_INODE=900
            ###STAGE name=bind status=SKIP reason=already_mounted_inode_match
            VIA=bind
            HVAC_DIGITAL_OK
        """.trimIndent()
        val d = HvacVerdict.parse(out)
        assertTrue(d.inodeMatch)
        assertEquals("OK", HvacVerdict.statusOf(d, mountOk = true, rc = 0))
    }

    @Test
    fun blockedChannelIsNotFailed() {
        val d = HvacVerdict.parse("ERR_ROOT_CHANNEL_UNAVAILABLE (state=NO_ROOT_SH)")
        assertEquals("BLOCKED", HvacVerdict.statusOf(d, mountOk = false, rc = 1, rootUnavailable = true))
        assertEquals("FAILED", HvacVerdict.statusOf(d, mountOk = false, rc = 1, permDenied = true))
    }
}
