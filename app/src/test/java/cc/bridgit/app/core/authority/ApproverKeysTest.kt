package cc.bridgit.app.core.authority

import cc.bridgit.app.relay.Sealed
import cc.bridgit.app.security.BoundOwnerCheck
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec

class ApproverKeysTest {
    private fun caps(api: Int = 34, lock: Boolean = true, strongBox: Boolean = false, biometric: Boolean = false) =
        ApproverCapabilities(api, lock, strongBox, biometric, ApproverCapability.credentialAllowed(api))

    @Test fun probeDecisionTable() {
        val ready = ApproverReadiness.READY
        val noLock = ApproverReadiness.NO_SCREEN_LOCK
        val unsupported = ApproverReadiness.PLATFORM_UNSUPPORTED
        val rows = listOf(
            caps(api = 28, biometric = true) to unsupported,
            caps(api = 28, lock = false) to unsupported,
            caps(api = 29, lock = false, biometric = false) to noLock,
            caps(api = 29, biometric = false) to unsupported,
            caps(api = 29, biometric = true) to ready,
            caps(api = 29, biometric = true, strongBox = true) to ready,
            caps(api = 30, lock = false) to noLock,
            caps(api = 30, biometric = false) to ready,
            caps(api = 30, biometric = true) to ready,
            caps(api = 35, biometric = false, strongBox = true) to ready,
            caps(api = 35, lock = false, biometric = true, strongBox = true) to noLock,
            // Whatever the reader derived, the field decides: no per-use key for the screen lock, no biometric.
            ApproverCapabilities(34, secureLockScreen = true, strongBox = true, strongBiometric = false, perUseCredentialKeys = false) to unsupported,
        )
        for ((c, want) in rows) assertEquals(c.toString(), want, ApproverCapability.decide(c))
        assertEquals(listOf("ready", "no_screen_lock", "platform_unsupported"), ApproverReadiness.values().map { it.id })
        // The two refusals are the contract pack's error codes (contracts/client-core/errors.json).
        val codes = cc.bridgit.app.core.contract.ErrorCatalogData.ENTRIES.map { it.code }
        for (r in listOf(noLock, unsupported)) assertTrue(r.id, r.id in codes)
    }

    @Test fun screenLockIsOfferedOnlyWhereThePerUseKeyTakesIt() {
        val strong = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
        val credential = androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(strong, BoundOwnerCheck.authenticators(29))
        assertEquals(strong or credential, BoundOwnerCheck.authenticators(30))
        assertEquals(strong or credential, BoundOwnerCheck.authenticators(35))
    }

    private val self = "bgi_operating"
    private val digest = MessageDigest.getInstance("SHA-256").digest("record".toByteArray())
    private fun record(purpose: String = "approval_decision") = OwnerAuth.Record(purpose, digest, "nonce-1", 7, 2_000)
    private fun otherSpki() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded

    private fun state(keys: ApproverKeys, pastWait: Boolean = true) =
        OwnerAuth.State(self, listOf(OwnerAuth.Approver("bgi_approver", keys.approverKeySpki()!!, otherSpki(), pastWait)), nowMs = 1_000)

    /** Signs what an approver device signs for [r]: both steps, as [BoundOwnerCheck] runs them around the prompt. */
    private fun approve(keys: ApproverKeys, r: OwnerAuth.Record, purpose: String = r.purpose, decision: String = OwnerAuth.APPROVE): OwnerAuth.Answer {
        val bytes = OwnerAuth.bytes(purpose, decision, self, "bgi_approver", r.digest, r.nonce, r.generation, r.expiresAtMs)
        return OwnerAuth.Answer(OwnerAuth.LABEL, purpose, decision, self, "bgi_approver", keys.beginSign(bytes).finish())
    }

