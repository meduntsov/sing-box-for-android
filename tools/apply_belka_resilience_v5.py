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
'''        private const val FAST_GATE_URL = "https://www.gstatic.com/generate_204"
        private const val FAST_GATE_TIMEOUT_MS = 2500
''',
'''        private val FAST_GATE_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        )
        private const val FAST_GATE_TIMEOUT_MS = 2200
''',
    "dual fast gate constants",
)

replace_once(
'''    private fun probeFastGate(node: HealthConfigPatcher.Node): Double {
        val encodedTag = URLEncoder.encode(node.tag, "UTF-8").replace("+", "%20")
        val encodedUrl = URLEncoder.encode(FAST_GATE_URL, "UTF-8").replace("+", "%20")
        val endpoint =
            "http://127.0.0.1:${plan.clashPort}/proxies/$encodedTag/delay" +
                "?timeout=$FAST_GATE_TIMEOUT_MS&url=$encodedUrl"
        val started = System.nanoTime()
        val connection = localClash(endpoint)
        connection.requestMethod = "GET"

        return try {
            val code = connection.responseCode
            val body = runCatching {
                (if (code >= 400) connection.errorStream else connection.inputStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
            }.getOrDefault("")
            require(code in 200..299) {
                "Clash delay HTTP $code${if (body.isBlank()) "" else ": $body"}"
            }

            val measured = (System.nanoTime() - started) / 1_000_000.0
            runCatching { JSONObject(body).optDouble("delay", measured) }
                .getOrDefault(measured)
                .takeIf { it > 0.0 }
                ?: measured
        } finally {
            connection.disconnect()
        }
    }
''',
'''    private fun probeFastGate(node: HealthConfigPatcher.Node): Double {
        var best: Double? = null
        val failures = mutableListOf<String>()

        for (url in FAST_GATE_URLS) {
            val delay = runCatching {
                clashDelay(node.tag, url, FAST_GATE_TIMEOUT_MS)
            }.onFailure {
                failures += "${url.substringAfter("://").substringBefore('/')}=${it.message ?: "FAIL"}"
            }.getOrNull()

            if (delay != null) {
                best = best?.let { previous -> minOf(previous, delay) } ?: delay
                // One independent connectivity endpoint is enough for the gate.
                break
            }
        }

        return best ?: error("all fast-gate endpoints failed: ${failures.joinToString(", ")}")
    }
''',
    "dual fast gate implementation",
)

replace_once(
'''    private fun eligible(row: NodeResult): Boolean {
        if (row.quarantineUntil > System.currentTimeMillis()) return false
        val tg = row.telegram
        val ig = row.instagram
        return tg.ok &&
            tg.medianMs != null && tg.medianMs <= MAX_TG_MTPROTO_MS &&
            tg.abridgedOk > 0 && tg.intermediateOk > 0 &&
            ig.apiOk && ig.apiMs != null && ig.apiMs <= MAX_IG_API_MS &&
            row.web.okCount >= MIN_GENERIC_OK &&
            row.web.medianMs != null
    }

    private fun fallbackEligible(row: NodeResult): Boolean {
        if (row.quarantineUntil > System.currentTimeMillis()) return false
        val tg = row.telegram
        val telegramUsable =
            tg.ok && tg.medianMs != null && tg.medianMs <= 1500.0
        val secondaryConnectivity =
            row.instagram.apiOk || row.web.okCount >= 1
        return telegramUsable && secondaryConnectivity
    }
''',
'''    private fun eligible(row: NodeResult): Boolean {
        if (row.quarantineUntil > System.currentTimeMillis()) return false
        val tg = row.telegram
        val telegramUsable =
            tg.ok && tg.medianMs != null && tg.medianMs <= MAX_TG_MTPROTO_MS
        val instagramUsable =
            row.instagram.apiOk &&
                row.instagram.apiMs != null &&
                row.instagram.apiMs <= MAX_IG_API_MS
        val webUsable = row.web.okCount >= MIN_GENERIC_OK && row.web.medianMs != null

        // Do not throw away an otherwise good VPN because one service-specific
        // probe is temporarily blocked. Require solid generic web connectivity
        // plus at least one application-specific probe. Missing metrics still
        // receive a large score penalty, so a fully healthy node wins.
        return webUsable && (telegramUsable || instagramUsable)
    }

    private fun fallbackEligible(row: NodeResult): Boolean {
        if (row.quarantineUntil > System.currentTimeMillis()) return false
        val tg = row.telegram
        val telegramUsable =
            tg.ok && tg.medianMs != null && tg.medianMs <= 1800.0
        val instagramUsable =
            row.instagram.apiOk &&
                row.instagram.apiMs != null &&
                row.instagram.apiMs <= 2500.0
        val webUsable = row.web.okCount >= 1

        // Rescue mode: one proven working service is preferable to reporting
        // "no candidate" and leaving the user without a usable route.
        return telegramUsable || instagramUsable || webUsable
    }
''',
    "resilient eligibility",
)

if text == original:
    raise SystemExit("No changes made")

path.write_text(text, encoding="utf-8")
print("BelkaVPN resilience v5 applied:")
print("- fast gate tries Google then Cloudflare")
print("- one blocked probe no longer eliminates a server")
print("- normal candidate = web 2/3 + Telegram or Instagram")
print("- degraded fallback accepts any proven working service")
