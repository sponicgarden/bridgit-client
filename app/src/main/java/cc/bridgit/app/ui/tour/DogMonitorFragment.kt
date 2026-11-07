package cc.bridgit.app.ui.tour

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FragmentDogMonitorBinding
import cc.bridgit.app.dog.DogCamera
import cc.bridgit.app.dog.DogJev
import cc.bridgit.app.dog.DogStatus
import cc.bridgit.app.dog.DogWords
import cc.bridgit.app.ui.common.Fmt
import kotlin.math.roundToInt

/**
 * Pet monitor applet (owner, 2 Nov 2026): Start/Stop, the latest photo and what Jev saw in it, today's
 * sleep, meals and drinks, and the recent readings. Follows app.dog.state. Keeps the screen on while
 * watching, since the camera needs the phone unlocked.
 */
class DogMonitorFragment : Fragment() {
    private var _b: FragmentDogMonitorBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private var shownFrameAt = -1L
    private var updatingCameraChoices = false
    private var cameraChoicesKey = ""
    private var microphoneChoicesKey = ""
    private val askMicrophone = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if(granted){app.settings.setOn("mic",true);app.dog.setMicrophones((app.dog.state.value.microphones+cc.bridgit.app.dog.DogAudio.BUILTIN).distinct());cc.bridgit.app.BridgeService.start(requireContext())}
        if(_b!=null)render(app.dog.state.value)
    }

    private val askCamera = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startWatching()
        else com.google.android.material.snackbar.Snackbar.make(b.root, R.string.dog_need_camera, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
    }

    /** Start: camera permission first, then Bridgit's service with the camera (a visible start), then the monitor. */
    private fun startWatching() {
        val ctx = requireContext()
        if (!app.remoteView.dogBackend) { chooseBackend(); return }
        if (DogCamera.requiresScreen(app.dog.state.value.cameraMode, app.dog.state.value.cameras) && app.dog.fakePhotos == null && ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askCamera.launch(android.Manifest.permission.CAMERA); return
        }
        // The camera lives in Bridgit's service, which runs only while Bridgit is on. With no agents yet nobody
        // can reach it, so turn it on (as Add agent does); a paused Bridgit is the owner's choice: ask.
        if (!app.settings.listenerEnabled) {
            if (app.agents.count == 0) app.settings.listenerEnabled = true
            else {
                com.google.android.material.snackbar.Snackbar.make(b.root, R.string.dog_need_listener, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction(R.string.dog_turn_on) { app.settings.listenerEnabled = true; startWatching() }.show()
                return
            }
        }
        val l = app.listener.value
        if (!l.running || l.startedInBackground || !l.cameraReady) runCatching { cc.bridgit.app.BridgeService.start(ctx) }
        app.dog.start()
        // Barking detection is on by default: the first Start ticks the built-in microphone (the owner can untick it).
        if (app.dog.takeMicrophoneOffer()) {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                app.settings.setOn("mic", true)
                app.dog.setMicrophones((app.dog.state.value.microphones + cc.bridgit.app.dog.DogAudio.BUILTIN).distinct())
            } else askMicrophone.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentDogMonitorBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        val pad = b.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.scroll) { v, insets ->
            v.updatePadding(bottom = pad + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        b.deviceAccess.setOnClickListener {
            cc.bridgit.app.appdevices.DeviceAccessDialogs.forApp(requireContext(), app, cc.bridgit.app.appdevices.AppDeviceAccess.DOG) { render(app.dog.state.value) }
        }
        b.personalBackend.setOnClickListener {
            if (app.remoteView.dogPendingDelete) viewLifecycleOwner.lifecycleScope.launch { app.remoteView.refresh(); render(app.dog.state.value) }
            else chooseBackend()
        }
        if (!app.remoteView.dogBackendChosen && savedInstanceState == null) view.post { if (_b != null) chooseBackend() }
        b.startStop.setOnClickListener { if (app.dog.state.value.running) app.dog.stop() else startWatching() }
        listOf(b.cameraAuto to DogCamera.AUTO,b.cameraRear to DogCamera.REAR,b.cameraFront to DogCamera.FRONT).forEach { (box,id) ->
            box.setOnCheckedChangeListener { _, checked -> if (!updatingCameraChoices) toggleCamera(id,checked) }
        }
        b.localCameras.setOnClickListener { cc.bridgit.app.appdevices.DeviceAccessDialogs.forApp(requireContext(),app,cc.bridgit.app.appdevices.AppDeviceAccess.DOG) { render(app.dog.state.value) } }
        b.feedingSetup.setOnClickListener { chooseFeedingView() }
        b.findCamera.setOnClickListener { app.dog.findBestCamera() }
        b.panSearch.setOnCheckedChangeListener { _, on -> if(on!=app.dog.state.value.panSearchEnabled)app.dog.setPanSearch(on) }
        b.cameraSearchDelays.setOnClickListener { chooseSearchDelay() }
        b.useClef.isChecked = "clef" in app.dog.state.value.models
        b.useJev.isChecked = "jev" in app.dog.state.value.models
        b.useClef.setOnCheckedChangeListener { _, on -> app.dog.setModelEnabled("clef", on) }
        b.useJev.setOnCheckedChangeListener { _, on -> app.dog.setModelEnabled("jev", on) }
        b.webPhoto.isChecked = app.remoteView.dogPhoto
        b.webPhoto.setOnCheckedChangeListener { _, on -> app.remoteView.setDogPhoto(on) }
        b.clear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.dog_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dog_clear) { _, _ -> app.dog.clearHistory() }.show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.dog.state.collect { render(it) } }
                launch { app.remoteView.backendStatus.collect { render(app.dog.state.value) } }
                launch { app.remoteView.linked.collect { email ->
                    _b?.webPhotoDesc?.text = if (email != null && app.remoteView.dogBackend) getString(R.string.dog_web_linked, email) else getString(R.string.dog_web_not_linked)
                } }
                // "now", "1 min ago" on the latest reading move on between photos.
                launch { while (true) { delay(30_000); render(app.dog.state.value) } }
            }
        }
    }

    private fun chooseBackend() {
        val on = app.remoteView.dogBackend
        if (on && app.remoteView.linked.value == null) {
            startActivity(android.content.Intent(requireContext(), cc.bridgit.app.remoteview.LinkWebViewActivity::class.java).putExtra("applet_only", true))
            return
        }
        if (on) {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.link_web_view_delete_title)
                .setMessage(R.string.link_web_view_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.link_web_view_device_only) { _, _ ->
                    app.remoteView.setDogBackend(false); render(app.dog.state.value)
                }.show()
            return
        }
        val choice = cc.bridgit.app.databinding.DialogAppletPortalBinding.inflate(layoutInflater)
        choice.device.text = app.remoteView.deviceLabel()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(choice.root).setBackground(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)).create()
        choice.deviceOnly.setOnClickListener {
            app.remoteView.setDogBackend(false); render(app.dog.state.value); dialog.dismiss()
        }
        choice.webPortal.setOnClickListener {
            app.remoteView.setDogBackend(true); render(app.dog.state.value); dialog.dismiss()
            if (app.remoteView.dogBackend) startActivity(android.content.Intent(requireContext(), cc.bridgit.app.remoteview.LinkWebViewActivity::class.java).putExtra("applet_only", true))
        }
        dialog.show()
    }

    private fun chooseSearchDelay() {
        val cameras = listOf(DogCamera.REAR, DogCamera.FRONT) + allowedCameras().map { it.id }
        val rows = listOf(getString(R.string.dog_search_delay_row, getString(R.string.dog_sampling_all), app.dog.samplingAllSeconds())) +
            cameras.map { getString(R.string.dog_search_delay_row, camName(it), app.dog.cameraSearchDelaySeconds(it)) }
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.dog_search_delays)
            .setItems(rows.toTypedArray()) { _, index ->
                editSearchDelay(if (index == 0) null else cameras[index - 1])
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** [camera] null: the value all cameras and microphones follow. */
    private fun editSearchDelay(camera: String?) {
        val context = requireContext()
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        content.addView(android.widget.TextView(context).apply { setText(if (camera == null) R.string.dog_sampling_all_note else R.string.dog_search_delay_note) })
        val input = com.google.android.material.textfield.TextInputEditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((if (camera == null) app.dog.samplingAllSeconds() else app.dog.cameraSearchDelaySeconds(camera)).toString())
            hint = getString(R.string.dog_search_delay_range)
            selectAll()
        }
        content.addView(input)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.dog_search_delay_title, if (camera == null) getString(R.string.dog_sampling_all) else camName(camera))).setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(if (camera == null) R.string.dog_sampling_all_default else R.string.dog_search_delay_default) { _, _ -> if (camera == null) app.dog.setSamplingAll(null) else app.dog.setCameraSearchDelay(camera, null); render(app.dog.state.value) }
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = input.text.toString().toIntOrNull()
                if (!cc.bridgit.app.appdevices.SensorSearchDelays.validSeconds(seconds)) input.error = getString(R.string.dog_search_delay_range)
                else { if (camera == null) app.dog.setSamplingAll(seconds) else app.dog.setCameraSearchDelay(camera, seconds); render(app.dog.state.value); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun allowedCameras() = app.lanCameras.all().filter { app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG, it.id) }

    private fun render(s: DogStatus) {
        val b = _b ?: return
        val ctx = requireContext()
        b.personalBackend.text = if (app.remoteView.dogPendingDelete) "Retry server deletion" else if (app.remoteView.dogBackend) "Device + Viewing Portal" else "Device only · this applet"
        b.backendStatus.text = app.remoteView.backendStatus.value.ifEmpty { if (app.remoteView.dogBackend) "Personal backend selected. Link your Google account to upload." else "Nothing from this applet is sent to the server. Remote AI is unavailable in device-only mode." }
        b.webPhoto.isEnabled = app.remoteView.dogBackend
        if (s.running && DogCamera.requiresScreen(s.cameraMode,s.cameras)) requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b.status.text = if (s.running) getString(R.string.dog_status_running, s.asked, s.unchanged) +
            (if (s.missed > 0) getString(R.string.dog_status_missed, s.missed) else "") else getString(R.string.dog_status_stopped)
        b.startStop.text = getString(if (s.running) R.string.dog_stop else R.string.dog_start)
        b.startStop.isEnabled = s.running || s.models.isNotEmpty()
        b.useClef.isChecked = "clef" in s.models
        b.useJev.isChecked = "jev" in s.models
        renderCameraChoices(s)
        renderMicrophones(s)
        b.how.setText(if (!DogCamera.requiresScreen(s.cameraMode,s.cameras)) R.string.lan_dog_how else R.string.dog_how)
        b.cameraWhy.text = cameraWhy(s)
        b.networkCameraHint.isVisible = allowedCameras().isEmpty()
        b.localCameras.isVisible = app.lanCameras.all().isNotEmpty()
        b.panSearch.isChecked = s.panSearchEnabled
        b.panSearchStatus.text = s.searchStatus
        b.findCamera.isEnabled = s.running && !s.scanning
        b.error.isVisible = s.error != null
        b.error.text = s.error

        if (s.frame == null) {
            b.photo.setImageDrawable(null)
            b.photo.isVisible = false
            shownFrameAt = -1L
        } else if (s.frameAt != shownFrameAt) {
            BitmapFactory.decodeByteArray(s.frame, 0, s.frame.size)?.let { b.photo.setImageBitmap(it); b.photo.isVisible = true }
            shownFrameAt = s.frameAt
        }
        b.nowTitle.text = if (s.frame != null && s.frameAt > 0) getString(R.string.dog_now_at, Fmt.time(s.frameAt), Fmt.ago(s.frameAt)) else getString(R.string.dog_now)
        val t = app.dog.tracker
        val r = t.latest()
        b.now.text = DogWords.line(ctx, r)
        val verdicts = s.verdicts.ifEmpty { listOfNotNull(r) }
        b.nowDetail.text = verdicts.joinToString("\n") {
            listOf(DogWords.model(it.model), DogWords.line(ctx, it),
                getString(R.string.dog_sure, DogWords.model(it.model), (it.dogP * 100).roundToInt()),
                camName(it.camera), Fmt.agoShort(it.at)).joinToString(" · ")
        }
        b.nowDetail.isVisible = r != null

        val now = System.currentTimeMillis()
        val day = t.day(now)
        b.sleep.text = if (day.naps == 1) getString(R.string.dog_sleep_value_one, DogWords.duration(day.sleepMs))
            else getString(R.string.dog_sleep_value, DogWords.duration(day.sleepMs), day.naps)
        b.asleepNow.isVisible = day.asleepSince != null
        b.asleepNow.text = getString(R.string.dog_asleep_now, DogWords.duration(day.asleepForMs))
        b.meals.text = day.lastMeal?.let { getString(R.string.dog_count_duration, day.meals, DogWords.duration(day.eatingMs), Fmt.time(it)) } ?: getString(R.string.dog_count_none)
        b.drinks.text = day.lastDrink?.let { getString(R.string.dog_count_duration, day.drinks, DogWords.duration(day.drinkingMs), Fmt.time(it)) } ?: getString(R.string.dog_count_none)
        val episodes = t.episodesToday(now).sortedByDescending { it.lastSeen }.take(20)
        b.activitySessions.text = if (episodes.isEmpty()) getString(R.string.dog_sessions_none) else episodes.joinToString("\n") { e ->
            val duration = if (e.frames == 1) getString(R.string.dog_session_brief)
                else getString(R.string.dog_duration_approx, DogWords.duration(e.durationMs(t.frameMs)))
            getString(R.string.dog_session_line, Fmt.time(e.start), DogWords.activity(ctx, e.kind), duration)
        }
        b.tallyTotal.text = getString(R.string.dog_tally_line, day.photos, DogWords.duration(day.watchedMs))
        val order = DogJev.ACTIVITIES + listOf("unsure", "no_dog", "dark")
        b.tally.text = day.views.entries.sortedBy { order.indexOf(it.key) }.joinToString("\n") { (k, n) ->
            val label = when (k) { "no_dog" -> getString(R.string.dog_tally_no_dog); "dark" -> getString(R.string.dog_too_dark); "unsure" -> getString(R.string.dog_tally_unsure); else -> DogWords.activity(ctx, k) }
            "$label: $n"
        }
        b.tally.isVisible = day.views.isNotEmpty()
        fun median(g: cc.bridgit.app.dog.Gaps?) = g?.let { resources.getQuantityString(R.plurals.dog_median_value, it.count, DogWords.duration(it.medianMs), it.count) } ?: getString(R.string.dog_median_none)
        b.medianDrinks.text = median(day.medianDrinkGap)
        b.medianMeals.text = median(day.medianMealGap)
        val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        b.recent.text = t.recent().takeLast(15).reversed().joinToString("\n") { "${clock.format(java.util.Date(it.at))}  ${DogWords.line(ctx, it)} · ${shortName(it.camera)}${if (it.reused) " (" + getString(R.string.dog_same_picture) + ")" else ""}" }
    }

    private fun toggleCamera(id:String, checked:Boolean) {
        if(checked && id.startsWith("lan:"))app.settings.setOn("lan_camera",true)
        val s=app.dog.state.value
        val (mode,cameras)=DogCamera.toggle(s.cameraMode,s.cameras,id,checked)
        if(mode==DogCamera.AUTO) app.dog.setCameraMode(DogCamera.AUTO) else app.dog.setCameras(cameras)
        renderCameraChoices(app.dog.state.value)
    }
    private fun renderCameraChoices(s:DogStatus) {
        updatingCameraChoices=true
        try {
            val cameras=app.lanCameras.all()
            val key=cameras.joinToString { it.id+it.name+app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG,it.id) }
            if(key!=cameraChoicesKey || b.networkCameraChoices.childCount!=cameras.size) {
                cameraChoicesKey=key;b.networkCameraChoices.removeAllViews()
                cameras.forEach { c ->
                    val allowed=app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG,c.id)
                    val box=com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
                        text=c.name+if(allowed) "" else " — enable in Device access"
                        tag=c.id;isEnabled=allowed;minHeight=(48*resources.displayMetrics.density).toInt()
                        setOnCheckedChangeListener { _,checked -> if(!updatingCameraChoices)toggleCamera(c.id,checked) }
                    }
                    b.networkCameraChoices.addView(box)
                }
            }
            b.cameraAuto.isChecked=s.cameraMode==DogCamera.AUTO
            b.cameraRear.isChecked=s.cameraMode!=DogCamera.AUTO && DogCamera.REAR in s.cameras
            b.cameraFront.isChecked=s.cameraMode!=DogCamera.AUTO && DogCamera.FRONT in s.cameras
            for(i in 0 until b.networkCameraChoices.childCount) {
                val box=b.networkCameraChoices.getChildAt(i) as com.google.android.material.checkbox.MaterialCheckBox
                box.isChecked=s.cameraMode!=DogCamera.AUTO && box.tag in s.cameras
            }
        }finally{updatingCameraChoices=false}
    }
    private fun renderMicrophones(s:DogStatus) {
        updatingCameraChoices=true
        try {
            val sources=listOf(cc.bridgit.app.dog.DogAudio.BUILTIN to "Built-in microphone")+app.lanCameras.all().map { it.id to (it.name+" microphone") }
            val key=sources.joinToString { it.first+it.second+app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG,it.first) }
            if(key!=microphoneChoicesKey || b.microphoneChoices.childCount!=sources.size) {
                microphoneChoicesKey=key;b.microphoneChoices.removeAllViews()
                sources.forEach { (id,name) ->
                    val allowed=id==cc.bridgit.app.dog.DogAudio.BUILTIN || app.appDevices.allows(cc.bridgit.app.appdevices.AppDeviceAccess.DOG,id)
                    val box=com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
                        text=name+if(allowed) "" else " — enable in Device access";tag=id;isEnabled=allowed;minHeight=(48*resources.displayMetrics.density).toInt()
                        setOnCheckedChangeListener { _,checked ->
                            if(!updatingCameraChoices) {
                                if(checked && id==cc.bridgit.app.dog.DogAudio.BUILTIN && requireContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    askMicrophone.launch(android.Manifest.permission.RECORD_AUDIO);renderMicrophones(app.dog.state.value)
                                }else {
                                    if(checked){app.settings.setOn(if(id==cc.bridgit.app.dog.DogAudio.BUILTIN)"mic" else "lan_camera_audio",true);if(id!=cc.bridgit.app.dog.DogAudio.BUILTIN)app.settings.setOn("lan_camera",true);cc.bridgit.app.BridgeService.start(requireContext())}
                                    app.dog.setMicrophones((if(checked)app.dog.state.value.microphones+id else app.dog.state.value.microphones-id).distinct())
                                }
                            }
                        }
                    };b.microphoneChoices.addView(box)
                }
            }
            for(i in 0 until b.microphoneChoices.childCount){val box=b.microphoneChoices.getChildAt(i) as com.google.android.material.checkbox.MaterialCheckBox;box.isChecked=box.tag in s.microphones}
            b.microphoneStatus.text=s.microphoneStatus
            b.feedingStatus.text=app.dog.feeding.message
        }finally{updatingCameraChoices=false}
    }
    private fun chooseFeedingView() {
        val cameras=allowedCameras()
        val names=cameras.map { it.name }+getString(R.string.dog_feeding_off)
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.dog_feeding_setup).setItems(names.toTypedArray()) { _,which ->
            if(which==cameras.size) { app.dog.feeding.setEnabled(false);render(app.dog.state.value);return@setItems }
            val camera=cameras[which]
            MaterialAlertDialogBuilder(requireContext()).setTitle(camera.name).setMessage(R.string.dog_feeding_confirm)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.dog_feeding_save) { _,_ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        b.feedingSetup.isEnabled=false
                        try {
                            app.dog.feeding.save(camera.id)
                            app.settings.setOn("lan_camera_audio",true);app.settings.setOn("lan_camera",true)
                            app.dog.setMicrophones((app.dog.state.value.microphones+camera.id).distinct())
                            app.dog.feeding.setEnabled(true);cc.bridgit.app.BridgeService.start(requireContext())
                        } catch(e:Exception) {
                            if(e is kotlinx.coroutines.CancellationException)throw e
                            MaterialAlertDialogBuilder(requireContext()).setMessage(e.message ?: "Could not save this camera view.").setPositiveButton(android.R.string.ok,null).show()
                        } finally { if(_b!=null) { b.feedingSetup.isEnabled=true;render(app.dog.state.value) } }
                    }
                }.show()
        }.show()
    }
    private fun camName(c: String): String = if (c.startsWith("lan:")) allowedCameras().firstOrNull { it.id == c }?.name ?: getString(R.string.lan_camera_missing) else getString(if (c == DogCamera.FRONT) R.string.dog_cam_front else R.string.dog_cam_rear)
    private fun shortName(c: String): String = if (c.startsWith("lan:")) camName(c) else getString(if (c == DogCamera.FRONT) R.string.dog_cam_name_front else R.string.dog_cam_name_rear)

    /** Which camera is in use and why, in plain words. */
    private fun cameraWhy(s: DogStatus): String {
        if (s.cameraMode != DogCamera.AUTO) return "Selected cameras take turns: " + s.cameras.joinToString { camName(it) }
        if (s.scanning) return getString(R.string.dog_cam_scanning)
        val c = s.choice ?: return getString(R.string.dog_cam_auto_wait)
        val tested = getString(R.string.dog_cam_tested, Fmt.time(c.at)) + " " + c.scores.entries.joinToString(" · ") { (cam, p) ->
            when {
                p == null -> getString(R.string.dog_cam_score_failed, shortName(cam))
                p >= DogJev.DOG_MIN -> getString(R.string.dog_cam_score_dog, shortName(cam), (p * 100).roundToInt())
                else -> getString(R.string.dog_cam_score_none, shortName(cam), (p * 100).roundToInt())
            }
        } + "."
        return when (c.why) {
            DogCamera.Why.BEST -> getString(R.string.dog_cam_best, camName(c.primary), tested)
            DogCamera.Why.SHARED -> getString(R.string.dog_cam_shared, camName(c.cameras[0]), camName(c.cameras[1]), tested)
            DogCamera.Why.ONLY_ONE -> getString(R.string.dog_cam_only, camName(c.primary), tested)
            DogCamera.Why.NO_DOG -> getString(R.string.dog_cam_no_dog, camName(c.primary), tested, if (s.nextScanAt > 0) Fmt.time(s.nextScanAt) else "")
        }
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) render(app.dog.state.value)
    }

    override fun onDestroyView() {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroyView(); _b = null
    }
}
