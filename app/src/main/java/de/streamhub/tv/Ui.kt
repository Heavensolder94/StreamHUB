package de.streamhub.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

internal object Ui {
    const val BG = 0xFF080B12.toInt()
    const val PANEL = 0xFF171D2B.toInt()
    const val BLUE = 0xFF6AB7FF.toInt()
    const val TEXT = Color.WHITE
    const val MUTED = 0xFF9AA7BD.toInt()
    const val SOFT = 0xFFCDD6E6.toInt()
    const val BORDER = 0xFF273044.toInt()

    fun Context.dp(value: Int): Int = (resources.displayMetrics.density * value + 0.5f).toInt()

    fun bg(color: Int, radiusDp: Float = 16f, stroke: Int? = null, context: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.resources.displayMetrics.density * radiusDp
            if (stroke != null) setStroke(context.dp(2), stroke)
        }

    fun gradient(context: Context, colors: IntArray, radiusDp: Float = 18f, orientation: GradientDrawable.Orientation =
        GradientDrawable.Orientation.LEFT_RIGHT): GradientDrawable =
        GradientDrawable(orientation, colors).apply {
            cornerRadius = context.resources.displayMetrics.density * radiusDp
        }

    fun text(context: Context, text: String, size: Float = 18f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            includeFontPadding = true
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    fun button(context: Context, label: String, compact: Boolean = false, onClick: () -> Unit): TextView {
        val button = text(context, label, if (compact) 13f else 16f, TEXT, true)
        button.isFocusable = true
        button.isClickable = true
        button.gravity = Gravity.CENTER
        button.setPadding(context.dp(if (compact) 16 else 20), context.dp(8), context.dp(if (compact) 16 else 20), context.dp(8))
        button.background = bg(PANEL, 11f, BORDER, context)
        button.setOnFocusChangeListener { view, hasFocus ->
            view.background = bg(if (hasFocus) BLUE else PANEL, 11f, if (hasFocus) Color.WHITE else BORDER, context)
            button.setTextColor(if (hasFocus) BG else TEXT)
            view.animate().scaleX(if (hasFocus) 1.045f else 1f)
                .scaleY(if (hasFocus) 1.045f else 1f).setDuration(145).start()
            view.elevation = if (hasFocus) context.dp(9).toFloat() else 0f
        }
        button.setOnClickListener { onClick() }
        return button
    }

    fun vertical(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun lp(w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)
}

/**
 * Built-in cinematic abstract artwork. No third-party poster CDN, no tracking,
 * no remote image dependencies and no copyright-protected film imagery.
 */
internal class CinematicArtwork(
    context: Context,
    private val seed: String,
    private val category: String,
    private val hero: Boolean = false
) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = when (category) {
        "Anime" -> intArrayOf(0xFF081A35.toInt(), 0xFF183F69.toInt(), 0xFF572E76.toInt())
        "Filme" -> intArrayOf(0xFF141529.toInt(), 0xFF4A294F.toInt(), 0xFF955039.toInt())
        "Serien" -> intArrayOf(0xFF091E28.toInt(), 0xFF16516B.toInt(), 0xFF126158.toInt())
        "Mischportale" -> intArrayOf(0xFF171624.toInt(), 0xFF593044.toInt(), 0xFF684F74.toInt())
        else -> intArrayOf(0xFF10243E.toInt(), 0xFF253B6C.toInt(), 0xFF6A315B.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 1f || h < 1f) return
        val variation = (seed.hashCode().toLong() and 0x7fffffff).toFloat() / Int.MAX_VALUE
        p.shader = LinearGradient(0f, h, w, 0f, palette, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * (0.50f + variation * .33f), h * .39f,
            min(w, h) * (if (hero) 1.3f else .78f), intArrayOf(0x99CFA8F4.toInt(), 0x005C97C6), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        // Glowing horizon
        p.color = 0x3365CDFF
        p.strokeWidth = if (hero) 4f else 2f
        canvas.drawLine(0f, h * .68f, w, h * .48f, p)

        // Layered glass-like cinematic shapes
        val offset = variation * w * .20f
        val shards = listOf(
            floatArrayOf(.13f, .20f, .62f, .08f, .53f, .80f),
            floatArrayOf(.52f, .14f, 1.07f, .25f, .77f, 1.05f),
            floatArrayOf(-.14f, .60f, .30f, .37f, .40f, 1.10f)
        )
        shards.forEachIndexed { index, a ->
            val path = Path().apply {
                moveTo(w*a[0]+offset, h*a[1])
                lineTo(w*a[2]+offset, h*a[3])
                lineTo(w*a[4]+offset, h*a[5])
                close()
            }
            p.color = when(index) { 0 -> 0x336BB6FF; 1 -> 0x32F3B2D3; else -> 0x343CA9DB }
            canvas.drawPath(path,p)
        }

        p.color = 0x26FFFFFF
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (hero) 3f else 2f
        val radius = min(w,h) * .32f
        canvas.drawCircle(w * .70f, h * .42f, radius,p)
        canvas.drawCircle(w * .70f, h * .42f, radius * .76f,p)
        p.style = Paint.Style.FILL

        // Graphic architectural silhouettes give every tile its own poster identity.
        val count = if (hero) 14 else 7
        val blockWidth = w / count
        for (i in 0 until count) {
            val heightFraction = .10f + (((i * 13 + seed.length * 7) % 11) / 11f) * .25f
            p.color = if (i % 3 == 0) 0x99232942.toInt() else 0xC60A1020.toInt()
            canvas.drawRect(i*blockWidth-2f, h*(1f-heightFraction), (i+1)*blockWidth+1f, h, p)
        }
        p.color = 0x24FFFFFF
        val label = seed.take(1).uppercase()
        p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = min(w, h) * (if (hero) .72f else .95f)
        canvas.drawText(label, w * (if (hero) .76f else .53f), h * .69f, p)
    }
}
