package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.WifiPlatform
import com.vinnovateit.latch.core.wifi.AutoLoginManager
import com.vinnovateit.latch.core.wifi.HostelAuthenticator
import com.vinnovateit.latch.core.wifi.HostelAuthResult
import com.vinnovateit.latch.core.wifi.LoginResult

class LatchEngineHostelAuthenticator(
    private val loginManager: AutoLoginManager,
    private val credentials: CredentialStore,
    private val wifi: WifiPlatform,
    private val logger: Logger,
) : HostelAuthenticator {

    private companion object {
        const val TAG = "HostelAuthenticator"
    }

    override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
        val user = credentials.userId()
        val pass = credentials.password()
        if (user.isNullOrBlank() || pass.isNullOrBlank()) {
            logger.w(TAG, "Authentication skipped: credentials missing.")
            return HostelAuthResult.Failure("Missing saved credentials")
        }

        logger.d(TAG, "Attempting portal authentication...")
        val handle = networkHandle ?: wifi.activeHandle()

        var result = loginManager.attemptLogin(
            userId = user,
            password = pass,
            handle = handle,
            useAlternate = false,
            fallbackIp = wifi.gatewayIp(),
        )

        if (result is LoginResult.Failure) {
            val gw = wifi.gatewayIp()
            if (gw != null) {
                logger.d(TAG, "Retrying portal authentication with direct gateway IP...")
                result = loginManager.attemptLogin(
                    userId = user,
                    password = pass,
                    handle = handle,
                    useAlternate = false,
                    fallbackIp = gw,
                )
            }
        }

        return when (result) {
            is LoginResult.Success -> {
                logger.d(TAG, "Portal authentication returned success.")
                HostelAuthResult.Success
            }
            is LoginResult.Failure -> {
                logger.w(TAG, "Portal authentication returned failure.")
                HostelAuthResult.Failure("Portal rejected login")
            }
        }
    }
}
