package se.overforapk.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/** Håller Wi-Fi vaket under en överföring så att anslutningen inte stryps när skärmen eller strömspar slår till. */
object TransferLocks {
    fun hold(context: Context): AutoCloseable {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        val lock = runCatching {
            wifi?.createWifiLock(mode, "OverforAPK")?.also { it.setReferenceCounted(false); it.acquire() }
        }.getOrNull()
        return AutoCloseable { runCatching { if (lock?.isHeld == true) lock.release() } }
    }
}
