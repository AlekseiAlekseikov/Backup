package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter50 §4.1/§4.2 — слой `/vendor/etc/carlock` проверяется ИСПОЛНЕНИЕМ сгенерированных
 * скриптов, а не чтением их текста.
 *
 * Проверяемое:
 *  * `MOUNT_FACT` печатается ВСЕГДА после успешного `mount` — даже если проверка слоя не удалась
 *    (иначе реестр «забывает» созданный слой, и снять его потом нечем);
 *  * владение слоем доказывается парой «root слоя == ожидаемый root источника» + `dev:ino`,
 *    а не литеральным префиксом пути;
 *  * `stat`/таблица недоступны → `unknown`, а не «наше»/«ок»;
 *  * чужой слой сверху не снимается ни в bind, ни в restore.
 *
 * Чего стенд НЕ доказывает: реальный mountinfo прошивки, поведение `umount -l` на её ядре,
 * видимость слоя в namespace аудиопроцесса и слышимость звука.
 */
class Iter50MountLayerTest {

    private var stage: String = ""

    private fun stand(extra: Map<String, String> = emptyMap()): Triple<Iter50Stand, File, Map<String, String>> {
        val st = Iter50Stand()
        // Модель прошивки: /data — ОТДЕЛЬНАЯ ФС с root=/local (в стенде её роль играет
        // каталог внутри temp-директории). Именно из-за этого «root пути» ≠ «путь».
        val data = File(st.dir, "data").apply { mkdirs() }
        val tmp = File(data, "local/tmp").apply { mkdirs() }
        st.mountinfo.writeText(
            "1 0 8:1 / / rw - ext4 /dev/root rw\n" +
                "2 1 253:0 / ${tmp.absolutePath} rw - f2fs /dev/block/sda1 rw\n"
        )
        val targets = File(st.dir, "carlock").apply { mkdirs() }
        val stageFile = File(tmp, "carlock_custom_stage.wav").apply { writeText("RIFF") }
        stage = stageFile.absolutePath
        val env = mapOf("TARGET_DIR" to targets.absolutePath) + extra
        return Triple(st, targets, env)
    }

    private fun wav(dir: File, name: String = "a.wav"): File =
        File(dir, name).apply { writeText("RIFF") }

    @Test
    fun verifiedLayerEmitsFactWithSourceAndRoot() {
        val (st, targets, env) = stand()
        val t = wav(targets)
        val (rc, out) = st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        assertEquals("скрипт завершился: $out", 0, rc)
        assertTrue("root источника вычислен по таблице, а не угадан: $out",
            out.contains("EXPECTED_ROOT value=/carlock_custom_stage.wav"))
        assertTrue("слой подтверждён: $out", out.contains("MOUNT_VERIFIED:${t.absolutePath}|$stage"))
        assertTrue("факт для реестра с источником и root: $out",
            out.contains("MOUNT_FACT:${t.absolutePath}|$stage state=verified") &&
                out.contains("root=/carlock_custom_stage.wav") &&
                out.contains("mount_rc=0"))
        assertTrue("слой реально в таблице", st.mountinfo.readText().contains(t.absolutePath))
        assertEquals("ровно один mount", 1, st.mountCalls().count { it.startsWith("mount ") })
    }

    @Test
    fun layerNotListedIsUnknownButFactIsStillRecorded() {
        val (st, targets, env) = stand(mapOf("HIDE_LAYER" to "1"))
        val t = wav(targets)
        val (_, out) = st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        assertTrue("неизвестно, а не «ок»: $out", out.contains("MOUNT_VERIFY_UNKNOWN:${t.absolutePath} reason=layer_not_listed"))
        assertFalse("MOUNTED_OK не печатается без доказательства", out.contains("MOUNTED_OK:$"))
        // Главное: факт есть — иначе слой останется на устройстве «безымянным».
        assertTrue("факт напечатан даже без подтверждения: $out",
            out.contains("MOUNT_FACT:${t.absolutePath}|$stage state=unknown") &&
                out.contains("reason=layer_not_listed mount_rc=0"))
    }

