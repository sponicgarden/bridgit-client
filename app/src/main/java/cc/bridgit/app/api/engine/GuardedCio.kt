/*
 * Adapted from Ktor 2.3.12 (CIOApplicationEngine.kt and backend/HttpServer.kt),
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Apache 2.0 license.
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(io.ktor.util.InternalAPI::class)

package cc.bridgit.app.api.engine

import io.ktor.events.raiseCatching
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.Request
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.awaitClosed
import io.ktor.server.cio.backend.port
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.call
import io.ktor.server.cio.CIOApplicationCall
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.cio.CIOApplicationRequest
import io.ktor.server.cio.HttpServer
import io.ktor.server.cio.HttpServerSettings
import io.ktor.server.cio.backend.ServerIncomingConnection
import io.ktor.server.cio.backend.ServerRequestScope
import io.ktor.server.cio.backend.startServerConnectionPipeline
import io.ktor.server.cio.backend.toNetworkAddress
import io.ktor.server.cio.internal.WeakTimeoutQueue
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineEnvironment
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.BaseApplicationEngine
import io.ktor.server.engine.ConnectorType
import io.ktor.server.application.ServerReady
import io.ktor.server.engine.DefaultUncaughtExceptionHandler
import io.ktor.server.engine.handleFailure
import io.ktor.server.engine.withPort
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.httpVersion
import io.ktor.server.response.respond
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.util.pipeline.execute
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.channels.ClosedChannelException

/**
 * Ktor's CIO engine with one change: the accept loop can end. Stock CIO (2.3.12, and still on Ktor's main branch)
 * does `catch (IOException) { continue }` around `accept()` with no suspension point. When Tailscale goes away,
 * Android destroys the socket bound to its address, every `accept()` fails at once, and the loop spins at 100% CPU
 * per address; `stop()` cannot cancel it, and closing the socket only turns the error into another IOException.
 * Here a failed accept waits (backing off to 1 s) before retrying, which gives cancellation a place to land, and a
 * socket that stays broken fails the engine ([stopped] completes with [DeadListener]). ApiServer then asks
 * BridgeService to rebind, even when the address list looks unchanged (Tailscale back within the settle delay).
 */
object GuardedCio : ApplicationEngineFactory<GuardedCioEngine, CIOApplicationEngine.Configuration> {
    override fun create(
        environment: ApplicationEngineEnvironment,
        configure: CIOApplicationEngine.Configuration.() -> Unit
    ): GuardedCioEngine = GuardedCioEngine(environment, configure)
}

