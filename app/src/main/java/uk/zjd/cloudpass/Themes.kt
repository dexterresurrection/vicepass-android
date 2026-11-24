package uk.zjd.cloudpass

import android.app.Activity
import android.view.Menu
import android.view.MenuItem
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.stylish.Stylish
import com.kunzisoft.keepass.settings.PreferencesUtil
import uk.zjd.cvdesign.CvTheme
import uk.zjd.cvdesign.CvThemeButton

/** Кнопка темы набора (G3) в шапке: нажатие — следующая тема, долгое — список. Все темы KeePassDX — в настройках. */
object Themes {
    private fun parts(t: CvTheme): Pair<Int, Int> = when (t) {
        CvTheme.DARK -> R.string.list_style_name_light to R.string.list_style_brightness_night
        CvTheme.FF7 -> R.string.list_style_name_cp_ff7 to R.string.list_style_brightness_night
        CvTheme.VICE_NIGHT -> R.string.list_style_name_cp_vice_day to R.string.list_style_brightness_night
        CvTheme.VICE_DAY -> R.string.list_style_name_cp_vice_day to R.string.list_style_brightness_light
        CvTheme.LIGHT -> R.string.list_style_name_light to R.string.list_style_brightness_light
    }

    fun current(activity: Activity): CvTheme = when (Stylish.getThemeId(activity)) {
        R.style.CloudPassStyle_Ff7 -> CvTheme.FF7
        R.style.CloudPassStyle_ViceNight -> CvTheme.VICE_NIGHT
        R.style.CloudPassStyle_ViceDay -> CvTheme.VICE_DAY
        R.style.KeepassDXStyle_Light -> CvTheme.LIGHT
        else -> CvTheme.DARK
    }

    fun addTo(activity: Activity, menu: Menu) {
        menu.add(Menu.NONE, R.id.menu_cp_theme, 60, R.string.cp_theme).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            actionView = CvThemeButton(activity).apply {
                me = "pass"
                current = { current(activity) }
                onPick = { pick(activity, it) }
            }
        }
    }

    private fun pick(activity: Activity, t: CvTheme) {
        val before = Stylish.getThemeId(activity)
        val (style, brightness) = parts(t)
        PreferenceManager.getDefaultSharedPreferences(activity).edit(commit = true) {
            putString(activity.getString(R.string.setting_style_brightness_key), activity.getString(brightness))
        }
        PreferencesUtil.setStyle(activity, activity.getString(style))
        if (Stylish.getThemeId(activity) != before) activity.recreate()
    }
}
