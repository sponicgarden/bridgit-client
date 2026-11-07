package cc.bridgit.app.lancamera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Deterministic, bounded controller. Android/model adapters live outside this class. */
class FramingEngine(private val driver: Driver, private val limits: FramingRules.Limits, private val cameras: List<String>,
    private val calibrate: Boolean, private val clock: () -> Long = System::nanoTime, private val changed: (State) -> Unit = {}) {
    interface Driver {
        suspend fun authorize()
        suspend fun snapshot(camera: String): ByteArray
        suspend fun assess(frame: ByteArray): FramingRules.Observation
        suspend fun displacement(before: ByteArray, after: ByteArray, axis: String): Int? // image left/up=-1
        suspend fun controls(camera: String): CameraControls
        suspend fun move(camera: String, pan: Double, tilt: Double)
        suspend fun settle(ms: Long)
        suspend fun stop(camera: String): Boolean
    }
    data class State(val state: String = "search", val reason: String? = null, val camera: String? = null,
        val moves: Int = 0, val checks: Int = 0, val travel: Double = 0.0, val observation: FramingRules.Observation? = null,
        val frame: ByteArray? = null, val frameAt: Long = 0, val stopRequested: Boolean = false, val stopConfirmed: Boolean? = null,
        val framingLimited: Boolean = false, val panSign: Int? = null, val tiltSign: Int? = null)
    var state = State(); private set
    private val started = clock()
    private var touched: String? = null
    private class End(val reason: String): Exception()
    private fun update(f: (State) -> State) { state=f(state); changed(state) }
    private suspend fun guard() {
        driver.authorize()
        if ((clock()-started)/1_000_000 >= limits.timeoutS*1000L) throw End("time_limit")
    }
    private suspend fun frame(camera: String): ByteArray { guard(); return driver.snapshot(camera).also { guard(); update { s -> s.copy(camera=camera,frame=it,frameAt=System.currentTimeMillis()) } } }
    private suspend fun assess(bytes: ByteArray): FramingRules.Observation {
        guard(); if (state.checks >= limits.checks) throw End("check_limit")
        update { it.copy(checks=it.checks+1) }
        return driver.assess(bytes).also { guard(); update { s -> s.copy(observation=it) } }
    }
    private suspend fun shift(before: ByteArray, after: ByteArray, axis: String): Int? {
        guard(); if(state.checks >= limits.checks) throw End("check_limit")
        update { it.copy(checks=it.checks+1) }
        return driver.displacement(before,after,axis).also { guard() }
    }
    private suspend fun move(camera: String, pan: Double, tilt: Double, controls: CameraControls) {
        guard()
        if (state.checks >= limits.checks) throw End("check_limit")
        if (state.moves >= limits.moves) throw End("move_limit")
        if (state.travel+abs(pan)+abs(tilt)>limits.travel+1e-9) throw End("travel_limit")
        if (!controls.permits(pan,tilt)) throw End("unsupported_relative_space")
        // Reserve budget before I/O: a failed/uncertain command still consumes it.
        update { it.copy(moves=it.moves+1,travel=it.travel+abs(pan)+abs(tilt)) }
        touched=camera
        driver.move(camera,pan,tilt); guard(); driver.settle(limits.settleMs); guard()
    }
    private suspend fun signs(camera: String, initial: ByteArray, controls: CameraControls): Triple<Int,Int,ByteArray> {
        if (!calibrate) throw End("calibration_required")
        var before=initial
        suspend fun axis(axis: String): Int {
            val probe=minOf(.01,limits.step)
            move(camera,if(axis=="horizontal") probe else 0.0,if(axis=="vertical") probe else 0.0,controls)
            val after=frame(camera)
            val delta=shift(before,after,axis)
            before=after
            return when(delta) { -1 -> 1; 1 -> -1; else -> throw End("calibration_unclear") }
        }
        val x=axis("horizontal"); val y=axis("vertical")
        update { it.copy(panSign=x,tiltSign=y) }
        return Triple(x,y,before)
    }
    suspend fun run(): State {
        try {
            var selected: String?=null; var bytes: ByteArray?=null; var observation: FramingRules.Observation?=null
            var ambiguous=false
            // Try all chosen cameras at their present aim before any PTZ search.
            for(camera in cameras) {
                val f=frame(camera); val o=assess(f)
                if(FramingRules.similar(o,limits)) { selected=camera; bytes=f; observation=o; break }
                if(o.visibility?.first!="absent" || (o.visibility?.second?:0.0)<limits.confidence || o.similarity==null) ambiguous=true
            }
            if(selected==null) {
                if(ambiguous) throw End("target_unclear")
                // One finite sweep per selected camera; all share the same budget.
                for(camera in cameras) {
                    guard(); val controls=driver.controls(camera)
                    if(!controls.relative) continue
                    var f=frame(camera)
                    val (sx,sy,calibrated)=signs(camera,f,controls); f=calibrated
                    for((x,y) in listOf(1 to 0,-1 to 0,0 to 1,0 to -1)) {
                        move(camera,x*limits.step*sx,y*limits.step*sy,controls)
                        val next=frame(camera)
                        val axis=if(x!=0) "horizontal" else "vertical"
                        val delta=shift(f,next,axis)
                        if(delta==null || delta != -(if(x!=0) x else y)) throw End("motion_unverified")
                        f=next; val o=assess(f)
                        if(FramingRules.similar(o,limits)) { selected=camera; bytes=f; observation=o; break }
                        if(o.visibility?.first !in listOf("absent","whole","cropped")) throw End("target_unclear")
                    }
                    if(selected!=null) break
                }
                if(selected==null) throw End("not_found")
            }
            val camera=selected; var f=bytes!!; var o=observation!!
            update { it.copy(state="center",camera=camera,frame=f,observation=o) }
            var controls: CameraControls?=null; var sx=state.panSign; var sy=state.tiltSign
            var stable=0; var unclear=0; var lastX=0; var lastY=0
            while(true) {
                guard()
                if(!FramingRules.similar(o,limits)) throw End("target_lost_or_unclear")
                val x=FramingRules.direction(o.horizontal,"left","right",limits.confidence)
                val y=FramingRules.direction(o.vertical,"up","down",limits.confidence)
                if(x==null || y==null) {
                    stable=0
                    if(++unclear>2) throw End("position_confidence")
                    f=frame(camera); o=assess(f); continue
                }
                unclear=0
                if(x==0 && y==0) {
                    update { it.copy(state="verify") }
                    if(++stable>=2) {
                        update { it.copy(state="held",reason="centered",framingLimited=o.visibility?.first!="whole" || o.size?.first!="suitable" || (o.size?.second?:0.0)<limits.confidence) }
                        break
                    }
                    driver.settle(limits.settleMs); f=frame(camera); o=assess(f); continue
                }
                stable=0
                if(controls==null) controls=driver.controls(camera)
                if(!controls.relative) throw End("relative_ptz_unsupported")
                if(sx==null || sy==null) {
                    val signs=signs(camera,f,controls); sx=signs.first; sy=signs.second; f=signs.third; o=assess(f); continue
                }
                // A reversal needs a second independent answer; no oscillation on one bin flip.
                if(x*lastX<0 || y*lastY<0) {
                    driver.settle(limits.settleMs); val next=frame(camera); val confirm=assess(next)
                    if(!FramingRules.similar(confirm,limits)) throw End("target_lost_or_unclear")
                    if(FramingRules.direction(confirm.horizontal,"left","right",limits.confidence)!=x || FramingRules.direction(confirm.vertical,"up","down",limits.confidence)!=y) throw End("direction_unstable")
                    f=next; o=confirm
                }
                update { it.copy(state="center") }
                // One axis per command makes movement verification interpretable.
                val horizontal=x!=0; val direction=if(horizontal) x else y
                val amount=if(horizontal) o.panAmount else o.tiltAmount
                val proposed=if(amount!=null && amount.second>=limits.confidence) when(amount.first) { "large" -> .03; "medium" -> .02; else -> .01 } else .01
                val step=minOf(limits.step,proposed)
                move(camera,if(horizontal) direction*step*sx else 0.0,if(horizontal) 0.0 else direction*step*sy,controls)
                val after=frame(camera)
                val delta=shift(f,after,if(horizontal) "horizontal" else "vertical")
                if(delta==null || delta != -direction) throw End("motion_unverified")
                lastX=x; lastY=y; f=after; o=assess(f)
            }
        } catch(e: End) { update { it.copy(state=if(e.reason.endsWith("limit")) "limit" else "unclear",reason=e.reason) } }
        catch(e: CancellationException) { update { it.copy(state="cancelled",reason="cancelled_or_access_changed") }; throw e }
        catch(e: Exception) { update { it.copy(state="failed",reason="camera_or_model_failed") } }
        finally {
            touched?.let { camera -> withContext(NonCancellable) {
                update { it.copy(stopRequested=true) }
                val stopped=runCatching { driver.stop(camera) }.getOrDefault(false)
                update { it.copy(stopConfirmed=stopped,state=if(!stopped && it.state=="held") "failed" else it.state,
                    reason=if(!stopped) "stop_unconfirmed" else it.reason) }
            } }
        }
        return state
    }
}
