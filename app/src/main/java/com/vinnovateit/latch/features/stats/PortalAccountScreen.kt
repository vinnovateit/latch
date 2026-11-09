package com.vinnovateit.latch.features.stats

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.portal.PortalHistoryClient
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.wifi.isKnownCampusNetwork
import com.vinnovateit.latch.platform.LatchAppGraph
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme
import org.json.JSONObject

private const val PORTAL_HOST = PortalHistoryClient.DEFAULT_PORTAL_HOST

/**
 * Whether [url] is the captive portal itself.
 *
 * The portal is plain HTTP on a bare IP, so anything that redirects or spoofs
 * it would otherwise be handed the user's credentials.
 */
private fun isPortalUrl(url: String?): Boolean =
    url != null && runCatching { Uri.parse(url).host }.getOrNull() == PORTAL_HOST

/**
 * Runs [action] with the page's login form as `f`, `u` and `p`, and evaluates to
 * true, only if the page is the portal and the form posts back to the portal.
 * Checked in the page itself because the page can change after it loads.
 */
private fun portalFormScript(action: String): String = """
    (function() {
        var f = document.querySelector('form[name="chooseAuthForm"]');
        var u = document.querySelector('input[name="loginUserId"]');
        var p = document.querySelector('input[name="loginPassword"]');
        if (!f || !u || !p || location.protocol !== 'http:' || location.hostname !== '$PORTAL_HOST') return false;
        var target = new URL(f.getAttribute('action') || location.href, location.href);
        if (target.protocol !== 'http:' || target.hostname !== '$PORTAL_HOST') return false;
        $action
        return true;
    })();
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalAccountScreen(
    onBackPressed: () -> Unit
) {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current
    val backgroundColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.background

    val wifi = remember { LatchAppGraph.platform.wifi }
    var isOnCampusWifi by remember { mutableStateOf(isKnownCampusNetwork(wifi)) }
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loginFormReady by remember { mutableStateOf(false) }

    // Runs before the WebView below is created, so its first request is on Wi-Fi.
    // Without this it follows the default network, which stays on mobile data
    // while a captive portal is unvalidated.
    val releaseWifiPin = remember(isOnCampusWifi) {
        if (isOnCampusWifi) wifi.activeHandle()?.let(wifi::pinProcess) else null
    }
    DisposableEffect(releaseWifiPin) {
        onDispose {
            releaseWifiPin?.invoke()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    val credentials = remember { LatchAppGraph.platform.credentials }
    val userId = if (credentials.exists()) credentials.userId() ?: "" else ""
    val password = if (credentials.exists()) credentials.password() ?: "" else ""

    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Manage Account",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (isOnCampusWifi) {
                        IconButton(
                            onClick = { webViewRef?.reload() }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Reload",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!isOnCampusWifi) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Campus Wi-Fi Required",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The captive portal is a local intranet service and can only be accessed when connected to campus Wi-Fi. Latch also needs Location access to confirm the network's name.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            isOnCampusWifi = isKnownCampusNetwork(wifi)
                        }
                    ) {
                        Text("Retry Connection")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                    loginFormReady = false
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    // The WebView holds the user's credentials, so
                                    // it never leaves the portal. A link the user
                                    // tapped goes to the browser rather than looking
                                    // dead; a page can't open anything by itself.
                                    val target = request?.url ?: return true
                                    if (isPortalUrl(target.toString())) return false
                                    if (request.hasGesture() && target.scheme in setOf("http", "https")) {
                                        LatchAppGraph.platform.systemActions.openUrl(target.toString())
                                    }
                                    return true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (!isPortalUrl(url)) return
                                    if (userId.isBlank() || password.isBlank()) return
                                    view?.evaluateJavascript(portalFormScript("")) { result ->
                                        loginFormReady = result == "true"
                                    }
                                }
                            }

                            loadUrl("http://$PORTAL_HOST/registration/Main.jsp?wispId=1")
                            webViewRef = this
                        }
                    }
                )

                if (loginFormReady) {
                    Button(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(24.dp),
                        onClick = {
                            val page = webViewRef ?: return@Button
                            if (!isPortalUrl(page.url)) return@Button
                            loginFormReady = false
                            page.evaluateJavascript(
                                portalFormScript(
                                    """
                                    u.value = ${JSONObject.quote(userId)};
                                    p.value = ${JSONObject.quote(password)};
                                    HTMLFormElement.prototype.submit.call(f);
                                    """.trimIndent()
                                ),
                                null,
                            )
                        }
                    ) {
                        Text(stringResource(R.string.portal_sign_in_saved))
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
