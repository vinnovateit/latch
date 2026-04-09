package com.vinnovateit.latch.common.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.vinnovateit.latch.features.wifi.manager.AutoLoginManager
import com.vinnovateit.latch.data.StoredCredentials
import com.vinnovateit.latch.features.wifi.manager.LoginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LoginTestRunner {

  @SuppressLint("ServiceCast")
  suspend fun run(context: Context) = withContext(Dispatchers.IO) {
    Log.d("LoginTest", "Running manual login test...")

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = cm.activeNetwork

    if (activeNetwork == null) {
      Log.d("LoginTest", "No active network.")
      return@withContext
    }

    val caps = cm.getNetworkCapabilities(activeNetwork)
    if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) != true) {
      Log.d("LoginTest", "Active network does not appear to be a captive portal.")
      return@withContext
    }

    Log.d("LoginTest", "Captive portal detected on active network. Proceeding to login...")

    val userId = StoredCredentials.getUserId(context)
    val password = StoredCredentials.getPassword(context)

    if (userId.isNullOrBlank() || password.isNullOrBlank()) {
      Log.d("LoginTest", "❌ Credentials are not set.")
      return@withContext
    }

    // Bind the process to ensure the login request goes over the correct network
    cm.bindProcessToNetwork(activeNetwork)
    val success = try {
      AutoLoginManager.attemptLogin(context, userId, password, activeNetwork)
    } finally {
      cm.bindProcessToNetwork(null) // Always unbind
    }

    if (success == LoginResult.Success) {
      Log.d("LoginTest", "✅ Manual login successful!")
    } else {
      Log.d("LoginTest", "❌ Manual login failed.")
    }
  }
}