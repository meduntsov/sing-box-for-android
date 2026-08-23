package io.nekohasekai.sfa.bg.health

import org.json.JSONArray
import org.json.JSONObject

object HealthConfigPatcher {
    private const val SELECTOR_TAG = "proxy"
    private const val INBOUND_PREFIX = "__belka_health_"
    private const val FIRST_TEST_PORT = 20810
    private const val DEFAULT_CLASH_PORT = 9090

    data class Node(
        val tag: String,
        val testPort: Int,
    )

    data class Plan(
        val selectorTag: String,
        val clashPort: Int,
        val clashSecret: String,
        val nodes: List<Node>,
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

        // BelkaVPN owns automatic server selection.
        // Remove urltest/auto from the active proxy selector and use only
        // concrete VLESS nodes discovered from the profile.
        val concreteSelectorOutbounds = JSONArray()
        leafTags.forEach { tag ->
            concreteSelectorOutbounds.put(tag)
            // Avoid 15-second stalls on dead routes.
            outboundByTag[tag]?.put("connect_timeout", "5s")
        }
        selector.put("outbounds", concreteSelectorOutbounds)
        selector.remove("default")
        selector.put("interrupt_exist_connections", true)

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

        // QUIC -> TCP fallback. A fast reject makes HTTP/3 clients retry TCP/443.
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
            if (!isOldHealthRule) {
                // Old profiles may explicitly route traffic through urltest[auto].
                // Route that traffic through BelkaVPN's selector instead.
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
            ),
        )
    }

    private fun parseClashPort(value: String): Int? {
        if (value.isBlank()) return null
        return value.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
    }
}