class GuardedCioEngine internal constructor(
    environment: ApplicationEngineEnvironment,
    configure: CIOApplicationEngine.Configuration.() -> Unit,
    private val giveUpAfter: Int,
    private val accept: suspend (ServerSocket) -> io.ktor.network.sockets.Socket
) : BaseApplicationEngine(environment) {
    constructor(environment: ApplicationEngineEnvironment, configure: CIOApplicationEngine.Configuration.() -> Unit) :
        this(environment, configure, ACCEPT_GIVE_UP_AFTER, { it.accept() })

    private val configuration = CIOApplicationEngine.Configuration().apply(configure)
    private val dispatcher = Dispatchers.IO
    private val startupJob: CompletableDeferred<Unit> = CompletableDeferred()
    private val stopRequest: CompletableJob = Job()
    private val serverJob: Job

    /** Completes when the engine is gone: stopped, or with [DeadListener] when a listening socket died. */
    val stopped: Job get() = serverJob

    init {
        serverJob = initServerJob()
        serverJob.invokeOnCompletion { cause ->
            cause?.let { stopRequest.completeExceptionally(it) }
            cause?.let { startupJob.completeExceptionally(it) }
            environment.stop()
        }
    }

    override fun start(wait: Boolean): ApplicationEngine {
        serverJob.start()
        runBlocking {
            startupJob.await()
            environment.monitor.raiseCatching(ServerReady, environment, environment.log)
            if (wait) serverJob.join()
        }
        return this
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        stopRequest.complete()
        runBlocking {
            if (withTimeoutOrNull(gracePeriodMillis) { serverJob.join() } == null) {
                serverJob.cancel()
                if (withTimeoutOrNull(timeoutMillis - gracePeriodMillis) { serverJob.join() } == null) environment.stop()
            }
        }
    }

    private fun CoroutineScope.startConnector(host: String, port: Int): HttpServer {
        val settings = HttpServerSettings(
            host = host,
            port = port,
            connectionIdleTimeoutSeconds = configuration.connectionIdleTimeoutSeconds.toLong(),
            reuseAddress = configuration.reuseAddress
        )
        return guardedHttpServer(settings, giveUpAfter, accept) { request -> handleRequest(request) }
    }

    private suspend fun addHandlerForExpectedHeader(output: ByteWriteChannel, cioCall: CIOApplicationCall) {
        val phase = PipelinePhase("ExpectedHeaderPhase")
        cioCall.request.pipeline.insertPhaseBefore(ApplicationReceivePipeline.Before, phase)
        cioCall.request.pipeline.intercept(phase) {
            val request = cioCall.request
            val version = HttpProtocolVersion.parse(request.httpVersion)
            val expect = request.headers[HttpHeaders.Expect]?.lowercase()
            if (expect == null || version == HttpProtocolVersion.HTTP_1_0 || !hasBody(request)) {
                return@intercept
            }
            if (expect != "100-continue") {
                call.respond(HttpStatusCode.ExpectationFailed)
            } else {
                output.writeStringUtf8("HTTP/1.1 100 Continue\r\n")
                output.flush()
            }
        }
    }

    private fun hasBody(request: CIOApplicationRequest): Boolean {
        val contentLength = request.headers[HttpHeaders.ContentLength]?.toLong()
        return request.headers[HttpHeaders.TransferEncoding] != null || (contentLength != null && contentLength > 0)
    }

    private suspend fun ServerRequestScope.handleRequest(request: Request) {
        withContext(dispatcher) {
            val call = CIOApplicationCall(
                application, request, input, output, dispatcher, dispatcher, upgraded, remoteAddress, localAddress
            )
            try {
                addHandlerForExpectedHeader(output, call)
                pipeline.execute(call)
            } catch (error: Throwable) {
                handleFailure(call, error)
            } finally {
                call.release()
            }
        }
    }

    private fun initServerJob(): Job {
        val environment = environment
        val resolved = resolvedConnectors
        // A dead listener fails this root job; report it through [stopped], not the thread's uncaught handler (a crash).
        val failureHandler = environment.parentCoroutineContext[CoroutineExceptionHandler]
            ?: CoroutineExceptionHandler { _, cause -> environment.log.warn("Listener ended: ${cause.message}") }
        return CoroutineScope(environment.parentCoroutineContext + dispatcher + failureHandler).launch(start = CoroutineStart.LAZY) {
            val servers = ArrayList<HttpServer>(environment.connectors.size)
            try {
                environment.connectors.forEach {
                    if (it.type == ConnectorType.HTTPS) throw UnsupportedOperationException("HTTPS is not supported")
                }
                withContext(dispatcher) { environment.start() }
                val started = environment.connectors.map { it to startConnector(it.host, it.port) }
                servers.addAll(started.map { it.second })
                resolved.complete(started.map { (c, s) -> c.withPort(s.serverSocket.await().localAddress.port) })
            } catch (cause: Throwable) {
                servers.forEach { it.rootServerJob.cancel() }
                stopRequest.completeExceptionally(cause)
                startupJob.completeExceptionally(cause)
                throw cause
            }
            startupJob.complete(Unit)
            stopRequest.join()
            servers.forEach { it.acceptJob.cancel() }
            withContext(dispatcher) {
                environment.monitor.raise(ApplicationStopPreparing, environment)
            }
        }
    }

}

