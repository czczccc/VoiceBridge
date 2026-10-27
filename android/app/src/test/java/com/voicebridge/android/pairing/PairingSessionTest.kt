package com.voicebridge.android.pairing

import com.voicebridge.android.protocol.Body
import com.voicebridge.android.protocol.Message
import com.voicebridge.android.protocol.PairStatus
import com.voicebridge.android.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// The fingerprint the client saw during the TLS handshake.
private val SERVER_FINGERPRINT = "SHA256:" + "0123456789abcdef".repeat(4)
private val SERVER_NONCE = "B".repeat(43)
private val PAIRING_TOKEN = "C".repeat(43)
private const val SERVER_DEVICE_ID = "windows-device"
private const val SERVER_NAME = "DESKTOP"
private const val PAIRING_ID = "pairing-1"
private const val TTL_MS = 120_000L

class PairingSessionTest {
    private var clock = 1_000L
    private val sent = mutableListOf<Body>()

    private fun session(
        observedFingerprint: String = SERVER_FINGERPRINT,
        windowsDeviceId: String = SERVER_DEVICE_ID,
    ) = PairingSession(
        windowsDeviceId = windowsDeviceId,
        observedFingerprint = observedFingerprint,
        deviceId = "android-device",
        deviceName = "Pixel",
        clientVersion = "0.1.0-test.1",
        send = { bytes -> sent += Protocol.decode(bytes).body },
        now = { clock },
    )

    private fun serverMessage(body: Body): ByteArray =
        Protocol.encode(Message(1, "server-${sent.size}", clock, body))

    private fun challenge(
        pairingId: String = PAIRING_ID,
        certFingerprint: String = SERVER_FINGERPRINT,
        serverNonce: String = SERVER_NONCE,
        serverDeviceId: String = SERVER_DEVICE_ID,
        expiresAt: Long = clock + TTL_MS,
    ): ByteArray = serverMessage(
        Body.PairChallenge(pairingId, serverDeviceId, SERVER_NAME, serverNonce, certFingerprint, expiresAt),
    )

    private fun pairedResult(): ByteArray = serverMessage(
        Body.PairResult(PAIRING_ID, PairStatus.Paired, PAIRING_TOKEN, SERVER_DEVICE_ID, SERVER_NAME),
    )

    @Test
    fun happyPathShowsTheCodeAndKeepsTheToken() {
        val session = session()
        session.start()
        assertEquals(PairingState.Idle, session.state.value)

        val request = sent.single() as Body.PairRequest
        assertEquals("android-device", request.deviceId)
        assertEquals("Pixel", request.deviceName)
        assertEquals("0.1.0-test.1", request.clientVersion)
        assertEquals(43, request.clientNonce.length)

        session.onMessage(challenge())
        val awaiting = session.state.value as PairingState.AwaitingCode
        assertEquals(sasCode(SERVER_FINGERPRINT, request.clientNonce, SERVER_NONCE), awaiting.code)
        assertTrue(awaiting.code, Regex("[0-9]{6}").matches(awaiting.code))

        session.confirm()
        session.confirm()
        val confirmation = sent[1] as Body.PairConfirm
        assertEquals(PAIRING_ID, confirmation.pairingId)
        assertTrue(confirmation.confirmed)
        assertEquals(2, sent.size)

        session.onMessage(pairedResult())
        val paired = session.state.value as PairingState.Paired
        assertEquals(PAIRING_TOKEN, paired.token)
        assertEquals(SERVER_DEVICE_ID, paired.serverDeviceId)
        assertEquals(SERVER_NAME, paired.serverName)
        assertFalse(paired.toString(), paired.toString().contains(PAIRING_TOKEN))
    }

    @Test
    fun fingerprintMismatchNeverShowsACode() {
        val session = session(observedFingerprint = "SHA256:" + "f".repeat(64))
        session.start()
        session.onMessage(challenge())
        assertTrue(session.state.value is PairingState.Rejected)
        // Only pair.request went out: no confirmation is sent to a peer that relayed the exchange.
        assertEquals(1, sent.size)
    }

