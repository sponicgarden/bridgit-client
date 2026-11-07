package cc.bridgit.app.portal

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import cc.bridgit.app.portal.LinkedDevicesLayout.Role
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Linked devices map's colours and shapes, shared by the map, its legend and the Pair a device area so all three
 * read as one system (owner, 5 Nov 2026): hubs are gold hexagons, portals violet tiles, satellites blue tiles, network
 * devices slate circles, and this phone is green.
 */
object LinkedDevicesGlyph {
    fun dark(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun roleColour(dark: Boolean, r: Role) = when (r) {
        Role.HUB -> if (dark) 0xFFF2B544.toInt() else 0xFF805600.toInt()
        Role.PORTAL -> if (dark) 0xFFB48CFF.toInt() else 0xFF6940AA.toInt()
        Role.SATELLITE -> if (dark) 0xFF5CC8FF.toInt() else 0xFF00688E.toInt()
        Role.NETWORK -> if (dark) 0xFF9FB3CF.toInt() else 0xFF4D5D78.toInt()
    }

    fun thisPhone(dark: Boolean) = if (dark) 0xFF4FE0A0.toInt() else 0xFF006C46.toInt()

    fun hexagon(cx: Float, cy: Float, r: Float) = Path().apply {
        for (i in 0..5) {
            val a = Math.toRadians((60.0 * i) - 90.0)
            val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** A small outline of [role]'s shape in [colour], [sizePx] square, for legends and labels. */
    class Icon(private val role: Role, private val colour: Int, private val sizePx: Int, private val stroke: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND; color = colour }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour; alpha = 50 }
        override fun getIntrinsicWidth() = sizePx
        override fun getIntrinsicHeight() = sizePx
        override fun draw(c: Canvas) {
            val b = bounds; val cx = b.exactCenterX(); val cy = b.exactCenterY()
            val half = minOf(b.width(), b.height()) / 2f - stroke
            val shape = when (role) {
                Role.HUB -> hexagon(cx, cy, half)
                Role.NETWORK -> Path().apply { addCircle(cx, cy, half * 0.92f, Path.Direction.CW) }
                else -> Path().apply {
                    val r = RectF(cx - half * 0.86f, cy - half * 0.86f, cx + half * 0.86f, cy + half * 0.86f)
                    addRoundRect(r, half * 0.4f, half * 0.4f, Path.Direction.CW)
                }
            }
            c.drawPath(shape, fill); c.drawPath(shape, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
