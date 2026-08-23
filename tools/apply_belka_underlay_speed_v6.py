#!/usr/bin/env python3
from pathlib import Path


def patch_file(path_str: str, replacements: list[tuple[str, str, str]]) -> None:
    path = Path(path_str)
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new, label in replacements:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{path_str}: {label}: expected exactly 1 match, found {count}")
        text = text.replace(old, new, 1)
    if text == original:
        raise SystemExit(f"{path_str}: no changes made")
    path.write_text(text, encoding="utf-8")


patch_file(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/HealthController.kt",
    [
        (
'''        private val FAST_GATE_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
        private const val FAST_GATE_TIMEOUT_MS = 2200
''',
'''        private val FAST_GATE_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
        private const val FAST_GATE_TIMEOUT_MS = 2200

        // If no VPN node is usable, diagnose the physical Wi-Fi/LTE link
        // directly, bypassing the VPN. A failed direct speed probe is treated
        // as an unstable/very slow Internet connection rather than a VPN-node
        // failure. 256 KiB is enough to classify a bad link without wasting
        // much mobile traffic.
        private const val UNDERLAY_SPEED_BYTES = 256 * 1024
        private const val UNDERLAY_SPEED_URL =
            "https://speed.cloudflare.com/__down?bytes=262144"
        private const val LOW_INTERNET_MBPS = 3.0
        private const val UNDERLAY_CONNECT_TIMEOUT_MS = 3000
        private const val UNDERLAY_READ_TIMEOUT_MS = 5000
''',
            "underlay constants",
        ),
        (
'''    private var lastSpeedTestAt = 0L
    private var cycleNumber = 0L
''',
'''    private var lastSpeedTestAt = 0L
    private var cycleNumber = 0L
    private var lowInternetMode = false
''',
            "low internet mode state",
        ),
        (
'''        BelkaVpnState.cycleStart(currentTag, now + HEALTH_INTERVAL_MS)
        Log.i(TAG, "CYCLE#$cycle START nodes=${plan.nodes.size}")

        val dcs = loadTelegramDcs() ?: run {
            Log.w(TAG, "CYCLE#$cycle Telegram bootstrap unavailable; selector unchanged")
            BelkaVpnState.finishNoEligible(currentTag, now + HEALTH_INTERVAL_MS)
            return
        }
''',
'''        BelkaVpnState.cycleStart(currentTag, now + HEALTH_INTERVAL_MS)
        Log.i(TAG, "CYCLE#$cycle START nodes=${plan.nodes.size}")

        // While the physical Internet is known to be bad, do not fan out into
        // Telegram/Instagram/Web probes every cycle. First re-check only the
        // underlay. Resume expensive VPN health checks after it recovers.
        if (lowInternetMode) {
            val underlaySpeed = probeUnderlaySpeed()
            if (isLowInternet(underlaySpeed)) {
                val label = underlaySpeed?.let { "${fmt1(it)} Mbps" } ?: "unmeasurable"
                Log.w(TAG, "CYCLE#$cycle LOW-INTERNET backoff: $label; VPN probes skipped")
                BelkaVpnState.finishLowInternet(
                    currentTag,
                    underlaySpeed,
                    now + HEALTH_INTERVAL_MS,
                )
                return
            }
            lowInternetMode = false
            Log.i(
                TAG,
                "CYCLE#$cycle underlay recovered: ${fmt1(underlaySpeed!!)} Mbps; VPN probes resumed",
            )
        }

        val dcs = loadTelegramDcs() ?: run {
            val nextCheckAt = System.currentTimeMillis() + HEALTH_INTERVAL_MS
            finishNoCandidate(
                cycle,
                currentTag,
                nextCheckAt,
                "Telegram bootstrap unavailable",
            )
            return
        }
''',
            "underlay backoff and bootstrap diagnosis",
        ),
        (
'''            Log.w(
                TAG,
                "CYCLE#$cycle DONE: no usable server; selector unchanged; elapsed=${elapsedMs(cycleStarted)}ms",
            )
            BelkaVpnState.finishNoEligible(currentTag, nextCheckAt)
            return
''',
'''            finishNoCandidate(
                cycle,
                currentTag,
                nextCheckAt,
                "no normal/degraded VPN candidates; elapsed=${elapsedMs(cycleStarted)}ms",
            )
            return
''',
            "no-candidate underlay diagnosis",
        ),
        (
'''    private fun hasUnderlyingNetwork(): Boolean {
''',
'''    private fun finishNoCandidate(
        cycle: Long,
        currentTag: String?,
        nextCheckAt: Long,
        reason: String,
    ) {
        val underlaySpeed = probeUnderlaySpeed()
        if (isLowInternet(underlaySpeed)) {
            lowInternetMode = true
            val label = underlaySpeed?.let { "${fmt1(it)} Mbps" } ?: "unmeasurable"
            Log.w(TAG, "CYCLE#$cycle LOW-INTERNET ($reason): $label; selector unchanged")
            BelkaVpnState.finishLowInternet(currentTag, underlaySpeed, nextCheckAt)
        } else {
            lowInternetMode = false
            Log.w(
                TAG,
                "CYCLE#$cycle VPN-SERVERS-UNAVAILABLE ($reason); " +
                    "underlay=${fmt1(underlaySpeed!!)} Mbps; selector unchanged",
            )
            BelkaVpnState.finishNoEligible(currentTag, nextCheckAt)
        }
    }

    private fun isLowInternet(speedMbps: Double?): Boolean =
        speedMbps == null || speedMbps < LOW_INTERNET_MBPS

    private fun probeUnderlaySpeed(): Double? {
        val network = physicalInternetNetwork() ?: return null
        return runCatching {
            val connection = network.openConnection(URL(UNDERLAY_SPEED_URL)) as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = UNDERLAY_CONNECT_TIMEOUT_MS
                connection.readTimeout = UNDERLAY_READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", UA)
                connection.setRequestProperty("Accept-Encoding", "identity")
                val code = connection.responseCode
                require(code in 200..299) { "underlay speed HTTP $code" }

                val started = System.nanoTime()
                var total = 0
                BufferedInputStream(connection.inputStream).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (total < UNDERLAY_SPEED_BYTES) {
                        val count = input.read(
                            buffer,
                            0,
                            minOf(buffer.size, UNDERLAY_SPEED_BYTES - total),
                        )
                        if (count < 0) break
                        total += count
                    }
                }
                require(total >= 64 * 1024) { "underlay speed short read: $total" }
                val seconds =
                    ((System.nanoTime() - started) / 1_000_000_000.0)
                        .coerceAtLeast(0.001)
                (total * 8.0 / 1_000_000.0) / seconds
            } finally {
                connection.disconnect()
            }
        }.onFailure {
            Log.w(TAG, "underlay speed probe failed: ${it.message}")
        }.getOrNull()
    }

    private fun physicalInternetNetwork(): android.net.Network? {
        val candidates = connectivityManager.allNetworks.filter { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@filter false
            val physical =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            physical && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return candidates.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }

    private fun hasUnderlyingNetwork(): Boolean {
''',
            "underlay speed helpers",
        ),
    ],
)

