package io.nekohasekai.sfa.bg.health

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.math.ceil

class HealthController(
    context: Context,
    private val plan: HealthConfigPatcher.Plan,
) : AutoCloseable {
    companion object {
        private const val TAG = "AliceHealth"

        private const val TG_BOOTSTRAP_URL =
            "https://raw.githubusercontent.com/telegramdesktop/tdesktop/dev/Telegram/SourceFiles/mtproto/mtproto_dc_options.cpp"
        private const val TG_CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000

        private const val INSTAGRAM_API = "https://i.instagram.com/api/v1/"
        private val INSTAGRAM_CDNS = listOf(
            "https://static.cdninstagram.com/",
            "https://scontent.cdninstagram.com/",
        )

        private const val SPEED_BYTES = 512 * 1024
        private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=524288"

        private const val HEALTH_INTERVAL_MS = 3L * 60 * 1000
        private const val SPEED_INTERVAL_MS = 30L * 60 * 1000

        private const val MAX_TG_MTPROTO_MS = 900.0
        private const val MAX_IG_API_MS = 1500.0
        private const val MAX_IG_CDN_MS = 1500.0
        private const val MIN_SPEED_MBPS = 8.0
        private const val MIN_SWITCH_IMPROVEMENT = 0.12

        private const val SOCKET_TIMEOUT_MS = 8000
        private const val HTTP_TIMEOUT_MS = 10000
        private const val MAX_MTPROTO_PAYLOAD = 1024 * 1024

        private const val REQ_PQ_MULTI = 0xBE7E8EF1.toInt()
        private const val RES_PQ = 0x05162463

        private const val UA =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"

        private const val ABRIDGED = "abridged"
        private const val INTERMEDIATE = "intermediate"
    }

    private data class DcEndpoint(
        val dc: Int,
        val ip: String,
        val port: Int,
    )

    private data class TelegramResult(
        val ok: Boolean,
        val medianMs: Double?,
        val okDc: Int,
        val totalDc: Int,
        val requiredDc: Int,
        val abridgedOk: Int,
        val intermediateOk: Int,
    )

    private data class InstagramResult(
        val apiOk: Boolean,
        val apiMs: Double?,
        val cdnOk: Boolean,
        val cdnMs: Double?,
    )

    private data class NodeResult(
        val tag: String,
        val telegram: TelegramResult,
        val instagram: InstagramResult,
        val speedMbps: Double?,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val cacheDir = File(appContext.filesDir, "alice-health").apply { mkdirs() }
    private val telegramCache = File(cacheDir, "telegram-bootstrap.json")
    private var loopJob: Job? = null
    private val speedCache = HashMap<String, Double?>()
    private var lastSpeedTestAt = 0L

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            delay(2000)
            while (isActive) {
                runCatching { runCycle() }
                    .onFailure { Log.w(TAG, "health cycle failed", it) }
                delay(HEALTH_INTERVAL_MS)
            }
        }
    }

    override fun close() {
        loopJob?.cancel()
        loopJob = null
        scope.cancel()
    }

    private fun runCycle() {
        if (plan.nodes.isEmpty()) return

        val dcs = loadTelegramDcs() ?: run {
            Log.w(TAG, "Telegram bootstrap unavailable; selector unchanged")
            return
        }

        val now = System.currentTimeMillis()
        val globalSpeedTest = now - lastSpeedTestAt >= SPEED_INTERVAL_MS || speedCache.values.all { it == null }
        if (globalSpeedTest) lastSpeedTestAt = now

        val results = plan.nodes.map { node ->
            val tg = probeTelegram(node, dcs)
            val ig = probeInstagram(node)
            val servicesOk = tg.ok && ig.apiOk && ig.cdnOk

            if (!servicesOk) {
                speedCache[node.tag] = null
            } else if (globalSpeedTest || speedCache[node.tag] == null) {
                speedCache[node.tag] = probeSpeed(node)
            }

            NodeResult(node.tag, tg, ig, speedCache[node.tag])
        }

        for (row in results) {
            Log.i(
                TAG,
                "${row.tag}: TG=${row.telegram.okDc}/${row.telegram.totalDc} " +
                    "abr=${row.telegram.abridgedOk} int=${row.telegram.intermediateOk} " +
                    "${formatMs(row.telegram.medianMs)}, IG=${formatMs(row.instagram.apiMs)}/" +
                    "${formatMs(row.instagram.cdnMs)}, speed=${formatSpeed(row.speedMbps)}, " +
                    "eligible=${eligible(row)}",
            )
        }

        val candidates = results.filter(::eligible)
        if (candidates.isEmpty()) {
            Log.w(TAG, "no eligible server; selector unchanged")
            return
        }

        var best = candidates.minBy(::score)
        val currentTag = clashCurrent()
        val current = results.firstOrNull { it.tag == currentTag }

        if (current != null && eligible(current) && current.tag != best.tag) {
            val currentScore = score(current)
            val bestScore = score(best)
            val improvement = (currentScore - bestScore) / currentScore.coerceAtLeast(1.0)
            if (improvement < MIN_SWITCH_IMPROVEMENT) {
                best = current
            }
        }

        if (best.tag != currentTag) {
            clashSelect(best.tag)
            Log.i(TAG, "SELECT -> ${best.tag}, score=${"%.1f".format(score(best))}")
        } else {
            Log.i(TAG, "KEEP -> ${best.tag}, score=${"%.1f".format(score(best))}")
        }
    }

    private fun eligible(row: NodeResult): Boolean {
        val tg = row.telegram
        val ig = row.instagram
        val speed = row.speedMbps
        return tg.ok &&
            tg.medianMs != null && tg.medianMs <= MAX_TG_MTPROTO_MS &&
            tg.abridgedOk > 0 && tg.intermediateOk > 0 &&
            ig.apiOk && ig.apiMs != null && ig.apiMs <= MAX_IG_API_MS &&
            ig.cdnOk && ig.cdnMs != null && ig.cdnMs <= MAX_IG_CDN_MS &&
            speed != null && speed >= MIN_SPEED_MBPS
    }

    private fun score(row: NodeResult): Double {
        val tg = row.telegram.medianMs ?: Double.MAX_VALUE
        val ig = maxOf(
            row.instagram.apiMs ?: Double.MAX_VALUE,
            row.instagram.cdnMs ?: Double.MAX_VALUE,
        )
        val speed = (row.speedMbps ?: 0.1).coerceAtLeast(0.1)
        return 0.50 * tg + 0.35 * ig + 0.15 * (3000.0 / speed)
    }

    private fun loadTelegramDcs(): Map<Int, List<DcEndpoint>>? {
        val cached = readTelegramCache()
        if (cached != null && System.currentTimeMillis() - cached.first < TG_CACHE_MAX_AGE_MS) {
            return cached.second
        }

        for (node in plan.nodes) {
            val source = runCatching { httpText(node, TG_BOOTSTRAP_URL, 2_000_000) }.getOrNull() ?: continue
            val parsed = runCatching { parseTelegramDesktopBootstrap(source) }.getOrNull() ?: continue
            if (parsed.isNotEmpty()) {
                writeTelegramCache(parsed)
                Log.i(TAG, "Telegram bootstrap refreshed via ${node.tag}: ${parsed.size} DCs")
                return parsed
            }
        }

        return cached?.second
    }

    private fun parseTelegramDesktopBootstrap(source: String): Map<Int, List<DcEndpoint>> {
        val block = Regex(
            "const\\s+BuiltInDc\\s+kBuiltInDcs\\[\\]\\s*=\\s*\\{(.*?)\\};",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(source)?.groupValues?.get(1) ?: error("kBuiltInDcs[] not found")

        val entry = Regex("\\{\\s*(\\d+)\\s*,\\s*\"([^\"]+)\"\\s*,\\s*(\\d+)\\s*\\}")
        val result = linkedMapOf<Int, MutableList<DcEndpoint>>()

        for (match in entry.findAll(block)) {
            val dc = match.groupValues[1].toInt()
            val ip = match.groupValues[2]
            val port = match.groupValues[3].toInt()
            if (port != 443 || !isIpv4(ip)) continue
            val endpoint = DcEndpoint(dc, ip, port)
            val list = result.getOrPut(dc) { mutableListOf() }
            if (list.none { it.ip == ip && it.port == port }) list += endpoint
        }

        return result
    }

    private fun readTelegramCache(): Pair<Long, Map<Int, List<DcEndpoint>>>? {
        return runCatching {
            if (!telegramCache.isFile) return null
            val root = JSONObject(telegramCache.readText())
            val fetchedAt = root.getLong("fetched_at")
            val dcsObject = root.getJSONObject("dcs")
            val result = linkedMapOf<Int, List<DcEndpoint>>()
            for (key in dcsObject.keys()) {
                val dc = key.toInt()
                val items = dcsObject.getJSONArray(key)
                val endpoints = buildList {
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        add(DcEndpoint(dc, item.getString("ip"), item.optInt("port", 443)))
                    }
                }
                if (endpoints.isNotEmpty()) result[dc] = endpoints
            }
            fetchedAt to result
        }.getOrNull()
    }

    private fun writeTelegramCache(dcs: Map<Int, List<DcEndpoint>>) {
        runCatching {
            val root = JSONObject().put("fetched_at", System.currentTimeMillis())
            val dcsObject = JSONObject()
            for ((dc, endpoints) in dcs) {
                val array = JSONArray()
                endpoints.forEach { array.put(JSONObject().put("ip", it.ip).put("port", it.port)) }
                dcsObject.put(dc.toString(), array)
            }
            root.put("dcs", dcsObject)
            telegramCache.writeText(root.toString(2))
        }
    }

    private fun probeTelegram(
        node: HealthConfigPatcher.Node,
        dcs: Map<Int, List<DcEndpoint>>,
    ): TelegramResult {
        val successfulDcLatency = mutableListOf<Double>()
        var abridgedOk = 0
        var intermediateOk = 0

        for ((dc, endpoints) in dcs.toSortedMap()) {
            val preferred = if (dc % 2 == 1) ABRIDGED else INTERMEDIATE
            val alternate = if (preferred == ABRIDGED) INTERMEDIATE else ABRIDGED
            var best: Pair<String, Double>? = null

            endpointLoop@ for (endpoint in endpoints) {
                for (transport in listOf(preferred, alternate)) {
                    val latency = runCatching {
                        mtprotoReqPq(node.testPort, endpoint.ip, endpoint.port, transport)
                    }.getOrNull()

                    if (latency != null) {
                        best = transport to latency
                        break@endpointLoop
                    }
                }
            }

            if (best != null) {
                successfulDcLatency += best.second
                if (best.first == ABRIDGED) abridgedOk++ else intermediateOk++
            }
        }

        val total = dcs.size
        val okDc = successfulDcLatency.size
        val required = requiredDcCount(total)
        val bothTransports = abridgedOk > 0 && intermediateOk > 0

        return TelegramResult(
            ok = okDc >= required && bothTransports,
            medianMs = median(successfulDcLatency),
            okDc = okDc,
            totalDc = total,
            requiredDc = required,
            abridgedOk = abridgedOk,
            intermediateOk = intermediateOk,
        )
    }

    private fun requiredDcCount(total: Int): Int {
        if (total <= 0) return 0
        val ratio = ceil(total * 0.60).toInt()
        val absolute = minOf(3, total)
        return maxOf(ratio, absolute)
    }

    private fun mtprotoReqPq(
        testPort: Int,
        targetIp: String,
        targetPort: Int,
        transport: String,
    ): Double {
        val started = System.nanoTime()
        val socket = socksConnect(testPort, targetIp, targetPort)
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val output = BufferedOutputStream(it.getOutputStream())
            val nonce = ByteArray(16).also(random::nextBytes)
            val payload = buildReqPq(nonce)
            sendTransport(output, transport, payload)
            val response = receiveTransport(input, transport)
            validateResPq(response, nonce)
        }
        return (System.nanoTime() - started) / 1_000_000.0
    }

    private fun buildReqPq(nonce: ByteArray): ByteArray {
        val body = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(REQ_PQ_MULTI)
            .put(nonce)
            .array()

        return ByteBuffer.allocate(20 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0L)
            .putLong(messageId())
            .putInt(body.size)
            .put(body)
            .array()
    }

    private fun messageId(): Long {
        val millis = System.currentTimeMillis()
        val seconds = millis / 1000L
        val fraction = ((millis % 1000L) * 0x1_0000_0000L) / 1000L
        return ((seconds shl 32) or fraction) and -4L
    }

    private fun sendTransport(output: BufferedOutputStream, transport: String, payload: ByteArray) {
        require(payload.size % 4 == 0)
        when (transport) {
            ABRIDGED -> {
                output.write(0xEF)
                val words = payload.size / 4
                if (words < 0x7F) {
                    output.write(words)
                } else {
                    output.write(0x7F)
                    output.write(words and 0xFF)
                    output.write((words ushr 8) and 0xFF)
                    output.write((words ushr 16) and 0xFF)
                }
                output.write(payload)
            }

            INTERMEDIATE -> {
                output.write(byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()))
                output.write(leInt(payload.size))
                output.write(payload)
            }

            else -> error("unknown MTProto transport $transport")
        }
        output.flush()
    }

    private fun receiveTransport(input: BufferedInputStream, transport: String): ByteArray {
        val length = when (transport) {
            ABRIDGED -> {
                val first = readByte(input)
                if (first and 0x80 != 0) error("unexpected abridged quick-ack")
                val words = if (first == 0x7F) {
                    readByte(input) or (readByte(input) shl 8) or (readByte(input) shl 16)
                } else {
                    first
                }
                words * 4
            }

            INTERMEDIATE -> {
                val raw = leInt(readExact(input, 4), 0)
                if (raw < 0) error("unexpected intermediate quick-ack")
                raw
            }

            else -> error("unknown MTProto transport $transport")
        }

        require(length in 1..MAX_MTPROTO_PAYLOAD) { "invalid MTProto payload length $length" }
        return readExact(input, length)
    }

    private fun validateResPq(payload: ByteArray, nonce: ByteArray) {
        if (payload.size == 4) {
            val code = leInt(payload, 0)
            if (code < 0) error("Telegram MTProto transport error ${-code}")
        }
        require(payload.size >= 40) { "short MTProto response" }
        require(leLong(payload, 0) == 0L) { "unexpected auth_key_id" }
        val bodyLength = leInt(payload, 16)
        require(bodyLength >= 36 && 20 + bodyLength <= payload.size) { "invalid MTProto body length" }
        require(leInt(payload, 20) == RES_PQ) { "expected resPQ" }
        require(payload.copyOfRange(24, 40).contentEquals(nonce)) { "resPQ nonce mismatch" }
    }

    private fun socksConnect(testPort: Int, targetIp: String, targetPort: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", testPort), SOCKET_TIMEOUT_MS)
        socket.soTimeout = SOCKET_TIMEOUT_MS

        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())

        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        require(readExact(input, 2).contentEquals(byteArrayOf(0x05, 0x00))) { "SOCKS auth rejected" }

        val address = targetIp.split('.').map { it.toInt().toByte() }.toByteArray()
        require(address.size == 4) { "IPv4 required" }
        output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01))
        output.write(address)
        output.write(byteArrayOf((targetPort ushr 8).toByte(), targetPort.toByte()))
        output.flush()

        val head = readExact(input, 4)
        require(head[0].toInt() and 0xFF == 5 && head[1].toInt() and 0xFF == 0) {
            "SOCKS CONNECT failed: ${head[1].toInt() and 0xFF}"
        }
        when (head[3].toInt() and 0xFF) {
            1 -> readExact(input, 4)
            3 -> readExact(input, readByte(input))
            4 -> readExact(input, 16)
            else -> error("invalid SOCKS address type")
        }
        readExact(input, 2)
        return socket
    }

    private fun probeInstagram(node: HealthConfigPatcher.Node): InstagramResult {
        val api = httpReachability(node, INSTAGRAM_API)
        val cdn = INSTAGRAM_CDNS.map { httpReachability(node, it) }
            .filter { it.first && it.second != null }

        return InstagramResult(
            apiOk = api.first,
            apiMs = api.second,
            cdnOk = cdn.isNotEmpty(),
            cdnMs = cdn.mapNotNull { it.second }.minOrNull(),
        )
    }

    private fun httpReachability(
        node: HealthConfigPatcher.Node,
        url: String,
    ): Pair<Boolean, Double?> {
        val started = System.nanoTime()
        return try {
            val connection = openProxyConnection(node, url)
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Range", "bytes=0-1023")
            val status = connection.responseCode
            runCatching {
                (if (status >= 400) connection.errorStream else connection.inputStream)?.use { stream ->
                    val buffer = ByteArray(1024)
                    stream.read(buffer)
                }
            }
            connection.disconnect()
            (status < 500) to ((System.nanoTime() - started) / 1_000_000.0)
        } catch (_: Exception) {
            false to null
        }
    }

    private fun probeSpeed(node: HealthConfigPatcher.Node): Double? {
        return runCatching {
            val connection = openProxyConnection(node, SPEED_URL)
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            require(status in 200..299)

            val started = System.nanoTime()
            var total = 0
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (total < SPEED_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size, SPEED_BYTES - total))
                    if (count < 0) break
                    total += count
                }
            }
            connection.disconnect()
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            if (total < 64 * 1024 || seconds <= 0.0) null else total * 8.0 / seconds / 1_000_000.0
        }.getOrNull()
    }

    private fun httpText(
        node: HealthConfigPatcher.Node,
        url: String,
        maxBytes: Int,
    ): String {
        val connection = openProxyConnection(node, url)
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", UA)
        require(connection.responseCode in 200..299)
        val data = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (output.size() < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        connection.disconnect()
        return String(data, Charsets.UTF_8)
    }

    private fun openProxyConnection(
        node: HealthConfigPatcher.Node,
        url: String,
    ): HttpURLConnection {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", node.testPort))
        return (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            instanceFollowRedirects = true
        }
    }

    private fun clashCurrent(): String? {
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

    private fun localClash(url: String): HttpURLConnection {
        return (URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            if (plan.clashSecret.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${plan.clashSecret}")
            }
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun readByte(input: BufferedInputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException()
        return value
    }

    private fun readExact(input: BufferedInputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        return result
    }

    private fun leInt(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

    private fun leInt(value: ByteArray, offset: Int): Int = ByteBuffer.wrap(value, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int

    private fun leLong(value: ByteArray, offset: Int): Long = ByteBuffer.wrap(value, offset, 8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .long

    private fun formatMs(value: Double?): String = value?.let { "${it.toInt()}ms" } ?: "FAIL"

    private fun formatSpeed(value: Double?): String = value?.let { "%.1fMb/s".format(it) } ?: "n/a"

}
