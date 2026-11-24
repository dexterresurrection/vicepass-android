package uk.zjd.cloudpass

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.kunzisoft.keepass.R
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Проверка пароля на утечки с k-анонимностью: SHA-1 считается на устройстве, наружу уходят только первые 5 знаков.
 * Со входом — через наш сервер (pass.zjd.uk/v1/hibp, сервер тоже видит только 5 знаков), без входа — напрямую
 * api.pwnedpasswords.com (может быть недоступен из РФ).
 */
object Breach {
    private val io = Executors.newSingleThreadExecutor()

    private fun direct(password: CharArray): Int {
        val bytes = String(password).toByteArray(Charsets.UTF_8)
        val hex = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02X".format(it) }
        bytes.fill(0)
        val r = Net.request("GET", "https://api.pwnedpasswords.com/range/${hex.substring(0, 5)}", headers = mapOf("Add-Padding" to "true"))
        if (r.code != 200) throw CabinetError(r.code, "Сервис проверки не отвечает (${r.code})")
        val suffix = hex.substring(5)
        return r.text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(suffix, true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
    }

    fun check(activity: Activity, password: CharArray) {
        Cabinet.init(activity)
        io.execute {
            val res = runCatching { if (Cabinet.loggedIn) PassApi.pwnedCount(password) else direct(password) }
            password.fill('\u0000')
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val msg = res.fold({ n ->
                    if (n > 0) activity.getString(R.string.cp_breach_found, n) else activity.getString(R.string.cp_breach_clean)
                }, { it.message ?: "Ошибка" })
                AlertDialog.Builder(activity).setTitle(R.string.cp_breach_check)
                    .setMessage(msg + "\n\n" + activity.getString(R.string.cp_breach_how))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }
}
