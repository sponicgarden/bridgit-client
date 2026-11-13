package cc.bridgit.app.security

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import cc.bridgit.app.BridgeApp

/**
 * Hosts the operating system's own prompt for one owner check (architecture §4.7) and nothing else: the
 * window is transparent and has no content of its own. The proposal id comes in the intent; what is signed
 * is rebuilt from the authority store, never taken from the intent. Not exported.
 *
 * It never outlives its prompt: it closes when there is nothing to ask, when the prompt ends, and when
 * Android restores it without its prompt (the signature in progress cannot be restored). The check stays
 * waiting in the store and is offered again when Bridgit is next in front.
 */
class OwnerCheckActivity : FragmentActivity() {
    private var asked = false
    private val app get() = application as BridgeApp
    private val proposal get() = intent.getStringExtra(EXTRA_PROPOSAL).orEmpty()

    override fun onCreate(state: Bundle?) {
        // No fragment is restored: a restored prompt would have nobody to answer to.
        super.onCreate(null)
        if (state != null || proposal.isEmpty()) { finish(); return }
        app.authority.showing(proposal)
    }

    override fun onResume() {
        super.onResume()
        if (asked || isFinishing) return
        asked = true
        val id = proposal
        // The store is never read on the main thread.
        app.authority.post {
            val check = runCatching { app.authority.check(id) }.getOrNull()
            runOnUiThread {
                if (check == null || isFinishing || isDestroyed) { finish(); return@runOnUiThread }
                // The prompt attaches only to a resumed activity. If this one was left meanwhile, ask on its next resume.
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) { asked = false; return@runOnUiThread }
                BoundOwnerCheck.sign(this, app.authority.keys, check.title, check.reason, check.message) { outcome ->
                    app.authority.answered(id, outcome)
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        if (app.authority.enabled) app.authority.showing(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROPOSAL = "proposal"

        /**
         * Asks Android to open the prompt. From the background Android allows this only while Bridgit is in
         * front or may display over other apps, and refuses silently otherwise; the check is then offered
         * when Bridgit is next opened. False only when the start itself failed.
         */
        fun launch(ctx: Context, proposal: String): Boolean = runCatching {
            ctx.startActivity(Intent(ctx, OwnerCheckActivity::class.java).putExtra(EXTRA_PROPOSAL, proposal)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            true
        }.getOrDefault(false)
    }
}
