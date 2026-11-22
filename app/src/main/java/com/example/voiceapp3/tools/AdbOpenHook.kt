package com.example.voiceapp3.tools

/**
 * iter66 — одноразовый обработчик «adbd ответил `OKAY` на `OPEN shell:<команда>`».
 *
 * `OKAY` на `OPEN` означает: adbd создал shell-сервис и запустил в нём команду. Для цепочки JIT это
 * единственное доказательство доставки, которое успевает прийти: дальше `ctl.restart zygote`, и
 * процесс приложения погибает раньше ответа `CLSE`.
 *
 * Срабатывает ровно один раз и только для той команды, под которую взведён: служебные пробы
 * (`id` и т.п.) внутри той же задачи транспорта его не тратят. Исключение обработчика не
 * доходит до транспорта.
 */
class AdbOpenHook {
    private var armed: Pair<String, () -> Unit>? = null

    @Synchronized
    fun arm(command: String, hook: (() -> Unit)?) {
        armed = hook?.let { command to it }
    }

    @Synchronized
    fun disarm() {
        armed = null
    }

    /** Вызывается транспортом на `OKAY`. @return `true`, если обработчик сработал сейчас. */
    fun opened(command: String): Boolean {
        val hook = synchronized(this) {
            val h = armed ?: return false
            if (h.first != command) return false
            armed = null
            h.second
        }
        runCatching { hook() }
        return true
    }
}
