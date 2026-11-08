package com.example.voiceapp3.tools

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter33n §1.2 — контракт уведомления статус-иконки 1:1 с референсом CentralEXAuto 3.3.9
 * (`w0/kg.j`) и таблицей слотов `w0/b3.run`.
 *
 * Почему это важно: плагин (`geely_auto_systemui_plugin_digital.apk`, тот же APK, что у референса)
 * принимает уведомление только если
 *   • `Notification.extras["flag_status_icon_notification"] == true`;
 *   • `flag_status_icon_id` — Integer (иначе `FlymeAutoNotificationEntry.checkStatusBarParameter`
 *     пишет в лог «String cannot be cast to Integer,use default 0» и ставит 0);
 *   • есть `flag_status_icon_remote_view` ИЛИ `flag_status_icon_icon`
 *     (`StatusIconManager.isStatusBarIconNotification` → «Notification entry has some null»).
 */
class StatusIconContractTest {

    @Test
    fun keysAreExactlyReferenceSet() {
        assertEquals(
            listOf(
                "flag_status_icon_notification",
                "flag_status_icon_id",
                "flag_status_icon_describe",
                "flag_status_icon_icon",
                "flag_status_icon_pressed_icon",
                "flag_status_icon_hide",
                "flag_status_icon_rank",
                "flag_status_icon_space_x",
                "flag_status_icon_is_pick_on",
                "flag_status_icon_specific_width",
            ),
            StatusIcons.contractKeys()
        )
    }

    @Test
    fun valuesMatchReference() {
        val c = StatusIcons.StatusIconContract(iconId = 97, describe = "StatusIcon_BatteryPercent", rank = 32)
        val m = StatusIcons.contractExtras(c).toMap()
        assertEquals(true, m["flag_status_icon_notification"])
        assertEquals(97, m["flag_status_icon_id"])              // Int, не String
        assertEquals("StatusIcon_BatteryPercent", m["flag_status_icon_describe"])
        assertEquals(32, m["flag_status_icon_rank"])
        assertEquals(1, m["flag_status_icon_space_x"])
        assertEquals(false, m["flag_status_icon_hide"])
        assertEquals(false, m["flag_status_icon_is_pick_on"])
        assertEquals(0, m["flag_status_icon_specific_width"])
    }

    @Test
    fun cexSlotTable() {
        // notifId / iconId / rank — из w0/b3.run (run(...) в CEX)
        assertEquals(listOf(Triple(15020, 97, 32), Triple(15021, 15, 22)), StatusIcons.cexTable())
    }

    @Test
    fun channelMatchesReference() {
        assertEquals("central_ex_status_temp", StatusIcons.channelStatus())
        assertEquals(NotificationManager.IMPORTANCE_LOW, StatusIcons.channelImportance())
    }

    /**
     * Найденное ограничение плагина (байткод `StatusIconManager.crateAppStatusIconInfo`):
     * `setDirection(FlymeAutoStatusBarContentDirection.LEFT)` для ЛЮБОГО уведомления от приложения,
     * а `StatusContentLayout.addIcon` принимает иконку только при `info.direction == layout.mDirection`.
     * Значит уведомлениями нельзя попасть в правый кластер (там только «родные» иконки плагина).
     * Тест фиксирует это как ЗНАНИЕ (в коде выбора направления нет).
     */
    @Test
    fun noDirectionOverrideInOurCode() {
        val f = listOf(
            java.io.File("src/main/java/com/example/voiceapp3/tools/StatusIcons.kt"),
            java.io.File("app/src/main/java/com/example/voiceapp3/tools/StatusIcons.kt"),
        ).firstOrNull { it.isFile } ?: error("не найден StatusIcons.kt")
        val src = f.readText()
        assertFalse("направление иконок задаёт плагин, своих полей нет",
            src.contains("flag_status_icon_direction"))
        // без иконки уведомление отбрасывается — проверяем, что мы её всегда кладём
        assertTrue(src.contains("c.hasIcon"))
    }
}
