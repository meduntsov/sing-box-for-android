package io.nekohasekai.sfa.bg.health

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class HealthController(
    context: Context,
    private val plan: HealthConfigPatcher.Plan,
) : AutoCloseable {
    companion object {
        private const val TAG = "BelkaHealth"
        private const val TG_BOOTSTRAP_URL =
            "https://raw.githubusercontent.com/telegramdesktop/tdesktop/dev/Telegram/SourceFiles/mtproto/mtproto_dc_options.cpp"
        private const val TG_CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000

        private const val INSTAGRAM_API = "https://i.instagram.com/api/v1/"
        private val GENERIC_HTTPS = listOf(
            "google" to "https://www.gstatic.com/generate_204",
            "cloudflare" to "https://cloudflare.com/cdn-cgi/trace",
            "openai" to "https://api.openai.com/v1/models",
        )
        private const val MIN_GENERIC_OK = 2

        private const val SPEED_BYTES = 512 * 1024
        private const val SPEED_URL = "https://speed.cloudflare.com/__down?bytes=524288"
        private const val HEALTH_INTERVAL_MS = 3L * 60 * 1000
        private const val SPEED_INTERVAL_MS = 30L * 60 * 1000

        private const val MAX_TG_MTPROTO_MS = 900.0
        private const val MAX_IG_API_MS = 1500.0
        private const val MIN_SPEED_MBPS = 8.0
        private const val MIN_SWITCH_IMPROVEMENT = 0.12

        private const val CIRCUIT_FAILS = 3
        private const val CIRCUIT_QUARANTINE_MS = 15L * 60 * 1000

        private const val SOCKET_TIMEOUT_MS = 3000
        private const val HTTP_TIMEOUT_MS = 5000
        private const val NODE_SOFT_BUDGET_MS = 45_000L
        private const val MAX_MTPROTO_PAYLOAD = 1024 * 1024

        private const val REQ_PQ_MULTI = 0xBE7E8EF1.toInt()
        private const val RES_PQ = 0x05162463
        private const val UA =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
        private const val ABRIDGED = "abridged"
        private const val INTERMEDIATE = "intermediate"
    }

    private data class DcEndpoint(val dc: Int, val ip: String, val port: Int)

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
    )

    private data class GenericResult(
        val okCount: Int,
        val total: Int,
        val medianMs: Double?,
        val details: String,
    )

    private data class NodeResult(
        val tag: String,
        val telegram: TelegramResult,
        val instagram: InstagramResult,
        val web: GenericResult,
        val speedMbps: Double?,
        val quarantineUntil: Long = 0L,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val cacheDir = File(appContext.filesDir, "belka-health").apply { mkdirs() }
    private val telegramCache = File(cacheDir, "telegram-bootstrap.json")

    private var loopJob: Job? = null
    private val speedCache = ConcurrentHashMap<String, Double>()
    private var lastSpeedTestAt = 0L
    private var cycleNumber = 0L

    private val failureStreak = ConcurrentHashMap<String, Int>()
    private val quarantineUntil = ConcurrentHashMap<String, Long>()

    fun start() {
        if (loopJob != null) return
        Log.i(TAG, "v2.2 START selector=${plan.selectorTag}, nodes=${plan.nodes.joinToString(",") { it.tag }}")
        loopJob = scope.launch {
            delay(1500)
            while (isActive) {
                try {
                    runCycle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "health cycle failed: ${e.message}", e)
                }
                delay(HEALTH_INTERVAL_MS)
            }
        }
    }

    override fun close() {
        Log.i(TAG, "v2.2 STOP")
        loopJob?.cancel()
        loopJob = null
        scope.cancel()
    }

    private suspend fun runCycle() {
        if (plan.nodes.isEmpty()) {
            Log.w(TAG, "cycle skipped: no concrete VLESS nodes")
            return
        }

        val cycle = ++cycleNumber
        val cycleStarted = System.nanoTime()
        val now = System.currentTimeMillis()
        val terminalTags = plan.nodes.map { it.tag }.toSet()
        val currentTag = clashCurrent()?.takeIf(terminalTags::contains)

        BelkaVpnState.cycleStart(currentTag, now + HEALTH_INTERVAL_MS)
        Log.i(TAG, "CYCLE#$cycle START nodes=${plan.nodes.size}")

        val dcs = loadTelegramDcs() ?: run {
            Log.w(TAG, "CYCLE#$cycle Telegram bootstrap unavailable; selector unchanged")
            BelkaVpnState.finishNoEligible(currentTag, now + HEALTH_INTERVAL_MS)
            return
        }

        val globalSpeedTest =
            now - lastSpeedTestAt >= SPEED_INTERVAL_MS || speedCache.isEmpty()
        if (globalSpeedTest) lastSpeedTestAt = now

        val results = supervisorScope {
            plan.nodes.map { node ->
                async(Dispatchers.IO) {
                    val until = quarantineUntil[node.tag] ?: 0L
                    if (until > System.currentTimeMillis()) {
                        val row = quarantinedResult(node.tag, dcs.size, until)
                        Log.i(TAG, "CYCLE#$cycle ${node.tag}: QUARANTINE; skipped")
                        publishNode(row)
                        row
                    } else {
                        quarantineUntil.remove(node.tag)
                        probeNode(node, dcs, globalSpeedTest, cycle)
                    }
                }
            }.awaitAll()
        }

        val candidates = results.filter(::eligible)
        val nextCheckAt = System.currentTimeMillis() + HEALTH_INTERVAL_MS

        if (candidates.isEmpty()) {
            Log.w(
                TAG,
                "CYCLE#$cycle DONE: no eligible server; selector unchanged; elapsed=${elapsedMs(cycleStarted)}ms",
            )
            BelkaVpnState.finishNoEligible(currentTag, nextCheckAt)
            return
        }

        var best = candidates.minBy(::score)
        val current = results.firstOrNull { it.tag == currentTag }

        if (current != null && eligible(current) && current.tag != best.tag) {
            val currentScore = score(current)
            val bestScore = score(best)
            val improvement = (currentScore - bestScore) / currentScore.coerceAtLeast(1.0)
            if (improvement < MIN_SWITCH_IMPROVEMENT) best = current
        }

        val action = if (best.tag != currentTag) {
            clashSelect(best.tag)
            Log.i(
                TAG,
                "CYCLE#$cycle SELECT -> ${best.tag}, score=${fmt1(score(best))}, elapsed=${elapsedMs(cycleStarted)}ms",
            )
            "SELECT → ${best.tag.uppercase(Locale.US)}"
        } else {
            Log.i(
                TAG,
                "CYCLE#$cycle KEEP -> ${best.tag}, score=${fmt1(score(best))}, elapsed=${elapsedMs(cycleStarted)}ms",
            )
            "KEEP ${best.tag.uppercase(Locale.US)}"
        }

        BelkaVpnState.finishSelected(best.tag, action, nextCheckAt)
    }

    private fun probeNode(
        node: HealthConfigPatcher.Node,
        dcs: Map<Int, List<DcEndpoint>>,
        globalSpeedTest: Boolean,
        cycle: Long,
    ): NodeResult {
        val started = System.nanoTime()
        val deadlineNs = started + NODE_SOFT_BUDGET_MS * 1_000_000L
        Log.i(TAG, "CYCLE#$cycle ${node.tag}: START port=${node.testPort}")

        val tg = try {
            probeTelegram(node, dcs, deadlineNs, cycle)
        } catch (e: Exception) {
            Log.w(TAG, "CYCLE#$cycle ${node.tag}: TG exception: ${e.message}")
            failedTelegram(dcs.size)
        }

        Log.i(
            TAG,
            "CYCLE#$cycle ${node.tag}: TG=${tg.okDc}/${tg.totalDc} abr=${tg.abridgedOk} int=${tg.intermediateOk} ${formatMs(tg.medianMs)}",
        )

        if (deadlineExceeded(deadlineNs)) {
            speedCache.remove(node.tag)
            var row = NodeResult(node.tag, tg, failedInstagram(), failedGeneric(), null)
            val until = updateCircuit(row, cycle)
            if (until > 0) row = row.copy(quarantineUntil = until)
            publishNode(row)
            Log.w(TAG, "CYCLE#$cycle ${node.tag}: SOFT-TIMEOUT after TG, elapsed=${elapsedMs(started)}ms")
            return row
        }

        val ig = try {
            probeInstagram(node)
        } catch (e: Exception) {
            Log.w(TAG, "CYCLE#$cycle ${node.tag}: IG exception: ${e.message}")
            failedInstagram()
        }
        Log.i(TAG, "CYCLE#$cycle ${node.tag}: IG api=${formatMs(ig.apiMs)} apiOk=${ig.apiOk}")

        val web = if (!deadlineExceeded(deadlineNs)) {
            try {
                probeGeneric(node, deadlineNs)
            } catch (e: Exception) {
                Log.w(TAG, "CYCLE#$cycle ${node.tag}: WEB exception: ${e.message}")
                failedGeneric()
            }
        } else {
            failedGeneric()
        }
        Log.i(
            TAG,
            "CYCLE#$cycle ${node.tag}: WEB=${web.okCount}/${web.total} ${formatMs(web.medianMs)} ${web.details}",
        )

        val preSpeedEligible =
            tg.ok &&
                tg.medianMs != null && tg.medianMs <= MAX_TG_MTPROTO_MS &&
                ig.apiOk && ig.apiMs != null && ig.apiMs <= MAX_IG_API_MS &&
                web.okCount >= MIN_GENERIC_OK

        var speed = speedCache[node.tag]
        if (!preSpeedEligible) {
            speedCache.remove(node.tag)
            speed = null
        } else if (!deadlineExceeded(deadlineNs) && (globalSpeedTest || speed == null)) {
            speed = probeSpeed(node)
            if (speed == null) speedCache.remove(node.tag) else speedCache[node.tag] = speed
        }

        var row = NodeResult(node.tag, tg, ig, web, speed)
        val until = updateCircuit(row, cycle)
        if (until > 0) row = row.copy(quarantineUntil = until)
        publishNode(row)

        Log.i(
            TAG,
            "CYCLE#$cycle ${node.tag}: DONE speed=${formatSpeed(speed)} eligible=${eligible(row)} elapsed=${elapsedMs(started)}ms",
        )
        return row
    }

    private fun updateCircuit(row: NodeResult, cycle: Long): Long {
        val transportHealthy =
            row.telegram.ok && row.instagram.apiOk && row.web.okCount >= MIN_GENERIC_OK

        if (transportHealthy) {
            failureStreak.remove(row.tag)
            quarantineUntil.remove(row.tag)
            return 0L
        }

        val count = (failureStreak[row.tag] ?: 0) + 1
        if (count < CIRCUIT_FAILS) {
            failureStreak[row.tag] = count
            Log.i(TAG, "CYCLE#$cycle ${row.tag}: circuit fail $count/$CIRCUIT_FAILS")
            return 0L
        }

        failureStreak.remove(row.tag)
        speedCache.remove(row.tag)
        val until = System.currentTimeMillis() + CIRCUIT_QUARANTINE_MS
        quarantineUntil[row.tag] = until
        Log.w(TAG, "CYCLE#$cycle ${row.tag}: CIRCUIT OPEN for 15 min")
        return until
    }

    private fun publishNode(row: NodeResult) {
        BelkaVpnState.updateNode(
            BelkaNodeStatus(
                tag = row.tag,
                telegramOk = row.telegram.ok,
                telegramMs = row.telegram.medianMs,
                instagramOk = row.instagram.apiOk,
                instagramMs = row.instagram.apiMs,
                webOk = row.web.okCount,
                webTotal = row.web.total,
                webMs = row.web.medianMs,
                speedMbps = row.speedMbps,
                eligible = eligible(row),
                quarantineUntil = row.quarantineUntil,
            ),
        )
    }

    private fun quarantinedResult(tag: String, totalDc: Int, until: Long) =
        NodeResult(
            tag = tag,
            telegram = failedTelegram(totalDc),
            instagram = failedInstagram(),
            web = failedGeneric(),
            speedMbps = null,
            quarantineUntil = until,
        )

    private fun failedTelegram(totalDc: Int) = TelegramResult(
        ok = false,
        medianMs = null,
        okDc = 0,
        totalDc = totalDc,
        requiredDc = requiredDcCount(totalDc),
        abridgedOk = 0,
        intermediateOk = 0,
    )

    private fun failedInstagram() = InstagramResult(false, null)

    private fun failedGeneric() = GenericResult(
        okCount = 0,
        total = GENERIC_HTTPS.size,
        medianMs = null,
        details = "FAIL",
    )

    private fun deadlineExceeded(deadlineNs: Long) = System.nanoTime() >= deadlineNs

    private fun elapsedMs(startedNs: Long) =
        (System.nanoTime() - startedNs) / 1_000_000L

    private fun eligible(row: NodeResult): Boolean {
        if (row.quarantineUntil > System.currentTimeMillis()) return false
        val tg = row.telegram
        val ig = row.instagram
        val speed = row.speedMbps
        return tg.ok &&
            tg.medianMs != null && tg.medianMs <= MAX_TG_MTPROTO_MS &&
            tg.abridgedOk > 0 && tg.intermediateOk > 0 &&
            ig.apiOk && ig.apiMs != null && ig.apiMs <= MAX_IG_API_MS &&
            row.web.okCount >= MIN_GENERIC_OK &&
            row.web.medianMs != null &&
            speed != null && speed >= MIN_SPEED_MBPS
    }

    private fun score(row: NodeResult): Double {
        val tg = row.telegram.medianMs ?: 10_000.0
        val ig = row.instagram.apiMs ?: 10_000.0
        val web = row.web.medianMs ?: 10_000.0
        val speed = (row.speedMbps ?: 0.1).coerceAtLeast(0.1)
        return 0.40 * tg + 0.20 * ig + 0.25 * web + 0.15 * (3000.0 / speed)
    }

    private fun loadTelegramDcs(): Map<Int, List<DcEndpoint>>? {
        val cached = readTelegramCache()
        if (cached != null && System.currentTimeMillis() - cached.first < TG_CACHE_MAX_AGE_MS) {
            return cached.second
        }

        val now = System.currentTimeMillis()
        for (node in plan.nodes) {
            if ((quarantineUntil[node.tag] ?: 0L) > now) continue
            Log.i(TAG, "Telegram bootstrap: trying ${node.tag}")
            val source = runCatching { httpText(node, TG_BOOTSTRAP_URL, 2_000_000) }
                .onFailure { Log.w(TAG, "Telegram bootstrap ${node.tag} failed: ${it.message}") }
                .getOrNull() ?: continue
            val parsed = runCatching { parseTelegramDesktopBootstrap(source) }.getOrNull()
                ?: continue
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
                endpoints.forEach {
                    array.put(JSONObject().put("ip", it.ip).put("port", it.port))
                }
                dcsObject.put(dc.toString(), array)
            }
            root.put("dcs", dcsObject)
            telegramCache.writeText(root.toString(2))
        }
    }

    private fun probeTelegram(
        node: HealthConfigPatcher.Node,
        dcs: Map<Int, List<DcEndpoint>>,
        deadlineNs: Long,
        cycle: Long,
    ): TelegramResult {
        val successfulDcLatency = mutableListOf<Double>()
        var abridgedOk = 0
        var intermediateOk = 0

        val entries = dcs.toSortedMap().entries.toList()
        val total = entries.size
        val required = requiredDcCount(total)

        for ((index, entry) in entries.withIndex()) {
            val dc = entry.key
            val endpoints = entry.value

            if (deadlineExceeded(deadlineNs)) {
                Log.w(TAG, "CYCLE#$cycle ${node.tag}: TG budget exhausted before DC$dc")
                break
            }

            val preferred = if (dc % 2 == 1) ABRIDGED else INTERMEDIATE
            val alternate = if (preferred == ABRIDGED) INTERMEDIATE else ABRIDGED
            var best: Pair<String, Double>? = null

            endpointLoop@ for (endpoint in endpoints) {
                for (transport in listOf(preferred, alternate)) {
                    if (deadlineExceeded(deadlineNs)) break@endpointLoop
                    val latency = runCatching {
                        mtprotoReqPq(node.testPort, endpoint.ip, endpoint.port, transport)
                    }.onFailure {
                        Log.d(
                            TAG,
                            "CYCLE#$cycle ${node.tag}: DC$dc $transport ${endpoint.ip} failed: ${it.message}",
                        )
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
                Log.i(TAG, "CYCLE#$cycle ${node.tag}: DC$dc OK ${best.first} ${formatMs(best.second)}")
            } else {
                Log.w(TAG, "CYCLE#$cycle ${node.tag}: DC$dc FAIL")
            }

            val okDc = successfulDcLatency.size
            val bothTransports = abridgedOk > 0 && intermediateOk > 0
            val remaining = total - index - 1

            if (okDc >= required && bothTransports) {
                Log.i(TAG, "CYCLE#$cycle ${node.tag}: TG EARLY-SUCCESS $okDc/$total")
                break
            }
            if (okDc + remaining < required) {
                Log.w(
                    TAG,
                    "CYCLE#$cycle ${node.tag}: TG EARLY-FAIL $okDc OK + $remaining remaining < $required",
                )
                break
            }
        }

        val okDc = successfulDcLatency.size
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
        val body = ByteBuffer.allocate(20)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(REQ_PQ_MULTI)
            .put(nonce)
            .array()
        return ByteBuffer.allocate(20 + body.size)
            .order(ByteOrder.LITTLE_ENDIAN)
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

    private fun sendTransport(
        output: BufferedOutputStream,
        transport: String,
        payload: ByteArray,
    ) {
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
        return InstagramResult(api.first, api.second)
    }

    private fun probeGeneric(
        node: HealthConfigPatcher.Node,
        deadlineNs: Long,
    ): GenericResult {
        val latencies = mutableListOf<Double>()
        val details = mutableListOf<String>()

        for ((index, probe) in GENERIC_HTTPS.withIndex()) {
            if (deadlineExceeded(deadlineNs)) break
            val (name, url) = probe
            val result = httpReachability(node, url)
            val latency = result.second
            if (result.first && latency != null) {
                latencies += latency
                details += "$name=${formatMs(latency)}"
            } else {
                details += "$name=FAIL"
            }

            val remaining = GENERIC_HTTPS.size - index - 1
            if (latencies.size >= MIN_GENERIC_OK) break
            if (latencies.size + remaining < MIN_GENERIC_OK) break
        }

        return GenericResult(
            okCount = latencies.size,
            total = GENERIC_HTTPS.size,
            medianMs = median(latencies),
            details = details.joinToString(" "),
        )
    }

    private fun httpReachability(node: HealthConfigPatcher.Node, url: String): Pair<Boolean, Double?> {
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
            if (total < 64 * 1024 || seconds <= 0.0) null
            else total * 8.0 / seconds / 1_000_000.0
        }.getOrNull()
    }

    private fun httpText(node: HealthConfigPatcher.Node, url: String, maxBytes: Int): String {
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

    private fun openProxyConnection(node: HealthConfigPatcher.Node, url: String): HttpURLConnection {
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
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 &&
            parts.all { it.toIntOrNull()?.let { number -> number in 0..255 } == true }
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

    private fun leInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun leInt(value: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(value, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leLong(value: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(value, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun formatMs(value: Double?): String =
        value?.let { "${it.toInt()}ms" } ?: "FAIL"

    private fun formatSpeed(value: Double?): String =
        value?.let { "${fmt1(it)}Mb/s" } ?: "n/a"

    private fun fmt1(value: Double): String =
        String.format(Locale.US, "%.1f", value)
}
