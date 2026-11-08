package cc.bridgit.app.lan.packs

import cc.bridgit.app.lan.DeviceInterfaces
import cc.bridgit.app.lan.NetworkDevices
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Base64

class DevicePackTest {
    private val key = Ed25519PrivateKeyParameters(SecureRandom())
    private val pub = Base64.getEncoder().encodeToString(key.generatePublicKey().encoded)

    private fun entry(bytes: ByteArray, version: String = "1.0.0", engine: Int = 1, enabled: Boolean = true) = JSONObject()
        .put("id", "upnp_av").put("version", version).put("engine", engine).put("title", "Speaker").put("label", "Speaker (UPnP)")
        .put("path", "upnp_av/$version.json").put("size", bytes.size).put("sha256", DevicePack.sha256(bytes)).put("enabled", enabled)
        .put("match", JSONObject().put("services_any", JSONArray(listOf("urn:schemas-upnp-org:service:AVTransport:"))))

    private fun envelope(payload: JSONObject, signer: Ed25519PrivateKeyParameters = key): String {
        val body = payload.toString().toByteArray()
        val s = Ed25519Signer().apply { init(true, signer); update(body, 0, body.size) }
        return JSONObject().put("format", DevicePack.ENVELOPE_FORMAT).put("key_id", DevicePack.KEY_ID)
            .put("payload", Base64.getEncoder().encodeToString(body)).put("signature", Base64.getEncoder().encodeToString(s.generateSignature())).toString()
    }
    private fun index(serial: Long, vararg entries: JSONObject, channel: String = "production") = JSONObject().put("format", DevicePack.INDEX_FORMAT)
        .put("channel", channel).put("serial", serial).put("published_at", "2026-11-07T00:00:00Z").put("packs", JSONArray(entries.toList()))

    private val source: ByteArray by lazy {
        val f = File("../tools/device-packs/upnp_av/1.0.0.json"); assumeTrue("pack source not in this checkout", f.isFile); f.readBytes()
    }

    @Test fun signedIndexIsCheckedForKeyChannelAndReplay() {
        val text = envelope(index(5, entry(source)))
        val i = DevicePack.readIndex(text, 5, publicKeyB64 = pub)
        assertEquals(5, i.serial); assertEquals("upnp_av", i.packs.single().id)
        assertThrows(DevicePack.Bad::class.java) { DevicePack.readIndex(text, 6, publicKeyB64 = pub) }
        assertThrows(DevicePack.Bad::class.java) { DevicePack.readIndex(text, 0) } // signed by another key than the app pins
        assertThrows(DevicePack.Bad::class.java) { DevicePack.readIndex(envelope(index(5, entry(source)), Ed25519PrivateKeyParameters(SecureRandom())), 0, publicKeyB64 = pub) }
        assertThrows(DevicePack.Bad::class.java) { DevicePack.readIndex(envelope(index(5, entry(source), channel = "test")), 0, publicKeyB64 = pub) }
        val tampered = JSONObject(text).put("payload", Base64.getEncoder().encodeToString(index(9, entry(source)).toString().toByteArray())).toString()
        assertThrows(DevicePack.Bad::class.java) { DevicePack.readIndex(tampered, 0, publicKeyB64 = pub) }
        assertThrows(DevicePack.Bad::class.java) { DevicePack.readIndex(envelope(index(5, entry(source).put("path", "../x/1.0.0.json"))), 0, publicKeyB64 = pub) }
    }

    @Test fun packFilesArePinnedByTheIndex() {
        val e = DevicePack.readIndex(envelope(index(1, entry(source))), 0, publicKeyB64 = pub).packs.single()
        val p = DevicePack.readPack(source, e)
        assertEquals("upnp_av", p.id); assertEquals(setOf("status", "play", "pause", "resume", "stop", "volume_get", "volume_set", "mute_set"), p.actions.map { it.name }.toSet())
        val changed = source.copyOf().also { it[it.size - 2] = ' '.code.toByte() }
        assertThrows(DevicePack.Bad::class.java) { DevicePack.readPack(changed, e) }
        val other = DevicePack.readIndex(envelope(index(1, entry(source, version = "1.0.1"))), 0, publicKeyB64 = pub).packs.single()
        assertThrows(DevicePack.Bad::class.java) { DevicePack.readPack(source, other) }
    }

    @Test fun versionsAndSupport() {
        assertTrue(DevicePack.compareVersions("1.10.0", "1.9.9") > 0); assertEquals(0, DevicePack.compareVersions("1.2", "1.2.0"))
        assertTrue(DevicePack.compareVersions("2", "10") < 0)
        val e = DevicePack.readIndex(envelope(index(1, entry(source))), 0, publicKeyB64 = pub).packs.single()
        assertNull(DevicePack.unsupported(e, 100))
        assertNotNull(DevicePack.unsupported(e.copy(engine = PackEngine.VERSION + 1), 100))
        assertNotNull(DevicePack.unsupported(e.copy(enabled = false), 100))
        assertNotNull(DevicePack.unsupported(e.copy(minAppVersionCode = 101), 100))
    }

    @Test fun packContentIsValidated() {
        val good = JSONObject(String(source))
        fun bad(change: (JSONObject) -> Unit) = assertThrows(DevicePack.Bad::class.java) { DevicePack.parsePack(JSONObject(good.toString()).also(change).toString()) }
        bad { it.getJSONObject("actions").remove("status") }
        bad { it.put("transport", "http") }
        bad { it.getJSONObject("actions").getJSONObject("pause").getJSONArray("steps").getJSONObject(0).put("service", "power") }
        bad { it.getJSONObject("actions").getJSONObject("stop").getJSONArray("steps").getJSONObject(0).getJSONArray("args").put(JSONArray(listOf("X", "{secret}"))) }
        bad { it.getJSONObject("actions").getJSONObject("stop").getJSONArray("steps").getJSONObject(0).put("action", "Stop<x/>") }
        bad { it.getJSONObject("describe").put("device_type", "MediaRenderer") }
    }

    @Test fun discoveredSpeakersMatchAPack() {
        val e = DevicePack.readIndex(envelope(index(1, entry(source))), 0, publicKeyB64 = pub).packs.single()
        val speaker = NetworkDevices.Device("192.168.1.64", "Kitchen", listOf("_linkplay._tcp.", "urn:schemas-upnp-org:service:AVTransport:1"), false)
        assertEquals("upnp_av", DeviceInterfaces.available(speaker, listOf(e))?.adapter)
        assertNull(DeviceInterfaces.available(speaker.copy(services = listOf("_linkplay._tcp.")), listOf(e)))
        assertNull(DeviceInterfaces.available(speaker, emptyList()))
    }
}
