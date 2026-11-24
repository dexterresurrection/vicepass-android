package uk.zjd.cloudpass

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CabinetError(val code: Int, message: String) : Exception(message)

class DeviceCode(val token: String, val userCode: String, val verificationUrl: String, val telegramUrl: String?,
                 val expiresIn: Int, val interval: Int)

enum class PollState { PENDING, DONE, EXPIRED }

/**
 * Аккаунт Cloud VPN / Vice: тот же API кабинета, что у Кошелька, Мессенджера и Почты.
 * Вход — по коду устройства: POST /cabinet/auth/device/request, затем опрос /cabinet/auth/deeplink/poll.
 * Refresh-токен хранится зашифрованным ключом из Android Keystore. Мастер-пароль хранилища сюда не попадает никогда.
 */
object Cabinet {
    private const val ORIGIN1 = "https://cabinet.zjd.uk"
    private const val ORIGIN2 = "https://cabinet.0nn.us"
    private const val PREFS = "cloudpass_cabinet"
    private const val KEY_ALIAS = "cloudpass_cabinet"

    private var prefs: SharedPreferences? = null
    @Volatile private var origin = ORIGIN1
    @Volatile private var refresh: String? = null
    @Volatile private var access: String? = null
    @Volatile private var accessUntil = 0L

    val loggedIn: Boolean get() = !refresh.isNullOrEmpty()
    val webOrigin: String get() = origin

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        refresh = p.getString("refresh", null)?.let { unseal(it) }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    private fun seal(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    } catch (_: Exception) { null }

    private fun unseal(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (_: Exception) { null }

    private fun setTokens(accessToken: String?, refreshToken: String?, expiresIn: Long) {
        refresh = refreshToken
        access = accessToken
        accessUntil = if (accessToken == null) 0 else System.currentTimeMillis() + (maxOf(expiresIn, 120) - 60) * 1000
        val e = prefs?.edit() ?: return
        val sealed = refreshToken?.let { seal(it) }
        if (sealed == null) e.remove("refresh") else e.putString("refresh", sealed)
        e.apply()
    }

    fun logout() {
        setTokens(null, null, 0)
        PassApi.forget()
    }

    /** Для проверки в эмуляторе: `adb shell setprop debug.cloudpass.cabinet http://10.0.2.2:8780` (без adb не задать). */
    private fun testOrigin(): String = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.cloudpass.cabinet") as String).trim()
    } catch (_: Exception) { "" }

    private fun call(method: String, path: String, body: String?, token: String?): HttpResult {
        val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
        testOrigin().takeIf { it.isNotEmpty() }?.let { return Net.request(method, "$it/api$path", body?.toByteArray(), headers) }
        val first = origin
        return try {
            Net.request(method, "$first/api$path", body?.toByteArray(), headers)
        } catch (e: Exception) {
            val other = if (first == ORIGIN1) ORIGIN2 else ORIGIN1
            val r = Net.request(method, "$other/api$path", body?.toByteArray(), headers)
            origin = other
            r
        }
    }

    private fun accessToken(force: Boolean): String {
        val cached = access
        if (!force && cached != null && System.currentTimeMillis() < accessUntil) return cached
        val rt = refresh ?: throw CabinetError(401, "Войдите в аккаунт через кабинет")
        val r = call("POST", "/cabinet/auth/refresh", JSONObject().put("refresh_token", rt).toString(), null)
        if (r.code == 401) {
            logout()
            throw CabinetError(401, "Вход устарел — войдите в аккаунт снова")
        }
        if (r.code >= 400) throw CabinetError(r.code, "Кабинет: ответ ${r.code}")
        val o = JSONObject(r.text)
        val token = o.optString("access_token")
        if (token.isEmpty()) throw CabinetError(500, "Кабинет: непонятный ответ на обновление входа")
        access = token
        accessUntil = System.currentTimeMillis() + (maxOf(o.optLong("expires_in", 900), 120) - 60) * 1000
        o.optString("refresh_token").takeIf { it.isNotEmpty() && it != rt }?.let { setTokens(token, it, o.optLong("expires_in", 900)) }
        return token
    }

    fun authed(method: String, path: String, body: JSONObject? = null): String {
        var r = call(method, path, body?.toString(), accessToken(false))
        if (r.code == 401) r = call(method, path, body?.toString(), accessToken(true))
        if (r.code >= 400) throw CabinetError(r.code, errorText(r))
        return r.text
    }

    private fun errorText(r: HttpResult): String {
        val detail = try { JSONObject(r.text).opt("detail") } catch (_: Exception) { null }
        return when (detail) {
            is String -> detail
            is JSONObject -> detail.optString("message").ifEmpty { "Кабинет: ответ ${r.code}" }
            else -> "Кабинет: ответ ${r.code}"
        }
    }

    fun deviceRequest(): DeviceCode {
        val r = call("POST", "/cabinet/auth/device/request", "{}", null)
        if (r.code == 429) throw CabinetError(429, "Слишком много попыток входа — подождите минуту")
        if (r.code >= 400) throw CabinetError(r.code, "Кабинет: ответ ${r.code}")
        val o = JSONObject(r.text)
        val token = o.optString("token")
        val code = o.optString("user_code")
        if (token.isEmpty() || code.isEmpty()) throw CabinetError(500, "Кабинет: непонятный ответ на запрос кода")
        return DeviceCode(token, code, o.optString("verification_url"),
            o.optString("telegram_url").takeIf { it.startsWith("https://") },
            o.optInt("expires_in", 600).coerceAtLeast(60), o.optInt("interval", 2).coerceAtLeast(1))
    }

    /** Один опрос. Сеть моргнула или кабинет просит подождать — код ещё жив, это PENDING. */
    fun devicePoll(token: String): PollState {
        val r = try {
            call("POST", "/cabinet/auth/deeplink/poll", JSONObject().put("token", token).toString(), null)
        } catch (_: Exception) {
            return PollState.PENDING
        }
        return when (r.code) {
            200 -> {
                val o = JSONObject(r.text)
                val rt = o.optString("refresh_token")
                if (rt.isEmpty()) throw CabinetError(500, "Кабинет: непонятный ответ на вход")
                setTokens(o.optString("access_token").ifEmpty { null }, rt, o.optLong("expires_in", 900))
                PollState.DONE
            }
            410 -> PollState.EXPIRED
            else -> PollState.PENDING
        }
    }

    /** Имя аккаунта для экрана «Аккаунт» (не обязательно). */
    fun accountName(): String = try {
        val o = JSONObject(authed("GET", "/cabinet/app/messenger-home")).optJSONObject("user")
        o?.optString("name").orEmpty()
    } catch (_: Exception) { "" }
}
