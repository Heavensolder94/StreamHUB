package de.streamhub.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

internal object Ui {
    const val BG = 0xFF080D16.toInt()
    const val PANEL = 0xFF121D2D.toInt()
    const val BLUE = 0xFF48AFFF.toInt()
    const val TEXT = Color.WHITE
    const val MUTED = 0xFF9EB1CC.toInt()

    fun Context.dp(value: Int): Int = (resources.displayMetrics.density * value + 0.5f).toInt()

    fun bg(color: Int, radiusDp: Float = 16f, stroke: Int? = null, context: Context): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.resources.displayMetrics.density * radiusDp
            if (stroke != null) setStroke(context.dp(2), stroke)
        }
    }

    fun text(context: Context, text: String, size: Float = 18f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    fun button(context: Context, label: String, compact: Boolean = false, onClick: () -> Unit): TextView {
        val button = text(context, label, if (compact) 14f else 19f, TEXT, true)
        button.isFocusable = true
        button.isClickable = true
        button.gravity = Gravity.CENTER
        button.setPadding(context.dp(if (compact) 14 else 18), context.dp(10), context.dp(if (compact) 14 else 18), context.dp(10))
        button.background = bg(PANEL, 11f, context = context)
        button.setOnFocusChangeListener { view: View, hasFocus: Boolean ->
            view.background = bg(if (hasFocus) BLUE else PANEL, 11f, context = context)
            button.setTextColor(if (hasFocus) BG else TEXT)
            view.scaleX = if (hasFocus) 1.035f else 1f
            view.scaleY = if (hasFocus) 1.035f else 1f
        }
        button.setOnClickListener { onClick() }
        return button
    }

    fun vertical(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)
}
