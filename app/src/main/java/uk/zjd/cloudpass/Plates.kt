package uk.zjd.cloudpass

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.settings.PreferencesUtil
import uk.zjd.cvdesign.CvAds
import uk.zjd.cvdesign.CvAdsView
import uk.zjd.cvdesign.CvApps
import uk.zjd.cvdesign.CvAppsPlateView
import java.io.File

/**
 * Плашки из дизайн-кита: реклама (pass.start) и «Vice apps» / «Наши приложения».
 * Только на экранах без данных хранилища: выбор хранилища и «Аккаунт». Никогда — в списке паролей, записи,
 * разблокировке и автозаполнении (там их не создаём вовсе).
 */
object Plates {
    private var ads: CvAds? = null

    private fun ads(ctx: Context): CvAds = ads ?: CvAds(File(ctx.applicationContext.cacheDir, "cv-ads")).also { ads = it }

    /** Оформление плашки по теме приложения: Vice → vice, «Классика RPG» → ff7, остальные → plain. */
    fun look(ctx: Context): Pair<String, Boolean> {
        val style = PreferencesUtil.getStyle(ctx)
        return when (style) {
            ctx.getString(R.string.list_style_name_cp_vice_day) -> "vice" to true
            ctx.getString(R.string.list_style_name_cp_vice_night) -> "vice" to false
            ctx.getString(R.string.list_style_name_cp_ff7) -> "ff7" to false
            else -> "plain" to false
        }
    }

    fun showAd(container: FrameLayout, slot: String = "pass.start") {
        val ctx = container.context
        container.removeAllViews()
        val (lk, day) = look(ctx)
        container.addView(CvAdsView(ctx).apply {
            this.ads = ads(ctx)
            this.slot = slot
            line = Brand.get(ctx).line
            look = lk
            this.day = day
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun appsPlate(ctx: Context): CvAppsPlateView = CvAppsPlateView(ctx).apply {
        line = if (Brand.get(ctx).vice) CvApps.Line.VICE else CvApps.Line.CLOUD
        look = look(ctx).first
        current = setOf(Brand.get(ctx).product)
    }
}
