package com.example.voiceapp3.tools

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * iter64 §1 — КОНСТРУКТОР ИДЕНТИЧНОСТИ СОБЫТИЯ ЖУРНАЛА.
 *
 * Дефект iter63 (причина потери записей): `rid` выдавался `AtomicLong(0)` **в памяти процесса**
 * (`FileLogCore.kt:36`, `:64`). Второй процесс того же APK (у владельца JIT и TTL работают именно
 * в нём) начинал нумерацию с 1 — и его запись с `rid=1` при слиянии `.new` выбрасывалась как
 * «уже существующая». После `ctl.restart zygote` (клик JIT) процесс перезапускался и первая запись
 * снова терялась. Всё, что нужно владельцу для разбора интернета, лежало именно в этих строках.
 *
 * Что здесь. Идентификатор собирается из ТРЁХ независимых фактов, а не из номера в процессе:
 *
 *  • `ts` — время записи (мс). Различает события во времени, поэтому новый запуск процесса не
 *    повторяет значения предыдущего;
 *  • `writer` — метка ПИСАТЕЛЯ: pid + starttime процесса (поле 22 `/proc/<pid>/stat`) + boot_id.
 *    Постоянна внутри процесса, различна между процессами и запусками; если `/proc` недоступен —
 *    случайная метка процесса (уникальность между процессами и запусками сохраняется);
 *  • локальный порядковый хвост — если два события попали в ОДНУ миллисекунду, `rid`
 *    увеличивается до свободного значения (монотонность внутри процесса).
 *
 * Ключ события для дедупа — пара (rid, writer). Поэтому два процесса, случайно получившие
 * одинаковый `rid`, не считаются одним событием.
 *
 * Модуль чистый (без Android). Проверяется и JVM-тестом с ДВУМЯ экземплярами (эмуляция двух
 * процессов), и двумя РЕАЛЬНЫМИ java-процессами (`main` в `Iter64TwoProcessMain`).
 */
object EventKeyGen {

    /**
     * Метка писателя: 8 hex-символов.
     *
     * `pid-starttime-boot_id` → хеш. Метка короткая, потому что строку читает человек, а полный
     * набор (pid/starttime/boot) уже используется в именах spill/work-файлов
     * ([FileLogCore.buildTag]) и там остаётся.
     */
    fun writerTag(pid: String?, bootId: String?, startTime: String?, fallback: String): String {
        val full = when {
            pid != null && startTime != null && bootId != null -> "p$pid-s$startTime-b$bootId"
            pid != null && startTime != null -> "p$pid-s$startTime"
            else -> "u$fallback"
        }
        // FNV-1a 32 → 8 hex. Коллизия двух писателей в ОДНОЙ миллисекунде теоретически
        // возможна, поэтому ключ события включает и `rid`, и метку: даже совпавшая метка не
        // превращает две записи в одну, если различается `rid` (время или хвост).
        var h = -2128831035   // 2166136261 as signed int (FNV offset basis, без переполнения литерала)
        for (b in full.toByteArray(Charsets.UTF_8)) {
            h = h xor b.toInt()
            h *= 16777619
        }
        return String.format("%08x", h)
    }

    /**
     * Собрать `rid` из времени и писателя.
     *
     * Младшие 41 бит — время мс (хватит до ~2065 года), старшие 22 бита — хеш писателя.
     * Значение не зависит от «номера в процессе», поэтому два процесса в одну миллисекунду
     * получают разные `rid`, а новый запуск не повторяет прежние значения.
     */
    fun compose(tsMs: Long, writer: String): Long {
        val t = tsMs and 0x1FFFFFFFFFFL
        val w = (writer.hashCode().toLong() and 0x3FFFFFL)   // 22 бита
        return (w shl 41) or t
    }

    /**
     * Состояние ОДНОГО писателя (процесса): метка + защита от совпадения `rid` в одну мс.
     *
     * Экземпляр, а не объект-синглтон: тест может создать второй экземпляр с другим состоянием и
     * проверить сценарий «второй процесс» на том же production-коде, ничего не подменяя.
     */
    class State(val writer: String) {
        private val last = AtomicLong(0L)

