package com.example.voiceapp3.ui.cx

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.example.voiceapp3.R

/**
 * Спецификация строк интерфейса — перенос генераторов шелла
 * `cxSect` / `cxDiv` / `cxToggleRow` / `cxSegRow` / `cxSliderRow` / `cxBtn`.
 *
 * В шелле эти функции возвращали HTML-строку; здесь из них собирается
 * нативная иерархия View. Состав полей совпадает с объектом-аргументом
 * соответствующей JS-функции, поэтому страницы переносятся построчно.
 */
/** Способ выбора в сетке плиток и, как следствие, форма события. */
enum class CxTileKind {
    /** `emit('drive.mode', {mode})` */
    DRIVE_MODE,

    /** `emit('drive.recup', {value})` — сегменты рекуперации */
    DRIVE_RECUP,

    /** `emit(tog ? 'climate.recirc' : 'climate.air', {value, on})` */
    CLIMATE_AIR,

    /** `emit('climate.heat', {name, on})` */
    CLIMATE_HEAT,

    /** `emit('climate.seat', {name, level})` */
    CLIMATE_SEAT,
}

/**
 * Плитка сетки.
 *
 * @param value    значение из `data-v`
 * @param label    подпись `<b>`
 * @param sub      подпись `<small>` (у режимов движения)
 * @param emoji    символ `<span>` (у режимов движения); для остальных — иконка
 * @param drawable имя VectorDrawable, сгенерированного из инлайнового SVG
 * @param toggle   независимый переключатель, а не пункт одиночного выбора
 * @param levels   число ступеней (у сидений — индикатор `.lvl` из трёх делений)
 */
data class CxTile(
    val value: String,
    val label: String,
    val sub: String? = null,
    val emoji: String? = null,
    val drawable: String? = null,
    val toggle: Boolean = false,
    val levels: Int = 0,
    /**
     * Переопределение рода события для отдельной плитки.
     *
     * Нужно потому, что в шелле обогревы и сиденья лежат в ОДНОЙ сетке
     * `.airGrid`, но события у них разные: `.heat` шлёт `climate.heat`
     * с {name, on}, а `.seat` — `climate.seat` с {name, level}. Без
     * переопределения сиденья отправляли бы чужое событие.
     */
    val kindOverride: CxTileKind? = null,
)

sealed class CxRow {

    /** `cxSect(t)` — заголовок группы. */
    data class Section(val title: String) : CxRow()

    /** `cxDiv()` — разделитель между строками. */
    object Divider : CxRow()

    /**
     * `cxToggleRow({key,title,desc,def})`.
     * `say` повторяет поведение шелла: тост «название · вкл/выкл».
     */
    data class Toggle(
        val key: String,
        val title: String,
        val desc: String? = null,
        val def: Boolean = false,
        val say: Boolean = true,
    ) : CxRow()

    /**
     * `cxSegRow({key,title,opts,def,width})`.
     * `opts` — пары «значение → подпись», порядок сохраняется.
     */
    data class Segmented(
        val key: String,
        val title: String,
        val opts: List<Pair<String, String>>,
        val def: String,
        val widthDp: Int = 400,
    ) : CxRow()

    /** `cxSliderRow({key,title,min,max,step,unit,def,wide})`. */
    data class Slider(
        val key: String,
        val title: String,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        val unit: String = "",
        val def: Int = 0,
        val wide: Boolean = false,
    ) : CxRow()

    /** `cxBtn(act,label,kind)`; `inject` — аналог `data-inject` у кнопок проверки фронта. */
    data class Button(
        val action: String,
        val label: String,
        val kind: String? = null,
        val inject: String? = null,
        /**
         * Подпись, вычисляемая из снимка состояния. Перекрывает [label] при
         * перерисовке: в шелле так работала кнопка «Управление списком (N)»,
         * где N — число приложений в `keepAliveApps`.
         */
        val labelFrom: ((CxState) -> String)? = null,
    ) : CxRow()

    /** `cxStatePill(text,kind)` — отдельный индикатор состояния. */
    data class Pill(val text: String, val kind: String? = null) : CxRow()

    /**
     * Строка только для чтения: заголовок плюс значение, вычисляемое из снимка
     * состояния. В шелле это `div.cx-row` с `data-cxrow` и `<small>`, куда
     * подставлялось значение из `TOOLS.state` (например `lockTriggerStatus`,
     * `lockRoute`, `unlockSoundName`).
     */
    data class Info(
        val key: String,
        val title: String,
        val valueFrom: (CxState) -> String,
        val note: Boolean = false,
        /** кнопка справа в той же строке — как в блоке копирования с USB */
        val action: Button? = null,
        /**
         * Индикатор состояния справа — аналог `cxStatePill(text,kind)` в строке.
         * Первая часть пары — текст, вторая — вариант (`ok` подсвечивает).
         */
        val pillFrom: ((CxState) -> Pair<String, String?>)? = null,
    ) : CxRow()

    /** Группа кнопок в одну строку с переносом — `div.cx-row.cx-actions`. */
    data class Buttons(val items: List<Button>) : CxRow()

    /**
     * Строка со списком — `<select class="cx-select">` из шелла: заголовок,
     * подпись с количеством и выпадающий список файлов.
     */
    data class Select(
        val key: String,
        val title: String,
        val subtitleFrom: (CxState) -> String,
        val optionsFrom: (CxState) -> List<String>,
        val selectedFrom: (CxState) -> String,
    ) : CxRow()

    /** Плашка статуса длительной операции — `#cx-sound-status-banner`. */
    data class Banner(val textFrom: (CxState) -> String) : CxRow()

    /**
     * Поле команды с кнопкой выполнения и быстрыми командами — блок
     * «Интерактивный Shell & ADB консоль» страницы диагностики.
     *
     * Поведение из шелла (`window.cxDevRunCmd` / `cxDevQuickCmd`): пустая
     * команда не отправляется вовсе; быстрая команда подставляет текст в поле
     * и сразу выполняет её, то есть обе идут одним путём.
     *
     * @param valueField ключ в payload действия: `command` у `dev.runCommand`
     *   против `value` у кнопок проверки фронта
     */
    data class CommandInput(
        val key: String,
        val hint: String,
        val caption: String,
        val runAction: String,
        val valueField: String = "command",
        val quick: List<Pair<String, String>> = emptyList(),
    ) : CxRow()

