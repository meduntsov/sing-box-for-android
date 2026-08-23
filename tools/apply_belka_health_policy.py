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
    "private const val HEALTH_INTERVAL_MS = 3L * 60 * 1000",
    "private const val HEALTH_INTERVAL_MS = 10L * 60 * 1000",
    "10-minute health interval",
)

replace_once(
    "private const val MIN_SWITCH_IMPROVEMENT = 0.12",
    "private const val SWITCH_TOLERANCE_MS = 150.0",
    "150ms switch tolerance",
)

replace_once(
'''        val candidates = results.filter(::eligible)
        val nextCheckAt = System.currentTimeMillis() + HEALTH_INTERVAL_MS
''',
'''        // Keep AI clients on the preferred country while that node is usable.
        // If it really fails health checks, fall back to the general selector.
        syncAiSelector(results, cycle)

        val candidates = results.filter(::eligible)
        val nextCheckAt = System.currentTimeMillis() + HEALTH_INTERVAL_MS
''',
    "AI selector synchronization",
)

replace_once(
'''            val degradedCandidates = results.filter(::fallbackEligible)
            if (degradedCandidates.isNotEmpty()) {
                val bestDegraded = degradedCandidates.minBy(::fallbackScore)
                if (bestDegraded.tag != currentTag) {
                    clashSelect(bestDegraded.tag)
                }
                Log.w(
                    TAG,
                    "CYCLE#$cycle DEGRADED -> ${bestDegraded.tag}, " +
                        "score=${fmt1(fallbackScore(bestDegraded))}, " +
                        "elapsed=${elapsedMs(cycleStarted)}ms",
                )
                BelkaVpnState.finishDegraded(
                    bestDegraded.tag,
                    "DEGRADED → ${bestDegraded.tag.uppercase(Locale.US)}",
                    nextCheckAt,
                )
                return
            }
''',
'''            val degradedCandidates = results.filter(::fallbackEligible)
            if (degradedCandidates.isNotEmpty()) {
                var bestDegraded = degradedCandidates.minBy(::fallbackScore)
                val currentDegraded = results.firstOrNull { it.tag == currentTag }

                // Do not flap between servers for small score changes. If the
                // current route is still usable, require >150 ms-equivalent
                // improvement before switching. A failed current route moves
                // immediately to the best usable fallback.
                if (
                    currentDegraded != null &&
                    fallbackEligible(currentDegraded) &&
                    currentDegraded.tag != bestDegraded.tag
                ) {
                    val improvementMs =
                        fallbackScore(currentDegraded) - fallbackScore(bestDegraded)
                    if (improvementMs <= SWITCH_TOLERANCE_MS) {
                        bestDegraded = currentDegraded
                    }
                }

                if (bestDegraded.tag != currentTag) {
                    clashSelect(bestDegraded.tag)
                }
                Log.w(
                    TAG,
                    "CYCLE#$cycle DEGRADED -> ${bestDegraded.tag}, " +
                        "score=${fmt1(fallbackScore(bestDegraded))}, " +
                        "elapsed=${elapsedMs(cycleStarted)}ms",
                )
                BelkaVpnState.finishDegraded(
                    bestDegraded.tag,
                    "DEGRADED → ${bestDegraded.tag.uppercase(Locale.US)}",
                    nextCheckAt,
                )
                return
            }
''',
    "degraded anti-flapping",
)

replace_once(
'''        if (current != null && eligible(current) && current.tag != best.tag) {
            val currentScore = score(current)
            val bestScore = score(best)
            val improvement = (currentScore - bestScore) / currentScore.coerceAtLeast(1.0)
            if (improvement < MIN_SWITCH_IMPROVEMENT) best = current
        }
''',
'''        if (current != null && eligible(current) && current.tag != best.tag) {
            val currentScore = score(current)
            val bestScore = score(best)
            val improvementMs = currentScore - bestScore

            // Healthy current server is sticky. Switch only when another node
            // is more than 150 ms-equivalent better. If current is unhealthy,
            // this block is skipped and failover happens immediately.
            if (improvementMs <= SWITCH_TOLERANCE_MS) best = current
        }
''',
    "normal anti-flapping",
)

replace_once(
'''    private fun clashCurrent(): String? {
        return runCatching {
            val encoded = URLEncoder.encode(plan.selectorTag, "UTF-8").replace("+", "%20")
            val connection = localClash("http://127.0.0.1:${plan.clashPort}/proxies/$encoded")
            connection.requestMethod = "GET"
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            JSONObject(body).optString("now").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun clashSelect(tag: String) {
        val encoded = URLEncoder.encode(plan.selectorTag, "UTF-8").replace("+", "%20")
        val connection = localClash("http://127.0.0.1:${plan.clashPort}/proxies/$encoded")
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("name", tag).toString().toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        connection.disconnect()
        require(code in 200..299) { "Clash selector returned HTTP $code" }
    }
''',
'''    private fun syncAiSelector(results: List<NodeResult>, cycle: Long) {
        val selectorTag = plan.aiSelectorTag ?: return
        val preferredTag = plan.aiPreferredTag ?: return
        val preferred = results.firstOrNull { it.tag == preferredTag }

        val target = if (preferred != null && fallbackEligible(preferred)) {
            preferredTag
        } else {
            plan.selectorTag
        }

        val current = clashCurrent(selectorTag)
        if (current != target) {
            runCatching { clashSelect(selectorTag, target) }
                .onSuccess {
                    Log.i(
                        TAG,
                        "CYCLE#$cycle AI selector -> $target " +
                            "(preferred=$preferredTag)",
                    )
                }
                .onFailure {
                    Log.w(TAG, "CYCLE#$cycle AI selector switch failed: ${it.message}")
                }
        }
    }

    private fun clashCurrent(selectorTag: String = plan.selectorTag): String? {
        return runCatching {
            val encoded = URLEncoder.encode(selectorTag, "UTF-8").replace("+", "%20")
            val connection = localClash("http://127.0.0.1:${plan.clashPort}/proxies/$encoded")
            connection.requestMethod = "GET"
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            JSONObject(body).optString("now").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun clashSelect(tag: String) {
        clashSelect(plan.selectorTag, tag)
    }

    private fun clashSelect(selectorTag: String, tag: String) {
        val encoded = URLEncoder.encode(selectorTag, "UTF-8").replace("+", "%20")
        val connection = localClash("http://127.0.0.1:${plan.clashPort}/proxies/$encoded")
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("name", tag).toString().toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        connection.disconnect()
        require(code in 200..299) { "Clash selector returned HTTP $code" }
    }
''',
    "generic Clash selector + AI failover",
)

if text == original:
    raise SystemExit("No changes made")

path.write_text(text, encoding="utf-8")
print("BelkaVPN health policy applied:")
print("- health cycle: 10 minutes")
print("- sticky tolerance: 150 ms-equivalent")
print("- immediate failover when current server is unhealthy")
print("- AI preferred country selector with automatic fallback")
