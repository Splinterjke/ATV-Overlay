package app.atv.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Ui {

    val BG_TOP = Color.rgb(34, 34, 34)
    val BG_BOTTOM = Color.rgb(34, 34, 34)
    val CARD = Color.rgb(43, 43, 43)
    val CARD_STROKE = Color.rgb(58, 58, 58)
    val CODE_BG = Color.rgb(38, 38, 38)

    val BTN = Color.rgb(51, 51, 51)
    val BTN_HOVER = Color.rgb(62, 62, 62)
    val BTN_PRESSED = Color.rgb(74, 74, 74)
    val BTN_STROKE = Color.rgb(58, 58, 58)

    val ACCENT = Color.rgb(253, 147, 83)
    val TEXT = Color.rgb(247, 241, 255)
    val MUTED = Color.rgb(166, 162, 171)
    val OK = Color.rgb(123, 216, 143)
    val WARN = Color.rgb(252, 229, 102)
    val ERR = Color.rgb(252, 97, 141)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun background(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(BG_TOP, BG_BOTTOM),
    )

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(ctx, 11f).toFloat()
            setStroke(dp(ctx, 1f).coerceAtLeast(1), CARD_STROKE)
        }
        val p = dp(ctx, 12f)
        setPadding(p, p, p, p)
    }

    fun cardParams(ctx: Context, marginBottomDp: Float = 10f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, marginBottomDp) }

    fun section(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title.uppercase()
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.16f
        setPadding(0, 0, 0, dp(ctx, 5f))
    }

    fun text(ctx: Context, sizeSp: Float = 12.5f, color: Int = TEXT): TextView =
        TextView(ctx).apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setLineSpacing(0f, 1.15f)
        }

    fun codeBlock(ctx: Context, sizeSp: Float = 10f): TextView = TextView(ctx).apply {
        setTextColor(MUTED)
        setTypeface(Typeface.MONOSPACE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setLineSpacing(0f, 1.2f)
        background = GradientDrawable().apply {
            setColor(CODE_BG)
            cornerRadius = dp(ctx, 9f).toFloat()
            setStroke(dp(ctx, 1f).coerceAtLeast(1), CARD_STROKE)
        }
        val p = dp(ctx, 10f)
        setPadding(p, p, p, p)
    }

    fun chip(ctx: Context, label: String): TextView = TextView(ctx).apply {
        text = label
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(ctx, 18f).toFloat()
            setStroke(dp(ctx, 1f).coerceAtLeast(1), Color.argb(120, 56, 189, 248))
        }
        setPadding(dp(ctx, 8f), dp(ctx, 2f), dp(ctx, 8f), dp(ctx, 2f))
    }

    private fun roundRect(
        ctx: Context,
        fill: Int,
        stroke: Int,
        strokeDp: Float,
        insetDp: Float = 0f,
    ): android.graphics.drawable.Drawable {
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, 9f).toFloat()
            if (stroke != 0) setStroke(dp(ctx, strokeDp).coerceAtLeast(1), stroke)
        }
        val inset = dp(ctx, insetDp)
        return if (inset > 0) InsetDrawable(shape, inset) else shape
    }

    private fun buttonBackground(ctx: Context): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            roundRect(ctx, BTN_PRESSED, ACCENT, 2.5f, insetDp = 3f),
        )
        addState(
            intArrayOf(android.R.attr.state_focused),
            roundRect(ctx, BTN_HOVER, ACCENT, 2.5f, insetDp = 3f),
        )
        addState(
            intArrayOf(-android.R.attr.state_enabled),
            roundRect(ctx, Color.rgb(38, 38, 40), BTN_STROKE, 1f),
        )
        addState(intArrayOf(), roundRect(ctx, BTN, BTN_STROKE, 1f))
    }


    fun button(ctx: Context, label: String, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(TEXT)
        stateListAnimator = null
        background = buttonBackground(ctx)
        val h = dp(ctx, 10f)
        val v = dp(ctx, 5f)
        setPadding(h, v, h, v)
        minimumHeight = dp(ctx, 40f)
        setOnClickListener { onClick() }
    }

    fun row(ctx: Context, gapDp: Float = 8f, vararg buttons: Button): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, gapDp) }
            buttons.forEachIndexed { i, b ->
                b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(ctx, gapDp)
                }
                addView(b)
            }
        }

    fun input(ctx: Context): EditText = EditText(ctx).apply {
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        background = GradientDrawable().apply {
            setColor(CODE_BG)
            cornerRadius = dp(ctx, 9f).toFloat()
            setStroke(dp(ctx, 1f).coerceAtLeast(1), BTN_STROKE)
        }
        backgroundTintList = null
        val h = dp(ctx, 11f)
        val v = dp(ctx, 8f)
        setPadding(h, v, h, v)
        setOnFocusChangeListener { v2, has ->
            (v2.background as? GradientDrawable)?.setStroke(
                dp(ctx, if (has) 2f else 1f).coerceAtLeast(1),
                if (has) ACCENT else BTN_STROKE,
            )
        }
    }

    fun statusLine(sb: SpannableStringBuilder, dot: Int, label: String, value: String, valueColor: Int) {
        val start = sb.length
        sb.append("● ").append(label).append("  ").append(value).append('\n')
        sb.setSpan(ForegroundColorSpan(dot), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(
            ForegroundColorSpan(MUTED), start + 2, start + 2 + label.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.setSpan(
            ForegroundColorSpan(valueColor), start + 2 + label.length + 2, sb.length - 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.setSpan(StyleSpan(Typeface.BOLD), start + 2 + label.length + 2, sb.length - 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun monoAccent(sb: SpannableStringBuilder, text: String) {
        val start = sb.length
        sb.append(text).append('\n')
        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(ACCENT), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun dim(sb: SpannableStringBuilder, text: String) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(MUTED), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
