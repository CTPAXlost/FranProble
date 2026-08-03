package ru.franprobe.app.engine

import android.content.Context
import android.net.Network
import android.os.Build
import kotlinx.coroutines.CancellationException
import ru.franprobe.app.BuildConfig
import ru.franprobe.app.model.DiagnosticConfig
import ru.franprobe.app.model.DiagnosticMode
import ru.franprobe.app.model.DiagnosticReport
import ru.franprobe.app.model.DnsRecordType
import ru.franprobe.app.model.NetworkSnapshot
import ru.franprobe.app.model.ProbeResult
import ru.franprobe.app.model.ProbeStatus
import ru.franprobe.app.model.RunProgress
import ru.franprobe.app.model.TestLayer
import ru.franprobe.app.net.NetworkEnvironment
import ru.franprobe.app.net.LocalCapabilityException
import ru.franprobe.app.net.NetworkTools
import ru.franprobe.app.net.RemoteProtocolException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.time.Instant
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

private data class EncryptedDnsEndpoint(
    val ip: String,
    val dotName: String,
    val dohName: String,
    val label: String
)

class DiagnosticEngine(context: Context) {
    private val environment = NetworkEnvironment(context.applicationContext)

    suspend fun run(
        config: DiagnosticConfig,
        onProgress: (RunProgress) -> Unit = {}
    ): DiagnosticReport {
        val startedAt = Instant.now()
        val startedNanos = System.nanoTime()
        val results = mutableListOf<ProbeResult>()
        val total = estimateSteps(config)
        var step = 0

        fun progress(message: String) {
            step++
            onProgress(RunProgress(step.coerceAtMost(total), total, message))
        }

        val network = environment.activeNetwork()
        val snapshot = environment.snapshot(network)
        results += networkResult(snapshot)
        progress("Собраны параметры активной сети")

        if (network == null || snapshot == null) {
            val finished = Instant.now()
            return DiagnosticReport(
                appVersion = BuildConfig.VERSION_NAME,
                startedAt = startedAt.toString(),
                finishedAt = finished.toString(),
                durationMs = elapsedMs(startedNanos),
                config = config,
                network = snapshot,
                results = results,
                conclusions = listOf("Android не предоставил активную сеть. Остальные проверки не запускались.")
            )
        }

        val resolvers = when (config.mode) {
            DiagnosticMode.QUICK -> config.dnsServers.take(3)
            else -> config.dnsServers
        }.distinct()

        for (resolver in resolvers) {
            results += runProbe(
                layer = TestLayer.DNS,
                name = "DNS по UDP",
                target = "$resolver:53 / example.com A",
                success = {
                    val data = NetworkTools.rawDnsUdp(network, resolver, "example.com", DnsRecordType.A, config.connectTimeoutMs)
                    val addresses = data.message.addresses
                    ProbeResult(
                        layer = TestLayer.DNS,
                        name = "DNS по UDP",
                        target = "$resolver:53 / example.com A",
                        status = dnsStatus(data.message.rcode, addresses, data.message.truncated),
                        summary = dnsSummary(data.message.rcode, addresses, data.message.truncated),
                        durationMs = data.elapsedMs,
                        details = mapOf(
                            "resolver" to resolver,
                            "transport" to data.transport
                        ) + dnsMessageDetails(data.message)
                    )
                }
            )
            progress("Проверен DNS UDP $resolver")

            if (config.mode != DiagnosticMode.QUICK || resolver == resolvers.firstOrNull()) {
                results += runProbe(
                    layer = TestLayer.DNS,
                    name = "DNS по TCP",
                    target = "$resolver:53 / example.com A",
                    success = {
                        val data = NetworkTools.rawDnsTcp(network, resolver, "example.com", DnsRecordType.A, config.connectTimeoutMs)
                        val addresses = data.message.addresses
                        ProbeResult(
                            layer = TestLayer.DNS,
                            name = "DNS по TCP",
                            target = "$resolver:53 / example.com A",
                            status = dnsStatus(data.message.rcode, addresses, data.message.truncated),
                            summary = dnsSummary(data.message.rcode, addresses, data.message.truncated),
                            durationMs = data.elapsedMs,
                            details = mapOf(
                                "resolver" to resolver,
                                "transport" to data.transport
                            ) + dnsMessageDetails(data.message)
                        )
                    }
                )
                progress("Проверен DNS TCP $resolver")
            }
        }

        if (config.mode == DiagnosticMode.FULL) {
            runEncryptedDnsTests(network, config, results, ::progress)
        }

        val responsiveResolvers = results.asSequence()
            .filter { it.layer == TestLayer.DNS && it.status == ProbeStatus.AVAILABLE }
            .mapNotNull { it.details["resolver"] }
            .distinct()
            .toList()
        val effectiveConfig = config.copy(
            dnsServers = (responsiveResolvers + config.dnsServers).distinct()
        )

        when (config.mode) {
            DiagnosticMode.SNI_MATRIX -> runSniMatrix(network, effectiveConfig, results, ::progress)
            DiagnosticMode.QUICK, DiagnosticMode.FULL -> runDomainTests(network, effectiveConfig, results, ::progress)
        }

        if (config.mode == DiagnosticMode.FULL) {
            runFixedIpTests(network, effectiveConfig, results, ::progress)
        }

        val conclusions = buildConclusions(snapshot, results)
        conclusions.forEachIndexed { index, conclusion ->
            results += ProbeResult(
                layer = TestLayer.SUMMARY,
                name = "Вывод ${index + 1}",
                target = "Сводный анализ",
                status = ProbeStatus.LIMITED,
                summary = conclusion,
                durationMs = 0
            )
        }

        val finished = Instant.now()
        onProgress(RunProgress(total, total, "Диагностика завершена"))
        return DiagnosticReport(
            appVersion = BuildConfig.VERSION_NAME,
            startedAt = startedAt.toString(),
            finishedAt = finished.toString(),
            durationMs = elapsedMs(startedNanos),
            config = config,
            network = snapshot,
            results = results,
            conclusions = conclusions
        )
    }

