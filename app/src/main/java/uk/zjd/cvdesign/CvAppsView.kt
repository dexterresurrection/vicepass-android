// Плашка «Vice apps» / «Наши приложения» и окно со всеми нашими приложениями — обычные View (Android).
//
//   val plate = CvAppsPlateView(context).apply { line = CvApps.Line.VICE; current = setOf("vice-vpn", "app") }
//   container.addView(plate)                         // по нажатию — CvAppsDialog.show(activity, line, current)
//   CvAppsDialog.show(activity, CvApps.Line.VICE, setOf("vice-vpn", "app"))   // или открыть окно сразу
// look — оформление по теме приложения (line — только какие приложения): "vice" (темы Vice), "ff7" («Классика RPG»),
// "plain" (обычные тёмная/светлая — цвета из темы приложения: colorSurfaceContainer/colorSurface, colorOnSurface,
// colorPrimary/colorOnPrimary), "auto" — по линии, как раньше (VICE → Vice, CLOUD → ff7).
// Ссылки открываются во внешнем браузере (Intent.ACTION_VIEW); список — android/common/CvAppsData.kt.
package uk.zjd.cvdesign

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private fun isRu() = Locale.getDefault().language.let { it == "ru" || it == "uk" || it == "be" || it == "kk" }

/** Цвет из темы приложения по имени атрибута (Material — без зависимости от библиотеки); нет — null. */
private fun themeColor(ctx: Context, vararg names: String): Int? {
    for (n in names) {
        val id = ctx.resources.getIdentifier(n, "attr", ctx.packageName)
        if (id == 0) continue
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(id, tv, true)) continue
        if (tv.resourceId != 0) runCatching { return ctx.resources.getColor(tv.resourceId, ctx.theme) }
        else if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) return tv.data
    }
    return null
}

private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

/** Цвета оформления плашки и окна. */
private class CvAppsPal(
    val bg: IntArray, val edge: Int, val edgeDp: Float, val fg: Int, val sub: Int, val cta: IntArray, val ctaFg: Int,
    val link: Int, val chipBg: Int, val chipEdge: Int, val iconBg: Int, val glow: Int,
) {
    companion object {
        fun of(ctx: Context, look: String, line: CvApps.Line, sheet: Boolean): CvAppsPal {
            val k = if (look == "vice" || look == "ff7" || look == "plain") look else if (line == CvApps.Line.VICE) "vice" else "ff7"
            return when (k) {
                "vice" -> CvAppsPal(
                    if (sheet) intArrayOf(0xFF22105C.toInt(), 0xFF0C0628.toInt()) else intArrayOf(0xFF2A0B6A.toInt(), 0xFF4A0F7A.toInt(), 0xFF2A0B6A.toInt()),
                    0xFFFF2BD6.toInt(), 2f, 0xFFFFF6FD.toInt(), 0xCCFFFFFF.toInt(), intArrayOf(0xFFFF2BD6.toInt(), 0xFFFF7A1A.toInt()), 0xFF12062E.toInt(),
                    0xFF6FF2FF.toInt(), 0x1FFFFFFF, 0x9900E5FF.toInt(), 0x22FFFFFF, 0xFFFF2BD6.toInt())
                "plain" -> {
                    val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val bg = (if (sheet) themeColor(ctx, "colorSurfaceContainerHigh", "colorSurface") else themeColor(ctx, "colorSurfaceContainer", "colorSurface"))
                        ?: if (night) (if (sheet) 0xFF141924.toInt() else 0xFF1A1F2B.toInt()) else Color.WHITE
                    val fg = themeColor(ctx, "colorOnSurface") ?: if (night) 0xFFEEF1F8.toInt() else 0xFF14213D.toInt()
                    val acc = themeColor(ctx, "colorPrimary") ?: 0xFF2F8CFF.toInt()
                    CvAppsPal(intArrayOf(bg, bg), alpha(fg, 0x24), 1f, fg, alpha(fg, 0xB3), intArrayOf(acc, acc), themeColor(ctx, "colorOnPrimary") ?: Color.WHITE,
                        acc, alpha(fg, 0x0F), alpha(fg, 0x26), alpha(fg, 0x12), 0)
                }
                else -> CvAppsPal(
                    if (sheet) intArrayOf(0xFF1B2F86.toInt(), 0xFF0B1550.toInt()) else intArrayOf(0xFF24409E.toInt(), 0xFF14247A.toInt()),
                    0xFFBCC2D6.toInt(), 2f, 0xFFFFF6FD.toInt(), 0xCCFFFFFF.toInt(), intArrayOf(0xFF2F8CFF.toInt(), 0xFF2F8CFF.toInt()), Color.WHITE,
                    0xFF9FC2FF.toInt(), 0x1FFFFFFF, 0x55FFFFFF, 0x22FFFFFF, 0)
            }
        }
    }
}

