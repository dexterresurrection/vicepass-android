// Рекламная плашка — обычные View (Android). Правила — android/common/CvAdsData.kt и FORMAT.md.
//
//   val ads = CvAds(File(cacheDir, "cv-ads"))                      // один на приложение (fetch / post — свой HTTP)
//   val plate = CvAdsView(context).apply { this.ads = ads; slot = "vpn.home"; line = "vice"; look = "vice"; day = false }
// line — какие баннеры (линия), look — оформление по теме приложения: "vice" (темы Vice), "ff7" («Классика RPG»),
// "plain" (обычные тёмная/светлая — цвета из темы приложения: colorSurfaceContainer/colorSurface, colorOnSurface,
// colorPrimary/colorOnPrimary), "auto" — по линии, как раньше (vice → Vice, cloud → ff7).
//   container.addView(plate, MATCH_PARENT, WRAP_CONTENT)         // высоту считает сама: wide — низкая (до 52 dp и строка
//                                                                // маркировки, если есть), side — ширина / 2
// У скруглённых нижних углов экрана и полосы жестов плашка сама отступает (CvScreen); в прокрутке — нет. Отключить:
// edgeInsets = false.
// Нет места или баннеров — View сам уходит в GONE. Нажатие — во внешнем браузере (ACTION_VIEW) или onOpen.
package uk.zjd.cvdesign

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import java.util.concurrent.Executors

class CvAdsView(context: Context) : FrameLayout(context) {
    var ads: CvAds? = null
    var slot: String = ""
    /** "vice" или "cloud" */
    var line: String = "cloud"
    var platform: String = "android"
    var day: Boolean = false
    /** Оформление: "auto" (по линии), "vice", "ff7", "plain". */
    var look: String = "auto"
    /** Свой способ открыть ссылку; null — внешний браузер (Intent.ACTION_VIEW). */
    var onOpen: ((String) -> Unit)? = null

    /** Отступать от скруглённых углов экрана и полосы жестов (если плашка стоит у нижнего края, не в прокрутке). */
    var edgeInsets: Boolean = true

    private val dp = resources.displayMetrics.density
    /** Сама плашка: фон, рамка и баннеры; CvAdsView вокруг неё держит отступы от краёв экрана. */
    private val box = FrameLayout(context)
    private val edgeWatch = ViewTreeObserver.OnPreDrawListener { placeAtEdge(); true }
    private val loc = IntArray(2)
    private val main = Handler(Looper.getMainLooper())
    private var feed: CvAds.Feed? = null
    private var plan: CvAds.Plan? = null
    private var cur: CvAds.Ad? = null
    private var shownAt = 0L          // с какого момента текущий баннер виден
    private var visibleMs = 0L        // сколько он уже был виден (для ротации)
    private var counted = false
    private var front: FrameLayout? = null
    private val tick = object : Runnable {
        override fun run() { step(); main.postDelayed(this, 250) }
    }

