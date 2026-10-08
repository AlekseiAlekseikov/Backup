package com.example.voiceapp3.nav.routing

import android.util.Log
import com.example.voiceapp3.nav.NavPositionFeed
import com.example.voiceapp3.nav.search.SearchResult

/**
 * Этап 7: активная навигационная сессия поверх построенного RouteResult.
 * Чистая математика, без UI и потоков (вызывается из host.onFix на main):
 *  · проекция позиции на полилинию маршрута (сегмент за сегментом);
 *  · оставшееся расстояние по маршруту и масштабированное время (ЛИНЕЙНО
 *    от доли пути — честно подписано «без учёта трафика»);
 *  · ближайший предстоящий манёвр и дистанция до него;
 *  · off-route: позиция стабильно (OFFROUTE_HITS подряд) дальше
 *    OFFROUTE_M от линии → колбэк хосту на rerouting (движок локальный,
 *    пересчёт бесплатен и без квот).
 */
class RouteSessionController(
    val route: RouteResult,
    private val cb: Callback
) {

    interface Callback {
        /** Состояние для хрома ведения (null → финиш достигнут persistently). */
        fun onNavUpdate(info: NavUpdate)
        fun onOffRoute(snap: NavPositionFeed.PositionSnapshot)
        fun onArrived()
    }

    /** Всё, что нужно хрому ведения на каждый фикс. */
    data class NavUpdate(
        val arrowDeg: Float,
        val maneuverText: String,
        /** Метры до точки ближайшего манёвра (null — уже только финиш). */
        val distToManeuverM: Double?,
        /** Осталось по маршруту (метры). */
        val remainM: Double,
        /** Масштабированное оставшееся время (мс), честно «без трафика». */
        val remainMs: Long,
        /** Текущее смещение от линии маршрута (метры). */
        val offLineM: Double
    )

    private val pts = route.points
    private var bestSeg = 0
    private var offRouteStreak = 0
    private var rerouting = false
    private var arrived = false

    fun setRerouting(v: Boolean) { rerouting = v }

    fun onFix(snap: NavPositionFeed.PositionSnapshot) {
        if (arrived || pts.size < 2) return
        val lat = snap.latitude ?: return
        val lon = snap.longitude ?: return
        if (!snap.hasFix) return

        val proj = project(lat, lon) ?: return
        val remain = (pts.size - 1 - proj.seg).toDouble().let { segs ->
            var acc = 0.0
            // хвост текущего сегмента
            acc += SearchResult.distanceMeters(
                proj.onLine.lat, proj.onLine.lon,
                pts[proj.seg + 1].lat, pts[proj.seg + 1].lon)
            var i = proj.seg + 1
            while (i < pts.size - 1) {
                acc += SearchResult.distanceMeters(
                    pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
                i++
            }
            acc
        }
        val total = route.distanceM.coerceAtLeast(1.0)
        val remainMs = (route.timeMs * (remain / total)).toLong()

        // ближайший манёвр впереди (по нарастающей геометрии)
        var nextMan: Maneuver? = null
        var distToMan: Double? = null
        run {
            var accFromPos = SearchResult.distanceMeters(
                proj.onLine.lat, proj.onLine.lon,
                pts[proj.seg + 1].lat, pts[proj.seg + 1].lon)
            var i = proj.seg + 1
            val it = route.maneuvers
                .filter { m -> !m.text.startsWith("Прибудьте") && !m.text.contains("финиш", true) }
                .iterator()
            mans@ while (it.hasNext()) {
                val m = it.next()
                // найти ближайшую вершину манёвра от текущего сегмента вперёд
                var j = i
                while (j < pts.size) {
                    if (SearchResult.distanceMeters(
                            pts[j].lat, pts[j].lon, m.point.lat, m.point.lon) < 25.0) {
                        nextMan = m
                        var d = accFromPos
                        var k = i
                        while (k < j) {
                            d += SearchResult.distanceMeters(
                                pts[k].lat, pts[k].lon, pts[k + 1].lat, pts[k + 1].lon)
                            k++
                        }
                        distToMan = d
                        break@mans
                    }
                    j++
                }
            }
        }

        // off-route
        if (proj.offLineM > OFFROUTE_M && !rerouting) {
            offRouteStreak++
            if (offRouteStreak >= OFFROUTE_HITS) {
                offRouteStreak = 0
                rerouting = true
                Log.i(TAG, "off-route: ${proj.offLineM.toInt()}м от линии — пересчёт")
                cb.onOffRoute(snap)
            }
        } else if (proj.offLineM <= OFFROUTE_M) {
            offRouteStreak = 0
        }

        // финиш
        if (remain < ARRIVE_M) {
            arrived = true
            cb.onArrived()
            return
        }

        val man = nextMan
        cb.onNavUpdate(
            NavUpdate(
                arrowDeg = man?.arrowDeg ?: 0f,
                maneuverText = man?.text ?: "финиш впереди",
                distToManeuverM = distToMan,
                remainM = remain,
                remainMs = remainMs,
                offLineM = proj.offLineM
            )
        )
    }

    // ═══════════════════════════ проекция на маршрут ════

    private data class Projection(
        val seg: Int, val onLine: RoutePoint, val offLineM: Double
    )

    /** Ищем ближайший сегмент; поиск каждый раз с небольшого окна вокруг
     *  прошлого попадания (маршрут не прыгает назад), при провале — полный. */
    private fun project(lat: Double, lon: Double): Projection? {
        fun scan(from: Int, toExcl: Int): Projection? {
            var best: Projection? = null
            var i = from
            while (i < toExcl && i < pts.size - 1) {
                val p = projectOnSegment(lat, lon, pts[i], pts[i + 1], i)
                if (best == null || p.offLineM < best.offLineM) best = p
                i++
            }
            return best
        }
        val w0 = (bestSeg - 6).coerceAtLeast(0)
        val w1 = (bestSeg + 60).coerceAtMost(pts.size - 1)
        var p = scan(w0, w1)
        if (p == null || p.offLineM > WINDOW_ESCAPE_M) {
            p = scan(0, pts.size - 1) ?: return null
        }
        if (p.seg >= bestSeg) bestSeg = p.seg   // монотонность против дрожания
        return Projection(bestSeg, p.onLine, p.offLineM)
    }

    /** Точка на сегменте в equirectangular-приближении (малые дистанции). */
    private fun projectOnSegment(
        lat: Double, lon: Double, a: RoutePoint, b: RoutePoint, seg: Int
    ): Projection {
        val latScale = Math.cos(Math.toRadians(lat))
        val ax = a.lon * latScale; val ay = a.lat
        val bx = b.lon * latScale; val by = b.lat
        val px = lon * latScale; val py = lat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0
        else ((px - ax) * dx + (py - ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val ox = ax + dx * tc; val oy = ay + dy * tc
        val onLine = RoutePoint(oy, ox / latScale)
        val off = SearchResult.distanceMeters(py, px / latScale, oy, ox / latScale)
        return Projection(seg, onLine, off)
    }

    companion object {
        private const val TAG = "NavRouteSession"
        private const val OFFROUTE_M = 60.0
        private const val OFFROUTE_HITS = 5
        private const val ARRIVE_M = 35.0
        private const val WINDOW_ESCAPE_M = 80.0
    }
}