    @Test
    fun deviceInodeMismatchIsUnverifiedNotOk() {
        val (st, targets, env) = stand(mapOf("STAT_MISMATCH" to "1"))
        val t = wav(targets)
        val (_, out) = st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        assertTrue("identity не совпала → unverified: $out",
            out.contains("MOUNT_VERIFY_FAIL:${t.absolutePath} reason=dev_ino_mismatch"))
        assertTrue("в факте та же причина: $out",
            out.contains("state=unverified") && out.contains("reason=dev_ino_mismatch"))
        assertFalse(out.contains("MOUNTED_OK:$"))
    }

    @Test
    fun statUnavailableIsUnknown() {
        val (st, targets, env) = stand(mapOf("STAT_BROKEN" to "1"))
        val t = wav(targets)
        val (_, out) = st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        assertTrue("нет stat → unknown: $out",
            out.contains("MOUNT_VERIFY_UNKNOWN:${t.absolutePath} reason=stat_unavailable"))
        assertTrue("факт всё равно есть: $out", out.contains("MOUNT_FACT:${t.absolutePath}|$stage state=unknown"))
    }

    @Test
    fun mountFailureIsReportedNotAssumed() {
        val (st, targets, env) = stand(mapOf("MOUNT_FAIL" to "1"))
        val t = wav(targets)
        val (_, out) = st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        assertTrue("отказ mount назван: $out", out.contains("MOUNT_FAIL:${t.absolutePath} rc=32"))
        assertFalse("факта о слое быть не должно — слоя нет", out.contains("MOUNT_FACT:${t.absolutePath}"))
        assertFalse(out.contains("MOUNTED_OK:$"))
    }

    @Test
    fun foreignLayerAboveIsNotRemoved() {
        val (st, targets, env) = stand()
        val t = wav(targets)
        // Сверху чужой слой: root не совпадает с ожидаемым root нашего источника.
        st.mountinfo.appendText("7 1 253:0 /other/place ${t.absolutePath} rw - f2fs /dev/block/sda1 rw\n")
        val (_, out) = st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        assertTrue("чужое названо: $out",
            out.contains("FOREIGN_MOUNT:${t.absolutePath}") && out.contains("reason=ownership_not_proven"))
        assertTrue("цель пропущена: $out", out.contains("TARGET_SKIP:${t.absolutePath} reason=foreign_layer_present"))
        assertTrue("чужой слой на месте", st.mountinfo.readText().contains("/other/place"))
        assertEquals("своего mount не было", 0, st.mountCalls().count { it.startsWith("mount ") })
    }

    @Test
    fun restoreRemovesOurLayerUsingRegistrySource() {
        val (st, targets, env) = stand()
        st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        val t = wav(targets)
        st.exec(LockMountScripts.bindScript(stage), "bind.sh", env) // слой точно есть
        val script = LockMountScripts.restoreScript(listOf(t.absolutePath to stage))
        val (rc, out) = st.exec(script, "restore.sh", env)
        assertEquals(0, rc)
        assertTrue("источник из реестра позволил вычислить root: $out",
            out.contains("RESTORE_EXPECTED target=${t.absolutePath} source=$stage root=/carlock_custom_stage.wav"))
        assertTrue("снято и подтверждено: $out", out.contains("STOCK_OK:${t.absolutePath}"))
        assertFalse("слоя больше нет", st.mountinfo.readText().contains(t.absolutePath))
    }

    @Test
    fun restoreKeepsForeignLayerAbove() {
        val (st, targets, env) = stand()
        val t = wav(targets)
        st.mountinfo.appendText("7 1 253:0 /other/place ${t.absolutePath} rw - f2fs /dev/block/sda1 rw\n")
        val script = LockMountScripts.restoreScript(listOf(t.absolutePath to stage))
        val (_, out) = st.exec(script, "restore.sh", env)
        assertTrue("чужое не снято и это сказано: $out",
            out.contains("FOREIGN_MOUNT:${t.absolutePath}") &&
                out.contains("STOCK_FOREIGN_LEFT:${t.absolutePath}"))
        assertTrue("чужой слой на месте", st.mountinfo.readText().contains("/other/place"))
    }

