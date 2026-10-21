package com.example.voiceapp3

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CommandsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        AppTheme.applyToWindow(this)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.decorView.isForceDarkAllowed = false
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3F5F8"))
            val p = dp(24)
            setPadding(p, dp(16), p, dp(16))
        }

        col.addView(Button(this).apply {
            text = "Назад"
            setBackgroundColor(Color.parseColor("#1F9A64"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setOnClickListener { finish() }
        })

        col.addView(TextView(this).apply {
            text = "Команды"
            setTextColor(Color.parseColor("#0F172A"))
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        })

        val blocks = listOf(
            "Климат" to "Включи кондиционер. Сделай теплее. Поставь 22 градуса. Быстрый обогрев. Быстрое охлаждение.",
            "Стёкла" to "Подогрев лобового стекла. Подогрев заднего стекла. Обдув лобового стекла.",
            "Обдув" to "Обдув на 4. Сильнее обдув. Обдув в лицо. Обдув на стекло.",
            "Сиденья" to "Подогрев сиденья на 1, 2 или 3. Подогрев руля.",
            "Окна" to "Открой левое окно. Закрой все окна. Чуть открой окно.",
            "Режимы" to "Режим эко. Режим комфорт. Режим спорт.",
            "Рекуперация" to "Рекуперация слабая. Рекуперация средняя. Рекуперация сильная.",
            "Кузов" to "Открой багажник. Открой передний багажник.",
            "Свет" to "Включи аварийку. Выключи аварийку."
        )
        for ((title, body) in blocks) {
            col.addView(TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(4))
            })
            col.addView(TextView(this).apply {
                text = body
                setTextColor(Color.parseColor("#64748B"))
                textSize = 16f
            })
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}