package cc.bridgit.app.lancamera


/** Only camera-advertised absolute coordinates; never estimate limits from relative nudges. */
data class PtzPoint(val x: Double, val y: Double)
data class PtzSpace(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
    fun contains(p: PtzPoint) = p.x.isFinite() && p.y.isFinite() && p.x in xMin..xMax && p.y in yMin..yMax
    fun target(view: String): PtzPoint {
        val x=(xMin+xMax)/2; val y=(yMin+yMax)/2
        return when(view) {
            "center" -> PtzPoint(x,y)
            "top" -> PtzPoint(x,yMax)
            "left" -> PtzPoint(xMin,y)
            "right" -> PtzPoint(xMax,y)
            "bottom" -> PtzPoint(x,yMin)
            else -> throw IllegalArgumentException("Unknown search view")
        }
    }
    fun near(a: PtzPoint,b: PtzPoint) = kotlin.math.abs(a.x-b.x) <= (xMax-xMin)*.005 && kotlin.math.abs(a.y-b.y) <= (yMax-yMin)*.005
    companion object {
        const val URI="http://www.onvif.org/ver10/tptz/PanTiltSpaces/PositionGenericSpace"
        val VIEWS=listOf("center","top","left","right","bottom")

    }
}

/** Verify each pose before taking exactly one photo; restore the original view even on failure. */
class PtzSearch(private val space: PtzSpace, private val driver: Driver) {
    interface Driver {
        suspend fun authorize()
        suspend fun position(): PtzPoint
        suspend fun move(point: PtzPoint)
        suspend fun settle(ms: Long)
        suspend fun capture(view: String)
        suspend fun stop()
    }
    class PositionUnavailable : Exception("PTZ could not reach the requested view")
    private suspend fun go(point:PtzPoint) {
        driver.authorize(); driver.move(point)
        repeat(80) {
            driver.authorize()
            if(space.near(driver.position(),point)) { driver.settle(500);driver.authorize();if(space.near(driver.position(),point))return }
            driver.settle(250)
        }
        throw PositionUnavailable()
    }
    suspend fun run(views:List<String>) {
        require(views.isNotEmpty() && views.distinct().size==views.size && views.all { it in PtzSpace.VIEWS })
        driver.authorize()
        val original=driver.position()
        if(!space.contains(original)) throw PositionUnavailable()
        try {
            for(view in views) { go(space.target(view));driver.capture(view) }
        } finally {
            // Never restore through revoked consent. Stop is still attempted on cancellation.
            try { driver.authorize(); go(original) } finally { driver.stop() }
        }
    }
}
