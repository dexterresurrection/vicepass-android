// Экран Android для CvScreen: скругления нижних углов (API 31+ — WindowInsets.getRoundedCorner, раньше — системный
// размер) и полоса жестов или кнопок. Для Compose:
//   val view = LocalView.current
//   CvAdsPlate(…, screen = { CvScreenAndroid.of(view) })
package uk.zjd.cvdesign

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets

object CvScreenAndroid {
    /** Экран окна, в котором стоит [view]; null — окна ещё нет. */
    fun of(view: View): CvScreen? {
        val root = view.rootView ?: return null
        if (root.width == 0 || root.height == 0) return null
        val wi = view.rootWindowInsets
        var bl = 0f
        var br = 0f
        var bar = 0f
        if (Build.VERSION.SDK_INT >= 31) {
            if (wi != null) {
                bl = (wi.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0).toFloat()
                br = (wi.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0).toFloat()
            }
        } else if (Build.VERSION.SDK_INT >= 28 && fullScreen(view, root)) {
            val res = view.resources
            val id = res.getIdentifier("rounded_corner_radius_bottom", "dimen", "android").takeIf { it != 0 }
                ?: res.getIdentifier("rounded_corner_radius", "dimen", "android")
            if (id != 0) runCatching { res.getDimensionPixelSize(id).toFloat() }.getOrNull()?.let { bl = it; br = it }
        }
        if (wi != null) {
            bar = if (Build.VERSION.SDK_INT >= 30) wi.getInsets(WindowInsets.Type.systemBars()).bottom.toFloat()
            else @Suppress("DEPRECATION") minOf(wi.systemWindowInsetBottom, wi.stableInsetBottom).toFloat()
        }
        return CvScreen(root.width.toFloat(), root.height.toFloat(), bl, br, bar)
    }

    /** Окно на весь экран (не часть разделённого экрана и не диалог) — только тогда его углы — углы экрана. */
    private fun fullScreen(view: View, root: View): Boolean {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        runCatching { view.display?.getRealMetrics(m) }
        return m.widthPixels > 0 && root.width == m.widthPixels && root.height >= m.heightPixels - 1
    }
}