    @Test
    fun restoreUnprovenWhenUmountFails() {
        val (st, targets, env) = stand(mapOf("UMOUNT_FAIL" to "1"))
        val t = wav(targets)
        st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        val script = LockMountScripts.restoreScript(listOf(t.absolutePath to stage))
        val (_, out) = st.exec(script, "restore.sh", env)
        assertTrue("снятие не подтверждено: $out", out.contains("UMOUNT_FAILED:${t.absolutePath}"))
        assertTrue("и это не выдаётся за сток: $out",
            out.contains("STOCK_UNPROVEN:${t.absolutePath}") && !out.contains("STOCK_OK:${t.absolutePath}"))
        assertTrue("слой остался в таблице", st.mountinfo.readText().contains(t.absolutePath))
    }

    // ───────────── iter51 §3.4: строгий барьер namespace и корень '/' ─────────────

    @Test
    fun restoreRefusesToUmountWhenNamespaceIsNotProven() {
        val (st, targets, env) = stand()
        val t = wav(targets)
        // Слой создан раньше (реестр помнит его), но текущий shell — в ДРУГОМ namespace.
        st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        val before = st.mountinfo.readText()
        val (rc, out) = st.exec(
            LockMountScripts.restoreScript(listOf(t.absolutePath to stage)),
            "restore.sh",
            env + mapOf("NS_DIFFERS" to "1")
        )
        assertEquals("скрипт завершился штатно, ничего не сняв", 0, rc)
        assertTrue("барьер назван: $out", out.contains("RESTORE_BLOCKED reason=namespace_not_proven"))
        assertTrue("ни одного umount не выполнено: ${st.mountCalls()}", st.mountCalls().none { it.startsWith("umount") })
        assertTrue("слой остался в таблице", st.mountinfo.readText().contains(t.absolutePath))
        assertEquals("таблица не изменилась", before, st.mountinfo.readText())
        assertFalse("STOCK_OK не заявляется", out.contains("STOCK_OK:"))
    }

    @Test
    fun restoreIsBlockedWhenMountinfoIsUnreadable() {
        val (st, targets, env) = stand()
        val t = wav(targets)
        st.exec(LockMountScripts.bindScript(stage), "bind.sh", env)
        val (rc, out) = st.exec(
            LockMountScripts.restoreScript(listOf(t.absolutePath to stage)),
            "restore.sh",
            env + mapOf("MOUNTINFO" to "/nonexistent/mountinfo")
        )
        assertEquals(0, rc)
        // MOUNTINFO задаётся стендом всегда — проверяем через нечитаемый путь.
        assertTrue("либо барьер таблицы, либо отсутствие доказанного слоя: $out",
            out.contains("RESTORE_BLOCKED reason=mountinfo_unreadable") ||
                (out.contains("STOCK_UNPROVEN") && !out.contains("STOCK_OK")))
        assertTrue("umount не выполнялся", st.mountCalls().none { it.startsWith("umount") })
    }

    @Test
    fun rootFilesystemAsOnlyParentStillYieldsCorrectExpectedRoot() {
        // Единственный родитель — корень ФС: /data/local/tmp покрыт mount point "/" с root "/".
        val st = Iter50Stand()
        st.mountinfo.writeText("1 0 8:1 / / rw - ext4 /dev/root rw\n")
        val targets = File(st.dir, "carlock").apply { mkdirs() }
        val stageFile = File(st.dir, "carlock_custom_stage.wav").apply { writeText("RIFF") }
        val env = mapOf("TARGET_DIR" to targets.absolutePath)
        val t = wav(targets)
        val (_, out) = st.exec(LockMountScripts.bindScript(stageFile.absolutePath), "bind.sh", env)
        // Ожидаемый root при единственном '/' = полный путь источника (а не "//...").
        assertTrue("root вычислен без '//': $out", !out.contains("EXPECTED_ROOT value=//"))
        assertTrue("факт по цели есть: $out", out.contains("MOUNT_FACT:${t.absolutePath}"))
    }
}
