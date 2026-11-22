package dev.materialclient

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Properties
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Ответ сервера с кодом не 2xx. Отдельный тип нужен, чтобы отличать «файла нет» от «сайт не отвечает». */
internal class HttpStatusException(
    val code: Int,
    val url: String,
    /** true, если часть адресов дала 404, а остальные не удалось даже проверить из-за сети. */
    val unreachable: Boolean = false
) : IllegalStateException("HTTP $code: $url")

/**
 * Сеть лаунчера: прокси, таймауты, повторы, запасные зеркала и понятные сообщения об ошибках.
 */
internal object Net {
    const val CONNECT_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 60_000
    private const val QUICK_CONNECT_MS = 10_000
    private const val QUICK_READ_MS = 20_000

    val HINT: String get() = tr("Похоже, сайт недоступен или очень медленно отвечает из твоей сети. Включи VPN для всей системы либо укажи прокси в настройках лаунчера (раздел «Сеть»), а затем повтори установку.")

    /** После неудачи с официальным сайтом дальше сначала идём в зеркало, чтобы не ждать таймаут каждый раз. */
    @Volatile
    var mirrorFirst = false

    private val settingsFile = File(System.getProperty("user.home"), ".material-client/settings.properties")
    private var systemSelector: ProxySelector? = null

    // ---------------------------------------------------------------- прокси

    class ProxyConfig(val type: Proxy.Type, val host: String, val port: Int)

