package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iter67 §J — скрипт цепочки: точка установки собственного APK не размонтируется.
 *
 * Живой факт: `umount -l` снимал `/data/app/…`, откуда исполняется наш APK; процесс терял файлы и
 * умирал ДО `setprop` — `zygote_pid` не менялся, `dalvik.vm.usejit=false`, файлов
 * `claim/lock/term` нет.
 */
class Iter67ChainScriptTest {

    private val s: String = JitRef.chainScriptForTest(want = true, guard = "")

    @Test
    fun `точка установки исключается из umount`() {
        // fail-before: ни `UMOUNT_SKIP`, ни `_self_dir` в скрипте не было.
        assertTrue("нет строки исключения: $s", s.contains("UMOUNT_SKIP self="))
        assertTrue("нет определения своего каталога", s.contains("_self_dir"))
        assertTrue("свой каталог берётся из pm path", s.contains("pm path " + JitRef.APP_ID))
        assertTrue("нужен dirname от пути APK", s.contains("_self_dir=\$(dirname"))
    }

    @Test
    fun `без pm path — umount не выполняется`() {
        assertTrue("нет честного пропуска", s.contains("UMOUNT_SKIPPED reason=self_unknown"))
    }

    @Test
    fun `trap EXIT стоит до svc wifi disable`() {
        val trap = s.indexOf("trap ")
        val svc = s.indexOf("svc wifi disable")
        // fail-before: trap не было вовсе — при смерти процесса не оставалось ни строки.
        assertTrue("нет trap EXIT", trap > 0)
        assertTrue("trap должен стоять ДО первого side effect: trap=$trap svc=$svc", trap < svc)
    }

    @Test
    fun `порядок шагов референса сохранён`() {
        val i1 = s.indexOf("svc wifi disable")
        val i2 = s.indexOf("umount -l")
        val i3 = s.indexOf("setprop dalvik.vm.usejit")
        val i4 = s.indexOf("setprop ctl.restart zygote")
        assertTrue("порядок нарушен: $i1 $i2 $i3 $i4", i1 in 1 until i2 && i2 < i3 && i3 < i4)
        assertTrue("перезапуск zygote обязан быть последним шагом",
            s.trimEnd().endsWith("setprop ctl.restart zygote"))
    }

    @Test
    fun `UMOUNT пишется до setprop`() {
        val umountDone = s.indexOf("UMOUNT_DONE path=")
        val setprop = s.indexOf("setprop dalvik.vm.usejit")
        assertTrue("отчёт umount должен быть ДО setprop: $umountDone vs $setprop",
            umountDone > 0 && umountDone < setprop)
    }

    @Test
    fun `terminal-файл операции, а не каталог приложения`() {
        val path = "/data/local/tmp/voiceapp3_jit_op-1.term"
        val script = JitRef.chainScript(want = true, guard = "", termPath = path)
        assertTrue("trap пишет не в свой файл", script.contains(path))
        // В /data/app скрипт писать не должен: именно его он и размонтирует.
        assertFalse("запись в /data/app", script.contains(">> /data/app"))
    }

    @Test
    fun `литерал референса без добавок остаётся доступен для сверки`() {
        val ref = JitRef.referenceScriptNoTrapForTest(want = true)
        assertFalse("в литерале референса trap быть не должно", ref.contains("trap "))
        assertFalse("в литерале референса нет UMOUNT_SKIP", ref.contains("UMOUNT_SKIP"))
        assertTrue("литерал обязан содержать шаг umount", ref.contains("umount -l"))
        assertTrue("литерал обязан содержать setprop свойства",
            ref.contains("setprop dalvik.vm.usejit true"))
    }

    @Test
    fun `applicationId в скрипте совпадает со сборкой`() {
        val gradle = java.io.File("app/build.gradle.kts").readText()
        assertTrue("applicationId в скрипте не совпадает со сборкой",
            gradle.contains("applicationId = \"" + JitRef.APP_ID + "\""))
    }
}