    init {
        visibility = GONE
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val bw = maxOf(0, w - paddingLeft - paddingRight)
        val h = ((plan?.height(bw / dp, fontScale) ?: (bw / dp / 4f)) * dp).toInt() + paddingTop + paddingBottom
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    private val fontScale get() = resources.configuration.fontScale

    /** Внутри прокрутки отступы не считаем: плашка едет вместе с содержимым. */
    private fun scrolls(): Boolean {
        var p = parent
        while (p is View) {
            if (p.isScrollContainer || p.javaClass.simpleName.contains("ScrollView") || p.javaClass.simpleName.contains("RecyclerView")) return true
            p = p.parent
        }
        return false
    }

    /** Для какого места посчитаны отступы: окно, его углы, ширина и верх плашки (без этого отступы бы «дышали»). */
    private var edgeKey: List<Int>? = null
    private var edgeTop = 0

    /** Отступы от скруглённых углов экрана и полосы жестов; пересчёт — только когда сменилось место или окно. */
    private fun placeAtEdge() {
        if (!edgeInsets || width == 0 || visibility != VISIBLE || plan == null) return
        val screen = CvScreenAndroid.of(this) ?: return
        getLocationInWindow(loc)
        val key = listOf(screen.width.toInt(), screen.height.toInt(), screen.cornerLeft.toInt(), screen.cornerRight.toInt(), screen.bottomBar.toInt(), width, loc[0])
        if (key == edgeKey && kotlin.math.abs(loc[1] - edgeTop) <= paddingBottom + 4 * dp) return
        if (paddingLeft != 0 || paddingRight != 0 || paddingBottom != 0) {   // сначала место без своих отступов
            edgeKey = null
            setPadding(0, 0, 0, 0)
            return
        }
        edgeKey = key
        edgeTop = loc[1]
        if (scrolls()) return
        val ins = screen.insets(loc[0].toFloat(), (loc[0] + width).toFloat(), (loc[1] + height).toFloat(), 14 * dp, dp)
        if (ins.left > 0 || ins.right > 0 || ins.bottom > 0) setPadding(ins.left.toInt(), 0, ins.right.toInt(), ins.bottom.toInt())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(edgeWatch)
        val a = ads ?: return
        io.execute {
            val f = a.load()
            val p = CvAds.plan(f, slot, line, platform)
            main.post {
                feed = f
                plan = p
                if (p == null || f == null) { visibility = GONE; return@post }
                visibility = VISIBLE
                box.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors()).apply {
                    cornerRadius = 14 * dp
                    setStroke((if (lookNow() == "plain") 1 * dp else 2 * dp).toInt(), edge())
                }
                box.clipToOutline = true
                show(CvAds.pick(p, null, slot))
                requestLayout()
                main.removeCallbacks(tick)
                main.post(tick)
            }
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(edgeWatch)
        main.removeCallbacks(tick)
        ads?.let { a -> io.execute { a.flush(true) } }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        if (v != VISIBLE) ads?.let { a -> io.execute { a.flush(true) } }   // ушли в фон — отправить накопленное
    }

    private fun lookNow(): String = if (look == "vice" || look == "ff7" || look == "plain") look else if (line == "vice") "vice" else "ff7"
    private val vice get() = lookNow() == "vice"

    /** Цвет из темы приложения по имени атрибута (Material — без зависимости от библиотеки); нет — null. */
    private fun themeColor(vararg names: String): Int? {
        for (n in names) {
            val id = resources.getIdentifier(n, "attr", context.packageName)
            if (id == 0) continue
            val tv = TypedValue()
            if (!context.theme.resolveAttribute(id, tv, true)) continue
            if (tv.resourceId != 0) runCatching { return context.resources.getColor(tv.resourceId, context.theme) }
            else if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) return tv.data
        }
        return null
    }
    private val plainBg by lazy { themeColor("colorSurfaceContainer", "colorSurface") ?: if (day) Color.WHITE else 0xFF1A1F2B.toInt() }
    private val plainFg by lazy { themeColor("colorOnSurface") ?: if (day) 0xFF14213D.toInt() else 0xFFEEF1F8.toInt() }
    private val plainAccent by lazy { themeColor("colorPrimary") ?: 0xFF2F8CFF.toInt() }
    private val plainOnAccent by lazy { themeColor("colorOnPrimary") ?: Color.WHITE }

