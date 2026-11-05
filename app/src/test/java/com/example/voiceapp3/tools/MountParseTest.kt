package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33h D1.5 — разбор `/proc/mounts` на РЕАЛЬНОЙ строке из лога владельца
 * (`uploads/voiceapp3 4.log`), а не на выдуманной:
 *
 * `/dev/block/platform/bootdevice/by-name/userdata /system/app/GeelyAutoSystemUIPlugin/GeelyAutoSystemUIPlugin.apk ext4 rw,seclabel,nosuid,nodev,noatime,noauto_da_alloc,resgid=1065,errors=panic,data=ordered`
 *
 * Тест фиксирует то, из-за чего в 1.0.53 umount не выполнялся ни разу: прежний
 * `grep -F systemuiplugin` регистрозависим и эту строку НЕ находил.
 */
class MountParseTest {

    private val realLine =
        "/dev/block/platform/bootdevice/by-name/userdata " +
            "/system/app/GeelyAutoSystemUIPlugin/GeelyAutoSystemUIPlugin.apk ext4 " +
            "rw,seclabel,nosuid,nodev,noatime,noauto_da_alloc,resgid=1065,errors=panic,data=ordered"

    // 1. Реальная строка маунта распознаётся (и это ровно та строка, которую пропускал старый grep).
    @Test
    fun realLogLineIsOurMount() {
        assertTrue("реальная строка из лога должна распознаваться", HvacDigitalPlugin.isOurMountLine(realLine))
        assertEquals(realLine, HvacDigitalPlugin.firstOurMountLine("noise line\n$realLine\n"))
        assertEquals(1, HvacDigitalPlugin.countOurMounts(realLine))
        // Старый предикат: `grep -F systemuiplugin` — на этой же строке не находит ничего.
        assertFalse(realLine.contains("systemuiplugin"))
    }

    // 2. Чужие строки маунтов не считаются нашими.
    @Test
    fun foreignMountsIgnored() {
        val foreign = listOf(
            "/dev/block/dm-0 /system ext4 ro,seclabel,relatime 0 0",
            "/dev/block/dm-1 /data ext4 rw,seclabel,nosuid,nodev 0 0",
            "tmpfs /mnt/vendor/persist tmpfs rw,seclabel,nosuid,nodev,noexec,relatime,mode=700 0 0",
            "RC=1",
        )
        for (l in foreign) assertFalse("не наш маунт: $l", HvacDigitalPlugin.isOurMountLine(l))
        assertEquals(0, HvacDigitalPlugin.countOurMounts(foreign.joinToString("\n")))
        assertEquals("", HvacDigitalPlugin.firstOurMountLine(foreign.joinToString("\n")))
    }

    // 3. Пустой вывод (/proc/mounts без плагина) + счётчик umount из скрипта.
    @Test
    fun emptyMountsAndUmountCounter() {
        assertEquals(0, HvacDigitalPlugin.countOurMounts(""))
        assertEquals("", HvacDigitalPlugin.firstOurMountLine(""))
        assertEquals(1, HvacDigitalPlugin.umountCountOf("UMOUNT stale: /system/app/GeelyAutoSystemUIPlugin/GeelyAutoSystemUIPlugin.apk\nUMOUNT_OK x\nUMOUNT_COUNT=1"))
        assertEquals(0, HvacDigitalPlugin.umountCountOf("RESTORE_OK"))
    }
}