    private suspend fun runEncryptedDnsTests(
        network: Network,
        config: DiagnosticConfig,
        results: MutableList<ProbeResult>,
        progress: (String) -> Unit
    ) {
        val endpoints = listOf(
            EncryptedDnsEndpoint("1.1.1.1", "one.one.one.one", "cloudflare-dns.com", "Cloudflare"),
            EncryptedDnsEndpoint("8.8.8.8", "dns.google", "dns.google", "Google"),
            EncryptedDnsEndpoint("77.88.8.8", "common.dot.dns.yandex.net", "common.dot.dns.yandex.net", "Яндекс")
        )
        for (endpoint in endpoints) {
            results += runProbe(
                layer = TestLayer.DNS,
                name = "DNS over TLS",
                target = "${endpoint.ip}:853 / SNI=${endpoint.dotName} (${endpoint.label})",
                success = {
                    val data = NetworkTools.rawDnsTls(
                        network = network,
                        resolver = endpoint.ip,
                        serverName = endpoint.dotName,
                        domain = "example.com",
                        type = DnsRecordType.A,
                        timeoutMs = config.readTimeoutMs
                    )
                    ProbeResult(
                        layer = TestLayer.DNS,
                        name = "DNS over TLS",
                        target = "${endpoint.ip}:853 / SNI=${endpoint.dotName} (${endpoint.label})",
                        status = dnsStatus(data.message.rcode, data.message.addresses, data.message.truncated),
                        summary = dnsSummary(data.message.rcode, data.message.addresses, data.message.truncated),
                        durationMs = data.elapsedMs,
                        details = mapOf(
                            "resolver" to endpoint.ip,
                            "transport" to "DoT",
                            "sni" to endpoint.dotName
                        ) + dnsMessageDetails(data.message)
                    )
                }
            )
            progress("Проверен DoT ${endpoint.label}")

            results += runProbe(
                layer = TestLayer.DNS,
                name = "DNS over HTTPS",
                target = "${endpoint.ip}:443 / https://${endpoint.dohName}/dns-query (${endpoint.label})",
                success = {
                    val data = NetworkTools.rawDnsHttps(
                        network = network,
                        resolver = endpoint.ip,
                        serverName = endpoint.dohName,
                        domain = "example.com",
                        type = DnsRecordType.A,
                        timeoutMs = config.readTimeoutMs
                    )
                    ProbeResult(
                        layer = TestLayer.DNS,
                        name = "DNS over HTTPS",
                        target = "${endpoint.ip}:443 / https://${endpoint.dohName}/dns-query (${endpoint.label})",
                        status = dnsStatus(data.message.rcode, data.message.addresses, data.message.truncated),
                        summary = "${data.httpStatusLine}; ${dnsSummary(data.message.rcode, data.message.addresses, data.message.truncated)}",
                        durationMs = data.elapsedMs,
                        details = mapOf(
                            "resolver" to endpoint.ip,
                            "transport" to "DoH",
                            "sni" to endpoint.dohName,
                            "httpStatusLine" to data.httpStatusLine,
                            "contentType" to (data.contentType ?: "—"),
                            "httpProtocol" to data.protocol,
                            "tlsVersion" to (data.tlsVersion ?: "—"),
                            "cipherSuite" to (data.cipherSuite ?: "—")
                        ) + dnsMessageDetails(data.message)
                    )
                }
            )
            progress("Проверен DoH ${endpoint.label}")
        }
    }

    private suspend fun runDomainTests(
        network: Network,
        config: DiagnosticConfig,
        results: MutableList<ProbeResult>,
        progress: (String) -> Unit
    ) {
        val domains = when (config.mode) {
            DiagnosticMode.QUICK -> listOf("yandex.ru", "telegram.org", "github.com")
            else -> config.targetDomains
        }.map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }.distinct()

