package uk.zjd.cloudpass

import org.json.JSONArray
import org.json.JSONObject

class PassMeta(val version: Long, val sha256: String, val size: Long, val updated: Long, val device: String) {
    companion object {
        fun of(o: JSONObject?) = o?.takeIf { it.has("version") }?.let {
            PassMeta(it.optLong("version"), it.optString("sha256"), it.optLong("size"), it.optLong("updated"), it.optString("device"))
        }
    }
}

sealed class PutResult {
    class Ok(val meta: PassMeta) : PutResult()
    /** 412: на сервере другая версия (current = null — хранилища там нет) */
    class Conflict(val current: PassMeta?) : PutResult()
}

/**
 * Сервер синхронизации (pass.zjd.uk, docs/PROTOCOL.md): хранит только зашифрованный файл KDBX.
 * Токен берётся в кабинете (/cabinet/app/pass-token) и живёт 7 дней; на 401 берём новый.
 */
object PassApi {
    @Volatile private var token: String? = null
    @Volatile private var tokenUntil = 0L
    @Volatile var server: String = "https://pass.zjd.uk"
        private set

    fun forget() {
        token = null
        tokenUntil = 0
    }

    /** Псевдоним аккаунта из токена: по нему приложение отличает свой файл от файла другого аккаунта. */
    fun account(): String = try {
        val body = token(false).substringBefore('.')
        JSONObject(String(android.util.Base64.decode(body, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))).getString("sub")
    } catch (e: org.json.JSONException) {
        throw CabinetError(500, "Кабинет выдал непонятный токен")
    }

    private fun token(force: Boolean): String {
        val t = token
        if (!force && t != null && System.currentTimeMillis() < tokenUntil) return t
        val o = JSONObject(Cabinet.authed("GET", "/cabinet/app/pass-token"))
        val nt = o.optString("token")
        if (nt.isEmpty()) throw CabinetError(500, "Кабинет не выдал токен хранилища")
        o.optString("server").takeIf { it.startsWith("https://") || it.startsWith("http://10.0.2.2:") }?.let { server = it.trimEnd('/') }
        token = nt
        tokenUntil = minOf(System.currentTimeMillis() + 6 * 86400_000L, o.optLong("exp") * 1000 - 3600_000L)
        return nt
    }

    private fun call(method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): HttpResult {
        fun go(force: Boolean) = Net.request(method, "$server$path", body,
            headers + ("Authorization" to "Bearer ${token(force)}"), timeoutMs = 25000)
        var r = go(false)
        if (r.code == 401) r = go(true)
        if (r.code == 401) throw CabinetError(401, "Сервер хранилища не принял вход — войдите снова")
        if (r.code == 429) throw CabinetError(429, "Слишком много запросов — подождите минуту")
        if (r.code >= 500) throw CabinetError(r.code, "Сервер хранилища не отвечает (${r.code})")
        return r
    }

    private fun message(r: HttpResult) = try { JSONObject(r.text).optString("message") } catch (_: Exception) { "" }
        .ifEmpty { "Сервер хранилища: ответ ${r.code}" }

    /** null — на сервере хранилища ещё нет */
    fun meta(): PassMeta? {
        val r = call("GET", "/v1/vault/meta")
        if (r.code == 404) return null
        if (r.code != 200) throw CabinetError(r.code, message(r))
        return PassMeta.of(JSONObject(r.text))
    }

    fun download(): Pair<PassMeta, ByteArray>? {
        val r = call("GET", "/v1/vault")
        if (r.code == 404) return null
        if (r.code != 200) throw CabinetError(r.code, message(r))
        val v = r.headers["x-vault-version"]?.toLongOrNull() ?: throw CabinetError(500, "Сервер не сказал версию файла")
        return PassMeta(v, r.headers["x-vault-sha256"].orEmpty(), r.body.size.toLong(), System.currentTimeMillis() / 1000, "") to r.body
    }

    fun upload(data: ByteArray, ifVersion: Long, device: String): PutResult {
        val r = call("PUT", "/v1/vault", data, mapOf("If-Match" to "\"v$ifVersion\"", "X-Device" to device,
            "Content-Type" to "application/octet-stream"))
        return when (r.code) {
            200 -> PutResult.Ok(PassMeta.of(JSONObject(r.text)) ?: throw CabinetError(500, "Непонятный ответ сервера"))
            412 -> PutResult.Conflict(PassMeta.of(JSONObject(r.text).optJSONObject("current")))
            else -> throw CabinetError(r.code, message(r))
        }
    }

    fun history(): List<PassMeta> {
        val r = call("GET", "/v1/history")
        if (r.code != 200) throw CabinetError(r.code, message(r))
        val a = JSONArray(r.text)
        return (0 until a.length()).mapNotNull { PassMeta.of(a.optJSONObject(it)) }
    }

    fun historyGet(version: Long): ByteArray {
        val r = call("GET", "/v1/history/$version")
        if (r.code != 200) throw CabinetError(r.code, message(r))
        return r.body
    }

    fun deleteAll() {
        val r = call("DELETE", "/v1/vault")
        if (r.code != 200) throw CabinetError(r.code, message(r))
    }

    /** Проверка утечек с k-анонимностью: серверу уходят только первые 5 знаков SHA-1 пароля. */
    fun pwnedCount(password: CharArray): Int {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        val hex = md.digest(bytes).joinToString("") { "%02X".format(it) }
        bytes.fill(0)
        val r = call("GET", "/v1/hibp/${hex.substring(0, 5)}")
        if (r.code != 200) throw CabinetError(r.code, message(r))
        val suffix = hex.substring(5)
        return r.text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(suffix, ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
    }
}
