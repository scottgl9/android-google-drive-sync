package com.vanespark.googledrivesync.crypto

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Password-based encryption using PBKDF2 key derivation and AES-GCM encryption.
 *
 * This implementation provides cross-device portable encryption, allowing files
 * encrypted on one device to be decrypted on any other device using the same passphrase.
 *
 * Security features:
 * - PBKDF2-HMAC-SHA256 with 100,000 iterations (OWASP minimum recommendation)
 * - AES-256-GCM authenticated encryption
 * - 128-bit cryptographically random salt per encryption
 * - 96-bit random IV per encryption
 * - 128-bit GCM authentication tag
 *
 * File format:
 * - 4 bytes: Magic header "PBKE"
 * - 1 byte: Version (currently 1)
 * - 16 bytes: Salt
 * - 12 bytes: IV
 * - N bytes: Encrypted data with GCM tag
 */
@Singleton
class PassphraseBasedCrypto @Inject constructor() {

    companion object {
        // File format constants
        private val MAGIC_HEADER = byteArrayOf('P'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 'E'.code.toByte())
        private const val VERSION: Byte = 1
        private const val HEADER_SIZE = 4 + 1 + 16 + 12 // magic + version + salt + iv

        // Cryptographic parameters
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
        private const val IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val ITERATION_COUNT = 100_000 // OWASP minimum recommendation

        // Passphrase validation
        private const val MIN_PASSPHRASE_LENGTH = 12
        private val WEAK_PASSPHRASES = setOf(
            "password1234",
            "123456789012",
            "qwertyuiopas",
            "abcdefghijkl"
        )

        // Encrypted file extension
        const val ENCRYPTED_EXTENSION = ".enc"
    }

    private val secureRandom = SecureRandom()

    /**
     * Encrypt a file with the given passphrase.
     *
     * @param input Input file to encrypt
     * @param output Output file for encrypted data
     * @param passphrase Passphrase for encryption (minimum 12 characters)
     * @throws WeakPassphraseException if passphrase doesn't meet requirements
     */
    fun encryptFile(input: File, output: File, passphrase: String) {
        validatePassphrase(passphrase)
        input.inputStream().use { inputStream ->
            output.outputStream().use { outputStream ->
                encrypt(inputStream, outputStream, passphrase)
            }
        }
    }

    /**
     * Decrypt a file with the given passphrase.
     *
     * @param input Encrypted input file
     * @param output Output file for decrypted data
     * @param passphrase Passphrase for decryption
     * @throws WrongPassphraseException if passphrase is incorrect
     * @throws CorruptedFileException if file format is invalid
     */
    fun decryptFile(input: File, output: File, passphrase: String) {
        input.inputStream().use { inputStream ->
            output.outputStream().use { outputStream ->
                decrypt(inputStream, outputStream, passphrase)
            }
        }
    }

    /**
     * Encrypt data from an input stream to an output stream.
     *
     * @param input Input stream with plaintext data
     * @param output Output stream for encrypted data
     * @param passphrase Passphrase for encryption
     */
    fun encrypt(input: InputStream, output: OutputStream, passphrase: String) {
        validatePassphrase(passphrase)

        // Generate random salt and IV
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val iv = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        secureRandom.nextBytes(iv)

        // Derive key from passphrase
        val key = deriveKey(passphrase, salt)

        // Write header
        output.write(MAGIC_HEADER)
        output.write(VERSION.toInt())
        output.write(salt)
        output.write(iv)

        // Encrypt data
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

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
     * @param passphrase Passphrase for decryption
     * @throws WrongPassphraseException if passphrase is incorrect
     * @throws CorruptedFileException if file format is invalid
     */
    fun decrypt(input: InputStream, output: OutputStream, passphrase: String) {
        // Read and validate header
        val header = ByteArray(4)
        if (input.read(header) != 4 || !header.contentEquals(MAGIC_HEADER)) {
            throw CorruptedFileException("Invalid file format: missing or incorrect magic header")
        }

        val version = input.read()
        if (version != VERSION.toInt()) {
            throw CorruptedFileException("Unsupported file version: $version")
        }

        // Read salt and IV
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val iv = ByteArray(IV_LENGTH_BYTES)
        if (input.read(salt) != SALT_LENGTH_BYTES) {
            throw CorruptedFileException("Failed to read salt")
        }
        if (input.read(iv) != IV_LENGTH_BYTES) {
            throw CorruptedFileException("Failed to read IV")
        }

        // Derive key from passphrase
        val key = deriveKey(passphrase, salt)

        // Decrypt data
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            // Read all encrypted data (including GCM tag)
            val encryptedData = input.readBytes()
            val decrypted = cipher.doFinal(encryptedData)
            output.write(decrypted)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassphraseException("Incorrect passphrase or corrupted data")
        } catch (e: javax.crypto.BadPaddingException) {
            throw WrongPassphraseException("Incorrect passphrase or corrupted data")
        }
    }