patch_file(
    "app/src/main/java/io/nekohasekai/sfa/bg/health/BelkaVpnState.kt",
    [
        (
'''    val noEligible: Boolean = false,
    val degraded: Boolean = false,
    val nodes: Map<String, BelkaNodeStatus> = emptyMap(),
''',
'''    val noEligible: Boolean = false,
    val degraded: Boolean = false,
    val lowInternet: Boolean = false,
    val underlaySpeedMbps: Double? = null,
    val nodes: Map<String, BelkaNodeStatus> = emptyMap(),
''',
            "UI underlay fields",
        ),
        (
'''                checking = true,
                noEligible = false,
                nextCheckAt = nextCheckAt,
''',
'''                checking = true,
                noEligible = false,
                lowInternet = false,
                underlaySpeedMbps = null,
                nextCheckAt = nextCheckAt,
''',
            "cycle resets underlay state",
        ),
        (
'''                noEligible = false,
                degraded = false,
                lastCheckAt = System.currentTimeMillis(),
''',
'''                noEligible = false,
                degraded = false,
                lowInternet = false,
                underlaySpeedMbps = null,
                lastCheckAt = System.currentTimeMillis(),
''',
            "selected resets low internet",
        ),
        (
'''                noEligible = false,
                degraded = true,
                lastCheckAt = System.currentTimeMillis(),
''',
'''                noEligible = false,
                degraded = true,
                lowInternet = false,
                underlaySpeedMbps = null,
                lastCheckAt = System.currentTimeMillis(),
''',
            "degraded resets low internet",
        ),
        (
'''    fun finishNoEligible(currentTag: String?, nextCheckAt: Long) {
        _state.update {
            it.copy(
                selectedTag = currentTag ?: it.selectedTag,
                checking = false,
                noEligible = true,
                degraded = false,
                lastCheckAt = System.currentTimeMillis(),
                nextCheckAt = nextCheckAt,
                lastAction = "Нет подходящего сервера",
            )
        }
    }

    fun countryLabel(tag: String?): String {
''',
'''    fun finishLowInternet(
        currentTag: String?,
        speedMbps: Double?,
        nextCheckAt: Long,
    ) {
        _state.update {
            it.copy(
                selectedTag = currentTag ?: it.selectedTag,
                checking = false,
                noEligible = false,
                degraded = false,
                lowInternet = true,
                underlaySpeedMbps = speedMbps,
                lastCheckAt = System.currentTimeMillis(),
                nextCheckAt = nextCheckAt,
                lastAction = speedMbps?.let {
                    "Низкая скорость интернета: ${String.format(Locale.US, "%.1f", it)} Мбит/с"
                } ?: "Низкая скорость / нестабильный интернет",
            )
        }
    }

    fun finishNoEligible(currentTag: String?, nextCheckAt: Long) {
        _state.update {
            it.copy(
                selectedTag = currentTag ?: it.selectedTag,
                checking = false,
                noEligible = true,
                degraded = false,
                lowInternet = false,
                underlaySpeedMbps = null,
                lastCheckAt = System.currentTimeMillis(),
                nextCheckAt = nextCheckAt,
                lastAction = "VPN-серверы недоступны",
            )
        }
    }

    fun countryLabel(tag: String?): String {
''',
            "low internet finish state",
        ),
    ],
)