    private fun colors(): IntArray = when {
        lookNow() == "plain" -> intArrayOf(plainBg, plainBg)
        day -> intArrayOf(Color.WHITE, Color.WHITE)
        vice -> intArrayOf(0xFF22105C.toInt(), 0xFF0C0628.toInt())
        else -> intArrayOf(0xFF24409E.toInt(), 0xFF14247A.toInt())
    }
    private fun edge(): Int = when {
        lookNow() == "plain" -> (plainFg and 0x00FFFFFF) or 0x24000000
        vice && !day -> 0xFFFF2BD6.toInt(); vice -> 0xFF18C4FF.toInt(); !day -> 0xFFBCC2D6.toInt(); else -> 0x1F000000
    }
    /** Цвет текста оформления (свой fg баннера важнее). */
    private fun fgTheme(): Int = if (lookNow() == "plain") plainFg else if (day) 0xFF0B1F4A.toInt() else 0xFFFFF6FD.toInt()
    /** Кнопка: фон (градиент) и цвет текста. */
    private fun ctaColors(): IntArray = when (lookNow()) {
        "vice" -> intArrayOf(0xFFFF2BD6.toInt(), 0xFFFF7A1A.toInt())
        "plain" -> intArrayOf(plainAccent, plainAccent)
        else -> intArrayOf(0xFF2F8CFF.toInt(), 0xFF2F8CFF.toInt())
    }
    private fun ctaText(): Int = when (lookNow()) { "vice" -> 0xFF12062E.toInt(); "plain" -> plainOnAccent; else -> Color.WHITE }

    /** Видна ли плашка: на экране ≥ 50 % площади и окно приложения на переднем плане. */
    private fun visibleNow(): Boolean {
        if (!isShown || windowVisibility != VISIBLE) return false
        val r = Rect()
        if (!getGlobalVisibleRect(r)) return false
        return r.width().toLong() * r.height() >= width.toLong() * height / 2
    }

    private fun step() {
        val p = plan ?: return
        val f = feed ?: return
        val a = ads ?: return
        val c = cur ?: return
        if (!visibleNow()) { shownAt = 0; return }
        val now = System.currentTimeMillis()
        if (shownAt == 0L) shownAt = now
        visibleMs += 250
        if (!counted && now - shownAt >= 1000) {
            counted = true
            io.execute { a.impression(f, c, slot) }
        }
        if (p.ads.size > 1 && visibleMs >= p.intervalSec * 1000L) show(CvAds.pick(p, c, slot))
    }

