// Кнопка быстрой смены темы — обычные View (Android). Значок и поведение те же, что у web/cv-theme.js и Compose:
// нажатие — следующая тема (её имя на секунду рядом), долгое нажатие — список. Место — справа в верхней панели главного
// экрана; одна на экран. В модуле (CvHostTheme.hosted(me)) кнопки нет: тему задаёт хост.
//
//   toolbar.addView(CvThemeButton(this).apply {
//       me = "vpn"                                       // имя программы (как в CvHostTheme)
//       current = { prefs.theme }                        // CvTheme
//       onPick = { prefs.theme = it; CvHostTheme.set("vpn", it); CvFf7Views.refresh() }
//       allowed = { CvTheme.ORDER }                      // если программа знает не все темы
//   })
// В меню панели (пункт с android:icon="@drawable/cv_ic_theme"): CvThemeButton.choose(activity, current, allowed, onPick).
package uk.zjd.cvdesign

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast

class CvThemeButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** Имя программы (как в CvHostTheme.set): внутри чужой программы кнопки нет. */
    var me: String = ""
        set(v) { field = v; update() }
    /** Тема программы сейчас. */
    var current: () -> CvTheme = { CvTheme.DARK }
        set(v) { field = v; update() }
    /** Темы, которые программа умеет. */
    var allowed: () -> List<CvTheme> = { CvTheme.ORDER }
        set(v) { field = v; update() }
    /** Выбор человека: сохранить и применить. */
    var onPick: (CvTheme) -> Unit = {}
    /** Цвет значка; null — из темы экрана (colorControlNormal). */
    var tint: Int? = null
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val star = Path()
    private var unhook: (() -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
        setOnClickListener {
            val t = CvTheme.next(current(), allowed())
            if (t == current()) return@setOnClickListener
            onPick(t)
            update()
            Toast.makeText(context.applicationContext, t.title(russian(context)), Toast.LENGTH_SHORT).show()
        }
        setOnLongClickListener {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            choose(this, current(), allowed()) { onPick(it); update() }
            true
        }
    }

    /** Тема сменилась не этой кнопкой. */
    fun update() {
        val show = !CvHostTheme.hosted(me) && allowed().size > 1
        visibility = if (show) VISIBLE else GONE
        contentDescription = (if (russian(context)) "Тема: " else "Theme: ") + current().title(russian(context))
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(resolveSize((48 * dp).toInt(), widthMeasureSpec), resolveSize((48 * dp).toInt(), heightMeasureSpec))

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        unhook = CvHostTheme.onChange { post { update() } }
        update()
    }

    override fun onDetachedFromWindow() {
        unhook?.invoke()
        unhook = null
        super.onDetachedFromWindow()
    }

    private fun color(): Int {
        tint?.let { return it }
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.colorControlNormal, tv, true)) {
            if (tv.resourceId != 0) runCatching { return context.resources.getColor(tv.resourceId, context.theme) }
            else return tv.data
        }
        return if (current().dark) 0xFFF2F4FA.toInt() else 0xFF14213D.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val u = dp                                  // значок 24×24 dp по центру
        val c = color()
        canvas.save()
        canvas.translate(width / 2f - 12 * u, height / 2f - 12 * u)
        line.color = c
        line.strokeWidth = 1.8f * u
        fill.shader = null
        fill.color = c
        canvas.drawCircle(12 * u, 12 * u, 5.2f * u, line)
        rect.set(6.8f * u, 6.8f * u, 17.2f * u, 17.2f * u)
        canvas.drawArc(rect, -90f, 180f, true, fill)
        canvas.drawLine(12 * u, 1.8f * u, 12 * u, 4 * u, line)
        canvas.drawLine(12 * u, 20 * u, 12 * u, 22.2f * u, line)
        canvas.drawLine(1.8f * u, 12 * u, 4 * u, 12 * u, line)
        canvas.drawLine(4.8f * u, 4.8f * u, 6.4f * u, 6.4f * u, line)
        canvas.drawLine(4.8f * u, 19.2f * u, 6.4f * u, 17.6f * u, line)
        star(canvas, 19 * u, 6 * u, 2.4f * u)
        star(canvas, 20.2f * u, 14.8f * u, 1.8f * u)
        // точка — цвета нынешней темы
        val r = 4.5f * u
        val x = 20 * u
        val y = 20 * u
        fill.shader = LinearGradient(x - r, y - r, x + r, y + r, colors(current()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, fill)
        line.color = 0xD9FFFFFF.toInt()
        line.strokeWidth = 1.2f * u
        canvas.drawCircle(x, y, r, line)
        canvas.restore()
    }

    private fun star(canvas: Canvas, x: Float, y: Float, r: Float) {
        val k = r * 0.29f
        star.reset()
        star.moveTo(x, y - r); star.lineTo(x + k, y - k); star.lineTo(x + r, y); star.lineTo(x + k, y + k)
        star.lineTo(x, y + r); star.lineTo(x - k, y + k); star.lineTo(x - r, y); star.lineTo(x - k, y - k); star.close()
        canvas.drawPath(star, fill)
    }

    companion object {
        internal fun russian(c: Context): Boolean =
            c.resources.configuration.locales[0].language in setOf("ru", "uk", "be", "kk")

        /** Точка темы: те же цвета, что в вебе. */
        fun colors(t: CvTheme): IntArray = when (t) {
            CvTheme.DARK -> intArrayOf(0xFF0E1320.toInt(), 0xFF2F8CFF.toInt())
            CvTheme.FF7 -> intArrayOf(0xFF4266C8.toInt(), 0xFF101F6E.toInt())
            CvTheme.VICE_NIGHT -> intArrayOf(0xFFFF2BD6.toInt(), 0xFF7A1FD6.toInt(), 0xFF00E5FF.toInt())
            CvTheme.VICE_DAY -> intArrayOf(0xFF18C4FF.toInt(), 0xFFFFF3E2.toInt(), 0xFFFF6F61.toInt())
            CvTheme.LIGHT -> intArrayOf(0xFFFFFFFF.toInt(), 0xFFC9D4E6.toInt())
        }

        /** Список тем у [anchor] (долгое нажатие, пункт меню). */
        fun choose(anchor: View, current: CvTheme, allowed: List<CvTheme>, onPick: (CvTheme) -> Unit) {
            val ru = russian(anchor.context)
            val menu = PopupMenu(anchor.context, anchor, Gravity.END)
            val list = CvTheme.ORDER.filter { it in allowed }
            list.forEachIndexed { i, t -> menu.menu.add(1, i, i, t.title(ru)).isChecked = t == current }
            menu.menu.setGroupCheckable(1, true, true)
            list.indexOf(current).takeIf { it >= 0 }?.let { menu.menu.getItem(it).isChecked = true }
            menu.setOnMenuItemClickListener { item ->
                list.getOrNull(item.itemId)?.let { if (it != current) onPick(it) }
                true
            }
            menu.show()
        }

        /** Список тем для пункта меню панели. */
        fun choose(activity: Activity, current: CvTheme, allowed: List<CvTheme> = CvTheme.ORDER, onPick: (CvTheme) -> Unit) =
            choose(activity.window.decorView.findViewById<View>(android.R.id.content), current, allowed, onPick)
    }
}
