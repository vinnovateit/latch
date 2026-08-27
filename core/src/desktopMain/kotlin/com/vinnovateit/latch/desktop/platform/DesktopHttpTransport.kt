package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.net.HttpURLConnection
import java.net.URL

/**
 * Desktop HTTP transport.
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
 * The fix is a socket bound to the Wi-Fi interface's local address. It is not
 * implemented here because HttpURLConnection exposes no SocketFactory hook (only
 * HttpsURLConnection does), so it requires either OkHttp or a hand-rolled
 * request over a bound Socket. [WindowsWifiPlatform.wifiLocalAddress] already
 * provides the address that work would need, and this interface is shaped so the
 * change lands here without touching AutoLoginManager.
 */
class DesktopHttpTransport : HttpTransport {
    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection =
        url.openConnection() as HttpURLConnection
}