    @Test
    fun expiredChallengeIsRejectedWithoutACode() {
        val session = session()
        session.start()
        clock = 121_000
        session.onMessage(challenge(expiresAt = clock))
        assertTrue(session.state.value is PairingState.Rejected)
        assertEquals(1, sent.size)
    }

    @Test
    fun pairedResultWithoutTokenFailsClosed() {
        val session = session()
        session.start()
        session.onMessage(challenge())
        // Protocol.decode already rejects `paired` with no token; the session must fail, not hang.
        val raw = """
            {"v":1,"type":"pair.result","messageId":"server-2","sentAt":$clock,
             "pairingId":"$PAIRING_ID","status":"paired",
             "serverDeviceId":"$SERVER_DEVICE_ID","serverName":"$SERVER_NAME"}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        session.onMessage(raw)
        assertTrue(session.state.value is PairingState.Failed)
    }

    @Test
    fun duplicateResultIsIgnored() {
        val session = session()
        session.start()
        session.onMessage(challenge())
        session.confirm()
        session.onMessage(pairedResult())
        val paired = session.state.value
        session.onMessage(serverMessage(Body.PairResult(PAIRING_ID, PairStatus.Rejected, null, SERVER_DEVICE_ID, SERVER_NAME)))
        assertSame(paired, session.state.value)
    }

    @Test
    fun resultForAnotherPairingIdIsIgnored() {
        val session = session()
        session.start()
        session.onMessage(challenge())
        session.onMessage(serverMessage(Body.PairResult("pairing-2", PairStatus.Rejected, null, SERVER_DEVICE_ID, SERVER_NAME)))
        assertTrue(session.state.value is PairingState.AwaitingCode)
    }

    @Test
    fun confirmBeforeAChallengeSendsNothing() {
        val session = session()
        session.confirm()
        assertTrue(sent.isEmpty())
        assertEquals(PairingState.Idle, session.state.value)
    }

    @Test
    fun rejectSendsUnconfirmedAndStopsTheSession() {
        val session = session()
        session.start()
        session.onMessage(challenge())
        session.reject()

        val confirmation = sent[1] as Body.PairConfirm
        assertEquals(PAIRING_ID, confirmation.pairingId)
        assertFalse(confirmation.confirmed)
        assertTrue(session.state.value is PairingState.Rejected)

        session.onMessage(pairedResult())
        assertTrue(session.state.value is PairingState.Rejected)
        assertEquals(2, sent.size)
    }

    @Test
    fun secondChallengeFailsTheSession() {
        val session = session()
        session.start()
        session.onMessage(challenge())
        session.onMessage(challenge(pairingId = "pairing-2"))
        assertTrue(session.state.value is PairingState.Failed)
    }

    @Test
    fun challengeFromAnotherDeviceFailsTheSession() {
        val session = session(windowsDeviceId = "some-other-device")
        session.start()
        session.onMessage(challenge())
        assertTrue(session.state.value is PairingState.Failed)
    }

    @Test
    fun malformedMessageFailsInsteadOfHanging() {
        val session = session()
        session.start()
        session.onMessage("not json".toByteArray(Charsets.UTF_8))
        assertTrue(session.state.value is PairingState.Failed)
    }

    @Test
    fun everyNonPairedStatusGivesItsOwnReason() {
        val reasons = mutableSetOf<String>()
        for (status in listOf(PairStatus.Rejected, PairStatus.Expired, PairStatus.Mismatch, PairStatus.Busy)) {
            val session = session()
            session.start()
            session.onMessage(challenge())
            session.onMessage(serverMessage(Body.PairResult(PAIRING_ID, status, null, SERVER_DEVICE_ID, SERVER_NAME)))
            val rejected = session.state.value as PairingState.Rejected
            assertTrue(rejected.reason.isNotBlank())
            reasons += rejected.reason
        }
        assertEquals(4, reasons.size)
    }
}