    /**
     * Encrypt a byte array with the given passphrase.
     *
     * @param data Data to encrypt
     * @param passphrase Passphrase for encryption
     * @return Encrypted data with header
     */
    fun encrypt(data: ByteArray, passphrase: String): ByteArray {
        validatePassphrase(passphrase)

        // Generate random salt and IV
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val iv = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        secureRandom.nextBytes(iv)

        // Derive key from passphrase
        val key = deriveKey(passphrase, salt)

        // Encrypt data
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        val encryptedData = cipher.doFinal(data)

        // Build output: header + encrypted data
        val output = ByteBuffer.allocate(HEADER_SIZE + encryptedData.size)
        output.put(MAGIC_HEADER)
        output.put(VERSION)
        output.put(salt)
        output.put(iv)
        output.put(encryptedData)

        return output.array()
    }

    /**
     * Decrypt a byte array with the given passphrase.
     *
     * @param data Encrypted data with header
     * @param passphrase Passphrase for decryption
     * @return Decrypted data
     * @throws WrongPassphraseException if passphrase is incorrect
     * @throws CorruptedFileException if data format is invalid
     */
    fun decrypt(data: ByteArray, passphrase: String): ByteArray {
        if (data.size < HEADER_SIZE) {
            throw CorruptedFileException("Data too short to contain valid header")
        }

        val buffer = ByteBuffer.wrap(data)

        // Validate header
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC_HEADER)) {
            throw CorruptedFileException("Invalid file format: missing or incorrect magic header")
        }

        val version = buffer.get()
        if (version != VERSION) {
            throw CorruptedFileException("Unsupported file version: $version")
        }

        // Read salt and IV
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val iv = ByteArray(IV_LENGTH_BYTES)
        buffer.get(salt)
        buffer.get(iv)

        // Read encrypted data
        val encryptedData = ByteArray(buffer.remaining())
        buffer.get(encryptedData)

        // Derive key and decrypt
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

        try {
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            return cipher.doFinal(encryptedData)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassphraseException("Incorrect passphrase or corrupted data")
        } catch (e: javax.crypto.BadPaddingException) {
            throw WrongPassphraseException("Incorrect passphrase or corrupted data")
        }
    }

    /**
     * Check if a file is encrypted with this format.
     *
     * @param file File to check
     * @return true if file has valid encryption header
     */
    fun isEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < HEADER_SIZE) {
            return false
        }

        return file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(MAGIC_HEADER)
        }
    }

    /**
     * Check if data is encrypted with this format.
     *
     * @param data Data to check
     * @return true if data has valid encryption header
     */
    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size < HEADER_SIZE) {
            return false
        }
        return data.sliceArray(0 until 4).contentEquals(MAGIC_HEADER)
    }

    /**
     * Validate passphrase strength.
     *
     * @param passphrase Passphrase to validate
     * @throws WeakPassphraseException if passphrase doesn't meet requirements
     */
    fun validatePassphrase(passphrase: String) {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            throw WeakPassphraseException(
                "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
            )
        }
        if (passphrase.lowercase() in WEAK_PASSPHRASES) {
            throw WeakPassphraseException("Passphrase is too common")
        }
    }

    /**
     * Estimate passphrase strength.
     *
     * @param passphrase Passphrase to evaluate
     * @return Strength level
     */
    fun estimateStrength(passphrase: String): PassphraseStrength {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            return PassphraseStrength.WEAK
        }
        if (passphrase.lowercase() in WEAK_PASSPHRASES) {
            return PassphraseStrength.WEAK
        }

        var score = 0

        // Length bonus
        score += when {
            passphrase.length >= 20 -> 2
            passphrase.length >= 16 -> 1
            else -> 0
        }

        // Character variety
        if (passphrase.any { it.isUpperCase() }) score++
        if (passphrase.any { it.isLowerCase() }) score++
        if (passphrase.any { it.isDigit() }) score++
        if (passphrase.any { !it.isLetterOrDigit() }) score++

        return when {
            score >= 6 -> PassphraseStrength.VERY_STRONG
            score >= 4 -> PassphraseStrength.STRONG
            score >= 2 -> PassphraseStrength.MEDIUM
            else -> PassphraseStrength.WEAK
        }
    }

    /**
     * Derive a secret key from passphrase and salt using PBKDF2.
     */
    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val spec = PBEKeySpec(
            passphrase.toCharArray(),
            salt,
            ITERATION_COUNT,
            KEY_LENGTH_BITS
        )
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, KEY_ALGORITHM)
    }
}

/**
 * Passphrase strength levels
 */
enum class PassphraseStrength {
    WEAK,
    MEDIUM,
    STRONG,
    VERY_STRONG
}

/**
 * Exception thrown when passphrase is too weak
 */
class WeakPassphraseException(message: String) : Exception(message)

/**
 * Exception thrown when passphrase is incorrect
 */
class WrongPassphraseException(message: String) : Exception(message)

/**
 * Exception thrown when encrypted file is corrupted
 */
class CorruptedFileException(message: String) : Exception(message)
