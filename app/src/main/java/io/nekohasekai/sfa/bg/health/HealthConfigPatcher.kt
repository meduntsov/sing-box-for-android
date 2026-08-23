package io.nekohasekai.sfa.bg.health

import org.json.JSONArray
import org.json.JSONObject

object HealthConfigPatcher {
    private const val SELECTOR_TAG = "proxy"
    private const val AI_SELECTOR_TAG = "__belka_ai"
    private const val AI_PREFERRED_COUNTRY_TAG = "de"
    private const val INBOUND_PREFIX = "__belka_health_"
    private const val FIRST_TEST_PORT = 20810
    private const val DEFAULT_CLASH_PORT = 9090

    private val AI_DOMAIN_SUFFIXES = listOf(
        "chatgpt.com",
        "openai.com",
        "oaistatic.com",
        "oaiusercontent.com",
        "claude.ai",
        "anthropic.com",
        "claudeusercontent.com",
        "clau.de",
        "browser-intake-us5-datadoghq.com",
        "gemini.google.com",
        "aistudio.google.com",
        "generativelanguage.googleapis.com",
        "robinfrontend-pa.googleapis.com",
    )

    data class Node(
        val tag: String,
        val testPort: Int,
    )

    data class Plan(
        val selectorTag: String,
        val clashPort: Int,
        val clashSecret: String,
        val nodes: List<Node>,
        val aiSelectorTag: String? = null,
        val aiPreferredTag: String? = null,
    )

    data class Result(
        val content: String,
        val plan: Plan,
    )

    fun patch(content: String): Result? {
        val root = JSONObject(content)
        val outbounds = root.optJSONArray("outbounds") ?: return null

        val outboundByTag = LinkedHashMap<String, JSONObject>()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val tag = outbound.optString("tag")
            if (tag.isNotBlank()) outboundByTag[tag] = outbound
        }

        val selector = outboundByTag[SELECTOR_TAG] ?: return null
        if (selector.optString("type") != "selector") return null

        val leafTags = linkedSetOf<String>()
        val visited = hashSetOf<String>()

        fun collectLeaf(tag: String) {
            if (!visited.add(tag)) return
            val outbound = outboundByTag[tag] ?: return
            when (outbound.optString("type")) {
                "vless" -> leafTags += tag
                "selector", "urltest" -> {
                    val children = outbound.optJSONArray("outbounds") ?: return
                    for (index in 0 until children.length()) {
                        val child = children.optString(index)
                        if (child.isNotBlank()) collectLeaf(child)
                    }
                }
            }
        }

        val selectorChildren = selector.optJSONArray("outbounds") ?: return null
        for (index in 0 until selectorChildren.length()) {
            val child = selectorChildren.optString(index)
            if (child.isNotBlank()) collectLeaf(child)
        }
        if (leafTags.isEmpty()) return null

        val concreteSelectorOutbounds = JSONArray()
        leafTags.forEach { tag ->
            concreteSelectorOutbounds.put(tag)
            outboundByTag[tag]?.put("connect_timeout", "5s")
        }
        selector.put("outbounds", concreteSelectorOutbounds)
        selector.remove("default")
        selector.put("interrupt_exist_connections", true)

        val aiPreferredTag = leafTags.firstOrNull {
            it.equals(AI_PREFERRED_COUNTRY_TAG, ignoreCase = true)
        }
        val aiSelectorTag = if (aiPreferredTag != null) {
            val aiSelector = outboundByTag[AI_SELECTOR_TAG] ?: JSONObject().also {
                it.put("type", "selector")
                it.put("tag", AI_SELECTOR_TAG)
                outbounds.put(it)
                outboundByTag[AI_SELECTOR_TAG] = it
            }
            aiSelector.put("type", "selector")
            aiSelector.put("tag", AI_SELECTOR_TAG)
            aiSelector.put(
                "outbounds",
                JSONArray()
                    .put(aiPreferredTag)
                    .put(SELECTOR_TAG),
            )
            aiSelector.put("default", aiPreferredTag)
            aiSelector.put("interrupt_exist_connections", true)
            AI_SELECTOR_TAG
        } else {
            null
        }

        val cleanInbounds = JSONArray()
        val usedPorts = hashSetOf<Int>()
        val tunTags = linkedSetOf<String>()
        val existingInbounds = root.optJSONArray("inbounds") ?: JSONArray()

        for (index in 0 until existingInbounds.length()) {
            val inbound = existingInbounds.optJSONObject(index) ?: continue
            val inboundTag = inbound.optString("tag")
            if (inboundTag.startsWith(INBOUND_PREFIX) ||
                inboundTag.startsWith("__alice_health_")
            ) {
                continue
            }
            val port = inbound.optInt("listen_port", -1)
            if (port > 0) usedPorts += port
            if (inbound.optString("type") == "tun" && inboundTag.isNotBlank()) {
                tunTags += inboundTag
            }
            cleanInbounds.put(inbound)
        }

