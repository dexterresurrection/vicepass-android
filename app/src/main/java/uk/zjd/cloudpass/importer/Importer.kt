package uk.zjd.cloudpass.importer

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Импорт паролей из чужих менеджеров (Cloud Pass / Vice Pass). Только разбор файла — без Android и без базы.
 *
 *  CSV:  Chrome / Edge / Opera / Яндекс (name,url,username,password,note), Firefox (url,username,password,…),
 *        Safari / iCloud (Title,URL,Username,Password,Notes,OTPAuth), Bitwarden CSV (folder,…,login_uri,login_totp),
 *        1Password CSV, KeePassXC CSV (Group,Title,Username,Password,URL,Notes,TOTP) и любой CSV с похожими заголовками;
 *  JSON: Bitwarden (незашифрованный экспорт);
 *  1PUX: 1Password (zip с export.data).
 * Файл KeePass (.kdbx) сюда не идёт: его сливает в базу штатное «Объединить» (нужен пароль того файла).
 */
data class ImportItem(
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    /** otpauth://… или голый секрет base32 */
    val otp: String = "",
    val folder: String = "",
    val extra: List<Pair<String, String>> = emptyList(),
)

class ImportException(message: String) : Exception(message)

enum class ImportFormat { CSV, BITWARDEN_JSON, ONEPASSWORD_1PUX, KEEPASS }

object Importer {

