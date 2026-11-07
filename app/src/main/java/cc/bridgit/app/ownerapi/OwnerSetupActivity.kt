package cc.bridgit.app.ownerapi

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import cc.bridgit.app.BridgeApp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import cc.bridgit.app.ui.common.OwnerScreen

/** Non-exported owner setup. It opens official consent/install screens; it never impersonates approval. */
class OwnerSetupActivity:AppCompatActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val target=intent.getStringExtra("target") ?: "permissions"
        val screen=OwnerScreen(this,"Finish setup")
        screen.note("Your control agent requested setup. Android and account sign-in may still need your confirmation.")
        screen.row("Open setup") {
            val next=when(target) {
                "speech" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                "tts" -> Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                "remote_link" -> Intent(this,cc.bridgit.app.remoteview.LinkWebViewActivity::class.java)
                "install_update" -> {
                    lifecycleScope.launch {
                        val app=application as BridgeApp
                        try {
                            val ready=app.resourceDownloads.verifiedUpdate(intent.getStringExtra("agent_id").orEmpty(),intent.getStringExtra("job_id").orEmpty())
                            app.platform.authorize(intent.getStringExtra("agent_id").orEmpty(),"owner.configuration")
                            when(val checked=cc.bridgit.app.update.UpdateInstaller.reverify(app,ready)) {
                                is cc.bridgit.app.update.UpdateInstaller.Download.Ready -> {
                                    if(cc.bridgit.app.update.UpdateInstaller.canRequestInstalls(this@OwnerSetupActivity)) {
                                        startActivity(cc.bridgit.app.update.UpdateInstaller.installIntent(this@OwnerSetupActivity,checked.file))
                                        cc.bridgit.app.update.UpdateInstaller.logInstallHandoff(app,checked.manifest)
                                    } else startActivity(cc.bridgit.app.update.UpdateInstaller.unknownSourcesIntent(this@OwnerSetupActivity))
                                }
                                else -> screen.note("The cached update could not be verified. Ask your control agent to download it again.")
                            }
                        } catch(_:Exception) { screen.note("This install request is no longer available. Ask your control agent to check the update job.") }
                    }
                    null
                }
                "updates" -> { cc.bridgit.app.update.UpdateDialog.checkForUpdates(this,true); null }
                "camera_enrollment","own_keys" -> Intent(this,cc.bridgit.app.ui.MainActivity::class.java)
                    .putExtra("owner_setup_target",if(target=="camera_enrollment") "cameras" else "settings")
                else -> Intent(this,cc.bridgit.app.platform.ControlCenterActivity::class.java)
            }
            if(next!=null) try { startActivity(next) } catch(_:android.content.ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
            }
        }
    }
}
