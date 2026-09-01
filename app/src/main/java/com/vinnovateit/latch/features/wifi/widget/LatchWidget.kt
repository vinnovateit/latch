package com.vinnovateit.latch.features.wifi.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.vinnovateit.latch.features.home.MainActivity
import com.vinnovateit.latch.R
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import com.vinnovateit.latch.platform.LatchAppGraph
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.glance.ColorFilter
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme

private const val WIDGET_CORNER_RADIUS = 28

@Serializable
data class LatchWidgetState(
  val status: String = "Disconnected",
  val connectedDuration: String = "-",
  val isConnected: Boolean = false,
  val isLightTheme: Boolean = true,  // Flag for theme mode
  val useDynamicColors: Boolean = true,
  val accentColor: String = "Red"
)

class LatchWidget : GlanceAppWidget() {


  override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent {
      val stateJson = currentState<Preferences>()[LatchWidgetUpdater.WIDGET_STATE_PREF_KEY] ?: "{}"
      val state = try {
        Json.decodeFromString<LatchWidgetState>(stateJson)
      } catch (e: Exception) {
        LatchWidgetState() // Fallback on error
      }

      LatchWidgetContent(state)
    }
  }
}

@Composable
private fun LatchWidgetContent(state: LatchWidgetState) {
  val context = LocalContext.current
  val useDynamicColors = state.useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

  val seedColor = when (state.accentColor) {
    "Blue" -> Color(0xFF005AC1)
    "Green" -> Color(0xFF0F5223)
    "Purple" -> Color(0xFF7D00B8)
    "Pink" -> Color(0xFFD81B60)
    else -> Color(0xFFC01221) // Red
  }

  val colors = if (useDynamicColors) {
    ColorProviders(
      light = dynamicLightColorScheme(context),
      dark = dynamicDarkColorScheme(context)
    )
  } else {
    ColorProviders(
      light = dynamicColorScheme(seedColor = seedColor, isDark = false),
      dark = dynamicColorScheme(seedColor = seedColor, isDark = true)
    )
  }

  val size = LocalSize.current
  val isNarrow = size.width < 180.dp
  val padding = if (isNarrow) 12.dp else 16.dp
  val statusFontSize = if (isNarrow) 18.sp else 22.sp
  val buttonFontSize = if (isNarrow) 16.sp else 18.sp
  val durationFontSize = if (isNarrow) 12.sp else 14.sp

  GlanceTheme(colors = colors) {
    Column(
      modifier = GlanceModifier
        .fillMaxSize()
        .background(GlanceTheme.colors.background)
        .cornerRadius(WIDGET_CORNER_RADIUS.dp)
        .padding(padding)
        .clickable(actionStartActivity<MainActivity>()),
      verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = GlanceModifier.defaultWeight()) {
            Text(text = state.status, style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = statusFontSize, fontWeight = FontWeight.Bold))
          }
          Image(
            ImageProvider(R.drawable.ic_latch),
            contentDescription = "App Logo",
            modifier = GlanceModifier.size(40.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary)
          )
        }

        Spacer(modifier = GlanceModifier.height(16.dp))

        if (state.isConnected) {
          Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(modifier = GlanceModifier.background(GlanceTheme.colors.primary).cornerRadius(10.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
              Text(text = state.connectedDuration, style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = durationFontSize))
            }
          }
        }

        Spacer(modifier = GlanceModifier.defaultWeight())
        Box(
          modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.primary)
            .cornerRadius(16.dp)
            .padding(vertical = 12.dp)
            .clickable(actionRunCallback<ConnectAction>()),
          contentAlignment = Alignment.Center
        ) {
          val buttonText = if (state.isConnected) "Disconnect" else "Connect"
          Text(
            text = buttonText,
            style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = buttonFontSize, fontWeight = FontWeight.Bold)
          )
        }
      }
    }
}

class ConnectAction : ActionCallback {
    override suspend fun onAction(
      context: Context,
      glanceId: GlanceId,
      parameters: ActionParameters
    ) {
      val prefs = getAppWidgetState(
        context,
        glanceId = glanceId,
        definition = PreferencesGlanceStateDefinition,
      )

      val stateJson = prefs[LatchWidgetUpdater.WIDGET_STATE_PREF_KEY] ?: "{}"
      val state = try {
        Json.decodeFromString<LatchWidgetState>(stateJson)
      } catch (e: Exception) {
        LatchWidgetState()
      }

      val wifi = LatchAppGraph.platform.wifi

      if (!state.isConnected && (!wifi.isWifiEnabled() || !wifi.isConnectedToWifi())) {
          val wifiIntent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK
          }
          context.startActivity(wifiIntent)
          return
      }

      val intent = Intent(context, ForegroundService::class.java).apply {
        action = if (state.isConnected) {
          ForegroundService.ACTION_TRIGGER_LOGOUT
        } else {
          ForegroundService.ACTION_TRIGGER_LOGIN_CHECK
        }
      }
      context.startService(intent)

      // Give the service a moment to act before updating the widget
      delay(1500)
      LatchWidgetUpdater.enqueueOneTimeUpdate(context)
    }
  }