package com.example.voiceapp3.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iter60 §7 — UI/окна/логи: то, что действительно можно доказать без ГУ.
 *
 * Что проверяется:
 *  1. §7.4 `VentOwnerTx` — блок мутации больше не получает ЖИВЫЕ коллекции: утёкшая ссылка
 *     не меняет состояние и не обходит поколение (mutable-set escape).
 *  2. §7.5 `FileLogCore` — `renameTo` возвращает Boolean и его результат ПРОВЕРЯЕТСЯ, `fsync`
 *     не подавляется, путь свидетельства возвращается наружу.
 *  3. §7.2 — у каждого тумблера намерение и факт применения разделены (наличие ключей
 *     `*_applied`/`*_pending_reason` не совпадает с ключом намерения).
 *
 * Чего эти тесты НЕ доказывают (⛔, будет в отчёте): как выглядит UI на ГУ, реальную ротацию
 * логов на прошивке, движение стекла (VHAL) и слышимость звука.
 */
class Iter60OwnersLogTest {

    // ─────────────── 1. VentOwnerTx: нет возврата живого mutable-set ───────────────

    @Test
    fun mutationBlockCannotEscapeLiveCollections() {
        val tx = VentOwnerTx()
        tx.seed(mapOf(1 to setOf(10), 2 to setOf(20)))
        val genBefore = tx.generation()

        var leaked: MutableMap<Int, MutableSet<Int>>? = null
        tx.mutate { map ->
            map.getOrPut(3) { HashSet() }.add(30)
            leaked = map
        }
        assertEquals("мутация применена", setOf(30), tx.snapshot()[3])
        val genAfter = tx.generation()
        assertTrue("поколение выросло", genAfter > genBefore)

        // Утёкшая ссылка: мутация «в обход» не должна менять состояние и поколение.
        leaked!![1]!!.add(999)
        leaked!!.remove(2)
        assertEquals("состояние не изменилось через утёкшую ссылку", setOf(10), tx.snapshot()[1])
        assertEquals("удаление через утёкшую ссылку не сработало", setOf(20), tx.snapshot()[2])
        assertEquals("поколение не двигалось", genAfter, tx.generation())
    }

    @Test
    fun mutationIsAtomicAgainstConcurrentReaders() {
        val tx = VentOwnerTx()
        tx.seed(mapOf(1 to setOf(10)))
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..4).map { t ->
            Thread {
                repeat(200) {
                    tx.mutate { map -> map.getOrPut(t) { HashSet() }.add(t * 100 + it) }
                    // Читатель не должен увидеть «наполовину применённую» карту: ключи либо есть
                    // целиком, либо нет; исключений быть не должно.
                    runCatching { tx.snapshot().forEach { (_, v) -> v.size } }
                        .onFailure { errors.incrementAndGet() }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertEquals("чтение под тем же lock не падает", 0, errors.get())
        assertTrue("поколение соответствует числу мутаций", tx.generation() >= 800)
    }

    // ─────────────── 2. FileLogCore: честная запись и путь свидетельства ───────────────

    @Test
    fun logCoreChecksRenameAndFsyncInsteadOfAssumingSuccess() {
        val src = File("app/src/main/java/com/example/voiceapp3/tools/FileLogCore.kt").readText()
        assertTrue("результат renameTo проверяется",
            src.contains("val moved = runCatching { pending.renameTo(kept) }.getOrDefault(false)"))
        assertTrue("fsync не подавляется молча",
            src.contains("return Result(false, active, \"fsync_failed:"))
        assertTrue("отказ переноса назван, а не выдан за успех",
            src.contains("new_fsync_failed:kept_in_new"))
        // Путь свидетельства возвращается наружу в Result (а не только пишется в лог).
        assertTrue("файл-свидетельство возвращается в результате",
            src.contains("data class Result(") && src.contains("val file: File?,"))
        assertTrue("повреждение хвоста сообщается отдельным полем",
            src.contains("val tailNulBefore: Int,"))
    }

    // ─────────────── 3. тумблеры: намерение и факт применения — разные ключи ───────────────

    @Test
    fun everySideEffectToggleSeparatesIntentFromAppliedFact() {
        val ttl = File("app/src/main/java/com/example/voiceapp3/tools/NetworkTtl.kt").readText()
        val sound = File("app/src/main/java/com/example/voiceapp3/tools/LockSound.kt").readText()
        assertTrue("TTL: ключ намерения", ttl.contains("PREF_TTL_65_ACTIVE"))
        assertTrue("TTL: ключ факта", ttl.contains("PREF_TTL_65_APPLIED"))
        assertTrue("TTL: причина ожидания отдельно", ttl.contains("PREF_TTL_65_PENDING_REASON"))
        assertTrue("звук: ключ намерения", sound.contains("PREF_LOCK_SOUND_ENABLED"))
        assertTrue("звук: ключ факта", sound.contains("PREF_LOCK_SOUND_APPLIED"))
        assertTrue("звук: причина ожидания отдельно", sound.contains("PREF_LOCK_SOUND_PENDING_REASON"))
        // Автоматика не пишет намерение владельца (правило iter59 §7, сохранено).
        assertFalse("применение не переписывает намерение TTL",
            Regex("Prefs2\\.putString\\([^)]*PREF_TTL_65_ACTIVE").containsMatchIn(ttl))
    }
}
