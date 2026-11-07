package com.example.voiceapp3.tools

/**
 * iter33j §4.5 — вычистка мусора из `persist.sys.hvacdigital.*`.
 *
 * Наследие 1.0.53: в эти свойства писались ЧИСЛА (`temp_ext=9.5`, `exttemp=9.5`, `batt=71`).
 * У референса там только флаги `0|1` (`w0/x7.java:137-147`), а `temp_ext` — вообще фантомный
 * ключ 1.0.52 (референс его не знает: `w0/ah.java:17`, `w0/u5.java:27` — только
 * `temp`/`batt`/`exttemp`).
 *
 * Правила (не расширять без мандата):
 *  • трогаем ТОЛЬКО `persist.sys.hvacdigital.*` — чужие persist-свойства не наши;
 *  • легаси-ключ `temp_ext` — НЕ удалять (iter33l §1.2: это имя из рабочего лога iter19,
 *    эксперимент `EXT_PROBE` ещё не решил, какое имя читает SystemUI) — только `0`, если мусор;
 *  • числовое значение в `temp`/`batt`/`exttemp` — в `0` (наш флаг-писатель поставит нужное
 *    значение при следующем изменении тумблера);
 *  • одноразово за процесс, всё в лог строками `PERSIST_CLEAN …`.
 */
internal object PersistCleaner {
    private const val TAG = "CENTRAL_PersistCleaner"

    /** Фантомные ключи прошлых версий: референс их не знает. */
    internal val LEGACY_PROPS = listOf("persist.sys.hvacdigital.temp_ext")

    /** Настоящие ключи референса — только флаги `0|1`. */
    internal val FLAG_PROPS = listOf(
        HvacDigitalProps.PROP_TEMP,
        HvacDigitalProps.PROP_BATT,
        HvacDigitalProps.PROP_EXT,
    )

    /** Значение считается «мусором», если оно не пустое и не `0`/`1`. */
    internal fun isGarbage(v: String): Boolean = v.isNotEmpty() && v != "0" && v != "1"

    @Volatile private var done = false

    /** Скрипт чистки (чистая функция — покрыт тестом `PersistCleanerTest`). */
    internal fun script(): String {
        val sb = StringBuilder()
        // iter33l §1.2: легаси-имя НЕ чистим в пусто — переводим мусор в `0` (эксперимент идёт).
        for (p in LEGACY_PROPS) {
            sb.append("V=\$(getprop $p); case \"\$V\" in 0|1|\"\") ;; *) ")
            sb.append("setprop $p 0 2>/dev/null; ")
            sb.append("echo \"PERSIST_CLEAN prop=$p was=\$V now=\$(getprop $p) reason=legacy_key_normalized\";; esac\n")
        }
        for (p in FLAG_PROPS) {
            sb.append("V=\$(getprop $p); case \"\$V\" in 0|1|\"\") ;; *) ")
            sb.append("setprop $p 0 2>/dev/null; ")
            sb.append("echo \"PERSIST_CLEAN prop=$p was=\$V now=\$(getprop $p) reason=numeric_not_flag\";; esac\n")
        }
        return sb.toString().trimEnd()
    }

    /** Один проход за процесс. Вызывать из фонового потока (App.onCreate → BgWorker). */
    fun cleanupOnce() {
        if (done) return
        done = true
        val (rc, out) = try {
            AdbClient.execute(script(), 12000)
        } catch (t: Throwable) {
            AppLog.w(TAG, "PERSIST_CLEAN failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        val lines = out.lineSequence().filter { it.contains("PERSIST_CLEAN") }.toList()
        if (lines.isEmpty()) {
            AppLog.i(TAG, "PERSIST_CLEAN none (все persist.sys.hvacdigital.* уже флаги или пусты) rc=$rc")
        } else {
            for (l in lines) AppLog.i(TAG, l.trim())
        }
    }

    /** Диагностика: текущие значения (read-only) — для отчёта и HEARTBEAT. */
    fun snapshot(): String {
        val props = LEGACY_PROPS + FLAG_PROPS
        val cmd = props.joinToString("; ") { "echo \"$it=\$(getprop $it)\"" }
        val out = try { AdbClient.execute(cmd, 8000).second } catch (t: Throwable) { "" }
        return out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }
}
