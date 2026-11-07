package cc.bridgit.app.ui.home

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.alerts.AlertRelay
import cc.bridgit.app.databinding.SettingsDialogFieldBinding

/** The Home banner's side of owner email verification (alerts.EmailGate): add an address, or resend the link / fix the address. HomeFragment notices the tapped link by itself. */
object EmailPrompt {
    /** Asks for the address and sends the confirmation email. [done] runs afterwards on the main thread. */
    fun ask(f: Fragment, done: () -> Unit) {
        val app = f.requireActivity().application as BridgeApp
        val v = SettingsDialogFieldBinding.inflate(f.layoutInflater)
        v.field1.hint = f.getString(R.string.alerts_recipient_hint)
        v.field2.visibility = android.view.View.GONE
        v.input1.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        v.input1.filters = arrayOf(android.text.InputFilter.LengthFilter(254))
        v.input1.setText(app.settings.alertEmail ?: "")
        cc.bridgit.app.ui.EmailSuffixChips.attach(v.root as android.widget.LinearLayout, v.input1)
        val d = MaterialAlertDialogBuilder(f.requireContext())
            .setTitle(R.string.email_verify_title)
            .setMessage(R.string.email_verify_body)
            .setView(v.root)
            .setPositiveButton(R.string.email_verify_send, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = v.input1.text?.toString()?.trim()?.lowercase().orEmpty()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { v.field1.error = f.getString(R.string.alerts_recipient_invalid); return@setOnClickListener }
                d.dismiss()
                f.viewLifecycleOwner.lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { app.alerts.subscribe(email, "You") }
                    snack(f, when (r) {
                        AlertRelay.Subscribe.CONFIRMED -> f.getString(R.string.email_verify_done)
                        AlertRelay.Subscribe.PENDING -> f.getString(R.string.email_verify_sent, email)
                        AlertRelay.Subscribe.BAD_EMAIL -> f.getString(R.string.alerts_recipient_invalid)
                        AlertRelay.Subscribe.RATE_LIMITED -> f.getString(R.string.alerts_rate_limited)
                        AlertRelay.Subscribe.FAILED -> f.getString(R.string.alerts_failed)
                    })
                    done()
                }
            }
        }
        d.show()
    }


    private fun snack(f: Fragment, text: String) {
        f.view?.let { Snackbar.make(it, text, Snackbar.LENGTH_LONG).show() }
    }
}
