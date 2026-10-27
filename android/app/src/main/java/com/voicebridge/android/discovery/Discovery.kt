package com.voicebridge.android.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** DNS-SD service type in the form [NsdManager] expects; the wire type is `_voicebridge._tcp.local.`. */
const val SERVICE_TYPE = "_voicebridge._tcp"

private const val TAG = "Discovery"
private const val RESOLVE_TIMEOUT_MILLIS = 5_000L

/** A Windows VoiceBridge instance that is currently advertised on this LAN. */
data class DiscoveredComputer(
    val serviceName: String,
    val deviceId: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
)

/** DNS-SD browse could not be started; [errorCode] is the `NsdManager` failure code. */
class DiscoveryException(val errorCode: Int) :
    Exception("DNS-SD discovery failed with error code $errorCode")

/**
 * Browses `_voicebridge._tcp` and emits the devices whose TXT record passes [parse] and whose SRV
 * record resolved to an address.
 *
 * Each collection starts its own browse and stops it when the collector is cancelled, so the
 * returned flow can be collected by exactly one owner at a time.
 */
class Discovery(context: Context) {
    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun start(): Flow<List<DiscoveredComputer>> = callbackFlow {
        val events = Channel<Event>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DNS-SD discovery could not start (error $errorCode)")
                events.trySend(Event.StartFailed(errorCode))
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                events.trySend(Event.Found(serviceInfo))
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                events.trySend(Event.Lost(serviceInfo.serviceName))
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DNS-SD discovery could not stop (error $errorCode)")
            }
        }
        // The NsdManager callbacks arrive on a platform handler thread, so they only enqueue here;
        // one worker coroutine owns the maps, the resolves and the emission.
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        val worker = launch(Dispatchers.IO) {
            val computers = LinkedHashMap<String, DiscoveredComputer>()
            val deviceIdByService = HashMap<String, String>()
            for (event in events) {
                when (event) {
                    is Event.StartFailed -> {
                        close(DiscoveryException(event.errorCode))
                        break
                    }
                    is Event.Found -> {
                        // Resolving is slow and NsdManager rejects overlapping requests, so it runs
                        // on this single worker while further found events wait in the channel.
                        // NsdManager occasionally drops a resolve callback; a stalled resolve would
                        // otherwise block this single worker and freeze discovery for good.
                        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) {
                            resolve(event.serviceInfo)
                        } ?: continue
                        val computer = computerOf(resolved)
                        if (computer == null) {
                            Log.w(TAG, "Ignoring ${resolved.serviceName}: no usable TXT record or address")
                            continue
                        }
                        deviceIdByService[computer.serviceName] = computer.deviceId
                        if (computers.putIfAbsent(computer.deviceId, computer) == null) {
                            send(computers.values.toList())
                        }
                    }
                    is Event.Lost -> {
                        val deviceId = deviceIdByService.remove(event.serviceName) ?: continue
                        if (computers.remove(deviceId) != null) send(computers.values.toList())
                    }
                }
            }
        }
        awaitClose {
            worker.cancel()
            events.close()
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (error: IllegalArgumentException) {
                // Thrown when the browse is already stopping; there is nothing left to stop.
                Log.w(TAG, "DNS-SD discovery was already stopped", error)
            }
        }
    }

    private fun computerOf(info: NsdServiceInfo): DiscoveredComputer? {
        val record = parse(info.attributes) ?: return null
        val host = hostOf(info) ?: return null
        if (info.port !in 1..65535) return null
        return DiscoveredComputer(
            serviceName = info.serviceName,
            deviceId = record.deviceId,
            displayName = record.displayName,
            host = host,
            port = info.port,
            fingerprint = record.fingerprint,
        )
    }

    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses = addressesOf(info)
        // IPv4 first: it is the address a Windows host always advertises and the one a link-local
        // IPv6 scope id would make unusable inside a URL.
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return address?.hostAddress
    }

    private fun addressesOf(info: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34 deprecates host in favour of the list, which may hold IPv4 and IPv6 at once.
            info.hostAddresses.ifEmpty { listOfNotNull(deprecatedHost(info)) }
        } else {
            listOfNotNull(deprecatedHost(info))
        }

    @Suppress("DEPRECATION")
    private fun deprecatedHost(info: NsdServiceInfo): InetAddress? = info.host

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? {
        val finished = AtomicBoolean(false)
        return suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Could not resolve ${serviceInfo.serviceName} (error $errorCode)")
                    if (finished.compareAndSet(false, true)) continuation.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (finished.compareAndSet(false, true)) continuation.resume(serviceInfo)
                }
            }
            try {
                nsdManager.resolveService(info, listener)
            } catch (error: IllegalArgumentException) {
                // NsdManager throws this when another resolve is already in flight; the entry is
                // skipped and the browse keeps running.
                Log.w(TAG, "Could not start resolving ${info.serviceName}", error)
                if (finished.compareAndSet(false, true)) continuation.resume(null)
            }
        }
    }
}

private sealed interface Event {
    class Found(val serviceInfo: NsdServiceInfo) : Event

    class Lost(val serviceName: String) : Event

    class StartFailed(val errorCode: Int) : Event
}
