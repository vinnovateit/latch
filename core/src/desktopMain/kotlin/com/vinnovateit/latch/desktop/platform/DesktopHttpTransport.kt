package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.net.HttpURLConnection
import java.net.URL

/**
 * Desktop HTTP transport for Windows and Linux. macOS uses [BoundHttpTransport].
 *
 * Uses the OS default route. This mirrors how the Android app's attemptLogout
 * already worked (a plain url.openConnection() relying on the caller having bound
 * the process) and is correct on a single-homed laptop.
 *
 * KNOWN LIMITATION -- multi-homed machines. A laptop docked with both Ethernet
 * and Wi-Fi will send the generate_204 probe and the credential POST out
 * whichever interface holds the default route, which may not be the Wi-Fi one.
 * Symptom: a false "latched" state, or a login attempt that never reaches the
 * portal.
 *
 * The fix is a socket bound to the Wi-Fi interface's local address, as
 * [BoundHttpTransport] does on macOS. Windows picks the interface from the
 * source address too, and [WindowsWifiPlatform.wifiLocalAddress] already
 * provides the address it would need. Linux does not: its routing ignores
 * the source address unless policy routing is set up, so binding there would
 * send Wi-Fi-addressed packets out of the Ethernet port.
 */
class DesktopHttpTransport : HttpTransport {
    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection =
        url.openConnection() as HttpURLConnection
}
