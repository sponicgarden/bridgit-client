package cc.bridgit.app.ui.adb

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.adb.SuperPowers
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AGT-15b: Revoke Super Powers, from the ADB screen or an agent's page. Asks once, shows progress,
 * then lists each step: done, or still on with where to turn it off.
 */
object RevokeSuperPowersDialog {
    fun show(f: Fragment, onDone: () -> Unit = {}) {
        val ctx = f.requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.revoke_super_title)
            .setMessage(R.string.revoke_super_body)
            .setPositiveButton(R.string.revoke_super_confirm) { _, _ -> run(f, onDone) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun run(f: Fragment, onDone: () -> Unit) {
        val ctx = f.requireContext()
        val app = ctx.applicationContext as BridgeApp
        val working = MaterialAlertDialogBuilder(ctx).setTitle(R.string.revoke_super_working).setMessage("…")
            .setCancelable(false).show()
        f.lifecycleScope.launch {
            val lines = SuperPowers.revoke(app) { step ->
                f.lifecycleScope.launch(Dispatchers.Main) { working.setMessage(step) }
            }
            withContext(Dispatchers.Main) {
                working.dismiss()
                if (!f.isAdded) return@withContext
                MaterialAlertDialogBuilder(f.requireContext())
                    .setTitle(R.string.revoke_super_done_title)
                    .setMessage(summary(lines))
                    .setPositiveButton(R.string.ok, null)
                    .show()
                onDone()
            }
        }
    }

    /** "✓ Usage access", "✗ Wireless debugging off — Settings → …". Plain text, one step per line. */
    fun summary(lines: List<SuperPowers.Line>): String = lines.joinToString("\n") { l ->
        (if (l.ok) "✓ " else "✗ ") + l.what + (l.how?.let { " — $it" } ?: "")
    }
}
