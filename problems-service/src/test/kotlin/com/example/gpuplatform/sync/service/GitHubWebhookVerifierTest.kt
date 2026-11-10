package com.example.gpuplatform.sync.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GitHubWebhookVerifierTest {

    private val verifier = GitHubWebhookVerifier()

    @Test
    fun `verifies a valid signature`() {
        val secret = "MySignature"
        val body = "Hello, World!".toByteArray(Charsets.UTF_8)
        val sig = computeSignature(secret, body)
        assertThat(verifier.verify(secret, "sha256=$sig", body)).isTrue()
    }

    @Test
    fun `matches the documented GitHub example`() {
        // Sanity check against an independently computed reference
        // HMAC-SHA256(key="MySignature", data="Hello, World!").
        val secret = "MySignature"
        val body = "Hello, World!".toByteArray(Charsets.UTF_8)
        assertThat(
            verifier.verify(
                secret,
                "sha256=639d930ee4b2367f0122e14cd011eeb4e19106489cfa89290e73167387ae1f16",
                body,
            )
        ).isTrue()
    }

    @Test
    fun `rejects a wrong signature`() {
        val secret = "MySignature"
        val body = "Hello, World!".toByteArray(Charsets.UTF_8)
        assertThat(verifier.verify(secret, "sha256=abcd", body)).isFalse()
    }

    @Test
    fun `rejects a signature with a different secret`() {
        val secret = "MySignature"
        val body = "Hello, World!".toByteArray(Charsets.UTF_8)
        val other = computeSignature("OtherSecret", body)
        assertThat(verifier.verify(secret, "sha256=$other", body)).isFalse()
    }

    @Test
    fun `rejects missing header or empty secret`() {
        val body = "data".toByteArray(Charsets.UTF_8)
        assertThat(verifier.verify("secret", null, body)).isFalse()
        assertThat(verifier.verify("", "sha256=0101", body)).isFalse()
    }

    @Test
    fun `rejects wrong prefix`() {
        val secret = "MySignature"
        val body = "Hello, World!".toByteArray(Charsets.UTF_8)
        val sig = computeSignature(secret, body)
        assertThat(verifier.verify(secret, "sha1=$sig", body)).isFalse()
    }

    private fun computeSignature(secret: String, body: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }
}