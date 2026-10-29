package com.example.voiceapp3.tools

import com.example.voiceapp3.BuildConfig
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.*

/**
 * BgWorker — общий фон-исполнитель для задач, не имеющих права жить на main.
 *
 * Пул из 4 daemon-потоков с именем "CENTRAL_Bg-N", чтобы в трассировках
 * и в тредах watchdog-а было понятно, чьи это задачи.
 *
 * Главный поток никогда не должен выполнять AdbClient/iptables/VHAL/сетевые
 * вызовы. Все такие места используют [BgWorker.post] / [BgWorker.run],
 * а перед опасным вызовом стучатся в [assertNotMain].
 */
object BgWorker {

    private const val TAG = "CENTRAL_BgWorker"
    private const val SLOW_TASK_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val executor: ExecutorService = ThreadPoolExecutor(
        4, 4, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { r ->
            Thread(r).apply {
                name = "CENTRAL_Bg-${threadNumber()}"
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    )

    private val threadSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private fun threadNumber() = threadSeq.incrementAndGet()

    /**
     * isMainThread — истина, если текущий поток — главный (UI) поток приложения.
     */
    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /**
     * assertNotMain — проверка, что вызывающий код работает НЕ на main.
     *
     * Вызывается в начале методов, которым не место на UI-потоке (AdbClient.execute,
     * syncAll, iptables/ip6tables, mount-bind, VHAL чтение).
     *
     * В debug бросает [IllegalStateException] (сразу видно стек вызовов),
     * в release просто пишет E-лог и не падает (защита от крэшей у пользователя).
     */
    fun assertNotMain(where: String) {
        if (!isMainThread()) return
        val stack = Throwable().stackTrace
            .drop(1)
            .firstOrNull { it.className.startsWith("com.example.voiceapp3") }
        val loc = stack?.let { " (${it.fileName}:${it.lineNumber})" } ?: ""
        val msg = "MAIN-THREAD BLOCKING CALLED at $where$loc — must run on BgWorker"
        if (BuildConfig.DEBUG) {
            throw IllegalStateException(msg)
        } else {
            Log.e(TAG, msg)
        }
    }

    /**
     * post — запускает [block] на фоновом пуле (fire-and-forget).
     *
     * Используется для fire-and-forget задач (тик watchdog-а, переключение тумблера
     * после клика в UI, первоначальная инициализация и т.п.).
     */
    fun post(name: String, block: () -> Unit) {
        executor.execute(wrap(name, block))
    }

    /**
     * run — запускает [block] на фоне и доставляет результат в [onResult] на main.
     *
     * @param name     читаемое имя задачи (для лога при долгом выполнении).
     * @param block    вычисление, выполняется в BgWorker-потоке.
     * @param onResult колбэк с результатом, ВСЕГДА вызывается на главном Looper.
     */
    fun <T> run(name: String, block: () -> T, onResult: (Result<T>) -> Unit) {
        executor.execute {
            val start = System.currentTimeMillis()
            try {
                val value = block()
                val dt = System.currentTimeMillis() - start
                if (dt > SLOW_TASK_MS) {
                    Log.w(TAG, "slow task '$name' took ${dt}ms")
                }
                mainHandler.post { onResult(Result.success(value)) }
            } catch (t: Throwable) {
                Log.e(TAG, "task '$name' failed: ${t.message}", t)
                mainHandler.post { onResult(Result.failure(t)) }
            }
        }
    }

    private fun wrap(name: String, block: () -> Unit): Runnable = Runnable {
        val start = System.currentTimeMillis()
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "task '$name' failed: ${t.message}", t)
        } finally {
            val dt = System.currentTimeMillis() - start
            if (dt > SLOW_TASK_MS) {
                Log.w(TAG, "slow task '$name' took ${dt}ms")
            }
        }
    }
}
