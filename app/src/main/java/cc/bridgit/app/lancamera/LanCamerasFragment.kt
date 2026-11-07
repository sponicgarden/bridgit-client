package cc.bridgit.app.lancamera

import android.os.Bundle
import android.text.InputType
import android.view.TextureView
import android.view.WindowManager
import android.widget.*
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.withContext
import cc.bridgit.app.portal.PortalLinks
import java.util.UUID

/** Owner-only enrolment: agents can use configured cameras, never read or change their passwords. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LanCamerasFragment : Fragment(), cc.bridgit.app.lan.NetworkDevicesFragment.Host {
    private val app get() = requireActivity().application as BridgeApp
    private lateinit var content: LinearLayout
    private var player: ExoPlayer? = null
    private var liveDialog: androidx.appcompat.app.AlertDialog? = null
    private var editor: androidx.appcompat.app.AlertDialog? = null
    private var scene: DevicesScene? = null
    private var listView: View? = null
    private var tabs: TabLayout? = null
    /** Switched to a hub or portal link (owner, 5 Nov 2026): the Devices screen shows that phone's devices, read only. */
    private var remoteKey: String? = null
    private var remoteAt = 0L
    private var remoteList: ScrollView? = null
    private var remotePayload = org.json.JSONObject()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(8)) }
        // The List view is the network device list, with the cameras added to Bridgit at its top.
        val list = FrameLayout(requireContext()).apply { id = R.id.devices_list_container }
        listView = list
        // Owner, 4 Nov 2026: two subtabs, Map (Home's scene style) and List (every device, added ones first).
        val root = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        // Owner, 4 Nov 2026: no Help link;
        // Map and List are a segmented switch on the top line, the selected half filled.
        fun c(res: Int) = androidx.core.content.ContextCompat.getColor(requireContext(), res)
        val tabBar = TabLayout(requireContext()).apply {
            addTab(newTab().setText(R.string.devices_tab_map))
            addTab(newTab().setText(R.string.devices_tab_list))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setSelectedTabIndicator(android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(c(R.color.b_primary)) })
            setSelectedTabIndicatorColor(c(R.color.b_primary))
            setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_STRETCH)
            isTabIndicatorFullWidth = true
            tabRippleColor = null
            setTabTextColors(c(R.color.b_on_surface_variant), c(R.color.b_on_primary))
        }
        tabs = tabBar
        // The track is a wrapper, so the selected pill keeps both rounded ends inside it.
        val track = FrameLayout(requireContext()).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(c(R.color.b_surface_container_high)) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(tabBar, FrameLayout.LayoutParams(-1, -1))
        }
        // Owner, 5 Nov 2026: no longer a tab; opened from More as "Network device monitor", with a back arrow.
        root.addView(com.google.android.material.appbar.MaterialToolbar(requireContext()).apply {
            setTitle(R.string.nav_cameras)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationContentDescription(R.string.cd_back)
            setNavigationOnClickListener { androidx.navigation.fragment.NavHostFragment.findNavController(this@LanCamerasFragment).popBackStack() }
        }, LinearLayout.LayoutParams(-1, -2))
        val top = LinearLayout(requireContext()).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(2), dp(16), dp(2)) }
        top.addView(track, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(top, LinearLayout.LayoutParams(-1, -2))
        val frame = FrameLayout(requireContext())
        val s = DevicesScene(requireContext(), app, ::openFromMap)
        frame.addView(s, FrameLayout.LayoutParams(-1, -1))
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        val rl = ScrollView(requireContext()).apply { visibility = View.GONE; setBackgroundColor(c(R.color.b_surface)) }
        remoteList = rl
        frame.addView(rl, FrameLayout.LayoutParams(-1, -1))
        scene = s
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, s.backCallback)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom); insets
        }
        render()
        val discovered = arguments?.getString("discoveredHost")
        val start = if (discovered != null) TAB_LIST else prefs().getInt(KEY_TAB, TAB_MAP).coerceIn(TAB_MAP, TAB_LIST)
        tabBar.getTabAt(start)?.select()
        showTab(start)
        // The opening animation plays the first time the map is shown in each app run; later visits open on the finished map.
        s.load(play = start == TAB_MAP && !mapPlayedThisRun)
        if (start == TAB_MAP) mapPlayedThisRun = true
        tabBar.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                prefs().edit().putInt(KEY_TAB, t.position).apply()
                showTab(t.position)
                if (t.position == TAB_MAP && scene?.scanning != true) {
                    if (!mapPlayedThisRun) { mapPlayedThisRun = true; scene?.play() } else scene?.refresh()
                }
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            // Tapping Map again plays the opening animation again.
            override fun onTabReselected(t: TabLayout.Tab) { if (t.position == TAB_MAP) scene?.play() }
        })
        discovered?.let { host ->
            arguments?.remove("discoveredHost")
            if (cc.bridgit.app.lan.LanRules.ipv4(host) != null) root.post { if (isAdded) edit(null, host) }
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (childFragmentManager.findFragmentByTag(LIST_TAG) == null)
            childFragmentManager.beginTransaction().add(R.id.devices_list_container, cc.bridgit.app.lan.NetworkDevicesFragment(), LIST_TAG).commitNow()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.log.latestId.collectLatest { delay(150); scene?.pollLog() } }
                // Find devices updates what this network has seen; pick it up when coming back.
                launch { while (true) { if (tabs?.selectedTabPosition == TAB_MAP) scene?.refresh(); delay(10_000) } }
                // Switched to another phone: its devices, read through its hub link or portal link, at most twice a minute.
                launch { PortalLinks(app).selected.collect { syncRemote(force = true) } }
                launch { while (true) { delay(30_000); syncRemote(force = false) } }
            }
        }
    }

    private fun prefs() = requireContext().getSharedPreferences("devices_tab", android.content.Context.MODE_PRIVATE)

    private fun showTab(position: Int) {
        val remote = scene?.isRemote == true
        scene?.visibility = if (position == TAB_MAP) View.VISIBLE else View.INVISIBLE
        listView?.visibility = if (position == TAB_LIST && !remote) View.VISIBLE else View.GONE
        remoteList?.visibility = if (position == TAB_LIST && remote) View.VISIBLE else View.GONE
    }

    private fun syncRemote(force: Boolean) {
        if (!isAdded) return
        val links = PortalLinks(app)
        val hub = links.selectedHub()
        val link = if (hub == null) links.selectedLink() else null
        val key = hub?.let { PortalLinks.HUB + it.linkId } ?: link?.device
        if (key == null) {
            if (remoteKey != null) { remoteKey = null; scene?.setRemote(null); showTab(tabs?.selectedTabPosition ?: TAB_MAP) }
            return
        }
        val now = System.currentTimeMillis()
        if (!force && key == remoteKey && now - remoteAt < 25_000) return
        remoteAt = now
        val switched = key != remoteKey
        remoteKey = key
        val name = hub?.name ?: link!!.name
        // Show the phone's name at once; its devices land when they arrive.
        if (switched) { remotePayload = org.json.JSONObject(); scene?.setRemote(DevicesScene.Remote(name, org.json.JSONObject())); renderRemoteList(name, org.json.JSONObject(), null); showTab(tabs?.selectedTabPosition ?: TAB_MAP) }
        viewLifecycleOwner.lifecycleScope.launch {
            val res = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    if (hub != null) app.hubLinks.portalGet(hub, "/v1/portal/devices")
                    else cc.bridgit.app.portal.PortalClient.get(link!!, "/v1/portal/devices")
                }
            }
            if (!isAdded || remoteKey != key) return@launch
            res.onSuccess { remotePayload = it; scene?.setRemote(DevicesScene.Remote(name, it)); renderRemoteList(name, it, null) }
                .onFailure { renderRemoteList(name, remotePayload, it.message ?: it.javaClass.simpleName) }
            showTab(tabs?.selectedTabPosition ?: TAB_MAP)
        }
    }

    /** The List tab while another phone is shown: its devices by group, read only. */
    private fun renderRemoteList(name: String, payload: org.json.JSONObject, error: String?) {
        val rl = remoteList ?: return
        val col = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        fun row(text: String, size: Float, bold: Boolean = false, top: Int = 0) = col.addView(TextView(requireContext()).apply {
            this.text = text; textSize = size; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.b_on_surface)); setPadding(0, dp(top), 0, dp(2)) })
        row(getString(R.string.devices_remote_title, name), 15f, true)
        error?.let { row(getString(R.string.devices_remote_error, it), 13f) }
        for ((title, lines) in DevicesScene.remoteListRows(payload)) {
            row(title, 14f, true, top = 12)
            lines.forEach { row(it, 14f) }
        }
        rl.removeAllViews(); rl.addView(col)
    }

    /** A tap on the map: a camera opens its live view, a seen camera opens Add camera, anything else the device finder. */
    private fun openFromMap(id: String) {
        if (!isAdded) return
        if (scene?.isRemote == true) {
            // Another phone's devices are read only here; its scan and cameras are on that phone.
            if (id == "find") android.widget.Toast.makeText(requireContext(), R.string.devices_remote_find, android.widget.Toast.LENGTH_LONG).show()
            else tabs?.getTabAt(TAB_LIST)?.select()
            return
        }
        when {
            id == "list" -> tabs?.getTabAt(TAB_LIST)?.select()
            id == "find" -> networkList()?.requestScan()
            id.startsWith("lan:") -> runCatching { app.lanCameras.all() }.getOrNull()?.firstOrNull { it.id == id }?.let { live(it) }
            id.startsWith("seen:") -> {
                val host = id.removePrefix("seen:")
                val camera = runCatching {
                    cc.bridgit.app.lan.DiscoveryHistory(requireContext())
                        .read(cc.bridgit.app.lan.HostLan(requireContext()).historyScope(requireContext()))
                        .firstOrNull { it.host == host }?.cameraCandidate == true
                }.getOrDefault(false)
                if (camera && cc.bridgit.app.lan.LanRules.ipv4(host) != null) edit(null, host)
                else tabs?.getTabAt(TAB_LIST)?.select()
            }
            else -> tabs?.getTabAt(TAB_LIST)?.select()
        }
    }

    private fun text(s: String) = TextView(requireContext()).apply { text = s; textSize = 17f; setPadding(0, 16, 0, 12) }.also { content.addView(it) }
    private fun button(s: String, action: () -> Unit) = com.google.android.material.button.MaterialButton(requireContext()).apply { text = s; setOnClickListener { action() } }.also { content.addView(it) }
    private fun render() {
        content.removeAllViews()
        val cameras = runCatching {
            val existing = app.lanCameras.all()
            app.appDevices // Migrate existing memberships before enrolling any new device.
            existing
        }.getOrElse { text(getString(R.string.lan_store_error)); emptyList() }
        // Bridgit Satellites (docs/spec-satellites.md): other Bridgit phones linked to this one.
        content.addView(LinearLayout(requireContext()).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(4), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.b_primary_container)) }
            addView(TextView(requireContext()).apply {
                text = getString(R.string.satellite_devices_card, runCatching { app.satellites.count() }.getOrDefault(0)); textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(com.google.android.material.button.MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                setText(R.string.satellite_devices_open)
                setOnClickListener { startActivity(android.content.Intent(requireContext(), cc.bridgit.app.portal.PortalActivity::class.java)) } })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        // Added cameras: one compact card each, actions in a row (owner, 4 Nov 2026).
        if (cameras.isNotEmpty()) content.addView(TextView(requireContext()).apply {
            setText(R.string.devices_added); textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(6), 0, dp(6))
        })
        for (camera in cameras) {
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(6), dp(4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.b_primary_container))
                }
            }
            card.addView(TextView(requireContext()).apply {
                text = camera.name; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            val report = camera.capabilityReport
            val apps = cc.bridgit.app.appdevices.AppDeviceAccess.apps.filter { app.appDevices.allows(it.id, camera.id) }
            card.addView(TextView(requireContext()).apply {
                textSize = 12f
                text = listOf(camera.host,
                    if (apps.isEmpty()) getString(R.string.device_no_apps) else getString(R.string.device_in_apps, apps.joinToString(", ") { getString(it.title) }),
                    getString(R.string.lan_capability_summary,
                        report?.optJSONObject("audio_in")?.optString("state") ?: "not_checked",
                        report?.optJSONObject("audio_out")?.optString("state") ?: "not_checked",
                        report?.optJSONObject("tapo_talk")?.optString("state") ?: "not_checked")).joinToString(" · ")
            })
            val actions = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            fun action(label: String, run: () -> Unit) = actions.addView(com.google.android.material.button.MaterialButton(requireContext(), null,
                com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = label; textSize = 13f; minWidth = 0; minimumWidth = 0; setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { run() }
            }, LinearLayout.LayoutParams(-2, -2))
            action(getString(R.string.lan_live)) { live(camera) }
            action(getString(R.string.device_add_to_apps)) {
                cc.bridgit.app.appdevices.DeviceAccessDialogs.forDevice(requireContext(), app, camera.id, camera.name) { render() }
            }
            action(getString(R.string.lan_edit)) { edit(camera) }
            action(getString(R.string.lan_remove)) {
                MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.lan_remove_confirm).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.lan_remove) { _, _ ->
                        if (camera.id in app.dog.state.value.cameras || (app.dog.state.value.running && app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, camera.id)))
                            app.dog.stop(getString(R.string.app_device_removed), "device_removed")
                        app.appDevices.remove(camera.id)
                        app.lanCameras.remove(camera.id); render()
                    }.show()
            }
            card.addView(android.widget.HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        scene?.refresh()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun edit(old: LanCamera?, discoveredHost: String = "") {
        val fields = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(8)) }
        fun field(label: Int, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            val outline = com.google.android.material.textfield.TextInputLayout(requireContext()).apply {
                hint = getString(label)
                boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
            }
            val input = com.google.android.material.textfield.TextInputEditText(outline.context).apply {
                inputType = type; setText(value); isSaveEnabled = false; setSingleLine()
                if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
                    transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
            outline.addView(input)
            fields.addView(outline, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            return input
        }
        fields.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
            setText(R.string.lan_setup_help)
            setOnClickListener {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.lan_setup_help)
                    .setMessage(R.string.lan_camera_setup_help)
                    .setNeutralButton(R.string.lan_tapo_setup) { _, _ ->
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.lan_tapo_setup)
                            .setMessage(R.string.lan_tapo_help).setPositiveButton(android.R.string.ok, null).show()
                    }.setPositiveButton(android.R.string.ok, null).show()
            }
        })
        val name = field(R.string.lan_name, old?.name.orEmpty())
        val host = field(R.string.lan_host, old?.host ?: discoveredHost)
        val user = field(R.string.lan_user, old?.username.orEmpty())
        // Existing password stays encrypted until Test and save; no pre-filled secret in the view tree.
        val talkPass = field(if(old==null)R.string.lan_cloud_password else R.string.lan_cloud_password_keep,
            type=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val forgetTalk=android.widget.CheckBox(requireContext()).apply {
            setText(R.string.lan_cloud_forget);visibility=if(old?.talkPassword?.isNotEmpty()==true)View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, checked -> if(checked)talkPass.text.clear();talkPass.isEnabled=!checked }
        }
        fields.addView(forgetTalk)
        cc.bridgit.app.ui.common.SecretViews.hideSecret(talkPass) { talkPass.text.toString() }
        val pass = field(if (old == null) R.string.lan_password else R.string.lan_password_keep,
            type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        cc.bridgit.app.ui.common.SecretViews.hideSecret(pass) { pass.text.toString() }
        val result = TextView(requireContext()); fields.addView(result)
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            setBackgroundResource(R.drawable.design_bg_info)
        }
        val brand = LinearLayout(requireContext()).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(requireContext()).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        brand.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.brand_wordmark)
            contentDescription = getString(R.string.app_name)
        }, LinearLayout.LayoutParams(dp(112), dp(36)).apply { marginStart = dp(12) })
        header.addView(brand)
        header.addView(TextView(requireContext()).apply {
            setText(if (old == null) R.string.lan_add else R.string.lan_edit)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        val dialog = MaterialAlertDialogBuilder(requireContext()).setCustomTitle(header)
            .setView(ScrollView(requireContext()).apply { addView(fields) }).setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.lan_test_save, null).create()
        cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(dialog.window)
        var testJob: kotlinx.coroutines.Job? = null
        dialog.setOnDismissListener { testJob?.cancel(); pass.text.clear(); talkPass.text.clear(); editor = null }
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            // Debug visual QA captures only the untouched blank form, never enrolled credentials.
            if (cc.bridgit.app.BuildConfig.DEBUG && old == null) dialog.window?.decorView?.post {
                if (listOf(name, host, user, pass, talkPass).all { it.text.isNullOrEmpty() }) {
                    val root = dialog.window?.decorView
                    if (root != null && root.width > 0 && root.height > 0) {
                        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                        root.draw(android.graphics.Canvas(bitmap))
                        java.io.File(requireContext().cacheDir, "lan-camera-empty-form.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
            dialog.getButton(-1).setOnClickListener {
                val camera = try { LanCamera(old?.id ?: "lan:${UUID.randomUUID()}", name.text.toString().trim(), host.text.toString().trim(), user.text.toString(), pass.text.toString().ifEmpty { old?.password.orEmpty() }, talkPassword=if(forgetTalk.isChecked)"" else talkPass.text.toString().ifEmpty { old?.talkPassword.orEmpty() }) }
                    catch (e: IllegalArgumentException) { result.setText(R.string.lan_invalid); return@setOnClickListener }
                dialog.getButton(-1).isEnabled = false; result.setText(R.string.lan_testing)
                testJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val info = withContext(Dispatchers.IO) { Onvif(camera, requireContext()).info() }
                        RtspFrame.snapshot(requireContext(), camera)
                        val ctx=kotlinx.coroutines.currentCoroutineContext()
                        val report=withContext(Dispatchers.IO){ CameraProbe.inspect(requireContext(),camera,info){
                            ctx.ensureActive()
                            if(old!=null){val current=app.lanCameras.all().firstOrNull{it.id==old.id}
                                if(current==null || current.host!=old.host || current.username!=old.username || current.password!=old.password || current.talkPassword!=old.talkPassword)
                                    throw CameraFailure("Camera enrollment or credentials changed. Reopen the editor before testing again.")}
                        } }
                        if(camera.talkPassword.isNotEmpty() && report.getJSONObject("tapo_talk").optString("state")!="authenticated")throw CameraFailure("Tapo talk authentication failed. Check the separate cloud password, or leave it empty for video/microphone enrollment.")
                        app.lanCameras.save(camera.verified(info).assessed(report)); pass.text.clear();talkPass.text.clear(); dialog.dismiss(); render()
                        Toast.makeText(requireContext(), getString(R.string.lan_test_ok, info.model), Toast.LENGTH_LONG).show()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { result.text = (e as? CameraFailure)?.message ?: getString(R.string.lan_test_failed) }
                    finally { dialog.getButton(-1)?.isEnabled = true }
                }
            }
        }
        editor = dialog
        dialog.show()
    }
    private fun live(camera: LanCamera) {
        val panel = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val video = TextureView(requireContext())
        panel.addView(video, LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels * 9 / 16)))
        val status = TextView(requireContext()).apply { text = getString(R.string.lan_connecting); setPadding(24, 8, 24, 8) }; panel.addView(status)
        val controls = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }; panel.addView(controls)
        var info: Onvif.Info? = null
        for ((label, pan, tilt) in listOf(Triple("←", -.03, 0.0), Triple("↑", 0.0, .03), Triple("↓", 0.0, -.03), Triple("→", .03, 0.0), Triple("■", 0.0, 0.0))) {
            val btn = com.google.android.material.button.MaterialButton(requireContext()).apply { text = label; isEnabled = false
                contentDescription = getString(when(label) { "←" -> R.string.lan_left; "→" -> R.string.lan_right; "↑" -> R.string.lan_up; "↓" -> R.string.lan_down; else -> R.string.lan_stop }) }
            controls.addView(btn, LinearLayout.LayoutParams(0, -2, 1f))
            btn.setOnClickListener {
                val profile = info?.profile ?: return@setOnClickListener
                viewLifecycleOwner.lifecycleScope.launch {
                    if (label == "■") app.cameraFraming.cancelCamera(camera.id)
                    try { withContext(Dispatchers.IO) { if (label == "■") Onvif(camera, requireContext()).stop(profile) else Onvif(camera, requireContext()).move(profile, pan, tilt) } }
                    catch (e: Exception) { status.setText(R.string.lan_move_failed) }
                }
            }
        }
        player?.release()
        val livePlayer = try { RtspFrame.player(requireContext(), camera) }
            catch (e: CameraFailure) { Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show(); return }
        player = livePlayer.apply {
            setVideoTextureView(video)
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onRenderedFirstFrame() { status.setText(R.string.lan_live_video) }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { status.setText(R.string.lan_video_failed) }
            })
            prepare(); playWhenReady = true
        }
        liveDialog = MaterialAlertDialogBuilder(requireContext()).setTitle(camera.name).setView(panel).setPositiveButton(android.R.string.ok, null).create().apply {
            cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(window)
            setOnDismissListener { player?.release(); player = null; liveDialog = null }
            show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            info = withContext(Dispatchers.IO) { runCatching { Onvif(camera, requireContext()).info() }.getOrNull() }
            for (i in 0 until controls.childCount) controls.getChildAt(i).isEnabled = info?.ptz == true
        }
    }
    private fun networkList() = childFragmentManager.findFragmentByTag(LIST_TAG) as? cc.bridgit.app.lan.NetworkDevicesFragment

    // ---- NetworkDevicesFragment.Host: the List view's scan plays on the Map ----
    override fun listHeader(): View = content
    /** Cameras the scan doesn't find can still be added by address; a quiet link at the end of the list. */
    override fun listFooter(): View = com.google.android.material.button.MaterialButton(requireContext(), null,
        com.google.android.material.R.attr.borderlessButtonStyle).apply {
        setText(R.string.devices_add_by_address); textSize = 13f
        setOnClickListener { edit(null) }
    }
    override fun onScanStarted() {
        mapPlayedThisRun = true
        scene?.scanStarted() // first, so the tab switch below doesn't replay the opening
        tabs?.getTabAt(TAB_MAP)?.select()
    }
    override fun onScanProgress(found: List<cc.bridgit.app.lan.NetworkDevices.Device>) { scene?.scanProgress(found) }
    override fun onScanFinished() { scene?.scanFinished() }
    override fun setUpCamera(host: String) { if (cc.bridgit.app.lan.LanRules.ipv4(host) != null) edit(null, host) }

    override fun onDestroyView() {
        editor?.dismiss(); liveDialog?.dismiss()
        scene?.release(); scene = null; listView = null; tabs = null
        super.onDestroyView()
    }
    override fun onStop() { liveDialog?.dismiss(); player?.release(); player = null; super.onStop() }

    companion object {
        private const val TAB_MAP = 0
        private const val TAB_LIST = 1
        private const val KEY_TAB = "tab"
        private const val LIST_TAG = "network_list"
        /** The map's opening animation plays once per app run, like Home's diagnostics. */
        private var mapPlayedThisRun = false
    }
}
