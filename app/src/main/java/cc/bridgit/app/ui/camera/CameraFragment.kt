package cc.bridgit.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FragmentCameraBinding
import cc.bridgit.app.watch.OwnerPhotos
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The Camera tab (owner, 5 Nov 2026): an ordinary camera, rear or front. Every photo is handed to the applets
 * that check photos ([OwnerPhotos]), so walking round the kitchen taking photos feeds the kitchen applet just like
 * its own timed photos. The screen holds the phone's camera lock while it is open, so a watch's timed photo
 * waits (or skips) instead of fighting it for the camera.
 */
class CameraFragment : Fragment() {
    private var _b: FragmentCameraBinding? = null
    private val b get() = _b!!
    private val app get() = requireContext().applicationContext as BridgeApp
    private var rear = true
    private var capture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var locked = false
    private var opening: Job? = null
    private var sent = 0
    /** Asked for the camera permission once on this view; after that only the tappable message asks again. */
    private var asked = false

    private val askCamera: androidx.activity.result.ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) open() else showMessage(getString(R.string.camera_no_permission)) { requestCamera() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _b = FragmentCameraBinding.inflate(inflater, container, false)
        rear = state?.getBoolean(KEY_REAR) ?: prefs().getBoolean(KEY_REAR, true)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            b.top.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top + dp(12)); insets
        }
        b.shutter.setOnClickListener { take() }
        b.flip.setOnClickListener {
            rear = !rear
            prefs().edit().putBoolean(KEY_REAR, rear).apply()
            bind()
        }
        b.sendTo.setOnClickListener { choose() }
        return b.root
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putBoolean(KEY_REAR, rear) }

    override fun onResume() {
        super.onResume()
        renderTargets()
        showConnected()
        // On a satellite: re-read the hub's photo applets, so one added there shows here without waiting for the hub.
        if (app.hubLinks.hubs().isNotEmpty()) viewLifecycleOwner.lifecycleScope.launch {
            val before = app.ownerPhotos.cameraTargets().map { it.id }
            withContext(Dispatchers.IO) { app.hubLinks.refreshFromHubs() }
            if (_b != null && app.ownerPhotos.cameraTargets().map { it.id } != before) { renderTargets(); showConnected() }
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open()
        else if (!asked) { asked = true; requestCamera() }
        else showMessage(getString(R.string.camera_no_permission)) { requestCamera() }
    }

    override fun onPause() {
        _b?.connected?.let { it.removeCallbacks(fadeConnected); it.animate().cancel(); it.isVisible = false }
        close()
        super.onPause()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    private fun requestCamera() { askCamera.launch(Manifest.permission.CAMERA) }

    /** Takes the camera lock (waiting while a watch is mid-photo), then binds the preview. */
    private fun open() {
        if (locked || opening?.isActive == true) return
        opening = viewLifecycleOwner.lifecycleScope.launch {
            var waited = false
            while (!app.registry.cameraLock.tryLock()) {
                if (!waited) { showMessage(getString(R.string.camera_busy)); waited = true }
                delay(700)
            }
            locked = true
            // Clear only the "busy" note: the no-applet explanation stays until an applet checks photos.
            if (_b?.message?.text == getString(R.string.camera_busy)) hideMessage()
            val p = runCatching { withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(requireContext()).get() } }.getOrNull()
            if (p == null) { showMessage(getString(R.string.camera_failed, "no camera")); return@launch }
            provider = p
            bind()
        }
    }

    private fun bind() {
        if (!locked) return // never touch the camera while a watch holds it
        val p = provider ?: return
        val selector = if (rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val canFlip = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        b.flip.isVisible = canFlip
        if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) rear = !rear
        val preview = Preview.Builder().build().also { it.surfaceProvider = b.preview.surfaceProvider }
        val shot = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(b.preview.display?.rotation ?: android.view.Surface.ROTATION_0).build()
        runCatching {
            p.unbindAll()
            p.bindToLifecycle(viewLifecycleOwner, if (rear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA, preview, shot)
            capture = shot
        }.onFailure { showMessage(getString(R.string.camera_failed, it.message ?: it.javaClass.simpleName)) }
    }

    private fun close() {
        opening?.cancel(); opening = null
        runCatching { provider?.unbindAll() }
        provider = null
        capture = null
        if (locked) { locked = false; runCatching { app.registry.cameraLock.unlock() } }
    }

    private fun take() {
        val shot = capture ?: return
        // The callbacks and the check can finish after the owner left the tab (fragment detached): use the app only.
        val appRef = app
        if (appRef.ownerPhotos.cameraTargets().isEmpty()) { return }
        b.shutter.isEnabled = false
        b.flash.alpha = 0.8f
        b.flash.animate().alpha(0f).setDuration(220).start()
        val file = File(appRef.cacheDir, "owner-photo-${System.nanoTime()}.jpg")
        val camera = if (rear) "rear" else "front"
        val targets = appRef.ownerPhotos.chosen()
        shot.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(appRef),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    _b?.shutter?.isEnabled = true
                    sent++
                    _b?.count?.apply { text = sent.toString(); isVisible = true }
                    showResult(appRef.getString(R.string.camera_checking))
                    // The checks go on if the owner leaves the tab; the answer is shown only if the screen is still here.
                    work.launch {
                        val text = try {
                            val bytes = file.readBytes()
                            resultLine(appRef, appRef.ownerPhotos.submitFromCamera(bytes, camera, targets, "camera tab"))
                        } catch (e: OwnerPhotos.NoTargets) { e.message.orEmpty() }
                        catch (e: Exception) { appRef.getString(R.string.camera_failed, e.message ?: e.javaClass.simpleName) }
                        finally { file.delete() }
                        withContext(Dispatchers.Main) { if (_b != null) showResult(text) }
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    file.delete()
                    _b?.shutter?.isEnabled = true
                    showResult(appRef.getString(R.string.camera_failed, e.message ?: "camera error"))
                }
            })
    }

    /** One line per applet: "Kitchen cleanliness: state: messy". */
    private fun resultLine(app: BridgeApp, r: JSONObject): String {
        val ts = r.optJSONArray("targets") ?: return ""
        return (0 until ts.length()).joinToString("\n") { i ->
            val t = ts.getJSONObject(i)
            val checks = t.optJSONArray("checks")
            val lines = (0 until (checks?.length() ?: 0)).map { checks!!.getJSONObject(it) }
                // A people check only says "looked for people" (or that spotting is off); show it when it is the only one.
                .let { cs -> cs.filter { it.optString("kind") != "people" }.ifEmpty { cs } }
                .map { c -> if (c.optString("status") == "skipped") app.getString(R.string.camera_not_checked, c.optString("summary")) else c.optString("summary") }
            "${t.optString("name")}: ${lines.joinToString(" · ")}"
        }
    }

    private fun renderTargets() {
        val all = app.ownerPhotos.cameraTargets()
        val chosen = app.ownerPhotos.chosen()
        b.sendTo.text = when {
            all.isEmpty() -> getString(R.string.camera_send_none)
            chosen.isEmpty() -> if (all.size == 1) getString(R.string.camera_send_some, all[0].name) else getString(R.string.camera_send_all, all.size)
            else -> getString(R.string.camera_send_some, all.filter { it.id in chosen }.joinToString(", ") { it.name })
        }
        b.shutter.alpha = if (all.isEmpty()) 0.4f else 1f
    }

    /** Which applets check the photos: all of them unless the owner picks some. */
    private fun choose() {
        val all = app.ownerPhotos.cameraTargets()
        if (all.isEmpty()) { return }
        val chosen = app.ownerPhotos.chosen()
        val checked = BooleanArray(all.size) { chosen.isEmpty() || all[it].id in chosen }
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.camera_choose_title)
            .setMultiChoiceItems(all.map { it.name }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ids = all.filterIndexed { i, _ -> checked[i] }.map { it.id }
                // None or every one ticked both mean "all", which also covers applets installed later.
                app.ownerPhotos.choose(if (ids.isEmpty() || ids.size == all.size) emptyList() else ids)
                renderTargets(); showConnected()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun showMessage(text: String, onTap: (() -> Unit)? = null) {
        val v = _b ?: return
        v.message.text = text; v.message.isVisible = true
        v.message.setOnClickListener(onTap?.let { f -> View.OnClickListener { f() } })
    }

    /** Lists the applets that check photos taken here, then fades out after 10 seconds. */
    private fun showConnected() {
        val v = _b?.connected ?: return
        v.removeCallbacks(fadeConnected); v.animate().cancel()
        val all = app.ownerPhotos.cameraTargets()
        val chosen = app.ownerPhotos.chosen()
        val names = (if (chosen.isEmpty()) all else all.filter { it.id in chosen }).map { it.name }
        if (names.isEmpty()) { v.isVisible = false; return }
        v.text = getString(R.string.camera_connected, names.joinToString("\n") { "• $it" })
        v.alpha = 1f; v.isVisible = true
        v.postDelayed(fadeConnected, CONNECTED_MS)
    }

    private val fadeConnected = Runnable {
        _b?.connected?.animate()?.alpha(0f)?.setDuration(600)?.withEndAction { _b?.connected?.isVisible = false }?.start()
    }

    private fun hideMessage() { _b?.message?.isVisible = false }

    private fun showResult(text: String) { _b?.result?.apply { this.text = text; isVisible = text.isNotBlank() } }

    private fun prefs() = requireContext().getSharedPreferences("camera_tab", android.content.Context.MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_REAR = "rear"
        const val CONNECTED_MS = 10_000L
        /** Outlives the screen, so a check that was started finishes. */
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
