package com.vinnovateit.latch.desktop.platform.mac

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** One Keychain item's secret bytes. Every failure carries the OSStatus, never the secret. */
internal interface KeychainItem {
    sealed interface Read {
        class Found(val secret: ByteArray) : Read
        data object Missing : Read
        data class Failed(val status: Int) : Read
    }

    fun read(): Read

    /** Creates the item or replaces its secret; 0 (errSecSuccess) or the failing OSStatus. */
    fun write(secret: ByteArray): Int

    /** Removes the item; 0 when it is gone, including when it never existed. */
    fun delete(): Int
}

/**
 * Defers loading the native Keychain binding to first use, and turns a
 * failure to load it into an ordinary Keychain failure rather than a crash
 * while the platform is being assembled.
 */
internal class LazyKeychainItem(create: () -> KeychainItem) : KeychainItem {
    private val delegate: Result<KeychainItem> by lazy { runCatching(create) }

    override fun read(): KeychainItem.Read =
        delegate.fold({ it.read() }, { KeychainItem.Read.Failed(ERR_SEC_NOT_AVAILABLE) })

    override fun write(secret: ByteArray): Int = delegate.fold({ it.write(secret) }, { ERR_SEC_NOT_AVAILABLE })

    override fun delete(): Int = delegate.fold({ it.delete() }, { ERR_SEC_NOT_AVAILABLE })
}

internal const val ERR_SEC_SUCCESS = 0
internal const val ERR_SEC_NOT_AVAILABLE = -25291
internal const val ERR_SEC_ITEM_NOT_FOUND = -25300
internal const val ERR_SEC_DUPLICATE_ITEM = -25299

/** A short, secret-free explanation of the OSStatus values a user can act on. */
internal fun keychainStatusMessage(status: Int): String = when (status) {
    -25308 -> "the Keychain is locked or cannot ask for permission right now (errSecInteractionNotAllowed)"
    -128 -> "the Keychain access prompt was cancelled (errSecUserCanceled)"
    -25293 -> "Keychain authorization failed (errSecAuthFailed)"
    ERR_SEC_NOT_AVAILABLE -> "no Keychain is available (errSecNotAvailable)"
    -61 -> "Keychain write permission denied (errSecWrPerm)"
    else -> "Keychain error $status"
}

/**
 * A generic-password item in the user's login Keychain, through the SecItem
 * API of Security.framework.
 *
 * Called in-process rather than through `/usr/bin/security`, which can only
 * take a new password as a command-line argument -- visible to every user on
 * the machine in `ps` while it runs.
 *
 * Loading this class loads CoreFoundation and Security, so it is only ever
 * constructed on macOS.
 */
