package cc.bridgit.app.relay

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Turning the relay on from the Relay screen: register this phone if needed, then let the listener connect. */
object RelaySetup {
    /** The relay address is missing or isn't this phone's: only More › Relay › Advanced can fix it. */
    class AddressProblem(message: String, val missing: Boolean) : IllegalStateException(message)

    private val http by lazy {
        OkHttpClient.Builder().addInterceptor(cc.bridgit.app.outbound.OutboundInterceptor).followRedirects(false).followSslRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
    }

    /**
     * Blocking; call off the main thread. With no [code] the phone registers itself (the relay must allow
     * self-enrolment); with a one-time code from the relay's owner page it uses that. A phone the relay already
     * knows just reconnects.
     */
    fun turnOn(app: BridgeApp, store: RelayStore, code: String = "") {
        if (store.base.isEmpty()) store.base = store.defaultBase().ifEmpty { throw AddressProblem(app.getString(R.string.relay_no_address), missing = true) }
        if (!store.base.endsWith("/d/${store.device}")) throw AddressProblem(app.getString(R.string.relay_address_helper, store.device), missing = false)
        if (!store.enrolled || code.isNotEmpty()) {
            val body = store.enrollment().apply { if (code.isNotEmpty()) put("token", code) else put("proof", store.selfProof()) }
            val req = Request.Builder().url(store.base + "/enroll")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.source()?.let { if (it.request(65537)) "" else it.readUtf8() }.orEmpty()
                val error = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                if (!r.isSuccessful && error != "already_enrolled") error(error.ifEmpty { "HTTP ${r.code}" })
            }
            store.enrolled = true
        }
        store.enabled = true
        app.settings.listenerEnabled = true
        BridgeService.start(app)
        BridgeService.running?.relay?.wake()
    }
}
