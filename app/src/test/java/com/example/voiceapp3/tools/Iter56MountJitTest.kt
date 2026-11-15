package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter56 §7 — восстановление снимка звука НЕ снимает слой по ЭВРИСТИКЕ.
 *
 * Что чинится (аудит iter55): когда ожидаемый `root` источника не вычислялся, `drop_own_layers`
 * принимал решение по ЛИТЕРАЛЬНОМУ ПРЕФИКСУ (`STAGE_PREFIX*`). Префикс — не доказательство:
 * чужой bind может иметь похожий корень (`/vendor/etc/carlock_other`), и такой слой снимался
 * как «свой». Направление ошибки опасное — чужая точка монтирования исчезала с устройства.
 *
 * Метод проверки: скрипт восстановления ИСПОЛНЯЕТСЯ настоящим `/bin/sh` на стенде с подставными
 * `mount`/`umount` и подменяемой таблицей `mountinfo`. Таблица показывает слой на цели, корень
 * которого начинается с `STAGE_PREFIX`, при этом сам источник в таблице ОТСУТСТВУЕТ, поэтому
 * ожидаемый root вычислить нельзя.
 *
 * Чего тест НЕ доказывает: реальное поведение mountinfo/umount на прошивке ГУ.
 */
class Iter56MountJitTest {

    private fun stand(): Triple<Iter50Stand, File, Map<String, String>> {
        val st = Iter50Stand()
        val data = File(st.dir, "data").apply { mkdirs() }
        val tmp = File(data, "local/tmp").apply { mkdirs() }
        st.mountinfo.writeText(
            "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
                "2 1 253:0 / ${tmp.absolutePath} rw - f2fs /dev/block/sda1 rw\n"
        )
        val targets = File(st.dir, "carlock").apply { mkdirs() }
        return Triple(st, targets, mapOf("TARGET_DIR" to targets.absolutePath))
    }

    @Test
    fun layerWithPrefixLikeRootIsNeverTreatedAsOurs() {
        val (st, targets, env) = stand()
        val t = File(targets, "a.wav").apply { writeText("RIFF") }
        // Корень слоя НАЧИНАЕТСЯ с STAGE_PREFIX — ровно то, что прежнее правило принимало за «своё».
        // Ожидаемый корень источника при этом ДРУГОЙ, значит владение не доказано.
        st.mountinfo.appendText(
            "9 1 253:0 ${MountInfo.STAGE_PREFIX}something_else ${t.absolutePath} rw - f2fs /dev/block/sda1 rw\n"
        )
        val source = File(st.dir, "stage.wav").apply { writeText("RIFF") }
        val before = st.mountinfo.readText()
        val (rc, out) = st.exec(
            LockMountScripts.restoreScript(listOf(t.absolutePath to source.absolutePath)),
            "restore_prefix.sh",
            env
        )
        assertEquals("скрипт завершился штатно: $out", 0, rc)
        assertTrue("владение не доказано и это сказано: $out",
            out.contains("FOREIGN_MOUNT:${t.absolutePath}") &&
                out.contains("reason=ownership_not_proven"))
        assertFalse("префикс не выдаётся за доказательство: $out", out.contains("method=prefix"))
        assertTrue("ни одного umount по цели: ${st.mountCalls()}",
            st.mountCalls().none { it.contains(t.absolutePath) })
        assertEquals("таблица не изменилась", before, st.mountinfo.readText())
        assertFalse("сток не заявляется", out.contains("STOCK_OK:${t.absolutePath}"))
    }

    @Test
    fun unprovableExpectedRootKeepsTheLayerInsteadOfGuessing() {
        val (st, targets, env) = stand()
        val t = File(targets, "b.wav").apply { writeText("RIFF") }
        val fakeRoot = MountInfo.STAGE_PREFIX + "whatever"
        st.mountinfo.appendText("9 1 253:0 $fakeRoot ${t.absolutePath} rw - f2fs /dev/block/sda1 rw\n")
        // Таблица, из которой ожидаемый root источника ВЫЧИСЛИТЬ НЕЛЬЗЯ: у строки нет major:minor,
        // такая строка не описывает монтирование и не может служить основанием владения.
        val bad = File(st.dir, "mountinfo_bad").apply {
            writeText("1 0 n/a / / rw - ext4 /dev/root rw\n" +
                "9 1 n/a $fakeRoot ${t.absolutePath} rw - f2fs /dev/block/sda1 rw\n")
        }
        val source = File(st.dir, "stage2.wav").apply { writeText("RIFF") }
        val before = st.mountinfo.readText()
        val (rc, out) = st.exec(
            LockMountScripts.restoreScript(listOf(t.absolutePath to source.absolutePath)),
            "restore_unprovable.sh",
            env + mapOf("MOUNTINFO" to bad.absolutePath)
        )
        assertEquals("скрипт завершился штатно: $out", 0, rc)
        assertTrue("недоказуемое владение названо прямо: $out",
            out.contains("reason=root_expected_uncomputable") &&
                out.contains("action=keep_layer"))
        assertFalse("префикс не выдаётся за доказательство: $out", out.contains("method=prefix"))
        assertTrue("ни одного umount по цели: ${st.mountCalls()}",
            st.mountCalls().none { it.contains(t.absolutePath) })
        assertEquals("таблица не изменилась", before, st.mountinfo.readText())
    }
}
