package com.example.voiceapp3.nav.search

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.voiceapp3.nav.ConceptIcons
import com.example.voiceapp3.nav.NavTheme

/**
 * Stage NAV-DESIGN-7 (navigator-app-7.html): полноэкранная панель поиска,
 * состояние `s-search` макета, подключённая к реальной логике этапа 6
 * (SearchRepository / Photon / история / избранное).
 *
 * Перенесено из макета:
 *  · sbar: «назад» + поле с акцентной каймой + ⌫ + кнопка 🎙;
 *  · ряд категорий (⛽ АЗС / ⚡ Зарядки / 🍔 Поесть / 🅿️ Парковки /
 *    🛒 Магазины / 🏨 Отели / 🔧 Сервис / 🏥 Аптеки) — честно: тап ставит
 *    подпись в поле, а запрос уходит с osm_tag Photon;
 *  · список: аватар-emoji по osm_value точки, имя с акцентной подсветкой
 *    совпадений, правый блок «N км / по прямой» (время в пути НЕ рисуем:
 *    роутер — этап 7; демо-цифры макета не копируем);
 *  · встроенная автомобильная клавиатура (раскладка макета, ⇧, ⌫,
 *    ?123/АБВ, 🌐 — системный переключатель раскладок, «Найти» = submit
 *    без debounce), все цвета — из темы.
 *
 * Не перенесено из макета (демо-слой): полоса морфологических подсказок
 * («центр/центре/центра») — нет честного источника; ★-рейтинг и часы
 * «открыто» — таких полей в ответе Photon нет; переключатель состояний.
 */