        /**
         * Следующий `rid` для НОВОГО события.
         *
         * Если `compose` вернул значение не больше предыдущего (та же миллисекунда, перевод часов
         * назад), берётся `previous + 1`: монотонность внутри процесса сохраняется, а
         * уникальность между процессами обеспечивает метка писателя в ключе.
         */
        fun next(tsMs: Long): Long {
            val candidate = compose(tsMs, writer)
            while (true) {
                val prev = last.get()
                val value = if (candidate > prev) candidate else prev + 1
                if (last.compareAndSet(prev, value)) return value
            }
        }
    }

    /** Метка писателя ТЕКУЩЕГО процесса (одна на процесс, считается один раз). */
    val self: String by lazy {
        if (procDisabled()) {
            // iter65 §2: проверяемый fallback-путь. Раньше эта ветка была недостижима в тестах,
            // поэтому её «случайность» никто не измерял.
            randomTag()
        } else {
            val pid = FileLogCore.readPid()
            writerTag(pid, FileLogCore.readBootId(), FileLogCore.readStartTime(pid), randomTag())
        }
    }

    /**
     * iter65 §2.2 — считается ли `/proc` недоступным.
     *
     * Переключатель существует ровно для того, чтобы проверить fallback-метку тем же
     * production-кодом (без подмены объекта тестом). В бою он не выставляется.
     */
    fun procDisabled(): Boolean =
        runCatching { System.getProperty("voiceapp3.noProc") == "1" }.getOrDefault(false)

    /**
     * Fallback-метка писателя, когда `/proc` недоступен.
     *
     * iter65 §2 — ЧТО БЫЛО НЕ ТАК: прежняя версия склеивала `nanoTime xor identityHashCode xor
     * hashCode` в **Int** (~31 бит) и не включала pid. Именно эта метка работает ТАМ, где ничего
     * предсказать нельзя; два процесса, стартовавших одновременно на разных ядрах, могли получить
     * одинаковую метку, и при совпадении миллисекунды совпал бы ключ `(rid, writer)` — та же
     * потеря записи, которую закрывали в iter64.
     *
     * Теперь: 64 бита из `SecureRandom` + pid + `nanoTime`, до 16 hex (парсер `[0-9a-f]{1,16}`).
     * Метка стабильна внутри процесса — её кеширует `self` (`by lazy`).
     */
    fun randomTag(): String {
        val rnd = SECURE.nextLong()
        // pid берётся тем же способом, что и везде в этом пакете (`/proc/self`), БЕЗ
        // `ProcessHandle`: это Java 9 API, которого на Android нет — CI iter65 поймал ровно это
        // (`Unresolved reference 'ProcessHandle'` в `:131`). Недоступен `/proc` — pid = 0,
        // уникальность даёт 64-битный SecureRandom + nanoTime.
        val pid = FileLogCore.readPid()?.toLongOrNull() ?: 0L
        val nano = System.nanoTime()
        return java.lang.Long.toHexString(rnd xor (pid shl 32) xor nano)
    }

    /** Один генератор на процесс: `SecureRandom` инициализируется небесплатно. */
    private val SECURE: java.security.SecureRandom by lazy { java.security.SecureRandom() }

    /** Состояние текущего процесса (единственный экземпляр, которым пользуется боевая запись). */
    val selfState: State by lazy { State(self) }
}

/**
 * Разбор `/proc`/времени для [EventKeyGen]. Вынесен отдельно, чтобы тесты могли подставить
 * значения, не трогая production-объект.
 */
internal object WriterIdentity {

    /** Полный набор фактов о писателе (для отчёта и тестов). */
    fun describe(pid: String?, bootId: String?, startTime: String?): String =
        "pid=${pid ?: "none"} start=${startTime ?: "none"} boot=${bootId?.take(8) ?: "none"}"
}
