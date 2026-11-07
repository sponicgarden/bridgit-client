package cc.bridgit.app.platform

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.nfc.*
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import cc.bridgit.app.*
import org.json.JSONObject
import kotlinx.coroutines.launch
import cc.bridgit.app.ui.common.dp

/** All authority changes and approvals originate in this non-exported owner surface. */
class ControlCenterActivity:AppCompatActivity() {
    private val app get()=application as BridgeApp
    private val runtime get()=app.platform
    private lateinit var screen:cc.bridgit.app.ui.common.OwnerScreen
    private val root get()=screen.list
    private val scroll get()=screen.scroll
    private val handler=Handler(Looper.getMainLooper())
    private var chosenAgent:String?=null
    private var importAgent:String?=null
    private var permissionRequestedAt = 0L
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty() && SystemClock.elapsedRealtime() - permissionRequestedAt < 500 &&
            denied.none { shouldShowRequestPermissionRationale(it) }) openAppSettings()
        if(app.policy.listenerEnabled) BridgeService.start(this)
        render()
    }
    private val share=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK && result.data!=null) startForegroundService(Intent(this,ProjectionService::class.java).putExtra("consent",result.data))
        render()
    }
    private val document=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) safely { runtime.files.import(importAgent ?: return@safely,uri); render() } }
    override fun onCreate(state:Bundle?) { super.onCreate(state); screen=cc.bridgit.app.ui.common.OwnerScreen(this,getString(cc.bridgit.app.R.string.more_control_center)); render() }
    override fun onResume() { super.onResume(); foreground=true; render() }
    override fun onPause() { foreground=false; NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this); super.onPause() }
    private fun heading(text:String)=screen.header(text)
    private fun text(value:String) { screen.note(value); (root.getChildAt(root.childCount-1) as TextView).setTextIsSelectable(true) }
    private fun button(label:String,action:()->Unit) { screen.row(label) { safely(action) } }
    private fun switch(label:String,on:Boolean,change:(Boolean)->Unit) { root.addView(com.google.android.material.materialswitch.MaterialSwitch(this).apply { text=label; isChecked=on; minHeight=dp(56); setPadding(dp(16),0,dp(16),0); setOnCheckedChangeListener { _,v -> change(v) } }) }
    private fun safely(action:()->Unit) { try { action() } catch(e:Exception) { AlertDialog.Builder(this).setTitle("Needs attention").setMessage(if(e is ApiError) e.message else "The action could not be completed.").setPositiveButton("OK",null).show() } }
    private fun open(action:String,uri:Uri?=null) { try { startActivity(Intent(action,uri)) } catch(e:ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) } }
    private fun openAppSettings() = open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    private fun requestAccess(vararg requested: String) {
        val missing = cc.bridgit.app.capability.Perms.forRequest(requested)
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) openAppSettings()
        else {
            permissionRequestedAt = SystemClock.elapsedRealtime()
            permissions.launch(missing.toTypedArray())
        }
    }
    private fun render() {
        if(!::screen.isInitialized) return
        val position=scroll.scrollY; root.removeAllViews()
        text("${runtime.store.setting("device_name",Build.MODEL)} • ${BuildConfigInfo.versionName(this)} • ${if(BuildConfig.PRIVATE_CONTROL) "Private control build" else "Play feature set"}\nDevice: ${runtime.store.deviceId}")
        text("New capabilities start off. Enable a feature and separately grant it to a named control agent. Stop cancels active authority. Android permissions are always granted through Android.")
        button("Stop all control agent work") { runtime.stopAll(); app.policy.listenerEnabled=false; BridgeService.stop(this); render() }
        button("Refresh status and requests") { render() }
        button("Name this device") { input("Device name",runtime.store.setting("device_name",Build.MODEL)) { DeviceName.set(app,it); render() } }
        heading("Android access")
        button("Grant camera, microphone, photos and notifications") { requestAccess(Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) }
        button("Grant location") { requestAccess(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION) }
        if(BuildConfig.PRIVATE_CONTROL) {
            button("Grant Nearby devices") {
                if (Build.VERSION.SDK_INT >= 31) requestAccess(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
                else openAppSettings()
            }
            button("Enable private app control") { AlertDialog.Builder(this).setTitle("Private app control").setMessage(getString(cc.bridgit.app.R.string.private_control_disclosure)).setPositiveButton("Open Android settings") { _,_-> open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }.setNegativeButton("Cancel",null).show() }
            button("Choose notification access") { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            button("Read an NFC tag while this screen is open") { readNfc() }
            button("Grant a connected USB device") { grantUsb() }
        }
        button("Share my screen using Android consent") { share.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
        if(ProjectionService.instance?.active==true) button("Stop screen sharing") { ProjectionService.instance?.stopSelf(); render() }
        button("Do Not Disturb access") { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
        button("Brightness settings access") { open(Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:$packageName")) }
        button("Notification and capture channels") { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)) }
        button("Battery and app settings") { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")) }
        text("Private control: ${DeviceControlService.instance!=null}; screen sharing: ${ProjectionService.instance?.active==true}; visible capture channel: ${Capabilities.visible(this)}")
        heading("Enabled device features")
        Capabilities.all.filter { !it.privateOnly || BuildConfig.PRIVATE_CONTROL }.forEach { cap ->
            switch(cap.label,Capabilities.enabled(app,cap.id)) { on -> safely { Capabilities.setEnabled(app,cap.id,on) } }
        }
        heading("Control agent access")
        val control=cc.bridgit.app.ui.home.HomeStates.controlAgents(app).map { it.id }.toSet() // portal viewers are not agents
        val agents=app.policy.agents().filter { it.id in control }
        if(agents.isEmpty()) text("Add a control agent from the main screen first.")
        else {
            if(agents.none { it.id==chosenAgent }) chosenAgent=agents.first().id
            val spinner=Spinner(this).apply { adapter=ArrayAdapter(this@ControlCenterActivity,android.R.layout.simple_spinner_dropdown_item,agents.map { it.name }); setSelection(agents.indexOfFirst { it.id==chosenAgent }) }
            var first=true
            spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent:AdapterView<*>?)=Unit; override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { if(first) { first=false; return }; chosenAgent=agents[position].id; render() } }
            root.addView(spinner,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(16),0,dp(16),0) })
            val agent=agents.first { it.id==chosenAgent }; identity(agent,root)
            text("Grants stay active until you turn them off.")
            Capabilities.all.filter { !it.privateOnly || BuildConfig.PRIVATE_CONTROL }.forEach { cap -> switch(cap.label,runtime.hasGrant(agent.id,cap.id)) { on -> safely { if(on) runtime.grant(agent.id,cap.id,Long.MAX_VALUE) else runtime.revoke(agent.id,cap.id) } } }
            button("Revoke every grant for ${agent.name}") { runtime.revoke(agent.id); render() }
            if(BuildConfig.PRIVATE_CONTROL) button("Choose notification apps for ${agent.name}") { chooseNotificationApps(agent.id) }
            button("Share a selected file with ${agent.name}") { importAgent=agent.id; document.launch(arrayOf("image/*","text/plain","application/pdf","audio/*")) }
        }
        heading("Questions and approvals")
        val pending=runtime.store.list(limit=300).filter { (it.optString("kind")=="operation" && it.optString("status")=="awaiting_owner") || (it.optString("kind")=="session" && it.optString("status")=="awaiting_owner") || (it.optString("kind")=="interaction" && it.optString("status")=="pending") || (it.optString("kind")=="workflow" && it.optString("status")=="awaiting_owner") }
        if(pending.isEmpty()) text("No requests are waiting.")
        pending.forEach { record -> button("${record.optJSONObject("identity")?.optString("name") ?: "Control agent"}: ${record.optString("title",record.optJSONObject("review_context")?.optString("title") ?: record.optString("command",record.optString("kind")))}") { review(record) } }
        heading("Control sessions")
        runtime.store.list(kind="session").filter { it.optString("status")=="active" }.forEach { record -> text("${record.optJSONObject("identity")?.optString("name")} • ${record.optString("purpose")}\n${record.optString("apps")}"); button("Stop this control session") { runtime.closeSession(record,"stopped_by_owner"); render() } }
        heading("Approved recipients and event delivery")
        text("Destinations are added only here. HTTPS acceptance means delivered to a service; recipient acknowledgment is a separate step. Help alerts allow five seconds to cancel and stop escalating after acknowledgment. This is not emergency dispatch.")
        button("Open Applets › Connections") { startActivity(Intent(this,ConnectionsActivity::class.java)) }
        button("Add an approved HTTPS recipient") { addDestination() }
        runtime.store.list("owner","destination").forEach { d ->
            text("${d.optString("name")} • ${if(d.optBoolean("enabled")) "Enabled" else "Revoked"}\n${d.optString("url")}")
            if(d.optBoolean("enabled")) {
                button("Copy ${d.optString("name")}'s verification secret") { sensitiveCopy("Bridgit callback verification",d.getString("secret")) }
                chosenAgent?.let { agent -> button("Send this control agent's scoped events to ${d.optString("name")}") { runtime.delivery.subscribe(agent,d.getString("id")); Toast.makeText(this,"Event delivery enabled",Toast.LENGTH_SHORT).show() } }
                button("Revoke this recipient") { runtime.delivery.revoke(d.getString("id")); render() }
            }
        }
        chosenAgent?.let { agent -> button("Stop this control agent's event callbacks") { runtime.store.setting("callback:$agent","",true) } }
        runtime.store.list(kind="alert").forEach { alert -> text("Help alert • ${alert.optString("status")} • ${alert.optString("acknowledged_by")}"); if(alert.optString("status") in setOf("queued","delivered","escalated")) button("Cancel this help alert") { runtime.delivery.cancel(alert.getString("agent_id"),alert.getString("id")); render() } }
        if(BuildConfig.PRIVATE_CONTROL) {
            val policy=getSystemService(android.app.admin.DevicePolicyManager::class.java)
            text("Managed kiosk available: ${policy.isLockTaskPermitted(packageName)}. Device-owner provisioning is a separate explicit setup; no device reset is performed by BRIDGIT.")
            if(policy.isLockTaskPermitted(packageName)) button("Start approved managed kiosk") { startLockTask() }
            if(getSystemService(ActivityManager::class.java).lockTaskModeState!=ActivityManager.LOCK_TASK_MODE_NONE) button("Exit managed kiosk") { stopLockTask() }
        }
        heading("Local appliances and workflows")
        text("Native templates only. Interval checks run with the listener; Android maintenance checks may be deferred. No emergency response or continuous availability is promised.")
        runtime.store.list(kind="workflow").forEach { record ->
            val definition=record.getJSONObject("definition"); val last=runtime.store.list(record.getString("agent_id"),"execution",200).filter { it.optString("workflow_id")==record.getString("id") }.maxByOrNull { it.optLong("started_at") }
            text("${definition.optString("name")} • ${record.optString("status")}${record.optString("blocked_reason").takeIf { it.isNotEmpty() && record.optString("status")=="blocked" }?.let { " ($it)" } ?: ""}\n${FlowSteps.summary(definition)}${definition.optJSONObject("ui")?.optString("text")?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""}${last?.let { "\nLast run: ${it.optString("status")}${it.optString("error").takeIf { e -> e.isNotEmpty() }?.let { e -> " ($e)" } ?: ""}" } ?: ""}")
            if(record.optString("status")=="active") {
                if(!ApplicationEvents.isAppletEvent(definition.optJSONObject("trigger")?.optString("type")) && definition.optJSONObject("trigger")?.optString("type")!="called") button(definition.optJSONObject("ui")?.optString("title")?.takeIf { it.isNotBlank() } ?: "Run / Help") {
                    // A share step needs the owner's own tap, which this is; workflow.run (the agent's way) refuses it.
                    runtime.scope.launch { runCatching { runtime.workflows.runNow(record.getString("agent_id"),record.getString("id"),null,ownerTap=runtime.workflows.shares(definition),personStarted=true) } }
                    render()
                }
                button("Pause this workflow") { runtime.workflows.pause(record.getString("agent_id"),record.getString("id")); render() }
            } else button("Review and activate workflow") { review(record) }
        }
        heading("Camera monitoring")
        text("Explicitly approved, time-limited JPEG frames. No microphone streaming. Captures stop on lock, lost visibility or revoked access. Viewers must inspect freshness.")
        runtime.store.list(kind="stream").forEach { stream ->
            val id=stream.getString("id"); text(runtime.monitor.status(stream.getString("agent_id"),id).toString(2))
            if(stream.optString("status")=="active") {
                button("Stop this camera session") { runtime.monitor.stop(stream.getString("agent_id"),id); render() }
                button("Issue a viewer credential") { val token=runtime.monitor.issueViewer(stream.getString("agent_id"),id); val dialog=AlertDialog.Builder(this).setTitle("Viewer credential — shown once").setMessage("Read-only for this stream. Store securely.\n\n$token\n\n/v2/viewer/streams/$id").setPositiveButton("Copy") { _,_-> sensitiveCopy("Bridgit viewer",token) }.setNegativeButton("Close",null).create(); cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(dialog.window); dialog.show(); dialog.findViewById<TextView>(android.R.id.message)?.let { m -> cc.bridgit.app.ui.common.SecretViews.hideSecret(m) { token } } }
                button("Revoke viewer credential") { runtime.monitor.revokeViewer(id) }
            }
        }
        heading("Files and notices")
        runtime.store.list(kind="file").forEach { file -> button("Remove local file: ${file.optString("name")}") { runtime.files.remove(file.getString("agent_id"),file.getString("id")); render() } }
        runtime.store.list(kind="file").forEach { record -> button("Open ${record.optString("name")}") { val agent=record.getString("agent_id"); val id=record.getString("id"); val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.files",runtime.files.file(agent,id)); startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,record.getString("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); runtime.store.emit(agent,"file.opened",id) } }
        runtime.store.list(kind="interaction").filter { it.optString("status")=="delivered" }.take(20).forEach { r -> text("${r.optJSONObject("identity")?.optString("name")}: ${r.optString("title")}\n${r.optString("text")}") }
        button("Remove completed platform history") {
            AlertDialog.Builder(this).setTitle("Remove completed history?").setMessage("Removes completed operation bodies, finished workflow runs and alerts, answered questions, notes and feedback from this local platform store. Idempotency tombstones remain, so old requests cannot be replayed. Original gallery media and active workflows are preserved.").setPositiveButton("Remove") { _,_->
                runtime.store.list(limit=1000).filter { (it.optString("kind") in setOf("operation","execution","outbox") && it.optString("status") in setOf("completed","failed","cancelled","expired","unknown_outcome","acknowledged","delivered")) || (it.optString("kind")=="interaction" && it.optString("status")!="pending") || it.optString("kind") in setOf("note","feedback") }.forEach { runtime.store.delete(it.getString("id"),it.getString("agent_id")) }; render()
            }.setNegativeButton("Cancel",null).show()
        }
        heading("Recent operations")
        runtime.store.list(kind="operation").take(20).forEach { record -> text("${record.optString("command")} • ${record.optString("status")}\n${record.optString("message")}"); if(record.optString("status") in listOf("accepted","running","awaiting_owner")) button("Cancel this operation") { runtime.cancelOperation(record.getString("agent_id"),record.getString("id")); render() } }
        text("Build readiness and blockers are also available through /v2/capabilities. Control agent artwork is self-supplied, not a verified identity badge.")
        scroll.post { scroll.scrollTo(0,position) }
    }
    private fun applicationReview(definition:JSONObject):String {
        val trigger=definition.getJSONObject("trigger")
        val plain=FlowSteps.summary(definition)+"\n\nIt needs: "+runCatching { runtime.workflows.validate(definition).getJSONArray("required_scopes").let { s -> (0 until s.length()).joinToString(", ") { i -> Capabilities.all.firstOrNull { it.id==s.getString(i) }?.label ?: s.getString(i) } } }.getOrDefault("(couldn't check)")+"\n\nDetails:\n"
        if(!ApplicationEvents.isAppletEvent(trigger.optString("type"))) return plain+definition.toString(2)
        val source=if(trigger.optString("transport")=="notification") "Selected notifications from ${trigger.optString("package")}. Phone must be unlocked." else "JSON webhooks authenticated as this control agent."
        return plain+"Applet: ${ApplicationEvents.appletId(trigger)}\nSource: ${trigger.optString("source_id")}\n$source\nFormat: ${trigger.optString("adapter","generic")}\nEvent: ${trigger.optString("event_type").ifEmpty { "All event types" }}\nCooldown: ${definition.optInt("cooldown_s",trigger.optInt("cooldown_seconds",30))} seconds per device and event type.\n\nOnly the actions below will run. Event text cannot change actions or recipients.\n\n${definition.toString(2)}"
    }
    private fun review(record:JSONObject) {
        val agent=record.optJSONObject("identity") ?: AgentBranding.json(runtime.getAgent(record.getString("agent_id")))
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,16,24,16) }; identityJson(agent,box)
        val body=when(record.getString("kind")) { "operation" -> if(record.optString("command")=="owner.configure") record.optJSONObject("review_context")?.optString("description") ?: "This request needs to be sent again." else (record.optJSONObject("review_context")?.toString(2)?.plus("\n\n") ?: "")+record.getJSONObject("input").toString(2); "session" -> "Purpose: ${record.optString("purpose")}\nApps: ${record.optString("apps")}\nDuration: ${record.optInt("duration_seconds")} seconds\nEvery action still requires review."; "workflow" -> applicationReview(record.getJSONObject("definition")); else -> record.optString("text") }
        box.addView(TextView(this).apply { text=body; textSize=15f; setTextIsSelectable(true) })
        if(record.optString("kind")=="interaction") {
            val edit=EditText(this).apply { hint="Your answer" }; box.addView(edit)
            val dialog=AlertDialog.Builder(this).setTitle(record.optString("title")).setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Reply") { _,_-> safely { runtime.answer(record.getString("id"),edit.text.toString()); render() } }.setNegativeButton("Dismiss") { _,_-> safely { runtime.answer(record.getString("id"),"",true); render() } }.create()
            record.optJSONArray("choices")?.let { choices -> for(i in 0 until choices.length()) box.addView(MaterialButton(this).apply { text=choices.getString(i); setOnClickListener { safely { runtime.answer(record.getString("id"),choices.getString(i)); dialog.dismiss(); render() } } }) }
            dialog.show(); return
        }
        AlertDialog.Builder(this).setTitle(if(record.optString("command")=="owner.configure") "Review request" else "Review ${record.optString("command",record.optString("kind"))}").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Continue") { _,_->
            val confirm=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,16,24,16) }; identityJson(agent,confirm)
            confirm.addView(TextView(this).apply { text="Confirm the exact action above. Its authority cannot grow after approval." })
            AlertDialog.Builder(this).setTitle("Confirm ${agent.optString("name")}'s request").setView(confirm).setPositiveButton("Approve") { _,_-> cc.bridgit.app.security.OwnerCheck.run(this, getString(cc.bridgit.app.R.string.biometric_approve, agent.optString("name"))) { safely {
                when(record.getString("kind")) {
                    "session" -> { runtime.approveSession(record.getString("id")); val pkg=record.strings("apps").first(); packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
                    "workflow" -> runtime.workflows.activate(record.getString("id"))
                    else -> {
                        if(record.optString("command") in setOf("ui.action","ui.sequence")) {
                            val input=record.getJSONObject("input"); val session=runtime.session(record.getString("agent_id"),input.text("session_id")); packageManager.getLaunchIntentForPackage(record.optJSONObject("review_context")?.optString("package")?.takeIf { it.isNotBlank() } ?: session.strings("apps").first())?.let { startActivity(it) }
                            handler.postDelayed({ safely { runtime.approveOperation(record.getString("id")) } },1200)
                        } else runtime.approveOperation(record.getString("id"))
                    }
                }
                render()
            } } }.setNegativeButton("Cancel",null).show()
        }.setNegativeButton("Decline") { _,_-> safely {
            when(record.getString("kind")) { "session" -> runtime.closeSession(record,"declined"); "workflow" -> runtime.workflows.pause(record.getString("agent_id"),record.getString("id")); else -> runtime.cancelOperation(record.getString("agent_id"),record.getString("id")) }; render()
        } }.show()
    }
    private fun identity(agent:Policy.Agent,box:LinearLayout)=identityJson(AgentBranding.json(agent),box)
    private fun identityJson(agent:JSONObject,box:LinearLayout) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(8,12,8,12) }
        AgentBranding.bitmap(agent.optString("icon_base64").takeIf { it.isNotBlank() && it!="null" })?.let { bitmap -> row.addView(ImageView(this).apply { setImageBitmap(bitmap); contentDescription="${agent.optString("name")} icon" },LinearLayout.LayoutParams(72,72)) }
        row.addView(TextView(this).apply { text=agent.optString("name"); textSize=20f; setTypeface(typeface,Typeface.BOLD); setPadding(12,0,0,0) })
        box.addView(row)
        AgentBranding.bitmap(agent.optString("wordmark_base64").takeIf { it.isNotBlank() && it!="null" })?.let { bitmap -> box.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds=true; contentDescription="${agent.optString("name")} wordmark" },LinearLayout.LayoutParams(-1,72)) }
    }
    private fun input(title:String,value:String,save:(String)->Unit) { val field=EditText(this).apply { setText(value) }; AlertDialog.Builder(this).setTitle(title).setView(field).setPositiveButton("Save") { _,_-> safely { save(field.text.toString()) } }.setNegativeButton("Cancel",null).show() }
    private fun chooseNotificationApps(agent:String) {
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps=packageManager.queryIntentActivities(intent,0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val selected=runtime.store.setting("notification_apps:$agent").split(',').filter { it.isNotBlank() }.toMutableSet()
        val checked=BooleanArray(apps.size) { apps[it].activityInfo.packageName in selected }
        AlertDialog.Builder(this).setTitle("Notifications this control agent may read").setMultiChoiceItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray(),checked) { _,index,on -> if(on) selected.add(apps[index].activityInfo.packageName) else selected.remove(apps[index].activityInfo.packageName) }
            .setPositiveButton("Save") { _,_-> safely { NotificationSelection(app).set(agent, selected.toList()) } }.setNegativeButton("Cancel",null).show()
    }
    private fun sensitiveCopy(label:String,value:String) {
        val clip=ClipData.newPlainText(label,value)
        clip.description.extras=PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this,"Copied — store securely",Toast.LENGTH_SHORT).show()
    }
    private fun addDestination() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        val name=EditText(this).apply { hint="Recipient or service name" }; val url=EditText(this).apply { hint="https://recipient.example/bridgit" }
        val privateNetwork=CheckBox(this).apply { text="Allow this exact destination on my private network" }
        box.addView(name); box.addView(url); box.addView(privateNetwork)
        AlertDialog.Builder(this).setTitle("Approve a destination").setMessage("Only this HTTPS address receives help alerts and events, signed with X-Bridgit-Signature; flows' send steps use Standard Webhooks signatures. Applets › Connections shows every connection, its secret and a test send.").setView(box).setPositiveButton("Approve") { _,_-> safely { runtime.delivery.destination(name.text.toString(),url.text.toString(),privateNetwork.isChecked); render() } }.setNegativeButton("Cancel",null).show()
    }
    private fun grantUsb() {
        val manager=getSystemService(UsbManager::class.java); val devices=manager.deviceList.values.toList()
        if(devices.isEmpty()) throw ApiError(409,"no_usb_device","Connect a USB device first")
        AlertDialog.Builder(this).setTitle("Choose USB device").setItems(devices.map { it.productName ?: it.deviceName }.toTypedArray()) { _,index -> manager.requestPermission(devices[index],PendingIntent.getBroadcast(this,987,Intent("$packageName.USB_PERMISSION").setPackage(packageName),PendingIntent.FLAG_IMMUTABLE)) }.show()
    }
    private fun readNfc() {
        val nfc=NfcAdapter.getDefaultAdapter(this) ?: throw ApiError(409,"nfc_unavailable","This device has no NFC reader")
        nfc.enableReaderMode(this,{ tag ->
            val ndef=android.nfc.tech.Ndef.get(tag); val messages=ndef?.cachedNdefMessage?.records?.take(16)?.map { r -> JSONObject().put("type",String(r.type)).put("payload_base64",android.util.Base64.encodeToString(r.payload.take(4096).toByteArray(),android.util.Base64.NO_WRAP)) } ?: emptyList()
            runtime.store.setting("last_nfc",JSONObject().put("id",tag.id.toHex()).put("technologies",org.json.JSONArray(tag.techList.toList())).put("records",org.json.JSONArray(messages)).put("as_of",System.currentTimeMillis()).put("content_is_untrusted",true).toString(),true)
            handler.post { Toast.makeText(this,"NFC tag read",Toast.LENGTH_SHORT).show(); nfc.disableReaderMode(this) }
        },NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,null)
        Toast.makeText(this,"Hold your own tag near the phone",Toast.LENGTH_LONG).show()
    }
    companion object { @Volatile var foreground=false; private set }
}
class ShareReceiveActivity:AppCompatActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state); val app=application as BridgeApp
        if(intent.action!=Intent.ACTION_SEND) { finish(); return }
        val control=cc.bridgit.app.ui.home.HomeStates.controlAgents(app).map { it.id }.toSet()
        val agents=app.policy.agents().filter { it.id in control && app.platform.hasGrant(it.id,"files") && Capabilities.enabled(app,"files") }
        if(agents.isEmpty()) { AlertDialog.Builder(this).setTitle("No control agent has file access").setMessage("Enable Files and shares and grant it to your control agent in Agent permissions.").setPositiveButton("Open Agent permissions") { _,_-> startActivity(Intent(this,ControlCenterActivity::class.java)); finish() }.setNegativeButton("Cancel") { _,_-> finish() }.show(); return }
        AlertDialog.Builder(this).setTitle("Share with which control agent?").setItems(agents.map { it.name }.toTypedArray()) { _,index ->
            try {
                val a=agents[index]; app.platform.authorize(a.id,"files")
                val uri=androidx.core.content.IntentCompat.getParcelableExtra(intent,Intent.EXTRA_STREAM,Uri::class.java)
                if(uri!=null && uri.scheme=="content") app.platform.files.import(a.id,uri)
                else { val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(32768) ?: throw ApiError(422,"unsupported_share","Share text or a supported document"); app.platform.files.put(a.id,JSONObject().put("name","Shared text.txt").put("mime","text/plain").put("data_base64",android.util.Base64.encodeToString(text.toByteArray(),android.util.Base64.NO_WRAP))) }
                Toast.makeText(this,"Shared with ${a.name}",Toast.LENGTH_SHORT).show()
            } catch(e:Exception) { Toast.makeText(this,if(e is ApiError) e.message else "Could not import this share",Toast.LENGTH_LONG).show() }
            finish()
        }.setNegativeButton("Cancel") { _,_-> finish() }.setOnCancelListener { finish() }.show()
    }
}