    private fun show(ad: CvAds.Ad) {
        cur = ad
        htmlParts = null
        capParts = null
        shownAt = 0
        visibleMs = 0
        counted = false
        contentDescription = ad.title.ifEmpty { ad.text }
        val layer = FrameLayout(context).apply { alpha = 0f }
        val side = plan?.format == "side"
        val strip = plan?.format == "strip"
        val wideMode = if (side || strip) 'f' else wideModeOf(ad)
        shownMode = wideMode
        // картинка слева, подпись справа: полоса без image_strip и низкая wide
        val rowImage = ad.kind != "html" && ((strip && ad.imageStrip.isEmpty()) || wideMode == 'r')
        val fg = runCatching { Color.parseColor(ad.fg) }.getOrElse { fgTheme() }
        runCatching { layer.setBackgroundColor(Color.parseColor(ad.bg)) }
        // у HTML-баннеров метка «Реклама» стоит в строке заголовка, строка рекламодателя — полосой под текстом
        // (ничего не закрывают); у картинок метка — в углу (у полосы с подписью — над подписью), строка — тоже полосой
        val inflow = ad.kind == "html"
        val legalText = ad.mark.legal()
        // есть строка маркировки (рекламодатель или erid) — она уже начинается с «Реклама», отдельную метку не рисуем
        val showLabel = ad.mark.label.isNotEmpty() && legalText == null
        // строка маркировки — всегда отдельной полосой снизу: не закрывает ни текст, ни картинку
        val content: FrameLayout = if (legalText != null) {
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val c = FrameLayout(context)
            wrap.addView(c, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            wrap.addView(legalView(ad.mark), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            layer.addView(wrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            c
        } else layer
        if (strip && ad.kind == "html") {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0) }
            row.addView(TextView(context).apply { text = ad.title.ifEmpty { ad.text }; setTextColor(fg); setTypeface(typeface, android.graphics.Typeface.BOLD); textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            if (ad.cta.isNotEmpty()) row.addView(TextView(context).apply {
                text = ad.cta; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(ctaText())
                setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, ctaColors()).apply { cornerRadius = 999 * dp }
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (10 * dp).toInt() })
            if (showLabel) row.addView(labelView(ad.mark.label), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (10 * dp).toInt() })
            content.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else if (ad.kind == "html") {
            // раскладка под высоту места — adaptHtml(): кнопка в строке заголовка или снизу, текст на оставшиеся строки
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding((12 * dp).toInt(), (7 * dp).toInt(), (12 * dp).toInt(), (7 * dp).toInt()) }
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val title = TextView(context).apply { text = ad.title.ifEmpty { ad.text }; setTextColor(fg); setTypeface(typeface, android.graphics.Typeface.BOLD); textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            head.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val ctaIn = if (ad.cta.isNotEmpty()) chip(ad.cta).also { head.addView(it, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dp).toInt() }) } else null
            if (showLabel) head.addView(labelView(ad.mark.label), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dp).toInt() })
            col.addView(head)
            val body = TextView(context).apply {
                text = if (ad.html.isNotEmpty()) HtmlCompat.fromHtml(CvAds.safeHtml(ad.html), HtmlCompat.FROM_HTML_MODE_COMPACT) else ad.text
                setTextColor(fg); textSize = 13f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(0f, 1f); includeFontPadding = false
            }
            col.addView(body, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (3 * dp).toInt() })
            val ctaBl = if (ad.cta.isNotEmpty()) chip(ad.cta).also { col.addView(it, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() }) } else null
            content.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            htmlParts = HtmlParts(col, title, body, ctaIn, ctaBl, showLabel, legalText != null)
            post { adaptHtml() }
        } else {
            val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val rel = when {
                (strip || wideMode == 's') && ad.imageStrip.isNotEmpty() -> ad.imageStrip
                side && ad.imageSide.isNotEmpty() -> ad.imageSide
                else -> ad.image
            }
            if (rowImage) {
                // картинка слева (полоса — 2/3, низкая wide — 4:1 без обрезки), справа подпись; метка «Реклама» — над
                // подписью, а не поверх неё
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val contentH = plan?.contentHeight(boxWidthDp(), fontScale) ?: 0f
                if (strip) row.addView(img, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 2f))
                else row.addView(img, LinearLayout.LayoutParams((minOf(contentH * 4, boxWidthDp() * 0.68f) * dp).toInt(), LayoutParams.MATCH_PARENT))
                val cap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0) }
                val lab = if (showLabel) labelView(ad.mark.label).also { cap.addView(it, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = (2 * dp).toInt() }) } else null
                val tv = TextView(context).apply {
                    text = ad.title; setTextColor(fgTheme()); includeFontPadding = false
                    setTypeface(typeface, android.graphics.Typeface.BOLD); textSize = 13f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }
                cap.addView(tv)
                row.addView(cap, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                content.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                capParts = CapParts(cap, tv, lab, split = false)
                post { adaptCaption() }
            } else if (side && ad.imageSide.isEmpty()) {
                // подпись берёт свои 1–2 строки (не меньше половины места или 44 dp), картинка — остаток
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                col.addView(img, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                val tv = TextView(context).apply {
                    text = ad.title; setTextColor(fgTheme())
                    setTypeface(typeface, android.graphics.Typeface.BOLD); textSize = 14f; maxLines = 2; gravity = Gravity.CENTER_VERTICAL
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding((12 * dp).toInt(), (3 * dp).toInt(), (12 * dp).toInt(), (3 * dp).toInt())
                }
                col.addView(tv, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                content.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                capParts = CapParts(col, tv, null, split = true)
                post { adaptCaption() }
            } else content.addView(img, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val f = feed
            val a = ads
            if (f != null && a != null) io.execute {
                val bytes = a.image(f, rel)
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                main.post { if (bmp != null) img.setImageBitmap(bmp) }
            }
        }
        val rowCap = rowImage
        if (!inflow && !rowCap && showLabel) layer.addView(labelView(ad.mark.label),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, (6 * dp).toInt(), (8 * dp).toInt(), 0) })
        box.addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val old = front
        front = layer
        val reduce = runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
        if (reduce) { layer.alpha = 1f; old?.let { box.removeView(it) } }
        else {
            layer.animate().alpha(1f).setDuration(300).start()
            old?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction { box.removeView(old) }?.start()
        }
    }

    private var shownMode = 'f'

    /** Ширина плашки, dp: до раскладки — по экрану. */
    private fun boxWidthDp(): Float = (if (box.width > 0) box.width else resources.displayMetrics.widthPixels - (32 * dp).toInt()) / dp

    /** Как рисовать баннер в wide при нынешней ширине (CvAds.Plan.wideImage). */
    private fun wideModeOf(ad: CvAds.Ad): Char =
        if (ad.kind == "html") 'f' else plan?.wideImage(boxWidthDp(), fontScale, ad.imageStrip.isNotEmpty()) ?: 'f'

    private class HtmlParts(val col: LinearLayout, val title: TextView, val body: TextView, val ctaIn: TextView?, val ctaBl: TextView?,
                            val label: Boolean, val legal: Boolean)
    private var htmlParts: HtmlParts? = null

    /** Подпись к картинке (strip без image_strip, side без image_side). */
    private class CapParts(val box: LinearLayout, val text: TextView, val label: View?, val split: Boolean)
    private var capParts: CapParts? = null

    /** Подпись к картинке: в полосе — столько строк, сколько помещается; под картинкой — не меньше min(половина, 44 dp). */
    private fun adaptCaption() {
        val c = capParts ?: return
        if (c.box.height == 0) return
        if (c.split) {
            val min = minOf(c.box.height / 2, (44 * dp).toInt())
            if (c.text.minHeight != min) c.text.minHeight = min
        } else {
            val avail = c.box.height - (c.label?.let { it.height + (2 * dp).toInt() } ?: 0)
            val lines = maxOf(1, avail / maxOf(1, c.text.lineHeight))
            if (c.text.maxLines != lines) c.text.maxLines = lines
        }
    }

    private fun labelView(t: String) = TextView(context).apply {
        text = t; setTextColor(Color.WHITE); maxLines = 1; setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setPadding((6 * dp).toInt(), (1 * dp).toInt(), (6 * dp).toInt(), (1 * dp).toInt())
        background = GradientDrawable().apply { cornerRadius = 5 * dp; setColor(0x73000000) }
    }

    private fun legalView(m: CvAds.Mark) = LegalLine(context, m).apply {
        contentDescription = m.legal()
        setBackgroundColor(0x80000000.toInt())
        setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
    }

    /**
     * Строка маркировки: «Реклама» · рекламодатель · «erid: …». Метка и erid не сжимаются (erid обязан быть виден),
     * многоточием режется только рекламодатель; если ему остаётся меньше 32 dp — его нет совсем.
     */
    private class LegalLine(context: Context, m: CvAds.Mark) : android.view.ViewGroup(context) {
        private fun tv(t: String) = TextView(context).apply {
            text = t; setTextColor(Color.WHITE); textSize = 10f; maxLines = 1; isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val label = tv(m.label.ifEmpty { "Реклама" })
        private val adv = tv(if (m.advertiser.isNotEmpty()) "\u00a0· ${m.advertiser}" else "")
        private val erid = tv(if (m.erid.isNotEmpty()) "\u00a0· erid: ${m.erid}" else "")
        private val minAdv = (32 * resources.displayMetrics.density).toInt()
        private var wrap = false
        init { addView(label); addView(adv); addView(erid) }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            label.measure(free, free)
            erid.measure(free, free)
            adv.measure(free, free)
            val full = if (adv.text.isEmpty()) 0 else adv.measuredWidth
            val total = MeasureSpec.getSize(widthMeasureSpec)
            val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
            val rest = if (bounded) total - paddingLeft - paddingRight - label.measuredWidth - erid.measuredWidth else full
            if (full > 0 && (rest >= full || rest >= minAdv)) {
                adv.visibility = VISIBLE
                adv.measure(MeasureSpec.makeMeasureSpec(maxOf(0, minOf(rest, full)), MeasureSpec.EXACTLY), free)
            } else adv.visibility = GONE
            val advW = if (adv.visibility == VISIBLE) adv.measuredWidth else 0
            // не помещается и «Реклама · erid: …» — erid на второй строке (виден целиком)
            wrap = bounded && erid.text.isNotEmpty() && label.measuredWidth + erid.measuredWidth > total - paddingLeft - paddingRight
            val h = (if (wrap) label.measuredHeight + erid.measuredHeight
                else maxOf(label.measuredHeight, erid.measuredHeight, if (adv.visibility == VISIBLE) adv.measuredHeight else 0)) + paddingTop + paddingBottom
            val w = if (bounded) total else paddingLeft + label.measuredWidth + advW + erid.measuredWidth + paddingRight
            setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var x = paddingLeft
            val y = paddingTop
            for (v in listOf(label, adv, erid)) {
                if (v.visibility != VISIBLE) continue
                if (wrap && v === erid) { erid.layout(paddingLeft, y + label.measuredHeight, paddingLeft + erid.measuredWidth, y + label.measuredHeight + erid.measuredHeight); continue }
                v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
                x += v.measuredWidth
            }
        }
    }

    private fun chip(t: String) = TextView(context).apply {
        text = t
        maxLines = 1
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(ctaText())
        setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, ctaColors()).apply { cornerRadius = 999 * dp }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        adaptHtml()
        post {
            // ширина стала известна или сменилась — картинка в wide могла сменить раскладку
            val c = cur
            if (c != null && plan?.format != "side" && plan?.format != "strip" && wideModeOf(c) != shownMode) show(c)
            adaptHtml()
            adaptCaption()
        }
    }

    /** HTML-баннер под высоту места: ничего не обрезается посередине (те же пороги, что в вебе и Compose). */
    private fun adaptHtml() {
        val p = htmlParts ?: return
        if (box.height == 0) return
        val fs = resources.configuration.fontScale
        // строка маркировки под текстом: после раскладки — реальная высота места над ней, до — оценка ~17 dp (10 sp + отступы)
        val contentH = (p.col.parent as? View)?.height ?: 0
        val h = if (p.legal && contentH in 1 until box.height) contentH / dp else box.height / dp - (if (p.legal) CvAds.LEGAL * fs else 0f)
        // низкое (50…58) — то же, что среднее, но поля меньше: заголовок с кнопкой и одна строка текста
        val mode = if (h >= 96 * fs) 'l' else if (h >= 50 * fs) 'm' else 's'
        val tight = mode == 'm' && h < 58 * fs
        // метка «Реклама» в строке заголовка занимает ~60 dp — кнопке в строке нужно на столько же больше ширины
        val inline = mode != 'l' && p.ctaIn != null && box.width / dp - (if (p.label) 60 else 0) >= 200
        val headH = (if (inline) 24 else 20) * fs
        val ctaH = if (mode == 'l' && p.ctaBl != null) 32 * fs else 0f
        val lines = maxOf(0, ((h - (if (tight) 8 else 14) - headH - ctaH - 3) / (17 * fs) + 0.01f).toInt())
        p.ctaBl?.visibility = if (mode == 'l') VISIBLE else GONE
        p.ctaIn?.visibility = if (inline) VISIBLE else GONE
        p.body.visibility = if (mode != 's' && lines > 0) VISIBLE else GONE
        p.body.maxLines = maxOf(1, lines)
        p.title.textSize = if (mode == 's') 13f else 15f
        val v = ((if (mode == 's') 3 else if (tight) 4 else 7) * dp).toInt()
        p.col.setPadding(((if (mode == 's') 10 else 12) * dp).toInt(), v, ((if (mode == 's') 10 else 12) * dp).toInt(), v)
    }

    private fun click() {
        val f = feed ?: return
        val a = ads ?: return
        val ad = cur ?: return
        io.execute {
            val u = a.clickUrl(f, ad, slot) ?: return@execute
            main.post {
                val open = onOpen
                if (open != null) open(u) else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    companion object {
        private val io = Executors.newFixedThreadPool(2)
    }
}