    fun detect(fileName: String, data: ByteArray): ImportFormat {
        val name = fileName.lowercase(Locale.ROOT)
        if (data.size >= 4 && data[0] == 0x03.toByte() && data[1] == 0xD9.toByte() && data[2] == 0xA2.toByte() && data[3] == 0x9A.toByte())
            return ImportFormat.KEEPASS
        if (data.size >= 2 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) return ImportFormat.ONEPASSWORD_1PUX
        val head = String(data, 0, minOf(data.size, 64), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (name.endsWith(".json") || head.startsWith("{")) return ImportFormat.BITWARDEN_JSON
        return ImportFormat.CSV
    }

    fun parse(fileName: String, data: ByteArray): List<ImportItem> = when (detect(fileName, data)) {
        ImportFormat.CSV -> parseCsv(String(data, Charsets.UTF_8))
        ImportFormat.BITWARDEN_JSON -> parseBitwardenJson(String(data, Charsets.UTF_8))
        ImportFormat.ONEPASSWORD_1PUX -> parse1pux(data)
        ImportFormat.KEEPASS -> throw ImportException("Это файл KeePass — откройте хранилище и выберите «Объединить» в меню")
    }

    // ── CSV ──

    /** RFC 4180: кавычки, "" внутри, переводы строк в поле; разделитель — запятая, точка с запятой или табуляция. */
    fun csvRows(text: String): List<List<String>> {
        val s = text.trimStart('﻿')
        val firstLine = s.substringBefore('\n')
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                delim -> { row.add(cell.toString()); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private val TITLE = listOf("title", "name", "account", "site", "item name")
    private val URL = listOf("url", "login_uri", "website", "web site", "uri", "urls", "login url", "hostname", "origin")
    private val USER = listOf("username", "login_username", "login", "user", "user name", "email", "e-mail", "login name")
    private val PASS = listOf("password", "login_password", "pass", "passwd")
    private val NOTES = listOf("notes", "note", "notesplain", "extra", "comments", "comment")
    private val OTP = listOf("totp", "otp", "otpauth", "login_totp", "one-time password", "2fa", "one time password")
    private val FOLDER = listOf("folder", "group", "grouping", "category", "vault")
    /** служебные колонки, которые не переносим в доп. поля */
    private val SKIP = setOf("favorite", "type", "reprompt", "fields", "httprealm", "formactionorigin", "guid", "timecreated",
        "timelastused", "timepasswordchanged", "icon", "last modified", "created", "archived", "tags", "fav")

    fun parseCsv(text: String): List<ImportItem> {
        val rows = csvRows(text)
        if (rows.size < 2) throw ImportException("В файле нет записей (нужна строка заголовков и хотя бы одна строка)")
        val head = rows[0].map { it.trim().lowercase(Locale.ROOT) }
        fun col(names: List<String>) = names.firstNotNullOfOrNull { n -> head.indexOf(n).takeIf { it >= 0 } }
        val cTitle = col(TITLE)
        val cUrl = col(URL)
        val cUser = col(USER)
        val cPass = col(PASS)
        val cNotes = col(NOTES)
        val cOtp = col(OTP)
        val cFolder = col(FOLDER)
        if (cPass == null && cUser == null) throw ImportException("Не нашёл колонок «password» и «username» — это не экспорт паролей?")
        val used = setOfNotNull(cTitle, cUrl, cUser, cPass, cNotes, cOtp, cFolder)
        val fieldsCol = head.indexOf("fields").takeIf { it >= 0 }
        return rows.drop(1).map { r ->
            fun v(c: Int?) = c?.let { r.getOrNull(it) }?.trim().orEmpty()
            val url = v(cUrl)
            val extra = head.indices.filter { it !in used && head[it] !in SKIP && v(it).isNotEmpty() }
                .map { rows[0][it].trim() to v(it) }.toMutableList()
            // Bitwarden CSV: доп. поля «имя: значение» построчно
            v(fieldsCol).lines().filter { ':' in it }.forEach { extra.add(it.substringBefore(':').trim() to it.substringAfter(':').trim()) }
            ImportItem(
                title = v(cTitle).ifEmpty { hostOf(url) }.ifEmpty { v(cUser) }.ifEmpty { "Без названия" },
                username = v(cUser), password = v(cPass), url = url, notes = v(cNotes), otp = v(cOtp), folder = v(cFolder),
                extra = extra,
            )
        }.filter { it.password.isNotEmpty() || it.username.isNotEmpty() || it.notes.isNotEmpty() || it.otp.isNotEmpty() }
    }

    fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore(':').removePrefix("www.")

    // ── Bitwarden JSON ──

    fun parseBitwardenJson(text: String): List<ImportItem> {
        val root = try { JSONObject(text.trimStart('﻿')) } catch (e: Exception) { throw ImportException("Файл JSON не читается: ${e.message}") }
        if (root.optBoolean("encrypted", false))
            throw ImportException("Это зашифрованный экспорт Bitwarden — выгрузите заново в формате «.json» без пароля")
        val items = root.optJSONArray("items") ?: throw ImportException("В JSON нет списка items — это не экспорт Bitwarden?")
        val folders = HashMap<String, String>()
        root.optJSONArray("folders")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { folders[it.optString("id")] = it.optString("name") } }
        val out = mutableListOf<ImportItem>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val extra = mutableListOf<Pair<String, String>>()
            it.optJSONArray("fields")?.let { f -> for (j in 0 until f.length()) f.optJSONObject(j)?.let { x ->
                if (x.optString("name").isNotEmpty()) extra.add(x.optString("name") to x.optString("value")) } }
            val login = it.optJSONObject("login")
            val uris = login?.optJSONArray("uris")
            val urls = (0 until (uris?.length() ?: 0)).mapNotNull { j -> uris?.optJSONObject(j)?.optString("uri")?.takeIf { u -> u.isNotEmpty() } }
            urls.drop(1).forEachIndexed { j, u -> extra.add("URL ${j + 2}" to u) }
            it.optJSONObject("card")?.let { c -> listOf("cardholderName" to "Владелец карты", "brand" to "Платёжная система",
                "number" to "Номер карты", "expMonth" to "Месяц", "expYear" to "Год", "code" to "CVV").forEach { (k, n) ->
                c.optString(k).takeIf { v -> v.isNotEmpty() && v != "null" }?.let { v -> extra.add(n to v) } } }
            it.optJSONObject("identity")?.let { c -> c.keys().forEach { k -> c.optString(k).takeIf { v -> v.isNotEmpty() && v != "null" }?.let { v -> extra.add(k to v) } } }
            out.add(ImportItem(
                title = it.optString("name").ifEmpty { "Без названия" },
                username = login?.optString("username").orNull(), password = login?.optString("password").orNull(),
                url = urls.firstOrNull().orEmpty(), notes = it.optString("notes").orNull(), otp = login?.optString("totp").orNull(),
                folder = folders[it.optString("folderId")].orEmpty(), extra = extra,
            ))
        }
        return out
    }

    private fun String?.orNull(): String = if (this == null || this == "null") "" else this

    // ── 1Password 1PUX ──

    fun parse1pux(data: ByteArray): List<ImportItem> {
        var json: String? = null
        ZipInputStream(ByteArrayInputStream(data)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "export.data") { json = z.readBytes().toString(Charsets.UTF_8); break }
            }
        }
        val root = JSONObject(json ?: throw ImportException("В архиве нет export.data — это не экспорт 1Password (.1pux)?"))
        val out = mutableListOf<ImportItem>()
        val accounts = root.optJSONArray("accounts") ?: JSONArray()
        for (a in 0 until accounts.length()) {
            val vaults = accounts.optJSONObject(a)?.optJSONArray("vaults") ?: continue
            for (v in 0 until vaults.length()) {
                val vault = vaults.optJSONObject(v) ?: continue
                val vname = vault.optJSONObject("attrs")?.optString("name").orEmpty()
                val items = vault.optJSONArray("items") ?: continue
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (item.optString("state") == "archived") continue
                    val ov = item.optJSONObject("overview") ?: JSONObject()
                    val det = item.optJSONObject("details") ?: JSONObject()
                    var user = ""; var pass = ""; var otp = ""
                    val extra = mutableListOf<Pair<String, String>>()
                    det.optJSONArray("loginFields")?.let { lf -> for (j in 0 until lf.length()) lf.optJSONObject(j)?.let { f ->
                        when (f.optString("designation")) {
                            "username" -> user = f.optString("value")
                            "password" -> pass = f.optString("value")
                            else -> if (f.optString("value").isNotEmpty() && f.optString("name").isNotEmpty()) extra.add(f.optString("name") to f.optString("value"))
                        } } }
                    if (pass.isEmpty()) pass = det.optString("password")
                    det.optJSONArray("sections")?.let { ss -> for (j in 0 until ss.length()) {
                        val fields = ss.optJSONObject(j)?.optJSONArray("fields") ?: continue
                        for (k in 0 until fields.length()) {
                            val f = fields.optJSONObject(k) ?: continue
                            val value = f.optJSONObject("value") ?: continue
                            val key = value.keys().asSequence().firstOrNull() ?: continue
                            val raw = value.opt(key)
                            val s = when (raw) { is JSONObject -> raw.toString(); null -> ""; else -> raw.toString() }
                            if (s.isEmpty() || s == "null") continue
                            if (key == "totp" && otp.isEmpty()) otp = s else extra.add(f.optString("title").ifEmpty { key } to s)
                        }
                    } }
                    val urls = ov.optJSONArray("urls")
                    val url = ov.optString("url").ifEmpty { urls?.optJSONObject(0)?.optString("url").orEmpty() }
                    out.add(ImportItem(title = ov.optString("title").ifEmpty { "Без названия" }, username = user, password = pass,
                        url = url, notes = det.optString("notesPlain"), otp = otp, folder = vname, extra = extra))
                }
            }
        }
        return out
    }

    /** otpauth-ссылка из того, что дал экспорт (ссылка как есть, голый секрет — в ссылку). */
    fun otpUri(otp: String, title: String, username: String): String? {
        val t = otp.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("otpauth://", ignoreCase = true)) return t
        if (t.startsWith("steam://", ignoreCase = true)) return null
        val secret = t.replace(" ", "").replace("-", "").uppercase(Locale.ROOT)
        if (!secret.matches(Regex("^[A-Z2-7]+=*$"))) return null
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val label = enc(title.ifEmpty { "Import" }) + (if (username.isNotEmpty()) ":" + enc(username) else "")
        return "otpauth://totp/$label?secret=$secret&issuer=${enc(title.ifEmpty { "Import" })}"
    }
}
