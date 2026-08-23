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
'''        // Very cheap transport pre-check. If a node cannot establish one TCP
        // connection through its VLESS path, do not create dozens of Telegram,
        // Instagram and generic HTTPS probes that will only time out as well.
        private const val FAST_GATE_IP = "1.1.1.1"
        private const val FAST_GATE_PORT = 443
''',
'''        // Fast gate is executed by sing-box itself through Clash API.
        // This makes the timeout cancel inside sing-box instead of leaving an
        // orphaned outbound attempt that logs a 15-second deadline later.
        private const val FAST_GATE_URL = "https://www.gstatic.com/generate_204"
        private const val FAST_GATE_TIMEOUT_MS = 2500
''',
    "Clash API fast-gate constants",
)

replace_once(
'''    private fun probeFastGate(node: HealthConfigPatcher.Node): Double {
        val started = System.nanoTime()
        val socket = socksConnect(node.testPort, FAST_GATE_IP, FAST_GATE_PORT)
        socket.close()
        return (System.nanoTime() - started) / 1_000_000.0
    }
''',
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
    "Clash API fast-gate implementation",
)

if text == original:
    raise SystemExit("No changes made")

path.write_text(text, encoding="utf-8")
print("BelkaVPN fast gate v2 applied:")
print("- native sing-box Clash API /proxies/<tag>/delay")
print("- gate URL: https://www.gstatic.com/generate_204")
print("- sing-box-owned timeout: 2500 ms")
print("- no SOCKS fast-gate orphan connections")