/** Плашка-кнопка для разметки или кода. line — линия (VICE: «Vice apps», CLOUD: «Наши приложения»). */
class CvAppsPlateView(context: Context) : LinearLayout(context) {
    var line: CvApps.Line = CvApps.Line.CLOUD
        set(v) { field = v; paint() }
    /** Оформление: "auto" (по линии), "vice", "ff7", "plain". */
    var look: String = "auto"
        set(v) { field = v; paint() }
    var current: Set<String> = emptySet()
    /** Свой HTTP-клиент для списка и иконок (url → байты); null — обычный HttpURLConnection. */
    var fetch: ((String) -> ByteArray)? = null
    private val dp = resources.displayMetrics.density
    private val title = TextView(context)
    private val sub = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = (14 * dp).toInt()
        setPadding(p, (12 * dp).toInt(), p, (12 * dp).toInt())
        val emoji = TextView(context).apply { textSize = 24f; tag = "emoji" }
        addView(emoji)
        val col = LinearLayout(context).apply { orientation = VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.setTextColor(Color.WHITE)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        sub.setTextColor(0xCCFFFFFF.toInt())
        sub.text = if (isRu()) "Все наши приложения — скачать" else "All our apps — download"
        col.addView(title)
        col.addView(sub)
        addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply { text = "›"; textSize = 24f; setTextColor(Color.WHITE); tag = "go" })
        isClickable = true
        isFocusable = true
        setOnClickListener { (context as? Activity)?.let { CvAppsDialog.show(it, line, current, fetch = fetch, look = look) } }
        paint()
    }

    private fun paint() {
        val vice = line == CvApps.Line.VICE
        (findViewWithTag<TextView>("emoji"))?.text = if (vice) "🐕" else "☁"
        title.text = if (vice) "Vice apps" else if (isRu()) "Наши приложения" else "Our apps"
        val pal = CvAppsPal.of(context, look, line, sheet = false)
        if (pal.glow != 0) title.setShadowLayer(10 * dp, 0f, 0f, pal.glow) else title.setShadowLayer(0f, 0f, 0f, 0)
        title.setTextColor(pal.fg)
        sub.setTextColor(pal.sub)
        (findViewWithTag<TextView>("go"))?.setTextColor(pal.fg)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, pal.bg).apply {
            cornerRadius = 16 * dp
            setStroke((pal.edgeDp * dp).toInt().coerceAtLeast(1), pal.edge)
        }
    }
}

/** Окно со списком: иконка, имя, версия, «Скачать» → GitHub / РУ Зеркало / наш сервер; внизу — другая линия. */
object CvAppsDialog {
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val iconCache = HashMap<String, Bitmap?>()

