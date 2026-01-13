package com.vanespark.googledrivesync.crypto

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified encryption manager that supports multiple encryption methods.
 *
 * Encryption modes:
 * - [EncryptionMode.NONE]: No encryption
 * - [EncryptionMode.DEVICE_KEYSTORE]: Hardware-backed encryption (device-specific)
 * - [EncryptionMode.PASSPHRASE]: Password-based encryption (cross-device portable)
 *
 * Usage:
 * ```kotlin
 * // Encrypt with passphrase (recommended for cloud backups)
 * encryptionManager.encryptFile(
 *     input = sourceFile,
 *     output = encryptedFile,
 *     config = EncryptionConfig.passphrase("my-secure-passphrase")
 * )
 *
 * // Decrypt (auto-detects encryption type)
 * encryptionManager.decryptFile(
 *     input = encryptedFile,
 *     output = decryptedFile,
 *     passphrase = "my-secure-passphrase" // Only needed for passphrase-encrypted files
 * )
 * ```
 */
@Singleton
class EncryptionManager @Inject constructor(
    private val passphraseBasedCrypto: PassphraseBasedCrypto,
    private val cryptoManager: CryptoManager
) {

    /**
     * Encrypt a file with the specified configuration.
     *
     * @param input Input file to encrypt
     * @param output Output file for encrypted data
     * @param config Encryption configuration
     */
    fun encryptFile(input: File, output: File, config: EncryptionConfig) {
        when (config) {
            is EncryptionConfig.None -> {
                input.copyTo(output, overwrite = true)
            }
            is EncryptionConfig.DeviceKeystore -> {
                cryptoManager.encryptFile(input, output)
            }
            is EncryptionConfig.Passphrase -> {
                passphraseBasedCrypto.encryptFile(input, output, config.passphrase)
            }
        }
    }

    /**
     * Decrypt a file, auto-detecting the encryption type.
     *
     * @param input Encrypted input file
     * @param output Output file for decrypted data
     * @param passphrase Passphrase for passphrase-encrypted files (optional)
     * @throws WrongPassphraseException if passphrase is incorrect
     * @throws DeviceKeyException if device key decryption fails
     * @throws CorruptedFileException if file format is invalid
     */
    fun decryptFile(input: File, output: File, passphrase: String? = null) {
        when (detectEncryptionType(input)) {
            EncryptionType.NONE -> {
                input.copyTo(output, overwrite = true)
            }
            EncryptionType.DEVICE_KEYSTORE -> {
                cryptoManager.decryptFile(input, output)
            }
            EncryptionType.PASSPHRASE -> {
                requireNotNull(passphrase) {
                    "Passphrase required for passphrase-encrypted files"
                }
                passphraseBasedCrypto.decryptFile(input, output, passphrase)
            }
        }
    }

    /**
     * Encrypt data with the specified configuration.
     *
     * @param data Data to encrypt
     * @param config Encryption configuration
     * @return Encrypted data (or original data if no encryption)
     */
    fun encrypt(data: ByteArray, config: EncryptionConfig): ByteArray {
        return when (config) {
            is EncryptionConfig.None -> data
            is EncryptionConfig.DeviceKeystore -> cryptoManager.encrypt(data)
            is EncryptionConfig.Passphrase -> passphraseBasedCrypto.encrypt(data, config.passphrase)
        }
    }

    /**
     * Decrypt data, auto-detecting the encryption type.
     *
     * @param data Encrypted data
     * @param passphrase Passphrase for passphrase-encrypted data (optional)
     * @return Decrypted data
     */
    fun decrypt(data: ByteArray, passphrase: String? = null): ByteArray {
        return when (detectEncryptionType(data)) {
            EncryptionType.NONE -> data
            EncryptionType.DEVICE_KEYSTORE -> cryptoManager.decrypt(data)
            EncryptionType.PASSPHRASE -> {
                requireNotNull(passphrase) {
                    "Passphrase required for passphrase-encrypted data"
                }
                passphraseBasedCrypto.decrypt(data, passphrase)
            }
        }
    }

    /**
     * Encrypt a stream with the specified configuration.
     *
     * @param input Input stream with plaintext data
     * @param output Output stream for encrypted data
     * @param config Encryption configuration
     */
    fun encrypt(input: InputStream, output: OutputStream, config: EncryptionConfig) {
        when (config) {
            is EncryptionConfig.None -> {
                input.copyTo(output)
            }
            is EncryptionConfig.DeviceKeystore -> {
                cryptoManager.encrypt(input, output)
            }
            is EncryptionConfig.Passphrase -> {
                passphraseBasedCrypto.encrypt(input, output, config.passphrase)
            }
        }
    }

    /**
     * Detect the encryption type of a file.
     *
     * @param file File to check
     * @return Detected encryption type
     */
    fun detectEncryptionType(file: File): EncryptionType {
        return when {
            passphraseBasedCrypto.isEncrypted(file) -> EncryptionType.PASSPHRASE
            cryptoManager.isDeviceEncrypted(file) -> EncryptionType.DEVICE_KEYSTORE
            else -> EncryptionType.NONE
        }
    }

    /**
     * Detect the encryption type of data.
     *
     * @param data Data to check
     * @return Detected encryption type
     */
    fun detectEncryptionType(data: ByteArray): EncryptionType {
        return when {
            passphraseBasedCrypto.isEncrypted(data) -> EncryptionType.PASSPHRASE
            cryptoManager.isDeviceEncrypted(data) -> EncryptionType.DEVICE_KEYSTORE
            else -> EncryptionType.NONE
        }
    }

    /**
     * Validate a passphrase for encryption.
     *
     * @param passphrase Passphrase to validate
     * @throws WeakPassphraseException if passphrase doesn't meet requirements
     */
    fun validatePassphrase(passphrase: String) {
        passphraseBasedCrypto.validatePassphrase(passphrase)
    }

    /**
     * Estimate passphrase strength.
     *
     * @param passphrase Passphrase to evaluate
     * @return Strength level
     */
    fun estimatePassphraseStrength(passphrase: String): PassphraseStrength {
        return passphraseBasedCrypto.estimateStrength(passphrase)
    }

    /**
     * Check if device keystore key exists.
     */
    fun hasDeviceKey(): Boolean = cryptoManager.hasKey()

    /**
     * Delete the device keystore key.
     *
     * Warning: This will make all device-encrypted data unrecoverable.
     */
    fun deleteDeviceKey() = cryptoManager.deleteKey()
}