    /**
     * Область вывода моноширинным шрифтом — `<pre id="__dev_cmd_output">` и
     * `<pre id="__dev_log_output">`. Текст берётся из снимка состояния: в шелле
     * его ставили `window.__onDevCmdResult` / `__onDevLogcatResult`.
     */
    /**
     * Плитки выбора палитры — перенос `cxThemeTiles()`.
     *
     * Каталог и порядок берутся из [CxPalettes.ALL], то есть из `THEMES` шелла;
     * плитка показывает страницу, карточку, её текст и две точки акцентов —
     * те же шесть цветов, что рисовал `thmPrev` / `thmCard` / `tdots`.
     */
    data class ThemeTiles(val key: String = "themeApp") : CxRow()

    /**
     * Сетка плиток — `.modes` и `.airGrid` панели «Быстрый доступ».
     *
     * Способ выбора определяется [kind]: режим движения и направление обдува —
     * одиночный выбор, обогрев и сиденья — независимые переключатели. Форма
     * события и состав payload повторяют обработчик клика шелла.
     */
    data class TileGrid(
        val key: String,
        val kind: CxTileKind,
        val tiles: List<CxTile>,
        val columns: Int = CxMetrics.GRID_COLUMNS,
        /** значения, которые сейчас подсвечены; берётся из снимка состояния */
        val selectedFrom: (CxState) -> Set<String>,
    ) : CxRow()

    /**
     * Индикатор заполнения без возможности взаимодействия — `.updProg` /
     * `.ubar` панели обновлений. Отдельный тип вместо [Slider] потому, что
     * слайдер подразумевал бы запись значения, которой здесь нет.
     */
    data class Progress(
        val key: String,
        val valueFrom: (CxState) -> Int,
    ) : CxRow()

    data class Console(
        val key: String,
        val placeholder: String,
        val textFrom: (CxState) -> String,
        val textColor: Int,
        val maxHeightDp: Int = 240,
    ) : CxRow()
}

/**
 * Доступ к строковым ресурсам.
 *
 * Экраны принимают его вместо `Context`, потому что из ресурсов им нужны
 * только строки, а зависимость от `Context` делала бы спецификации страниц
 * непроверяемыми вне прибора: `android.content.Context` — абстрактный класс
 * примерно с девяноста абстрактными методами, и заглушку для него не написать.
 * Реализация в приложении — `{ activity.getString(it) }`.
 */
fun interface CxStrings {
    fun get(resId: Int): String
}

/** Единственный канал записи — аналог `cxSet(key,value)` → `settings.change`. */
interface CxWrite {
    fun set(key: String, value: Any)
}

/**
 * Канал действий кнопок — аналог `cxAction(act,btn)`.
 *
 * Маршрутизация из шелла сохраняется на стороне получателя: `audio.selectFile`
 * и `audio.selectUnlockFile` открывают системный выбор документа, остальные
 * `audio.*` и `dev.*` уходят отдельными событиями, всё прочее — как
 * `tools.action` с полем `action`.
 */
interface CxAct {
    /**
     * Действие с произвольным набором полей.
     *
     * Формы payload в шелле различаются по событию, поэтому одним полем не
     * обойтись: `drive.mode` -> {mode}, `climate.air` -> {value, on},
     * `perf.saveKeepAlive` -> {packages: [String]} — значением бывает и массив,
     * `climate.heat` -> {name, on}, `climate.seat` -> {name, level},
     * `car.color` -> {color}, `dev.runCommand` -> {command}.
     */
    fun act(action: String, payload: Map<String, Any?>)

    /**
     * Удобная форма для действий с одним значением.
     *
     * @param value     значение; null, если его нет
     * @param valueKey  ключ в payload: `value` у кнопок проверки фронта
     *   (`data-inject` в шелле), `command` у `dev.runCommand`
     */
    fun act(action: String, value: String?, valueKey: String = "value") =
        act(action, if (value == null) emptyMap<String, Any?>()
                    else mapOf<String, Any?>(valueKey to value))

    companion object {
        /**
         * Локальное действие очистки журнала — аналог `cxDevClearLogs()`,
         * который в шелле не отправлял события, а просто перезаписывал текст
         * области вывода. Здесь его обрабатывает слой обвязки: кладёт пустую
         * строку в `CxPage.DEV_LOGCAT_OUTPUT` нового снимка.
         */
        const val CLEAR_LOGCAT = "local.clearLogcat"
    }
}

/**
 * Страница раздела.
 *
 * Два режима обновления — как в шелле:
 *  * [render] собирает иерархию заново (аналог `cxRepaintPage`);
 *  * [bind] правит значения уже созданных контролов на месте (аналог
 *    `cxSyncControls` + `cxPaintNotes`), без пересборки и без потери прокрутки.
 *
 * Все цвета берутся из атрибутов темы, поэтому страница одинаково выглядит в
 * каждой из десяти палитр и не требует пересоздания при их смене — достаточно
 * пересоздать активность, что и делает [CxPalettes.select].
 */
