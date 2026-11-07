package cc.bridgit.app.ui.addagent

import androidx.lifecycle.ViewModelStore
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Connection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingExportTest {
    @Test fun exportRebuildsEveryUrlAndQrAfterListenerPortChanges() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as BridgeApp
        app.settings.listenerEnabled = true
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        val store = ViewModelStore()
        val vm = AddAgentViewModel(app)
        store.put("pairing", vm)
        // Seed the already-created agent: JVM tests have no Android hardware Keystore.
        val (agent, key) = app.agents.add("Pairing regression", Connection.DIRECT)
        ReflectionHelpers.setField(vm, "agentId", agent.id)
        ReflectionHelpers.setField(vm, "key", key)
        try {
            for (connection in listOf(Connection.DIRECT, Connection.USERSPACE)) {
                app.settings.port = 18529
                app.updateListener { BridgeApp.ListenerState(running = true, boundTo = listOf("127.0.0.1"), port = 18529) }
                // The existing agent/key is reused, as when returning to an open setup screen.
                vm.name = "Pairing regression"
                vm.connection = connection
                vm.refreshPrompt()
                assertTrue(vm.prompt!!.contains(":18529/"))
                ServerSocket(0).use { server ->
                    val port = server.localPort
                    app.settings.port = port
                    app.updateListener { it.copy(port = port) }
                    val export = vm.prepareExport()!!
                    assertFalse(export.prompt.contains(":18529/"))
                    assertTrue(export.prompt.contains("http://127.0.0.1:$port/v1/mcp"))
                    assertTrue(export.prompt.contains("http://127.0.0.1:$port/mcp"))
                    assertEquals("http://127.0.0.1:$port/v1", org.json.JSONObject(export.qr).getString("base_url"))
                    if (connection == Connection.USERSPACE) assertTrue(export.prompt.contains("BRIDGIT_PORT=$port"))
                }
            }
            app.updateListener { it.copy(boundTo = emptyList()) }
            assertNull(vm.prepareExport())
            assertNotNull(vm.key)
            assertFalse(vm.acknowledged)
        } finally { store.clear() }
    }
}
