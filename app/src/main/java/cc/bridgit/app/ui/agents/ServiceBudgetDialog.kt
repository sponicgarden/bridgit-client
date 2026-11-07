package cc.bridgit.app.ui.agents

import android.text.InputType
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.judgment.ServiceBudgets
import cc.bridgit.app.ui.common.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Owner and approved agent writes share ServiceBudgets validation and transport. */
fun Fragment.serviceBudgetDialog(app: BridgeApp, service: String) {
    lifecycleScope.launch {
        val ctx = context ?: return@launch
        val view = try { withContext(Dispatchers.IO) { ServiceBudgets.read(app, service) } }
            catch (e: Exception) { MaterialAlertDialogBuilder(ctx).setMessage(e.message).setPositiveButton(android.R.string.ok,null).show(); return@launch }
        if (!isAdded) return@launch
        val parent = LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL; setPadding(ctx.dp(24),0,ctx.dp(24),0) }
        val fields = linkedMapOf<String, TextInputEditText>()
        val limits = view.getJSONArray("limits")
        for (per in listOf("hour","day","week","month")) {
            val existing=(0 until limits.length()).map(limits::getJSONObject).filter { it.optString("per")==per }.minByOrNull { it.optDouble("usd") }
            val field=TextInputEditText(ctx).apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(existing?.optDouble("usd")?.toString().orEmpty()) }
            fields[per]=field
            parent.addView(TextInputLayout(ctx).apply { hint="USD / $per"; addView(field); existing?.let { helperText=String.format(java.util.Locale.US, "Spent: $%.6f", it.optDouble("spent_usd")) } })
        }
        val dialog=MaterialAlertDialogBuilder(ctx).setTitle(ServiceBudgets.NAMES[service]+" budget").setView(parent).setNegativeButton(android.R.string.cancel,null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val next=JSONArray()
            var valid=true
            for ((per,field) in fields) {
                val text=field.text.toString().trim()
                val usd=text.toDoubleOrNull()
                if(text.isNotEmpty() && (usd==null || !usd.isFinite() || usd !in 0.01..100.0)) { field.error="0.01–100"; valid=false }
                else if(usd!=null) next.put(JSONObject().put("id",per).put("per",per).put("usd",usd))
            }
            if(valid) lifecycleScope.launch {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled=false
                try { withContext(Dispatchers.IO) { ServiceBudgets.set(app,service,next) }; dialog.dismiss() }
                catch(e:Exception) { fields.values.first().error=e.message; dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled=true }
            }
        } }
        dialog.show()
    }
}