        for (domain in domains) {
            val systemAddresses = mutableListOf<InetAddress>()
            results += runProbe(
                layer = TestLayer.DNS,
                name = "Системное DNS",
                target = domain,
                success = {
                    val started = System.nanoTime()
                    val addresses = NetworkTools.systemResolve(network, domain, config.readTimeoutMs)
                    systemAddresses += addresses
                    val (v4, v6) = NetworkTools.separateAddresses(addresses)
                    ProbeResult(
                        layer = TestLayer.DNS,
                        name = "Системное DNS",
                        target = domain,
                        status = if (addresses.isNotEmpty()) ProbeStatus.AVAILABLE else ProbeStatus.UNREACHABLE,
                        summary = if (addresses.isNotEmpty()) "Получено адресов: ${addresses.size}" else "DNS вернул пустой ответ",
                        durationMs = elapsedMs(started),
                        details = mapOf("IPv4" to v4.joinToString(), "IPv6" to v6.joinToString())
                    )
                }
            )
            progress("Системное DNS: $domain")

            val systemIps = systemAddresses.mapNotNull { it.hostAddress }.distinct()
            val directIps = if (config.mode == DiagnosticMode.FULL || systemIps.isEmpty()) {
                resolveViaConfiguredDns(network, config, domain, results, progress)
            } else {
                emptyList()
            }

            if (config.mode == DiagnosticMode.FULL && systemIps.isNotEmpty() && directIps.isNotEmpty()) {
                val systemSet = systemIps.toSet()
                val directSet = directIps.toSet()
                val overlap = systemSet.intersect(directSet)
                val same = systemSet == directSet
                results += ProbeResult(
                    layer = TestLayer.DNS,
                    name = "Сравнение DNS-ответов",
                    target = domain,
                    status = when {
                        same -> ProbeStatus.AVAILABLE
                        overlap.isNotEmpty() -> ProbeStatus.LIMITED
                        else -> ProbeStatus.LIMITED
                    },
                    summary = when {
                        same -> "Системный и прямой DNS вернули одинаковые адреса"
                        overlap.isNotEmpty() -> "Наборы DNS-адресов различаются, но имеют общие значения"
                        else -> "Системный и прямой DNS вернули полностью разные адреса"
                    },
                    durationMs = 0,
                    details = mapOf(
                        "systemAddresses" to systemIps.joinToString(),
                        "directAddresses" to directIps.joinToString(),
                        "commonAddresses" to overlap.joinToString()
                    )
                )
                progress("Сопоставлены DNS-ответы для $domain")
            }

            val uniqueIps = (systemIps + directIps).distinct()
            if (uniqueIps.isEmpty()) {
                results += notTested(
                    TestLayer.TCP,
                    "TCP после DNS",
                    "$domain:443",
                    "IP-адрес не получен ни системным, ни прямым DNS. TCP не запускался."
                )
                results += notTested(
                    TestLayer.TLS,
                    "TLS/SNI после DNS",
                    "$domain:443",
                    "IP-адрес отсутствует. TLS не запускался и не помечен как заблокированный."
                )
                progress("Пропущены TCP/TLS для $domain: нет IP")
                continue
            }

            val selectedIps = selectAddresses(uniqueIps, config.maxResolvedIpsPerDomain)
            var reachableIp: String? = null
            for (ip in selectedIps) {
                val tcp = runProbe(
                    layer = TestLayer.TCP,
                    name = if (ip.contains(':')) "TCP IPv6" else "TCP IPv4",
                    target = "$ip:443 ($domain)",
                    success = {
                        val data = NetworkTools.tcpConnect(network, ip, 443, config.connectTimeoutMs)
                        reachableIp = reachableIp ?: ip
                        ProbeResult(
                            layer = TestLayer.TCP,
                            name = if (ip.contains(':')) "TCP IPv6" else "TCP IPv4",
                            target = "$ip:443 ($domain)",
                            status = ProbeStatus.AVAILABLE,
                            summary = "TCP-соединение установлено",
                            durationMs = data.elapsedMs,
                            details = mapOf("ip" to ip, "port" to "443", "localAddress" to (data.localAddress ?: "—"))
                        )
                    }
                )
                results += tcp
                progress("TCP $domain через $ip")
            }

            val tlsIp = reachableIp
            if (tlsIp == null) {
                results += notTested(
                    TestLayer.TLS,
                    "TLS с правильным SNI",
                    "$domain:443",
                    "Ни одно прямое TCP-подключение к полученным IP не установилось. TLS ClientHello не отправлялся."
                )
                progress("Пропущен TLS/SNI для $domain: TCP не установлен")
                continue
            }
            results += runProbe(
                layer = TestLayer.TLS,
                name = "TLS с правильным SNI",
                target = "$tlsIp:443 / SNI=$domain",
                success = {
                    val data = NetworkTools.tlsHandshake(
                        network = network,
                        ip = tlsIp,
                        port = 443,
                        sni = domain,
                        hostHeader = domain,
                        protocolPreference = listOf("TLSv1.3", "TLSv1.2"),
                        alpn = listOf("http/1.1"),
                        timeoutMs = config.readTimeoutMs,
                        sendHttpRequest = true
                    )
                    val mismatch = data.certificateMatchesSni == false
                    ProbeResult(
                        layer = TestLayer.TLS,
                        name = "TLS с правильным SNI",
                        target = "$tlsIp:443 / SNI=$domain",
                        status = if (mismatch || data.httpErrorType != null) ProbeStatus.LIMITED else ProbeStatus.AVAILABLE,
                        summary = when {
                            mismatch -> "TLS прошёл, но сертификат не соответствует SNI"
                            data.httpStatusLine != null -> "TLS и HTTP ответили: ${data.httpStatusLine}"
                            data.httpErrorType != null -> "TLS-рукопожатие успешно, но HTTP-проба завершилась отдельно: ${data.httpErrorMessage}"
                            else -> "TLS-рукопожатие успешно"
                        },
                        durationMs = data.elapsedMs,
                        details = tlsDetails(data)
                    )
                }
            )
            progress("TLS/SNI $domain")
        }
    }

    private suspend fun resolveViaConfiguredDns(
        network: Network,
        config: DiagnosticConfig,
        domain: String,
        results: MutableList<ProbeResult>,
        progress: (String) -> Unit
    ): List<String> {
        val collected = mutableListOf<String>()
        val recordTypes = if (config.mode == DiagnosticMode.QUICK) {
            listOf(DnsRecordType.A)
        } else {
            listOf(DnsRecordType.A, DnsRecordType.AAAA)
        }

        for (resolver in config.dnsServers.distinct()) {
            for (recordType in recordTypes) {
                var answers = emptyList<String>()
                var truncated = false
                val udpResult = runProbe(
                    layer = TestLayer.DNS,
                    name = "Прямое DNS-разрешение",
                    target = "$resolver:53 / $domain ${recordType.name}",
                    success = {
                        val data = NetworkTools.rawDnsUdp(
                            network,
                            resolver,
                            domain,
                            recordType,
                            config.connectTimeoutMs
                        )
                        answers = data.message.addresses
                        truncated = data.message.truncated
                        collected += answers
                        ProbeResult(
                            layer = TestLayer.DNS,
                            name = "Прямое DNS-разрешение",
                            target = "$resolver:53 / $domain ${recordType.name}",
                            status = dnsStatus(data.message.rcode, answers, truncated),
                            summary = dnsSummary(data.message.rcode, answers, truncated),
                            durationMs = data.elapsedMs,
                            details = mapOf(
                                "resolver" to resolver,
                                "transport" to data.transport,
                                "recordType" to recordType.name,
                                "answers" to answers.joinToString(),
                                "rcode" to data.message.rcode.toString(),
                                "truncated" to truncated.toString(),
                                "validatedTransactionId" to "true",
                                "validatedQuestion" to "true"
                            ) + dnsMessageDetails(data.message)
                        )
                    }
                )
                results += udpResult
                progress("Прямой DNS $domain ${recordType.name} через $resolver")

                if (truncated) {
                    val tcpResult = runProbe(
                        layer = TestLayer.DNS,
                        name = "Повтор DNS по TCP",
                        target = "$resolver:53 / $domain ${recordType.name}",
                        success = {
                            val data = NetworkTools.rawDnsTcp(
                                network,
                                resolver,
                                domain,
                                recordType,
                                config.readTimeoutMs
                            )
                            answers = data.message.addresses
                            collected += answers
                            ProbeResult(
                                layer = TestLayer.DNS,
                                name = "Повтор DNS по TCP",
                                target = "$resolver:53 / $domain ${recordType.name}",
                                status = dnsStatus(data.message.rcode, answers, data.message.truncated),
                                summary = dnsSummary(data.message.rcode, answers, data.message.truncated),
                                durationMs = data.elapsedMs,
                                details = mapOf(
                                    "resolver" to resolver,
                                    "transport" to "TCP",
                                    "recordType" to recordType.name,
                                    "answers" to answers.joinToString(),
                                    "rcode" to data.message.rcode.toString()
                                ) + dnsMessageDetails(data.message)
                            )
                        }
                    )
                    results += tcpResult
                    progress("Повтор DNS/TCP $domain через $resolver")
                }
            }
            if (config.mode == DiagnosticMode.QUICK && collected.isNotEmpty()) break
        }
        return collected.distinct()
    }

    private suspend fun runSniMatrix(
        network: Network,
        config: DiagnosticConfig,
        results: MutableList<ProbeResult>,
        progress: (String) -> Unit
    ) {
        val ip = config.customMatrixIp.trim().removePrefix("[").removeSuffix("]")
        if (!NetworkTools.isIpLiteral(ip)) {
            results += ProbeResult(
                layer = TestLayer.TLS,
                name = "Матрица SNI",
                target = config.customMatrixIp,
                status = ProbeStatus.ERROR,
                summary = "Для матрицы нужен прямой IPv4 или IPv6 адрес",
                durationMs = 0
            )
            return
        }

        val baseTcp = runProbe(
            layer = TestLayer.TCP,
            name = "Базовый TCP матрицы",
            target = "$ip:${config.customMatrixPort}",
            success = {
                val data = NetworkTools.tcpConnect(network, ip, config.customMatrixPort, config.connectTimeoutMs)
                ProbeResult(
                    layer = TestLayer.TCP,
                    name = "Базовый TCP матрицы",
                    target = "$ip:${config.customMatrixPort}",
                    status = ProbeStatus.AVAILABLE,
                    summary = "TCP доступен; можно сравнивать TLS ClientHello",
                    durationMs = data.elapsedMs,
                    details = mapOf("localAddress" to (data.localAddress ?: "—"))
                )
            }
        )
        results += baseTcp
        progress("Проверен TCP для матрицы SNI")
        if (baseTcp.status != ProbeStatus.AVAILABLE) {
            results += notTested(
                TestLayer.TLS,
                "Матрица SNI",
                "$ip:${config.customMatrixPort}",
                "Базовый TCP не установлен. Перебор SNI пропущен: TLS ClientHello не может быть отправлен."
            )
            progress("Матрица SNI пропущена: TCP недоступен")
            return
        }

        val candidates: List<String?> = listOf<String?>(null) + config.sniCandidates
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        for (sni in candidates) {
            for (tlsVersion in listOf("TLSv1.3", "TLSv1.2")) {
                val title = if (sni == null) "без SNI" else "SNI=$sni"
                results += runProbe(
                    layer = TestLayer.TLS,
                    name = "Матрица SNI — $tlsVersion",
                    target = "$ip:${config.customMatrixPort} / $title",
                    success = {
                        val data = NetworkTools.tlsHandshake(
                            network = network,
                            ip = ip,
                            port = config.customMatrixPort,
                            sni = sni,
                            hostHeader = sni,
                            protocolPreference = listOf(tlsVersion),
                            alpn = listOf("http/1.1"),
                            timeoutMs = config.readTimeoutMs,
                            sendHttpRequest = true
                        )
                        ProbeResult(
                            layer = TestLayer.TLS,
                            name = "Матрица SNI — $tlsVersion",
                            target = "$ip:${config.customMatrixPort} / $title",
                            status = if (data.certificateMatchesSni == false || data.httpErrorType != null) {
                                ProbeStatus.LIMITED
                            } else {
                                ProbeStatus.AVAILABLE
                            },
                            summary = buildString {
                                append("TLS прошёл")
                                data.httpStatusLine?.let { append(", HTTP: $it") }
                                data.httpErrorMessage?.let { append(", HTTP-проба: $it") }
                                if (data.certificateMatchesSni == false) append(", сертификат не совпадает")
                            },
                            durationMs = data.elapsedMs,
                            details = tlsDetails(data) + mapOf("requestedTlsVersion" to tlsVersion)
                        )
                    }
                )
                progress("Матрица: $title, $tlsVersion")
            }
        }

        // Проверяем Host отдельно от SNI: TCP/TLS может проходить без SNI,
        // а HTTP-виртуальный хост — обрабатываться иначе.
        for (host in candidates.filterNotNull().take(5)) {
            results += runProbe(
                layer = TestLayer.HTTP,
                name = "HTTP Host без SNI",
                target = "$ip:${config.customMatrixPort} / без SNI / Host=$host",
                success = {
                    val data = NetworkTools.tlsHandshake(
                        network = network,
                        ip = ip,
                        port = config.customMatrixPort,
                        sni = null,
                        hostHeader = host,
                        protocolPreference = listOf("TLSv1.3", "TLSv1.2"),
                        alpn = listOf("http/1.1"),
                        timeoutMs = config.readTimeoutMs,
                        sendHttpRequest = true
                    )
                    ProbeResult(
                        layer = TestLayer.HTTP,
                        name = "HTTP Host без SNI",
                        target = "$ip:${config.customMatrixPort} / без SNI / Host=$host",
                        status = when {
                            data.httpStatusLine != null -> ProbeStatus.AVAILABLE
                            data.httpErrorType != null -> ProbeStatus.LIMITED
                            else -> ProbeStatus.INCONCLUSIVE
                        },
                        summary = when {
                            data.httpStatusLine != null -> "TLS без SNI прошёл, HTTP ответил: ${data.httpStatusLine}"
                            data.httpErrorType != null -> "TLS без SNI прошёл, но HTTP-проба завершилась отдельно: ${data.httpErrorMessage}"
                            else -> "TLS без SNI прошёл; HTTP не вернул строку статуса"
                        },
                        durationMs = data.elapsedMs,
                        details = tlsDetails(data) + mapOf("hostHeader" to host)
                    )
                }
            )
            progress("Матрица: без SNI, Host=$host")
        }

        val h2Sni = candidates.firstOrNull { it != null }
        if (h2Sni != null) {
            if (Build.VERSION.SDK_INT < 29) {
                results += notTested(
                    TestLayer.TLS,
                    "ALPN HTTP/2",
                    "$ip:${config.customMatrixPort} / SNI=$h2Sni",
                    "Android ниже API 29 не позволяет FranProbe надёжно задать и прочитать ALPN через SSLSocket."
                )
                progress("ALPN h2 не проверен: API устройства ниже 29")
            } else {
                results += runProbe(
                    layer = TestLayer.TLS,
                    name = "ALPN HTTP/2",
                    target = "$ip:${config.customMatrixPort} / SNI=$h2Sni",
                    success = {
                        val data = NetworkTools.tlsHandshake(
                            network = network,
                            ip = ip,
                            port = config.customMatrixPort,
                            sni = h2Sni,
                            hostHeader = null,
                            protocolPreference = listOf("TLSv1.3", "TLSv1.2"),
                            alpn = listOf("h2", "http/1.1"),
                            timeoutMs = config.readTimeoutMs,
                            sendHttpRequest = false
                        )
                        ProbeResult(
                            layer = TestLayer.TLS,
                            name = "ALPN HTTP/2",
                            target = "$ip:${config.customMatrixPort} / SNI=$h2Sni",
                            status = if (data.selectedAlpn == "h2") ProbeStatus.AVAILABLE else ProbeStatus.LIMITED,
                            summary = when (data.selectedAlpn) {
                                "h2" -> "TLS прошёл, сервер выбрал ALPN h2"
                                null -> "TLS прошёл, но сервер не выбрал ALPN"
                                else -> "TLS прошёл, но вместо h2 выбран ALPN ${data.selectedAlpn}"
                            },
                            durationMs = data.elapsedMs,
                            details = tlsDetails(data)
                        )
                    }
                )
                progress("Проверен ALPN h2")
            }
        }
    }

    private suspend fun runFixedIpTests(
        network: Network,
        config: DiagnosticConfig,
        results: MutableList<ProbeResult>,
        progress: (String) -> Unit
    ) {
        val targets = listOf(
            Triple("1.1.1.1", 443, "Cloudflare"),
            Triple("8.8.8.8", 443, "Google"),
            Triple("77.88.8.8", 443, "Яндекс DNS")
        )
        for ((ip, port, label) in targets) {
            results += runProbe(
                layer = TestLayer.TCP,
                name = "Прямой TCP по IP",
                target = "$ip:$port ($label)",
                success = {
                    val data = NetworkTools.tcpConnect(network, ip, port, config.connectTimeoutMs)
                    ProbeResult(
                        layer = TestLayer.TCP,
                        name = "Прямой TCP по IP",
                        target = "$ip:$port ($label)",
                        status = ProbeStatus.AVAILABLE,
                        summary = "TCP доступен без DNS",
                        durationMs = data.elapsedMs,
                        details = mapOf("localAddress" to (data.localAddress ?: "—"))
                    )
                }
            )
            progress("Прямой TCP $ip:$port")

            val udp = runProbe(
                layer = TestLayer.UDP,
                name = "UDP 443 — ответный пробник",
                target = "$ip:443 ($label)",
                success = {
                    val data = NetworkTools.udpResponseProbe(network, ip, 443, 1_500)
                    ProbeResult(
                        layer = TestLayer.UDP,
                        name = "UDP 443 — ответный пробник",
                        target = "$ip:443 ($label)",
                        status = when {
                            data.responseBytes != null -> ProbeStatus.AVAILABLE
                            data.portUnreachable -> ProbeStatus.LIMITED
                            else -> ProbeStatus.INCONCLUSIVE
                        },
                        summary = when {
                            data.responseBytes != null -> "Получен UDP-ответ: ${data.responseBytes} байт"
                            data.portUnreachable -> "Получен ICMP/UDP Port Unreachable: путь до узла есть, но UDP-порт отклонён."
                            else -> "Ответа нет. Это не доказывает блокировку: произвольный UDP-пакет сервер может игнорировать."
                        },
                        durationMs = data.elapsedMs,
                        details = mapOf(
                            "responseBytes" to (data.responseBytes?.toString() ?: "none"),
                            "portUnreachable" to data.portUnreachable.toString()
                        )
                    )
                }
            )
            results += udp
            progress("UDP 443 $ip")
        }
    }

    private inline fun runProbe(
        layer: TestLayer,
        name: String,
        target: String,
        success: () -> ProbeResult
    ): ProbeResult {
        val started = System.nanoTime()
        return try {
            success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failureResult(layer, name, target, elapsedMs(started), error)
        }
    }

    private fun failureResult(
        layer: TestLayer,
        name: String,
        target: String,
        durationMs: Long,
        error: Throwable
    ): ProbeResult {
        val root = generateSequence(error) { it.cause }.last()
        val (status, summary) = when (root) {
            is SocketTimeoutException -> ProbeStatus.UNREACHABLE to
                "Тайм-аут. Узел/порт не ответил; возможны фильтрация, потеря маршрута или молчаливый сервер."
            is ConnectException -> {
                if (root.message.orEmpty().contains("refused", ignoreCase = true)) {
                    ProbeStatus.LIMITED to "Узел достижим, но порт отклонил соединение."
                } else {
                    ProbeStatus.UNREACHABLE to "TCP-соединение не установлено: ${root.message.orEmpty()}"
                }
            }
            is NoRouteToHostException -> ProbeStatus.UNREACHABLE to "Нет маршрута к адресу."
            is SSLHandshakeException -> ProbeStatus.LIMITED to
                "TCP дошёл до TLS, но рукопожатие было отклонено или несовместимо."
            is SSLException -> ProbeStatus.LIMITED to "TLS-соединение завершилось ошибкой после TCP."
            is RemoteProtocolException -> ProbeStatus.LIMITED to
                (root.message ?: "Удалённый сервис ответил протокольной ошибкой.")
            is LocalCapabilityException -> ProbeStatus.NOT_TESTED to
                (root.message ?: "Проверка не поддерживается этим устройством.")
            is java.net.UnknownHostException -> ProbeStatus.UNREACHABLE to "DNS не вернул адрес."
            is IllegalArgumentException -> ProbeStatus.ERROR to (root.message ?: "Некорректные входные данные")
            else -> ProbeStatus.ERROR to (root.message ?: root::class.java.simpleName)
        }
        return ProbeResult(
            layer = layer,
            name = name,
            target = target,
            status = status,
            summary = summary,
            durationMs = durationMs,
            errorType = root::class.java.name,
            errorMessage = root.message
        )
    }

    private fun networkResult(snapshot: NetworkSnapshot?): ProbeResult {
        if (snapshot == null) {
            return ProbeResult(
                layer = TestLayer.NETWORK,
                name = "Параметры активной сети",
                target = "Android ConnectivityManager",
                status = ProbeStatus.UNREACHABLE,
                summary = "Активная сеть отсутствует",
                durationMs = 0
            )
        }
        val status = when {
            snapshot.validated -> ProbeStatus.AVAILABLE
            snapshot.captivePortal -> ProbeStatus.LIMITED
            else -> ProbeStatus.LIMITED
        }
        return ProbeResult(
            layer = TestLayer.NETWORK,
            name = "Параметры активной сети",
            target = "Network ${snapshot.networkHandle ?: "—"}",
            status = status,
            summary = when {
                snapshot.validated -> "Android подтвердил доступ в интернет"
                snapshot.captivePortal -> "Обнаружен портал авторизации"
                else -> "Сеть есть, но Android не подтвердил интернет"
            },
            durationMs = 0,
            details = mapOf(
                "transport" to snapshot.transports.joinToString(),
                "capabilities" to snapshot.capabilities.joinToString(),
                "interface" to (snapshot.interfaceName ?: "—"),
                "mtu" to (snapshot.mtu?.toString() ?: "—"),
                "addresses" to snapshot.addresses.joinToString(),
                "dns" to snapshot.dnsServers.joinToString(),
                "routes" to snapshot.routes.joinToString(" | "),
                "privateDnsActive" to snapshot.privateDnsActive.toString(),
                "privateDnsServer" to (snapshot.privateDnsServerName ?: "—"),
                "metered" to snapshot.metered.toString(),
                "proxy" to (snapshot.proxy ?: "—")
            )
        )
    }

    private fun notTested(layer: TestLayer, name: String, target: String, reason: String) = ProbeResult(
        layer = layer,
        name = name,
        target = target,
        status = ProbeStatus.NOT_TESTED,
        summary = reason,
        durationMs = 0
    )

    private fun dnsStatus(rcode: Int, addresses: List<String>, truncated: Boolean): ProbeStatus = when {
        rcode != 0 -> ProbeStatus.LIMITED
        truncated -> ProbeStatus.LIMITED
        addresses.isEmpty() -> ProbeStatus.LIMITED
        else -> ProbeStatus.AVAILABLE
    }

    private fun dnsSummary(rcode: Int, addresses: List<String>, truncated: Boolean): String = when {
        rcode != 0 -> "DNS ответил с RCODE=$rcode"
        truncated -> "DNS ответил обрезанным пакетом; результат неполный, выполняется повтор по TCP"
        addresses.isEmpty() -> "DNS ответил, но адресов A/AAAA нет"
        else -> "DNS ответил: ${addresses.joinToString()}"
    }

    private fun dnsMessageDetails(message: ru.franprobe.app.model.DnsMessage): Map<String, String> = mapOf(
        "flags" to "0x${message.flags.toString(16).padStart(4, '0')}",
        "rcode" to message.rcode.toString(),
        "question" to (message.questionName ?: "—"),
        "questionType" to (message.questionType?.toString() ?: "—"),
        "questionClass" to (message.questionClass?.toString() ?: "—"),
        "questionCount" to message.questionCount.toString(),
        "answerCount" to message.answerCount.toString(),
        "authorityCount" to message.authorityCount.toString(),
        "additionalCount" to message.additionalCount.toString(),
        "truncated" to message.truncated.toString(),
        "answers" to message.addresses.joinToString(),
        "answerRecords" to message.answers.joinToString(" | ") {
            "${it.name} type=${it.type} ttl=${it.ttl} value=${it.value}"
        },
        "minAnswerTtl" to (message.answers.minOfOrNull { it.ttl }?.toString() ?: "—"),
        "rawBytes" to message.rawSize.toString(),
        "rawPreviewHex" to message.rawPreviewHex
    )

    private fun selectAddresses(addresses: List<String>, max: Int): List<String> {
        val v4 = addresses.filter { !it.contains(':') }.take(max)
        val v6 = addresses.filter { it.contains(':') }.take(max)
        return (v4 + v6).distinct()
    }

    private fun tlsDetails(data: ru.franprobe.app.net.TlsProbeData): Map<String, String> = mapOf(
        "ip" to data.ip,
        "port" to data.port.toString(),
        "sni" to (data.sni ?: "<без SNI>"),
        "protocol" to data.protocol,
        "cipher" to data.cipherSuite,
        "alpn" to (data.selectedAlpn ?: "—"),
        "certificateSubject" to (data.certificateSubject ?: "—"),
        "certificateSans" to data.certificateSans.joinToString(),
        "certificateMatchesSni" to (data.certificateMatchesSni?.toString() ?: "—"),
        "httpStatusLine" to (data.httpStatusLine ?: "—"),
        "httpErrorType" to (data.httpErrorType ?: "—"),
        "httpErrorMessage" to (data.httpErrorMessage ?: "—"),
        "certificateTrustValidated" to "false (диагностический режим; Subject/SAN анализируются отдельно)"
    )

    private fun buildConclusions(snapshot: NetworkSnapshot, results: List<ProbeResult>): List<String> {
        val conclusions = mutableListOf<String>()
        if (!snapshot.validated) {
            conclusions += "Android не пометил сеть как VALIDATED. Это совместимо с белым списком, недоступностью контрольных адресов Android или порталом авторизации."
        }

        val dnsAvailable = results.filter { it.layer == TestLayer.DNS && it.status == ProbeStatus.AVAILABLE }
        val dnsUnavailable = results.filter { it.layer == TestLayer.DNS && it.status == ProbeStatus.UNREACHABLE }
        if (dnsAvailable.isNotEmpty() && dnsUnavailable.isNotEmpty()) {
            conclusions += "DNS доступен выборочно: часть резолверов отвечает, часть не достигается. Это сильный признак фильтрации по IP/порту либо маршруту."
        } else if (dnsAvailable.isEmpty()) {
            conclusions += "Ни один проверенный DNS-способ не дал пригодного адреса. Последующие зависимые тесты должны считаться не выполненными, а не автоматически заблокированными."
        }

        val tcpDirectOk = results.any { it.layer == TestLayer.TCP && it.name.contains("по IP") && it.status == ProbeStatus.AVAILABLE }
        val tcpDirectBad = results.any { it.layer == TestLayer.TCP && it.name.contains("по IP") && it.status == ProbeStatus.UNREACHABLE }
        if (tcpDirectOk && tcpDirectBad) {
            conclusions += "Прямые TCP-подключения по IP проходят выборочно. Вероятнее фильтрация адресов/сетей, а не полная блокировка TCP/443."
        }

        val tlsSuccesses = results.filter { it.layer == TestLayer.TLS && it.status == ProbeStatus.AVAILABLE }
        val tlsFailures = results.filter { it.layer == TestLayer.TLS && it.status == ProbeStatus.LIMITED }
        if (tlsSuccesses.isNotEmpty() && tlsFailures.isNotEmpty()) {
            conclusions += "TCP/TLS поведение зависит от SNI, TLS-версии или конкретного IP. Сравните строки матрицы: смена SNI имеет смысл только там, где базовый TCP уже устанавливается."
        }

        if (results.any { it.status == ProbeStatus.NOT_TESTED }) {
            conclusions += "В отчёте есть статус «Не проверено»: FranProbe больше не выдаёт ложный BLOCKED, когда тест не стартовал из-за отсутствия DNS/IP."
        }
        conclusions += "Отсутствие ответа на произвольный UDP-пакет не считается доказательством блокировки. Для уверенного вывода нужен протокольный ответ или сравнение с контрольной сетью."
        return conclusions.distinct()
    }

    private fun estimateSteps(config: DiagnosticConfig): Int {
        val resolverCount = config.dnsServers.distinct().size.coerceAtLeast(1)
        val maxTcpPerDomain = config.maxResolvedIpsPerDomain.coerceAtLeast(1) * 2
        return when (config.mode) {
            DiagnosticMode.QUICK -> {
                val initialDns = 4
                val perDomain = 1 + resolverCount + maxTcpPerDomain + 1
                1 + initialDns + 3 * perDomain
            }
            DiagnosticMode.FULL -> {
                val initialDns = resolverCount * 2 + 6
                val perDomainWorstCase = 1 + resolverCount * 4 + 1 + maxTcpPerDomain + 1
                1 + initialDns + config.targetDomains.size * perDomainWorstCase + 6
            }
            DiagnosticMode.SNI_MATRIX -> 1 + resolverCount * 2 + 1 +
                (config.sniCandidates.size + 1).coerceAtMost(21) * 2 +
                config.sniCandidates.size.coerceAtMost(5) + 1
        }.coerceAtLeast(1)
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000
}