class CxPage(
    private val ctx: Context,
    private val write: CxWrite,
    private val act: CxAct,
    private val onSay: ((String) -> Unit)? = null,
) {

    private val toggles = LinkedHashMap<String, CompoundButton>()
    private val segments = LinkedHashMap<String, MutableList<Pair<String, TextView>>>()
    private val sliders = LinkedHashMap<String, SeekBar>()
    private val sliderLabels = LinkedHashMap<String, TextView>()
    private val sliderSpecs = LinkedHashMap<String, CxRow.Slider>()
    private val noteViews = LinkedHashMap<String, TextView>()
    private val infoValueViews = LinkedHashMap<String, TextView>()
    private val infoSpecs = LinkedHashMap<String, CxRow.Info>()
    private val selectViews = LinkedHashMap<String,
        Pair<android.widget.Spinner, CxRow.Select>>()
    private val bannerViews = ArrayList<Pair<TextView, CxRow.Banner>>()
    private val commandInputs = LinkedHashMap<String,
        Pair<android.widget.EditText, CxRow.CommandInput>>()
    private val consoleViews = LinkedHashMap<String, Pair<TextView, CxRow.Console>>()
    private val pillViews = LinkedHashMap<String, Pair<TextView, CxRow.Info>>()
    private val dynamicLabels = ArrayList<Pair<TextView, CxRow.Button>>()
    private val actionViews = ArrayList<View>()
    private var root: LinearLayout? = null

    companion object {
        /* Ключи плашки статуса длительной операции. В шелле это глобальные
           переменные `window.__soundOpBusy` / `__soundOpSuccess` / `__soundOpMsg`,
           которые ставил `cxSoundAction` и снимал `window.__cxSoundResult` по
           приходе снимка с `last`. Здесь они живут в том же снимке состояния,
           поэтому отдельного канала не нужно. */
        const val SOUND_BUSY = "__soundOpBusy"
        const val SOUND_SUCCESS = "__soundOpSuccess"
        const val SOUND_MSG = "__soundOpMsg"

        /* Ключи снимка состояния для консоли разработчика. В шелле результат
           приходил в `window.__onDevCmdResult` / `__onDevLogcatResult`; здесь
           слой обвязки кладёт их в тот же снимок, что и остальные значения. */
        const val DEV_CMD_OUTPUT = "devCommandOutput"
        const val DEV_CMD_EXIT = "devCommandExitCode"
        const val DEV_LOGCAT_OUTPUT = "devLogcatOutput"

        /* Фон консоли — литерал из шелла (#1e1e1e), намеренно не токен
           палитры: это терминальное окно, оно одинаковое в любой теме. */
        const val CONSOLE_BG = 0xFF1E1E1E.toInt()
        const val CONSOLE_CMD_FG = 0xFF85E89D.toInt()
        const val CONSOLE_LOG_FG = 0xFF9ECBFF.toInt()
    }

    // ── построение ──────────────────────────────────────────────────────

    fun render(rows: List<CxRow>, state: CxState, notes: CxNoteSource): View {
        toggles.clear(); segments.clear(); sliders.clear()
        sliderLabels.clear(); sliderSpecs.clear(); noteViews.clear()
        infoValueViews.clear(); infoSpecs.clear(); selectViews.clear()
        bannerViews.clear(); actionViews.clear()
        commandInputs.clear(); consoleViews.clear(); progressViews.clear()
        pillViews.clear(); dynamicLabels.clear()
        tileViews.clear(); tileSpecs.clear()
        lastState = state

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(CxTheme.dp(ctx, 20f), CxTheme.dp(ctx, 16f),
                CxTheme.dp(ctx, 20f), CxTheme.dp(ctx, 24f))
        }
        for (r in rows) body.addView(build(r, state, notes))
        root = body

        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun build(row: CxRow, state: CxState, notes: CxNoteSource): View = when (row) {
        is CxRow.Section -> section(row)
        CxRow.Divider -> divider()
        is CxRow.Toggle -> toggle(row, state, notes)
        is CxRow.Segmented -> segmented(row, state)
        is CxRow.Slider -> slider(row, state, notes)
        is CxRow.Button -> button(row)
        is CxRow.Pill -> pill(row)
        is CxRow.Info -> info(row, state, notes)
        is CxRow.Buttons -> buttons(row)
        is CxRow.Select -> select(row, state)
        is CxRow.Banner -> banner(row, state)
        is CxRow.CommandInput -> commandInput(row)
        is CxRow.Console -> console(row, state)
        is CxRow.ThemeTiles -> themeTiles(state)
        is CxRow.TileGrid -> tileGrid(row, state)
        is CxRow.Progress -> progress(row, state)
    }

    private fun section(r: CxRow.Section) = TextView(ctx).apply {
        text = r.title
        setTextColor(CxTheme.color(ctx, R.attr.cxMuted2))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, CxMetrics.SECTION_TEXT_SP))
        setMinimumHeight(CxTheme.dp(ctx, CxMetrics.SECTION_HEIGHT_DP))
        gravity = Gravity.BOTTOM
        val pl = CxTheme.dp(ctx, CxMetrics.ROW_PAD_LEFT_DP)
        setPadding(pl, CxTheme.dp(ctx, CxMetrics.SECTION_MARGIN_TOP_DP),
            pl, CxTheme.dp(ctx, CxMetrics.SECTION_PAD_BOTTOM_DP))
    }

    private fun divider() = View(ctx).apply {
        setBackgroundColor(CxTheme.color(ctx, R.attr.cxLine))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, CxTheme.dp(ctx, 1f)))
    }

    private fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(CxTheme.color(ctx, R.attr.cxCard), CxTheme.dp(ctx, 14f))
        setPadding(CxTheme.dp(ctx, 18f), CxTheme.dp(ctx, 6f),
            CxTheme.dp(ctx, 18f), CxTheme.dp(ctx, 6f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CxTheme.dp(ctx, 8f); bottomMargin = CxTheme.dp(ctx, 8f)
        }
    }

    private fun rowShell(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setMinimumHeight(CxTheme.dp(ctx, CxMetrics.ROW_MIN_HEIGHT_DP))
        setPadding(CxTheme.dp(ctx, CxMetrics.ROW_PAD_LEFT_DP),
            CxTheme.dp(ctx, CxMetrics.ROW_PAD_TOP_DP),
            CxTheme.dp(ctx, CxMetrics.ROW_PAD_RIGHT_DP),
            CxTheme.dp(ctx, CxMetrics.ROW_PAD_BOTTOM_DP))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Блок «заголовок + подпись + статус» слева. */
    private fun titleBlock(title: String, desc: String?, noteKey: String?,
                           notes: CxNoteSource, weight: Float,
                           fixedWidthDp: Float = 0f): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }
        col.addView(TextView(ctx).apply {
            text = title
            setTextColor(CxTheme.color(ctx, R.attr.cxInk))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 15f))
        })
        if (!desc.isNullOrEmpty()) col.addView(TextView(ctx).apply {
            text = desc
            setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12f))
            setPadding(0, CxTheme.dp(ctx, 3f), 0, 0)
        })
        if (noteKey != null) {
            val note = TextView(ctx).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 11f))
                setPadding(0, CxTheme.dp(ctx, 4f), 0, 0)
            }
            noteViews[noteKey] = note
            col.addView(note)
            paintNote(noteKey, notes)
        }
        return col
    }

    private fun toggle(r: CxRow.Toggle, state: CxState, notes: CxNoteSource): View {
        val card = card(); card.addView(rowShell().also { row ->
            row.addView(titleBlock(r.title, r.desc, r.key, notes, 1f))
            val sw = Switch(ctx).apply {
                isChecked = state.bool(r.key, r.def)
                setOnCheckedChangeListener { _, checked ->
                    if (r.say) onSay?.invoke(r.title + if (checked) " · вкл" else " · выкл")
                    write.set(r.key, checked)
                }
            }
            toggles[r.key] = sw
            row.addView(sw, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = CxTheme.dp(ctx, 14f)
            })
        })
        return card
    }

    private fun segmented(r: CxRow.Segmented, state: CxState): View {
        val cur = state.string(r.key, r.def)
        val card = card(); card.addView(rowShell().also { row ->
            row.addView(titleBlock(r.title, null, null, NoNotes, 1f))
            val seg = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundRect(CxTheme.color(ctx, R.attr.cxTrack),
                    CxTheme.dp(ctx, CxMetrics.SEG_RADIUS_DP))
                val sp = CxTheme.dp(ctx, CxMetrics.SEG_PAD_DP)
                setPadding(sp, sp, sp, sp)
                setMinimumHeight(CxTheme.dp(ctx, CxMetrics.SEG_HEIGHT_DP))
            }
            val list = mutableListOf<Pair<String, TextView>>()
            for ((value, label) in r.opts) {
                val b = TextView(ctx).apply {
                    text = label
                    gravity = Gravity.CENTER
                    minWidth = CxTheme.dp(ctx, CxMetrics.SEGB_MIN_WIDTH_DP)
                    val hp = CxTheme.dp(ctx, CxMetrics.SEGB_PAD_H_DP)
                    setPadding(hp, 0, hp, 0)
                    minHeight = CxTheme.dp(ctx, CxMetrics.SEG_HEIGHT_DP - 2 * CxMetrics.SEG_PAD_DP)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, CxMetrics.SEGB_TEXT_SP))
                    setOnClickListener {
                        paintSegment(list, value)
                        write.set(r.key, value)
                    }
                }
                seg.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                list.add(value to b)
            }
            paintSegment(list, cur)
            segments[r.key] = list
            row.addView(seg, LinearLayout.LayoutParams(
                CxTheme.dp(ctx, r.widthDp.toFloat()), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = CxTheme.dp(ctx, 14f)
            })
        })
        return card
    }

    private fun slider(r: CxRow.Slider, state: CxState, notes: CxNoteSource): View {
        val value = state.int(r.key, r.def)
        val card = card()
        card.addView(rowShell().also { row ->
            row.addView(titleBlock(r.title, null, r.key, notes, if (r.wide) 0.9f else 1f))
            val sb = SeekBar(ctx).apply {
                val steps = stepsOf(r)
                max = steps
                progress = toProgress(r, value)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        sliderLabels[r.key]?.text = fromProgress(r, p).toString() + r.unit
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {
                        write.set(r.key, fromProgress(r, s.progress))
                    }
                })
            }
            sliders[r.key] = sb; sliderSpecs[r.key] = r
            row.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                leftMargin = CxTheme.dp(ctx, 14f); rightMargin = CxTheme.dp(ctx, 10f)
            })
            val lab = TextView(ctx).apply {
                text = value.toString() + r.unit
                setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 13f))
                minWidth = CxTheme.dp(ctx, 54f)
                gravity = Gravity.END
            }
            sliderLabels[r.key] = lab
            row.addView(lab)
        })
        return card
    }

    private fun paintPill(v: TextView, r: CxRow.Info, state: CxState) {
        val f = r.pillFrom ?: return
        val (text, kind) = f(state)
        v.text = text
        val ok = kind == "ok"
        v.background = roundRect(CxTheme.color(ctx, if (ok) R.attr.cxGreen else R.attr.cxTrack),
            CxTheme.dp(ctx, 999f))
        v.setTextColor(if (ok) 0xFFFFFFFF.toInt() else CxTheme.color(ctx, R.attr.cxMuted))
    }

    private fun button(r: CxRow.Button): View {
        val accent = r.kind == "accent"
        val danger = r.kind == "danger"
        val ok = r.kind == "ok"
        val bg = when {
            danger -> CxTheme.color(ctx, R.attr.cxDanger)
            accent -> CxTheme.color(ctx, R.attr.cxAccent)
            ok -> CxTheme.color(ctx, R.attr.cxGreen)
            else -> CxTheme.color(ctx, R.attr.cxBtn)
        }
        val fg = if (danger || accent || ok) 0xFFFFFFFF.toInt()
                 else CxTheme.color(ctx, R.attr.cxOnBtn)
        return TextView(ctx).apply {
            text = r.label
            if (r.labelFrom != null) dynamicLabels.add(this to r)
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = roundRect(bg, CxTheme.dp(ctx, CxMetrics.BTN_RADIUS_DP))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, CxMetrics.BTN_TEXT_SP))
            minHeight = CxTheme.dp(ctx, CxMetrics.BTN_HEIGHT_DP)
            val bh = CxTheme.dp(ctx, CxMetrics.BTN_PAD_H_DP)
            setPadding(bh, 0, bh, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = CxTheme.dp(ctx, 6f); bottomMargin = CxTheme.dp(ctx, 6f)
            }
            setOnClickListener {
                val handled = localAction?.invoke(r.action) == true
                if (!handled) act.act(r.action, r.inject)
            }
        }
    }

    private fun pill(r: CxRow.Pill) = TextView(ctx).apply {
        text = r.text
        gravity = Gravity.CENTER
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 11f))
        val bg = if (r.kind == "ok") CxTheme.color(ctx, R.attr.cxGreen)
                 else CxTheme.color(ctx, R.attr.cxTrack)
        setTextColor(if (r.kind == "ok") 0xFFFFFFFF.toInt()
                     else CxTheme.color(ctx, R.attr.cxMuted))
        background = roundRect(bg, CxTheme.dp(ctx, 999f))
        setPadding(CxTheme.dp(ctx, 10f), CxTheme.dp(ctx, 3f),
            CxTheme.dp(ctx, 10f), CxTheme.dp(ctx, 3f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CxTheme.dp(ctx, 4f)
        }
    }

    private fun info(r: CxRow.Info, state: CxState, notes: CxNoteSource): View {
        val card = card(); card.addView(rowShell().also { row ->
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = r.title
                setTextColor(CxTheme.color(ctx, R.attr.cxInk))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 15f))
            })
            val value = TextView(ctx).apply {
                text = r.valueFrom(state)
                setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12f))
                setPadding(0, CxTheme.dp(ctx, 3f), 0, 0)
            }
            infoValueViews[r.key] = value
            col.addView(value)
            if (r.note) {
                val note = TextView(ctx).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 11f))
                    setPadding(0, CxTheme.dp(ctx, 4f), 0, 0)
                }
                noteViews[r.key] = note
                col.addView(note)
                paintNote(r.key, notes)
            }
            row.addView(col)
            if (r.pillFrom != null) {
                val p = TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        CxTheme.sp(ctx, 11f))
                    val ph = CxTheme.dp(ctx, 10f); val pv = CxTheme.dp(ctx, 4f)
                    setPadding(ph, pv, ph, pv)
                }
                pillViews[r.key] = p to r
                paintPill(p, r, state)
                row.addView(p, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = CxTheme.dp(ctx, CxMetrics.ROW_GAP_DP)
                })
            }
            if (r.action != null) {
                val b = button(r.action)
                actionViews.add(b)
                row.addView(b, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = CxTheme.dp(ctx, CxMetrics.ROW_GAP_DP)
                })
            }
        })
        infoSpecs[r.key] = r
        return card
    }

    private fun buttons(r: CxRow.Buttons): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = CxTheme.dp(ctx, 6f); bottomMargin = CxTheme.dp(ctx, 6f)
            }
        }
        val gap = CxTheme.dp(ctx, CxMetrics.SEG_GAP_DP + 8f)
        for ((i, b) in r.items.withIndex()) {
            val v = button(b)
            v.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = gap
            }
            actionViews.add(v)
            wrap.addView(v)
        }
        return wrap
    }

    private fun select(r: CxRow.Select, state: CxState): View {
        val card = card(); card.addView(rowShell().also { row ->
            row.addView(titleBlock(r.title, r.subtitleFrom(state), null, NoNotes, 1f))
            /* Цвет текста Spinner задаётся адаптером, а не самим виджетом:
               у Spinner нет setTextColor. Штатный
               simple_spinner_dropdown_item рисует системным цветом, поэтому
               для палитр потребуется свой адаптер — помечено в PLAN.md. */
            val sp = android.widget.Spinner(ctx).apply {
                adapter = android.widget.ArrayAdapter(ctx,
                    android.R.layout.simple_spinner_dropdown_item,
                    r.optionsFrom(state).toMutableList())
                val cur = r.selectedFrom(state)
                val idx = r.optionsFrom(state).indexOf(cur)
                if (idx >= 0) setSelection(idx)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        val opts = r.optionsFrom(state)
                        if (pos in opts.indices) selectSink?.invoke(r.key, opts[pos])
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
            selectViews[r.key] = sp to r
            row.addView(sp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = CxTheme.dp(ctx, CxMetrics.ROW_GAP_DP)
            })
        })
        return card
    }

    private fun banner(r: CxRow.Banner, state: CxState): View {
        val v = TextView(ctx).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, CxMetrics.BTN_TEXT_SP))
            setTextColor(0xFFFFFFFF.toInt())
            val p = CxTheme.dp(ctx, 12f)
            setPadding(CxTheme.dp(ctx, 16f), p, CxTheme.dp(ctx, 16f), p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                val m = CxTheme.dp(ctx, 12f)
                topMargin = m; bottomMargin = m; marginStart = m; marginEnd = m
            }
        }
        bannerViews.add(v to r)
        paintBanner(v, r, state)
        return v
    }

    private fun paintBanner(v: TextView, r: CxRow.Banner, state: CxState) {
        val text = r.textFrom(state)
        v.text = text
        v.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        val busy = state.bool(SOUND_BUSY, false)
        val ok = state.bool(SOUND_SUCCESS, true)
        v.background = roundRect(CxTheme.color(ctx,
            when {
                busy -> R.attr.cxAccent
                ok -> R.attr.cxGreen
                else -> R.attr.cxDanger
            }), CxTheme.dp(ctx, 8f))
    }

    private fun commandInput(r: CxRow.CommandInput): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = CxTheme.dp(ctx, 8f); bottomMargin = CxTheme.dp(ctx, 8f)
            }
        }
        col.addView(TextView(ctx).apply {
            text = r.caption
            setTextColor(CxTheme.color(ctx, R.attr.cxMuted))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 14f))
        })
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CxTheme.dp(ctx, 8f)
        }
        val input = android.widget.EditText(ctx).apply {
            hint = r.hint
            setHintTextColor(CxTheme.color(ctx, R.attr.cxMuted2))
            setTextColor(CxTheme.color(ctx, R.attr.cxInk))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 14f))
            typeface = android.graphics.Typeface.MONOSPACE
            background = roundRect(CxTheme.color(ctx, R.attr.cxCard2), CxTheme.dp(ctx, 10f))
            setSingleLine(true)   // геттера нет, поэтому не свойство
            val p = CxTheme.dp(ctx, 12f)
            setPadding(p, CxTheme.dp(ctx, 10f), p, CxTheme.dp(ctx, 10f))
        }
        commandInputs[r.key] = input to r
        line.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        /* Кнопка строится общим способом, но её собственный обработчик
           перезаписывается: команда уходит с текстом из поля, а не с фиксированным
           значением. setOnClickListener заменяет ранее назначенный. */
        val run = button(CxRow.Button(r.runAction, "ВЫПОЛНИТЬ", kind = "accent"))
        run.setOnClickListener { submitCommand(r.key) }
        actionViews.add(run)
        line.addView(run, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = CxTheme.dp(ctx, 8f)
        })
        col.addView(line)

        if (r.quick.isNotEmpty()) {
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            wrap.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = CxTheme.dp(ctx, 6f)
            }
            var line2: LinearLayout? = null
            for ((i, q) in r.quick.withIndex()) {
                if (i % 3 == 0) {
                    line2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    wrap.addView(line2)
                }
                val b = TextView(ctx).apply {
                    text = q.first
                    gravity = Gravity.CENTER
                    setTextColor(CxTheme.color(ctx, R.attr.cxOnBtn))
                    background = roundRect(CxTheme.color(ctx, R.attr.cxTrack),
                        CxTheme.dp(ctx, CxMetrics.SEGB_RADIUS_DP))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12f))
                    val ph = CxTheme.dp(ctx, 10f); val pv = CxTheme.dp(ctx, 8f)
                    setPadding(ph, pv, ph, pv)
                    setOnClickListener {
                        commandInputs[r.key]?.first?.setText(q.second)
                        submitCommand(r.key)
                    }
                }
                line2?.addView(b, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val g = CxTheme.dp(ctx, 6f)
                    marginStart = g; marginEnd = g
                    topMargin = g; bottomMargin = g
                })
            }
            col.addView(wrap)
        }
        return col
    }

    /** Отправка команды; пустая не уходит — как `if(!inp.value.trim()) return` в шелле. */
    private fun submitCommand(key: String) {
        val pair = commandInputs[key] ?: return
        val text = pair.first.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        act.act(pair.second.runAction, text, pair.second.valueField)
    }

    private val progressViews = LinkedHashMap<String, Pair<View, CxRow.Progress>>()

    private fun progress(r: CxRow.Progress, state: CxState): View {
        val track = android.widget.FrameLayout(ctx).apply {
            background = roundRect(CxTheme.color(ctx, R.attr.cxTrack), CxTheme.dp(ctx, 999f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, CxTheme.dp(ctx, 10f)).apply {
                topMargin = CxTheme.dp(ctx, 8f); bottomMargin = CxTheme.dp(ctx, 8f)
            }
        }
        val fill = View(ctx).apply {
            background = roundRect(CxTheme.color(ctx, R.attr.cxAccent), CxTheme.dp(ctx, 999f))
        }
        track.addView(fill, android.widget.FrameLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT))
        progressViews[r.key] = fill to r
        applyProgress(fill, r, state, track.width)
        // ширина track до раскладки неизвестна — пересчитаем после неё
        track.post { applyProgress(fill, r, state, track.width) }
        return track
    }

    private fun applyProgress(fill: View, r: CxRow.Progress, state: CxState, widthPx: Int) {
        val pct = r.valueFrom(state).coerceIn(0, 100)
        val lp = fill.layoutParams
        lp.width = if (widthPx > 0) widthPx * pct / 100 else 0
        fill.layoutParams = lp
    }

    private fun console(r: CxRow.Console, state: CxState): View {
        val scroll = ScrollView(ctx).apply {
            background = roundRect(CONSOLE_BG, CxTheme.dp(ctx, 10f))
            val p = CxTheme.dp(ctx, 12f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CxTheme.dp(ctx, r.maxHeightDp.toFloat())).apply {
                topMargin = CxTheme.dp(ctx, 10f); bottomMargin = CxTheme.dp(ctx, 8f)
            }
        }
        val tv = TextView(ctx).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(r.textColor)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12f))
            text = r.textFrom(state).ifEmpty { r.placeholder }
        }
        consoleViews[r.key] = tv to r
        scroll.addView(tv)
        return scroll
    }

    /**
     * Плитки палитр. Выбор вызывает [paletteSink], который ставит слой обвязки:
     * в шелле это был `cxSetTheme(id)`, здесь — `CxPalettes.select`, поскольку
     * смена темы требует пересоздания активности.
     */
    private fun themeTiles(state: CxState): View {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            val p = CxTheme.dp(ctx, CxMetrics.THEME_GAP_DP)
            topMargin = CxTheme.dp(ctx, 4f); bottomMargin = CxTheme.dp(ctx, 14f)
            leftMargin = CxTheme.dp(ctx, 2f); rightMargin = CxTheme.dp(ctx, 2f)
        }
        /* Подсвечивается ВЫБРАННАЯ палитра, а не действующая: при включённом
           «Следовать теме ГУ» они расходятся, и плитки показывают выбор
           пользователя — как `setTheme` в шелле против `body[data-th]`. */
        val current = themeStore?.let { CxPalettes.indexOfId(it.chosenId()) } ?: -1
        var line: LinearLayout? = null
        for ((i, pal) in CxPalettes.ALL.withIndex()) {
            if (i % 3 == 0) {
                line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                wrap.addView(line, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val tile = paletteTile(pal, pal.index == current)
            tile.setOnClickListener { paletteSink?.invoke(pal.id) }
            line?.addView(tile, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                val g = CxTheme.dp(ctx, CxMetrics.THEME_GAP_DP)
                marginStart = g; marginEnd = g; topMargin = g; bottomMargin = g
            })
        }
        return wrap
    }

    /** Одна плитка: превью страницы с карточкой и подписью — как `thmPrev`. */
    private fun paletteTile(pal: CxPalette, selected: Boolean): LinearLayout {
        val c = pal.preview   // page1, card, ink, muted, accent, green
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(CxTheme.color(ctx,
                if (selected) R.attr.cxSel else R.attr.cxCard),
                CxTheme.dp(ctx, CxMetrics.THEME_RADIUS_DP))
            val p = CxTheme.dp(ctx, CxMetrics.THEME_PAD_DP)
            setPadding(p, p, p, p)
        }
        val prev = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(c[0], CxTheme.dp(ctx, 8f))
            val p = CxTheme.dp(ctx, 8f)
            setPadding(p, p, p, p)
        }
        prev.addView(View(ctx).apply {
            background = roundRect(c[2], CxTheme.dp(ctx, 3f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, CxTheme.dp(ctx, 6f))
        })
        val cardPrev = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(c[1], CxTheme.dp(ctx, 5f))
            val p = CxTheme.dp(ctx, 6f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = CxTheme.dp(ctx, 6f)
            }
        }
        cardPrev.addView(View(ctx).apply {
            background = roundRect(c[3], CxTheme.dp(ctx, 2f))
            layoutParams = LinearLayout.LayoutParams(
                CxTheme.dp(ctx, 40f), CxTheme.dp(ctx, 5f))
        })
        val dots = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        dots.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CxTheme.dp(ctx, 5f)
        }
        for (col in intArrayOf(c[3], c[4], c[5])) {
            dots.addView(View(ctx).apply {
                background = roundRect(col, CxTheme.dp(ctx, 999f))
            }, LinearLayout.LayoutParams(CxTheme.dp(ctx, 9f), CxTheme.dp(ctx, 9f)).apply {
                marginEnd = CxTheme.dp(ctx, 4f)
            })
        }
        cardPrev.addView(dots)
        prev.addView(cardPrev)
        tile.addView(prev)
        tile.addView(TextView(ctx).apply {
            text = pal.title + if (selected) " ✓" else ""
            setTextColor(CxTheme.color(ctx,
                if (selected) R.attr.cxOnSel else R.attr.cxInk))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12f))
            setPadding(0, CxTheme.dp(ctx, 7f), 0, 0)
        })
        return tile
    }

    /** Снимок, с которым страница построена или обновлена. Нужен плиткам:
     *  состояние ДО переключения уходит в payload, как в шелле. */
    var lastState: CxState = CxState.EMPTY
        private set

    private val tileViews = LinkedHashMap<String, MutableList<Pair<String, View>>>()
    private val tileSpecs = LinkedHashMap<String, CxRow.TileGrid>()

    private fun tileGrid(r: CxRow.TileGrid, state: CxState): View {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val sel = r.selectedFrom(state)
        val views = mutableListOf<Pair<String, View>>()
        var line: LinearLayout? = null
        for ((i, tile) in r.tiles.withIndex()) {
            if (i % r.columns == 0) {
                line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                wrap.addView(line, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val v = tileView(r, tile, sel.contains(tile.value))
            v.setOnClickListener { onTileClick(r, tile) }
            views.add(tile.value to v)
            line?.addView(v, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                val g = CxTheme.dp(ctx, CxMetrics.GRID_GAP_DP)
                marginStart = g; marginEnd = g; topMargin = g; bottomMargin = g
            })
        }
        tileViews[r.key] = views
        tileSpecs[r.key] = r
        return wrap
    }

    private fun tileView(r: CxRow.TileGrid, tile: CxTile, selected: Boolean): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundRect(CxTheme.color(ctx,
                if (selected) R.attr.cxSel else R.attr.cxCard2),
                CxTheme.dp(ctx, CxMetrics.TILE_RADIUS_DP))
            setPadding(CxTheme.dp(ctx, CxMetrics.TILE_PAD_H_DP),
                CxTheme.dp(ctx, CxMetrics.TILE_PAD_TOP_DP),
                CxTheme.dp(ctx, CxMetrics.TILE_PAD_H_DP),
                CxTheme.dp(ctx, CxMetrics.TILE_PAD_BOTTOM_DP))
        }
        val fg = CxTheme.color(ctx, if (selected) R.attr.cxOnSel else R.attr.cxInk)
        val icon = CxTheme.dp(ctx, CxMetrics.TILE_ICON_DP)
        if (tile.emoji != null) {
            col.addView(TextView(ctx).apply {
                text = tile.emoji
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    CxTheme.sp(ctx, CxMetrics.MODE_EMOJI_SP))
            })
        } else if (tile.drawable != null) {
            col.addView(android.widget.ImageView(ctx).apply {
                val id = ctx.resources.getIdentifier(
                    tile.drawable, "drawable", ctx.packageName)
                if (id != 0) setImageResource(id)
                setColorFilter(fg)
            }, LinearLayout.LayoutParams(icon, icon))
        }
        col.addView(TextView(ctx).apply {
            text = tile.label
            gravity = Gravity.CENTER
            setTextColor(fg)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 12f))
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                CxTheme.dp(ctx, CxMetrics.TILE_GAP_DP)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = CxTheme.dp(ctx, CxMetrics.TILE_GAP_DP)
        })
        if (tile.sub != null) col.addView(TextView(ctx).apply {
            text = tile.sub
            gravity = Gravity.CENTER
            setTextColor(CxTheme.color(ctx,
                if (selected) R.attr.cxOnSel else R.attr.cxMuted))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, CxTheme.sp(ctx, 10.5f))
        })
        if (tile.levels > 0) {
            val lvl = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            lvl.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = CxTheme.dp(ctx, 4f)
            }
            for (i in 0 until tile.levels) {
                lvl.addView(View(ctx).apply {
                    setBackgroundColor(CxTheme.color(ctx,
                        if (selected) R.attr.cxOnSel else R.attr.cxTrack))
                }, LinearLayout.LayoutParams(CxTheme.dp(ctx, 7f), CxTheme.dp(ctx, 3f)).apply {
                    marginEnd = CxTheme.dp(ctx, 2f)
                })
            }
            col.addView(lvl)
        }
        return col
    }

    /**
     * Форма события и состав payload — дословно из обработчика клика шелла.
     * `on` и `level` передаются в состоянии ДО переключения: в шелле класс
     * читался в момент клика, а переключал элемент уже нативный обработчик.
     */
    private fun onTileClick(r: CxRow.TileGrid, tile: CxTile) {
        val was = r.selectedFrom(lastState).contains(tile.value)
        when (tile.kindOverride ?: r.kind) {
            CxTileKind.DRIVE_MODE ->
                act.act("drive.mode", mapOf("mode" to tile.value))
            CxTileKind.DRIVE_RECUP ->
                act.act("drive.recup", mapOf("value" to tile.value))
            CxTileKind.CLIMATE_AIR ->
                act.act(if (tile.toggle) "climate.recirc" else "climate.air",
                    mapOf("value" to tile.value, "on" to was.toString()))
            CxTileKind.CLIMATE_HEAT ->
                act.act("climate.heat",
                    mapOf("name" to tile.value, "on" to was.toString()))
            CxTileKind.CLIMATE_SEAT ->
                act.act("climate.seat",
                    mapOf("name" to tile.value, "level" to (if (was) 0 else 1).toString()))
        }
    }

    /**
     * Приёмник выбора палитры; ставится слоем обвязки и должен вызвать
     * [CxPalettes.select], а не только сменить тему: выбор пишется в
     * `ui_shell_theme_id`, пересчитывается действующая палитра и зеркалится во
     * Flutter-настройки, иначе экран телеметрии разъедется с оболочкой.
     */
    var paletteSink: ((id: String) -> Unit)? = null

    /**
     * Перехват действий, которые не уходят в нативную часть. Возврат true
     * означает «обработано локально».
     *
     * Нужно для открытия модальных окон: в шелле кнопке «Управление списком»
     * соответствовал прямой вызов `cxOpenKeepAliveModal()`, а не событие,
     * хотя атрибут `data-cxact="perf.manageKeepAlive"` у неё был. Без
     * перехвата действие ушло бы в `tools.action` и никто бы его не обработал.
     */
    var localAction: ((action: String) -> Boolean)? = null

    /** Хранилище палитры; ставится слоем обвязки. Без него плитки не подсвечены. */
    var themeStore: CxThemeStore? = null

    /** Доступность контролов на время длительной операции — аналог `disAttr`. */
    fun setBusy(busy: Boolean) {
        for (v in actionViews) v.isEnabled = !busy
        for ((_, v) in toggles) v.isEnabled = !busy
        for ((_, list) in segments) for ((_, sv) in list) sv.isEnabled = !busy
        for ((_, sb) in sliders) sb.isEnabled = !busy
        for ((_, pair) in selectViews) pair.first.isEnabled = !busy
        for ((_, pair) in commandInputs) pair.first.isEnabled = !busy
    }

    /** Приёмник выбора из списка; ставится слоем обвязки. */
    var selectSink: ((key: String, value: String) -> Unit)? = null

    // ── обновление на месте ─────────────────────────────────────────────

    /**
     * Аналог `cxSyncControls` + `cxPaintNotes`: значения и подписи
     * обновляются у уже созданных контролов, иерархия не пересобирается.
     */
    fun bind(state: CxState, notes: CxNoteSource) {
        for ((key, view) in toggles) {
            if (!state.has(key)) continue
            val on = state.bool(key, false)
            if (view.isChecked != on) view.isChecked = on
        }
        for ((key, list) in segments) {
            if (!state.has(key)) continue
            paintSegment(list, state.string(key, ""))
        }
        for ((key, sb) in sliders) {
            val spec = sliderSpecs[key] ?: continue
            if (!state.has(key)) continue
            val v = state.int(key, spec.def)
            val p = toProgress(spec, v)
            if (sb.progress != p) sb.progress = p
            sliderLabels[key]?.text = v.toString() + spec.unit
        }
        for ((key, spec) in infoSpecs) {
            infoValueViews[key]?.text = spec.valueFrom(state)
        }
        for ((key, pair) in selectViews) {
            val (spinner, spec) = pair
            val opts = spec.optionsFrom(state)
            val cur = (spinner.adapter as? android.widget.ArrayAdapter<String>)
            if (cur == null || cur.count != opts.size ||
                (0 until cur.count).any { cur.getItem(it) != opts[it] }) {
                spinner.adapter = android.widget.ArrayAdapter(ctx,
                    android.R.layout.simple_spinner_dropdown_item, opts.toMutableList())
            }
            val sel = opts.indexOf(spec.selectedFrom(state))
            if (sel >= 0 && spinner.selectedItemPosition != sel) spinner.setSelection(sel)
        }
        for ((v, spec) in bannerViews) paintBanner(v, spec, state)
        for ((_, pair) in pillViews) paintPill(pair.first, pair.second, state)
        lastState = state
        for ((tv, spec) in dynamicLabels) spec.labelFrom?.let { tv.text = it(state) }
        for ((_, pair) in progressViews) {
            val (fill, spec) = pair
            val parent = fill.parent as? View
            applyProgress(fill, spec, state, parent?.width ?: 0)
        }
        for ((_, pair) in consoleViews) {
            val (tv, spec) = pair
            val t = spec.textFrom(state)
            tv.text = t.ifEmpty { spec.placeholder }
        }
        for (key in noteViews.keys.toList()) paintNote(key, notes)
    }

    private fun paintNote(key: String, notes: CxNoteSource) {
        val view = noteViews[key] ?: return
        val n = notes.noteFor(key)
        if (n == null || n.text.isEmpty()) {
            view.text = ""
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = n.text
        view.setTextColor(CxTheme.color(ctx,
            if (n.isFailure) R.attr.cxDanger else R.attr.cxGreen))
    }

    private fun paintSegment(list: List<Pair<String, TextView>>, current: String) {
        for ((value, view) in list) {
            val sel = value == current
            view.background = if (sel)
                roundRect(CxTheme.color(ctx, R.attr.cxSel),
                    CxTheme.dp(ctx, CxMetrics.SEGB_RADIUS_DP)) else null
            view.setTextColor(CxTheme.color(ctx,
                if (sel) R.attr.cxOnSel else R.attr.cxMuted))
        }
    }

    // ── вспомогательное ─────────────────────────────────────────────────

    private fun stepsOf(r: CxRow.Slider): Int {
        val step = if (r.step <= 0) 1 else r.step
        return ((r.max - r.min) / step).coerceAtLeast(1)
    }

    private fun toProgress(r: CxRow.Slider, value: Int): Int {
        val step = if (r.step <= 0) 1 else r.step
        return ((value - r.min) / step).coerceIn(0, stepsOf(r))
    }

    private fun fromProgress(r: CxRow.Slider, progress: Int): Int {
        val step = if (r.step <= 0) 1 else r.step
        return (r.min + progress * step).coerceIn(r.min, r.max)
    }

    private fun roundRect(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
}
