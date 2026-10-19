package app.monogatari.android.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object DownloadConnectivity {
    fun isWifi(context:Context):Boolean {
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network=manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
    }
}
