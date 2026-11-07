package cc.bridgit.app.ui.applets

import android.content.Intent
import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.ui.MainActivity
import org.json.JSONObject

/** Both entry points open the selected applet's own screen. */
object AppletNavigation {
    fun openIntent(app: BridgeApp, instance: JSONObject): Intent =
        if (app.applets.portalIsScreen(instance)) portalIntent(app, "pkg." + instance.getString("package_id"))
        else AppletPortalActivity.intent(app, instance.getString("id"))

    /** All applet portal entry points share the embedded browser and its owner sign-in. */
    fun portalIntent(context: Context, portalId: String = ""): Intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(MainActivity.EXTRA_OPEN_PORTAL, portalId)
}
