package com.vinnovateit.latch.desktop.platform.windows

enum class WindowsConnectivityLevel {
    None,
    LocalAccess,
    ConstrainedInternetAccess,
    InternetAccess,
    Unknown;

    companion object {
        fun fromString(str: String?): WindowsConnectivityLevel = when (str?.trim()) {
            "None" -> None
            "LocalAccess" -> LocalAccess
            "ConstrainedInternetAccess" -> ConstrainedInternetAccess
            "InternetAccess" -> InternetAccess
            else -> Unknown
        }
    }
}

data class WindowsNetworkInfo(
    val adapterName: String?,
    val adapterUp: Boolean,
    val radioOn: Boolean?,
    val ssid: String?,
    val gateway: String?,
    val connectivityLevel: WindowsConnectivityLevel = WindowsConnectivityLevel.Unknown,
    val profileName: String? = null,
    val isWlan: Boolean = true,
)

data class WindowsNetworkIdentity(
    val ssid: String?,
    val adapterName: String?,
    val profileName: String?,
) {
    fun isSameNetwork(other: WindowsNetworkIdentity?): Boolean {
        if (other == null) return false
        if (!ssid.isNullOrEmpty() && !other.ssid.isNullOrEmpty()) {
            return ssid.equals(other.ssid, ignoreCase = true)
        }
        return adapterName == other.adapterName && profileName == other.profileName
    }
}
