package cc.bridgit.app.lancamera

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Named PTZ positions belong to one installed applet principal on one physical monitoring host. */
internal class CameraViews(context: Context) : SQLiteOpenHelper(context,"applet_camera_views.sqlite",null,1) {
    companion object {
        val NAME = Regex("[a-z][a-z0-9_]{0,31}")
        fun fingerprint(c: LanCamera): String {
            val identity = listOf(c.id,c.host,c.username,c.password).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    override fun onCreate(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE views(agent TEXT NOT NULL,name TEXT NOT NULL,revision TEXT NOT NULL,document TEXT NOT NULL,PRIMARY KEY(agent,name))")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported camera view schema")

    private fun read(agent: String,name: String): JSONObject? =
        readableDatabase.rawQuery("SELECT document,revision FROM views WHERE agent=? AND name=?",arrayOf(agent,name)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)).put("revision",it.getString(1)) else null
        }

    fun status(agent: String,name: String): JSONObject = JSONObject().put("view",name).apply {
        val current = read(agent,name)
        put("saved",current != null)
        if (current != null) for (field in listOf("camera","x","y","revision")) put(field,current.get(field))
    }

    /** An absent expected revision means create. Compare and replace under one database write lock. */
    fun replace(agent: String,name: String,expected: String?,view: JSONObject?, authorize: () -> Unit = {}) {
        require(NAME.matches(name))
        val db = writableDatabase
        db.beginTransaction()
        try {
            authorize()
            if (read(agent,name)?.getString("revision") != expected)
                throw ApiError(409,"conflict","The saved camera view changed. Read it again.")
            if (view == null) db.delete("views","agent=? AND name=?",arrayOf(agent,name))
            else {
                if (expected == null) db.rawQuery("SELECT count(*) FROM views WHERE agent=?",arrayOf(agent)).use {
                    it.moveToFirst()
                    if (it.getInt(0) >= 8) throw ApiError(409,"too_many_open","Remove a saved camera view first.")
                }
                db.replaceOrThrow("views",null,ContentValues().apply {
                    put("agent",agent); put("name",name); put("revision",UUID.randomUUID().toString()); put("document",view.toString())
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun removeAgent(agent: String) { writableDatabase.delete("views","agent=?",arrayOf(agent)) }

    suspend fun savePosition(app: BridgeApp,agent: String,name: String,id: String,expected: String?,authorize: () -> Unit) = withContext(Dispatchers.IO) {
        val token = UUID.randomUUID().toString()
        authorize()
        if (!CameraMotion.acquire(listOf(id),token)) throw ApiError(409,"sensor_busy","The camera is moving.")
        try {
            val camera = app.lanCameras.get(id)
            val client = Onvif(camera,app,token,authorize)
            val profile = client.info().profile
            val pose = client.feedingPosition(profile)
            authorize()
            if (fingerprint(camera) != fingerprint(app.lanCameras.get(id))) throw ApiError(409,"conflict","Camera enrollment changed.")
            replace(agent,name,expected,JSONObject().put("camera",id).put("profile",profile)
                .put("x",pose.x).put("y",pose.y).put("enrollment",fingerprint(camera)),authorize)
        } finally { CameraMotion.release(token) }
    }

    suspend fun capture(app: BridgeApp,agent: String,name: String,id: String,authorize: () -> Unit): ByteArray = withContext(Dispatchers.IO) {
        val view = read(agent,name) ?: throw ApiError(409,"camera_view_missing","Save the camera view on this host first.")
        val enrolled = app.lanCameras.get(id)
        val context = currentCoroutineContext()
        fun live() {
            context.ensureActive()
            authorize()
            if (view.getString("camera") != id || read(agent,name)?.getString("revision") != view.getString("revision") ||
                fingerprint(app.lanCameras.get(id)) != view.getString("enrollment"))
                throw ApiError(409,"camera_view_changed","Review the camera enrollment and save the view again.")
        }
        live()
        val service = BridgeService.running ?: throw ApiError(409,"camera_unavailable","Start Bridgit on the monitoring device.")
        val token = UUID.randomUUID().toString()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        if (!CameraMotion.acquire(listOf(id),token) { cancelled.set(true) }) throw ApiError(409,"sensor_busy","The camera is moving.")
        try {
            val notification = service.captureStarted("Checking a saved camera view")
            try {
                fun allowed() {
                    if (cancelled.get()) throw ApiError(409,"capture_cancelled","Camera movement cancelled.")
                    live()
                }
                val profile = view.getString("profile")
                val target = Onvif.Position(view.getDouble("x"),view.getDouble("y"))
                val taskOwned = app.applets.forAgent(agent) != null
                if (taskOwned) cc.bridgit.app.applets.AppletDeviceStops.begin(app, agent, id, profile, token)
                val client = Onvif(enrolled,app,token) { allowed() }
                val arrived = CameraAim.run(object: CameraAim.Driver {
                    override suspend fun authorize() = allowed()
                    override suspend fun move() {
                        check(client.info().profile == profile) { "Camera profile changed. Save the view again." }
                        client.aimAt(profile,target)
                    }
                    override suspend fun settle() = delay(1000)
                    override suspend fun arrived(): Boolean {
                        val actual = client.feedingPosition(profile)
                        return kotlin.math.abs(actual.x-target.x) <= .025 && kotlin.math.abs(actual.y-target.y) <= .025
                    }
                    override suspend fun stop() = if (taskOwned) cc.bridgit.app.applets.AppletDeviceStops.stop(app, token)
                        else runCatching { Onvif(enrolled,app,token).stop(profile) }.isSuccess
                })
                if (!arrived) throw ApiError(409,"camera_view_unconfirmed","The camera did not confirm its position and Stop.")
                allowed()
                RtspFrame.snapshot(app,enrolled).also { allowed() }
            } finally { service.captureEnded(notification) }
        } finally { CameraMotion.release(token) }
    }
}