    @Test fun softwareKeySignsAnApprovalThatOwnerAuthAccepts() {
        val keys = SoftwareApproverKeys()
        assertEquals(ApproverKeys.State.ABSENT, keys.state())
        assertNull(keys.approverKeySpki())
        assertThrows(ApproverKeys.NeedsSetup::class.java) { keys.beginSign(ByteArray(1)) }

        val made = keys.create(byteArrayOf(1, 2, 3))
        assertEquals(ApproverKeys.State.READY, keys.state())
        assertArrayEquals(made.approverKeySpki, keys.approverKeySpki())
        assertArrayEquals(byteArrayOf(1, 2, 3), keys.challenge)
        Sealed.validatePublic(made.sealingPublic)
        assertEquals(made.sealingPublic, keys.sealingPublic())

        val purpose = cc.bridgit.app.core.contract.ContractVocab.OWNER_AUTH_PURPOSES.first { !it.proposed }.id
        val r = record(purpose)
        val answer = approve(keys, r)
        assertEquals(64, answer.signatureP1363.size)
        assertEquals(OwnerAuth.Result.Approved("bgi_approver"), OwnerAuth.verify(r, answer, state(keys)))
    }

    @Test fun aSignatureDoesNotCarryToAnotherRecordPurposeOrDecision() {
        val keys = SoftwareApproverKeys().apply { create(ByteArray(16)) }
        val purposes = cc.bridgit.app.core.contract.ContractVocab.OWNER_AUTH_PURPOSES.filter { !it.proposed }.map { it.id }
        val r = record(purposes[0])
        val good = approve(keys, r)
        fun refused(reason: OwnerAuth.Reason) = OwnerAuth.Result.Refused(reason)

        // Another record (another digest), under the same key.
        val other = OwnerAuth.Record(r.purpose, MessageDigest.getInstance("SHA-256").digest("other".toByteArray()), r.nonce, r.generation, r.expiresAtMs)
        assertEquals(refused(OwnerAuth.Reason.BAD_SIGNATURE), OwnerAuth.verify(other, good, state(keys)))
        // Signed for another purpose, presented as this one.
        val crossed = approve(keys, r, purpose = purposes[1])
        val relabelled = OwnerAuth.Answer(crossed.label, r.purpose, crossed.decision, crossed.target, crossed.approver, crossed.signatureP1363)
        assertEquals(refused(OwnerAuth.Reason.BAD_SIGNATURE), OwnerAuth.verify(r, relabelled, state(keys)))
        assertEquals(refused(OwnerAuth.Reason.PURPOSE_MISMATCH), OwnerAuth.verify(r, crossed, state(keys)))
        // A decline is never under the approver key, and never an approval.
        val decline = approve(keys, r, decision = OwnerAuth.DECLINE)
        assertEquals(refused(OwnerAuth.Reason.BAD_SIGNATURE), OwnerAuth.verify(r, decline, state(keys)))
        val asApproval = OwnerAuth.Answer(decline.label, decline.purpose, OwnerAuth.APPROVE, decline.target, decline.approver, decline.signatureP1363)
        assertEquals(refused(OwnerAuth.Reason.BAD_SIGNATURE), OwnerAuth.verify(r, asApproval, state(keys)))
        // A flipped bit.
        val bent = OwnerAuth.Answer(good.label, good.purpose, good.decision, good.target, good.approver, good.signatureP1363.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() })
        assertEquals(refused(OwnerAuth.Reason.BAD_SIGNATURE), OwnerAuth.verify(r, bent, state(keys)))
        // Still in its wait.
        assertEquals(refused(OwnerAuth.Reason.APPROVER_WAITING), OwnerAuth.verify(r, good, state(keys, pastWait = false)))
    }

    @Test fun aLockChangeLeavesNeedsSetupAndANewKeyIsADifferentKey() {
        val keys = SoftwareApproverKeys()
        val first = keys.create(ByteArray(16))
        val r = record(cc.bridgit.app.core.contract.ContractVocab.OWNER_AUTH_PURPOSES.first { !it.proposed }.id)
        val enrolled = state(keys)
        val before = approve(keys, r)

        keys.invalidate()
        assertEquals(ApproverKeys.State.NEEDS_SETUP, keys.state())
        assertThrows(ApproverKeys.NeedsSetup::class.java) { keys.beginSign(ByteArray(1)) }

        val second = keys.create(ByteArray(16))
        assertEquals(ApproverKeys.State.READY, keys.state())
        assertNotEquals(first.approverKeySpki.toList(), second.approverKeySpki.toList())
        assertNotEquals(first.sealingPublic, second.sealingPublic)
        // The operating device still lists the old key: the new key's signature is not an approval there.
        assertEquals(OwnerAuth.Result.Approved("bgi_approver"), OwnerAuth.verify(r, before, enrolled))
        assertEquals(OwnerAuth.Result.Refused(OwnerAuth.Reason.BAD_SIGNATURE), OwnerAuth.verify(r, approve(keys, r), enrolled))

        keys.clear()
        assertEquals(ApproverKeys.State.ABSENT, keys.state())
    }

    @Test fun aDeviceThatCannotBeAnApproverMakesNoKey() {
        for (why in listOf(ApproverReadiness.NO_SCREEN_LOCK, ApproverReadiness.PLATFORM_UNSUPPORTED)) {
            val keys = SoftwareApproverKeys(why)
            assertEquals(why, assertThrows(ApproverKeys.Unavailable::class.java) { keys.create(ByteArray(16)) }.readiness)
            assertEquals(ApproverKeys.State.ABSENT, keys.state())
        }
    }

    @Test fun messageKeyNeedsNoApproverKeyAndOutlivesIt() {
        val operating = SoftwareApproverKeys(ApproverReadiness.NO_SCREEN_LOCK)
        val approver = SoftwareApproverKeys().apply { create(ByteArray(16)) }
        val aad = "request".toByteArray()

        // An operating device with no screen lock still has a message key; a request opens with no owner check.
        val envelope = Sealed.seal(operating.messageKey(), approver.messagePublic(), "show this".toByteArray(), aad)
        assertArrayEquals("show this".toByteArray(), Sealed.open(approver.messageKey(), operating.messagePublic(), envelope, aad))

        val public = approver.messagePublic()
        approver.invalidate()
        assertEquals(public, approver.messagePublic())
        approver.clear()
        assertEquals(public, approver.messagePublic())
        approver.resetMessageKey()
        assertNotEquals(public, approver.messagePublic())
        // The message key is not the sealing key: it never seals a secret.
        assertNotEquals(approver.create(ByteArray(16)).sealingPublic, approver.messagePublic())
    }

    @Test fun changingTheCallersBufferWhileThePromptIsOpenDoesNotChangeWhatIsSigned() {
        val keys = SoftwareApproverKeys().apply { create(ByteArray(16)) }
        val r = record(cc.bridgit.app.core.contract.ContractVocab.OWNER_AUTH_PURPOSES.first { !it.proposed }.id)
        val buffer = OwnerAuth.bytes(r.purpose, OwnerAuth.APPROVE, self, "bgi_approver", r.digest, r.nonce, r.generation, r.expiresAtMs)
        val shown = buffer.copyOf()

        val pending = keys.beginSign(buffer) // the prompt opens here
        buffer.fill(0x5a) // the caller reuses its buffer while the owner decides
        val signature = pending.finish()

        assertTrue(OwnerAuth.verifies(keys.approverKeySpki()!!, shown, signature))
        assertFalse(OwnerAuth.verifies(keys.approverKeySpki()!!, buffer, signature))
        val answer = OwnerAuth.Answer(OwnerAuth.LABEL, r.purpose, OwnerAuth.APPROVE, self, "bgi_approver", signature)
        assertEquals(OwnerAuth.Result.Approved("bgi_approver"), OwnerAuth.verify(r, answer, state(keys)))
    }

    @Test fun onlyAHardwareKeyWithAHardwareEnforcedEveryUseCheckIsAccepted() {
        fun p(h: ApproverKeys.Hardware, required: Boolean = true, inHardware: Boolean = true, everyUse: Boolean = true) =
            ApproverKeys.Protection(h, required, inHardware, everyUse)
        assertTrue(p(ApproverKeys.Hardware.TEE).acceptable)
        assertTrue(p(ApproverKeys.Hardware.STRONGBOX).acceptable)
        assertFalse(p(ApproverKeys.Hardware.SOFTWARE).acceptable)
        for (h in listOf(ApproverKeys.Hardware.TEE, ApproverKeys.Hardware.STRONGBOX)) {
            assertFalse(p(h, required = false).acceptable)
            assertFalse(p(h, inHardware = false).acceptable)
            assertFalse(p(h, everyUse = false).acceptable)
        }
        // The fake says what it is, so nothing can mistake it for a hardware key.
        val made = SoftwareApproverKeys().create(ByteArray(16))
        assertFalse(made.approverKey.acceptable)
        assertFalse(made.sealingWrapKey.acceptable)
        assertFalse(made.strongBox)
    }
}
