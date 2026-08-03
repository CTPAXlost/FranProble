package ru.franprobe.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.franprobe.app.model.DnsMessage
import ru.franprobe.app.model.DnsRecordType
import ru.franprobe.app.model.NetworkSnapshot
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RemoteProtocolException(message: String) : IOException(message)

class NetworkEnvironment(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    fun activeNetwork(): Network? = connectivityManager.activeNetwork

    fun snapshot(network: Network?): NetworkSnapshot? {
        network ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        val link = connectivityManager.getLinkProperties(network)
        return NetworkSnapshot(
            networkHandle = network.networkHandle,
            transports = transportNames(capabilities),
            capabilities = capabilityNames(capabilities),
            interfaceName = link?.interfaceName,
            mtu = link?.mtu?.takeIf { it > 0 },
            addresses = link?.linkAddresses?.map { it.toString() }.orEmpty(),
            dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            routes = link?.routes?.map { it.toString() }.orEmpty(),
            privateDnsActive = if (Build.VERSION.SDK_INT >= 28) link?.isPrivateDnsActive == true else false,
            privateDnsServerName = if (Build.VERSION.SDK_INT >= 28) link?.privateDnsServerName else null,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            proxy = link?.httpProxy?.toString()
        )
    }

    private fun transportNames(c: NetworkCapabilities): List<String> = buildList {
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi‑Fi")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Мобильная")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
        if (Build.VERSION.SDK_INT >= 31 && c.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add("USB")
    }

    private fun capabilityNames(c: NetworkCapabilities): List<String> = buildList {
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("INTERNET")
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("VALIDATED")
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) add("CAPTIVE_PORTAL")
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("NOT_METERED")
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) add("NOT_RESTRICTED")
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) add("NOT_VPN")
    }
}

data class DnsProbeData(
    val resolver: String,
    val transport: String,
    val message: DnsMessage,
    val elapsedMs: Long
)

data class DohProbeData(
    val resolver: String,
    val serverName: String,
    val httpStatusLine: String,
    val contentType: String?,
    val protocol: String,
    val tlsVersion: String?,
    val cipherSuite: String?,
    val message: DnsMessage,
    val elapsedMs: Long
)

data class TcpProbeData(
    val ip: String,
    val port: Int,
    val localAddress: String?,
    val elapsedMs: Long
)

data class TlsProbeData(
    val ip: String,
    val port: Int,
    val sni: String?,
    val protocol: String,
    val cipherSuite: String,
    val selectedAlpn: String?,
    val certificateSubject: String?,
    val certificateSans: List<String>,
    val certificateMatchesSni: Boolean?,
    val httpStatusLine: String?,
    val elapsedMs: Long
)

data class UdpProbeData(
    val ip: String,
    val port: Int,
    val responseBytes: Int?,
    val elapsedMs: Long
)

object NetworkTools {
    suspend fun systemResolve(
        network: Network,
        domain: String,
        timeoutMs: Int
    ): List<InetAddress> = withTimeout(timeoutMs.toLong()) {
        withContext(Dispatchers.IO) {
            network.getAllByName(domain).toList()
        }
    }

