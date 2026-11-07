package cc.bridgit.app.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.applets.AppletTables
import cc.bridgit.app.applets.CameraShots
import cc.bridgit.app.ui.common.dp
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The full-screen camera of an applet card's camera button (docs/applet-wave2.md B): portrait, a shutter, a
 * front/back switch, a strip of the last shots with a count, and Done. Each tap takes a photo and queues its save
 * ([CameraShots.queue]) at once, so the owner keeps shooting; saves finish even after the screen closes. Holds the
 * phone's camera lock while open, like the Camera tab.
 */
class AppletCameraActivity : AppCompatActivity() {
    private val app get() = applicationContext as BridgeApp
    private lateinit var target: CameraShots.Target
    private lateinit var preview: PreviewView
    private lateinit var strip: LinearLayout
    private lateinit var count: TextView
    private lateinit var message: TextView
    private lateinit var flip: MaterialButton
    private lateinit var flash: View
    private var back = true
    private var capture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var locked = false
    private var opening: Job? = null
    private var taken = 0
    private var asked = false
    private var turn: OrientationEventListener? = null

    private val askCamera: androidx.activity.result.ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) open() else showMessage(getString(R.string.camera_no_permission)) { requestCamera() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val row = runCatching { JSONObject(intent.getStringExtra(EXTRA_ROW) ?: "{}") }.getOrDefault(JSONObject())
        target = CameraShots.Target(intent.getStringExtra(EXTRA_AGENT).orEmpty(), intent.getStringExtra(EXTRA_TABLE).orEmpty(),
            intent.getStringExtra(EXTRA_PHOTO) ?: "photo", row, intent.getStringExtra(EXTRA_SIZE) ?: "large", intent.getLongExtra(EXTRA_INTERACTION, 0L))
        back = state?.getBoolean(KEY_BACK) ?: (intent.getStringExtra(EXTRA_LENS) != "front")
        taken = state?.getInt(KEY_TAKEN) ?: 0
        setContentView(layout())
        if (app.agents.get(target.agentId)?.allows("camera") != true) {
            showMessage("This applet isn't allowed to use the camera. Turn on its camera permission in Installed applets.")
        }
    }

    private fun layout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        root.addView(flash, FrameLayout.LayoutParams(-1, -1))
        message = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(dp(24), dp(16), dp(24), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xCC000000.toInt()) }
        }
        root.addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0x99000000.toInt()); setPadding(dp(12), dp(8), dp(12), dp(16)) }
        val stripRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stripRow.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        count = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(12), 0, 0, 0) }
        stripRow.addView(count, LinearLayout.LayoutParams(-2, -2))
        bottom.addView(stripRow, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        flip = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Switch"; setTextColor(Color.WHITE); contentDescription = "Switch between the front and back camera"
            setOnClickListener { back = !back; bind() }
        }
        controls.addView(flip, LinearLayout.LayoutParams(0, -2, 1f))
        val shutter = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(4), 0xFF9E9E9E.toInt()) }
            contentDescription = "Take a photo"; isClickable = true; isFocusable = true
            setOnClickListener { take() }
        }
        controls.addView(FrameLayout(this).apply { addView(shutter, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.CENTER)) }, LinearLayout.LayoutParams(0, dp(84), 1f))
        val done = MaterialButton(this).apply { text = "Done"; setOnClickListener { finish() } }
        controls.addView(done, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(controls, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            bottom.setPadding(dp(12), dp(8), dp(12), dp(16) + bars.bottom); insets
        }
        showCount()
        return root
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putBoolean(KEY_BACK, back); out.putInt(KEY_TAKEN, taken) }

    override fun onResume() {
        super.onResume()
        listener = this
        turn = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                // The screen stays portrait; the photo follows how the phone is held.
                capture?.targetRotation = when (deg) { in 45 until 135 -> Surface.ROTATION_270; in 135 until 225 -> Surface.ROTATION_180; in 225 until 315 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
        if (app.agents.get(target.agentId)?.allows("camera") != true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open()
        else if (!asked) { asked = true; requestCamera() }
        else showMessage(getString(R.string.camera_no_permission)) { requestCamera() }
    }

    override fun onPause() {
        turn?.disable(); turn = null
        if (listener === this) listener = null
        close()
        super.onPause()
    }

    private fun requestCamera() { askCamera.launch(Manifest.permission.CAMERA) }

    /** Takes the camera lock (waiting while a watch is mid-photo), then binds the preview. */
    private fun open() {
        if (locked || opening?.isActive == true) return
        opening = lifecycleScope.launch {
            var waited = false
            while (!app.registry.cameraLock.tryLock()) {
                if (!waited) { showMessage(getString(R.string.camera_busy)); waited = true }
                delay(700)
            }
            locked = true
            if (message.text == getString(R.string.camera_busy)) hideMessage()
            val p = runCatching { withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(this@AppletCameraActivity).get() } }.getOrNull()
            if (p == null) { showMessage(getString(R.string.camera_failed, "no camera")); return@launch }
            provider = p
            bind()
        }
    }

    private fun bind() {
        if (!locked) return
        val p = provider ?: return
        val both = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        flip.visibility = if (both) View.VISIBLE else View.INVISIBLE
        if (!runCatching { p.hasCamera(if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)) back = !back
        val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
        // The A11's front camera skips exposure in minimize-latency mode: its photos came out near black while the
        // preview was bright. Front shots use quality mode (still queued, so the shutter never waits).
        val mode = if (back) ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY else ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        val shot = ImageCapture.Builder().setCaptureMode(mode).setTargetRotation(Surface.ROTATION_0).build()
        runCatching {
            p.unbindAll()
            p.bindToLifecycle(this, if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA, pv, shot)
            capture = shot
        }.onFailure { showMessage(getString(R.string.camera_failed, it.message ?: it.javaClass.simpleName)) }
    }

    private fun close() {
        opening?.cancel(); opening = null
        runCatching { provider?.unbindAll() }
        provider = null; capture = null
        if (locked) { locked = false; runCatching { app.registry.cameraLock.unlock() } }
    }

    /** One tap, one photo: captured to a file (CameraX writes its EXIF rotation) and queued; the shutter never waits. */
    private fun take() {
        val shot = capture ?: return
        flash.alpha = 0.7f; flash.animate().alpha(0f).setDuration(200).start()
        val appRef = app
        // The front camera's still capture came out near black in a dim room on the A11 (ISO 1960, 1/16 s) while its
        // preview was bright, in both capture modes. Front shots keep the frame the owner sees, un-mirrored so labels
        // and brand names read the right way round.
        if (!back) {
            val frame = preview.bitmap
            if (frame != null) {
                val t = target
                val upright = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, android.graphics.Matrix().apply { preScale(-1f, 1f) }, true)
                if (upright !== frame) frame.recycle()
                taken++; showCount()
                CameraShots.queue.launch {
                    val bytes = java.io.ByteArrayOutputStream().use { o -> upright.compress(Bitmap.CompressFormat.JPEG, 92, o); o.toByteArray() }
                    thumb(bytes)?.let { b -> post { it.addThumb(b) } }
                    upright.recycle()
                    try { CameraShots.save(appRef, t, bytes) }
                    catch (e: AppletTables.Refused) { post { it.showMessage("A photo wasn't saved: ${e.message}") } }
                    catch (e: Exception) { post { it.showMessage("A photo wasn't saved: ${e.message ?: e.javaClass.simpleName}") } }
                }
                return
            }
        }
        val file = File(cacheDir, "applet-shot-${System.nanoTime()}.jpg")
        val t = target
        shot.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(appRef), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                taken++; showCount()
                CameraShots.queue.launch {
                    val bytes = try { file.readBytes() } catch (e: Exception) { null } finally { file.delete() }
                    if (bytes == null) { post { it.showMessage("A photo couldn't be read back from the camera.") }; return@launch }
                    thumb(bytes)?.let { b -> post { it.addThumb(b) } }
                    try { CameraShots.save(appRef, t, bytes) }
                    catch (e: AppletTables.Refused) { post { it.showMessage("A photo wasn't saved: ${e.message}") } }
                    catch (e: Exception) { post { it.showMessage("A photo wasn't saved: ${e.message ?: e.javaClass.simpleName}") } }
                }
            }
            override fun onError(e: ImageCaptureException) {
                file.delete()
                showMessage(getString(R.string.camera_failed, e.message ?: "camera error"))
            }
        })
    }

    private fun thumb(bytes: ByteArray): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 160) s *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    private fun addThumb(b: Bitmap) {
        val img = ImageView(this).apply {
            setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.DKGRAY) }; clipToOutline = true
        }
        strip.addView(img, 0, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(6) })
        while (strip.childCount > MAX_THUMBS) strip.removeViewAt(strip.childCount - 1)
    }

    private fun showCount() {
        count.text = if (taken == 1) "1 photo" else "$taken photos"
        count.contentDescription = count.text
    }

    private fun showMessage(text: String, onTap: (() -> Unit)? = null) {
        message.text = text; message.visibility = View.VISIBLE
        message.setOnClickListener(onTap?.let { f -> View.OnClickListener { f() } } ?: View.OnClickListener { hideMessage() })
    }

    private fun hideMessage() { message.visibility = View.GONE }

    companion object {
        private const val EXTRA_AGENT = "agent"
        private const val EXTRA_TABLE = "table"
        private const val EXTRA_PHOTO = "photo"
        private const val EXTRA_ROW = "row"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_LENS = "lens"
        private const val EXTRA_INTERACTION = "interaction"
        private const val KEY_BACK = "back"
        private const val KEY_TAKEN = "taken"
        private const val MAX_THUMBS = 12

        /** The screen showing now, for the saves' thumbnails and errors (null when it closed). */
        @Volatile private var listener: AppletCameraActivity? = null
        private fun post(f: (AppletCameraActivity) -> Unit) { android.os.Handler(android.os.Looper.getMainLooper()).post { listener?.let(f) } }

        /** Opens the camera for a card's camera button: [camera] is its `do.camera` with the row's references filled. */
        fun start(context: Context, agentId: String, interactionId: Long, camera: JSONObject, row: JSONObject) {
            context.startActivity(Intent(context, AppletCameraActivity::class.java)
                .putExtra(EXTRA_AGENT, agentId).putExtra(EXTRA_INTERACTION, interactionId)
                .putExtra(EXTRA_TABLE, camera.optString("table")).putExtra(EXTRA_PHOTO, camera.optString("photo", "photo"))
                .putExtra(EXTRA_ROW, row.toString()).putExtra(EXTRA_SIZE, camera.optString("size", "large"))
                .putExtra(EXTRA_LENS, camera.optString("lens", "back")))
        }
    }
}