        var nextPort = FIRST_TEST_PORT
        fun allocatePort(): Int {
            while (nextPort in usedPorts) nextPort++
            val value = nextPort++
            usedPorts += value
            return value
        }

        val nodes = leafTags.map { tag ->
            val port = allocatePort()
            cleanInbounds.put(
                JSONObject()
                    .put("type", "mixed")
                    .put("tag", INBOUND_PREFIX + tag)
                    .put("listen", "127.0.0.1")
                    .put("listen_port", port),
            )
            Node(tag, port)
        }
        root.put("inbounds", cleanInbounds)

        val route = root.optJSONObject("route")
            ?: JSONObject().also { root.put("route", it) }
        val existingRules = route.optJSONArray("rules") ?: JSONArray()
        val cleanRules = JSONArray()

        for (node in nodes) {
            cleanRules.put(
                JSONObject()
                    .put("inbound", INBOUND_PREFIX + node.tag)
                    .put("outbound", node.tag),
            )
        }

        // Force HTTP/3 clients back to TCP, where TLS SNI can be sniffed
        // reliably before routing AI traffic to its sticky country selector.
        for (tunTag in tunTags) {
            cleanRules.put(
                JSONObject()
                    .put("inbound", tunTag)
                    .put("network", "udp")
                    .put("port", 443)
                    .put("action", "reject")
                    .put("method", "default")
                    .put("no_drop", true),
            )
        }

        // Android apps frequently connect to an already-resolved IP. Sniff TLS
        // ClientHello/HTTP Host on TUN traffic so domain_suffix rules still see
        // claude.ai, anthropic.com, openai.com, etc. The sniff action is not
        // terminal: routing continues with the discovered hostname.
        for (tunTag in tunTags) {
            cleanRules.put(
                JSONObject()
                    .put("inbound", tunTag)
                    .put("network", "tcp")
                    .put("action", "sniff")
                    .put("sniffer", JSONArray().put("tls").put("http"))
                    .put("timeout", "500ms"),
            )
        }

        if (aiSelectorTag != null) {
            val domains = JSONArray()
            AI_DOMAIN_SUFFIXES.forEach(domains::put)
            cleanRules.put(
                JSONObject()
                    .put("domain_suffix", domains)
                    .put("outbound", aiSelectorTag),
            )
        }

        for (index in 0 until existingRules.length()) {
            val rule = existingRules.optJSONObject(index) ?: continue
            val inbound = rule.opt("inbound")
            val isOldHealthRule = when (inbound) {
                is String ->
                    inbound.startsWith("__alice_health_") ||
                        inbound.startsWith(INBOUND_PREFIX)
                is JSONArray -> (0 until inbound.length()).any {
                    val value = inbound.optString(it)
                    value.startsWith("__alice_health_") ||
                        value.startsWith(INBOUND_PREFIX)
                }
                else -> false
            }
            val isOldAiRule = rule.optString("outbound") == AI_SELECTOR_TAG
            val isOldBelkaSniff = rule.optString("action") == "sniff" &&
                when (inbound) {
                    is String -> inbound in tunTags
                    is JSONArray -> (0 until inbound.length()).any { inbound.optString(it) in tunTags }
                    else -> false
                }
            if (!isOldHealthRule && !isOldAiRule && !isOldBelkaSniff) {
                if (rule.optString("outbound") == "auto") {
                    rule.put("outbound", SELECTOR_TAG)
                }
                cleanRules.put(rule)
            }
        }
        route.put("rules", cleanRules)

        val experimental = root.optJSONObject("experimental")
            ?: JSONObject().also { root.put("experimental", it) }
        val clash = experimental.optJSONObject("clash_api")
            ?: JSONObject().also { experimental.put("clash_api", it) }

        val externalController = clash.optString("external_controller")
        val clashPort = parseClashPort(externalController) ?: DEFAULT_CLASH_PORT
        if (externalController.isBlank()) {
            clash.put("external_controller", "127.0.0.1:$clashPort")
        }

        return Result(
            root.toString(2),
            Plan(
                selectorTag = SELECTOR_TAG,
                clashPort = clashPort,
                clashSecret = clash.optString("secret"),
                nodes = nodes,
                aiSelectorTag = aiSelectorTag,
                aiPreferredTag = aiPreferredTag,
            ),
        )
    }

    private fun parseClashPort(value: String): Int? {
        if (value.isBlank()) return null
        return value.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
    }
}
