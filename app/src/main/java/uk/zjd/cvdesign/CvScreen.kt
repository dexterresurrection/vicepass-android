// Углы и безопасные края экрана: сколько отступить плашке у края, чтобы её не съели скругления и полоса жестов
// (без android.* и Compose). Значения экрана даёт CvScreenAndroid.of(view) (android/view/CvScreenAndroid.kt).
package uk.zjd.cvdesign

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Окно, px: размер, радиусы нижних углов экрана (0 — прямые или окно не у края), полоса жестов/кнопок снизу. */
class CvScreen(val width: Float, val height: Float, val cornerLeft: Float = 0f, val cornerRight: Float = 0f, val bottomBar: Float = 0f) {
    class Insets(val left: Float, val right: Float, val bottom: Float)

    /**
     * Отступы плашки, px: слева, справа и снизу. [left], [right], [bottom] — её края в окне БЕЗ отступов,
     * [radius] — скругление плашки, [dp] — плотность. У края без скруглений отступов нет.
     */
    fun insets(left: Float, right: Float, bottom: Float, radius: Float, dp: Float): Insets {
        val below = height - bottom
        val corner = max(cornerLeft, cornerRight)
        if (below < -1f || below > max(corner, bottomBar)) return Insets(0f, 0f, 0f)
        var lift = max(0f, bottomBar - below)
        // у скруглённого угла — не вплотную к нижнему краю
        if (corner > 0f && below + lift < 8 * dp) lift = 8 * dp - below
        val y = max(0f, below) + lift
        return Insets(side(cornerLeft, radius, left, y, dp), side(cornerRight, radius, width - right, y, dp), lift)
    }

    companion object {
        private const val K = 0.29289f   // 1 − 1/√2: насколько угол скруглённой плашки отстоит от её прямого угла

        /** Отступ от боковой стороны: угол плашки (скругление [r]) должен лежать внутри угла экрана (радиус [corner]). */
        fun side(corner: Float, r: Float, side: Float, below: Float, dp: Float): Float {
            if (corner <= 0f) return 0f
            val y = below + K * r
            if (y >= corner) return 0f
            val free = corner - 2 * dp
            val dy = corner - y
            val x = if (dy >= free) corner else corner - sqrt(free * free - dy * dy)
            return min(max(0f, x - K * r - side), 28 * dp)
        }
    }
}
