package com.example.voiceapp3.tools

/**
 * iter39 §3.1 — пассивный (read-only) путь транспорта для диагностики.
 *
 * Зачем отдельный класс: диагностика (`InternetDiag`, `JitMountDiag`) отправляет только читающие
 * команды, но их ПОЛНЫЙ путь через `AdbClient.execute` при таймауте/обрыве/отсутствии root
 * запускает переподключение канала, а после трёх неудач — `setprop ctl.restart adbd` и повтор
 * отложенных команд. Это изменение системы, которого диагностика делать не имеет права.
 *
 * Здесь вся ветвящаяся логика «что делать при событии транспорта» собрана в одном месте, без
 * единого Android-импорта, поэтому её можно прогнать на JVM с подставным I/O.
 *
 * Границы (важные):
 *  • у [Io] НЕТ методов восстановления/replay/перезапуска служб — пассивный путь физически не
 *    может их вызвать;
 *  • bootstrap не запускается: если канал ещё не поднимался, ответ `unavailable`
 *    (`reason=not_started`), а не неявный старт транспорта с побочными эффектами;
 *  • состояние канала НЕ меняется: обрыв/таймаут не переводят его в другой режим;
 *  • ошибки не маскируются: `unavailable`/`channel_not_ready`/`timeout` различаются.
 *
 * Это НЕ доказательство отсутствия изменений во всём приложении: обычные (не пассивные) команды
 * сохраняют прежнее поведение транспорта, включая восстановление канала.
 */
object AdbPassive {

    /** Код возврата «пассивный путь недоступен» (не путать с rc самой команды). */
    const val RC_UNAVAILABLE = 3

    /** Код возврата таймаута транспорта — как у `AdbClient.execute`. */
    const val RC_TIMEOUT = 137

    /** Состояние канала глазами пассивного пути. */
    enum class Channel { NOT_STARTED, BOOTING, ROOT_TCP, ROOT_UNIX, NO_ROOT_SH, PERM_FAIL }

    /**
     * Подставной/настоящий низкоуровневый I/O. Реализация живёт в `AdbClient`; тесты передают
     * свою. Методов восстановления здесь намеренно нет.
     */
    interface Io {
        fun channel(): Channel

        /** Команда по подтверждённому root-каналу (TCP 5555). */
        fun execRoot(cmd: String, timeoutMs: Int): Pair<Int, String>

        /** Команда по root-каналу через unix-сокет `adbd`. */
        fun execUnix(cmd: String, timeoutMs: Int): Pair<Int, String>

        /** Read-only команда через `Runtime.sh` (uid=1000), когда root-канала нет. */
        fun execShell(cmd: String, timeoutMs: Int): Pair<Int, String>
    }

    /** Итог пассивного вызова: rc, вывод и КАКОЙ путь использован (для честного лога). */
    data class Result(val rc: Int, val out: String, val path: String) {
        val ok: Boolean get() = rc == 0
        val unavailable: Boolean get() = rc == RC_UNAVAILABLE
        val timeout: Boolean get() = rc == RC_TIMEOUT
    }

    private fun un(why: String, path: String = "none") =
        Result(RC_UNAVAILABLE, "ERR_ADB_PASSIVE_UNAVAILABLE reason=$why", path)

    /**
     * Выполнить одну читающую команду без побочных эффектов транспорта.
     *
     * Ни при одной ветке не вызывается восстановление, replay, bootstrap, изменение ADB-свойств
     * или новое подключение: единственный «выход» при недоступности — честный `unavailable`.
     */
    fun run(cmd: String, timeoutMs: Int, io: Io): Result {
        val ch = try {
            io.channel()
        } catch (t: Throwable) {
            return un("channel_state:${t.javaClass.simpleName}")
        }
        return when (ch) {
            Channel.NOT_STARTED ->
                un("not_started")
            Channel.BOOTING ->
                un("channel_not_ready")
            Channel.PERM_FAIL ->
                un("root_unavailable")
            Channel.ROOT_TCP -> call(cmd, timeoutMs, "root_tcp") { io.execRoot(cmd, timeoutMs) }
            Channel.ROOT_UNIX -> call(cmd, timeoutMs, "root_unix") { io.execUnix(cmd, timeoutMs) }
            Channel.NO_ROOT_SH -> call(cmd, timeoutMs, "sh_uid1000") { io.execShell(cmd, timeoutMs) }
        }
    }

    private fun call(
        cmd: String,
        timeoutMs: Int,
        path: String,
        body: () -> Pair<Int, String>
    ): Result = try {
        val (rc, out) = body()
        when {
            // Таймаут транспорта: исход команды неизвестен, канал НЕ трогаем и не «лечим».
            rc == RC_TIMEOUT || out.contains("ERR_ADB_TIMEOUT") ->
                Result(RC_TIMEOUT, out.ifEmpty { "ERR_ADB_TIMEOUT after ${timeoutMs}ms" }, path)
            rc == RC_UNAVAILABLE || out.contains("ERR_ADB_PASSIVE_UNAVAILABLE") ->
                Result(RC_UNAVAILABLE, out, path)
            else -> Result(rc, out, path)
        }
    } catch (t: Throwable) {
        // Исключение I/O — тоже честный отказ, без попыток переподключения.
        Result(RC_UNAVAILABLE, "ERR_ADB_PASSIVE_IO ${t.javaClass.simpleName}: ${t.message ?: "-"}", path)
    }
}
