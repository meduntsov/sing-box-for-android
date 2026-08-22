package io.nekohasekai.sfa.bg.health

import org.json.JSONArray
import org.json.JSONObject

object HealthConfigPatcher {
    private const val SELECTOR_TAG = "proxy"
    private const val INBOUND_PREFIX = "__alice_health_"
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
            if (tag.isNotBlank()) {
                outboundByTag[tag] = outbound
            }
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

        selector.put("interrupt_exist_connections", true)

        val cleanInbounds = JSONArray()
        val usedPorts = hashSetOf<Int>()
        val existingInbounds = root.optJSONArray("inbounds") ?: JSONArray()
        for (index in 0 until existingInbounds.length()) {
            val inbound = existingInbounds.optJSONObject(index) ?: continue
            if (inbound.optString("tag").startsWith(INBOUND_PREFIX)) continue
            val port = inbound.optInt("listen_port", -1)
            if (port > 0) usedPorts += port
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

        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val existingRules = route.optJSONArray("rules") ?: JSONArray()
        val cleanRules = JSONArray()

        for (node in nodes) {
            cleanRules.put(
                JSONObject()
                    .put("inbound", INBOUND_PREFIX + node.tag)
                    .put("outbound", node.tag),
            )
        }

        for (index in 0 until existingRules.length()) {
            val rule = existingRules.optJSONObject(index) ?: continue
            val inbound = rule.opt("inbound")
            val isOldHealthRule = when (inbound) {
                is String -> inbound.startsWith(INBOUND_PREFIX)
                is JSONArray -> (0 until inbound.length()).any {
                    inbound.optString(it).startsWith(INBOUND_PREFIX)
                }
                else -> false
            }
            if (!isOldHealthRule) cleanRules.put(rule)
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
