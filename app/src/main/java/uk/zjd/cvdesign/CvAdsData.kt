// Рекламная плашка — данные и правила без привязки к Android (Android, Compose Desktop, обычные View).
// Формат: описание формата ads.json. Те же правила, что web/cv-ads.js.
//
//   val ads = CvAds(dir = File(context.cacheDir, "cv-ads"))          // один на приложение
//   val feed = ads.load()                                            // в фоне: сервер → бакет → кэш (ttl из ads.json)
//   val plan = CvAds.plan(feed, "vpn.home", line = "vice", platform = "android")   // null — плашку не рисовать
//   var ad = CvAds.pick(plan, null, "vpn.home")                      // потом каждые plan.interval с: pick(plan, ad, slot)
//   val png = ads.image(feed, ad.image)                              // байты картинки (кэш по имени файла)
//   ads.impression(feed, ad, "vpn.home")                             // баннер виден ≥ 50 % ≥ 1 с
//   val url = ads.clickUrl(feed, ad, "vpn.home")                     // открыть во внешнем браузере
//   ads.flush(force = false)                                         // не чаще раза в 10 мин; force — при уходе в фон
// fetch — свой HTTP-клиент для ads.json и картинок (url → байты; сеть через прокси приложения), post — для показов.
package uk.zjd.cvdesign

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

