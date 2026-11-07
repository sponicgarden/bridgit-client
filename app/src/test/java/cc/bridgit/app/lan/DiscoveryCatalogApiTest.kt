package cc.bridgit.app.lan

import android.app.Application
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.core.Connection
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscoveryCatalogApiTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun observation(host: String, name: String, service: String) = JSONObject()
        .put("host", host).put("name", name).put("service", service).put("source", "mdns")
    @Test fun groupsAndTotalsCountDevicesRatherThanAdvertisements() {
        val rows = JSONArray()
            .put(observation("192.168.1.20", "Tapo C210", "tapo_camera"))
            .put(observation("192.168.1.20", "Tapo C210", "_rtsp._tcp."))
            .put(observation("192.168.1.21", "HIKVISION DS-2CD2086G2-IU - DEMO123456", "_http._tcp."))
            .put(observation("192.168.1.30", "WiiM Living room", "_linkplay._tcp."))
            .put(observation("192.168.1.40", "TP-Link Deco M4R", "tplink_router"))
            .put(observation("192.168.1.50", "192.168.1.50", "_http._tcp."))
        val result = DiscoveryResult.enrich(app, JSONObject().put("observations", rows))
        assertEquals(5, result.getInt("total_devices"))
        assertEquals(4, result.getInt("total_categories"))
        val categories = result.getJSONArray("categories")
        assertEquals("cameras", categories.getJSONObject(0).getString("id"))
        assertEquals(2, categories.getJSONObject(0).getInt("count"))
        val cameraBrands = categories.getJSONObject(0).getJSONArray("brands")
        assertEquals(2, cameraBrands.length())
        assertEquals(1, cameraBrands.getJSONObject(0).getInt("count"))
        assertEquals(5, (0 until categories.length()).sumOf { categories.getJSONObject(it).getInt("count") })
        assertEquals("other", categories.getJSONObject(categories.length()-1).getString("id"))
        assertFalse(categories.getJSONObject(categories.length()-1).getBoolean("expanded_by_default"))
        val devices = result.getJSONArray("devices")
        assertEquals("Tapo C210", devices.getJSONObject(0).getString("name"))
        assertFalse(devices.getJSONObject(0).getBoolean("enrolled"))
        assertFalse(devices.getJSONObject(0).getBoolean("verified"))
        assertEquals("not_checked", devices.getJSONObject(0).getString("control_status"))
        assertEquals("tapo", devices.getJSONObject(0).getJSONObject("brand").getString("id"))
    }
    @Test fun brandAssetsAreBundledPassiveAndDoNotInferManufacturerFromGenericProtocols() {
        val brands = DeviceBrands(app)
        assertTrue(brands.all.size >= 16)
        for (brand in brands.all) {
            assertTrue(brand.name, app.resources.getIdentifier(brand.resource,"drawable",app.packageName) != 0)
            val bytes = brands.logo(app,brand)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(brand.sha256,hash)
            if (brand.mime == "image/svg+xml") {
                val svg=String(bytes)
                assertFalse(svg.contains("<script",true));assertFalse(svg.contains("href=",true))
                assertFalse(svg.contains("<image",true));assertFalse(svg.contains("foreignObject",true))
            }
        }
        assertNull(brands.find(NetworkDevices.Device("192.168.1.20","Living room",listOf("_googlecast._tcp."),false)))
        assertNull(brands.get("../../settings"))
        val customMac = NetworkDevices.Device("192.168.1.20", "Custom laptop name", listOf("_airplay._tcp."), false, listOf("MacBookPro18,2"))
        assertEquals("apple", brands.find(customMac)!!.id)
        assertEquals(NetworkDevicePresentation.Kind.COMPUTER, NetworkDevicePresentation.describe(customMac).kind)
        assertEquals("wiim",brands.find(NetworkDevices.Device("192.168.1.20","WiiM Living room",listOf("_linkplay._tcp."),false))!!.id)
    }
    @Test fun realCatalogAndLogoHandlersUseOnlyDiscoveryGrantAndRefuseRevocation() = runBlocking {
        app.settings.setOn("lan_discovery",true)
        val (initial,_) = app.agents.add("NetworkCatalog", Connection.DIRECT)
        app.agents.update(initial.id) { it.copy(caps=setOf("lan_discovery")) }
        val agent=app.agents.get(initial.id)!!
        val controller=Robolectric.buildService(BridgeService::class.java).create()
        val api=ApiServer(controller.get(),emptyList(),0);api.prepareRelay()
        suspend fun call(spec: cc.bridgit.app.api.EndpointSpec, path: Map<String,String> = emptyMap()) =
            api.dispatch(spec,agent,"100.64.0.2",emptyMap(),path,JSONObject(),null)
        try {
            val catalog=call(LanModule.CATALOG)
            assertEquals(String(catalog.bytes),200,catalog.status)
            assertEquals(DiscoveryCatalog.VERSION,JSONObject(String(catalog.bytes)).getInt("version"))
            val logo=call(LanModule.BRAND_LOGO,mapOf("id" to "tapo"))
            assertEquals(String(logo.bytes),200,logo.status)
            assertTrue(JSONObject(String(logo.bytes)).getString("data_base64").isNotBlank())
            assertEquals(404,call(LanModule.BRAND_LOGO,mapOf("id" to "../../settings")).status)
            app.agents.update(agent.id) { it.copy(caps=emptySet()) }
            assertEquals(403,call(LanModule.CATALOG).status)
            assertEquals(403,call(LanModule.REMEMBERED).status)
            app.agents.update(agent.id) { it.copy(caps=setOf("lan_discovery")) }
            app.settings.setOn("lan_discovery",false)
            assertEquals(403,call(LanModule.CATALOG).status)
            assertEquals(403,call(LanModule.REMEMBERED).status)
        } finally { api.stop();controller.destroy() }
    }
}