    /** Принимает host:port, http://host:port, socks5://host:port. Пустая строка и ошибки дают null. */
    fun parseProxy(text: String): ProxyConfig? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val socks = raw.startsWith("socks", ignoreCase = true)
        val hostPort = raw.substringAfter("://", raw).trimEnd('/')
        val host = hostPort.substringBeforeLast(':', "")
        val port = hostPort.substringAfterLast(':', "").toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) return null
        return ProxyConfig(if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP, host, port)
    }

    /** Слово «direct» отключает любые прокси: нужно, если в системе остался неработающий прокси. */
    fun isDirect(text: String): Boolean = text.trim().equals("direct", ignoreCase = true)

    /** Текст ошибки для настроек или null, если строка прокси корректна (или пуста). */
    fun validateProxy(text: String): String? =
        if (text.isBlank() || isDirect(text) || parseProxy(text) != null) null
        else tr("Укажи прокси в виде адрес:порт, например 127.0.0.1:7890 или socks5://127.0.0.1:1080 (или слово direct, чтобы не использовать прокси)")

    private fun readSettings(): Properties = try {
        Properties().also { props -> if (settingsFile.isFile) settingsFile.inputStream().use { props.load(it) } }
    } catch (_: Exception) {
        Properties()
    }

    fun savedProxy(): String = readSettings().getProperty("proxy", "")

    fun saveProxy(text: String) {
        // Файл настроек общий с темой и путём к Java: обновляем только одно значение.
        val props = readSettings()
        props.setProperty("proxy", text.trim())
        settingsFile.parentFile?.mkdirs()
        settingsFile.outputStream().use { props.store(it, "Material Client settings") }
    }

    /** Вызывается один раз при старте, до первого сетевого запроса. */
    fun init() {
        // Без этого Java не видит системный прокси Windows, хотя браузер через него ходит.
        System.setProperty("java.net.useSystemProxies", "true")
        systemSelector = ProxySelector.getDefault()
        applyProxy(savedProxy())
    }

    /** Применяет прокси из настроек к самому лаунчеру. Пустая строка возвращает системные настройки. */
    fun applyProxy(text: String) {
        if (isDirect(text)) {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
            })
            return
        }
        val config = parseProxy(text)
        if (config == null) {
            ProxySelector.setDefault(systemSelector)
            return
        }
        val proxy = Proxy(config.type, InetSocketAddress.createUnresolved(config.host, config.port))
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val host = uri?.host
                val local = host == null || host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
                return if (local) listOf(Proxy.NO_PROXY) else listOf(proxy)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        })
    }

    /** Те же сетевые настройки для установщиков Forge и NeoForge, которые запускаются отдельной Java. */
    fun childJvmArgs(): List<String> {
        if (isDirect(savedProxy())) return listOf("-Djava.net.useSystemProxies=false")
        val config = parseProxy(savedProxy()) ?: return listOf("-Djava.net.useSystemProxies=true")
        return if (config.type == Proxy.Type.SOCKS) {
            listOf("-DsocksProxyHost=${config.host}", "-DsocksProxyPort=${config.port}")
        } else {
            listOf(
                "-Dhttp.proxyHost=${config.host}", "-Dhttp.proxyPort=${config.port}",
                "-Dhttps.proxyHost=${config.host}", "-Dhttps.proxyPort=${config.port}",
                "-Dhttp.nonProxyHosts=localhost|127.*"
            )
        }
    }

    // ---------------------------------------------------------------- запросы

    fun open(url: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = connectMs
        connection.readTimeout = readMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "MaterialClient/0.1")
        return connection
    }

    private fun fetchText(url: String, connectMs: Int, readMs: Int): String {
        val connection = open(url, connectMs, readMs)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code, url)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Один из возможных адресов данных вместе с тем, как их разобрать. */
    class Source<T>(val url: String, val mirror: Boolean = false, val parse: (String) -> T)

    fun hostOf(url: String): String = try {
        URI.create(url).host ?: url
    } catch (_: Exception) {
        url
    }

    /** Сайт вместе с портом: по нему определяем, что другой адрес того же сервера ждать бессмысленно. */
    private fun authorityOf(url: String): String = try {
        URI.create(url).authority ?: url
    } catch (_: Exception) {
        url
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        var depth = 0
        while (current.cause != null && current.cause !== current && depth < 10) {
            current = current.cause!!
            depth++
        }
        return current
    }

    fun isNetworkError(error: Throwable): Boolean {
        if (error is HttpStatusException) return false
        val root = rootCause(error)
        return root is IOException
    }

    fun describe(error: Throwable): String {
        if (error is HttpStatusException) return tr("ответ сервера HTTP {0}", error.code)
        return when (val root = rootCause(error)) {
            is SocketTimeoutException -> tr("время ожидания истекло")
            is UnknownHostException -> tr("не удалось найти адрес сайта (нет интернета или проблема с DNS)")
            is ConnectException -> tr("не удалось подключиться")
            is SSLException -> tr("ошибка защищённого соединения")
            is SocketException -> tr("соединение разорвано")
            is IOException -> root.message ?: tr("ошибка сети")
            else -> error.message ?: error.javaClass.simpleName
        }
    }

    /** «сайт: причина» плюс подсказка, если дело в сети. */
    fun explain(url: String, error: Throwable): String {
        val text = "${hostOf(url)}: ${describe(error)}"
        return if (isNetworkError(error)) "$text. $HINT" else text
    }

    private fun ordered(sources: List<Source<*>>): List<Int> {
        val indices = sources.indices.toList()
        return if (mirrorFirst) indices.sortedBy { if (sources[it].mirror) 0 else 1 } else indices
    }

    /**
     * Берёт данные из первого источника, который ответил. Сначала быстрый проход с короткими таймаутами,
     * затем более терпеливый. Если сайт не отвечает, остальные адреса того же сайта пропускаются.
     */
    fun <T> fetchFirst(sources: List<Source<T>>): T {
        require(sources.isNotEmpty())
        val failures = mutableListOf<Pair<Source<T>, Exception>>()
        for (round in 0..1) {
            val connectMs = if (round == 0) QUICK_CONNECT_MS else CONNECT_TIMEOUT_MS
            val readMs = if (round == 0) QUICK_READ_MS else READ_TIMEOUT_MS
            val deadHosts = mutableSetOf<String>()
            var networkProblem = false
            for (index in ordered(sources)) {
                val source = sources[index]
                MinecraftInstaller.cancelled()
                if (authorityOf(source.url) in deadHosts) continue
                try {
                    val value = source.parse(fetchText(source.url, connectMs, readMs))
                    if (failures.any { isNetworkError(it.second) }) mirrorFirst = source.mirror
                    return value
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    failures += source to error
                    if (isNetworkError(error)) {
                        networkProblem = true
                        deadHosts += authorityOf(source.url)
                    }
                }
            }
            // Если все ответы были «ошибка сервера» (например 404), повтор ничего не изменит.
            if (!networkProblem) break
            try {
                Thread.sleep(700)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException(tr("Установка отменена"))
            }
        }
        throw failureOf(failures.map { it.first.url to it.second })
    }

    private fun failureOf(failures: List<Pair<String, Exception>>): Exception {
        val last = failures.last().second
        // Единственная ошибка «такого файла нет» остаётся как есть: вызывающий код может на неё реагировать.
        if (failures.all { it.second is HttpStatusException }) return last
        val details = failures.distinctBy { it.first }.joinToString("; ") { "${hostOf(it.first)}: ${describe(it.second)}" }
        val hint = if (failures.any { isNetworkError(it.second) }) " $HINT" else ""
        return IllegalStateException(tr("Не удалось получить данные ({0}).{1}", details, hint), last)
    }

    fun getText(official: String, mirror: String? = null): String =
        fetchFirst(listOfNotNull(Source(official, false) { it }, mirror?.let { Source(it, true) { text -> text } }))

    // ---------------------------------------------------------------- проверка сети

    private fun probe(url: String): String {
        val started = System.nanoTime()
        var total = 0L
        return try {
            val connection = open(url, 8_000, 8_000)
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    tr("НЕТ — ответ сервера HTTP {0}", code)
                } else {
                    val buffer = ByteArray(16 * 1024)
                    connection.inputStream.use { input ->
                        while (total < 200 * 1024) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                        }
                    }
                    val millis = (System.nanoTime() - started) / 1_000_000
                    tr("OK — получено {0} КБ за {1} мс", total / 1024, millis)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            if (total > 0) {
                tr("НЕТ — {0}, успело прийти {1} КБ, потом данные встали — так выглядит ограничение скорости у провайдера", describe(e), total / 1024)
            } else {
                tr("НЕТ — {0}", describe(e))
            }
        }
    }

    /** Проверяет доступность сайтов, которые нужны для установки. Блокирует поток: запускать вне UI. */
    fun checkAll(): String {
        val mirror = LoaderEndpoints.mirrorUrl("/optifine/versionList")
        val targets = listOfNotNull(
            "Mojang (Minecraft)" to "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "Forge" to "${LoaderEndpoints.forgeMaven}/net/minecraftforge/forge/maven-metadata.xml",
            "NeoForge" to "${LoaderEndpoints.neoForgeMaven}/releases/net/neoforged/neoforge/maven-metadata.xml",
            "Fabric" to "${LoaderEndpoints.fabricMeta}/versions/loader/1.21.1",
            "Quilt" to "${LoaderEndpoints.quiltMeta}/versions/loader/1.21.1",
            mirror?.let { tr("Зеркало BMCLAPI (OptiFine, запасное для Forge и Fabric)") to it }
        )
        val proxy = savedProxy().ifBlank { tr("не задан (используются системные настройки)") }
        // Сайты проверяются одновременно: недоступный сайт не должен задерживать остальные.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(targets.size)
        val lines = try {
            val futures = targets.map { (name, url) -> pool.submit<String> { "$name: ${probe(url)}" } }
            futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        return tr("Прокси: {0}\n", proxy) + lines.joinToString("\n")
    }
}
