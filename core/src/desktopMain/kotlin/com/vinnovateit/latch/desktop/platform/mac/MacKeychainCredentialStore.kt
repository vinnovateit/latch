package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class KeychainCredentials(val userId: String, val password: String)

/**
 * Portal credentials in the user's macOS Keychain, as one generic-password
 * item: service [SERVICE], account [ACCOUNT], holding the registration number
 * and password together so they can never be saved or cleared half at a time.
 *
 * There is no file and no fallback. If the Keychain refuses (locked, access
 * prompt dismissed), that is reported; nothing is written anywhere else.
 */
class MacKeychainCredentialStore internal constructor(
    private val keychain: KeychainItem,
    private val logger: Logger,
) : CredentialStore {

    constructor(logger: Logger) : this(
        LazyKeychainItem { SecurityFrameworkKeychainItem(SERVICE, ACCOUNT, LABEL) },
        logger,
    )

    internal companion object {
        /** Stable forever: changing either orphans every saved credential. */
        const val SERVICE = "com.vinnovateit.latch"
        const val ACCOUNT = "portal-credentials"
        const val LABEL = "Latch portal credentials"
        private const val TAG = "MacKeychainCredentialStore"
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cache: KeychainCredentials? = null

    override fun save(userId: String, password: String): Result<Unit> {
        val creds = KeychainCredentials(userId, password)
        val status = keychain.write(json.encodeToString(creds).toByteArray(Charsets.UTF_8))
        if (status != ERR_SEC_SUCCESS) {
            val message = "Could not save credentials to the Keychain: ${keychainStatusMessage(status)}."
            logger.e(TAG, message)
            return Result.failure(IllegalStateException(message))
        }
        cache = creds
        return Result.success(Unit)
    }

    private fun read(): KeychainCredentials? {
        cache?.let { return it }
        return when (val result = keychain.read()) {
            is KeychainItem.Read.Found -> runCatching {
                json.decodeFromString<KeychainCredentials>(result.secret.toString(Charsets.UTF_8))
            }.onFailure {
                // Not deleted: it is the user's Keychain item and may be repairable by hand.
                logger.e(TAG, "Keychain item $SERVICE/$ACCOUNT is not in the expected format; ignoring it")
            }.getOrNull()?.also { cache = it }
            KeychainItem.Read.Missing -> null
            is KeychainItem.Read.Failed -> {
                logger.e(TAG, "Could not read credentials from the Keychain: ${keychainStatusMessage(result.status)}")
                null
            }
        }
    }

    override fun userId(): String? = read()?.userId

    override fun password(): String? = read()?.password

    override fun exists(): Boolean = read() != null

    /** Succeeds only once the Keychain confirms no Latch item remains. */
    override fun clear(): Result<Unit> {
        cache = null
        val deleted = keychain.delete()
        val failure = when {
            deleted != ERR_SEC_SUCCESS -> keychainStatusMessage(deleted)
            else -> when (val check = keychain.read()) {
                KeychainItem.Read.Missing -> return Result.success(Unit)
                is KeychainItem.Read.Found -> "the Keychain item is still present"
                is KeychainItem.Read.Failed -> "could not confirm removal (${keychainStatusMessage(check.status)})"
            }
        }
        val message = "Could not remove credentials from the Keychain: $failure."
        logger.e(TAG, message)
        return Result.failure(IllegalStateException(message))
    }
}
