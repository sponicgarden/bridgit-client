package cc.bridgit.app.clientcore

import cc.bridgit.app.core.profile.ProfileBuilder
import cc.bridgit.app.core.profile.SetupState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android profile builder against contracts/client-core/profile/android-target.json, which the pack
 * validates against schemas/device-profile.schema.json. Same facts in, the same document out.
 */
class ProfileTargetTest {
    private val facts = SetupState.Facts(deviceNamed = true, listenerBound = false, relayOn = true, agents = 1, emailVerified = false,
        emailPending = false, notificationsOn = false, sensorsWithheld = false, missingPermissions = listOf("android.permission.CAMERA"),
        batteryRestricted = true, diagnosticDone = true)

    @Test fun builderGivesTheTargetDocument() {
        val target = JSONObject(javaClass.getResource("/client-core/profile/android-target.json")!!.readText())
        val device = ProfileBuilder.Device("bgi_fixturetablet", null, "Fixture tablet", "tablet", "15", "1.8.0", 149, listOf("host"),
            mapOf("camera" to "present", "microphone" to "present", "speaker" to "present", "location" to "present"))
        val caps = listOf(
            ProfileBuilder.Capability("status", true, true, true, null, emptyList(), listOf("GET" to "/v1/health")),
            ProfileBuilder.Capability("camera", true, false, true, "permission:android.permission.CAMERA", ProfileBuilder.SENSOR_EXECUTION,
                listOf("POST" to "/v1/camera/snapshot")),
        )
        val built = ProfileBuilder.build(3, java.time.Instant.parse("2026-11-11T08:00:00.400Z").toEpochMilli(), device, true, false, caps, SetupState.json(facts))
        assertEquals(canon(target), canon(built))
    }

    @Test fun everyStateIdIsOneOfTheFour() {
        val all = listOf(facts, facts.copy(emailPending = true), facts.copy(agents = 0, deviceNamed = false, listenerBound = false, relayOn = false,
            sensorsWithheld = true, diagnosticDone = false))
        for (f in all) for (s in SetupState.steps(f)) assertTrue("${s.id}=${s.state}", s.state in cc.bridgit.app.core.contract.ContractVocab.SETUP_STATES)
        assertEquals("waiting", SetupState.steps(facts.copy(emailPending = true)).single { it.id == "owner_email" }.state)
    }

    /** A JSON value as text with keys sorted, so documents compare regardless of key order. */
    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canon(v.get(k)) }
        is org.json.JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is String -> JSONObject.quote(v)
        JSONObject.NULL, null -> "null"
        else -> v.toString()
    }
}
