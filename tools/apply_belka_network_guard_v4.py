#!/usr/bin/env python3
from pathlib import Path

path = Path("app/src/main/java/io/nekohasekai/sfa/bg/health/HealthController.kt")
text = path.read_text(encoding="utf-8")
original = text


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
'''import android.content.Context
import android.util.Log
''',
'''import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
''',
    "network imports",
)

replace_once(
'''    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
''',
'''    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
''',
    "connectivity manager",
)

replace_once(
'''            while (isActive) {
                try {
                    runCycle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "health cycle failed: ${e.message}", e)
                }
                delay(HEALTH_INTERVAL_MS)
            }
''',
'''            while (isActive) {
                // Wi-Fi/mobile handovers briefly leave Android without an
                // underlying default interface. Do not turn that into a VPN
                // server failure; wait a few seconds and retry instead.
                if (!hasUnderlyingNetwork()) {
                    Log.i(TAG, "NETWORK TRANSITION: no underlying interface; health paused")
                    delay(5_000)
                    continue
                }

                try {
                    runCycle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "health cycle failed: ${e.message}", e)
                }
                delay(HEALTH_INTERVAL_MS)
            }
''',
    "pause health during network transition",
)

replace_once(
'''    private fun updateCircuit(row: NodeResult, cycle: Long): Long {
        // Quarantine only a truly dead transport. A node with working
''',
'''    private fun updateCircuit(row: NodeResult, cycle: Long): Long {
        // A missing/changing Android underlay is not a server failure. This
        // specifically protects Wi-Fi <-> cellular transitions from opening
        // the circuit or triggering a failover/quarantine.
        if (!hasUnderlyingNetwork()) {
            Log.i(TAG, "CYCLE#$cycle ${row.tag}: NETWORK TRANSITION; failure not counted")
            return 0L
        }

        // Quarantine only a truly dead transport. A node with working
''',
    "do not count network transition failures",
)

replace_once(
'''    private fun median(values: List<Double>): Double? {
''',
'''    private fun hasUnderlyingNetwork(): Boolean {
        return runCatching {
            connectivityManager.allNetworks.any { network ->
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
                val physical =
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                physical && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }.getOrDefault(true)
    }

    private fun median(values: List<Double>): Double? {
''',
    "underlying network helper",
)

if text == original:
    raise SystemExit("No changes made")

path.write_text(text, encoding="utf-8")
print("BelkaVPN network guard v4 applied:")
print("- pauses health while Android has no physical underlay")
print("- Wi-Fi/cellular handovers do not count as server failures")
print("- no quarantine/failover caused by missing default interface")
