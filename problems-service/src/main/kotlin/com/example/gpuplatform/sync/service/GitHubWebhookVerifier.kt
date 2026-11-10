package com.example.gpuplatform.sync.service

import org.springframework.stereotype.Component
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies GitHub webhook signatures using the shared secret
 * (HMAC-SHA256 over the raw body, per GitHub's `X-Hub-Signature-256`).
 */
@Component
class GitHubWebhookVerifier {

    companion object {
        const val HEADER = "X-Hub-Signature-256"
        const val PREFIX = "sha256="
    }

    fun verify(secret: String, signatureHeader: String?, rawBody: ByteArray): Boolean {
        if (secret.isEmpty()) {
            return false
        }
        val expected = signatureHeader ?: return false
        if (!expected.startsWith(PREFIX)) {
            return false
        }
        val provided = expected.removePrefix(PREFIX)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val computed = mac.doFinal(rawBody)
        val providedBytes = try {
            hexToBytes(provided)
        } catch (e: Exception) {
            return false
        }
        return MessageDigestEqualUtils.isEqual(computed, providedBytes)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "invalid hex" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}

private object MessageDigestEqualUtils {
    fun isEqual(a: ByteArray, b: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(a, b)
}