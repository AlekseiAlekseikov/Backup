package com.example.voiceapp3.nav.offline

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.StatFs
import android.view.MotionEvent
import android.view.animation.PathInterpolator
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.voiceapp3.nav.NavTheme
import java.util.Locale

/**
 * Stage NAV-OFFLINE (этап 5): нижняя панель «Офлайн-карты».
 *
 * Каталог регионов → состояния из OfflineMapManager → действия
 * скачать/пауза/продолжить/удалить. Честные подписи: размеры строк —
 * грубые оценки (значимы только порядки), реальный прогресс — по
 * счётчикам OfflineManager (ресурсы + байты + тайлы).
 *
 * Размер и цвет — из выбранной темы шелла (NavTheme): панель ←
 * card/page1, текст ← ink/muted, границы ← line, акценты ← accent1/2.
 * Пересоздание экземпляра на смену темы выполняет хост — этот класс сам
 * цвета не перечитывает.
 *
 * Дизайн/токены — 1:1 по HTML-макету этапа 5. Обновление — подписка
 * менеджера + второй тикер, пока панель видима.
 */
class OfflineSheetController(
    private val activity: Activity,
    private val manager: OfflineMapManager,
    private val density: Float,
    private val theme: NavTheme
) {

    /** Корневой view панели (FrameLayout LP: MATCH_PARENT по ширине, низ). */
    val view: LinearLayout

    private val main = Handler(Looper.getMainLooper())
    private val rowsContainer = LinearLayout(activity)
    private var storageText: TextView? = null
    private var storageFill: View? = null
    private var storageSpacer: View? = null
    private var states: Map<String, OfflineMapManager.PackState> = emptyMap()
    private var tickerRunning = false

    /** true = шторка выезжает/на экране (хост им затеняет фон, как backdrop-blur макета). */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    /** Лок альфы анимации открытия/закрытия (шторка движется только этим путём). */
    private var slideAnimating = false

    private val stateListener: (Map<String, OfflineMapManager.PackState>) -> Unit = { s ->
        states = s
        rebuildRows()
    }

    init {
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.sheetTop, theme.sheetBot)
            ).apply {
                // макет .sheet{border-radius:26px} — плавающая карта над доком
                cornerRadius = dpf(26f)
                setStroke(dp(1), theme.borderHairline)
            }
            isClickable = true   // пустые тапы внутри карты не улетают на карту
            visibility = View.GONE

            // ── ручка панели (макет .grip: 46×5, radius 99) ──
            addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(5)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(10); bottomMargin = dp(12)
                }
                background = GradientDrawable().apply {
                    setColor(theme.gripColor)
                    cornerRadius = dpf(99f)
                }
            })

            // ── шапка: [h2 + p] и ЕДИНСТВЕННАЯ кнопка ✕ сверху справа ──
            addView(buildHeader())

            // ── честный индикатор хранилища (концепт этапа 5; очистки кэша нет) ──
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(theme.storageBg); cornerRadius = dpf(16f)
                    setStroke(dp(1), theme.storageEdge)
                }
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }

                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    addView(text("Хранилище", 13.5f, bold = true, color = theme.ink))
                    storageText = text("", 12f, color = theme.textSecondary)
                    addView(storageText!!.apply { setPadding(0, dp(2), 0, 0) })
                })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = 1f
                    layoutParams = LinearLayout.LayoutParams(
                        0, dp(7), 1f
                    ).apply { marginStart = dp(16) }
                    background = GradientDrawable().apply {
                        setColor(theme.track); cornerRadius = dpf(99f)
                    }
                    storageFill = View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f)
                        background = GradientDrawable(
                            GradientDrawable.Orientation.LEFT_RIGHT,
                            intArrayOf(theme.accent1, theme.accent2)
                        ).apply { cornerRadius = dpf(99f) }
                    }
                    storageSpacer = View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    }
                    addView(storageFill!!)
                    addView(storageSpacer!!)
                })
            })

            addView(ScrollView(activity).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                rowsContainer.orientation = LinearLayout.VERTICAL
                addView(rowsContainer)
            })
        }
    }

    // ═══════════════════════════════════ шапка ════

    /**
     * Единственный блок закрытия окна: одна кнопка ✕ (34dp, круг tint(ink,6%),
     * кайма --line, tint --muted), обработчик — hide(). Никаких CSS и скрытых
     * дублей: элемент создаётся ровно в одном месте этого класса.
     */
    private fun buildHeader(): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = dp(16) }
                addView(text("Офлайн-карты", 20f, bold = true, color = theme.ink).apply {
                    letterSpacing = -0.015f   // макет h2 letter-spacing:-.3px
                })
                addView(text(
                    "Глубина пакетов ~ z14 · размеры — честные грубые оценки · " +
                        "качается по одному региону за тап, можно поставить на паузу. " +
                        "Беларусь — базовый пакет: без него и без сети карта пуста.",
                    12.5f, color = theme.textSecondary
                ).apply { setPadding(0, dp(6), 0, dp(10)) })
            })

            addView(ImageView(activity).apply {
                // ✕ 34×34 — нативный SVG-рендер в цвете --muted
                setImageBitmap(
                    com.example.voiceapp3.nav.ConceptIcons.fabIcon(
                        activity,
                        com.example.voiceapp3.nav.ConceptIcons.Kind.CLOSE,
                        theme.textSecondary,
                        boxDp = 34f
                    )
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = GradientDrawable().apply {
                    setColor(theme.chipBg); cornerRadius = dpf(17f)
                    setStroke(dp(1), theme.borderHairline)
                }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                contentDescription = "Закрыть"
                setOnClickListener { hide() }
            })
        }

    /** Шторка выезда, как в макете: .sheet{transition:transform .45s cubic-bezier(.2,.9,.25,1)} */
    fun isShown(): Boolean = view.visibility == View.VISIBLE

    fun show() {
        if (slideAnimating || isShown()) return
        slideAnimating = true
        manager.addStateListener(stateListener)
        manager.forceRefresh()
        startTicker()
        onVisibilityChanged?.invoke(true)
        val h = sheetHeightPx().toFloat()
        view.visibility = View.VISIBLE
        view.translationY = h * OPEN_FROM_FRACTION
        view.animate().cancel()
        view.animate()
            .translationY(0f)
            .setDuration(OPEN_CLOSE_MS)
            .setInterpolator(SHEET_EASE)
            .withEndAction { slideAnimating = false }
            .start()
    }

    fun hide() {
        if (!isShown()) return
        slideAnimating = false
        view.animate().cancel()
        manager.removeStateListener(stateListener)
        tickerRunning = false
        main.removeCallbacksAndMessages(TICKER_TOKEN)
        onVisibilityChanged?.invoke(false)
        val h = sheetHeightPx().toFloat()
        view.animate()
            .translationY(h * OPEN_FROM_FRACTION)
            .setDuration(OPEN_CLOSE_MS)
            .setInterpolator(SHEET_EASE)
            .withEndAction {
                view.visibility = View.GONE
                view.translationY = 0f
            }
            .start()
    }

    /** Высота полотна шторки: берём из зафиксированного LP хоста, иначе эвристика 55%. */
    private fun sheetHeightPx(): Int {
        val h = view.layoutParams?.height ?: 0
        return if (h > 0) h else (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
    }

    fun toggle() = if (isShown()) hide() else show()

    fun release() = hide()

    /** Тикер прогресса пока панель открыта (события listStates приходят не чаще). */
    private fun startTicker() {
        tickerRunning = true
        main.postDelayed(ticker, TICKER_TOKEN, TICKER_MS)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!tickerRunning) return
            manager.forceRefresh()
            main.postDelayed(this, TICKER_TOKEN, TICKER_MS)
        }
    }

    // ═══════════════════════════════════ строки каталога ════

    private fun rebuildRows() {
        val was = rowsContainer.childCount
        rowsContainer.removeAllViews()
        var currentGroup: OfflineRegionsCatalog.Group? = null
        OfflineRegionsCatalog.REGIONS.forEach { def ->
            if (def.group != currentGroup) {
                currentGroup = def.group
                rowsContainer.addView(
                    text(def.group.title.uppercase(Locale.ROOT), 11f, bold = true, color = theme.accent1)
                        .apply {
                            letterSpacing = 0.16f
                            setPadding(dp(2), dp(14), 0, dp(2))
                        }
                )
            }
            rowsContainer.addView(row(def))
        }
        if (was == 0) rowsContainer.requestLayout()
        updateStorage()
    }

    /** Честный подсчёт: занято = сумма байт завершённых ресурсов всех пакетов,
     * свободно = StatFs по разделу данных. Заливка = занято из (занято+свободно). */
    private fun updateStorage() {
        val used = states.values.sumOf { it.doneBytes }
        val free = runCatching {
            android.os.StatFs(activity.filesDir.path).availableBytes
        }.getOrDefault(0L)
        storageText?.text = "занято ${fmtBytes(used)} · свободно ${fmtBytes(free)}"
        val frac = if (used + free > 0) used.toFloat() / (used + free).toFloat() else 0f
        (storageFill?.layoutParams as? LinearLayout.LayoutParams)?.weight = frac.coerceIn(0f, 1f)
        (storageSpacer?.layoutParams as? LinearLayout.LayoutParams)?.weight = (1f - frac).coerceIn(0f, 1f)
        storageFill?.requestLayout()
    }

    private fun row(def: OfflineRegionsCatalog.RegionDef): View {
        val st = states[def.id]
        val base = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(theme.rowBg); cornerRadius = dpf(18f)
                setStroke(dp(1), theme.borderHairline)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        // пиктограмма региона в мягком квадрате (концепт этапа 5)
        base.addView(TextView(activity).apply {
            text = flagEmojiFor(def.id)
            textSize = 17f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(theme.chipBg); cornerRadius = dpf(13f)
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        })

        val left = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // имя + чипы состояния в одну строку
        val nameRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // .row .nm{font-weight:600} макета
        nameRow.addView(text(def.name, 15f, bold = true, color = theme.ink))
        if (def.id == OfflineRegionsCatalog.RECOMMENDED_ID) {
            nameRow.addView(tagChip("обязательный", WARN))
        }
        if (st?.phase == OfflineMapManager.Phase.DONE) {
            nameRow.addView(tagChip("загружено", TEXT_OK))
            // метка «обновлено …» ставится ОДИН раз при первом DONE наблюдения —
            // без сетевых перевыдумок: это просто честное «когда пакет доехал».
            markDoneTimestamp(def.id)
        }
        left.addView(nameRow)
        left.addView(text(statusLine(def, st), 12.5f, color = statusColor(st))
            .apply { setPadding(0, dp(3), 0, 0) })

        // макет: мини-бар виден и у «загрузка…», и у «пауза …» (статус морожен);
        // процент считается из БАЙТОВ карточки («322 МБ из 980 МБ» → done/total)
        val pct = progress01For(def, st)
        if (pct != null &&
            (st?.phase == OfflineMapManager.Phase.DOWNLOADING ||
                st?.phase == OfflineMapManager.Phase.PAUSED)
        ) {
            left.addView(progressBar(pct))
        }

        base.addView(left)
        val btn = when (st?.phase) {
            OfflineMapManager.Phase.NOT_STARTED,
            null -> ghostButton(actionLabel(st), theme.ink)
            OfflineMapManager.Phase.PAUSED -> filledButton(actionLabel(st))
            OfflineMapManager.Phase.DOWNLOADING -> ghostButton(actionLabel(st), theme.accent1)
            OfflineMapManager.Phase.DONE -> ghostButton(actionLabel(st), theme.ink)
        }
        base.addView(btn.apply {
            minimumWidth = dp(96)
            minimumHeight = dp(36)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            setOnClickListener { onAction(def, st) }
        })
        // «Удалить» (✕) рядом с КАЖДЫМ скачанным/качающимся регионом:
        // загрузка/пауза/DONE — везде (требование: кнопка удаления у каждого
        // загруженного региона; у DONE главный слот теперь у «Обновить»).
        if (st?.phase == OfflineMapManager.Phase.PAUSED ||
            st?.phase == OfflineMapManager.Phase.DOWNLOADING ||
            st?.phase == OfflineMapManager.Phase.DONE
        ) {
            base.addView(TextView(activity).apply {
                text = "✕"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT_ERR)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(0x24E5484D.toInt())
                    cornerRadius = dpf(13f)
                    setStroke(dp(1), 0x4DE5484D.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(36)).apply { marginStart = dp(8) }
                contentDescription = "Удалить «" + def.name + "»"
                attachPressFeedback(this)
                setOnClickListener { confirmDelete(def, st) }
            })
        }
        return base
    }

    /** Маленький чип-тег справа от имени региона («обязательный»/«загружено»). */
    private fun tagChip(label: String, color: Int): TextView =
        TextView(activity).apply {
            text = label
            textSize = 11f   // .tag{font-size:11px}
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply {
                setColor((color and 0x00FFFFFF) or 0x1A000000.toInt())
                cornerRadius = dpf(7f)
                setStroke(dp(1), (color and 0x00FFFFFF) or 0x4D000000.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }

    /** Флаг-emoji по id региона (двухбуквенный ISO → региональные индикаторы). */
    private fun flagEmojiFor(id: String): String {
        if (id.length != 2) return "📍"   // у нас «ru-*» — регион РФ
        val up = id.uppercase(Locale.ROOT)
        val r0 = Character.toChars(0x1F1E6 + (up[0] - 'A'))
        val r1 = Character.toChars(0x1F1E6 + (up[1] - 'A'))
        return String(r0) + String(r1)
    }

    /** Байты → человеческие «322 МБ»/«3,4 ГБ». */
    private fun fmtBytes(b: Long): String = when {
        b >= 1_000_000_000L -> String.format(Locale.US, "%.1f", b / 1e9).replace('.', ',') + " ГБ"
        b >= 1_000_000L -> (b / 1_000_000L).toString() + " МБ"
        b >= 1_000L -> (b / 1_000L).toString() + " КБ"
        else -> "$b Б"
    }

    /** Отклик нажатия (макет .fab:hover/-1px): лёгкий scale 0.94 → 1 на 180 мс. */
    internal fun attachPressFeedback(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f)
                    .setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
    }

    /** Тонкая двухчастная полоска прогресса: акцентная заливка + трек темы. */
    private fun progressBar(pct: Float): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5)
            ).apply { topMargin = dp(9) }
            background = GradientDrawable().apply {
                setColor(theme.track); cornerRadius = dpf(99f)
            }
            addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                    pct.coerceIn(0f, 1f))
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(theme.accent1, theme.accent2)
                ).apply { cornerRadius = dpf(99f) }
            })
            addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, (1f - pct).coerceIn(0f, 1f)
                )
            })
        }

    private fun onAction(def: OfflineRegionsCatalog.RegionDef, st: OfflineMapManager.PackState?) {
        when (st?.phase ?: OfflineMapManager.Phase.NOT_STARTED) {
            OfflineMapManager.Phase.NOT_STARTED,
            OfflineMapManager.Phase.PAUSED -> manager.download(def)
            OfflineMapManager.Phase.DOWNLOADING -> manager.pause(def.id)
            // «Обновить» (макет): честная перекачка — удалить+скачать под сквозным
            // подтверждением: актуальные тайлы движок возьмёт с tile-сервера заново.
            OfflineMapManager.Phase.DONE -> confirmRefresh(def)
        }
    }

    /**
     * Кнопка «Удалить» у строки региона: подтверждение; если пакет качается —
     * manager.delete сам сначала ставит INACTIVE (остановка), потом удаляет.
     * Базовый (рекомендованный) пакет защищён ДВОЙНЫМ подтверждением — его
     * удаление ломает офлайн-покрытие домашнего региона.
     */
    private fun confirmDelete(def: OfflineRegionsCatalog.RegionDef, st: OfflineMapManager.PackState?) {
        val downloading = st?.phase == OfflineMapManager.Phase.DOWNLOADING
        var msg = "Офлайн-пакет уйдёт из памяти; без сети регион исчезнет с карты."
        if (downloading) msg = "Загрузку остановим, потом удалим скачанные данные. " + msg
        val title = "Удалить «" + def.name + "»?"
        if (def.id == OfflineRegionsCatalog.RECOMMENDED_ID) {
            AlertDialog.Builder(activity)
                .setTitle("«" + def.name + "» — базовый пакет")
                .setMessage("Это рекомендованный представлением ГУ пакет: без него карта домашнего региона исчезнет без сети. Продолжить удаление?")
                .setPositiveButton("Продолжить") { _, _ ->
                    AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setMessage(msg + "\n\nПоследний шаг — без отмены.")
                        .setPositiveButton("Удалить навсегда") { _, _ -> doDelete(def.id) }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("Удалить") { _, _ -> doDelete(def.id) }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun doDelete(id: String) {
        manager.delete(id)
        // замороженный полный размер и метка «обновлено» уходят вместе с пакетом
        activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_TOTAL_PREFIX + id)
            .remove(PREF_DONE_TS_PREFIX + id)
            .apply()
    }

    /** «Обновить» у загруженного: подтверждение, короткое честное описание. */
    private fun confirmRefresh(def: OfflineRegionsCatalog.RegionDef) {
        AlertDialog.Builder(activity)
            .setTitle("Обновить «" + def.name + "»?")
            .setMessage("Пакет будет перекачан целиком с tile-сервера: нужна сеть и тот же объём трафика, что у первой загрузки.")
            .setPositiveButton("Обновить") { _, _ ->
                manager.refresh(def)
                activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .remove(PREF_TOTAL_PREFIX + def.id)
                    .remove(PREF_DONE_TS_PREFIX + def.id)
                    .apply()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ═══════════════════════ «обновлено …» дата пакета ════

    /** Записать момент достижения DONE (однократно, до очистки/перекачки). */
    private fun markDoneTimestamp(id: String) {
        val prefs = activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (prefs.getLong(PREF_DONE_TS_PREFIX + id, 0L) == 0L) {
            prefs.edit().putLong(PREF_DONE_TS_PREFIX + id, System.currentTimeMillis()).apply()
        }
    }

    /** « · обновлено вчера» / « · обновлено 12 дн. назад» — суффикс подписи DONE. */
    private fun doneAgoSuffix(id: String): String {
        val ts = activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getLong(PREF_DONE_TS_PREFIX + id, 0L)
        if (ts == 0L) return ""
        val days = ((System.currentTimeMillis() - ts) / 86_400_000L).toInt()
        val word = when {
            days == 0 -> return " · обновлено сегодня"
            days == 1 -> return " · обновлено вчера"
            days % 10 == 1 && days % 100 != 11 -> "день"
            days % 10 in 2..4 && days % 100 !in 12..14 -> "дня"
            else -> "дней"
        }
        return " · обновлено $days $word назад"
    }

    private fun statusLine(def: OfflineRegionsCatalog.RegionDef, st: OfflineMapManager.PackState?): String {
        if (st == null || st.phase == OfflineMapManager.Phase.NOT_STARTED) {
            // без знака «≈» в любых состояниях (требование UX)
            return "не скачан · " + def.sizeHint.removePrefix("≈")
        }
        val mb = String.format(Locale.US, "%.0f", st.doneBytes / 1_000_000.0)
        val total = totalBytesFor(def, st)
        return when (st.phase) {
            OfflineMapManager.Phase.DOWNLOADING -> {
                if (st.totalResources == 0L) "подготовка загрузки…"
                else "загрузка · $mb МБ из ${fmtTotal(total)}"
            }
            // «пауза · 322 МБ из 980 МБ» — скачанное из полного размера
            OfflineMapManager.Phase.PAUSED ->
                "пауза · $mb МБ из ${fmtTotal(total)}" +
                    (st.errorText?.let { " · ошибка: $it" } ?: "")
            // завершено: «980 МБ из 980 МБ · обновлено N дн. назад» — под прах
            // зафиксируется факт; метка времени честная (только из prefs, без фейка)
            OfflineMapManager.Phase.DONE -> "скачан · $mb МБ из ${fmtTotal(total)}" +
                doneAgoSuffix(def.id) +
                (st.errorText?.let { " · ошибка: $it" } ?: "")
            OfflineMapManager.Phase.NOT_STARTED -> "не скачан · " + def.sizeHint.removePrefix("≈")
        }
    }

    /**
     * ПОЛНЫЙ размер файла в байтах — постоянный на всём пути загрузки:
     *  · при завершении: фабричное значение = реальные байты пакета
     *    (источник истины на 100%, «980 МБ из 980 МБ»);
     *  · в процессе: ОДНОКРАТНО замороженная оценка done/progress
     *    (после первого надёжного тика прогресса она не пересчитывается и
     *    поэтому НЕ уменьшается с ростом загрузки; prefs «voiceapp3»);
     *  · до первого тика: верхняя граница честной каталожной оценки.
     */
    private fun totalBytesFor(
        def: OfflineRegionsCatalog.RegionDef,
        st: OfflineMapManager.PackState?
    ): Long? {
        val prefs = activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val key = PREF_TOTAL_PREFIX + def.id
        var frozen = prefs.getLong(key, 0L).takeIf { it > 0 }

        when (st?.phase) {
            OfflineMapManager.Phase.DONE -> {
                // завершили: фактический размер становится окончательной правдой
                if (st.doneBytes > 0 && frozen != st.doneBytes) {
                    frozen = st.doneBytes
                    prefs.edit().putLong(key, st.doneBytes).apply()
                }
                return frozen
            }
            else -> Unit
        }
        if (frozen == null) {
            val pct = st?.progress01
            if (pct != null && pct > 0.02f && st.doneBytes > 0) {
                frozen = (st.doneBytes / pct.toDouble()).toLong()
                prefs.edit().putLong(key, frozen).apply()
            }
        }
        return frozen ?: parseUpperBoundBytes(def.sizeHint)
    }

    /** Мегапол-байтор «980 МБ» к нефиксированному null (краткая строка). */
    private fun fmtTotal(total: Long?): String =
        total?.let { fmtBytes(it) } ?: "…"

    /** «≈0,5–1 ГБ»/«≈100–350 МБ» → верхняя граница в байтах (константно). */
    private fun parseUpperBoundBytes(hint: String): Long? {
        val unit = if (hint.contains("ГБ")) 1_000_000_000.0 else 1_000_000.0
        val nums = Regex("[0-9]+(?:[.,][0-9]+)?").findAll(hint)
            .map { it.value.replace(',', '.').toDoubleOrNull() }
            .filterNotNull().toList()
        val upper = nums.maxOrNull() ?: return null
        return (upper * unit).toLong()
    }

    /** Полоса = скачано ÷ ПОЛНЫЙ размер (×100% — в LinearLayout weights). */
    private fun progress01For(
        def: OfflineRegionsCatalog.RegionDef,
        st: OfflineMapManager.PackState?
    ): Float? {
        st ?: return null
        val total = totalBytesFor(def, st)
        if (total != null && total > 0) {
            return (st.doneBytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        }
        return st.progress01
    }

    private fun statusColor(st: OfflineMapManager.PackState?): Int = when (st?.phase) {
        OfflineMapManager.Phase.DOWNLOADING -> theme.accent1
        OfflineMapManager.Phase.DONE -> TEXT_OK
        OfflineMapManager.Phase.PAUSED -> if (st.errorText != null) TEXT_ERR else theme.textSecondary
        else -> theme.textSecondary
    }

    private fun actionLabel(st: OfflineMapManager.PackState?): String = when (st?.phase) {
        OfflineMapManager.Phase.DOWNLOADING -> "Пауза"
        OfflineMapManager.Phase.PAUSED -> "Продолжить"
        // макет navigator-app-7: у загруженного — «Обновить» (перекачать),
        // удаление — отдельная ✕ рядом
        OfflineMapManager.Phase.DONE -> "Обновить"
        else -> "Скачать"
    }

    // ═══════════════════════════════════ фабрики ════

    private fun text(s: String, sizeSp: Float, bold: Boolean = false, color: Int): TextView =
        TextView(activity).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** Залитая кнопка темы (как .btn шелла): bg btn → тень btn, текст onbtn. */
    private fun filledButton(label: String): TextView =
        TextView(activity).apply {
            text = label
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(theme.onbtn)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.btn, theme.btnShadowed)
            ).apply { cornerRadius = dpf(13f) }
            setPadding(dp(20), 0, dp(20), 0)
            attachPressFeedback(this)
        }

    /** Ghost-кнопка темы: прозрачный дым поверх карточки, кайма --line. */
    private fun ghostButton(label: String, color: Int): TextView =
        TextView(activity).apply {
            text = label
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(color)
            background = GradientDrawable().apply {
                setColor(theme.ghostBg)
                cornerRadius = dpf(13f)
                setStroke(dp(1), (color and 0x00FFFFFF) or 0x3B000000.toInt())
            }
            setPadding(dp(20), 0, dp(20), 0)
            attachPressFeedback(this)
        }

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    companion object {
        private const val TICKER_MS = 2_000L
        private const val TICKER_TOKEN = "nav_offline_tick"
        private const val PREFS = "voiceapp3"
        private const val PREF_TOTAL_PREFIX = "nav_pack_total_"
        private const val PREF_DONE_TS_PREFIX = "nav_pack_done_ts_"

        /** Из CSS макета: transition .45s cubic-bezier(.2,.9,.25,1), translateY(130%). */
        private const val OPEN_CLOSE_MS = 450L
        private const val OPEN_FROM_FRACTION = 1.3f
        private val SHEET_EASE = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)

        // Семантические статусы — не темытся: как фиксированный зелёный батареи
        // шелла (критическая логика «готово/ошибка» читается одинаково в любой палитре).
        private val TEXT_OK = 0xFF36E3A0.toInt()
        private val TEXT_ERR = 0xFFFF6B6F.toInt()
        private val WARN = 0xFFFFB020.toInt()
    }
}