/**
 * Encryption configuration sealed class
 */
sealed class EncryptionConfig {
    /**
     * No encryption
     */
    object None : EncryptionConfig()

    /**
     * Device keystore encryption (device-specific, hardware-backed)
     */
    object DeviceKeystore : EncryptionConfig()

    /**
     * Passphrase-based encryption (cross-device portable)
     */
    data class Passphrase(val passphrase: String) : EncryptionConfig()

    companion object {
        /**
         * Create passphrase encryption config
         */
        fun passphrase(passphrase: String) = Passphrase(passphrase)

        /**
         * Create device keystore encryption config
         */
        fun deviceKeystore() = DeviceKeystore

        /**
         * Create no encryption config
         */
        fun none() = None
    }
}

/**
 * Detected encryption type
 */
enum class EncryptionType {
    NONE,
    DEVICE_KEYSTORE,
    PASSPHRASE
}

/**
 * Encryption mode for configuration
 */
enum class EncryptionMode {
    /**
     * No encryption
     */
    NONE,

    /**
     * Device keystore encryption (device-specific)
     * Files can only be decrypted on the same device.
     */
    DEVICE_KEYSTORE,

    /**
     * Passphrase-based encryption (cross-device)
     * Files can be decrypted on any device with the correct passphrase.
     */
    PASSPHRASE
}
