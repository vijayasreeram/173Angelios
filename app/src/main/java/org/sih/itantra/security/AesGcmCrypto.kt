package org.sih.itantra.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Military-grade AES-256 GCM Authenticated Encryption for local packet payloads and database storage.
 */
object AesGcmCrypto {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    // Master session key (or derived from device keystore)
    private val masterKeyBytes = byteArrayOf(
        0x49, 0x53, 0x52, 0x4F, 0x5F, 0x54, 0x41, 0x43,
        0x54, 0x49, 0x43, 0x41, 0x4C, 0x5F, 0x32, 0x30,
        0x32, 0x36, 0x5F, 0x69, 0x54, 0x41, 0x4E, 0x54,
        0x52, 0x41, 0x5F, 0x4B, 0x45, 0x59, 0x30, 0x31
    )
    private val secretKey: SecretKey = SecretKeySpec(masterKeyBytes, "AES")

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val ciphertext = cipher.doFinal(plaintext)
        // Output: [12B IV][Ciphertext + 16B Tag]
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    fun decrypt(encryptedBytes: ByteArray): ByteArray? {
        if (encryptedBytes.size < IV_LENGTH_BYTES + (TAG_LENGTH_BITS / 8)) return null
        return try {
            val iv = ByteArray(IV_LENGTH_BYTES)
            System.arraycopy(encryptedBytes, 0, iv, 0, iv.size)

            val ciphertextLength = encryptedBytes.size - IV_LENGTH_BYTES
            val ciphertext = ByteArray(ciphertextLength)
            System.arraycopy(encryptedBytes, IV_LENGTH_BYTES, ciphertext, 0, ciphertextLength)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }
}
