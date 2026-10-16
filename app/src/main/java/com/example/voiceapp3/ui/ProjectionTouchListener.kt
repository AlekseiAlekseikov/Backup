package com.example.voiceapp3.ui

import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import com.timhss.capyenergy.projection.ProjectionStack
import com.timhss.capyenergy.projection.ProjectionTouchAction
import com.timhss.capyenergy.projection.ProjectionTouchController
import com.timhss.capyenergy.projection.ProjectionTouchPointer
import com.timhss.capyenergy.projection.ProjectionTouchRequest

/**
 * Нативный эквивалент `projection_touch_surface.dart` из capy: видео-оверлей
 * (TextureView) с полной пересылкой касаний на телефон.
 *
 * Перенесённые гарантии виджета capy:
 *  - маппинг локального пространства View в пространство буфера — простое
 *    растяжение (texture box = картинка), по одной операции на ось;
 *    клamping — работа нативной политики, не эта;
 *  - CarPlay — один палец: DOWN/MOVE/UP/CANCEL с единственным поинтером
 *    (политика отбросит второй как SECOND_POINTER);
 *  - Android Auto — мультитач: карта живых пальцев `_live`, actionIndex =
 *    индекс id в порядке нажатия (ровно как `ProjectionTouchSurface`);
 *  - CANCEL пересылается, а не глотается: политика перепишет его в UP,
 *    который телефон и ждёт;
 *  - геометрия буфера приходит из render-статуса — те же числа, что ушли
 *    в `notifyMainSurfaceAttached` (два разных значения разнесли бы
 *    касание и картинку по разным местам).
 *
 * `enabled` следует за вкладкой: пока false, касания не уходят и жест
 * забывается.
 */
class ProjectionTouchListener(
    private val stack: ProjectionStack,
    private val controller: ProjectionTouchController,
    private val enabled: () -> Boolean,
) : View.OnTouchListener {

    @Volatile
    var bufferWidth: Int = 0

    @Volatile
    var bufferHeight: Int = 0

    /** Ids с пальцем внизу, в порядке нажатия (только Android Auto). */
    private val live = LinkedHashMap<Int, PointF>()

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (!enabled()) {
            // Жест брошен: локальная карта забывается, политику почистит
            // releaseGesture/unbind на стороне моста.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) live.clear()
            return false
        }

        val scaleX = if (view.width > 0) bufferWidth.toDouble() / view.width else 0.0
        val scaleY = if (view.height > 0) bufferHeight.toDouble() / view.height else 0.0
        val action = event.actionMasked

        if (stack != ProjectionStack.ANDROID_AUTO) {
            // Одиночный путь CarPlay: один палец, actionIndex всегда 0.
            val pointer = pointer(event, event.actionIndex, scaleX, scaleY)
            if (pointer != null) {
                controller.send(
                    ProjectionTouchRequest(
                        stack = stack,
                        action = action,
                        actionIndex = 0,
                        pointers = listOf(pointer),
                    )
                )
            }
            return true
        }

        // Android Auto: мультитач, как _onDown/_onMove/_onUp/_onCancel в Dart.
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                live[event.getPointerId(event.actionIndex)] = point(event, event.actionIndex)
                send(action, scaleX, scaleY, 0)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val id = event.getPointerId(event.actionIndex)
                live[id] = point(event, event.actionIndex)
                val index = indexOf(id)
                send(action, scaleX, scaleY, index)
            }

            MotionEvent.ACTION_MOVE -> {
                // MOVE для пальца без DOWN присоединяется к списку, а не
                // форкает второй жест — политика всё равно отказала бы
                // одиночному второму DOWN.
                for (i in 0 until event.pointerCount) {
                    live[event.getPointerId(i)] = point(event, i)
                }
                send(action, scaleX, scaleY, 0)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (!live.containsKey(id)) return true
                live[id] = point(event, event.actionIndex)
                val index = indexOf(id)
                send(action, scaleX, scaleY, index)
                live.remove(id)
            }

            MotionEvent.ACTION_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (!live.containsKey(id)) return true
                live[id] = point(event, event.actionIndex)
                send(action, scaleX, scaleY, if (live.size == 1) 0 else indexOf(id))
                live.clear()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (live.isEmpty()) return true
                for (i in 0 until event.pointerCount) {
                    live[event.getPointerId(i)] = point(event, i)
                }
                // CANCEL переписывается политикой в UP, поднимающий весь жест.
                send(ProjectionTouchAction.CANCEL, scaleX, scaleY, 0)
                live.clear()
            }
        }
        return true
    }

    /** Забыть жест без отправки (вкладка ушла/пауза). */
    fun reset() = live.clear()

    private fun send(action: Int, scaleX: Double, scaleY: Double, actionIndex: Int) {
        val pointers = live.entries.mapIndexed { _, (id, p) ->
            ProjectionTouchPointer(id, p.x * scaleX, p.y * scaleY)
        }
        if (pointers.isEmpty()) return
        controller.send(
            ProjectionTouchRequest(
                stack = stack,
                action = action,
                actionIndex = actionIndex,
                pointers = pointers,
            )
        )
    }

    private fun indexOf(id: Int): Int = live.keys.indexOf(id).coerceAtLeast(0)

    private fun point(event: MotionEvent, index: Int) =
        PointF(event.getX(index), event.getY(index))

    private fun pointer(
        event: MotionEvent,
        index: Int,
        scaleX: Double,
        scaleY: Double,
    ): ProjectionTouchPointer? {
        if (index >= event.pointerCount) return null
        return ProjectionTouchPointer(
            id = event.getPointerId(index),
            x = event.getX(index) * scaleX,
            y = event.getY(index) * scaleY,
        )
    }
}
