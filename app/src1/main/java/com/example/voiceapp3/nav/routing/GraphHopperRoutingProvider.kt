package com.example.voiceapp3.nav.routing

import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.util.FinishInstruction
import com.graphhopper.util.Instruction
import com.graphhopper.util.RoundaboutInstruction
import com.graphhopper.util.Translation
import com.graphhopper.util.ViaInstruction
import java.util.Locale

/**
 * Этап 7: on-device маршрутизация GraphHopper (Apache 2.0) поверх графа
 * GraphManager. Работает полностью без сети: маршрут, rerouting —
 * всё локально, quots не существует.
 *
 * Текст манёвров — официальный путь GH (getTurnDescription с переводом
 * движка, ru с фалбэком en). Стрелка — грубый класс по направлению знака
 * (левый семейный отрицательный, правый положительный; кольцо/финиш/вия —
 * по типу инструкции) — направление не искажается никогда.
 */
class GraphHopperRoutingProvider(
    private val graphs: GraphManager
) : RoutingProvider {

    override fun isReady(): Boolean = graphs.hopper != null

    @Throws(RoutingError::class)
    override fun route(from: RoutePoint, to: RoutePoint): RouteResult {
        // fix9: живой route работает под read-lock GraphManager — хоппер не
        // закроется (обновление/удаление) в середине расчёта
        return graphs.withRouteLock { routeLocked(from, to) }
    }

    @Throws(RoutingError::class)
    private fun routeLocked(from: RoutePoint, to: RoutePoint): RouteResult {
        val hopper = graphs.hopper
            ?: throw RoutingError.GraphNotReady("движок не загружен (откройте «Офлайн-маршрутизация»)")
        val tr: Translation = runCatching {
            hopper.translationMap.getWithFallBack(Locale("ru"))
        }.getOrElse {
            Log.w(TAG, "перевод ru недоступен, используем en", it)
            hopper.translationMap.getWithFallBack(Locale.ENGLISH)
        }
        val rsp = try {
            hopper.route(GHRequest(from.lat, from.lon, to.lat, to.lon)
                .setProfile(GraphManager.PROFILE_CAR))
        } catch (e: com.graphhopper.util.exceptions.PointNotFoundException) {
            throw RoutingError.NoRoute(
                "точка слишком далеко от дорог графа (#${e.pointIndex})")
        } catch (e: com.graphhopper.util.exceptions.ConnectionNotFoundException) {
            throw RoutingError.NoRoute("между точками нет пути по дорогам графа")
        } catch (t: Throwable) {
            throw RoutingError.Engine(t.message ?: t.javaClass.simpleName, t)
        }
        if (rsp.hasErrors()) {
            val e = rsp.errors.firstOrNull()
            throw RoutingError.NoRoute(e?.message ?: "движок не ответил")
        }
        val best = rsp.best
        if (best.points.size() < 2)
            throw RoutingError.NoRoute("пустая геометрия (точки вне дорожного графа?)")

        val pts = best.points.let { pl ->
            (0 until pl.size()).map { RoutePoint(pl.getLat(it), pl.getLon(it)) }
        }
        val maneuvers = best.instructions.mapNotNull { ins -> toManeuver(ins, tr) }
        return RouteResult(
            points = pts,
            distanceM = best.distance,
            timeMs = best.time,
            maneuvers = maneuvers,
            alternativesCount = (rsp.all.size - 1).coerceAtLeast(0)
        )
    }

    override fun close() { /* граф у GraphManager; тут состояния нет */ }

    // ═══════════════════════════ конвертация ════

    private fun toManeuver(ins: Instruction, tr: Translation): Maneuver? {
        // финиш — особая инструкция без имени улицы; отдельный вид
        val sign = ins.sign
        val text = runCatching { ins.getTurnDescription(tr) }
            .getOrDefault(ins.name ?: "")
        val arrow: Float = when (ins) {
            is RoundaboutInstruction -> 0f                    // кольцо: стрелка вверх
            is FinishInstruction -> 0f                        // финиш: флажок в тексте
            is ViaInstruction -> 0f
            else -> when {
                sign == Instruction.CONTINUE_ON_STREET -> 0f
                sign == Instruction.U_TURN_LEFT ||
                    sign == Instruction.U_TURN_UNKNOWN -> -150f
                sign == Instruction.U_TURN_RIGHT -> 150f
                sign < 0 -> -90f                              // левое семейство
                else -> 90f                                   // правое семейство
            }
        }
        // точка привязки — первый пункт геометрии инструкции
        val pl = ins.points
        if (pl.size() == 0) return null
        return Maneuver(
            arrowDeg = arrow,
            text = if (text.isBlank()) "манёвр" else text,
            point = RoutePoint(pl.getLat(0), pl.getLon(0)),
            legDistanceM = ins.distance
        )
    }

    companion object { private const val TAG = "NavRouting" }
}
