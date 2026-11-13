package cc.bridgit.app.clientcore

import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The real HTTP listener goes through ApiServer.admitHttp (phase 1.6): bound to loopback, which is not the tailnet,
 * every call must be refused with forbidden_source by the seam's first step. The later steps are DoorFixturesTest's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class HttpListenerSeamTest {
    @Test fun loopbackCallerIsRefusedByTheSeam() {
        val svc = Robolectric.buildService(BridgeService::class.java).create().get()
        val port = java.net.ServerSocket(0).use { it.localPort }
        val api = ApiServer(svc, listOf("127.0.0.1"), port)
        api.start()
        try {
            var code = -1; var body = ""
            for (i in 0 until 50) {
                try {
                    val c = java.net.URL("http://127.0.0.1:$port/v1/log").openConnection() as java.net.HttpURLConnection
                    c.setRequestProperty("Authorization", "Bearer brg_fixtureWrongKey0000")
                    code = c.responseCode
                    body = (c.errorStream ?: c.inputStream).bufferedReader().readText()
                    break
                } catch (e: java.net.ConnectException) { Thread.sleep(100) }
            }
            assertEquals(403, code)
            assertEquals("forbidden_source", JSONObject(body).getString("error"))
        } finally { api.stop(); svc.onDestroy() }
    }
}