    suspend fun rawDnsUdp(
        network: Network,
        resolver: String,
        domain: String,
        type: DnsRecordType,
        timeoutMs: Int
    ): DnsProbeData = withContext(Dispatchers.IO) {
        val query = DnsCodec.buildQuery(domain, type)
        val started = System.nanoTime()
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            network.bindSocket(socket)
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(resolver)
            socket.connect(InetSocketAddress(address, 53))
            socket.send(DatagramPacket(query.bytes, query.bytes.size))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val response = buffer.copyOf(packet.length)
            val message = DnsCodec.parseResponse(response, query)
            DnsProbeData(resolver, "UDP", message, elapsedMs(started))
        }
    }

    suspend fun rawDnsTcp(
        network: Network,
        resolver: String,
        domain: String,
        type: DnsRecordType,
        timeoutMs: Int
    ): DnsProbeData = withContext(Dispatchers.IO) {
        val query = DnsCodec.buildQuery(domain, type)
        val started = System.nanoTime()
        network.socketFactory.createSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(InetAddress.getByName(resolver), 53), timeoutMs)
            val output = BufferedOutputStream(socket.getOutputStream())
            output.write((query.bytes.size ushr 8) and 0xFF)
            output.write(query.bytes.size and 0xFF)
            output.write(query.bytes)
            output.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val high = input.read()
            val low = input.read()
            require(high >= 0 && low >= 0) { "DNS/TCP не вернул длину сообщения" }
            val length = (high shl 8) or low
            require(length in 12..65535) { "Некорректная длина DNS/TCP: $length" }
            val response = input.readExactly(length)
            val message = DnsCodec.parseResponse(response, query)
            DnsProbeData(resolver, "TCP", message, elapsedMs(started))
        }
    }

    suspend fun rawDnsTls(
        network: Network,
        resolver: String,
        serverName: String,
        domain: String,
        type: DnsRecordType,
        timeoutMs: Int
    ): DnsProbeData = withContext(Dispatchers.IO) {
        val query = DnsCodec.buildQuery(domain, type)
        val started = System.nanoTime()
        createTlsSocket(
            network = network,
            ip = resolver,
            port = 853,
            sni = serverName,
            protocolPreference = listOf("TLSv1.3", "TLSv1.2"),
            alpn = emptyList(),
            timeoutMs = timeoutMs
        ).use { socket ->
            val output = BufferedOutputStream(socket.outputStream)
            output.write((query.bytes.size ushr 8) and 0xFF)
            output.write(query.bytes.size and 0xFF)
            output.write(query.bytes)
            output.flush()

            val input = BufferedInputStream(socket.inputStream)
            val high = input.read()
            val low = input.read()
            require(high >= 0 && low >= 0) { "DoT не вернул длину DNS-сообщения" }
            val length = (high shl 8) or low
            require(length in 12..65535) { "Некорректная длина DNS/DoT: $length" }
            val response = input.readExactly(length)
            val message = DnsCodec.parseResponse(response, query)
            DnsProbeData(resolver, "DoT", message, elapsedMs(started))
        }
    }

    suspend fun rawDnsHttps(
        network: Network,
        resolver: String,
        serverName: String,
        domain: String,
        type: DnsRecordType,
        timeoutMs: Int
    ): DohProbeData = withContext(Dispatchers.IO) {
        val query = DnsCodec.buildQuery(domain, type)
        val started = System.nanoTime()
        val resolverAddress = InetAddress.getByName(resolver)
        val directDns = Dns { hostname ->
            if (hostname.equals(serverName, ignoreCase = true)) {
                listOf(resolverAddress)
            } else {
                throw UnknownHostException("FranProbe разрешает только $serverName в этом DoH-тесте")
            }
        }
        val client = OkHttpClient.Builder()
            .dns(directDns)
            .socketFactory(network.socketFactory)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((timeoutMs * 2L).coerceAtLeast(timeoutMs.toLong()), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()

        try {
            val request = Request.Builder()
                .url("https://$serverName/dns-query")
                .header("Accept", "application/dns-message")
                .header("User-Agent", "FranProbe/2.0.2")
                .post(query.bytes.toRequestBody("application/dns-message".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val statusLine = "${response.protocol} ${response.code} ${response.message}".trim()
                if (!response.isSuccessful) {
                    throw RemoteProtocolException("DoH ответил: $statusLine")
                }
                val body = response.body.bytes()
                require(body.size in 12..1_048_576) { "Некорректный размер DoH-ответа: ${body.size}" }
                val message = DnsCodec.parseResponse(body, query)
                DohProbeData(
                    resolver = resolver,
                    serverName = serverName,
                    httpStatusLine = statusLine,
                    contentType = response.header("Content-Type"),
                    protocol = response.protocol.toString(),
                    tlsVersion = response.handshake?.tlsVersion?.javaName,
                    cipherSuite = response.handshake?.cipherSuite?.javaName,
                    message = message,
                    elapsedMs = elapsedMs(started)
                )
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    suspend fun tcpConnect(
        network: Network,
        ip: String,
        port: Int,
        timeoutMs: Int
    ): TcpProbeData = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        network.socketFactory.createSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)
            TcpProbeData(
                ip = ip,
                port = port,
                localAddress = socket.localAddress?.hostAddress,
                elapsedMs = elapsedMs(started)
            )
        }
    }

    suspend fun tlsHandshake(
        network: Network,
        ip: String,
        port: Int,
        sni: String?,
        hostHeader: String?,
        protocolPreference: List<String>,
        alpn: List<String>,
        timeoutMs: Int,
        sendHttpRequest: Boolean
    ): TlsProbeData = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        createTlsSocket(
            network = network,
            ip = ip,
            port = port,
            sni = sni,
            protocolPreference = protocolPreference,
            alpn = alpn,
            timeoutMs = timeoutMs
        ).use { socket ->
            val session = socket.session
            val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
            val sans = certificateDnsNames(certificate)
            val normalizedSni = sni?.takeUnless(::isIpLiteral)?.trim()?.trimEnd('.')
            val matches = normalizedSni?.let { host -> certificate?.let { certificateMatchesHost(it, host) } }

            var statusLine: String? = null
            if (sendHttpRequest && (Build.VERSION.SDK_INT < 29 || socket.applicationProtocol != "h2")) {
                val host = hostHeader?.takeIf { it.isNotBlank() } ?: normalizedSni ?: ip
                val output = BufferedOutputStream(socket.outputStream)
                output.write(
                    "GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: FranProbe/2.0.2\r\nConnection: close\r\nAccept: */*\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                output.flush()
                val input = BufferedInputStream(socket.inputStream)
                statusLine = input.readAsciiLine(2048)
            }

            val selectedAlpn = if (Build.VERSION.SDK_INT >= 29) {
                socket.applicationProtocol.takeIf { it.isNotBlank() }
            } else {
                null
            }
            TlsProbeData(
                ip = ip,
                port = port,
                sni = normalizedSni,
                protocol = session.protocol.orEmpty(),
                cipherSuite = session.cipherSuite.orEmpty(),
                selectedAlpn = selectedAlpn,
                certificateSubject = certificate?.subjectX500Principal?.name,
                certificateSans = sans,
                certificateMatchesSni = matches,
                httpStatusLine = statusLine,
                elapsedMs = elapsedMs(started)
            )
        }
    }

    suspend fun udpResponseProbe(
        network: Network,
        ip: String,
        port: Int,
        timeoutMs: Int
    ): UdpProbeData = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        DatagramSocket(null).use { socket ->
            socket.bind(InetSocketAddress(0))
            network.bindSocket(socket)
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(InetAddress.getByName(ip), port))
            val payload = byteArrayOf(0x46, 0x52, 0x41, 0x4E, 0x50, 0x52, 0x4F, 0x42, 0x45)
            socket.send(DatagramPacket(payload, payload.size))
            val responseBytes = try {
                val buffer = ByteArray(2048)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                response.length
            } catch (_: java.net.SocketTimeoutException) {
                null
            }
            UdpProbeData(ip, port, responseBytes, elapsedMs(started))
        }
    }

    fun separateAddresses(addresses: Collection<InetAddress>): Pair<List<String>, List<String>> {
        val ipv4 = addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
        val ipv6 = addresses.filterIsInstance<Inet6Address>().mapNotNull { it.hostAddress }.distinct()
        return ipv4 to ipv6
    }

    private fun createTlsSocket(
        network: Network,
        ip: String,
        port: Int,
        sni: String?,
        protocolPreference: List<String>,
        alpn: List<String>,
        timeoutMs: Int
    ): SSLSocket {
        val rawSocket = network.socketFactory.createSocket()
        try {
            rawSocket.soTimeout = timeoutMs
            rawSocket.connect(InetSocketAddress(InetAddress.getByName(ip), port), timeoutMs)

            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(DiagnosticTrustManager), SecureRandom())
            val socket = context.socketFactory.createSocket(rawSocket, ip, port, true) as SSLSocket
            socket.soTimeout = timeoutMs

            val supported = socket.supportedProtocols.toSet()
            val enabled = protocolPreference.filter { it in supported }
            require(enabled.isNotEmpty()) {
                "Запрошенные версии TLS не поддерживаются устройством: ${protocolPreference.joinToString()}"
            }
            socket.enabledProtocols = enabled.toTypedArray()

            val parameters = socket.sslParameters
            parameters.endpointIdentificationAlgorithm = null
            parameters.serverNames = if (!sni.isNullOrBlank() && !isIpLiteral(sni)) {
                listOf(SNIHostName(sni.trim().trimEnd('.')))
            } else {
                emptyList()
            }
            if (Build.VERSION.SDK_INT >= 29 && alpn.isNotEmpty()) {
                parameters.applicationProtocols = alpn.toTypedArray()
            }
            socket.sslParameters = parameters
            socket.startHandshake()
            return socket
        } catch (error: Throwable) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    private fun certificateDnsNames(certificate: X509Certificate?): List<String> {
        certificate ?: return emptyList()
        return runCatching {
            certificate.subjectAlternativeNames.orEmpty()
                .filter { it.size >= 2 && it[0] == 2 }
                .mapNotNull { it[1]?.toString() }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun certificateMatchesHost(certificate: X509Certificate, host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        val names = certificateDnsNames(certificate)
        if (names.isNotEmpty()) return names.any { patternMatches(it, normalized) }
        val cn = Regex("(?:^|,)CN=([^,]+)", RegexOption.IGNORE_CASE)
            .find(certificate.subjectX500Principal.name)?.groupValues?.getOrNull(1)
        return cn?.let { patternMatches(it, normalized) } == true
    }

    private fun patternMatches(pattern: String, host: String): Boolean {
        val p = pattern.lowercase().trimEnd('.')
        if (!p.startsWith("*.")) return p == host
        val suffix = p.removePrefix("*")
        return host.endsWith(suffix) && host.count { it == '.' } == p.count { it == '.' }
    }

    fun isIpLiteral(value: String): Boolean {
        val trimmed = value.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isBlank()) return false
        if (trimmed.contains(':')) {
            return runCatching { InetAddress.getByName(trimmed) is Inet6Address }.getOrDefault(false)
        }
        val parts = trimmed.split('.')
        return parts.size == 4 && parts.all { part ->
            val number = part.toIntOrNull()
            part.isNotEmpty() && part.all(Char::isDigit) && number != null && number in 0..255
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    private fun BufferedInputStream.readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(result, offset, length - offset)
            require(read >= 0) { "Поток завершился раньше DNS-сообщения" }
            offset += read
        }
        return result
    }

    private fun BufferedInputStream.readAsciiLine(maxBytes: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < maxBytes) {
            val value = read()
            if (value < 0) break
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.takeIf { it.isNotEmpty() }?.toByteArray()?.toString(Charsets.US_ASCII)
    }


    // Только для диагностических TLS-проб: сертификат сохраняется и сравнивается вручную.
    // Пользовательские данные через эти соединения не передаются.
    private object DiagnosticTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
