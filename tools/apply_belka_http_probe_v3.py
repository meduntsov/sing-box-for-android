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


# Reuse the native Clash delay endpoint for all reachability probes. This makes
# sing-box own the timeout/cancellation instead of leaving orphaned outbound
# attempts behind after HttpURLConnection on the app side has already timed out.
replace_once(
'''    private fun httpReachability(node: HealthConfigPatcher.Node, url: String): Pair<Boolean, Double?> {
        val started = System.nanoTime()
        return try {
            val connection = openProxyConnection(node, url)
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Range", "bytes=0-1023")
            val status = connection.responseCode
            runCatching {
                (if (status >= 400) connection.errorStream else connection.inputStream)?.use { stream ->
                    stream.read(ByteArray(1024))
                }
            }
            connection.disconnect()
            (status < 500) to ((System.nanoTime() - started) / 1_000_000.0)
        } catch (_: Exception) {
            false to null
        }
    }
''',
'''    private fun httpReachability(node: HealthConfigPatcher.Node, url: String): Pair<Boolean, Double?> {
        return runCatching {
            true to clashDelay(node.tag, url, HTTP_TIMEOUT_MS)
        }.getOrElse {
            false to null
        }
    }

    private fun clashDelay(tag: String, url: String, timeoutMs: Int): Double {
        val encodedTag = URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
        val encodedUrl = URLEncoder.encode(url, "UTF-8").replace("+", "%20")
        val endpoint =
            "http://127.0.0.1:${plan.clashPort}/proxies/$encodedTag/delay" +
                "?timeout=$timeoutMs&url=$encodedUrl"
        val started = System.nanoTime()
        val connection = localClash(endpoint).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs + 1000
            readTimeout = timeoutMs + 1000
        }

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
    "native Clash delay reachability",
)

# Avoid the OpenAI /v1/models endpoint as a generic connectivity probe. It may
# legitimately return auth/rate-limit responses. Use stable 204-style endpoints.
replace_once(
'''        private val GENERIC_HTTPS = listOf(
            "google" to "https://www.gstatic.com/generate_204",
            "cloudflare" to "https://cloudflare.com/cdn-cgi/trace",
            "openai" to "https://api.openai.com/v1/models",
        )
''',
'''        private val GENERIC_HTTPS = listOf(
            "google" to "https://www.gstatic.com/generate_204",
            "cloudflare" to "https://cp.cloudflare.com/generate_204",
            "google2" to "https://connectivitycheck.gstatic.com/generate_204",
        )
''',
    "stable generic probe URLs",
)

if text == original:
    raise SystemExit("No changes made")

path.write_text(text, encoding="utf-8")
print("BelkaVPN HTTP probe v3 applied:")
print("- Instagram/Web reachability use sing-box Clash delay API")
print("- timeout/cancellation owned by sing-box")
print("- generic probes use stable connectivity endpoints")