internal class SecurityFrameworkKeychainItem(
    private val service: String,
    private val account: String,
    private val label: String = service,
) : KeychainItem {

    private val cf = NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    private val sec = NativeLibrary.getInstance("/System/Library/Frameworks/Security.framework/Security")

    private fun cfFunction(name: String): Function = cf.getFunction(name)
    private fun secFunction(name: String): Function = sec.getFunction(name)

    /** The value of an exported `const CFStringRef` (or other CF object) symbol. */
    private fun cfConstant(library: NativeLibrary, symbol: String): Pointer =
        library.getGlobalVariableAddress(symbol).getPointer(0)

    private val kSecClass = cfConstant(sec, "kSecClass")
    private val kSecClassGenericPassword = cfConstant(sec, "kSecClassGenericPassword")
    private val kSecAttrService = cfConstant(sec, "kSecAttrService")
    private val kSecAttrAccount = cfConstant(sec, "kSecAttrAccount")
    private val kSecAttrLabel = cfConstant(sec, "kSecAttrLabel")
    private val kSecValueData = cfConstant(sec, "kSecValueData")
    private val kSecReturnData = cfConstant(sec, "kSecReturnData")
    private val kSecMatchLimit = cfConstant(sec, "kSecMatchLimit")
    private val kSecMatchLimitOne = cfConstant(sec, "kSecMatchLimitOne")
    private val kCFBooleanTrue = cfConstant(cf, "kCFBooleanTrue")
    private val keyCallBacks = cf.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")
    private val valueCallBacks = cf.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")

    private val kCFStringEncodingUTF8 = 0x08000100

    /** CF objects created for one call, released together however the call ends. */
    private inner class Scope : AutoCloseable {
        private val owned = mutableListOf<Pointer>()

        fun own(ref: Pointer?): Pointer = (ref ?: error("CoreFoundation allocation failed")).also { owned += it }

        fun string(value: String): Pointer {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val buffer = Memory(maxOf(1, bytes.size).toLong()).apply { write(0, bytes, 0, bytes.size) }
            return own(
                cfFunction("CFStringCreateWithBytes").invokePointer(
                    arrayOf(null, buffer, NativeLong(bytes.size.toLong()), kCFStringEncodingUTF8, 0.toByte()),
                ),
            )
        }

        fun data(value: ByteArray): Pointer {
            val buffer = Memory(maxOf(1, value.size).toLong()).apply { write(0, value, 0, value.size) }
            try {
                return own(cfFunction("CFDataCreate").invokePointer(arrayOf(null, buffer, NativeLong(value.size.toLong()))))
            } finally {
                buffer.clear()
            }
        }

        fun dictionary(vararg entries: Pair<Pointer, Pointer>): Pointer {
            val dict = own(
                cfFunction("CFDictionaryCreateMutable").invokePointer(
                    arrayOf(null, NativeLong(entries.size.toLong()), keyCallBacks, valueCallBacks),
                ),
            )
            entries.forEach { (k, v) -> cfFunction("CFDictionarySetValue").invokeVoid(arrayOf(dict, k, v)) }
            return dict
        }

        override fun close() {
            owned.asReversed().forEach { cfFunction("CFRelease").invokeVoid(arrayOf(it)) }
        }
    }

    private fun Scope.identity(): Array<Pair<Pointer, Pointer>> = arrayOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to string(service),
        kSecAttrAccount to string(account),
    )

    override fun read(): KeychainItem.Read = Scope().use { scope ->
        val query = scope.dictionary(
            *scope.identity(),
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        val result = PointerByReference()
        when (val status = secFunction("SecItemCopyMatching").invokeInt(arrayOf(query, result))) {
            ERR_SEC_SUCCESS -> {
                val data = scope.own(result.value)
                val length = cfFunction("CFDataGetLength").invokeLong(arrayOf(data)).toInt()
                val bytes = cfFunction("CFDataGetBytePtr").invokePointer(arrayOf(data))
                    ?.getByteArray(0, length) ?: ByteArray(0)
                KeychainItem.Read.Found(bytes)
            }
            ERR_SEC_ITEM_NOT_FOUND -> KeychainItem.Read.Missing
            else -> KeychainItem.Read.Failed(status)
        }
    }

    /**
     * Update first, add if there is nothing to update. If another process
     * added the item in between, the add reports a duplicate and the update
     * is retried once, so the write still lands rather than failing.
     */
    override fun write(secret: ByteArray): Int = Scope().use { scope ->
        val query = scope.dictionary(*scope.identity())
        val value = scope.data(secret)
        val changes = scope.dictionary(kSecValueData to value)
        var status = secFunction("SecItemUpdate").invokeInt(arrayOf(query, changes))
        if (status == ERR_SEC_ITEM_NOT_FOUND) {
            val item = scope.dictionary(*scope.identity(), kSecAttrLabel to scope.string(label), kSecValueData to value)
            status = secFunction("SecItemAdd").invokeInt(arrayOf(item, null))
            if (status == ERR_SEC_DUPLICATE_ITEM) {
                status = secFunction("SecItemUpdate").invokeInt(arrayOf(query, changes))
            }
        }
        status
    }

    override fun delete(): Int = Scope().use { scope ->
        when (val status = secFunction("SecItemDelete").invokeInt(arrayOf(scope.dictionary(*scope.identity())))) {
            ERR_SEC_ITEM_NOT_FOUND -> ERR_SEC_SUCCESS
            else -> status
        }
    }
}
