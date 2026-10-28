package com.example.voiceapp3.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Stage NAV-DESIGN (этап 5 дизайн-концепт): нативный рендер SVG-иконок
 * из присланного пользователем HTML-макета — пути взяты 1:1 из его SVG
 * (viewBox 24, stroke 1.9, linecap/linejoin round, stroke #DBE5F0 /
 * актив #FFFFFF), поэтому вид совпадает с макетом на любом шрифте.
 *
 * Использование: [fabIcon] отдаёт готовый Bitmap нужного вида и размера.
 * Битмапы генерируются подвремени в момент сборки chrome (не кэшм —
 * их единицы на весь экран).
 */
object ConceptIcons {

    enum class Kind { CROSSHAIR, ZOOM_IN, ZOOM_OUT, DOWNLOAD, SUN, MOON, AUTO, CLOSE,
        SEARCH, STAR, STAR_FILL, CLOCK, MAP_PIN, LAYERS, MIC, BACK, ARROW_UP }

    val DEFAULT_STROKE = 0xFFDBE5F0.toInt()
    val WHITE_STROKE = 0xFFFFFFFF.toInt()

    private const val VIEWBOX = 24f
    private const val STROKE_W = 1.9f

    /**
     * Иконка как в макете: box [boxDp] dp, внутренний размер иконки —
     * ~44 % бокса (равно соотношению 23/52 у .fab в макете).
     * Растр строится с 2x-oversampling'ом (ImageView уменьшит — без мыла).
     */
    fun fabIcon(
        context: Context,
        kind: Kind,
        color: Int = DEFAULT_STROKE,
        boxDp: Float = 52f
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val px = (boxDp * density * 2f).toInt().coerceAtLeast(32)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val inner = px * 23f / 52f
        val off = (px - inner) / 2f
        val s = inner / VIEWBOX
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = STROKE_W * s
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        canvas.translate(off, off)
        draw(canvas, kind, s, stroke, color)
        return bmp
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, s: Float, p: Paint) {
        c.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, p)
    }

    private fun circle(c: Canvas, cx: Float, cy: Float, r: Float, s: Float, p: Paint) {
        c.drawCircle(cx * s, cy * s, r * s, p)
    }

    private fun draw(c: Canvas, kind: Kind, s: Float, p: Paint, color: Int) {
        when (kind) {
            Kind.CROSSHAIR -> {
                // <path d="M12 2v3M12 19v3M2 12h3M19 12h3"/><circle cx="12" cy="12" r="5"/>
                line(c, 12f, 2f, 12f, 5f, s, p)
                line(c, 12f, 19f, 12f, 22f, s, p)
                line(c, 2f, 12f, 5f, 12f, s, p)
                line(c, 19f, 12f, 22f, 12f, s, p)
                circle(c, 12f, 12f, 5f, s, p)
            }
            Kind.ZOOM_IN -> {
                // <path d="M12 5v14M5 12h14"/>
                line(c, 12f, 5f, 12f, 19f, s, p)
                line(c, 5f, 12f, 19f, 12f, s, p)
            }
            Kind.ZOOM_OUT -> {
                // <path d="M5 12h14"/>
                line(c, 5f, 12f, 19f, 12f, s, p)
            }
            Kind.DOWNLOAD -> {
                // <path d="M12 3v11m0 0l-4-4m4 4l4-4M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2"/>
                line(c, 12f, 3f, 12f, 14f, s, p)          // рычага стрелки
                line(c, 12f, 14f, 8f, 10f, s, p)          // левое плечо
                line(c, 12f, 14f, 16f, 10f, s, p)         // правое плечо
                val tray = Path().apply {
                    moveTo(4f * s, 17f * s)
                    lineTo(4f * s, 19f * s)
                    // левый нижний закруглённый угол (SVG: a2 2 0 002 2)
                    arcTo(RectF(4f * s, 17f * s, 8f * s, 21f * s), 180f, -90f)
                    lineTo(18f * s, 21f * s)
                    // правый нижний угол (SVG: a2 2 0 002-2)
                    arcTo(RectF(16f * s, 17f * s, 20f * s, 21f * s), 90f, -90f)
                    lineTo(20f * s, 17f * s)
                }
                c.drawPath(tray, p)
            }
            Kind.SUN -> {
                // lucide sun: круг r4 + 8 лучей
                circle(c, 12f, 12f, 4f, s, p)
                line(c, 12f, 2f, 12f, 4f, s, p)
                line(c, 12f, 20f, 12f, 22f, s, p)
                line(c, 4.93f, 4.93f, 6.34f, 6.34f, s, p)
                line(c, 17.66f, 17.66f, 19.07f, 19.07f, s, p)
                line(c, 2f, 12f, 4f, 12f, s, p)
                line(c, 20f, 12f, 22f, 12f, s, p)
                line(c, 6.34f, 17.66f, 4.93f, 19.07f, s, p)
                line(c, 19.07f, 4.93f, 17.66f, 6.34f, s, p)
            }
            Kind.MOON -> {
                // полумесяц: большой круг r9 минус сместённый малый r7.5 (Path.Op)
                val big = Path().apply {
                    addCircle(12f * s, 12f * s, 9f * s, Path.Direction.CW)
                }
                val cut = Path().apply {
                    addCircle(15.5f * s, 11f * s, 7.5f * s, Path.Direction.CW)
                }
                big.op(big, cut, Path.Op.DIFFERENCE)
                c.drawPath(big, p)
            }
            Kind.AUTO -> {
                // круговая стрелка-обновление (rotate): дуга 300° + наконечник
                c.drawArc(RectF(3.5f * s, 3.5f * s, 20.5f * s, 20.5f * s), 40f, 280f, false, p)
                val tip = Path().apply {
                    // наконечник в конце дуги (~верх): треугольник хвоста стрелки
                    moveTo(12.6f * s, 3.6f * s)
                    lineTo(15.6f * s, 3.2f * s)
                    lineTo(13.8f * s, 6.0f * s)
                    close()
                }
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    this.color = color
                }
                c.drawPath(tip, fill)
            }
            Kind.CLOSE -> {
                // ✕ из макета (нейтральная замена системного глифа)
                line(c, 6f, 6f, 18f, 18f, s, p)
                line(c, 18f, 6f, 6f, 18f, s, p)
            }
            Kind.SEARCH -> {
                // lucide search: <circle cx=11 cy=11 r=7/><line 16.65,16.65→21,21/>
                circle(c, 11f, 11f, 7f, s, p)
                line(c, 16.65f, 16.65f, 21f, 21f, s, p)
            }
            Kind.STAR, Kind.STAR_FILL -> {
                // lucide star: M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88
                //              L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z
                val star = Path().apply {
                    moveTo(12f * s, 2f * s)
                    lineTo(15.09f * s, 8.26f * s)
                    lineTo(22f * s, 9.27f * s)
                    lineTo(17f * s, 14.14f * s)
                    lineTo(18.18f * s, 21.02f * s)
                    lineTo(12f * s, 17.77f * s)
                    lineTo(5.82f * s, 21.02f * s)
                    lineTo(7f * s, 14.14f * s)
                    lineTo(2f * s, 9.27f * s)
                    lineTo(8.91f * s, 8.26f * s)
                    close()
                }
                if (kind == Kind.STAR_FILL) {
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        this.color = color
                    }
                    c.drawPath(star, fill)
                } else {
                    c.drawPath(star, p)
                }
            }
            Kind.CLOCK -> {
                // lucide clock: <circle r=9/><polyline 12 6 12 12 16 14/>
                circle(c, 12f, 12f, 9f, s, p)
                line(c, 12f, 6f, 12f, 12f, s, p)
                line(c, 12f, 12f, 16f, 14f, s, p)
            }
            Kind.MAP_PIN -> {
                // lucide map-pin: капля r8 + внутренний круг r3
                val pin = Path().apply {
                    moveTo(4f * s, 10f * s)
                    arcTo(RectF(4f * s, 2f * s, 20f * s, 18f * s), 180f, -180f)
                    // две кривые к кончику (12,22): левая и правая стороны капли
                    cubicTo(20f * s, 16f * s, 15f * s, 20.5f * s, 12f * s, 22f * s)
                    cubicTo(9f * s, 20.5f * s, 4f * s, 16f * s, 4f * s, 10f * s)
                    close()
                }
                c.drawPath(pin, p)
                circle(c, 12f, 10f, 3f, s, p)
            }
            Kind.LAYERS -> {
                // lucide layers (макет navigator-app-7: 4-я фаба):
                // <path d="M12 3l9 5-9 5-9-5 9-5z"/><path d="M3 14l9 5 9-5"/>
                val top = Path().apply {
                    moveTo(12f * s, 3f * s)
                    lineTo(21f * s, 8f * s)
                    lineTo(12f * s, 13f * s)
                    lineTo(3f * s, 8f * s)
                    close()
                }
                c.drawPath(top, p)
                val bot = Path().apply {
                    moveTo(3f * s, 14f * s)
                    lineTo(12f * s, 19f * s)
                    lineTo(21f * s, 14f * s)
                }
                c.drawPath(bot, p)
            }
            Kind.MIC -> {
                // <rect x=9 y=3 w=6 h=11 rx=3/><path d="M5 11a7 7 0 0014 0M12 18v3"/>
                c.drawRoundRect(RectF(9f * s, 3f * s, 15f * s, 14f * s), 3f * s, 3f * s, p)
                c.drawArc(RectF(5f * s, 4f * s, 19f * s, 18f * s), 0f, 180f, false, p)
                line(c, 12f, 18f, 12f, 21f, s, p)
            }
            Kind.BACK -> {
                // chevron-left <path d="M15 5l-7 7 7 7"/>
                val ch = Path().apply {
                    moveTo(15f * s, 5f * s)
                    lineTo(8f * s, 12f * s)
                    lineTo(15f * s, 19f * s)
                }
                c.drawPath(ch, p)
            }
            Kind.ARROW_UP -> {
                // стрелка манёвра «вперёд» <path d="M12 21V5M6 11l6-6 6 6"/>
                line(c, 12f, 21f, 12f, 5f, s, p)
                val aw = Path().apply {
                    moveTo(6f * s, 11f * s)
                    lineTo(12f * s, 5f * s)
                    lineTo(18f * s, 11f * s)
                }
                c.drawPath(aw, p)
            }
        }
    }
}
