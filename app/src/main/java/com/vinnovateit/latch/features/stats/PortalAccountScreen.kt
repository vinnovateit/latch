package com.vinnovateit.latch.features.stats

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.portal.PortalHistoryClient
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.platform.LatchAppGraph
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme

private const val PORTAL_HOST = PortalHistoryClient.DEFAULT_PORTAL_HOST

/**
 * Whether [url] is the captive portal itself.
 *
 * The portal is plain HTTP on a bare IP, so anything that redirects or spoofs
 * it would otherwise be handed the user's credentials by [onPageFinished].
 */
private fun isPortalUrl(url: String?): Boolean =
    url != null && runCatching { Uri.parse(url).host }.getOrNull() == PORTAL_HOST

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalAccountScreen(
    onBackPressed: () -> Unit
) {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current
    val backgroundColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.background

    var isWifiConnected by remember { mutableStateOf(LatchAppGraph.platform.wifi.isConnectedToWifi()) }
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var hasAutoSubmitted by remember { mutableStateOf(false) }

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
                    if (isWifiConnected) {
                        IconButton(
                            onClick = {
                                hasAutoSubmitted = false
                                webViewRef?.reload()
                            }
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
            if (!isWifiConnected) {
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
                        text = "The captive portal is a local intranet service and can only be accessed when connected to campus Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            isWifiConnected = LatchAppGraph.platform.wifi.isConnectedToWifi()
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
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    isLoading = true
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    // The WebView holds the user's credentials, so
                                    // it never leaves the portal. Anything else is
                                    // handed to the browser rather than silently
                                    // dropped, which would look like a dead link.
                                    val target = request?.url?.toString() ?: return true
                                    if (isPortalUrl(target)) return false
                                    LatchAppGraph.platform.systemActions.openUrl(target)
                                    return true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (!isPortalUrl(url)) return
                                    if (!hasAutoSubmitted && userId.isNotBlank() && password.isNotBlank()) {
                                        val escapedUser = userId.replace("\\", "\\\\").replace("'", "\\'")
                                        val escapedPass = password.replace("\\", "\\\\").replace("'", "\\'")
                                        val js = """
                                            (function() {
                                                var u = document.querySelector('input[name="loginUserId"]');
                                                var p = document.querySelector('input[name="loginPassword"]');
                                                var f = document.querySelector('form[name="chooseAuthForm"]');
                                                if (u && p && f) {
                                                    u.value = '$escapedUser';
                                                    p.value = '$escapedPass';
                                                    f.submit();
                                                    return true;
                                                }
                                                return false;
                                            })();
                                        """.trimIndent()
                                        view?.evaluateJavascript(js) { result ->
                                            if (result == "true") {
                                                hasAutoSubmitted = true
                                            }
                                        }
                                    }
                                }
                            }

                            loadUrl("http://$PORTAL_HOST/registration/Main.jsp?wispId=1")
                            webViewRef = this
                        }
                    }
                )

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
