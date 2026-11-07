package cc.bridgit.app.portal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.databinding.ActivityPortalScanBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridgit Portal viewer: adds another Bridgit phone by scanning its pairing QR code (CameraX + zxing, on the phone,
 * nothing uploaded) or by pasting its bridgitportal:// link. The link is checked with one read before it is saved.
 */
class PortalScanActivity : AppCompatActivity() {
    /** Started with [EXTRA_SATELLITE]: scans a Bridgit satellite link and returns it in [EXTRA_LINK]. */
    private val satelliteMode get() = intent.getBooleanExtra(EXTRA_SATELLITE, false)

    companion object {
        const val EXTRA_SATELLITE = "satellite"
        const val EXTRA_LINK = "link"
    }

    private lateinit var b: ActivityPortalScanBinding
    private val found = AtomicBoolean(false)
    private val analysis = Executors.newSingleThreadExecutor()
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else showError(getString(R.string.portal_scan_no_camera))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The scanned link holds a key: no screenshots in release builds.
        if (!BuildConfig.DEBUG) cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(window)
        b = ActivityPortalScanBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            b.toolbar.updatePadding(top = bars.top); b.root.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.usePasted.setOnClickListener { use(b.link.text?.toString().orEmpty().trim()) }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() { analysis.shutdown(); super.onDestroy() }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also { it.surfaceProvider = b.preview.surfaceProvider }
            val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
            val scan = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            scan.setAnalyzer(analysis) { image ->
                try {
                    if (found.get()) return@setAnalyzer
                    val plane = image.planes[0]
                    val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                    val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                    val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
                    reader.reset()
                    val wanted = if (satelliteMode) cc.bridgit.app.satellite.SatelliteLink.PREFIX else "${PortalData.SCHEME}://pair#"
                    if (text != null && text.startsWith(wanted) && found.compareAndSet(false, true)) runOnUiThread { use(text) }
                } finally { image.close() }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, scan)
            }.onFailure { showError(it.message ?: getString(R.string.portal_scan_no_camera)) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showError(text: String) { b.error.text = text; b.error.isVisible = true }

    /** A scanned or pasted link: confirm, check it with one read, save it, and show that phone on Home. */
    private fun use(link: String) {
        // Satellite mode (SatelliteActivity): hand the link back; the hub screen claims it.
        if (satelliteMode) {
            if (!link.startsWith(cc.bridgit.app.satellite.SatelliteLink.PREFIX)) { found.set(false); showError(getString(R.string.satellite_not_link)); return }
            setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_LINK, link)); finish(); return
        }
        val fields = PortalData.parsePairLink(link)
        if (fields == null) { found.set(false); showError(getString(R.string.portal_scan_not_link)); return }
        val name = fields["name"].orEmpty().ifBlank { "Bridgit phone" }
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.portal_scan_confirm_title, name))
            .setMessage(getString(R.string.portal_scan_confirm_body, name))
            .setPositiveButton(R.string.portal_scan_confirm_ok) { _, _ -> check(link) }
            .setNegativeButton(R.string.cancel) { _, _ -> found.set(false) }
            .setOnCancelListener { found.set(false) }.show()
    }

    private fun check(link: String) {
        b.error.isVisible = false
        b.usePasted.isEnabled = false; b.usePasted.setText(R.string.portal_scan_checking)
        lifecycleScope.launch {
            val links = PortalLinks(this@PortalScanActivity)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Parse and validate without saving, then one real read through the relay.
                    val f = PortalData.parsePairLink(link) ?: throw IllegalArgumentException(getString(R.string.portal_scan_not_link))
                    val probe = PortalLinks.Link(f["device"].orEmpty(), f["relay"].orEmpty(), f["token"].orEmpty(), f["name"].orEmpty(), null, true, 0)
                    require(Regex("https://[A-Za-z0-9.-]{3,253}(:\\d{1,5})?").matches(probe.relay)) { getString(R.string.portal_scan_not_link) }
                    val home = PortalClient.get(probe, "/v1/portal/home")
                    // Single-use link: swap in a token made on this phone; the link's own token stops working.
                    val own = if (f["claim"] == "1") PortalClient.newToken().also { PortalClient.claim(probe, it) } else null
                    val saved = links.add(link, claimedToken = own)
                    PortalViewer.remember(saved.device, home)
                    saved
                }
            }
            b.usePasted.isEnabled = true; b.usePasted.setText(R.string.portal_scan_use_link)
            result.onSuccess { l ->
                links.select(l.device)
                // Straight to Home, which now shows that phone.
                startActivity(android.content.Intent(this@PortalScanActivity, cc.bridgit.app.ui.MainActivity::class.java)
                    .putExtra("owner_setup_target", "home")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }.onFailure {
                found.set(false)
                showError(getString(R.string.portal_scan_failed, it.message ?: it.javaClass.simpleName))
            }
        }
    }
}
