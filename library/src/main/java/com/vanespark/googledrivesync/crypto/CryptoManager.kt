package com.vanespark.googledrivesync.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-specific encryption using Android Keystore.
 *
 * This implementation provides hardware-backed encryption on supported devices,
 * where the key material never leaves the secure hardware.
 *
 * Note: Files encrypted with device keystore can ONLY be decrypted on the same device.
 * For cross-device portability, use [PassphraseBasedCrypto] instead.
 *
 * Security features:
 * - AES-256-GCM authenticated encryption
 * - Hardware-backed key storage (on supported devices)
 * - Random 96-bit IV per encryption
 * - 128-bit GCM authentication tag
 */
@Singleton
class CryptoManager @Inject constructor() {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "google_drive_sync_key"
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
        private const val IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        // Header for identifying device-encrypted files
        private val MAGIC_HEADER = byteArrayOf('D'.code.toByte(), 'K'.code.toByte(), 'E'.code.toByte(), 'Y'.code.toByte())
        private const val VERSION: Byte = 1
        private const val HEADER_SIZE = 4 + 1 + 12 // magic + version + iv
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)
        }
    }

    /**
     * Encrypt a file using device keystore.
     *
     * @param input Input file to encrypt
     * @param output Output file for encrypted data
     */
    fun encryptFile(input: File, output: File) {
        input.inputStream().use { inputStream ->
            output.outputStream().use { outputStream ->
                encrypt(inputStream, outputStream)
            }
        }
    }

    /**
     * Decrypt a file using device keystore.
     *
     * @param input Encrypted input file
     * @param output Output file for decrypted data
     * @throws DeviceKeyException if decryption fails
     */
    fun decryptFile(input: File, output: File) {
        input.inputStream().use { inputStream ->
            output.outputStream().use { outputStream ->
                decrypt(inputStream, outputStream)
            }
        }
    }

    /**
     * Encrypt data from an input stream to an output stream.
     *
     * @param input Input stream with plaintext data
     * @param output Output stream for encrypted data
     */
    fun encrypt(input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv

        // Write header
        output.write(MAGIC_HEADER)
        output.write(VERSION.toInt())
        output.write(iv)

        // Encrypt data
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            val encrypted = cipher.update(buffer, 0, bytesRead)
            if (encrypted != null) {
                output.write(encrypted)
            }
        }

        // Write final block with GCM tag
        val finalBlock = cipher.doFinal()
        output.write(finalBlock)
    }

    /**
     * Decrypt data from an input stream to an output stream.
     *
     * @param input Input stream with encrypted data
     * @param output Output stream for decrypted data
     * @throws DeviceKeyException if decryption fails
     */
    fun decrypt(input: InputStream, output: OutputStream) {
        // Read and validate header
        val header = ByteArray(4)
        if (input.read(header) != 4 || !header.contentEquals(MAGIC_HEADER)) {
            throw DeviceKeyException("Invalid file format: not a device-encrypted file")
        }

        val version = input.read()
        if (version != VERSION.toInt()) {
            throw DeviceKeyException("Unsupported file version: $version")
        }

        // Read IV
        val iv = ByteArray(IV_LENGTH_BYTES)
        if (input.read(iv) != IV_LENGTH_BYTES) {
            throw DeviceKeyException("Failed to read IV")
        }

        // Get key and decrypt
        val key = getKey() ?: throw DeviceKeyException("Encryption key not found")
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

        try {
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            // Read all encrypted data (including GCM tag)
            val encryptedData = input.readBytes()
            val decrypted = cipher.doFinal(encryptedData)
            output.write(decrypted)
        } catch (e: Exception) {
            throw DeviceKeyException("Decryption failed: ${e.message}", e)
        }
    }

    /**
     * Encrypt a byte array.
     *
     * @param data Data to encrypt
     * @return Encrypted data with header
     */
    fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data)

        // Build output: header + encrypted data
        val output = ByteBuffer.allocate(HEADER_SIZE + encryptedData.size)
        output.put(MAGIC_HEADER)
        output.put(VERSION)
        output.put(iv)
        output.put(encryptedData)

        return output.array()
    }

    /**
     * Decrypt a byte array.
     *
     * @param data Encrypted data with header
     * @return Decrypted data
     * @throws DeviceKeyException if decryption fails
     */
    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < HEADER_SIZE) {
            throw DeviceKeyException("Data too short to contain valid header")
        }

        val buffer = ByteBuffer.wrap(data)

        // Validate header
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC_HEADER)) {
            throw DeviceKeyException("Invalid file format: not a device-encrypted file")
        }

        val version = buffer.get()
        if (version != VERSION) {
            throw DeviceKeyException("Unsupported file version: $version")
        }

        // Read IV
        val iv = ByteArray(IV_LENGTH_BYTES)
        buffer.get(iv)

        // Read encrypted data
        val encryptedData = ByteArray(buffer.remaining())
        buffer.get(encryptedData)

        // Get key and decrypt
        val key = getKey() ?: throw DeviceKeyException("Encryption key not found")
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

        try {
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            return cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            throw DeviceKeyException("Decryption failed: ${e.message}", e)
        }
    }

    /**
     * Check if a file is encrypted with device keystore.
     *
     * @param file File to check
     * @return true if file has valid device encryption header
     */
    fun isDeviceEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < HEADER_SIZE) {
            return false
        }

        return file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(MAGIC_HEADER)
        }
    }

    /**
     * Check if data is encrypted with device keystore.
     *
     * @param data Data to check
     * @return true if data has valid device encryption header
     */
    fun isDeviceEncrypted(data: ByteArray): Boolean {
        if (data.size < HEADER_SIZE) {
            return false
        }
        return data.sliceArray(0 until 4).contentEquals(MAGIC_HEADER)
    }

    /**
     * Check if the encryption key exists.
     */
    fun hasKey(): Boolean = keyStore.containsAlias(KEY_ALIAS)

    /**
     * Delete the encryption key.
     *
     * Warning: This will make all previously encrypted data unrecoverable.
     */
    fun deleteKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    /**
     * Get existing key or create a new one.
     */
    private fun getOrCreateKey(): SecretKey {
        return getKey() ?: createKey()
    }

    /**
     * Get the existing encryption key.
     */
    private fun getKey(): SecretKey? {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey
    }

    /**
     * Create a new encryption key in the Android Keystore.
     */
    private fun createKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }
}

/**
 * Exception thrown when device keystore operations fail
 */
class DeviceKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