    fun show(
        activity: Activity,
        line: CvApps.Line,
        current: Set<String> = emptySet(),
        platform: CvApps.Platform = CvApps.Platform.ANDROID,
        fetch: ((String) -> ByteArray)? = null,
        look: String = "auto",
    ) {
        val dp = activity.resources.displayMetrics.density
        val ru = isRu()
        val vice = line == CvApps.Line.VICE
        val pal = CvAppsPal.of(activity, look, line, sheet = true)
        val white = pal.fg
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * dp).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, pal.bg).apply {
                cornerRadius = 20 * dp
                setStroke((pal.edgeDp * dp).toInt().coerceAtLeast(1), pal.edge)
            }
        }
        val head = TextView(activity).apply {
            text = if (vice) "Vice apps" else if (ru) "Наши приложения" else "Our apps"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(white)
            if (pal.glow != 0) setShadowLayer(12 * dp, 0f, 0f, pal.glow)
        }
        root.addView(head)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (8 * dp).toInt() })
        val note = TextView(activity).apply { text = if (ru) "Загрузка…" else "Loading…"; setTextColor(white); setPadding(0, (16 * dp).toInt(), 0, (16 * dp).toInt()) }
        list.addView(note)
        val foot = TextView(activity).apply {
            text = if (vice) (if (ru) "Оригинальные версии" else "Original versions") else (if (ru) "Vice-версии" else "Vice versions")
            setTextColor(pal.link)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
            setPadding(0, (10 * dp).toInt(), 0, 0)
            setOnClickListener { d.dismiss(); show(activity, if (vice) CvApps.Line.CLOUD else CvApps.Line.VICE, current, platform, fetch, look) }
        }
        root.addView(foot)
        d.setContentView(root)
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.94f).toInt().coerceAtMost((560 * dp).toInt()),
            (activity.resources.displayMetrics.heightPixels * 0.8f).toInt())
        d.show()

        val cache = File(activity.cacheDir, "cv-apps-downloads.json")
        io.execute {
            val cat = CvApps.load(cache, fetch = fetch)
            main.post {
                if (!d.isShowing) return@post
                list.removeAllViews()
                val items = cat?.let { CvApps.list(it, line, platform, current) }
                if (items == null || items.isEmpty()) {
                    note.text = if (cat == null) (if (ru) "Не удалось загрузить список. Проверьте связь." else "Could not load the list.")
                    else (if (ru) "Для этого устройства пока ничего нет." else "Nothing for this device yet.")
                    list.addView(note)
                    return@post
                }
                for (it in items) list.addView(row(activity, it, pal, ru, dp, fetch))
            }
        }
    }

    private fun row(a: Activity, it: CvApps.Item, pal: CvAppsPal, ru: Boolean, dp: Float, fetch: ((String) -> ByteArray)?): View {
        val white = pal.fg
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val iconBox = FrameLayout(a).apply {
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(pal.iconBg) }
            clipToOutline = true
        }
        val emoji = TextView(a).apply { text = it.emoji.ifEmpty { "•" }; textSize = 24f; gravity = Gravity.CENTER }
        val img = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        iconBox.addView(emoji, FrameLayout.LayoutParams(-1, -1))
        iconBox.addView(img, FrameLayout.LayoutParams(-1, -1))
        line.addView(iconBox, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()))
        loadIcon(it.icon, fetch) { bmp -> if (bmp != null) { img.setImageBitmap(bmp); emoji.visibility = View.GONE } }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0) }
        col.addView(TextView(a).apply { text = it.title; setTextColor(white); setTypeface(typeface, android.graphics.Typeface.BOLD); textSize = 15f })
        col.addView(TextView(a).apply { text = it.version; setTextColor(pal.sub); textSize = 12.5f })
        line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val files = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val btn = TextView(a).apply {
            text = if (ru) "Скачать" else "Download"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(pal.ctaFg)
            setPadding((16 * dp).toInt(), (9 * dp).toInt(), (16 * dp).toInt(), (9 * dp).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, pal.cta).apply { cornerRadius = 999 * dp }
            setOnClickListener { files.visibility = if (files.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        line.addView(btn)
        box.addView(line)
        for (f in it.files) {
            if (it.files.size > 1) files.addView(TextView(a).apply { text = f.label; setTextColor(pal.sub); textSize = 13f; setPadding(0, (8 * dp).toInt(), 0, 0) })
            val links = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (6 * dp).toInt(), 0, 0) }
            for ((ruName, enName, url) in f.links()) {
                links.addView(TextView(a).apply {
                    text = if (ru) ruName else enName
                    gravity = Gravity.CENTER
                    setTextColor(white)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    textSize = 13.5f
                    setPadding(0, (9 * dp).toInt(), 0, (9 * dp).toInt())
                    background = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(pal.chipBg); setStroke(dp.toInt(), pal.chipEdge) }
                    setOnClickListener { runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (6 * dp).toInt() })
            }
            files.addView(links)
        }
        box.addView(files)
        return box
    }

    private fun loadIcon(url: String, fetch: ((String) -> ByteArray)?, done: (Bitmap?) -> Unit) {
        synchronized(iconCache) { if (iconCache.containsKey(url)) { done(iconCache[url]); return } }
        io.execute {
            val bmp = runCatching {
                if (fetch != null) fetch(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                else URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            synchronized(iconCache) { iconCache[url] = bmp }
            main.post { done(bmp) }
        }
    }
}
