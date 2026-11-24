package uk.zjd.cloudpass

import android.content.Context
import org.json.JSONObject

/**
 * Линия приложения: Cloud Pass или Vice Pass. Один APK, одна подпись; линию задаёт assets/brand.json
 * ({"v":1,"brand":"vice","name":"Vice Pass","theme":"vice-night"}), Vice-APK получается заменой этого файла
 * и переключением значка.
 */
class Brand private constructor(val vice: Boolean, val name: String) {
    /** продукт в downloads.json (обновления) */
    val product: String get() = if (vice) "vice-pass" else "pass"
    val flavor: String get() = if (vice) "vpass-universal" else "pass-universal"
    val line: String get() = if (vice) "vice" else "cloud"
    /** как называть аккаунт в текстах */
    val serviceName: String get() = if (vice) "Vice" else "Cloud VPN"

    companion object {
        @Volatile
        private var cached: Brand? = null

        fun get(context: Context): Brand = cached ?: synchronized(this) {
            cached ?: read(context).also { cached = it }
        }

        private fun read(context: Context): Brand = try {
            val o = JSONObject(context.applicationContext.assets.open("brand.json").use { it.readBytes().toString(Charsets.UTF_8) })
            val vice = o.optString("brand") == "vice"
            Brand(vice, o.optString("name").ifEmpty { if (vice) "Vice Pass" else "Cloud Pass" })
        } catch (_: Exception) {
            Brand(false, "Cloud Pass")
        }
    }
}
