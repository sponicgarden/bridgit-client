package cc.bridgit.app.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.UUID
import cc.bridgit.app.outbound.Outbound
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Observations are untrusted hints, never proof of identity or permission to control. */
class LanDiscovery(private val context: Context, private val lan: HostLan,
    /** Owner screen only: called (on a worker thread) with every device seen so far, each time a new host appears. */
    private val onFound: ((List<NetworkDevices.Device>) -> Unit)? = null,
    private val authorized: () -> Unit) {
    companion object {
        val SERVICE_TYPES = DiscoveryCatalog.mdns.map { it.type }
    }
    private val observations = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val outcomes = Collections.synchronizedList(mutableListOf<JSONObject>())
    private fun add(host: String, source: String, service: String, port: Int? = null, name: String = "", model: String = "") {
        if (!LanRules.onLink(host, lan.local, lan.prefix)) return
        synchronized(observations) {
            if (observations.size >= 256) return
            val o = JSONObject().put("host", host).put("source", source).put("service", service.filter { it >= ' ' }.take(160))
                .put("verified", false).put("enrolled", false).put("availability", "observed").put("observed_at", System.currentTimeMillis())
            if (port != null) o.put("port", port)
            if (name.isNotBlank()) o.put("name", NetworkDevices.cleanName(name))
            if (model.isNotBlank()) o.put("advertised_model", NetworkDevices.cleanName(model))
            val newHost = observations.none { it.optString("host") == host }
            if (observations.none { it.optString("host") == host && it.optString("source") == source && it.optString("service") == service && it.optInt("port", -1) == (port ?: -1) }) observations.add(o)
            if (!newHost || onFound == null) return
        }
        val seen = synchronized(observations) { JSONArray(observations.toList()) }
        runCatching { onFound?.invoke(NetworkDevices.devices(seen)) }
    }
    suspend fun run(hosts: List<String>, subnet: Boolean): JSONObject = coroutineScope {
        val historyScope = lan.historyScope(context)
        val targets = if (subnet) LanRules.boundedHosts(lan.local, lan.prefix) else hosts
        require(targets.size <= 254); targets.forEach(lan::check)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("bridgit-lan-discovery")?.apply { setReferenceCounted(false); acquire() }
        try {
            listOf(
                async(Dispatchers.IO) { method("ssdp") { udp("239.255.255.250", 1900,
                    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n", "ssdp") } },
                async(Dispatchers.IO) { method("onvif") { udp("239.255.255.250", 3702,
                    "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:n=\"http://www.onvif.org/ver10/network/wsdl\"><s:Header><a:MessageID>uuid:${UUID.randomUUID()}</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To><a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action></s:Header><s:Body><d:Probe><d:Types>n:NetworkVideoTransmitter</d:Types></d:Probe></s:Body></s:Envelope>", "onvif") } },
                async { method("mdns") { mdns() } },
                async(Dispatchers.IO) { method("tplink") { tplink() } },
                async(Dispatchers.IO) { method("tcp_inventory") {
                    targets.distinct().chunked(16).forEach { batch ->
                        batch.map { host -> async(Dispatchers.IO) {
                            for (port in listOf(80, 443, 554, 2020)) {
                                ensureActive(); authorized()
                                val started = System.currentTimeMillis()
                                Outbound.check("tcp://$host:$port", "CONNECT", "tcp")
                                var connected = false
                                try { lan.network.socketFactory.createSocket().use { s ->
                                    s.connect(InetSocketAddress(lan.address(host), port), 180)
                                    connected = true
                                    add(host, "tcp", "tcp_open", port)
                                } } catch (_: java.io.IOException) { }
                                finally { Outbound.record("tcp://$host:$port", "CONNECT", "tcp", status = if (connected) 200 else 0, startedAt = started) }
                            }
                        } }.awaitAll()
                    }
                } }
            ).awaitAll()
        } finally { if (lock?.isHeld == true) lock.release() }
        authorized()
        require(HostLan(context).network == lan.network && lan.historyScope(context) == historyScope) { "Network changed during discovery" }
        val current = NetworkDevices.devices(JSONArray(observations.toList()))
        val reliable = outcomes.all { it.optString("status") == "completed" }
        val remembered = DiscoveryHistory(context).update(historyScope, current, reliable)
        DiscoveryResult.enrich(context, JSONObject().put("network", lan.json()).put("observations", JSONArray(observations.toList()))
            .put("methods", JSONArray(outcomes.toList())).put("complete", false)
            .put("limits", "IPv4 direct LAN only; 256 observations; 254 explicit/subnet hosts; 4 fixed TCP ports. Silence does not prove offline. No discovered URLs are followed."), remembered)
    }
    private suspend fun method(name: String, block: suspend () -> Unit) {
        try { block(); outcomes.add(JSONObject().put("method", name).put("status", "completed")) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (cc.bridgit.app.BuildConfig.DEBUG) android.util.Log.w("LanDiscovery", "$name unavailable", e)
            outcomes.add(JSONObject().put("method", name).put("status", "unavailable"))
        }
    }
    private suspend fun udp(group: String, port: Int, text: String, source: String) {
        DatagramSocket(null).use { socket ->
            lan.bindDiscoverySocket(socket, InetAddress.getByName(group)); socket.bind(InetSocketAddress(lan.local, 0)); socket.soTimeout = 250
            authorized()
            val bytes = text.toByteArray()
            Outbound.check("udp://$group:$port", "DISCOVER", "udp")
            Outbound.record("udp://$group:$port", "DISCOVER", "udp", sentBytes = bytes.size.toLong(), status = 0, startedAt = System.currentTimeMillis())
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(group), port))
            val until = System.nanoTime() + 2_000_000_000L
            var packets = 0
            while (System.nanoTime() < until && packets < 128) {
                currentCoroutineContext().ensureActive(); authorized()
                val packet = DatagramPacket(ByteArray(8192), 8192)
                try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                packets++
                val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val service = if (source == "ssdp") response.lineSequence().firstOrNull { it.startsWith("ST:", true) }?.substringAfter(':')?.trim() ?: "ssdp_responder"
                    else if (response.contains("ProbeMatches")) "onvif_candidate" else continue
                add(packet.address.hostAddress.orEmpty(), source, service)
            }
        }
    }
    private suspend fun tplink() {
        // Standard link-local broadcast, source-bound to the selected LAN (python-kasa's default target).
        val host = "255.255.255.255"
        val query = TpLinkDiscovery.query()
        DatagramSocket(null).use { socket ->
            lan.bindDiscoverySocket(socket, InetAddress.getByName(host)); socket.bind(InetSocketAddress(lan.local, 0))
            socket.broadcast = true; socket.soTimeout = 250
            fun send() {
                authorized()
                for (port in listOf(20002, 20004)) {
                    Outbound.check("udp://$host:$port", "DISCOVER", "udp")
                    socket.send(DatagramPacket(query, query.size, InetAddress.getByName(host), port))
                    Outbound.record("udp://$host:$port", "DISCOVER", "udp", sentBytes = query.size.toLong(), status = 0, startedAt = System.currentTimeMillis())
                }
            }
            send()
            val started = System.nanoTime()
            var repeated = false
            var packets = 0
            while (System.nanoTime() - started < 2_500_000_000L && packets < 64) {
                currentCoroutineContext().ensureActive(); authorized()
                if (!repeated && System.nanoTime() - started > 750_000_000L) { send(); repeated = true }
                val packet = DatagramPacket(ByteArray(8192), 8192)
                try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                packets++
                if (packet.port !in listOf(20002, 20004) || !LanRules.onLink(packet.address.hostAddress.orEmpty(), lan.local, lan.prefix)) continue
                val hint = TpLinkDiscovery.parse(packet.data.copyOf(packet.length)) ?: continue
                add(packet.address.hostAddress.orEmpty(), "tplink", hint.service, name = hint.name)
            }
        }
    }
    @Suppress("DEPRECATION")
    private suspend fun mdns() = coroutineScope {
        // Older NSD APIs cannot constrain discovery to the selected network.
        require(android.os.Build.VERSION.SDK_INT >= 34 ||
            android.os.Build.VERSION.SDK_INT >= 33 && android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.TIRAMISU) >= 3)
        val nsd = context.getSystemService(NsdManager::class.java)
        val active = AtomicBoolean(true)
        val resolves = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        val queue = kotlinx.coroutines.channels.Channel<Pair<NsdServiceInfo, String>>(128)
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val typeCounts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val resolver = launch {
            for ((info, type) in queue) {
                withTimeoutOrNull(750) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val listener = object : NsdManager.ResolveListener {
                            override fun onResolveFailed(service: NsdServiceInfo, code: Int) {
                                if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                            }
                            override fun onServiceResolved(service: NsdServiceInfo) {
                                if (active.get() && continuation.isActive) runCatching {
                                    authorized()
                                    val name = if (type == "_googlecast._tcp.") service.attributes["fn"]?.toString(Charsets.UTF_8).orEmpty().ifBlank { service.serviceName }
                                        else service.serviceName
                                    val modelKeys = when (type) {
                                        "_googlecast._tcp." -> listOf("md")
                                        "_airplay._tcp." -> listOf("model")
                                        "_raop._tcp." -> listOf("am")
                                        "_ipp._tcp.", "_ipps._tcp.", "_printer._tcp.", "_pdl-datastream._tcp." -> listOf("ty", "product")
                                        else -> emptyList()
                                    }
                                    val model = modelKeys.mapNotNull { key -> service.attributes[key]?.toString(Charsets.UTF_8) }.firstOrNull { it.isNotBlank() }.orEmpty()
                                    val addresses = if (android.os.Build.VERSION.SDK_INT >= 34) service.hostAddresses else listOfNotNull(service.host)
                                    addresses.filterIsInstance<java.net.Inet4Address>().forEach { address ->
                                        add(address.hostAddress.orEmpty(), "mdns", type, service.port, name, model)
                                    }
                                }
                                if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                            }
                        }
                        continuation.invokeOnCancellation {
                            if (android.os.Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(listener) }
                        }
                        try { authorized(); nsd.resolveService(info, listener) }
                        catch (e: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
                    }
                }
            }
        }
        // Fixed service types: never a continuous background browse or arbitrary supplied query.
        try {
            for (batch in SERVICE_TYPES.chunked(DiscoveryCatalog.BATCH_SIZE)) {
                val batchListeners = mutableListOf<NsdManager.DiscoveryListener>()
                for (type in batch) {
                authorized()
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(t: String) {}
                    override fun onDiscoveryStopped(t: String) {}
                    override fun onStartDiscoveryFailed(t: String, code: Int) { failures.incrementAndGet() }
                    override fun onStopDiscoveryFailed(t: String, code: Int) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onServiceFound(info: NsdServiceInfo) {
                        runCatching {
                            if (!active.get() || !seen.add("$type:${info.serviceName}") ||
                                typeCounts.computeIfAbsent(type) { AtomicInteger() }.incrementAndGet() > 24 ||
                                resolves.incrementAndGet() > 192) return
                            authorized()
                            queue.trySend(info to type)
                        }
                    }
                }
                listeners.add(listener)
                batchListeners.add(listener)
                Outbound.check("mdns://224.0.0.251:5353", "DISCOVER", "udp")
                Outbound.record("mdns://224.0.0.251:5353", "DISCOVER", "udp", status = 0, startedAt = System.currentTimeMillis())
                if (android.os.Build.VERSION.SDK_INT >= 34 ||
                    android.os.Build.VERSION.SDK_INT >= 33 && android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.TIRAMISU) >= 3)
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, lan.network, context.mainExecutor, listener)
                }
                delay(DiscoveryCatalog.BATCH_MILLIS)
                batchListeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
                listeners.removeAll(batchListeners.toSet())
            }
            check(failures.get() == 0) { "Some mDNS service types could not be browsed." }
        } finally {
            active.set(false); queue.close(); resolver.cancel()
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }
}
