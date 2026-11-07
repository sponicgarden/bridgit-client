package cc.bridgit.app.lan

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.zip.CRC32

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TpLinkDiscoveryTest {
    @Test fun queryIsABoundedReadOnlyProbeWithValidChecksumAndPublicKey() {
        val bytes = TpLinkDiscovery.query()
        val header = ByteBuffer.wrap(bytes)
        assertEquals(2, header.get(0).toInt())
        assertEquals(1, header.getShort(2).toInt())
        assertEquals(bytes.size - 16, header.getShort(4).toInt())
        val crc = header.getInt(12)
        header.putInt(12, 0x5A6B7C8D)
        assertEquals(crc, CRC32().apply { update(bytes) }.value.toInt())
        val payload = JSONObject(String(bytes, 16, bytes.size - 16, Charsets.UTF_8))
        val key = payload.getJSONObject("params").getString("rsa_key")
        assertTrue(key.startsWith("-----BEGIN PUBLIC KEY-----"))
        assertFalse(key.contains("PRIVATE"))
        assertEquals(setOf("params"), payload.keys().asSequence().toSet())
    }
    private fun packet(type: String, model: String) = ByteArray(16).also { it[0] = 2 } +
        JSONObject().put("error_code", 0).put("result", JSONObject().put("device_type", type)
            .put("device_model", model).put("encrypt_info", JSONObject().put("data", "PRIVATE-ACCOUNT-DATA")))
            .toString().toByteArray()
    @Test fun onlyPublicModelAndTypeBecomeHints() {
        val camera = TpLinkDiscovery.parse(packet("SMART.IPCAMERA", "C210"))!!
        assertEquals("Tapo C210", camera.name)
        assertEquals("tapo_camera", camera.service)
        assertFalse(camera.toString().contains("PRIVATE-ACCOUNT"))
        assertEquals("tplink_router", TpLinkDiscovery.parse(packet("HOMEWIFISYSTEM", "M4R"))!!.service)
        assertEquals("tplink_smart_device", TpLinkDiscovery.parse(packet("SMART.TAPOPLUG", "P110M"))!!.service)
    }
    @Test fun malformedPacketsDoNotBecomeDevices() {
        assertNull(TpLinkDiscovery.parse(ByteArray(16)))
        assertNull(TpLinkDiscovery.parse(ByteArray(8193)))
        assertNull(TpLinkDiscovery.parse(ByteArray(16).also { it[0] = 2 } + "not json".toByteArray()))
    }
    @Test fun catalogIsUniqueAndFitsTheScanDeadlineInBoundedBatches() {
        val types = DiscoveryCatalog.mdns.map { it.type }
        assertEquals(types.size, types.distinct().size)
        assertTrue(types.all { Regex("_[a-z0-9-]+\\._(tcp|udp)\\.").matches(it) })
        assertTrue(types.chunked(DiscoveryCatalog.BATCH_SIZE).size * DiscoveryCatalog.BATCH_MILLIS < 15_000)
        assertNotNull(DiscoveryCatalog.label("_linkplay._tcp."))
        assertNotNull(DiscoveryCatalog.label("_ipps._tcp."))
    }
}
