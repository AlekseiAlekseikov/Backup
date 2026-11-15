package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * iter57 §7.2 — АТОМАРНАЯ выдача поколения намерения JIT.
 *
 * Дефект (аудит iter56): `read → +1 → write` в двух `SharedPreferences` без исключительности;
 * два процесса могли получить один номер, а чтение обратно этого не обнаруживало.
 *
 * Здесь проверяется ФАКТ исключительности (`fcntl`-замок файла), а не текст кода:
 *  1. два независимых объекта хранилища (как два процесса) выдают номера без повторов;
 *  2. независимый процесс ОС, держащий тот же замок, действительно задерживает выдачу;
 *  3. переполнение не переходит в отрицательные числа, а честно не выдаёт номер;
 *  4. потеря одной из копий не сбрасывает нумерацию (legacy-префы и файл взаимно страхуют);
 *  5. часы в номере не участвуют (номер не зависит от `System.currentTimeMillis()`).
 *
 * Чего тесты НЕ доказывают: поведение на конкретной прошивке ГУ (общая ФС, работа `fcntl`
 * на `f2fs`), а также отсутствие гонки в самом `SharedPreferences`.
 */
class Iter57SeqTest {

    private fun dir(): File = File.createTempFile("it57seq", "").let { it.delete(); it.mkdirs(); it }

    private fun store(d: File, disk: MutableMap<String, Any?> = mutableMapOf()) =
        JitNet.AndroidStore({ JitFake.Prefs(disk) }, { null }) { d }

    private fun storeWithPrefs(d: File, cps: JitFake.Prefs, dps: JitFake.Prefs? = null) =
        JitNet.AndroidStore({ cps }, { dps }) { d }

    // ───── 1. Исключительность между «процессами» ─────

