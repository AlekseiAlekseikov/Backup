package com.example.voiceapp3.tools

/**
 * Управляемое «устройство» для сценариев, где shell живёт СВОЕЙ жизнью, а клиент (наш процесс)
 * может умереть и стартовать заново в любой момент.
 *
 * Что моделируется (REVIEW 3-го захода, §0.0 пп.1,2,4 и §4 мандат):
 *  • `AdbClient.execute` ставит задание в очередь и возвращает таймаут, НЕ отменяя работу
 *    (`AdbClient.kt:417-425`): [exec] при `answering=false` отдаёт `rc=137` и кладёт скрипт в
 *    [queued];
 *  • скрипт исполняется на «устройстве» шагами, которые управляются тестом: [stepWifiDisable],
 *    [stepRest],
 *  • сеть может измениться и БЕЗ нашего скрипта — [foreignAction] (пользователь/другой компонент);
 *  • смерть клиента моделируется просто тем, что тест перестаёт звать методы и создаёт новый
 *    разбор старта с тем же [JitFake.Store].
 *
 * Это НЕ граница реального adbd: реальный процесс/Android здесь не запускается (см. отчёт §4,
 * «что не доказано»).
 */
class JitLifecycleShell(
    private val net: JitFake.Net,
    var getprop: String = "false",
    var getpropRc: Int = 0,
    var cache: Boolean? = true
) : JitShell {

    /** Транспорт отвечает вызывающему (false — таймаут, задание остаётся в очереди). */
    var answering = true
    /** Уже отданные, но ещё не исполненные скрипты (очередь транспорта). */
    val queued = ArrayDeque<String>()
    /** Сколько скриптов транспорт реально принял к исполнению. */
    var accepted = 0
    /** Шаги, выполненные «на устройстве» (для проверки порядка и отсутствия лишнего). */
    val deviceSteps = mutableListOf<String>()

    override fun exec(command: String, timeoutMs: Int): Pair<Int, String> {
        if (command.startsWith("getprop ")) {
            return if (getpropRc == 0) Pair(0, getprop)
            else Pair(getpropRc, "ERR_ROOT_CHANNEL_UNAVAILABLE after ${timeoutMs}ms")
        }
        accepted++
        if (!answering) {
            queued.addLast(command)
            return Pair(137, "ERR_ADB_TIMEOUT after ${timeoutMs}ms")
        }
        // Ответ получен: считаем, что скрипт дошёл до последней команды.
        queued.clear()
        val (rc, out) = JitIsoStand(rules = 0).exec(command, getprop)
        deviceSteps += "whole_chain"
        return Pair(rc, out)
    }

    override fun cacheVisible(): Boolean? = cache

    /** Первая команда скрипта — как на устройстве. */
    fun stepWifiDisable(): Boolean {
        val script = queued.firstOrNull() ?: return false
        if (!script.contains("svc wifi disable")) return false
        deviceSteps += "svc wifi disable"
        net.wifi = false // радио действительно выключается
        return true
    }

    /** Остаток скрипта: слив правил, setprop и — последней командой — рестарт zygote. */
    fun stepRest(): Boolean {
        val script = queued.firstOrNull() ?: return false
        deviceSteps += "drain+setprop+zygote"
        queued.removeFirst()
        return script.contains("setprop ctl.restart zygote")
    }

    /** Сеть изменил кто-то другой, пока наш скрипт стоял в очереди. */
    fun foreignAction(wifi: Boolean? = null, ap: Boolean? = null) {
        wifi?.let { net.wifi = it }
        ap?.let { net.ap = it }
        deviceSteps += "foreign_action"
    }
}
