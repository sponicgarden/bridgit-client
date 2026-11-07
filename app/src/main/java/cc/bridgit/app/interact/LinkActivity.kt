package cc.bridgit.app.interact

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R

/**
 * Opens an interaction's link from a notification action (CAP-41). Android 12+ forbids notification
 * trampolines, so the action starts this invisible activity, which starts the link from the owner's tap.
 * The extras carry only ids: the URL or package is always read from the stored interaction.
 */
class LinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val id = intent.getLongExtra(InteractionNotifier.EXTRA_INTERACTION_ID, -1L)
        val controlId = intent.getStringExtra(InteractionNotifier.EXTRA_CONTROL_ID)
        val app = application as BridgeApp
        lifecycleScope.launch {
            val i = withContext(Dispatchers.IO) { if (id >= 0) app.interactions.get(id) else null }
            val link = i?.controls?.firstOrNull { it is Control.Link && it.id == controlId } as? Control.Link
            val target = link?.let { open(it) }
            if (target == null) {
                Toast.makeText(this@LinkActivity, R.string.interact_link_failed, Toast.LENGTH_SHORT).show()
            } else {
                try {
                    startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    app.log.system("audit", "interaction", getString(R.string.interact_link_opened, link.label, i.agent))
                    if (i.kind == IKind.NOTICE) withContext(Dispatchers.IO) { app.interactions.markSeen(i.id) }
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@LinkActivity, R.string.interact_link_no_app, Toast.LENGTH_SHORT).show()
                }
            }
            finish()
        }
    }

    private fun open(l: Control.Link): Intent? = when (NotifRules.linkOpen(l.target, l.pkg)) {
        NotifRules.LinkOpen.VIEW -> Intent(Intent.ACTION_VIEW, Uri.parse(l.target)).apply {
            if (l.target!!.trim().lowercase().startsWith("https:")) addCategory(Intent.CATEGORY_BROWSABLE)
        }
        NotifRules.LinkOpen.DIAL -> Intent(Intent.ACTION_DIAL, Uri.parse(l.target))
        NotifRules.LinkOpen.LAUNCH -> l.pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        NotifRules.LinkOpen.NONE -> null
    }

    companion object {
        fun intent(ctx: Context, interactionId: Long, controlId: String) = Intent(ctx, LinkActivity::class.java)
            .putExtra(InteractionNotifier.EXTRA_INTERACTION_ID, interactionId)
            .putExtra(InteractionNotifier.EXTRA_CONTROL_ID, controlId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