/** Delays after consecutive accept failures: 10 ms doubling to 1 s. */
internal fun acceptBackoffMillis(failures: Int): Long = (10L shl (failures - 1).coerceIn(0, 7)).coerceAtMost(1000L)

/** Consecutive accept failures (about 14 s with the backoff) after which the socket counts as dead. */
internal const val ACCEPT_GIVE_UP_AFTER = 20

/** The listening socket was closed under us or kept failing; it will not recover. */
class DeadListener(cause: IOException) : IOException("Listener socket died: ${cause.message}", cause)

/**
 * Accepts until cancelled. A failed accept (a destroyed socket fails every call at once) waits [acceptBackoffMillis]
 * before the next try, so the loop never spins and cancellation always has a place to land. A socket closed under
 * us, or [giveUpAfter] failures in a row, throws [DeadListener].
 */
internal suspend fun <T> acceptUntilDead(
    accept: suspend () -> T,
    giveUpAfter: Int = ACCEPT_GIVE_UP_AFTER,
    onAccepted: (T) -> Unit
) {
    var failures = 0
    while (true) {
        val client = try {
            accept().also { failures = 0 }
        } catch (cause: IOException) {
            currentCoroutineContext().ensureActive()
            if (cause is ClosedChannelException || ++failures >= giveUpAfter) throw DeadListener(cause)
            delay(acceptBackoffMillis(failures))
            continue
        }
        onAccepted(client)
    }
}

/** Ktor's `httpServer` with an accept loop that backs off on errors and fails once the socket is dead. */
fun CoroutineScope.guardedHttpServer(
    settings: HttpServerSettings,
    giveUpAfter: Int = ACCEPT_GIVE_UP_AFTER,
    accept: suspend (ServerSocket) -> io.ktor.network.sockets.Socket = { it.accept() },
    handler: suspend ServerRequestScope.(Request) -> Unit
): HttpServer {
    val socket = CompletableDeferred<ServerSocket>()
    val serverLatch: CompletableJob = Job()
    val serverJob = launch(CoroutineName("server-root-${settings.port}"), start = CoroutineStart.UNDISPATCHED) {
        serverLatch.join()
    }
    val selector = SelectorManager(coroutineContext)
    val timeout = WeakTimeoutQueue(settings.connectionIdleTimeoutSeconds * 1000L)

    val acceptJob = launch(serverJob + CoroutineName("accept-${settings.port}")) {
        aSocket(selector).tcp().bind(settings.host, settings.port) { reuseAddress = settings.reuseAddress }.use { server ->
            socket.complete(server)
            val exceptionHandler = coroutineContext[CoroutineExceptionHandler]
                ?: DefaultUncaughtExceptionHandler(KtorSimpleLogger("GuardedCio"))
            val connectionScope = CoroutineScope(
                coroutineContext + SupervisorJob(serverJob) + exceptionHandler + CoroutineName("request")
            )
            try {
                acceptUntilDead({ accept(server) }, giveUpAfter) { client ->
                    val connection = ServerIncomingConnection(
                        client.openReadChannel(),
                        client.openWriteChannel(),
                        client.remoteAddress.toNetworkAddress(),
                        client.localAddress.toNetworkAddress()
                    )
                    val clientJob = connectionScope.startServerConnectionPipeline(connection, timeout, handler)
                    clientJob.invokeOnCompletion { client.close() }
                }
            } finally {
                server.close()
                server.awaitClosed()
                connectionScope.coroutineContext.cancel()
            }
        }
    }

    acceptJob.invokeOnCompletion { cause ->
        cause?.let { socket.completeExceptionally(it) }
        serverLatch.complete()
        timeout.process()
    }
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    serverJob.invokeOnCompletion(onCancelling = true) { timeout.cancel() }
    serverJob.invokeOnCompletion { selector.close() }

    return HttpServer(serverJob, acceptJob, socket)
}
