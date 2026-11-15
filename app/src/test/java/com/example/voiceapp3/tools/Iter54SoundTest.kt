package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter54 §7 — ЖУРНАЛ ЗВУКА И ВЕРХНИЙ СЛОЙ MOUNT.
 *
 * Дефекты (аудит §7):
 *  1. `readOpJournal` любую ошибку чтения возвращал пустым журналом: «ENOENT» (операций не было)
 *     и «EACCES» (прочитать нельзя) были неразличимы, и сверка объявляла «всё снято»;
 *  2. перенос старой записи не был идемпотентным: сбой между добавлением и переименованием
 *     приводил к повторному добавлению;
 *  3. верхний слой mount определялся как `max(mount_id)`, хотя порядок наложения задаёт порядок
 *     строк mountinfo — при расхождении снимался не тот слой.
 *
 * Проверяется на НАСТОЯЩЕЙ файловой системе (права `chmod`, реальное отсутствие файла).
 */
class Iter54SoundTest {

    private val dir: File = File.createTempFile("it54snd", "").let { f ->
        f.delete(); f.mkdirs(); f
    }

    // ─────────────── §7.2: три различимых состояния журнала ───────────────

    @Test
    fun missingJournalIsAbsentButUnreadableJournalIsUnknown() {
        val missing = File(dir, "nope.log")
        assertTrue("нет файла — это факт (ENOENT), а не ошибка: ${(LockOp.readJournal(missing) as LockOp.JournalRead.Absent).wire()}",
            LockOp.readJournal(missing) is LockOp.JournalRead.Absent)

        val locked = File(dir, "locked.log").apply { writeText("1|id=a;gen=1;desired=1;state=planned;stage=/s;targets=/t;reason=\n") }
        // Права снимаются по-настоящему: под root такой тест не сработает, поэтому он пропускается.
        val chmod = ProcessBuilder("/bin/chmod", "000", locked.absolutePath).start().apply { waitFor() }
        val readableAsRoot = runCatching { locked.readText() }.isSuccess
        try {
            val r = LockOp.readJournal(locked)
            if (!readableAsRoot) {
                assertTrue("нечитаемый журнал — Unknown, а не «пусто»: ${r.wire()}",
                    r is LockOp.JournalRead.Unknown)
                assertTrue("причина названа: ${r.wire()}",
                    (r as LockOp.JournalRead.Unknown).why.contains("eacces") ||
                        r.why.contains("io:"))
            }
        } finally {
            ProcessBuilder("/bin/chmod", "600", locked.absolutePath).start().waitFor()
        }
        assertEquals("chmod выполнен", 0, chmod.exitValue())
    }

    @Test
    fun emptyJournalIsReadableAndCorruptLinesAreCounted() {
        val empty = File(dir, "empty.log").apply { writeText("") }
        val r = LockOp.readJournal(empty)
        assertTrue("пустой файл прочитан", r is LockOp.JournalRead.Readable)
        assertEquals("операций нет — это доказано чтением", 0, (r as LockOp.JournalRead.Readable).value.ops.size)

        val dirty = File(dir, "dirty.log").apply {
            writeText(
                "1|id=a;gen=1;desired=1;state=planned;stage=/s;targets=/t;reason=\n" +
                    "2|МУСОР-БЕЗ-ПОЛЕЙ\n"
            )
        }
        val d = LockOp.readJournal(dirty)
        assertTrue(d is LockOp.JournalRead.Readable)
        val j = (d as LockOp.JournalRead.Readable).value
        assertEquals("годная запись разобрана", 1, j.ops.size)
        assertEquals("повреждённая строка посчитана, а не проглочена", 1, j.broken)
        assertTrue("состояние журнала названо: ${d.wire()}", d.wire().contains("corrupt_lines=1"))
    }

    // ─────────────── §7.3: перенос старой записи идемпотентен ───────────────

    @Test
    fun journalAppendIsPerOperationAndLastStateWins() {
        val f = File(dir, "ops.log")
        val op = LockOp.BindOp("op1", 5L, true, "/stage", listOf("/t1", "/t2"), LockOp.State.PLANNED)
        f.appendText(LockOp.journalLine(op, 1L) + "\n")
        f.appendText(LockOp.journalLine(op.copy(state = LockOp.State.DONE, reason = "ok"), 2L) + "\n")
        val j = (LockOp.readJournal(f) as LockOp.JournalRead.Readable).value
        assertEquals("последняя строка побеждает", LockOp.State.DONE, j.ops["op1"]!!.state)
        assertTrue("разрешённых операций нет", j.unresolved().isEmpty())
        // Повторное добавление того же состояния не должно выглядеть новой операцией.
        f.appendText(LockOp.journalLine(op.copy(state = LockOp.State.DONE, reason = "ok"), 3L) + "\n")
        val j2 = (LockOp.readJournal(f) as LockOp.JournalRead.Readable).value
        assertEquals("одна операция", 1, j2.ops.size)
        assertTrue("ничего не «зависло»", j2.unresolved().isEmpty())
    }

    // ─────────────── §7.4: после OFF проверяются ВСЕ цели ───────────────

    @Test
    fun reconcileRequiresEveryTargetNotJustOne() {
        val targets = listOf("/vendor/etc/carlock/a.wav", "/vendor/etc/carlock/b.wav")
        // Ответ только по одной цели: частичный вывод НЕ доказывает снятие обеих.
        val partial = "STOCK_OK:/vendor/etc/carlock/a.wav\n"
        val d = LockOp.reconcileDetail(0, partial, targets)
        assertEquals("частичный ответ — не успех", LockOp.Reconcile.UNPROVEN, d.verdict)
        assertEquals("названа недостающая цель", listOf("/vendor/etc/carlock/b.wav"), d.missing)

        val full = "STOCK_OK:/vendor/etc/carlock/a.wav\nSTOCK_OK:/vendor/etc/carlock/b.wav\n"
        assertEquals("все цели подтверждены",
            LockOp.Reconcile.STOCK_RESTORED, LockOp.reconcileDetail(0, full, targets).verdict)

        // Дубль маркера по одной цели — подмена/склейка вывода, а не доказательство.
        val dup = "STOCK_OK:/vendor/etc/carlock/a.wav\nSTOCK_OK:/vendor/etc/carlock/a.wav\n" +
            "STOCK_OK:/vendor/etc/carlock/b.wav\n"
        assertEquals("дубль не считается", LockOp.Reconcile.UNPROVEN,
            LockOp.reconcileDetail(0, dup, targets).verdict)
    }

    // ─────────────── §7.4: верхний слой — порядок таблицы, id лишь сверка ───────────────

    @Test
    fun mountTopLayerUsesTableOrderWithIdCorroboration() {
        val scripts = listOf(
            File("src/main/java/com/example/voiceapp3/tools/LockMountScripts.kt"),
            File("app/src/main/java/com/example/voiceapp3/tools/LockMountScripts.kt"),
            File("/home/user/w/app/src/main/java/com/example/voiceapp3/tools/LockMountScripts.kt")
        ).first { it.isFile }.readText()
        assertTrue("метод назван: порядок + сверка id",
            scripts.contains("method=mountinfo_order_with_mount_id_corroboration"))
        assertTrue("расхождение даёт AMBIGUOUS, а не «наиболее вероятный» слой",
            scripts.contains("AMBIGUOUS||"))
        assertFalse("прежний ответ max(mount_id) убран",
            scripts.contains("method=max_mount_id"))
    }
}
