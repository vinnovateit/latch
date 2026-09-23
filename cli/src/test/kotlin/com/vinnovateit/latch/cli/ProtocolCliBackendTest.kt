package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.runtime.INSTANCE_PROTOCOL_VERSION
import com.vinnovateit.latch.core.runtime.InstanceRequest
import com.vinnovateit.latch.core.runtime.InstanceResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `status()` used to be the one loose decoder in this file: a missing field
 * degraded into `owner: ""`, `connection: ""`, `latched: no` instead of a
 * recognized protocol error. These pin the strict contract directly against
 * [ProtocolCliBackend], without a real socket round trip.
 */
class ProtocolCliBackendTest {
    @Test
    fun `a well-formed status response decodes cleanly`() = runTestBackend { backend ->
        val result = backend.status()

        assertEquals(
            CliStatus(owner = "desktop", connection = "online", ssid = "VIT", latched = true),
            result.value,
        )
    }

    @Test
    fun `a missing owner is rejected`() = runTestBackend(data = statusData(owner = null)) { backend ->
        assertInvalidStatus(backend.status())
    }

    @Test
    fun `a blank owner is rejected`() = runTestBackend(data = statusData(owner = "  ")) { backend ->
        assertInvalidStatus(backend.status())
    }

    @Test
    fun `a missing connection is rejected`() = runTestBackend(data = statusData(connection = null)) { backend ->
        assertInvalidStatus(backend.status())
    }

    @Test
    fun `a blank connection is rejected`() = runTestBackend(data = statusData(connection = "  ")) { backend ->
        assertInvalidStatus(backend.status())
    }

    @Test
    fun `a missing latched flag is rejected`() = runTestBackend(data = statusData(latched = null)) { backend ->
        assertInvalidStatus(backend.status())
    }

    @Test
    fun `a non-boolean latched flag is rejected`() = runTestBackend(data = statusData(latched = "maybe")) { backend ->
        assertInvalidStatus(backend.status())
    }

    @Test
    fun `a missing ssid is accepted as no ssid`() = runTestBackend(data = statusData(ssid = null)) { backend ->
        val result = backend.status()

        assertTrue(result.error == null, "a missing ssid alone must not be treated as invalid status")
        assertEquals(null, result.value?.ssid)
    }

    @Test
    fun `an empty ssid is accepted as no ssid`() = runTestBackend(data = statusData(ssid = "")) { backend ->
        val result = backend.status()

        assertTrue(result.error == null)
        assertEquals(null, result.value?.ssid)
    }

    @Test
    fun `an owner-side error response still propagates normally`() = runTestBackend(
        response = { InstanceResponse(requestId = it.requestId, ok = false, code = "NO_WIFI", message = "Wi-Fi is unavailable.") },
    ) { backend ->
        val result = backend.status()

        assertEquals("Wi-Fi is unavailable.", result.error)
    }

    private fun assertInvalidStatus(result: OperationResult<CliStatus>) {
        assertEquals("The owner returned invalid status.", result.error)
    }

    private fun statusData(
        owner: String? = "desktop",
        connection: String? = "online",
        ssid: String? = "VIT",
        latched: String? = "true",
    ): Map<String, String> = buildMap {
        owner?.let { put("owner", it) }
        connection?.let { put("connection", it) }
        ssid?.let { put("ssid", it) }
        latched?.let { put("latched", it) }
    }

    private fun runTestBackend(
        data: Map<String, String> = statusData(),
        response: (InstanceRequest) -> InstanceResponse = { InstanceResponse(requestId = it.requestId, ok = true, code = "OK", data = data) },
        block: suspend (CliBackend) -> Unit,
    ) = runBlocking {
        val backend = ProtocolCliBackend { command, arguments ->
            val request = InstanceRequest(
                version = INSTANCE_PROTOCOL_VERSION,
                token = "unused",
                requestId = "request-1",
                command = command,
                arguments = arguments,
            )
            response(request)
        }
        block(backend)
    }
}
