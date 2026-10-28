package com.example.voiceapp3.ui

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.voiceapp3.R
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.roundToInt

/**
 * Нативное сообщение над областью видео CarPlay / Android Auto (этап 9).
 *
 * Порт `_message()` из carplay_v2_screen.dart: четыре состояния — сервис
 * недоступен на ГУ → ошибка моста → подключение / ждём телефон.
 * Показывается, когда projection-вкладка активна и рендер не идёт
 * (нет attached или textureId). Пока рендер идёт, сообщения нет:
 * TextureView закрывает область.
 *
 * Данные: статусы `carplay.status` / `android_auto.status` и снимок
 * `projection.presence` — те же пуши, что раньше уходили в HTML (UiBridge.pushToUi).
 * Рамка: `projectionFrame` из JS — та же, что у видео.
 * Активация: `projection.activate` / `projection.deactivate` из JS.
 *
 * Стеки в вайре: "carplay" и "androidAuto".
 */
class ProjectionMessagePanel(private val activity: Activity) {

    private data class Status(
        val available: Boolean,
        val rendering: Boolean,
        val lastError: String?,
    )

    private val root: FrameLayout = activity.findViewById(R.id.projectionMessage)
    private val icon: TextView = activity.findViewById(R.id.projectionMessageIcon)
    private val title: TextView = activity.findViewById(R.id.projectionMessageTitle)
    private val sub: TextView = activity.findViewById(R.id.projectionMessageSub)

    /** Стек активной projection-вкладки; null — вкладки проекции нет. */
    private var activeStack: String? = null
    private val statuses = HashMap<String, Status>()
    private val connected = HashMap<String, Boolean>()

    /** Рамка видео активного стека, физические пиксели; null — ещё не измерена. */
    private var rectStack: String? = null
    private var rect: IntArray? = null

    init {
        root.visibility = View.GONE
    }

    /** Вкладка стека стала активной (projection.activate). */
    fun setActive(stack: String) {
        activeStack = stack
        refresh()
    }

    /** Вкладка стека ушла (projection.deactivate). */
    fun setInactive(stack: String) {
        if (activeStack == stack) activeStack = null
        refresh()
    }

    /** Рамка из projectionFrame (физические пиксели окна). */
    fun setRect(stack: String, x: Float, y: Float, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) {
            rect = null
        } else {
            rectStack = stack
            rect = intArrayOf(x.roundToInt(), y.roundToInt(), w.roundToInt(), h.roundToInt())
        }
        refresh()
    }

    /** Пуши из UiBridge.pushToUi: статусы стеков и снимок presence. */
    fun onEvent(name: String, json: String) {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        when (name) {
            "carplay.status" -> statuses["carplay"] = parseStatus(o)
            "android_auto.status" -> statuses["androidAuto"] = parseStatus(o)
            "projection.presence" -> {
                str(o, "carplay")?.let { connected["carplay"] = it == "connected" }
                str(o, "androidAuto")?.let { connected["androidAuto"] = it == "connected" }
            }
            else -> return
        }
        refresh()
    }

    private fun refresh() {
        val stack = activeStack
        val r = rect
        // Рамка относится к тому же стеку, что и активная вкладка — иначе не показываем.
        if (stack == null || r == null || rectStack != stack) {
            root.visibility = View.GONE
            return
        }
        // Отсутствующий статус = недоступен (как PRST = {} в прежнем JS).
        val st = statuses[stack] ?: Status(available = false, rendering = false, lastError = null)
        if (st.rendering) {
            root.visibility = View.GONE
            return
        }

        val isCp = stack == "carplay"
        val name = if (isCp) "Apple CarPlay" else "Android Auto"
        icon.text = if (isCp) "🍎" else "🤖"
        title.text = when {
            !st.available -> "$name на этом ГУ недоступен"
            st.lastError != null -> errorText(st.lastError)
            else -> "Подключение к телефону…"
        }
        sub.text = if (connected[stack] == true) {
            "Телефон подключён — экран появится с минуты на минуту"
        } else {
            "Подключите телефон, и его экран появится здесь"
        }

        val lp = root.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(0, 0)
        lp.width = r[2]
        lp.height = r[3]
        lp.leftMargin = r[0]
        lp.topMargin = r[1]
        root.layoutParams = lp
        root.visibility = View.VISIBLE
    }

    private fun parseStatus(o: JsonObject): Status {
        val rendering = bool(o, "attached") && o.get("textureId")?.takeIf { !it.isJsonNull } != null
        return Status(
            available = bool(o, "available"),
            rendering = rendering,
            lastError = str(o, "lastError"),
        )
    }

    private fun bool(o: JsonObject, key: String): Boolean =
        runCatching { o.get(key)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull() ?: false

    private fun str(o: JsonObject, key: String): String? =
        runCatching { o.get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()

    /** Тексты ошибок моста — как projErrMsg в прежнем HTML. */
    private fun errorText(code: String): String = when (code) {
        "BIND_FAILED", "SERVICE_UNAVAILABLE", "BINDING_DIED", "NULL_BINDING" -> "Сервис проекции недоступен"
        "ATTACH_FAILED", "DETACH_FAILED", "REMOTE_EXCEPTION" -> "Рендерер телефона не ответил"
        "INVALID_BUFFER_SIZE" -> "Неверный размер буфера"
        else -> "Ошибка проекции"
    }
}
