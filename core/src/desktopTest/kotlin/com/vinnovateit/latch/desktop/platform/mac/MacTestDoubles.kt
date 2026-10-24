package com.vinnovateit.latch.desktop.platform.mac

/** A probe whose answer the test changes between reads. */
internal class ScriptedProbe(@Volatile var reading: MacWifiReading) : MacWifiProbe {
    @Volatile var reads = 0
        private set

    override fun read(): MacWifiReading {
        reads++
        return reading
    }
}

/** Addresses per interface; an interface with no entry has no address. */
internal class ScriptedLinks(
    @Volatile var addresses: Map<String, String> = emptyMap(),
    @Volatile var gateway: String? = null,
) : MacLinkInspector {
    override fun ipv4Address(interfaceName: String): String? = addresses[interfaceName]
    override fun gateway(interfaceName: String): String? = gateway?.takeIf { interfaceName in addresses }
}

internal class RecordingPower(private val accept: Boolean = true) : MacWifiPower {
    val requests = mutableListOf<String>()
    override fun turnOn(interfaceName: String): Boolean {
        requests += interfaceName
        return accept
    }
}

internal fun on(ssid: String?, location: MacLocationAccess = MacLocationAccess.Granted, iface: String = "en0") =
    MacWifiReading.PoweredOn(iface, ssid, location)