class CvAds(
    private val dir: File,
    /** свой HTTP-клиент для ads.json и картинок (url → байты), например через прокси приложения */
    private val fetch: ((String) -> ByteArray)? = null,
    /** своя отправка показов: (адрес hit, тело) → удалось ли */
    private val post: ((url: String, body: String) -> Boolean)? = null,
    private val sources: List<String> = SOURCES,
    /** от чего считать пути картинок; null — от адреса, откуда пришёл ads.json (сервер или бакет) */
    private val base: String? = null,
    /** свой путь для картинок (url → url), например через локальный прокси приложения */
    private val asset: ((String) -> String)? = null,
    /** своя отправка показов, только тело (имеет приоритет над post) */
    private val send: ((body: String) -> Boolean)? = null,
) {
    class Mark(val label: String, val advertiser: String, val erid: String) {
        /** Строка «Реклама · рекламодатель · erid: …» или null, если её показывать не нужно. */
        fun legal(): String? = if (advertiser.isEmpty() && erid.isEmpty()) null
        else listOfNotNull(label.ifEmpty { "Реклама" }, advertiser.ifEmpty { null }, erid.ifEmpty { null }?.let { "erid: $it" }).joinToString(" · ")
    }

    class Ad(
        val id: String, val kind: String, val title: String, val image: String, val imageSide: String, val imageStrip: String,
        val html: String, val text: String, val cta: String, val bg: String, val fg: String,
        val url: String, val click: String, val weight: Int, val from: Long?, val to: Long?,
        val slots: List<String>, val lines: List<String>, val platforms: List<String>, val mark: Mark,
    )

    class Slot(val format: String, val interval: Int, val order: String, val max: Int)

    /** ads.json: base — адрес папки (для картинок), server — пришёл с сервера сейчас (клики через счётчик). */
    class Feed(val v: Int, val rev: Long, val ttl: Long, val hit: String, val slots: Map<String, Slot>, val ads: List<Ad>,
               val base: String, val server: Boolean, val defaults: Slot)

    class Plan(val slot: String, val format: String, val intervalSec: Int, val order: String, val ads: List<Ad>) {
        /** Соотношение сторон места: wide 4:1, side 2:1, strip 6:1. */
        val ratio: Float get() = when (format) { "side" -> 2f; "strip" -> 6f; else -> 4f }
        /** Есть ли среди баннеров места строка маркировки: под неё место оставлено всегда, высота не прыгает. */
        val anyLegal: Boolean = ads.any { it.mark.legal() != null }

        /**
         * Высота плашки, dp. wide — низкая: картинка или текст не выше WIDE_MAX (раньше 4:1 — 84–112 dp) плюс строка
         * маркировки, если она есть; side и strip — по пропорции, как раньше.
         */
        fun height(width: Float, fontScale: Float): Float =
            if (format == "wide") contentHeight(width, fontScale) + (if (anyLegal) LEGAL * fontScale else 0f) else width / ratio

        /** Высота места над строкой маркировки, dp (только wide). */
        fun contentHeight(width: Float, fontScale: Float): Float = minOf(width / 4f, WIDE_MAX * fontScale.coerceIn(1f, 1.4f))

        /**
         * Как рисовать картинку в wide: 'f' — картинка 4:1 целиком, 's' — полоса image_strip 6:1 целиком, 'r' — картинка
         * слева и подпись справа. При низкой плашке 4:1 во всю ширину обрезала бы треть рисунка.
         */
        fun wideImage(width: Float, fontScale: Float, hasStrip: Boolean): Char {
            val r = width / contentHeight(width, fontScale)
            return if (r <= 4.6f) 'f' else if (hasStrip && r in 5.2f..6.8f) 's' else 'r'
        }
    }

    // ── загрузка ──
    private val jsonFile get() = File(dir, "ads.json")
    private val metaFile get() = File(dir, "ads.meta")
    private val hitsFile get() = File(dir, "ads-hits.json")
    @Volatile private var fresh: Feed? = null

    /** Кэш моложе ttl → сеть (по очереди sources) → старый кэш (сколько бы ему ни было). Не в главном потоке. */
    fun load(force: Boolean = false, now: Long = System.currentTimeMillis()): Feed? {
        fresh?.takeIf { !force }?.let { return it }
        val cached = runCatching { jsonFile.readText() }.getOrNull()
        val meta = runCatching { metaFile.readText().split('\n') }.getOrNull()
        val cachedFeed = cached?.let { parse(it, meta?.getOrNull(0).orEmpty(), meta?.getOrNull(1) == "server") }
        if (!force && cachedFeed != null && now - jsonFile.lastModified() < cachedFeed.ttl * 1000) return cachedFeed
        for (src in sources) {
            val body = runCatching { String(get(src), Charsets.UTF_8) }.getOrNull() ?: continue
            val base = src.substringBeforeLast('/') + "/"
            val f = parse(body, base, true) ?: continue
            runCatching { dir.mkdirs(); jsonFile.writeText(body); metaFile.writeText("$base\nserver") }
            fresh = f
            return f
        }
        return cachedFeed?.let { Feed(it.v, it.rev, it.ttl, it.hit, it.slots, it.ads, it.base, false, it.defaults) }
    }

    /** Картинка баннера: из кэша по имени файла (имя содержит хэш — файл не меняется) или из сети. */
    fun image(feed: Feed, rel: String): ByteArray? {
        if (rel.isEmpty()) return null
        val name = rel.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val f = File(File(dir, "img"), name)
        if (f.exists() && f.length() > 0) return runCatching { f.readBytes() }.getOrNull()
        val raw = if (rel.startsWith("http")) rel else (base ?: feed.base) + rel
        val url = asset?.invoke(raw) ?: raw
        val bytes = runCatching { get(url) }.getOrNull() ?: return null
        runCatching { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
        return bytes
    }

    private fun get(url: String): ByteArray {
        fetch?.let { return it(url) }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.useCaches = false
        c.setRequestProperty("Cookie", "")   // без куки и Referer: показ нельзя связать с аккаунтом
        try {
            if (c.responseCode !in 200..299) error("http ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    // ── показы и клики ──
    private val lock = Any()
    private var lastSend = 0L

    fun impression(feed: Feed, ad: Ad, slot: String) = count(feed, ad.id, slot, 1, 0)

    /** Адрес для нажатия: через счётчик (click?s=слот), если ads.json с сервера; иначе url и клик в пакете показов. */
    fun clickUrl(feed: Feed, ad: Ad, slot: String): String? {
        if (feed.server && ad.click.isNotEmpty()) return ad.click + (if ('?' in ad.click) "&" else "?") + "s=" + slot
        count(feed, ad.id, slot, 0, 1)
        return ad.url.ifEmpty { null }
    }

    private fun count(feed: Feed, ad: String, slot: String, imp: Int, clk: Int) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val h = readHits()
            if (h.rev != feed.rev && h.rows.isNotEmpty()) flushLocked(h, feed.hit) // прошлая выкладка — отправить отдельно
            val cur = if (h.rev == feed.rev) h else Hits(feed.rev, feed.hit, LinkedHashMap())
            val k = "$ad|$slot"
            val r = cur.rows[k] ?: Row(ad, slot, 0, 0, now)
            cur.rows[k] = Row(ad, slot, minOf(1000, r.imp + imp), minOf(50, r.clk + clk), r.t)
            writeHits(Hits(feed.rev, feed.hit, cur.rows))
        }
        if (now - lastSend > 600_000) flush(false)
    }

    /** Отправить накопленное: не чаще раза в 10 минут (force — при уходе приложения в фон). Не в главном потоке. */
    fun flush(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSend < 600_000) return
        synchronized(lock) { flushLocked(readHits(), null) }
    }

    private fun flushLocked(h: Hits, hitUrl: String?) {
        val week = System.currentTimeMillis() - 7L * 86_400_000
        val rows = h.rows.values.filter { it.t >= week && (it.imp > 0 || it.clk > 0) }
        if (rows.isEmpty()) { writeHits(Hits(h.rev, h.hit, LinkedHashMap())); return }
        lastSend = System.currentTimeMillis()
        val batch = rows.take(50)
        val body = buildString {
            append("{\"rev\":").append(h.rev).append(",\"items\":[")
            batch.forEachIndexed { i, r ->
                if (i > 0) append(',')
                append("{\"ad\":\"").append(jsonEsc(r.ad)).append("\",\"slot\":\"").append(jsonEsc(r.slot))
                    .append("\",\"imp\":").append(r.imp).append(",\"clk\":").append(r.clk).append('}')
            }
            append("]}")
        }
        // своя сеть (fetch) без своей отправки — показы копятся, напрямую в обход приложения ничего не уходит
        if (this.send == null && post == null && fetch != null) return
        val ok = runCatching { this.send?.invoke(body) ?: (post ?: ::httpPost)(hitUrl ?: h.hit.ifEmpty { HIT }, body) }.getOrDefault(false)
        if (ok) {
            val rest = LinkedHashMap<String, Row>()
            for (r in rows.drop(50)) rest["${r.ad}|${r.slot}"] = r
            writeHits(Hits(h.rev, h.hit, rest))
        }
    }

    private fun httpPost(url: String, body: String): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain")
        return try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }

    private class Row(val ad: String, val slot: String, val imp: Int, val clk: Int, val t: Long)
    private class Hits(val rev: Long, val hit: String, val rows: LinkedHashMap<String, Row>)

    private fun readHits(): Hits {
        val lines = runCatching { hitsFile.readLines() }.getOrNull().orEmpty()
        val head = lines.firstOrNull()?.split('\t')
        val rows = LinkedHashMap<String, Row>()
        for (l in lines.drop(1)) {
            val p = l.split('\t')
            if (p.size == 5) rows["${p[0]}|${p[1]}"] = Row(p[0], p[1], p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toLongOrNull() ?: 0)
        }
        return Hits(head?.getOrNull(0)?.toLongOrNull() ?: -1, head?.getOrNull(1).orEmpty(), rows)
    }

    private fun writeHits(h: Hits) {
        runCatching {
            dir.mkdirs()
            hitsFile.writeText(buildString {
                append(h.rev).append('\t').append(h.hit).append('\n')
                for (r in h.rows.values) append(r.ad).append('\t').append(r.slot).append('\t').append(r.imp).append('\t').append(r.clk).append('\t').append(r.t).append('\n')
            })
        }
    }

    companion object {
        /** Самая большая высота содержимого wide, dp; строка маркировки (10 sp и поля) — ещё LEGAL. */
        const val WIDE_MAX = 52f
        const val LEGAL = 17f
        val SOURCES = listOf("https://cabinet.zjd.uk/app/ads/ads.json", "https://cvpn-dl.firsts3.ru/ads/ads.json")
        const val HIT = "https://cabinet.zjd.uk/api/ads/hit"
        private val rnd = Random(System.nanoTime())
        private val roundStart = HashMap<String, Int>()

        private fun jsonEsc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        /** План места или null (места нет в slots, нет подходящих баннеров, формат новее нашего). */
        fun plan(feed: Feed?, slot: String, line: String, platform: String, now: Long = System.currentTimeMillis()): Plan? {
            if (feed == null || feed.v > 1) return null
            val sc = feed.slots[slot] ?: return null
            var ads = feed.ads.filter { a ->
                (a.slots.isEmpty() || slot in a.slots) && (a.lines.isEmpty() || line in a.lines) &&
                    (a.platforms.isEmpty() || platform in a.platforms) && (a.from == null || now >= a.from) &&
                    (a.to == null || now < a.to) && (a.kind != "image" || a.image.isNotEmpty())
            }
            if (ads.isEmpty()) return null
            if (sc.max > 0 && ads.size > sc.max) {
                ads = if (sc.order == "weight") {
                    val keep = ads.sortedByDescending { it.weight }.take(sc.max).map { it.id }.toSet()
                    ads.filter { it.id in keep }
                } else ads.take(sc.max)
            }
            return Plan(slot, if (sc.format == "side" || sc.format == "strip") sc.format else "wide", maxOf(10, sc.interval), sc.order, ads)
        }

        /** Следующий баннер: weight — случайно ∝ весу, не текущий; round — по кругу со случайного старта. */
        fun pick(plan: Plan, current: Ad?, slot: String = plan.slot): Ad {
            val ads = plan.ads
            if (ads.size == 1) return ads[0]
            if (plan.order == "round") {
                val i = current?.let { c -> ads.indexOfFirst { it.id == c.id } } ?: -1
                if (i < 0) return ads[roundStart.getOrPut(slot) { rnd.nextInt(ads.size) } % ads.size]
                return ads[(i + 1) % ads.size]
            }
            val pool = ads.filter { it.id != current?.id }
            var r = rnd.nextInt(pool.sumOf { maxOf(1, it.weight) })
            for (a in pool) { r -= maxOf(1, a.weight); if (r < 0) return a }
            return pool.last()
        }

        /** ISO-время UTC («2026-11-20T00:00:00Z», доли секунды допустимы); без java.time — работает на любом Android. */
        private fun iso(s: Any?): Long? = (s as? String)?.takeIf { it.isNotEmpty() }?.let { v ->
            runCatching {
                val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                f.timeZone = java.util.TimeZone.getTimeZone("UTC")
                f.parse(v.substring(0, 19))!!.time
            }.getOrNull()
        }

        @Suppress("UNCHECKED_CAST")
        fun parse(json: String, base: String, server: Boolean): Feed? = runCatching {
            val root = Json(json).value() as Map<String, Any?>
            fun str(m: Map<String, Any?>?, k: String) = (m?.get(k) as? String).orEmpty()
            fun int(m: Map<String, Any?>?, k: String, d: Int) = (m?.get(k) as? Number)?.toInt() ?: d
            fun list(m: Map<String, Any?>?, k: String) = (m?.get(k) as? List<Any?>)?.mapNotNull { it as? String }.orEmpty()
            val rot = root["rotation"] as? Map<String, Any?>
            val def = Slot("wide", int(rot, "interval", 30), str(rot, "order").ifEmpty { "weight" }, int(rot, "max", 0))
            val slots = (root["slots"] as? Map<String, Any?>).orEmpty().mapValues { (_, v) ->
                val m = v as? Map<String, Any?>
                Slot(str(m, "format").ifEmpty { "wide" }, int(m, "interval", def.interval), str(m, "order").ifEmpty { def.order }, int(m, "max", def.max))
            }
            val ads = (root["ads"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }.map { a ->
                val mk = a["mark"] as? Map<String, Any?>
                Ad(str(a, "id"), str(a, "kind").ifEmpty { "image" }, str(a, "title"), str(a, "image"), str(a, "image_side"), str(a, "image_strip"),
                    str(a, "html"), str(a, "text"), str(a, "cta"), str(a, "bg"), str(a, "fg"), str(a, "url"), str(a, "click"),
                    int(a, "weight", 1).coerceIn(1, 100), iso(a["from"]), iso(a["to"]), list(a, "slots"), list(a, "lines"), list(a, "platforms"),
                    Mark(str(mk, "label"), str(mk, "advertiser"), str(mk, "erid")))
            }
            Feed(int(root, "v", 1), (root["rev"] as? Number)?.toLong() ?: 0L, (root["ttl"] as? Number)?.toLong() ?: 3600L,
                str(root, "hit").ifEmpty { HIT }, slots, ads, base, server, def)
        }.getOrNull()

        /** Разрешённая разметка html-баннера: только <b strong i em u s br small> без атрибутов, остальное — текстом. */
        fun safeHtml(h: String): String {
            val esc = h.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            return Regex("&lt;(/?)([a-zA-Z]+)\\s*/?&gt;").replace(esc) { m ->
                val tag = m.groupValues[2].lowercase()
                if (tag in setOf("b", "strong", "i", "em", "u", "s", "br", "small")) "<${m.groupValues[1]}$tag>" else m.value
            }
        }
    }

    // ── маленький разбор JSON (без внешних библиотек) ──
    private class Json(private val s: String) {
        private var i = 0
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("json @$i")
            }
        }
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws(); if (s[i++] == '}') return m }
        }
        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) { l += value(); ws(); if (s[i++] == ']') return l }
        }
        private fun str(): String {
            val b = StringBuilder(); i++
            while (true) {
                when (val c = s[i++]) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
        private fun num(): Number {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
        }
    }
}