patch_file(
    "app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardScreen.kt",
    [
        (
'''                            belkaVpnState.degraded && belkaVpnState.selectedTag != null ->
                                "🟠 ${BelkaVpnState.countryLabel(belkaVpnState.selectedTag)} · деградация"
                            belkaVpnState.noEligible -> "🔴 Нет подходящего VPN"
''',
'''                            belkaVpnState.lowInternet -> "🟠 Низкая скорость интернета"
                            belkaVpnState.degraded && belkaVpnState.selectedTag != null ->
                                "🟠 ${BelkaVpnState.countryLabel(belkaVpnState.selectedTag)} · деградация"
                            belkaVpnState.noEligible -> "🔴 VPN-серверы недоступны"
''',
            "dashboard low internet status",
        ),
    ],
)

patch_file(
    "app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/BelkaSmartStatusCard.kt",
    [
        (
'''                        vpnState.checking -> "🟡 проверка"
                        vpnState.degraded && vpnState.selectedTag != null -> "🟠 деградация"
                        vpnState.noEligible -> "🔴 нет кандидата"
''',
'''                        vpnState.checking -> "🟡 проверка"
                        vpnState.lowInternet -> "🟠 низкая скорость интернета"
                        vpnState.degraded && vpnState.selectedTag != null -> "🟠 деградация"
                        vpnState.noEligible -> "🔴 серверы недоступны"
''',
            "card low internet status",
        ),
        (
'''            val details = when {
                !vpnEnabled -> "VPN отключён"
                selected != null -> buildString {
''',
'''            val details = when {
                !vpnEnabled -> "VPN отключён"
                vpnState.lowInternet -> vpnState.underlaySpeedMbps?.let {
                    "Физический интернет ${formatSpeed(it)} · VPN health-check отложен"
                } ?: "Физический интернет нестабилен · speed-test не завершён"
                selected != null -> buildString {
''',
            "card low internet details",
        ),
        (
'''    val selected = state.selectedTag?.let(state.nodes::get)
        ?: return "Сервер ещё не выбран: ожидается первый завершённый health-check."

    if (state.noEligible) {
        return "Ни один сервер не прошёл даже минимальные условия fallback. Smart VPN не переключал маршрут и оставил последний выбранный сервер."
    }
''',
'''    if (state.lowInternet) {
        val speed = state.underlaySpeedMbps?.let(::formatSpeed) ?: "не определяется"
        return "VPN-кандидаты не прошли проверку, поэтому Smart VPN отдельно проверил физический Wi-Fi/LTE в обход VPN. Скорость: $speed. Причина классифицирована как низкая скорость или нестабильный интернет; повторные тяжёлые VPN-пробы отложены до восстановления канала."
    }

    if (state.noEligible) {
        return "Физический интернет работает с нормальной скоростью, но ни один VPN-сервер не прошёл даже минимальные условия fallback. Последний маршрут оставлен без автоматического переключения."
    }

    val selected = state.selectedTag?.let(state.nodes::get)
        ?: return "Сервер ещё не выбран: ожидается первый завершённый health-check."
''',
            "selection reason low internet",
        ),
        (
'''                        • Speed не является жёстким блокером, но влияет на рейтинг.
                        • Score: 40% Telegram + 20% Instagram + 25% Web + 15% штраф за скорость. Меньше — лучше.
''',
'''                        • Speed VPN-сервера не является жёстким блокером, но влияет на рейтинг.
                        • Если нет normal/degraded кандидата, отдельно измеряется физический Wi-Fi/LTE в обход VPN.
                        • Физическая скорость < 3 Мбит/с или незавершившийся прямой speed-test → «низкая скорость / нестабильный интернет», а не «нет кандидата».
                        • Пока физический канал плохой, следующие циклы делают только лёгкую underlay-проверку и не повторяют тяжёлые Telegram/Instagram/Web-пробы.
                        • Если физический интернет ≥ 3 Мбит/с, но VPN-кандидатов нет → «VPN-серверы недоступны».
                        • Score: 40% Telegram + 20% Instagram + 25% Web + 15% штраф за скорость. Меньше — лучше.
''',
            "dialog underlay logic",
        ),
    ],
)

print("BelkaVPN underlay speed v6 applied:")
print("- no-candidate triggers direct physical Wi-Fi/LTE speed diagnosis")
print("- <3 Mbps or failed direct speed test => low/unstable Internet")
print("- low-Internet backoff skips expensive VPN probes until recovery")
print("- normal underlay + no VPN candidate => VPN servers unavailable")
print("- dashboard/card show physical Internet diagnosis and measured speed")
