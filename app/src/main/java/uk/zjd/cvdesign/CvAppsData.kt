// Данные плашки «Vice apps» / «Наши приложения» без привязки к Android (Android, Compose Desktop, обычные View).
// Те же правила, что web/cv-apps.js и бот: downloads.json (основной адрес → запасной → кэш на сутки),
// линия vice — Vice-версия, если выложена, иначе Cloud (пары lines.vice; '' — только Vice); линия cloud — Cloud.
// Платформа: ANDROID — APK, WINDOWS — exe/zip/msi, ANY — всё. Текущее приложение не показывается.
//
//   val cat = CvApps.load(cacheFile)                       // в фоне (сеть); null — нет ни сети, ни кэша
//   val items = CvApps.list(cat, CvApps.Line.VICE, CvApps.Platform.ANDROID, current = setOf("vice-vpn", "app"))
//   items[0].files[0].links()                               // [GitHub, РУ Зеркало, наш сервер] → url
//   CvApps.viaGetPage(url, name)                            // APK из WebView — через get.html во внешнем браузере
package uk.zjd.cvdesign

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CvApps {
    enum class Line { VICE, CLOUD }
    enum class Platform { ANDROID, WINDOWS, ANY }

    val SOURCES = listOf("https://cabinet.zjd.uk/app/downloads.json", "https://cvpn-dl.firsts3.ru/downloads.json")
    const val BASE = "https://cabinet.zjd.uk/app/"
    const val GET = "https://cabinet.zjd.uk/app/get.html"
    const val DAY_MS = 24L * 3600 * 1000

    val ORDER = listOf("app", "windows", "zapret", "social", "social-win", "messenger", "messenger-win", "mail", "mail-win",
        "wallet", "love", "addon", "cloudbrowser", "browser-android", "chihua", "torrent", "pass", "pass-win", "talk", "talk-win", "ai", "ai-win")
    val VICE_PAIRS = linkedMapOf("vice-vpn" to "app", "vice-vpn-win" to "windows", "vice-zapret" to "zapret", "vice-club" to "social",
        "vice-club-win" to "social-win", "vice-messenger" to "messenger", "vice-messenger-win" to "messenger-win", "vice-mail" to "mail",
        "vice-mail-win" to "mail-win", "vice-wallet" to "wallet", "vice-addon" to "addon", "vice-browser" to "cloudbrowser",
        "vice-browser-android" to "browser-android", "miami" to "chihua", "vice-wave" to "", "vice-wave-win" to "", "vice-love" to "love",
        "vice-torrent" to "torrent", "vice-pass" to "pass", "vice-pass-win" to "pass-win",
        "vice-talk" to "talk", "vice-talk-win" to "talk-win", "vice-ai" to "ai", "vice-ai-win" to "ai-win")
    private val ICON_CLOUD = mapOf("app" to "cloudvpn-icon.png", "windows" to "cloudvpn-icon.png", "zapret" to "zapret/icon.png",
        "social" to "social/icon.png", "social-win" to "social/icon.png", "messenger" to "messenger/icon.png",
        "messenger-win" to "messenger/icon.png", "mail" to "mail/icon.png", "mail-win" to "mail/icon.png", "wallet" to "wallet/icon.png",
        "addon" to "extension-icon.png", "cloudbrowser" to "cloudbrowser/icon.png", "browser-android" to "cloudbrowser/icon.png",
        "chihua" to "chihua/icon.png", "love" to "love/icon.png", "torrent" to "torrent/icon.png",
        "pass" to "pass/icon.png", "pass-win" to "pass/icon.png",
        "talk" to "talk/icon.png", "talk-win" to "talk/icon.png", "ai" to "ai/icon.png", "ai-win" to "ai/icon.png")
    private val ICON_VICE = mapOf("vice-vpn-win" to "vice-vpn", "vice-addon" to "vice-vpn", "vice-club-win" to "vice-club",
        "vice-messenger-win" to "vice-messenger", "vice-mail-win" to "vice-mail", "vice-browser-android" to "vice-browser",
        "vice-wave-win" to "vice-wave", "vice-pass-win" to "vice-pass", "vice-talk-win" to "vice-talk",
        "vice-ai-win" to "vice-ai")

    class AppFile(val label: String, val name: String, val size: Long, val github: String?, val bucket: String?, val server: String?) {
        /** Ссылки в порядке бота: GitHub, РУ Зеркало, наш сервер (ru-подпись, en-подпись, url). */
        fun links(): List<Triple<String, String, String>> = listOfNotNull(
            github?.let { Triple("GitHub", "GitHub", it) },
            bucket?.let { Triple("РУ Зеркало", "RU mirror", it) },
            server?.let { Triple("Наш сервер", "Our server", it) },
        )
    }

    class Item(val key: String, val title: String, val emoji: String, val version: String, val files: List<AppFile>, val vice: Boolean, val icon: String)

    class Catalog(val products: Map<String, Map<String, Any?>>, val pairs: Map<String, String>)

    // ── загрузка ──

    /**
     * Каталог: кэш моложе суток → сеть (основной, запасной адрес) → старый кэш. Вызывать не в главном потоке.
     * [fetch] — свой HTTP-клиент (например, через прокси приложения): url → тело ответа; null — HttpURLConnection.
     * [sources] — свои адреса downloads.json.
     */
    fun load(
        cache: File?,
        force: Boolean = false,
        now: Long = System.currentTimeMillis(),
        fetch: ((String) -> ByteArray)? = null,
        sources: List<String> = SOURCES,
    ): Catalog? {
        val cached = cache?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        if (!force && cache != null && cached != null && now - cache.lastModified() < DAY_MS) parse(cached)?.let { return it }
        for (src in sources) {
            val body = runCatching { if (fetch != null) String(fetch(src), Charsets.UTF_8) else http(src) }.getOrNull() ?: continue
            val cat = parse(body) ?: continue
            runCatching { cache?.parentFile?.mkdirs(); cache?.writeText(body) }
            return cat
        }
        return cached?.let { parse(it) }
    }

    private fun http(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 9000
        c.setRequestProperty("Cache-Control", "no-cache")
        return try {
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(json: String): Catalog? = runCatching {
        val root = Json(json).value() as Map<String, Any?>
        val prods = (root["products"] as? Map<String, Any?>)?.mapValues { it.value as? Map<String, Any?> ?: emptyMap() } ?: return null
        val pairs = ((root["lines"] as? Map<String, Any?>)?.get("vice") as? Map<String, Any?>)?.mapValues { (it.value as? String).orEmpty() }
            ?: VICE_PAIRS
        Catalog(prods, pairs)
    }.getOrNull()

    // ── список ──

    @Suppress("UNCHECKED_CAST")
    private fun files(p: Map<String, Any?>?): List<Map<String, Any?>> = (p?.get("files") as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()

    private fun fits(name: String, platform: Platform): Boolean {
        val n = name.lowercase()
        return when (platform) {
            Platform.ANDROID -> n.endsWith(".apk")
            Platform.WINDOWS -> n.endsWith(".exe") || n.endsWith(".zip") || n.endsWith(".msi")
            Platform.ANY -> true
        }
    }

    fun list(cat: Catalog, line: Line, platform: Platform, current: Set<String> = emptySet()): List<Item> {
        val prods = cat.products
        val pairs = cat.pairs
        val published = { k: String -> files(prods[k]).isNotEmpty() }
        val viceOf = HashMap<String, String>()
        for ((v, c) in pairs) if (c.isNotEmpty() && published(v)) viceOf[c] = v
        val cloudKeys = ORDER.toMutableList()
        for (k in prods.keys) if (k !in pairs && k !in cloudKeys) cloudKeys += k
        val keys = ArrayList<String>()
        for (k in cloudKeys) {
            val v = viceOf[k]
            if (line == Line.VICE && v != null) keys += v else if (published(k)) keys += k
        }
        if (line == Line.VICE) for ((v, c) in pairs) if (c.isEmpty() && published(v)) keys += v
        val out = ArrayList<Item>()
        for (key in keys) {
            if (key in current || pairs[key] in current || viceOf[key] in current) continue
            val p = prods[key] ?: continue
            val fs = files(p).mapNotNull { f ->
                val name = f["name"] as? String ?: return@mapNotNull null
                if (!fits(name, platform)) return@mapNotNull null
                AppFile((f["label"] as? String) ?: name, name, (f["size"] as? Number)?.toLong() ?: 0L,
                    f["github"] as? String, f["bucket"] as? String, f["server"] as? String)
            }
            if (fs.isEmpty()) continue
            val title = (p["title"] as? String) ?: key
            val emoji = title.substringBefore(' ', "")
            val clean = title.dropWhile { !it.isLetterOrDigit() }.trim()
            val vice = key in pairs
            val icon = if (vice) "${BASE}vice/${ICON_VICE[key] ?: key}-256.png" else BASE + (ICON_CLOUD[key] ?: "cloudvpn-icon.png")
            out += Item(key, clean, emoji, (p["version"] as? String).orEmpty(), fs, vice, icon)
        }
        return out
    }

    /** APK из WebView скачивается во внешнем браузере — через страницу get.html, как в боте. */
    fun viaGetPage(url: String, name: String): String =
        "$GET?u=${URLEncoder.encode(url, "UTF-8")}&n=${URLEncoder.encode(name, "UTF-8")}"

    // ── маленький разбор JSON (без внешних библиотек; downloads.json — простой) ──
    private class Json(private val s: String) {
        private var i = 0
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("json @$i")
            }
        }
        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                if (s[i++] == ']') return l
            }
        }
        private fun str(): String {
            val b = StringBuilder()
            i++
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> b.append(e)
                        }
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