    @Test
    fun twoProcessesNeverGetTheSameNumber() {
        val d = dir()
        // Один общий «диск» префов на двоих — как общие SharedPreferences двух процессов.
        val disk = Collections.synchronizedMap(mutableMapOf<String, Any?>())
        val a = store(d, disk)
        val b = store(d, disk)
        val got = Collections.synchronizedList(mutableListOf<Long>())
        val barrier = CyclicBarrier(8)
        val threads = (0 until 8).map { i ->
            Thread {
                barrier.await(10, TimeUnit.SECONDS)
                repeat(25) {
                    val s = (if (i % 2 == 0) a else b).bumpIntentSeq()
                    if (s != null) got.add(s)
                }
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { it.join(60_000) }
        assertEquals("все выдачи дошли: $got", 200, got.size)
        assertEquals("номера НЕ повторяются (атомарная выдача): ${got.sorted()}",
            200, got.distinct().size)
        assertEquals("нумерация сплошная от 1 до 200, без дыр", (1L..200L).toList(), got.sorted())
    }

    @Test
    fun independentProcessHoldingTheLockDelaysIssuance() {
        val d = dir()
        val lockPath = File(d, "jit_intent_seq.lock").absolutePath
        // Независимый процесс ОС берёт ТОТ ЖЕ замок (fcntl) и держит его ~1.5 с.
        val script = "import fcntl,sys,time\n" +
            "f=open(sys.argv[1],'a+')\n" +
            "fcntl.lockf(f,fcntl.LOCK_EX)\n" +
            "sys.stdout.write('locked\\n')\n" +
            "sys.stdout.flush()\n" +
            "time.sleep(1.5)\n"
        val py = listOf("python3", "-c", script, lockPath)
        val p = try {
            ProcessBuilder(py).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            null
        }
        assumeTrue("нет python3 — межпроцессную часть проверить нечем", p != null)
        val reader = p!!.inputStream.bufferedReader()
        val hello = reader.readLine()
        assumeTrue("замок не взят внешним процессом: $hello", hello == "locked")
        val t0 = System.nanoTime()
        val s = store(d).bumpIntentSeq()
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        p.waitFor(30, TimeUnit.SECONDS)
        assertNotNull("номер выдан после освобождения замка", s)
        assertTrue("выдача ЖДАЛА внешний замок (${waitedMs} мс): исключительность настоящая",
            waitedMs >= 900)
    }

    // ───── 2. Переполнение ─────

    @Test
    fun overflowIsNotWrappedIntoNegativeNumbers() {
        val d = dir()
        File(d, "jit_intent_seq.state").writeText("epoch=deadbeef\nseq=${Long.MAX_VALUE}\n")
        val st = store(d)
        assertNull("номер не выдан при переполнении", st.bumpIntentSeq())
        assertTrue("причина названа: ${st.lastSeqNote}",
            st.lastSeqNote?.contains("overflow") == true)
    }

    // ───── 3. Потеря одной копии не сбрасывает нумерацию ─────

    @Test
    fun lostStateFileContinuesFromPrefs() {
        val d = dir()
        val disk = mutableMapOf<String, Any?>(
            JitRef.KEY_INTENT_SEQ to 41L,
            JitRef.KEY_INTENT_EPOCH to "0a0b0c0d0e0f0001"
        )
        val st = store(d, disk)
        assertEquals("продолжает префы, а не начинает с 1", 42L, st.bumpIntentSeq())
        assertEquals("префы обновлены", 42L, disk[JitRef.KEY_INTENT_SEQ])
        assertTrue("эпоха сохранена: ${st.lastSeqNote}",
            st.lastSeqNote?.contains("epoch=from_prefs") == true ||
                st.lastSeqNote?.contains("epoch=0a0b0c0d0e0f0001") == true)
    }

    @Test
    fun lostPrefsContinueFromStateFile() {
        val d = dir()
        File(d, "jit_intent_seq.state").writeText("epoch=1122334455667788\nseq=99\n")
        val disk = mutableMapOf<String, Any?>()
        val st = store(d, disk)
        assertEquals("продолжает файл, а не начинает с 1", 100L, st.bumpIntentSeq())
        assertEquals("префы догнали файл", 100L, disk[JitRef.KEY_INTENT_SEQ])
    }

    @Test
    fun firstEverIssuanceIsNamedAsNewEpochAndDoesNotUseClock() {
        val d = dir()
        val before = System.currentTimeMillis()
        val st = store(d)
        val first = st.bumpIntentSeq()
        assertEquals("первый номер — 1, а не время", 1L, first)
        assertTrue("эпоха названа новой: ${st.lastSeqNote}",
            st.lastSeqNote?.contains("epoch=new") == true)
        val second = st.bumpIntentSeq()
        assertEquals("второй — 2", 2L, second)
        assertTrue("эпоха та же: ${st.lastSeqNote}", st.lastSeqNote?.contains("epoch=kept") == true)
        assertTrue("номер не связан с часами", first!! < before)
        // Файл состояния не содержит времени: только эпоха и номер.
        val text = File(d, "jit_intent_seq.state").readText()
        assertTrue("состояние без часов: $text", text.trim().lines().size == 2)
        assertTrue("только epoch/seq: $text",
            text.lines().all { it.isBlank() || it.startsWith("epoch=") || it.startsWith("seq=") })
    }

    // ───── 4. Нет каталога ⇒ нет номера, но и не «вечный отказ» ─────

    @Test
    fun missingStateDirectoryYieldsNoNumberButSaysWhy() {
        val st = JitNet.AndroidStore({ JitFake.Prefs(mutableMapOf()) }, { null }) { null }
        assertNull("номер не выдан", st.bumpIntentSeq())
        assertTrue("причина названа: ${st.lastSeqNote}",
            st.lastSeqNote?.contains("no_state_dir") == true)
    }

    // ───── 5. Порча файла не даёт «молча начать с 1» ─────

    @Test
    fun corruptedStateFileWithKnownPrefsDoesNotReset() {
        val d = dir()
        File(d, "jit_intent_seq.state").writeText("garbage\nnot-a-counter\n")
        val disk = mutableMapOf<String, Any?>(JitRef.KEY_INTENT_SEQ to 7L)
        val st = store(d, disk)
        assertEquals("испорченный файл не обнуляет нумерацию", 8L, st.bumpIntentSeq())
    }

    @Test
    fun lockFileIsLeftInPlaceAndReusable() {
        val d = dir()
        val st = store(d)
        st.bumpIntentSeq()
        val lock = File(d, "jit_intent_seq.lock")
        assertTrue("файл-замок остаётся (его нельзя удалять: гонка с удалением)", lock.isFile)
        // Замок действительно освобождён: следующий захват не блокируется.
        RandomAccessFile(lock, "rw").use { raf ->
            val l = raf.channel.tryLock()
            assertNotNull("замок свободен после выдачи", l)
            l?.release()
        }
    }
}