class SearchSheetController(
    private val activity: Activity,
    private val repo: SearchRepository,
    private val density: Float,
    private val theme: NavTheme
) {

    /** Корневой view панели (хост вешает LP: full-screen с полями 14dp). */
    val view: LinearLayout

    /** Тап по результату → хост открывает карточку места и ставит пин. */
    var onSelect: ((SearchResult) -> Unit)? = null

    /** 🎙 = голосовой ввод адреса (Vosk-диктовка сервиса, НЕ ассистент). */
    private var micBtn: ImageView? = null
    private var voiceListening = false
    private var voicePulse: android.animation.ValueAnimator? = null
    private var dictationReceiver: android.content.BroadcastReceiver? = null

    /** Отправили hub «dictate», ждём broadcast «start» (сервис мог молчать). */
    private var voiceAwaitingStart = false
    private val voiceStartTimer = android.os.Handler(android.os.Looper.getMainLooper())
    private val voiceStartTimeout = Runnable {
        if (!voiceAwaitingStart) return@Runnable
        voiceAwaitingStart = false
        setVoiceUi(false)
        Log.w(TAG, "Голосовой сервис не ответил на dictate за " +
            "${VOICE_START_TIMEOUT_MS}мс (ассистент запущен? модель загружена?)")
        android.widget.Toast.makeText(
            activity,
            "Голосовой сервис не отвечает — проверьте, что ассистент запущен",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /** Колбэк видимости для скрима/блокировок хоста. */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    private lateinit var input: EditText
    private lateinit var clearBtn: ImageView
    private lateinit var statusLine: TextView
    private lateinit var kbContainer: LinearLayout
    private val rowsContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
    }

    private var shown = false
    private var slideAnimating = false
    private var lastSnap: SearchRepository.Snapshot? = null
    private var kbDigits = false
    private var kbShift = false

    private val repoListener: (SearchRepository.Snapshot) -> Unit = { snap ->
        lastSnap = snap
        rebuildList(snap)
        rebuildStatus(snap)
    }

    // ── данные, используемые при ПОСТРОЕНИИ (строго ВЫШЕ init{}: Kotlin
    //    инициализирует поля в порядке объявления — объявленные ниже init{}
    //    на момент построения ещё null. Именно это дало NPE в buildCats() ──

    private data class Category(val label: String, val emoji: String, val osmTag: String)

    private val categories = listOf(
        Category("АЗС", "⛽", "amenity:fuel"),
        Category("Зарядки", "⚡", "amenity:charging_station"),
        Category("Поесть", "🍔", "amenity:restaurant"),
        Category("Парковки", "🅿️", "amenity:parking"),
        Category("Магазины", "🛒", "shop:supermarket"),
        Category("Отели", "🏨", "tourism:hotel"),
        Category("Сервис", "🔧", "shop:car_repair"),
        Category("Аптеки", "🏥", "amenity:pharmacy")
    )

    // раскладка встроенной клавиатуры (макет: 3 буквенных ряда + нижний)
    private val kbLetters = listOf(
        listOf("й", "ц", "у", "к", "е", "н", "г", "ш", "щ", "з", "х"),
        listOf("ф", "ы", "в", "а", "п", "р", "о", "л", "д", "ж", "э")
    )
    private val kbLettersRow3 = listOf("я", "ч", "с", "м", "и", "т", "ь", "б", "ю")
    private val kbDigitsRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("-", "/", ":", ";", "(", ")", "№", "&", "@", "\""),
        listOf(".", ",", "!", "?", "'", "«", "»")
    )

    init {
        Log.i(TAG, "search sheet: сборка stage7-fix7 (диктовка адреса)")
        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.sheetTop, theme.sheetBot)
            ).apply {
                cornerRadius = dpf(26f)
                setStroke(dp(1), theme.borderHairline)
            }
            visibility = View.GONE
            isClickable = true
            // скругления панели режут всё содержимое, включая клавиатуру снизу
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, dpf(26f))
                }
            }
        }
        view.addView(buildSbar())
        view.addView(buildCats())
        statusLine = TextView(activity).apply {
            textSize = 12.5f
            setPadding(dp(20), dp(10), dp(20), 0)
            visibility = View.GONE
        }
        view.addView(statusLine)
        view.addView(
            ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dp(14), 0, dp(14), dp(6))
                addView(rowsContainer)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        kbContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        view.addView(kbContainer)
        rebuildKeyboard()
    }

    // ═══════════════════════════════════ видимость ════

    fun isShown(): Boolean = shown

    fun show() {
        if (slideAnimating || shown) return
        slideAnimating = true
        shown = true
        repo.listen(repoListener)
        registerDictationReceiver()
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
        input.post {
            input.requestFocus()   // системная клавиатура НЕ поднимается:
            // showSoftInputOnFocus=false; ввод — нашей клавиатурой макета
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        slideAnimating = false
        view.animate().cancel()
        repo.unlisten(repoListener)
        if (voiceListening || voiceAwaitingStart) sendDictationHub("cancel")
        voiceAwaitingStart = false
        voiceStartTimer.removeCallbacks(voiceStartTimeout)
        setVoiceUi(false)
        unregisterDictationReceiver()
        onVisibilityChanged?.invoke(false)
        imm()?.hideSoftInputFromWindow(input.windowToken, 0)
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

    fun toggle() = if (shown) hide() else show()

    fun release() = hide()

    // ═══════════════════════════ голосовой ввод адреса ════
    //
    // 🎙 = ДИКТОВКА адреса в поле (по решению пользователя). Канал: hub
    // `dictate` → VoiceAssistantService держит короткую Vosk-сессию (та же
    // модель, что у ассистента), текст приходит broadcast'ом
    // ACTION_NAV_DICTATION { EXTRA_STATE: start|partial|final|error|cancel }.
    // Панель НЕ закрывается; partial — живой текст в поле, final — фикс
    // и обычный поиск. Повторный тап по 🎙 — отмена.

    /** Старт/стоп по тапу (и из topbar: хост сначала делает show()).
     *  Тап сразу показывает «слушаю» (оптимизм) — дальше сессию честно
     *  подтверждает/отменяет broadcast из сервиса; молчание сервиса
     *  диагностируется таймаутом VOICE_START_TIMEOUT_MS. */
    fun toggleVoiceCapture() {
        Log.i(TAG, "mic tap (listening=$voiceListening awaiting=$voiceAwaitingStart shown=$shown)")
        if (!shown) show()
        if (voiceListening || voiceAwaitingStart) {
            sendDictationHub("cancel")
            voiceAwaitingStart = false
            voiceStartTimer.removeCallbacks(voiceStartTimeout)
            setVoiceUi(false)
        } else {
            voiceAwaitingStart = true
            voiceStartTimer.removeCallbacks(voiceStartTimeout)
            voiceStartTimer.postDelayed(voiceStartTimeout, VOICE_START_TIMEOUT_MS)
            setVoiceUi(true)          // мгновенная реакция на тап
            sendDictationHub(null)
            Log.i(TAG, "dictation start requested (hub)")
        }
    }

    /** Точка входа для верхней строки: гарантирует show() и жмёт «слушать». */
    fun startVoiceCapture() {
        if (!voiceListening) toggleVoiceCapture()
    }

    fun isVoiceListening(): Boolean = voiceListening

    private fun sendDictationHub(mode: String?) {
        runCatching {
            val i = android.content.Intent(com.example.voiceapp3.VoiceAssistantService.ACTION_HUB)
                .putExtra(
                    com.example.voiceapp3.VoiceAssistantService.EXTRA_HUB,
                    com.example.voiceapp3.VoiceAssistantService.HUB_DICTATE
                )
            mode?.let {
                i.putExtra(com.example.voiceapp3.VoiceAssistantService.EXTRA_MODE, it)
            }
            activity.sendBroadcast(i)
        }.onFailure { Log.w(TAG, "dictation hub send failed", it) }
    }

    private fun registerDictationReceiver() {
        if (dictationReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                if (intent?.action !=
                    com.example.voiceapp3.VoiceAssistantService.ACTION_NAV_DICTATION
                ) return
                val state = intent.getStringExtra(
                    com.example.voiceapp3.VoiceAssistantService.EXTRA_STATE
                ) ?: return
                val text = intent.getStringExtra(
                    com.example.voiceapp3.VoiceAssistantService.EXTRA_TEXT
                )
        when (state) {
                    "start" -> {
                        voiceAwaitingStart = false
                        voiceStartTimer.removeCallbacks(voiceStartTimeout)
                        setVoiceUi(true)
                    }
                    "partial" -> if (voiceListening && !text.isNullOrBlank()) {
                        // живой эхо-текст диктовки; курсор — в конец
                        input.setText(text)
                        input.setSelection(input.text.length)
                    }
                    "final" -> {
                        voiceAwaitingStart = false
                        voiceStartTimer.removeCallbacks(voiceStartTimeout)
                        setVoiceUi(false)
                        if (!text.isNullOrBlank()) {
                            input.setText(text)
                            input.setSelection(input.text.length)
                            repo.submit()   // финал → сразу выполнить поиск
                        }
                    }
                    "cancel" -> {
                        voiceAwaitingStart = false
                        voiceStartTimer.removeCallbacks(voiceStartTimeout)
                        setVoiceUi(false)
                    }
                    "error" -> {
                        voiceAwaitingStart = false
                        voiceStartTimer.removeCallbacks(voiceStartTimeout)
                        setVoiceUi(false)
                        Log.w(TAG, "dictation error: $text")
                        android.widget.Toast.makeText(
                            activity, "Голосовой ввод: ${text ?: "ошибка"}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        dictationReceiver = r
        val filter = android.content.IntentFilter(
            com.example.voiceapp3.VoiceAssistantService.ACTION_NAV_DICTATION
        )
        // не экспортированный приёмник: сервис шлёт setPackage(local)
        androidx.core.content.ContextCompat.registerReceiver(
            activity, r, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterDictationReceiver() {
        val r = dictationReceiver ?: return
        dictationReceiver = null
        runCatching { activity.unregisterReceiver(r) }
    }

    /** Визуал слушающей кнопки: акцентная подсветка + пульс scale. */
    private fun setVoiceUi(listening: Boolean) {
        voiceListening = listening
        voicePulse?.cancel()
        voicePulse = null
        val btn = micBtn ?: return
        btn.clearAnimation()
        btn.scaleX = 1f
        btn.scaleY = 1f
        btn.background = if (listening) {
            // слушаем: сплошной акцент (градиент «сам себе»)
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.accent1, theme.accent1)
            ).apply { cornerRadius = dpf(18f) }
        } else {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(theme.btn, theme.btnShadowed)
            ).apply { cornerRadius = dpf(18f) }
        }
        if (listening) {
            // голосовой ввод: клавиатура НЕ нужна — прячем, показываем статус
            kbContainer.visibility = View.GONE
            statusLine.text = "🎙 Слушаю — говорите адрес…"
            statusLine.setTextColor(theme.accent1)
            statusLine.visibility = View.VISIBLE
            Log.i(TAG, "voice ui: слушаю → клавиатура СКРЫТА (kb=GONE)")
            voicePulse = android.animation.ValueAnimator.ofFloat(1f, 0.88f, 1f).apply {
                duration = 900
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { a ->
                    val sc = a.animatedValue as Float
                    btn.scaleX = sc; btn.scaleY = sc
                }
                start()
            }
        } else {
            // диктовка завершена/отменена — клавиатуру и обычный статус назад
            kbContainer.visibility = View.VISIBLE
            Log.i(TAG, "voice ui: стоп → клавиатура ВОЗВРАЩЕНА (kb=VISIBLE)")
            lastSnap?.let { rebuildStatus(it) }
                ?: run { statusLine.visibility = View.GONE }
        }
    }

    private fun sheetHeightPx(): Int {
        val h = view.layoutParams?.height ?: 0
        return if (h > 0) h else
            (activity.resources.displayMetrics.heightPixels * 0.8f).toInt()
    }

    // ═══════════════════════════════════ sbar (назад + поле + 🎙) ════

    private fun buildSbar(): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(12))

            // назад (icon-btn 40 r13)
            addView(squareIcon(40, 13f, ConceptIcons.Kind.BACK, theme.ink) {
                hide()
            }.apply { layoutParams = (layoutParams as LinearLayout.LayoutParams)
                .apply { marginEnd = dp(12) } })

            // поле: glass, акцентная кайма, круглая r18, h56
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(4), 0)
                background = GradientDrawable().apply {
                    setColor(theme.chipBg)
                    cornerRadius = dpf(18f)
                    setStroke(dp(1), (theme.accent1 and 0x00FFFFFF) or 0x59000000)
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    marginEnd = dp(12)
                }

                addView(ImageView(activity).apply {
                    setImageBitmap(ConceptIcons.fabIcon(
                        activity, ConceptIcons.Kind.SEARCH, theme.textSecondary, boxDp = 20f
                    ))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                        .apply { marginEnd = dp(10) }
                })

                input = EditText(activity).apply {
                    hint = "Куда едем? Адрес, место или категория"
                    setHintTextColor(theme.textSecondary)
                    setTextColor(theme.ink)
                    textSize = 17f
                    setSingleLine(true)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    showSoftInputOnFocus = false   // наша клавиатура вместо системной
                    layoutParams = LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            clearBtn.visibility =
                                if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                            repo.setQuery(s?.toString() ?: "")
                        }
                    })
                }
                addView(input)

                clearBtn = ImageView(activity).apply {
                    setImageBitmap(ConceptIcons.fabIcon(
                        activity, ConceptIcons.Kind.CLOSE, theme.textSecondary, boxDp = 30f
                    ))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                    visibility = View.GONE
                    contentDescription = "Очистить поле"
                    setOnClickListener { input.text.clear() }
                }
                addView(clearBtn)
            })

            // 🎙 — голосовой ввод АДРЕСА (Vosk-диктовка VoiceAssistantService),
            // НЕ вызов ассистента. Повторный тап = отмена прослушивания.
            micBtn = squareIcon(56, 18f, ConceptIcons.Kind.MIC, theme.onbtn) {
                toggleVoiceCapture()
            }.apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(theme.btn, theme.btnShadowed)
                ).apply { cornerRadius = dpf(18f) }
            }
            addView(micBtn)
        }

    // ═══════════════════════════════════ категории ════

    private fun buildCats(): View = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(2), dp(18), dp(12))
            categories.forEachIndexed { i, cat ->
                addView(TextView(activity).apply {
                    text = cat.emoji + "  " + cat.label
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(theme.ink)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = GradientDrawable().apply {
                        setColor(theme.chipBg)
                        cornerRadius = dpf(14f)
                        setStroke(dp(1), theme.borderHairline)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = if (i == 0) 0 else dp(9) }
                    attachPressFeedback(this)
                    setOnClickListener {
                        input.setText(cat.label)
                        input.setSelection(cat.label.length)
                        repo.searchWithCategory(cat.label, cat.osmTag)
                    }
                })
            }
        })
    }

    // ═══════════════════════════════════ статус ════

    private fun rebuildStatus(snap: SearchRepository.Snapshot) {
        // во время диктовки статус «Слушаю…» держит setVoiceUi — не перебиваем
        if (voiceListening || voiceAwaitingStart) return
        val status: Pair<String, Int>? = when {
            snap.searching -> "ищем…" to theme.textSecondary
            snap.errorText != null && snap.query.trim().length >= 3 ->
                (snap.errorText + " — показаны история и избранное") to WARN
            repo.activeCategory != null && snap.query.trim() == repo.activeCategory?.first ->
                "категория «" + repo.activeCategory!!.first + "» — ближайшие места по тегу OSM" to
                    theme.textSecondary
            else -> null
        }
        if (status != null) {
            statusLine.text = status.first
            statusLine.setTextColor(status.second)
            statusLine.visibility = View.VISIBLE
        } else {
            statusLine.visibility = View.GONE
        }
    }

    // ═══════════════════════════════════ список ════

    private fun rebuildList(snap: SearchRepository.Snapshot) {
        rowsContainer.removeAllViews()
        var rows = 0

        snap.coordinate?.let { coord ->
            rowsContainer.addView(sectionHeader("Координаты"))
            rows += resultRow(coord, iconOverride = null)
        }

        if (snap.online.isNotEmpty()) {
            rowsContainer.addView(sectionHeader("Найдено", count = snap.online.size))
            snap.online.forEach { rows += resultRow(it) }
        }

        if (snap.favorites.isNotEmpty()) {
            rowsContainer.addView(sectionHeader("Избранное"))
            snap.favorites.forEach { rows += resultRow(it, emojiOverride = "⭐", inFavorites = true) }
        }

        if (snap.history.isNotEmpty()) {
            rowsContainer.addView(sectionHeader("Недавние", rightGhost = "очистить журнал") {
                AlertDialog.Builder(activity)
                    .setTitle("Очистить журнал поиска?")
                    .setMessage("Избранное сохранится, удалятся только последние точки.")
                    .setPositiveButton("Очистить") { _, _ -> repo.clearHistory() }
                    .setNegativeButton("Отмена", null)
                    .show()
            })
            snap.history.forEach { rows += resultRow(it, emojiOverride = "🕘") }
        }

        if (rows == 0 && !snap.searching) {
            val hint = when {
                snap.query.trim().isEmpty() ->
                    "Начните вводить адрес, место или ткните категорию выше."
                snap.query.trim().length < 3 && snap.coordinate == null ->
                    "Введите ещё " + (3 - snap.query.trim().length) + " " +
                        russianPluralLetters(3 - snap.query.trim().length) +
                        " — поиск сетью начинается с трёх букв."
                snap.errorText == null -> "Ничего не нашлось — переформулируйте запрос."
                else -> "Сеть недоступна, а локально совпадений нет."
            }
            rowsContainer.addView(TextView(activity).apply {
                text = hint
                textSize = 13.5f
                setTextColor(theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(28), dp(24), dp(28))
            })
        }
    }

    private fun russianPluralLetters(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "букву"
            mod10 in 2..4 && mod100 !in 12..14 -> "буквы"
            else -> "букв"
        }
    }

    private fun sectionHeader(
        title: String,
        count: Int? = null,
        rightGhost: String? = null,
        onRight: (() -> Unit)? = null
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(16), dp(6), dp(6))
        addView(TextView(activity).apply {
            text = title.uppercase(java.util.Locale.ROOT) + (count?.let { " · $it" } ?: "")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.16f
            setTextColor(theme.textSecondary)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        if (rightGhost != null && onRight != null) {
            addView(TextView(activity).apply {
                text = rightGhost
                textSize = 12.5f
                setTextColor(theme.accent1)
                attachPressFeedback(this)
                setOnClickListener { onRight() }
            })
        }
    }

    /** Строка результата: аватар-emoji (по osm_value), имя с подсветкой, ★ справа. */
    private fun resultRow(
        r: SearchResult,
        emojiOverride: String? = null,
        iconOverride: ConceptIcons.Kind? = null,
        inFavorites: Boolean = false
    ): Int {
        rowsContainer.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(12), dp(13))
            background = GradientDrawable().apply {
                setColor(theme.rowBg)
                cornerRadius = dpf(18f)
                setStroke(dp(1), theme.borderHairline)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            attachPressFeedback(this)
            setOnClickListener { select(r) }

            // аватар 46 r15
            addView(TextView(activity).apply {
                text = emojiOverride ?: r.emoji
                textSize = 19f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(theme.chipBg)
                    cornerRadius = dpf(15f)
                    setStroke(dp(1), theme.borderHairline)
                }
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                    marginEnd = dp(15)
                }
            })

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                addView(TextView(activity).apply {
                    text = highlighted(r.name, lastSnap?.query ?: "")
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(theme.ink)
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (r.subtitle.isNotBlank()) {
                    addView(TextView(activity).apply {
                        text = r.subtitle
                        textSize = 13f
                        setTextColor(theme.textSecondary)
                        setSingleLine(true)
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(4), 0, 0)
                    })
                }
            })

            // правый блок: дистанция + «по прямой» (время — этап 7)
            val dist = formatDistanceTo(r)
            if (dist.isNotEmpty()) {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    setPadding(dp(10), 0, dp(2), 0)
                    addView(TextView(activity).apply {
                        text = dist
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(theme.ink)
                    })
                    addView(TextView(activity).apply {
                        text = "по прямой"
                        textSize = 11f
                        setTextColor(theme.textSecondary)
                    })
                })
            }

            addView(ImageView(activity).apply {
                val fav = inFavorites || repo.isFavorite(r.key)
                setImageBitmap(ConceptIcons.fabIcon(
                    activity,
                    if (fav) ConceptIcons.Kind.STAR_FILL else ConceptIcons.Kind.STAR,
                    if (fav) theme.accent1 else theme.textSecondary,
                    boxDp = 34f
                ))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    marginStart = dp(10)
                }
                contentDescription = if (fav) "Убрать из избранного" else "В избранное"
                attachPressFeedback(this)
                setOnClickListener { repo.toggleFavorite(r) }
            })
        })
        return 1
    }

    /** Акцентная подсветка всех токенов запроса (от 2 символов) в имени. */
    private fun highlighted(name: String, query: String): CharSequence {
        val trimmed = query.trim()
        if (trimmed.length < 2) return name
        val span = SpannableString(name)
        val lower = name.lowercase()
        trimmed.lowercase().split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .forEach { token ->
                var idx = lower.indexOf(token)
                while (idx >= 0) {
                    span.setSpan(
                        ForegroundColorSpan(theme.accent1),
                        idx, idx + token.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    idx = lower.indexOf(token, idx + token.length)
                }
            }
        return span
    }

    private fun formatDistanceTo(r: SearchResult): String {
        val aLat = repo.biasLat ?: return ""
        val aLon = repo.biasLon ?: return ""
        return SearchResult.formatDistance(
            SearchResult.distanceMeters(aLat, aLon, r.lat, r.lon)
        )
    }

    // ═══════════════════════════════════ клавиатура ════

    private enum class K { LETTER, SHIFT, BKSP, MODE, LANG, SPACE, COMMA, ENTER }

    private fun rebuildKeyboard() {
        kbContainer.removeAllViews()
        kbContainer.setBackgroundColor(theme.page2)
        kbContainer.setPadding(dp(10), dp(10), dp(10), dp(12))

        if (!kbDigits) {
            addKbRow(kbLetters[0].map { it.uppercaseIf(kbShift) }, digitsSup = true)
            addKbRow(kbLetters[1].map { it.uppercaseIf(kbShift) })
            addKeyRow(
                buildList {
                    add(KbKey(K.SHIFT, "⇧", weight = 1.5f, active = kbShift))
                    kbLettersRow3.forEach { add(KbKey(K.LETTER, it.uppercaseIf(kbShift))) }
                    add(KbKey(K.BKSP, "⌫", weight = 1.5f))
                }
            )
        } else {
            kbDigitsRows[0].let { addKbRow(it) }
            kbDigitsRows[1].let { addKbRow(it) }
            addKeyRow(
                buildList {
                    add(KbKey(K.MODE, "АБВ", weight = 1.5f))
                    kbDigitsRows[2].forEach { add(KbKey(K.LETTER, it)) }
                    add(KbKey(K.BKSP, "⌫", weight = 1.5f))
                }
            )
        }
        addKeyRow(
            buildList {
                if (!kbDigits) add(KbKey(K.MODE, "?123", weight = 1.5f))
                add(KbKey(K.LANG, "🌐"))
                add(KbKey(K.SPACE, "Русский", weight = 4f))
                add(KbKey(K.COMMA, ","))
                add(KbKey(K.ENTER, "Найти", weight = 1.5f, primary = true))
            }
        )
    }

    private data class KbKey(
        val kind: K,
        val label: String,
        val weight: Float = 1f,
        val primary: Boolean = false,
        val active: Boolean = false
    )

    private fun addKeyRow(keys: List<KbKey>) = addKbRowViews(kbContainer, keys)

    private fun addKbRow(labels: List<String>, digitsSup: Boolean = false) {
        addKbRowViews(kbContainer, labels.map { KbKey(K.LETTER, it) }, digitsSup)
    }

    private fun addKbRowViews(root: LinearLayout, keys: List<KbKey>, digitsSup: Boolean = false) {
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(6) }
            keys.forEachIndexed { i, key ->
                addView(TextView(activity).apply {
                    text = key.label
                    textSize = if (key.kind in setOf(K.SPACE, K.MODE, K.ENTER)) 13f else 16f
                    if (key.primary) typeface = Typeface.DEFAULT_BOLD
                    setTextColor(
                        when {
                            key.primary -> theme.onbtn
                            key.active || key.kind == K.SPACE || key.kind == K.MODE ->
                                theme.textSecondary
                            else -> theme.ink
                        }
                    )
                    gravity = Gravity.CENTER
                    background = when {
                        key.primary -> GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(theme.btn, theme.btnShadowed)
                        ).apply { cornerRadius = dpf(11f) }
                        else -> GradientDrawable().apply {
                            setColor(theme.card)
                            cornerRadius = dpf(11f)
                            setStroke(dp(1), theme.borderHairline)
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, key.weight
                    ).apply { marginStart = if (i == 0) 0 else dp(6) }
                    attachPressFeedback(this)
                    setOnClickListener { onKey(key) }
                })
            }
        })
    }

    private fun onKey(key: KbKey) {
        when (key.kind) {
            K.LETTER -> {
                insertAtCursor(key.label)
                if (kbShift && !kbDigits) {
                    kbShift = false
                    rebuildKeyboard()
                }
            }
            K.SHIFT -> { kbShift = !kbShift; rebuildKeyboard() }
            K.BKSP -> backspaceAtCursor()
            K.MODE -> { kbDigits = !kbDigits; rebuildKeyboard() }
            K.LANG -> imm()?.showInputMethodPicker()   // системный выбор раскладок — честно
            K.SPACE -> insertAtCursor(" ")
            K.COMMA -> insertAtCursor(",")
            K.ENTER -> repo.submit()
        }
    }

    private fun insertAtCursor(text: String) {
        val s = input.selectionStart
        val e = input.selectionEnd
        if (s < 0 || e < 0) { input.append(text); return }
        input.text.replace(minOf(s, e), maxOf(s, e), text)
    }

    private fun backspaceAtCursor() {
        val s = input.selectionStart
        val e = input.selectionEnd
        if (s < 0 || e < 0) return
        if (s != e) input.text.delete(minOf(s, e), maxOf(s, e))
        else if (s > 0) input.text.delete(s - 1, s)
    }

    private fun String.uppercaseIf(up: Boolean): String =
        if (up) uppercase() else this

    // ═══════════════════════════════════ действия/утилиты ════

    private fun select(r: SearchResult) {
        Log.i(TAG, "select: ${r.name} (${r.lat}, ${r.lon})")
        repo.addToHistory(r)
        onSelect?.invoke(r)
        // панель не закрываем: карточка места поверх (поведение макета pick→place)
    }

    private fun squareIcon(
        sizeDp: Int, radiusDp: Float, kind: ConceptIcons.Kind, color: Int,
        onClick: () -> Unit
    ): ImageView = ImageView(activity).apply {
        setImageBitmap(ConceptIcons.fabIcon(activity, kind, color, boxDp = sizeDp.toFloat()))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(sizeDp / 4), dp(sizeDp / 4), dp(sizeDp / 4), dp(sizeDp / 4))
        background = GradientDrawable().apply {
            setColor(theme.chipBg)
            cornerRadius = dpf(radiusDp)
            setStroke(dp(1), theme.borderHairline)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        attachPressFeedback(this)
        setOnClickListener { onClick() }
    }

    private fun imm(): InputMethodManager? =
        activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun attachPressFeedback(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f)
                    .setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
    }

    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    companion object {
        private const val TAG = "NavSearchSheet"

        /** Диагноз «сервис молчит» после hub-dictate start (модель/помпа?). */
        private const val VOICE_START_TIMEOUT_MS = 3_000L

        /** Как в макете: .42s cubic-bezier(.2,.9,.25,1), translateY(100%+150px). */
        private const val OPEN_CLOSE_MS = 420L
        private const val OPEN_FROM_FRACTION = 1.2f
        private val SHEET_EASE = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)

        private val WARN = 0xFFFFB020.toInt()
    }
}
