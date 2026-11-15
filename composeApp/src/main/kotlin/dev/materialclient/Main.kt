package dev.materialclient

import kotlin.coroutines.coroutineContext
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.platform.Font as ComposePlatformFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URL
import javax.imageio.ImageIO
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
private fun LoaderLogo(domain: String, label: String, fallbackColor: Color, size: androidx.compose.ui.unit.Dp = 30.dp) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, domain) {
        value = withContext(Dispatchers.IO) {
            try {
                val url = URL("https://www.google.com/s2/favicons?domain_url=https://$domain&sz=128")
                val connection = url.openConnection().apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    setRequestProperty("User-Agent", "MaterialClient/1.0")
                }
                connection.getInputStream().use { stream -> ImageIO.read(stream)?.toComposeImageBitmap() }
            } catch (_: Exception) {
                null
            }
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!, contentDescription = "$label logo", modifier = Modifier.size(size))
    } else {
        Surface(shape = RoundedCornerShape(8.dp), color = fallbackColor, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) {
                Text(label.take(2).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private val AppBg = Color(0xFF17191D)
private val SurfaceA = Color(0xFF202329)
private val SurfaceB = Color(0xFF292E35)
private val Accent = Color(0xFF72E5D1)
private val AccentDark = Color(0xFF8B70E8)
private val AccentSoft = Color(0xFF2A3540)
private val TextMain = Color(0xFFF1F3F6)
private val TextMuted = Color(0xFFADB4BE)

private data class GameVersion(val id: String, val type: String)

data class Instance(
    val name: String,
    val version: String,
    val loader: String = "Vanilla",
    /** Память игры, МБ. 0 — как в общих настройках. */
    val memoryMb: Int = 0,
    /** Дополнительные JVM-аргументы этого экземпляра. */
    val jvmArgs: String = ""
)

private object ProfileStore {
    private val file = File(
        System.getProperty("user.home"),
        ".material-client/profiles.properties"
    )

    fun load(): List<Instance> {
        if (!file.exists()) {
            return listOf(Instance("Vanilla Survival", "1.21.1"))
        }

        return try {
            val p = Properties().apply {
                file.inputStream().use { load(it) }
            }
            val count = p.getProperty("count", "0").toInt()
            // Пустой список допустим: после удаления всех экземпляров они не должны появляться снова.
            (0 until count).mapNotNull { i ->
                val name = p.getProperty("profile.$i.name") ?: return@mapNotNull null
                val version = p.getProperty("profile.$i.version") ?: return@mapNotNull null
                val loader = p.getProperty("profile.$i.loader", "Vanilla")
                val memory = p.getProperty("profile.$i.memory", "0").trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                Instance(name, version, loader, memory, p.getProperty("profile.$i.jvmArgs", ""))
            }
        } catch (_: Exception) {
            listOf(Instance("Vanilla Survival", "1.21.1"))
        }
    }

    fun save(instances: List<Instance>) {
        try {
            file.parentFile.mkdirs()
            val p = Properties()
            p.setProperty("count", instances.size.toString())
            instances.forEachIndexed { i, instance ->
                p.setProperty("profile.$i.name", instance.name)
                p.setProperty("profile.$i.version", instance.version)
                p.setProperty("profile.$i.loader", instance.loader)
                p.setProperty("profile.$i.memory", instance.memoryMb.toString())
                p.setProperty("profile.$i.jvmArgs", instance.jvmArgs)
            }
            file.outputStream().use {
                p.store(it, "Material Client profiles")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private object JavaPreferences {
    /** Путь к Java из настроек. Пустая строка — лаунчер выбирает и при необходимости скачивает Java сам. */
    fun loadJavaPath(): String = GameSettings.javaPath

    fun saveJavaPath(path: String) {
        GameSettings.saveJavaPath(path)
    }
}


private object ThemePreferences {
    private val file = File(
        System.getProperty("user.home"),
        ".material-client/settings.properties"
    )

    fun load(): Properties = try {
        Properties().also { if (file.isFile) file.inputStream().use(it::load) }
    } catch (_: Exception) {
        Properties()
    }

    fun save(choice: String, customHex: String) {
        val props = load()
        props.setProperty("themeChoice", choice)
        props.setProperty("themeCustomHex", customHex)
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Material Client settings") }
    }

    fun saveProperty(key: String, value: String) {
        val props = load()
        props.setProperty(key, value)
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Material Client settings") }
    }
}

private object OfflineAccountPreferences {
    private val file = File(
        System.getProperty("user.home"),
        ".material-client/account.properties"
    )

    private fun readProperties() = Properties().apply {
        if (file.isFile) file.inputStream().use { load(it) }
    }

    fun loadAccounts(): List<String> {
        return try {
            val p = readProperties()
            val count = p.getProperty("offlineAccountCount", "0").toIntOrNull() ?: 0
            val saved = (0 until count).mapNotNull { p.getProperty("offlineAccount.$it") }
            val legacy = p.getProperty("offlineUsername", "Player")
            (saved + legacy).map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
                .ifEmpty { listOf("Player") }
        } catch (_: Exception) { listOf("Player") }
    }

    fun loadUsername(): String = try {
        readProperties().getProperty("offlineUsername", "Player")
    } catch (_: Exception) { "Player" }

    fun saveUsername(username: String) {
        val accounts = (loadAccounts() + username.trim()).distinctBy { it.lowercase() }
        file.parentFile?.mkdirs()
        val properties = readProperties().apply {
            setProperty("offlineUsername", username.trim())
            setProperty("offlineAccountCount", accounts.size.toString())
            accounts.forEachIndexed { index, name -> setProperty("offlineAccount.$index", name) }
        }
        file.outputStream().use { properties.store(it, "Material Client offline accounts") }
    }

    fun validateUsername(username: String): String? {
        return when {
            !username.matches(Regex("[A-Za-z0-9_]{3,16}")) ->
                tr("Ник должен содержать 3–16 латинских букв, цифр или _")

            else -> null
        }
    }
}

private object VersionRepository {
    suspend fun fetch(): List<GameVersion> = withContext(Dispatchers.IO) {
        val connection = URL(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        ).openConnection().apply {
            connectTimeout = 8000
            readTimeout = 8000
        }

        val json = connection.getInputStream().bufferedReader().use { it.readText() }

        // Разбираем манифест как JSON, а не регулярным выражением: порядок полей в нём не гарантирован.
        JsonParser.parseString(json).asJsonObject
            .getAsJsonArray("versions")
            .map { element ->
                val entry = element.asJsonObject
                GameVersion(
                    id = entry.get("id").asString,
                    type = entry.get("type").asString
                )
            }
            .distinctBy { it.id }
    }
}


internal object MinecraftInstaller {
    @Volatile private var cancelRequested = false
    @Volatile private var activeConnection: HttpURLConnection? = null

    // Parallelize small asset/library files, while keeping a shared bandwidth ceiling
    // so installation does not saturate the user's whole connection.
    private const val MAX_CONCURRENT_DOWNLOADS = 16

    fun requestCancel() {
        cancelRequested = true
        activeConnection?.disconnect()
    }

    /** Сбрасывает признак отмены перед новой операцией (запуск игры, загрузка Java). */
    fun resetCancel() {
        cancelRequested = false
    }

    internal data class DownloadItem(
        val url: String,
        val file: File,
        val sha1: String? = null,
        /** Запасные адреса (зеркала): к ним лаунчер переходит, если основной сайт не отвечает. */
        val fallbackUrls: List<String> = emptyList()
    )

    private fun readJson(url: String): JsonObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "MaterialClient/0.1")

            check(connection.responseCode in 200..299) {
                "HTTP ${connection.responseCode}: $url"
            }

            return connection.inputStream.bufferedReader().use {
                JsonParser.parseReader(it).asJsonObject
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            "%02x".format(it)
        }
    }

    internal fun cancelled() {
        if (cancelRequested || Thread.currentThread().isInterrupted) {
            throw java.util.concurrent.CancellationException(tr("Установка отменена"))
        }
    }

    /** Описание загрузчика, который ставится его официальным установщиком (Forge, NeoForge). */
    internal class InstallerSpec(
        val name: String,
        val loaderKey: String,
        /** Подходящие версии загрузчика для версии Minecraft, лучшие первыми. */
        val candidates: (String) -> List<String>,
        val installerUrl: (String) -> String,
        val installerFileName: (String) -> String,
        /** true, если папка в versions создана установщиком этого загрузчика для данной версии Minecraft. */
        val ownsVersionDir: (String, String) -> Boolean,
        /** Адрес установщика на зеркале или null. */
        val installerMirrorUrl: (String) -> String? = { null }
    )

    internal fun httpGetText(url: String): String = Net.getText(url)

    private val forgeSpec = InstallerSpec(
        name = "Forge",
        loaderKey = "Forge",
        candidates = { mc -> LoaderInstaller.forgeCandidates(mc) },
        installerUrl = { v -> "${LoaderEndpoints.forgeMaven}/net/minecraftforge/forge/$v/forge-$v-installer.jar" },
        installerFileName = { v -> "forge-$v-installer.jar" },
        ownsVersionDir = { mc, dir -> dir.startsWith("$mc-forge-") },
        installerMirrorUrl = { v -> LoaderEndpoints.mirrorUrl("/maven/net/minecraftforge/forge/$v/forge-$v-installer.jar") }
    )

    private val neoForgeSpec = InstallerSpec(
        name = "NeoForge",
        loaderKey = "NeoForge",
        candidates = { mc -> LoaderInstaller.neoForgeCandidates(mc) },
        installerUrl = { v -> "${LoaderEndpoints.neoForgeMaven}/releases/net/neoforged/neoforge/$v/neoforge-$v-installer.jar" },
        installerFileName = { v -> "neoforge-$v-installer.jar" },
        ownsVersionDir = { _, dir -> dir.startsWith("neoforge-") },
        installerMirrorUrl = { v -> LoaderEndpoints.mirrorUrl("/maven/net/neoforged/neoforge/$v/neoforge-$v-installer.jar") }
    )

    suspend fun installForge(
        minecraftVersion: String,
        javaPath: String,
        onStatus: (String) -> Unit
    ) = installWithInstaller(forgeSpec, minecraftVersion, javaPath, onStatus)

    suspend fun installNeoForge(
        minecraftVersion: String,
        javaPath: String,
        onStatus: (String) -> Unit
    ) = installWithInstaller(neoForgeSpec, minecraftVersion, javaPath, onStatus)

    private suspend fun installWithInstaller(
        spec: InstallerSpec,
        minecraftVersion: String,
        javaPath: String,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        // Флаг отмены здесь не сбрасывается: его сбрасывает начало install(), а нажатие «Отмена»
        // сразу после скачивания файлов Minecraft не должно теряться.
        require(minecraftVersion.matches(Regex("[A-Za-z0-9._-]+"))) {
            tr("Некорректная версия Minecraft: {0}", minecraftVersion)
        }
        // Установщик требует ту же Java, что и сама версия Minecraft; пустой путь — подбор и загрузка автоматически.
        val requiredJava = VersionResolver.requiredJava(minecraftVersion)
        val selection = JavaProvider.ensure(
            javaPath, requiredJava, JavaProvider.maxMajorFor(spec.loaderKey, requiredJava),
            onStatus = { message -> onStatus("${spec.name}: $message") }
        )
        val java = selection.java
        if (selection.note != null) onStatus("${spec.name}: ${selection.note}")

        val root = File(System.getProperty("user.home"), ".material-client")
        val installerDir = File(root, "installers").apply { mkdirs() }
        val versionsDir = File(root, "versions").apply { mkdirs() }
        // Установщик отказывается работать в папке без launcher_profiles.json.
        val launcherProfiles = File(root, "launcher_profiles.json")
        if (!launcherProfiles.isFile) {
            launcherProfiles.writeText("{\"profiles\":{},\"settings\":{},\"version\":3}")
        }
        onStatus(tr("{0}: получаем список доступных версий для Minecraft {1}…", spec.name, minecraftVersion))

        val candidates = spec.candidates(minecraftVersion)
        check(candidates.isNotEmpty()) {
            tr("Для Minecraft {0} нет версий {1}. Выбери другую версию Minecraft.", minecraftVersion, spec.name)
        }
        // В метаданных бывают версии без установщика (404): идём вниз по списку, пока не найдём скачиваемый.
        var installerVersion: String? = null
        var installer: File? = null
        var someSiteUnreachable = false
        for (candidate in candidates.take(8)) {
            cancelled()
            val file = File(installerDir, spec.installerFileName(candidate))
            if (file.isFile && file.length() >= 100_000L) {
                installerVersion = candidate
                installer = file
                break
            }
            onStatus(tr("{0}: скачиваем установщик {1}…", spec.name, candidate))
            try {
                download(
                    DownloadItem(
                        spec.installerUrl(candidate),
                        file,
                        null,
                        listOfNotNull(spec.installerMirrorUrl(candidate))
                    )
                ) { _, read, total ->
                    val size = if (total > 0) tr("{0} / {1} КБ", read / 1024, total / 1024) else tr("{0} КБ", read / 1024)
                    onStatus(tr("{0}: скачиваем установщик {1} — {2}", spec.name, candidate, size))
                }
            } catch (notFound: HttpStatusException) {
                if (notFound.unreachable) someSiteUnreachable = true
                continue
            }
            if (file.length() <= 100_000L) {
                val size = file.length()
                file.delete()
                error(tr("Скачанный установщик {0} слишком маленький ({1} байт): сайт вернул не файл, а заглушку или ошибку.", spec.name, size))
            }
            installerVersion = candidate
            installer = file
            break
        }
        if (installerVersion == null || installer == null) {
            error(
                tr("Для Minecraft {0} не найден доступный установщик {1}. ", minecraftVersion, spec.name) +
                    (if (someSiteUnreachable) Net.HINT else tr("Проверь версию Minecraft."))
            )
        }

        onStatus(tr("{0}: запускаем установщик {1} через {2}…", spec.name, installerVersion, java.name))
        val dirsBefore = versionsDir.list()?.toSet() ?: emptySet()
        val logDir = File(root, "logs").apply { mkdirs() }
        val installLog = File(logDir, "${spec.name.lowercase()}-install.log")
        // Прокси и системные сетевые настройки передаём и установщику: он сам качает библиотеки.
        val process = ProcessBuilder(
            listOf(java.absolutePath) + Net.childJvmArgs() +
                listOf("-jar", installer.absolutePath, "--installClient", root.absolutePath)
        ).directory(root).redirectErrorStream(true).start()
        val output = StringBuilder()
        val lastLine = AtomicReference("")
        val reader = Thread {
            try {
                // Полный вывод установщика сохраняется в logs/<загрузчик>-install.log.
                installLog.bufferedWriter().use { log ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        log.write(line)
                        log.newLine()
                        log.flush()
                        if (line.isNotBlank()) lastLine.set(line.trim())
                        synchronized(output) {
                            if (output.length < 20000) output.append(line).append('\n')
                        }
                    }
                }
            } catch (_: Exception) {
                // Процесс остановлен: чтение вывода просто заканчивается.
            }
        }.apply { isDaemon = true; start() }

        val startedAt = System.nanoTime()
        // Установщик скачивает библиотеки и собирает клиент: это занимает несколько минут.
        val deadline = startedAt + TimeUnit.MINUTES.toNanos(20)
        var installed = false
        try {
            while (process.isAlive) {
                cancelled()
                if (System.nanoTime() > deadline) {
                    error(tr("Установщик {0} не завершился за 20 минут. Вывод: {1}", spec.name, output.toString().takeLast(2500)))
                }
                val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)
                val line = lastLine.get().take(90)
                onStatus(
                    tr("{0}: устанавливаем {1} ({2}:{3})", spec.name, installerVersion, seconds / 60, "%02d".format(seconds % 60)) +
                        (if (line.isNotEmpty()) " — $line" else "")
                )
                process.waitFor(400, TimeUnit.MILLISECONDS)
            }
            reader.join(2000)
            check(process.exitValue() == 0) {
                val tail = output.toString().takeLast(1500)
                val networkProblem = Regex("(?i)timed out|timeout|UnknownHost|ConnectException|SSLHandshake|Connection reset|Unable to tunnel")
                    .containsMatchIn(output.toString())
                tr("Установщик {0} завершился с кодом {1}. Полный вывод: {2}. ", spec.name, process.exitValue(), installLog.absolutePath) +
                    (if (networkProblem) "${Net.HINT} " else "") + tail
            }
            onStatus(tr("{0}: проверяем установленный профиль…", spec.name))
            val versionId = MinecraftLauncher.resolveLaunchVersionId(minecraftVersion, spec.loaderKey)
            check(versionId != null) {
                tr("Установщик завершился без ошибки, но профиль {0} не найден в {1}. Вывод установщика: {2}", spec.name, versionsDir.absolutePath, output.toString().takeLast(1500))
            }
            val problem = MinecraftLauncher.verify(versionId)
            check(problem == null) { tr("{0} установлен не полностью: {1}", spec.name, problem) }
            installed = true
        } finally {
            // Отмена, ошибка или таймаут не должны оставлять работающий установщик и «полупрофиль»:
            // иначе игра потом жалуется на ненайденные библиотеки.
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            if (!installed) {
                versionsDir.listFiles()
                    ?.filter { it.isDirectory && it.name !in dirsBefore && spec.ownsVersionDir(minecraftVersion, it.name) }
                    ?.forEach { it.deleteRecursively() }
            }
        }
        onStatus(tr("{0} {1} успешно установлен.", spec.name, installerVersion))
    }

    internal suspend fun download(
        item: DownloadItem,
        onFileProgress: (String, Long, Long) -> Unit
    ) {
        cancelled()

        val target = item.file
        target.parentFile?.mkdirs()

        // Reuse a file only when its checksum is known and verified.
        if (target.isFile && item.sha1 != null &&
            sha1(target).equals(item.sha1, ignoreCase = true)
        ) {
            onFileProgress(target.name, target.length(), target.length())
            return
        }

        val temp = File(target.parentFile, target.name + ".part")
        var lastError: Exception? = null
        var lastUrl = item.url

        // Основной адрес и запасные (зеркала). Если до этого официальный сайт уже не отвечал, зеркало идёт первым.
        val allUrls = if (Net.mirrorFirst && item.fallbackUrls.isNotEmpty()) item.fallbackUrls + item.url else listOf(item.url) + item.fallbackUrls
        // Адреса, на которых файла нет (404), повторно не опрашиваем.
        val missing = mutableSetOf<String>()
        val totalAttempts = if (allUrls.size > 1) 4 else 3

        // Temporary network failures are common on large libraries/assets; retry safely.
        for (attempt in 1..totalAttempts) {
            cancelled()
            val urls = allUrls.filter { it !in missing }
            if (urls.isEmpty()) break
            val url = urls[(attempt - 1) % urls.size]
            lastUrl = url
            var connection: HttpURLConnection? = null
            try {
                temp.delete()
                connection = Net.open(url, Net.CONNECT_TIMEOUT_MS, Net.READ_TIMEOUT_MS)
                activeConnection = connection
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.connect()

                val code = connection.responseCode
                if (code !in 200..299) throw HttpStatusException(code, url)

                val expectedLength = connection.contentLengthLong
                var bytesRead = 0L
                onFileProgress(target.name, 0L, expectedLength)
                connection.inputStream.use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            cancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            bytesRead += count
                            onFileProgress(target.name, bytesRead, expectedLength)
                        }
                        output.flush()
                    }
                }

                cancelled()
                check(temp.isFile && temp.length() > 0L) {
                    tr("Сервер вернул пустой файл: {0}", target.name)
                }
                if (expectedLength >= 0L) {
                    check(temp.length() == expectedLength) {
                        tr("Загрузка {0} прервалась: ожидалось {1} байт, получено {2}", target.name, expectedLength, temp.length())
                    }
                }
                if (item.sha1 != null) {
                    check(sha1(temp).equals(item.sha1, ignoreCase = true)) {
                        tr("Проверка SHA-1 не пройдена для {0}", target.name)
                    }
                }

                // Replace the destination only after the temporary file is complete and verified.
                try {
                    java.nio.file.Files.move(
                        temp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        temp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                }
                // Если до этого был сбой сети, запоминаем, какой адрес сработал: дальше идём сразу туда.
                val earlierNetworkFailure = lastError?.let { Net.isNetworkError(it) } == true
                if (earlierNetworkFailure && item.fallbackUrls.isNotEmpty()) Net.mirrorFirst = url != item.url
                return
            } catch (cancel: java.util.concurrent.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                lastError = error
                temp.delete()
                if (error is HttpStatusException && error.code == 404) missing += url
                if (attempt < totalAttempts) {
                    // Short bounded backoff; cancellation is checked before retry.
                    try {
                        Thread.sleep(500L * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw java.util.concurrent.CancellationException(tr("Установка отменена"))
                    }
                }
            } finally {
                connection?.disconnect()
                if (activeConnection === connection) activeConnection = null
            }
        }

        temp.delete()
        // Файла нет на адресах, до которых достучались, а остальные недоступны: вызывающий код может попробовать другую версию.
        val sawOnlyMissingOrNetwork = lastError is HttpStatusException || lastError?.let { Net.isNetworkError(it) } == true
        if (missing.isNotEmpty() && sawOnlyMissingOrNetwork) {
            throw HttpStatusException(404, item.url, unreachable = missing.size < allUrls.size)
        }
        val reason = lastError?.let { Net.explain(lastUrl, it) } ?: tr("неизвестная ошибка")
        throw IllegalStateException(tr("Не удалось загрузить {0}: {1}", target.name, reason), lastError)
    }

    // Те же правила, что использует запуск игры: раньше здесь была отдельная неточная копия.
    private fun allowedByRules(library: JsonObject): Boolean = MinecraftRules.libraryAllowed(library)

    private fun extractNatives(archive: File, destination: File) {
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile

        java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                cancelled()
                val entry = zip.nextEntry ?: break

                if (entry.isDirectory) continue
                if (entry.name.startsWith("META-INF/")) continue

                val output = File(destination, entry.name).canonicalFile

                check(output.toPath().startsWith(canonicalRoot.toPath())) {
                    tr("Некорректный путь в native-архиве")
                }

                output.parentFile?.mkdirs()
                output.outputStream().buffered().use { stream ->
                    zip.copyTo(stream)
                }
            }
        }
    }

    suspend fun install(
        version: String,
        onStatus: (String) -> Unit,
        onProgress: (String, Long, Long, Int, Int, Float) -> Unit = { _, _, _, _, _, _ -> }
    ) {
        cancelRequested = false
        withContext(Dispatchers.IO) {
            require(version.matches(Regex("[A-Za-z0-9._-]+"))) {
                tr("Некорректный идентификатор версии")
            }

            val root = File(
                System.getProperty("user.home"),
                ".material-client"
            )

            val versionDir = File(root, "versions/$version")
            val librariesDir = File(root, "libraries")
            val assetsDir = File(root, "assets")
            val nativesDir = File(versionDir, "natives")

            onStatus(tr("Подготовка файлов Minecraft…"))
            cancelled()

            val manifest = readJson(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
            )

            val entry = manifest.getAsJsonArray("versions")
                .firstOrNull {
                    it.asJsonObject.get("id").asString == version
                }?.asJsonObject
                ?: error(tr("Версия {0} не найдена в манифесте Mojang", version))

            val metadata = readJson(entry.get("url").asString)
            versionDir.mkdirs()

            File(versionDir, "$version.json").writeText(metadata.toString())

            val client = metadata.getAsJsonObject("downloads")
                .getAsJsonObject("client")

            val jobs = linkedMapOf<String, DownloadItem>()

            fun add(item: DownloadItem) {
                jobs[item.file.absolutePath] = item
            }

            add(
                DownloadItem(
                    client.get("url").asString,
                    File(versionDir, "$version.jar"),
                    client.get("sha1")?.asString
                )
            )

            val libraries = metadata.getAsJsonArray("libraries")

            for (element in libraries) {
                cancelled()

                val library = element.asJsonObject
                if (!allowedByRules(library)) continue

                val downloads = library.getAsJsonObject("downloads")
                    ?: continue

                val artifact = downloads.getAsJsonObject("artifact")
                if (artifact != null) {
                    val path = artifact.get("path").asString
                    add(
                        DownloadItem(
                            artifact.get("url").asString,
                            File(librariesDir, path),
                            artifact.get("sha1")?.asString
                        )
                    )
                }

                val natives = library.getAsJsonObject("natives")
                val windowsNative = natives?.get("windows")?.asString

                if (windowsNative != null) {
                    val classifiers = downloads.getAsJsonObject("classifiers")
                    val classifier = windowsNative.replace(
                        "\${arch}",
                        if (System.getProperty("os.arch").contains("64")) "64" else "32"
                    )

                    val nativeArtifact = classifiers?.getAsJsonObject(classifier)

                    if (nativeArtifact != null) {
                        val nativeFile = File(
                            librariesDir,
                            nativeArtifact.get("path").asString
                        )

                        add(
                            DownloadItem(
                                nativeArtifact.get("url").asString,
                                nativeFile,
                                nativeArtifact.get("sha1")?.asString
                            )
                        )
                    }
                }
            }

            val assetIndexInfo = metadata.getAsJsonObject("assetIndex")
            val assetIndexId = assetIndexInfo.get("id").asString
            val assetIndexFile = File(assetsDir, "indexes/$assetIndexId.json")

            add(
                DownloadItem(
                    assetIndexInfo.get("url").asString,
                    assetIndexFile,
                    assetIndexInfo.get("sha1")?.asString
                )
            )

            // Конфигурация log4j: без неё версии 1.7–1.18.0 уязвимы к Log4Shell.
            val loggingFile = metadata.getAsJsonObject("logging")
                ?.getAsJsonObject("client")
                ?.getAsJsonObject("file")
            if (loggingFile != null) {
                add(
                    DownloadItem(
                        loggingFile.get("url").asString,
                        File(assetsDir, "log_configs/${File(loggingFile.get("id").asString).name}"),
                        loggingFile.get("sha1")?.asString
                    )
                )
            }

            onStatus(tr("Подготавливаем список файлов…"))

            // Индекс ресурсов нужен для построения списка загрузок. Сам файл индекса скачивается
            // как обычный элемент списка (с проверкой SHA-1), поэтому отдельно его не перезаписываем.
            val indexJson = readJson(assetIndexInfo.get("url").asString)

            val objects = indexJson.getAsJsonObject("objects")
            for ((_, value) in objects.entrySet()) {
                cancelled()

                val asset = value.asJsonObject
                val hash = asset.get("hash").asString
                val prefix = hash.take(2)

                add(
                    DownloadItem(
                        "https://resources.download.minecraft.net/$prefix/$hash",
                        File(assetsDir, "objects/$prefix/$hash"),
                        hash
                    )
                )
            }

            // Up to 16 simultaneous requests helps with the many small asset files.
            // The shared 5 MiB/s limiter above prevents all connections from saturating
            // the user's entire internet connection.
            val allJobs = jobs.values.toList()
            val completed = java.util.concurrent.atomic.AtomicInteger(0)
            val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
            val progressLock = Any()
            val partialProgress = mutableMapOf<String, Float>()

            onStatus(tr("Загрузка файлов в {0} потоков: 0 / {1}", MAX_CONCURRENT_DOWNLOADS, allJobs.size))
            coroutineScope {
                allJobs.map { item ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            cancelled()
                            download(item) { fileName, bytesRead, totalBytes ->
                                val fraction = if (totalBytes > 0L) {
                                    (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                val snapshot = synchronized(progressLock) {
                                    partialProgress[item.file.absolutePath] = fraction
                                    val overall = ((completed.get() + partialProgress.values.sum()) / allJobs.size.toFloat())
                                        .coerceIn(0f, 1f)
                                    completed.get() to overall
                                }
                                onProgress(fileName, bytesRead, totalBytes, snapshot.first, allJobs.size, snapshot.second)
                            }
                            val snapshot = synchronized(progressLock) {
                                val done = completed.incrementAndGet()
                                partialProgress.remove(item.file.absolutePath)
                                val overall = ((done + partialProgress.values.sum()) / allJobs.size.toFloat())
                                    .coerceIn(0f, 1f)
                                done to overall
                            }
                            onStatus(tr("Загрузка файлов: {0} / {1}", snapshot.first, allJobs.size))
                            onProgress(
                                item.file.name,
                                item.file.length(),
                                item.file.length(),
                                snapshot.first,
                                allJobs.size,
                                snapshot.second
                            )
                        }
                    }
                }.awaitAll()
            }

            // Распаковываем Windows natives после их загрузки.
            nativesDir.deleteRecursively()
            nativesDir.mkdirs()

            for (element in libraries) {
                cancelled()

                val library = element.asJsonObject
                if (!allowedByRules(library)) continue

                val downloads = library.getAsJsonObject("downloads") ?: continue
                val natives = library.getAsJsonObject("natives") ?: continue
                val windowsNative = natives.get("windows")?.asString ?: continue

                val classifier = windowsNative.replace(
                    "\${arch}",
                    if (System.getProperty("os.arch").contains("64")) "64" else "32"
                )

                val nativeArtifact = downloads
                    .getAsJsonObject("classifiers")
                    ?.getAsJsonObject(classifier)
                    ?: continue

                val nativeFile = File(
                    librariesDir,
                    nativeArtifact.get("path").asString
                )

                if (nativeFile.isFile) {
                    extractNatives(nativeFile, nativesDir)
                }
            }

            cancelled()
            // Установка не должна считаться успешной, если хотя бы одна библиотека не оказалась на диске.
            val missingLibraries = VersionResolver.classpathLibraries(VersionResolver.resolve(version))
                .filter { !it.isFile }
            check(missingLibraries.isEmpty()) {
                tr("Не скачаны библиотеки ({0}), например {1}. Запусти установку ещё раз.", missingLibraries.size, missingLibraries.first().name)
            }
            onStatus(tr("Основные файлы Minecraft {0} установлены", version))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
data class ModrinthMod(
    val id: String,
    val title: String,
    val description: String,
    val author: String,
    val downloads: Long,
    val iconUrl: String?
)

@Composable
private fun ModrinthProjectIcon(url: String?, title: String) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) null else try {
                val connection = URL(url).openConnection().apply {
                    connectTimeout = 5000
                    readTimeout = 7000
                    setRequestProperty("User-Agent", "MaterialClient/1.0")
                }
                connection.getInputStream().use { stream -> ImageIO.read(stream)?.toComposeImageBitmap() }
            } catch (_: Exception) { null }
        }
    }
    Surface(
        modifier = Modifier.size(72.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!, contentDescription = "$title icon", modifier = Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun App() {
    var profiles by remember { mutableStateOf(ProfileStore.load()) }
    var currentPage by remember { mutableStateOf("Экземпляры") }
    // Язык интерфейса лежит в I18n (состояние Compose): любой tr() на экране перерисовывается при его смене.
    val uiLanguage = I18n.language
    var contextMenuProfile by remember { mutableStateOf<Instance?>(null) }
    var deleteTarget by remember { mutableStateOf<Instance?>(null) }
    val savedTheme = remember { ThemePreferences.load() }
    var accentChoice by remember { mutableStateOf(savedTheme.getProperty("themeChoice", "Бирюзовый")) }
    var customHex by remember { mutableStateOf(savedTheme.getProperty("themeCustomHex", "#72E5D1")) }
    var cardScale by remember { mutableFloatStateOf(savedTheme.getProperty("instanceCardScale", "1.0").toFloatOrNull()?.coerceIn(0.8f, 1.2f) ?: 1.0f) }
    var selectedFontPath by remember { mutableStateOf(savedTheme.getProperty("customFontPath", "")) }
    var fontStatus by remember { mutableStateOf("") }
    val interfaceFontFamily = remember(selectedFontPath) {
        if (selectedFontPath.isNotBlank()) {
            try {
                val fontFile = File(selectedFontPath)
                if (fontFile.isFile) FontFamily(
                    ComposePlatformFont(file = fontFile, weight = FontWeight.Normal),
                    ComposePlatformFont(file = fontFile, weight = FontWeight.Bold)
                ) else FontFamily.SansSerif
            } catch (_: Throwable) { FontFamily.SansSerif }
        } else FontFamily.SansSerif
    }
    fun saveFontPath(path: String) {
        selectedFontPath = path
        val props = ThemePreferences.load()
        props.setProperty("customFontPath", path)
        val settingsFile = File(System.getProperty("user.home"), ".material-client/settings.properties")
        settingsFile.parentFile?.mkdirs()
        settingsFile.outputStream().use { props.store(it, "Material Client settings") }
    }
    fun parseHex(value: String, fallback: Color): Color {
        return try {
            val normalized = value.trim().removePrefix("#")
            require(normalized.length == 6)
            Color(normalized.toLong(16) or 0xFF000000L)
        } catch (_: Exception) {
            fallback
        }
    }
    val Accent = when (accentChoice) {
        "Фиолетовый" -> Color(0xFFB9A2FF)
        "Синий" -> Color(0xFF82B1FF)
        "Розовый" -> Color(0xFFFF9ECF)
        "Оранжевый" -> Color(0xFFFFB86B)
        "Свой цвет" -> parseHex(customHex, Color(0xFF72E5D1))
        else -> Color(0xFF72E5D1)
    }
    val AccentDark = Accent.copy(red = (Accent.red * 0.68f).coerceIn(0f, 1f), green = (Accent.green * 0.68f).coerceIn(0f, 1f), blue = (Accent.blue * 0.68f).coerceIn(0f, 1f))
    val AccentSoft = Color(
        red = (0.10f + Accent.red * 0.10f).coerceIn(0f, 1f),
        green = (0.11f + Accent.green * 0.10f).coerceIn(0f, 1f),
        blue = (0.13f + Accent.blue * 0.10f).coerceIn(0f, 1f),
        alpha = 1f
    )
    // Фон получает лишь лёгкий оттенок выбранного акцента, не яркость самой кнопки.
    val AppBg = Color(
        red = (0.075f + Accent.red * 0.025f).coerceIn(0f, 1f),
        green = (0.080f + Accent.green * 0.025f).coerceIn(0f, 1f),
        blue = (0.095f + Accent.blue * 0.035f).coerceIn(0f, 1f),
        alpha = 1f
    )
    val SurfaceA = Color(
        red = (0.115f + Accent.red * 0.025f).coerceIn(0f, 1f),
        green = (0.125f + Accent.green * 0.025f).coerceIn(0f, 1f),
        blue = (0.145f + Accent.blue * 0.035f).coerceIn(0f, 1f),
        alpha = 1f
    )
    val SurfaceB = Color(
        red = (0.155f + Accent.red * 0.025f).coerceIn(0f, 1f),
        green = (0.165f + Accent.green * 0.025f).coerceIn(0f, 1f),
        blue = (0.185f + Accent.blue * 0.035f).coerceIn(0f, 1f),
        alpha = 1f
    )
    val TextMain = Color(0xFFF1F3F6)
    val TextMuted = Color(0xFFADB4BE)
    var selectedName by remember {
        mutableStateOf(profiles.firstOrNull()?.name.orEmpty())
    }
    var modTargetInstance by remember { mutableStateOf(profiles.firstOrNull()?.name.orEmpty()) }
    val selectedProfile = profiles.firstOrNull { it.name == selectedName }

    var versions by remember { mutableStateOf<List<GameVersion>>(emptyList()) }
    var versionStatus by remember { mutableStateOf(tr("Загружаем список версий…")) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("Minecraft 1.21.1") }
    var newVersion by remember { mutableStateOf("1.21.1") }
    var newLoader by remember { mutableStateOf("Vanilla") }
    var installOptiFine by remember { mutableStateOf(false) }
    var showSnapshots by remember { mutableStateOf(false) }
    var showAlphaBeta by remember { mutableStateOf(false) }
    var versionMenuExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(tr("Готов к работе")) }
    var installing by remember { mutableStateOf(false) }
    var installStatus by remember { mutableStateOf("") }
    var installFileName by remember { mutableStateOf("") }
    var installProgress by remember { mutableStateOf(0f) }
    var installBytesRead by remember { mutableStateOf(0L) }
    var installBytesTotal by remember { mutableStateOf(-1L) }
    var installCompletedFiles by remember { mutableStateOf(0) }
    var installTotalFiles by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var installJob by remember { mutableStateOf<Job?>(null) }
    var modQuery by remember { mutableStateOf("") }
    var mods by remember { mutableStateOf<List<ModrinthMod>>(emptyList()) }
    var modsLoading by remember { mutableStateOf(false) }
    var modsError by remember { mutableStateOf<String?>(null) }
    var gameStatus by remember {
        mutableStateOf(tr("Игра не запущена"))
    }
    var gameRunning by remember {
        mutableStateOf(false)
    }
    var javaPath by remember { mutableStateOf(JavaPreferences.loadJavaPath()) }
    var javaSettingsStatus by remember { mutableStateOf("") }
    var autoJava by remember { mutableStateOf(GameSettings.autoJava) }
    var foundJavaReport by remember { mutableStateOf("") }
    var globalMemoryText by remember { mutableStateOf(GameSettings.memoryMb.let { if (it > 0) it.toString() else "" }) }
    var globalJvmArgsText by remember { mutableStateOf(GameSettings.jvmArgs) }
    var launchSettingsStatus by remember { mutableStateOf("") }
    val totalRamMb = remember { GameMemory.totalMb() }
    var settingsTarget by remember { mutableStateOf<Instance?>(null) }
    var settingsMemoryText by remember { mutableStateOf("") }
    var settingsJvmArgsText by remember { mutableStateOf("") }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var installedMods by remember { mutableStateOf<List<ModEntry>>(emptyList()) }
    var installedModsStatus by remember { mutableStateOf("") }
    var installedModsRefresh by remember { mutableStateOf(0) }
    var modDeleteTarget by remember { mutableStateOf<ModEntry?>(null) }
    var logInstance by remember { mutableStateOf(profiles.firstOrNull()?.name.orEmpty()) }
    var logSourceLabel by remember { mutableStateOf("") }
    var logRefresh by remember { mutableStateOf(0) }
    var logView by remember { mutableStateOf<LogView?>(null) }
    LaunchedEffect(currentPage, modTargetInstance, installedModsRefresh) {
        if (currentPage == "Установленные моды") {
            installedMods = withContext(kotlinx.coroutines.Dispatchers.IO) { InstalledMods.list(modTargetInstance) }
        }
    }
    LaunchedEffect(currentPage, logInstance, logSourceLabel, logRefresh) {
        if (currentPage == "Журнал игры") {
            logView = withContext(kotlinx.coroutines.Dispatchers.IO) {
                GameLogs.load(logInstance, logSourceLabel.ifBlank { null })
            }
        }
    }
    var proxyText by remember { mutableStateOf(Net.savedProxy()) }
    var proxyStatus by remember { mutableStateOf("") }
    var networkReport by remember { mutableStateOf("") }
    var networkChecking by remember { mutableStateOf(false) }

    var microsoftStatus by remember {
        mutableStateOf(tr("Не выполнен вход"))
    }
    var microsoftCode by remember {
        mutableStateOf("")
    }
    var microsoftVerificationUrl by remember {
        mutableStateOf("")
    }
    var microsoftBusy by remember {
        mutableStateOf(false)
    }

    var offlineUsername by remember {
        mutableStateOf(OfflineAccountPreferences.loadUsername())
    }
    var offlineAccounts by remember { mutableStateOf(OfflineAccountPreferences.loadAccounts()) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var showAddOfflineAccountDialog by remember { mutableStateOf(false) }
    var newOfflineUsername by remember { mutableStateOf("") }

    var accountStatus by remember {
        mutableStateOf("")
    }

    // Сообщения, которые уже появились на экране, остались бы на прежнем языке, поэтому при смене языка
    // они обновляются или сбрасываются. Новые сообщения сразу создаются на выбранном языке.
    fun setLanguage(code: String) {
        val language = I18n.normalize(code)
        if (language == I18n.language) return
        I18n.language = language
        try {
            GameSettings.saveLanguage(language)
        } catch (_: Exception) {
            // Не записался файл настроек: язык сменится до закрытия лаунчера.
        }
        fontStatus = ""
        versionStatus = if (versions.isNotEmpty()) {
            tr("Версий загружено: {0}", versions.size)
        } else {
            I18n.retranslate(versionStatus, "Загружаем список версий…", "Не удалось загрузить версии. Проверь интернет.") ?: versionStatus
        }
        if (!installing) {
            status = tr("Готов к работе")
            installStatus = ""
        }
        gameStatus = if (gameRunning) tr("Minecraft запущен…") else tr("Игра не запущена")
        microsoftStatus = if (microsoftBusy) tr("Ожидаем подтверждения в браузере…") else tr("Не выполнен вход")
        accountStatus = ""
        javaSettingsStatus = ""
        launchSettingsStatus = ""
        proxyStatus = ""
        installedModsStatus = ""
        foundJavaReport = ""
        if (!networkChecking) networkReport = ""
        settingsError = null
        createError = null
    }

    LaunchedEffect(Unit) {
        try {
            versions = VersionRepository.fetch()
            versionStatus = tr("Версий загружено: {0}", versions.size)
            if (versions.isNotEmpty() && versions.none { it.id == newVersion }) {
                newVersion = versions.firstOrNull { it.type == "release" }?.id ?: versions.first().id
                newName = "Minecraft $newVersion"
            }
        } catch (e: Exception) {
            versionStatus = tr("Не удалось загрузить версии. Проверь интернет.")
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF35275E),
            secondary = Accent,
            background = AppBg,
            surface = SurfaceA,
            onSurface = TextMain
        ),
        typography = Typography().let { t ->
            val sansBold = interfaceFontFamily
            t.copy(
                displayLarge = t.displayLarge.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                displayMedium = t.displayMedium.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                displaySmall = t.displaySmall.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                headlineLarge = t.headlineLarge.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                headlineMedium = t.headlineMedium.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                headlineSmall = t.headlineSmall.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                titleLarge = t.titleLarge.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                titleMedium = t.titleMedium.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                titleSmall = t.titleSmall.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                bodyLarge = t.bodyLarge.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                bodyMedium = t.bodyMedium.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                bodySmall = t.bodySmall.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                labelLarge = t.labelLarge.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                labelMedium = t.labelMedium.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold),
                labelSmall = t.labelSmall.copy(fontFamily = sansBold, fontWeight = FontWeight.Bold)
            )
        }
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(
                fontFamily = interfaceFontFamily,
                fontWeight = FontWeight.Bold
            )
        ) {
        Row(Modifier.fillMaxSize().background(AppBg)) {

            Column(
                Modifier
                    .width(204.dp)
                    .fillMaxHeight()
                    .background(SurfaceA)
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Accent,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Computer,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column {
                        Text(
                            "MATERIAL",
                            color = TextMain,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp
                        )
                        Text(
                            "CLIENT",
                            color = Accent,
                            letterSpacing = 3.sp,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        Text(
                            "PRISM-INSPIRED",
                            color = TextMuted,
                            letterSpacing = 1.sp,
                            fontSize = 8.sp,
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.height(30.dp))

                Text(
                    tr("БИБЛИОТЕКА"),
                    color = TextMuted,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp
                )

                Spacer(Modifier.height(10.dp))

                listOf(
                    "Экземпляры" to Icons.Default.Computer,
                    "Каталог модов" to Icons.Default.Extension,
                    "Установленные моды" to Icons.Default.Build,
                    "Журнал игры" to Icons.Default.Info,
                    "Настройки" to Icons.Default.Settings
                ).forEach { (label, icon) ->
                    val selected = currentPage == label

                    Surface(
                        color = if (selected) AccentSoft else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { currentPage = label }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = if (selected) Accent else TextMuted
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                tr(label),
                                color = if (selected) TextMain else TextMuted,
                                fontWeight = if (selected) FontWeight.SemiBold
                                else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Spacer(Modifier.height(12.dp))

                Text("MATERIAL CLIENT", color = TextMuted, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Text(status, color = TextMain, fontSize = 12.sp)
            }


            Column(
                Modifier
                    .fillMaxSize()
                    .background(AppBg)
                    .then(
                        if (currentPage == "Настройки") Modifier.verticalScroll(rememberScrollState())
                        else Modifier
                    )
                    .padding(horizontal = 30.dp, vertical = 26.dp)
            ) {
                if (currentPage == "Экземпляры") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                tr(currentPage),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMain
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("Управляй своими сборками Minecraft"),
                                color = TextMuted
                            )
                        }
                        Button(onClick = {
                            newName = "Minecraft $newVersion"
                            createError = null
                            showCreateDialog = true
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Создать экземпляр"))
                        }
                    }
                } else {
                    Text(
                        tr(currentPage),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain
                    )
                }

                Spacer(Modifier.height(6.dp))

                when (currentPage) {
                    "Экземпляры" -> {
                        Spacer(Modifier.height(20.dp))
                    }

                    "Каталог модов" -> {
                        Text(
                            if (modQuery.isBlank()) tr("Популярные моды на Modrinth") else tr("Ищи моды и расширяй свою сборку"),
                            color = TextMuted
                        )

                        Spacer(Modifier.height(20.dp))

                        Text(
                            tr("Каталог модов"),
                            fontSize = 22.sp,
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(12.dp))

                        // Каталог сам подбирает моды под версию Minecraft и загрузчик выбранного экземпляра.
                        val catalogTarget = profiles.firstOrNull { it.name == modTargetInstance }
                        var modLoaderFilter by remember { mutableStateOf(ModLoaders.canonical(catalogTarget?.loader) ?: "Все") }
                        var modInstallStatus by remember { mutableStateOf<String?>(null) }
                        suspend fun loadMods() {
                            modsLoading = true
                            modsError = null
                            try {
                                mods = ModrinthApi.search(
                                    modQuery,
                                    catalogTarget?.version,
                                    if (modLoaderFilter == "Все") null else modLoaderFilter
                                )
                            } catch (e: Exception) {
                                modsError = e.message ?: tr("Не удалось загрузить моды")
                            } finally {
                                modsLoading = false
                            }
                        }
                        LaunchedEffect(modTargetInstance, catalogTarget?.loader) {
                            ModLoaders.canonical(catalogTarget?.loader)?.let { modLoaderFilter = it }
                        }
                        LaunchedEffect(modTargetInstance, catalogTarget?.version, modLoaderFilter) { loadMods() }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr("Установить в:"), color = TextMuted)
                            var targetMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { targetMenuExpanded = true }) { Text(modTargetInstance.ifBlank { tr("Выбрать экземпляр") }) }
                                DropdownMenu(expanded = targetMenuExpanded, onDismissRequest = { targetMenuExpanded = false }) {
                                    profiles.forEach { profile -> DropdownMenuItem(text = { Text("${profile.name} (${profile.version})") }, onClick = { modTargetInstance = profile.name; targetMenuExpanded = false }) }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            var loaderMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { loaderMenuExpanded = true }) { Text(tr("Загрузчик: {0}", tr(modLoaderFilter))) }
                                DropdownMenu(expanded = loaderMenuExpanded, onDismissRequest = { loaderMenuExpanded = false }) {
                                    listOf("Все", "Fabric", "Forge", "NeoForge", "Quilt").forEach { loader -> DropdownMenuItem(text = { Text(tr(loader)) }, onClick = { modLoaderFilter = loader; loaderMenuExpanded = false }) }
                                }
                            }
                        }
                        if (catalogTarget != null) {
                            Text(
                                if (modLoaderFilter == "Все") tr("Показаны моды для Minecraft {0}", catalogTarget.version)
                                else tr("Показаны моды для Minecraft {0} и загрузчика {1}", catalogTarget.version, modLoaderFilter),
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        modInstallStatus?.let { Text(it, color = if (I18n.isProblem(it)) MaterialTheme.colorScheme.error else TextMuted, fontSize = 12.sp) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = modQuery,
                                onValueChange = { modQuery = it },
                                label = { Text(tr("Название мода")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )

                            Button(
                                onClick = { scope.launch { loadMods() } },
                                enabled = !modsLoading
                            ) {
                                Text(if (modsLoading) tr("Поиск...") else tr("Найти"))
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        modsError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }

                        if (modsLoading) {
                            CircularProgressIndicator()
                        } else if (mods.isEmpty() && modsError == null) {
                            Text(tr("Для этой версии Minecraft и загрузчика ничего не найдено."), color = TextMuted)
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(mods) { mod ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        ModrinthProjectIcon(mod.iconUrl, mod.title)
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                mod.title,
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextMain
                                            )

                                            Spacer(Modifier.height(4.dp))

                                            Text(
                                                mod.description,
                                                color = TextMuted,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            Spacer(Modifier.height(8.dp))

                                            Text(
                                                tr("Автор: {0} • Загрузок: {1}", mod.author, String.format(java.util.Locale.US, "%,d", mod.downloads)),
                                                color = TextMuted,
                                                fontSize = 12.sp
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Button(onClick = {
                                            val target = profiles.firstOrNull { it.name == modTargetInstance }
                                            if (target == null) { modInstallStatus = tr("Ошибка: сначала создай экземпляр") }
                                            else if (modLoaderFilter != "Все" && !target.loader.equals(modLoaderFilter, true)) { modInstallStatus = tr("Ошибка: выбранный экземпляр использует {0}, а фильтр — {1}", target.loader, modLoaderFilter) }
                                            else scope.launch {
                                                modInstallStatus = tr("Скачиваем {0}…", mod.title)
                                                try { modInstallStatus = ModrinthApi.installLatestCompatible(mod.id, target) { progress -> modInstallStatus = progress } }
                                                catch (e: Exception) { modInstallStatus = tr("Ошибка установки {0}: {1}", mod.title, e.message ?: e.javaClass.simpleName) }
                                            }
                                            }) { Text(tr("Установить в экземпляр")) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "Установленные моды" -> {
                        Text(
                            tr("Включай, выключай и удаляй моды выбранного экземпляра"),
                            color = TextMuted
                        )

                        Spacer(Modifier.height(14.dp))

                        var modsPageMenuExpanded by remember { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(tr("Экземпляр:"), color = TextMuted)
                            Box {
                                TextButton(onClick = { modsPageMenuExpanded = true }) {
                                    Text(modTargetInstance.ifBlank { tr("Выбрать экземпляр") })
                                }
                                DropdownMenu(
                                    expanded = modsPageMenuExpanded,
                                    onDismissRequest = { modsPageMenuExpanded = false }
                                ) {
                                    profiles.forEach { profile ->
                                        DropdownMenuItem(
                                            text = { Text("${profile.name} (${profile.version})") },
                                            onClick = {
                                                modTargetInstance = profile.name
                                                installedModsStatus = ""
                                                modsPageMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                installedModsStatus = ""
                                installedModsRefresh += 1
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Обновить"))
                            }
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val folder = InstalledMods.dir(modTargetInstance)
                                        folder.mkdirs()
                                        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(folder)
                                    } catch (e: Exception) {
                                        installedModsStatus = tr("Не удалось открыть папку: {0}", e.message ?: e.javaClass.simpleName)
                                    }
                                },
                                enabled = modTargetInstance.isNotBlank()
                            ) {
                                Text(tr("Открыть папку"))
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            if (modTargetInstance.isBlank()) {
                                tr("Сначала создай экземпляр.")
                            } else {
                                tr("Модов: {0}, выключено: {1}. Выключенный мод остаётся в папке с расширением .jar.disabled, игра его не загружает.", installedMods.size, installedMods.count { !it.enabled })
                            },
                            color = TextMuted,
                            fontSize = 12.sp
                        )

                        if (installedModsStatus.isNotBlank()) {
                            Text(
                                installedModsStatus,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        if (installedMods.isEmpty() && modTargetInstance.isNotBlank()) {
                            Text(
                                tr("В папке mods пока ничего нет. Моды можно поставить из каталога."),
                                color = TextMuted
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { currentPage = "Каталог модов" }) {
                                Text(tr("Открыть каталог модов"))
                            }
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(installedMods, key = { it.fileName }) { mod ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = mod.enabled,
                                            onCheckedChange = { wanted ->
                                                installedModsStatus =
                                                    InstalledMods.setEnabled(modTargetInstance, mod.fileName, wanted).orEmpty()
                                                installedModsRefresh += 1
                                            }
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                listOfNotNull(mod.name, mod.version).joinToString("  "),
                                                color = if (mod.enabled) TextMain else TextMuted,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                listOfNotNull(mod.loader, InstalledMods.sizeText(mod.sizeBytes), mod.fileName)
                                                    .joinToString("  •  "),
                                                color = TextMuted,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (!mod.enabled) {
                                            Text(tr("Выключен"), color = TextMuted, fontSize = 12.sp)
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        TextButton(onClick = { modDeleteTarget = mod }) {
                                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(tr("Удалить"))
                                        }
                                    }
                                }
                            }
                        }

                        if (modDeleteTarget != null) {
                            AlertDialog(
                                onDismissRequest = { modDeleteTarget = null },
                                title = { Text(tr("Удалить мод?")) },
                                text = {
                                    Text(tr("Файл {0} будет удалён с диска без возможности восстановления.", modDeleteTarget?.fileName ?: ""))
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val target = modDeleteTarget
                                        if (target != null) {
                                            installedModsStatus = InstalledMods.delete(modTargetInstance, target.fileName).orEmpty()
                                            installedModsRefresh += 1
                                        }
                                        modDeleteTarget = null
                                    }) { Text(tr("Удалить")) }
                                },
                                dismissButton = { TextButton(onClick = { modDeleteTarget = null }) { Text(tr("Отмена")) } }
                            )
                        }
                    }

                    "Журнал игры" -> {
                        Text(
                            tr("Вывод запуска, latest.log и последний краш-репорт выбранного экземпляра"),
                            color = TextMuted
                        )

                        Spacer(Modifier.height(14.dp))

                        var logMenuExpanded by remember { mutableStateOf(false) }
                        var logActionStatus by remember { mutableStateOf("") }
                        val currentLog = logView
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(tr("Экземпляр:"), color = TextMuted)
                            Box {
                                TextButton(onClick = { logMenuExpanded = true }) {
                                    Text(logInstance.ifBlank { tr("Выбрать экземпляр") })
                                }
                                DropdownMenu(
                                    expanded = logMenuExpanded,
                                    onDismissRequest = { logMenuExpanded = false }
                                ) {
                                    profiles.forEach { profile ->
                                        DropdownMenuItem(
                                            text = { Text("${profile.name} (${profile.version})") },
                                            onClick = {
                                                logInstance = profile.name
                                                logSourceLabel = ""
                                                logActionStatus = ""
                                                logMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                logActionStatus = ""
                                logRefresh += 1
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("Обновить"))
                            }
                            OutlinedButton(
                                onClick = {
                                    logActionStatus = try {
                                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                            java.awt.datatransfer.StringSelection(currentLog?.text.orEmpty()),
                                            null
                                        )
                                        tr("Журнал скопирован в буфер обмена.")
                                    } catch (e: Exception) {
                                        tr("Не удалось скопировать: {0}", e.message ?: e.javaClass.simpleName)
                                    }
                                },
                                enabled = currentLog != null && currentLog.text.isNotEmpty()
                            ) {
                                Text(tr("Копировать"))
                            }
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val folder = currentLog?.file?.parentFile ?: LauncherPaths.instanceDir(logInstance)
                                        folder.mkdirs()
                                        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(folder)
                                    } catch (e: Exception) {
                                        logActionStatus = tr("Не удалось открыть папку: {0}", e.message ?: e.javaClass.simpleName)
                                    }
                                },
                                enabled = logInstance.isNotBlank()
                            ) {
                                Text(tr("Открыть папку"))
                            }
                        }

                        if (currentLog != null && currentLog.sources.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                currentLog.sources.forEach { label ->
                                    FilterChip(
                                        selected = currentLog.selected == label,
                                        onClick = { logSourceLabel = label },
                                        label = { Text(tr(label)) }
                                    )
                                }
                            }
                        }

                        if (currentLog != null && currentLog.hints.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Surface(
                                color = AccentSoft,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        tr("Возможные причины"),
                                        color = Accent,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    currentLog.hints.forEach { hint ->
                                        Text("• $hint", color = TextMain, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        if (logActionStatus.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(logActionStatus, color = TextMuted, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(10.dp))

                        if (currentLog == null || currentLog.sources.isEmpty()) {
                            Text(
                                if (logInstance.isBlank()) tr("Сначала создай экземпляр.")
                                else tr("Журналов пока нет: они появятся после первого запуска игры."),
                                color = TextMuted
                            )
                        } else {
                            val logLines = remember(currentLog.text) { currentLog.text.lines() }
                            val logListState = rememberLazyListState()
                            LaunchedEffect(logLines) {
                                if (logLines.isNotEmpty()) logListState.scrollToItem(logLines.lastIndex)
                            }
                            Surface(
                                color = SurfaceA,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            ) {
                                LazyColumn(
                                    state = logListState,
                                    modifier = Modifier.fillMaxSize().padding(10.dp)
                                ) {
                                    items(logLines) { line ->
                                        val problem = line.contains("ERROR") || line.contains("FATAL") || line.contains("Exception")
                                        Text(
                                            line,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = if (problem) MaterialTheme.colorScheme.error else TextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "Настройки" -> {
                        var settingsTab by remember { mutableStateOf("look") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                            listOf(
                                "look" to "Внешний вид",
                                "accounts" to "Аккаунты",
                                "java" to "Java и память",
                                "network" to "Сеть"
                            ).forEach { (tabId, tabLabel) ->
                                val tabSelected = settingsTab == tabId
                                Surface(
                                    color = if (tabSelected) AccentSoft else Color.Transparent,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.clickable { settingsTab = tabId }
                                ) {
                                    Text(
                                        tr(tabLabel),
                                        color = if (tabSelected) Accent else TextMuted,
                                        fontWeight = if (tabSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        Spacer(Modifier.height(16.dp))

                        if (settingsTab == "look") {
                        Text(tr("Язык интерфейса"), fontSize = 18.sp, color = TextMain, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = uiLanguage == I18n.RU, onClick = { setLanguage(I18n.RU) }, label = { Text("Русский") })
                            FilterChip(selected = uiLanguage == I18n.EN, onClick = { setLanguage(I18n.EN) }, label = { Text("English") })
                        }
                        Text(tr("Выбор сохраняется и применяется сразу. Сообщения, уже показанные на экране, при переключении очищаются."), color = TextMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            tr("Настрой Material Client под себя"),
                            color = TextMuted
                        )

                        Spacer(Modifier.height(20.dp))

                        Text(
                            tr("Цвет интерфейса"),
                            fontSize = 18.sp,
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tr("Выбери основной акцент для кнопок, выделения и элементов навигации"),
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Бирюзовый", "Фиолетовый", "Синий", "Розовый", "Оранжевый", "Свой цвет").forEach { option ->
                                val swatch = when (option) {
                                    "Фиолетовый" -> Color(0xFFB9A2FF)
                                    "Синий" -> Color(0xFF82B1FF)
                                    "Розовый" -> Color(0xFFFF9ECF)
                                    "Оранжевый" -> Color(0xFFFFB86B)
                                    else -> Color(0xFF72E5D1)
                                }
                                FilterChip(
                                    selected = accentChoice == option,
                                    onClick = { accentChoice = option; ThemePreferences.save(accentChoice, customHex) },
                                    label = { Text(tr(option)) },
                                    leadingIcon = {
                                        Surface(
                                            color = swatch,
                                            shape = RoundedCornerShape(50),
                                            modifier = Modifier.size(12.dp)
                                        ) {}
                                    }
                                )
                            }
                        }

                        if (accentChoice == "Свой цвет") {
                            Spacer(Modifier.height(10.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = customHex,
                                    onValueChange = { customHex = it },
                                    label = { Text(tr("Свой цвет HEX")) },
                                    placeholder = { Text("#72E5D1") },
                                    singleLine = true,
                                    modifier = Modifier.widthIn(min = 180.dp, max = 280.dp)
                                )
                                Button(onClick = {
                                    customHex = if (customHex.startsWith("#")) customHex else "#$customHex"
                                    ThemePreferences.save(accentChoice, customHex)
                                }) { Text(tr("Применить")) }
                            }
                            Text(tr("Введи цвет в формате #RRGGBB. Фон автоматически подстроится мягким оттенком."), color = TextMuted, fontSize = 12.sp)
                        }

                        Spacer(Modifier.height(20.dp))
                        Text(tr("Шрифт интерфейса"), fontSize = 18.sp, color = TextMain, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(tr("Выбери исходный шрифт или загрузи свой файл TTF/OTF. Выбор сохранится после перезапуска."), color = TextMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = {
                                saveFontPath("")
                                fontStatus = tr("Используется исходный шрифт лаунчера")
                            }) { Text(tr("Стандартный")) }
                            Button(onClick = {
                                try {
                                    val dialog = FileDialog(null as Frame?, tr("Выбери файл шрифта"), FileDialog.LOAD)
                                    dialog.setFilenameFilter { _, name -> name.endsWith(".ttf", true) || name.endsWith(".otf", true) }
                                    dialog.isVisible = true
                                    val chosen = if (dialog.file != null && dialog.directory != null) File(dialog.directory, dialog.file) else null
                                    if (chosen != null && chosen.isFile) {
                                        // Validate that Java can read this font before persisting it.
                                        java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, chosen)
                                        saveFontPath(chosen.absolutePath)
                                        fontStatus = tr("Применён шрифт: {0}", chosen.name)
                                    }
                                } catch (e: Throwable) {
                                    fontStatus = tr("Не удалось загрузить шрифт. Выбери корректный TTF/OTF-файл.")
                                }
                            }) { Text(tr("Выбрать свой шрифт…")) }
                        }
                        if (selectedFontPath.isNotBlank()) {
                            Text(tr("Файл: {0}", File(selectedFontPath).name), color = TextMuted, fontSize = 12.sp)
                        }
                        if (fontStatus.isNotBlank()) {
                            Text(fontStatus, color = TextMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(20.dp))

                        Text(
                            tr("Размер карточек экземпляров"),
                            fontSize = 18.sp,
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            tr("{0}% — меняет размер карточек в библиотеке", (cardScale * 100).toInt()),
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        Slider(
                            value = cardScale,
                            onValueChange = { cardScale = it },
                            onValueChangeFinished = {
                                val props = ThemePreferences.load()
                                props.setProperty("instanceCardScale", cardScale.toString())
                                val settingsFile = File(System.getProperty("user.home"), ".material-client/settings.properties")
                                settingsFile.parentFile?.mkdirs()
                                settingsFile.outputStream().use { props.store(it, "Material Client settings") }
                            },
                            valueRange = 0.8f..1.2f,
                            steps = 3,
                            modifier = Modifier.fillMaxWidth()
                        )

                        }

                        if (settingsTab == "accounts") {
                        Text(
                            tr("Офлайн-аккаунт"),
                            fontSize = 18.sp,
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            tr("Используется для серверов, разрешающих offline-вход"),
                            color = TextMuted,
                            fontSize = 13.sp
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = offlineUsername,
                            onValueChange = {
                                offlineUsername = it
                                accountStatus = ""
                            },
                            label = { Text(tr("Ник игрока")) },
                            singleLine = true,
                            isError = OfflineAccountPreferences.validateUsername(
                                offlineUsername
                            ) != null,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val problem =
                                    OfflineAccountPreferences.validateUsername(offlineUsername)

                                if (problem != null) {
                                    accountStatus = problem
                                } else {
                                    try {
                                        OfflineAccountPreferences.saveUsername(
                                            offlineUsername
                                        )
                                        accountStatus = tr("Офлайн-ник сохранён")
                                        status = tr("Офлайн-аккаунт настроен")
                                    } catch (e: Exception) {
                                        accountStatus =
                                            tr("Ошибка сохранения: {0}", e.message ?: tr("неизвестная ошибка"))
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Сохранить ник"))
                        }

                        if (accountStatus.isNotBlank()) {
                            Text(
                                accountStatus,
                                color = if (I18n.isProblem(accountStatus)) MaterialTheme.colorScheme.error else Accent,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(Modifier.height(24.dp))

                        Text(
                            tr("Аккаунт Microsoft"),
                            fontSize = 18.sp,
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            microsoftStatus,
                            color = TextMuted,
                            fontSize = 13.sp
                        )

                        if (microsoftCode.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))

                            Text(
                                tr("Код подтверждения: {0}", microsoftCode),
                                color = Accent,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (microsoftVerificationUrl.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        try {
                                            if (Desktop.isDesktopSupported()) {
                                                Desktop.getDesktop().browse(
                                                    URI(microsoftVerificationUrl)
                                                )
                                            } else {
                                                microsoftStatus =
                                                    tr("Открой ссылку вручную: {0}", microsoftVerificationUrl)
                                            }
                                        } catch (e: Exception) {
                                            microsoftStatus =
                                                tr("Не удалось открыть браузер: {0}", e.message)
                                        }
                                    }
                                ) {
                                    Text(tr("Открыть страницу Microsoft"))
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            enabled = !microsoftBusy,
                            onClick = {
                                scope.launch {
                                    microsoftBusy = true
                                    microsoftStatus = tr("Запрашиваем код Microsoft…")
                                    microsoftCode = ""
                                    microsoftVerificationUrl = ""

                                    try {
                                        val deviceCode = MicrosoftAuth.requestDeviceCode()

                                        microsoftCode = deviceCode.userCode
                                        microsoftVerificationUrl = deviceCode.verificationUri
                                        microsoftStatus = deviceCode.message

                                        try {
                                            if (Desktop.isDesktopSupported()) {
                                                Desktop.getDesktop().browse(
                                                    URI(deviceCode.verificationUri)
                                                )
                                            }
                                        } catch (_: Exception) {
                                            // Код и ссылка останутся видимыми в интерфейсе.
                                        }

                                        microsoftStatus = tr("Ожидаем подтверждения в браузере…")

                                        val token = MicrosoftAuth.pollForToken(deviceCode) {
                                            // Статус ожидания уже отображается в интерфейсе.
                                        }

                                        // Пока токен не сохраняем на диск.
                                        microsoftStatus =
                                            tr("Microsoft OAuth завершён. Xbox/Minecraft-авторизация ещё не выполнена.")

                                        // Не выводим и не записываем access token в логи.
                                        check(token.accessToken.isNotBlank()) {
                                            tr("Microsoft не вернул access token.")
                                        }
                                    } catch (e: Exception) {
                                        microsoftStatus =
                                            tr("Ошибка входа: {0}", e.message ?: tr("неизвестная ошибка"))
                                    } finally {
                                        microsoftBusy = false
                                    }
                                }
                            }
                        ) {
                            if (microsoftBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }

                            Text(
                                if (microsoftBusy) tr("Ожидание входа…") else tr("Войти через Microsoft")
                            )
                        }


                        Spacer(Modifier.height(20.dp))

                        }

                        if (settingsTab == "java") {
                        Text(
                            tr("Настройки лаунчера"),
                            fontSize = 18.sp,
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            tr("Папка данных: {0}/.material-client", System.getProperty("user.home")),
                            color = TextMuted
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "Java",
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = autoJava,
                                onCheckedChange = {
                                    autoJava = it
                                    javaSettingsStatus = try {
                                        GameSettings.saveAutoJava(it)
                                        if (it) tr("Нужная Java будет скачиваться автоматически.") else tr("Автоматическая загрузка Java выключена.")
                                    } catch (e: Exception) {
                                        tr("Не удалось сохранить: {0}", e.message ?: tr("ошибка"))
                                    }
                                }
                            )
                            Text(tr("Скачивать нужную Java автоматически"), color = TextMain, fontSize = 13.sp)
                        }

                        Text(
                            tr("Лаунчер сам выбирает Java под версию игры: 1.20.5 и новее — Java 21, 1.18–1.20.4 — Java 17, старше — Java 8 (Forge и OptiFine для старых версий работают только на Java 8). Если подходящей Java на компьютере нет, она скачивается у Mojang в папку .material-client/runtime."),
                            color = TextMuted,
                            fontSize = 12.sp
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = javaPath,
                            onValueChange = {
                                javaPath = it
                                javaSettingsStatus = ""
                            },
                            label = { Text(tr("Путь к javaw.exe (необязательно)")) },
                            placeholder = {
                                Text(tr("Пусто — Java выбирается автоматически"))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val javaFile = File(javaPath.trim())
                                if (javaPath.isBlank()) {
                                    javaSettingsStatus = try {
                                        JavaPreferences.saveJavaPath("")
                                        tr("Java будет выбираться автоматически.")
                                    } catch (e: Exception) {
                                        tr("Не удалось сохранить: {0}", e.message ?: tr("ошибка"))
                                    }
                                } else if (!javaFile.isFile ||
                                    !(javaFile.name.equals("javaw.exe", true) ||
                                            javaFile.name.equals("java.exe", true) ||
                                            javaFile.name.equals("java", true))
                                ) {
                                    javaSettingsStatus =
                                        tr("Файл не найден. Укажи полный путь к javaw.exe или оставь поле пустым.")
                                } else {
                                    try {
                                        JavaPreferences.saveJavaPath(javaFile.absolutePath)
                                        javaSettingsStatus = tr("Путь к Java сохранён.")
                                    } catch (e: Exception) {
                                        javaSettingsStatus =
                                            tr("Не удалось сохранить: {0}", e.message ?: tr("ошибка"))
                                    }
                                }
                            }
                        ) {
                            Text(tr("Сохранить Java"))
                        }

                        if (javaSettingsStatus.isNotBlank()) {
                            Text(
                                javaSettingsStatus,
                                color = if (I18n.isProblem(javaSettingsStatus)) MaterialTheme.colorScheme.error else TextMuted
                            )
                        }

                        Text(
                            tr("Если указанная Java не подходит версии игры, лаунчер подберёт или скачает подходящую сам."),
                            color = TextMuted,
                            fontSize = 12.sp
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val found = JavaInfo.findInstalled()
                                foundJavaReport = if (found.isEmpty()) {
                                    tr("Установленных Java не найдено.")
                                } else {
                                    found.joinToString("\n") { "Java ${JavaInfo.detectMajor(it) ?: "?"} — ${it.absolutePath}" }
                                }
                            }
                        ) {
                            Text(tr("Показать найденные Java"))
                        }

                        if (foundJavaReport.isNotBlank()) {
                            Text(
                                foundJavaReport,
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            tr("Память и JVM-аргументы"),
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = globalMemoryText,
                            onValueChange = {
                                globalMemoryText = it
                                launchSettingsStatus = ""
                            },
                            label = { Text(tr("Память игры по умолчанию, МБ (пусто — автоматически)")) },
                            placeholder = { Text(tr("Например 4096")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            if (totalRamMb != null) {
                                tr(
                                    "Автоматически: {0} МБ для чистой игры и {1} МБ с модами (всего в компьютере {2} ГБ). Для отдельного экземпляра память можно задать в его меню (правая кнопка мыши → Настройки).",
                                    GameMemory.autoMb("Vanilla", totalRamMb), GameMemory.autoMb("Forge", totalRamMb), totalRamMb / 1024
                                )
                            } else {
                                tr(
                                    "Автоматически: {0} МБ для чистой игры и {1} МБ с модами. Для отдельного экземпляра память можно задать в его меню (правая кнопка мыши → Настройки).",
                                    GameMemory.autoMb("Vanilla", totalRamMb), GameMemory.autoMb("Forge", totalRamMb)
                                )
                            },
                            color = TextMuted,
                            fontSize = 12.sp
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = globalJvmArgsText,
                            onValueChange = {
                                globalJvmArgsText = it
                                launchSettingsStatus = ""
                            },
                            label = { Text(tr("Дополнительные JVM-аргументы для всех экземпляров")) },
                            placeholder = { Text("-XX:+UseG1GC -Dfile.encoding=UTF-8") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val problem = GameMemory.validate(globalMemoryText) ?: JvmArgs.validate(globalJvmArgsText)
                                if (problem != null) {
                                    launchSettingsStatus = problem
                                } else {
                                    launchSettingsStatus = try {
                                        GameSettings.saveMemoryMb(GameMemory.parse(globalMemoryText))
                                        GameSettings.saveJvmArgs(globalJvmArgsText)
                                        tr("Параметры запуска сохранены.")
                                    } catch (e: Exception) {
                                        tr("Не удалось сохранить: {0}", e.message ?: tr("ошибка"))
                                    }
                                }
                            }
                        ) {
                            Text(tr("Сохранить параметры запуска"))
                        }

                        if (launchSettingsStatus.isNotBlank()) {
                            Text(
                                launchSettingsStatus,
                                color = if (I18n.isKey(launchSettingsStatus, "Параметры запуска сохранены.")) TextMuted else MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        }

                        if (settingsTab == "network") {
                        Text(
                            tr("Сеть"),
                            color = TextMain,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = proxyText,
                            onValueChange = {
                                proxyText = it
                                proxyStatus = ""
                            },
                            label = { Text(tr("Прокси (необязательно)")) },
                            placeholder = { Text(tr("127.0.0.1:7890 или socks5://127.0.0.1:1080")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val problem = Net.validateProxy(proxyText)
                                if (problem != null) {
                                    proxyStatus = problem
                                } else {
                                    try {
                                        Net.saveProxy(proxyText)
                                        Net.applyProxy(proxyText)
                                        proxyStatus = if (proxyText.isBlank()) {
                                            tr("Прокси не задан: используются системные настройки.")
                                        } else {
                                            tr("Прокси сохранён.")
                                        }
                                    } catch (e: Exception) {
                                        proxyStatus = tr("Не удалось сохранить: {0}", e.message ?: tr("ошибка"))
                                    }
                                }
                            }
                        ) {
                            Text(tr("Сохранить прокси"))
                        }

                        if (proxyStatus.isNotBlank()) {
                            Text(
                                proxyStatus,
                                color = if (I18n.isProblem(proxyStatus)) MaterialTheme.colorScheme.error else TextMuted
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (!networkChecking) {
                                    networkChecking = true
                                    networkReport = tr("Проверяем сайты…")
                                    scope.launch {
                                        networkReport = try {
                                            withContext(Dispatchers.IO) { Net.checkAll() }
                                        } catch (e: Exception) {
                                            tr("Не удалось выполнить проверку: {0}", e.message ?: tr("ошибка"))
                                        }
                                        networkChecking = false
                                    }
                                }
                            }
                        ) {
                            Text(if (networkChecking) tr("Проверяем…") else tr("Проверить сеть"))
                        }

                        if (networkReport.isNotBlank()) {
                            Text(
                                networkReport,
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }

                        Text(
                            tr("Если загрузчики не скачиваются (таймаут), нажми «Проверить сеть»: там видно, какие сайты недоступны. Тогда включи VPN для всей системы или укажи свой прокси. Слово direct отключает прокси."),
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        }
                    }
                }


                if (currentPage == "Экземпляры") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 132.dp * cardScale),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        gridItems(profiles, key = { it.name }) { profile ->
                            val selected = profile.name == selectedName
                            Box {
                            Surface(
                                color = if (selected) AccentSoft else SurfaceA,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selected) 1.5.dp else 1.dp,
                                    if (selected) Accent else Color.White.copy(alpha = 0.07f)
                                ),
                                modifier = Modifier.fillMaxWidth().height(128.dp)
                                    .pointerInput(profile.name) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.type == PointerEventType.Press && event.buttons == androidx.compose.ui.input.pointer.PointerButtons(2)) {
                                                    selectedName = profile.name
                                                    contextMenuProfile = profile
                                                }
                                            }
                                        }
                                    }
                                    .clickable { selectedName = profile.name }
                            ) {
                                Column(
                                    Modifier.fillMaxSize().padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Surface(
                                        color = if (selected) Accent.copy(alpha = 0.16f) else SurfaceB,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                when (profile.loader.lowercase()) {
                                                    "fabric" -> Icons.Default.Extension
                                                    "forge" -> Icons.Default.Build
                                                    else -> Icons.Default.Computer
                                                },
                                                contentDescription = null,
                                                tint = if (selected) Accent else TextMuted,
                                                modifier = Modifier.size(27.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        profile.name,
                                        color = TextMain,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "${profile.version} • ${profile.loader}",
                                        color = if (selected) Accent else TextMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = contextMenuProfile?.name == profile.name,
                                onDismissRequest = { contextMenuProfile = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(tr("Моды")) },
                                    leadingIcon = { Icon(Icons.Default.Extension, contentDescription = null) },
                                    onClick = {
                                        modTargetInstance = profile.name
                                        installedModsStatus = ""
                                        currentPage = "Установленные моды"
                                        contextMenuProfile = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(tr("Журнал")) },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    onClick = {
                                        logInstance = profile.name
                                        logSourceLabel = ""
                                        currentPage = "Журнал игры"
                                        contextMenuProfile = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(tr("Настройки")) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = {
                                        settingsTarget = profile
                                        settingsMemoryText = if (profile.memoryMb > 0) profile.memoryMb.toString() else ""
                                        settingsJvmArgsText = profile.jvmArgs
                                        settingsError = null
                                        contextMenuProfile = null
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(tr("Удалить")) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = { deleteTarget = profile; contextMenuProfile = null }
                                )
                            }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Surface(color = SurfaceA, shape = RoundedCornerShape(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier.weight(1f).widthIn(min = 0.dp).padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    selectedProfile?.name ?: tr("Нет профиля"),
                                    color = TextMain,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    selectedProfile?.let { "Minecraft ${it.version} • ${it.loader}" } ?: "",
                                    color = Accent,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // Раньше статусы запуска и результат установки нигде не показывались,
                                // и ошибки (нет Java, нет файлов, сбой установки) оставались невидимыми.
                                val messages = listOfNotNull(
                                    installStatus.takeIf { !installing && it.isNotBlank() },
                                    gameStatus.takeIf { !I18n.isKey(it, "Игра не запущена") && it.isNotBlank() }
                                )
                                messages.forEach { message ->
                                    val isProblem = I18n.isProblem(message)
                                    Text(
                                        message,
                                        color = if (isProblem) MaterialTheme.colorScheme.error else TextMuted,
                                        fontSize = 11.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (I18n.startsWithKey(gameStatus, "Ошибка: Minecraft завершился")) {
                                    TextButton(onClick = {
                                        logSourceLabel = ""
                                        currentPage = "Журнал игры"
                                    }) { Text(tr("Открыть журнал")) }
                                }
                            }

                            Column(Modifier.widthIn(min = 0.dp, max = 240.dp).weight(0.85f)) {
                            Button(
                                enabled = if (installing) true else (!gameRunning && selectedProfile != null),
                                onClick = {
                                    if (installing) {
                                        installStatus = tr("Отмена загрузки...")
                                        MinecraftInstaller.requestCancel()
                                        installJob?.cancel(CancellationException(tr("Установка отменена пользователем")))
                                    } else {
                                    val profile = selectedProfile

                                    if (profile != null) {
                                        installing = true
                                        installStatus = tr("Подготовка к установке...")
                                        installFileName = ""
                                        installProgress = 0f
                                        installBytesRead = 0L
                                        installBytesTotal = -1L
                                        installCompletedFiles = 0
                                        installTotalFiles = 0
                                        status = tr("Устанавливаем Minecraft {0}", profile.version)

                                        installJob = scope.launch {
                                            try {
                                                MinecraftInstaller.install(
                                                    profile.version,
                                                    onStatus = { message -> installStatus = message },
                                                    onProgress = { fileName, bytesRead, totalBytes, completed, total, overallProgress ->
                                                        installFileName = fileName
                                                        installBytesRead = bytesRead
                                                        installBytesTotal = totalBytes
                                                        // Concurrent callbacks may arrive out of order; never let the UI counter/progress move backwards.
                                                        installCompletedFiles = maxOf(installCompletedFiles, completed)
                                                        installTotalFiles = total
                                                        installProgress = maxOf(installProgress, overallProgress.coerceIn(0f, 1f))
                                                    }
                                                )

                                                if (MinecraftLauncher.supportedLoaders.none { it.equals(profile.loader, ignoreCase = true) }) {
                                                    error(tr("Загрузчик {0} не поддерживается. Базовые файлы Minecraft скачаны.", profile.loader))
                                                }
                                                LoaderInstaller.install(
                                                    profile.loader,
                                                    profile.version,
                                                    javaPath.trim(),
                                                    // Пока идёт установка загрузчика, не показываем устаревшее «Скачивается: <файл>».
                                                    onStatus = { message -> installFileName = ""; installStatus = message }
                                                )

                                                installStatus = if (profile.loader.equals("Vanilla", ignoreCase = true)) {
                                                    tr("Файлы Minecraft {0} установлены!", profile.version)
                                                } else {
                                                    tr("Minecraft {0} + {1} установлены!", profile.version, profile.loader)
                                                }
                                                status = tr("Установка завершена")
                                                gameStatus = tr("Версия {0} готова к запуску", profile.version)
                                            } catch (e: CancellationException) {
                                                installStatus = tr("Установка отменена")
                                                status = tr("Установка отменена")
                                                throw e
                                            } catch (e: Exception) {
                                                installStatus =
                                                    tr("Ошибка установки: {0}", e.message ?: tr("неизвестная ошибка"))
                                                status = tr("Ошибка установки")
                                            } finally {
                                                installing = false
                                                installJob = null
                                            }
                                        }
                                    }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (installing) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                            ) {
                                if (installing) {
                                    Icon(Icons.Default.Close, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                }

                                Text(if (installing) tr("Отменить загрузку") else tr("Установить / проверить файлы"), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            }

                            if (installing) {
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { installProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    trackColor = SurfaceB
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    if (installFileName.isNotBlank()) {
                                        val fileSize = if (installBytesTotal > 0L) "${(installBytesRead / 1024)} / ${(installBytesTotal / 1024)} KB" else "${installBytesRead / 1024} KB"
                                        tr("Скачивается: {0} ({1})", installFileName, fileSize)
                                    } else installStatus,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (installTotalFiles > 0) {
                                    Text(
                                        tr("Готово: {0} / {1} • {2}%", installCompletedFiles, installTotalFiles, (installProgress * 100).toInt()),
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            if (currentPage == "Экземпляры") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box {
                                        OutlinedButton(
                                            onClick = { accountMenuExpanded = true },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.widthIn(min = 130.dp, max = 190.dp)
                                        ) {
                                            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(offlineUsername.ifBlank { tr("Игрок") }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = tr("Выбрать аккаунт"))
                                        }
                                        DropdownMenu(
                                            expanded = accountMenuExpanded,
                                            onDismissRequest = { accountMenuExpanded = false }
                                        ) {
                                            offlineAccounts.forEach { account ->
                                                DropdownMenuItem(
                                                    text = { Text(account) },
                                                    leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                                                    trailingIcon = if (account == offlineUsername) ({ Icon(Icons.Default.Check, contentDescription = null, tint = Accent) }) else null,
                                                    onClick = {
                                                        offlineUsername = account
                                                        OfflineAccountPreferences.saveUsername(account)
                                                        offlineAccounts = OfflineAccountPreferences.loadAccounts()
                                                        accountMenuExpanded = false
                                                        accountStatus = tr("Аккаунт {0} выбран", account)
                                                    }
                                                )
                                            }
                                            HorizontalDivider()
                                            DropdownMenuItem(
                                                text = { Text(tr("Добавить аккаунт…")) },
                                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                                onClick = {
                                                    accountMenuExpanded = false
                                                    newOfflineUsername = ""
                                                    showAddOfflineAccountDialog = true
                                                }
                                            )
                                        }
                                    }
                                    Button(
                                    enabled = !installing && !gameRunning && selectedProfile != null,
                                    onClick = {
                                        val profile = selectedProfile

                                        if (profile == null) {
                                            gameStatus = tr("Сначала выбери профиль")
                                        } else {
                                            val username = offlineUsername.trim()
                                            val usernameProblem =
                                                OfflineAccountPreferences.validateUsername(username)

                                            if (usernameProblem != null) {
                                                gameStatus = usernameProblem
                                            } else {
                                                gameRunning = true
                                                gameStatus = tr("Запускаем Minecraft…")

                                                val startedAt = System.currentTimeMillis()
                                                scope.launch {
                                                    try {
                                                        val result = withContext(
                                                            kotlinx.coroutines.Dispatchers.IO
                                                        ) {
                                                            OfflineAccountPreferences.saveUsername(username)

                                                            // Проверка файлов, выбор версии (для Forge это установленный
                                                            // профиль Forge) и запуск выполняются вне UI-потока.
                                                            MinecraftLauncher.launchProfile(
                                                                baseVersion = profile.version,
                                                                loader = profile.loader,
                                                                profileName = profile.name,
                                                                username = username,
                                                                javaPath = javaPath.trim(),
                                                                memoryMb = profile.memoryMb,
                                                                extraJvmArgs = profile.jvmArgs,
                                                                // Скачивание Java перед запуском: показываем, что происходит.
                                                                onStatus = { message -> gameStatus = message }
                                                            )
                                                        }

                                                        gameStatus = result.message

                                                        val process = result.process
                                                        if (result.success && process == null) {
                                                            gameStatus = tr("Процесс Minecraft не создан")
                                                        } else if (process != null) {
                                                            val exitCode = withContext(
                                                                kotlinx.coroutines.Dispatchers.IO
                                                            ) {
                                                                process.waitFor()
                                                            }

                                                            // После падения ищем в журналах понятную причину.
                                                            val crashHint = if (exitCode == 0) null else withContext(
                                                                kotlinx.coroutines.Dispatchers.IO
                                                            ) {
                                                                try {
                                                                    GameLogs.load(profile.name, GameLogs.LAUNCH).hints.firstOrNull()
                                                                        ?: GameLogs.load(profile.name, GameLogs.CRASH)
                                                                            .takeIf { (it.file?.lastModified() ?: 0L) >= startedAt - 5_000 }
                                                                            ?.hints?.firstOrNull()
                                                                } catch (_: Exception) {
                                                                    null
                                                                }
                                                            }
                                                            if (exitCode != 0) logInstance = profile.name

                                                            gameStatus = if (exitCode == 0) {
                                                                tr("Minecraft завершил работу (код 0)")
                                                            } else {
                                                                tr("Ошибка: Minecraft завершился с кодом {0}. ", exitCode) +
                                                                    (crashHint?.let { "$it " } ?: "") +
                                                                    tr("Подробности: страница «Журнал игры».")
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        gameStatus =
                                                            tr("Ошибка запуска: {0}", e.message ?: tr("неизвестная ошибка"))
                                                    } finally {
                                                        gameRunning = false
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Computer,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (gameRunning) tr("Minecraft запущен…") else tr("Играть"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                    }
                                }

                            }

                            Spacer(Modifier.height(4.dp))
                            }
                        }

                        if (showAddOfflineAccountDialog) {
                            AlertDialog(
                                onDismissRequest = { showAddOfflineAccountDialog = false },
                                title = { Text(tr("Добавить офлайн-аккаунт")) },
                                text = {
                                    Column {
                                        Text(tr("Укажи игровой ник (3–16 символов: латиница, цифры и _)."), color = TextMuted)
                                        Spacer(Modifier.height(10.dp))
                                        OutlinedTextField(
                                            value = newOfflineUsername,
                                            onValueChange = { newOfflineUsername = it; accountStatus = "" },
                                            label = { Text(tr("Ник игрока")) },
                                            singleLine = true,
                                            isError = newOfflineUsername.isNotBlank() && OfflineAccountPreferences.validateUsername(newOfflineUsername) != null
                                        )
                                        if (accountStatus.isNotBlank()) {
                                            Spacer(Modifier.height(6.dp))
                                            Text(accountStatus, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val name = newOfflineUsername.trim()
                                        val problem = OfflineAccountPreferences.validateUsername(name)
                                        when {
                                            problem != null -> accountStatus = problem
                                            offlineAccounts.any { it.equals(name, ignoreCase = true) } -> accountStatus = tr("Такой аккаунт уже добавлен")
                                            else -> {
                                                OfflineAccountPreferences.saveUsername(name)
                                                offlineUsername = name
                                                offlineAccounts = OfflineAccountPreferences.loadAccounts()
                                                accountStatus = tr("Аккаунт {0} добавлен", name)
                                                showAddOfflineAccountDialog = false
                                            }
                                        }
                                    }) { Text(tr("Добавить")) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showAddOfflineAccountDialog = false }) { Text(tr("Отмена")) }
                                }
                            )
                        }

                        if (settingsTarget != null) {
                            AlertDialog(
                                onDismissRequest = { settingsTarget = null },
                                title = { Text(tr("Настройки экземпляра")) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "${settingsTarget?.name ?: ""} • ${settingsTarget?.version ?: ""} • ${settingsTarget?.loader ?: ""}",
                                            color = TextMuted,
                                            fontSize = 12.sp
                                        )
                                        OutlinedTextField(
                                            value = settingsMemoryText,
                                            onValueChange = { settingsMemoryText = it; settingsError = null },
                                            label = { Text(tr("Память, МБ (пусто — как в общих настройках)")) },
                                            placeholder = { Text(tr("Например 4096")) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            tr("Сейчас игра получит {0} МБ.", GameMemory.effectiveMb(GameMemory.parse(settingsMemoryText), settingsTarget?.loader ?: "Vanilla")),
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                        OutlinedTextField(
                                            value = settingsJvmArgsText,
                                            onValueChange = { settingsJvmArgsText = it; settingsError = null },
                                            label = { Text(tr("Дополнительные JVM-аргументы")) },
                                            placeholder = { Text("-XX:+UseG1GC") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            tr("Они добавляются к общим аргументам из настроек лаунчера. Собственный -Xmx переопределяет поле памяти."),
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                        settingsError?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val target = settingsTarget
                                        if (target != null) {
                                            val problem = GameMemory.validate(settingsMemoryText) ?: JvmArgs.validate(settingsJvmArgsText)
                                            if (problem != null) {
                                                settingsError = problem
                                            } else {
                                                profiles = profiles.map {
                                                    if (it.name == target.name) {
                                                        it.copy(
                                                            memoryMb = GameMemory.parse(settingsMemoryText),
                                                            jvmArgs = settingsJvmArgsText.trim()
                                                        )
                                                    } else it
                                                }
                                                ProfileStore.save(profiles)
                                                settingsTarget = null
                                            }
                                        }
                                    }) { Text(tr("Сохранить")) }
                                },
                                dismissButton = { TextButton(onClick = { settingsTarget = null }) { Text(tr("Отмена")) } }
                            )
                        }

                        if (deleteTarget != null) {
                            AlertDialog(
                                onDismissRequest = { deleteTarget = null },
                                title = { Text(tr("Удалить экземпляр?")) },
                                text = { Text(tr("{0} — {1}", deleteTarget?.name ?: "", tr("Экземпляр будет удалён из списка."))) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val target = deleteTarget
                                        if (target != null) {
                                            profiles = profiles.filterNot { it.name == target.name }
                                            ProfileStore.save(profiles)
                                            if (selectedName == target.name) selectedName = profiles.firstOrNull()?.name.orEmpty()
                                            if (modTargetInstance == target.name) modTargetInstance = profiles.firstOrNull()?.name.orEmpty()
                                        }
                                        deleteTarget = null
                                    }) { Text(tr("Удалить")) }
                                },
                                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(tr("Отмена")) } }
                            )
                        }

                        if (showCreateDialog) {
                            AlertDialog(
                                onDismissRequest = {
                                    showCreateDialog = false
                                },
                                title = {
                                    Text(tr("Создать экземпляр"))
                                },
                                text = {
                                    val visibleVersions = versions.filter { version ->
                                        version.type == "release" ||
                                            (showSnapshots && version.type == "snapshot") ||
                                            (showAlphaBeta && version.type in setOf("old_alpha", "old_beta"))
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        ExposedDropdownMenuBox(
                                            expanded = versionMenuExpanded,
                                            onExpandedChange = { versionMenuExpanded = it }
                                        ) {
                                            OutlinedTextField(
                                                value = newVersion,
                                                onValueChange = {},
                                                readOnly = true,
                                                label = { Text(tr("Версия Minecraft")) },
                                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = versionMenuExpanded) },
                                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                                singleLine = true
                                            )
                                            ExposedDropdownMenu(
                                                expanded = versionMenuExpanded,
                                                onDismissRequest = { versionMenuExpanded = false }
                                            ) {
                                                visibleVersions.forEach { version ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            Column {
                                                                Text(version.id)
                                                                Text(
                                                                    when (version.type) {
                                                                        "snapshot" -> tr("Снапшот")
                                                                        "old_alpha" -> tr("Альфа")
                                                                        "old_beta" -> tr("Бета")
                                                                        else -> tr("Релиз")
                                                                    },
                                                                    fontSize = 11.sp,
                                                                    color = TextMuted
                                                                )
                                                            }
                                                        },
                                                        onClick = {
                                                            newVersion = version.id
                                                            newName = "Minecraft ${version.id}"
                                                            versionMenuExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        Text(tr("Загрузчик модов"), color = TextMain, fontWeight = FontWeight.SemiBold)
                                        val loaderOptions = listOf(
                                            Triple("Vanilla", "minecraft.net", Color(0xFF8C98A8)),
                                            Triple("Fabric", "fabricmc.net", Color(0xFFE8B84A)),
                                            Triple("Forge", "files.minecraftforge.net", Color(0xFF8B70E8)),
                                            Triple("Quilt", "quiltmc.org", Color(0xFFE58BB8)),
                                            Triple("NeoForge", "neoforged.net", Color(0xFF72B7E5))
                                        )
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            loaderOptions.chunked(2).forEach { rowOptions ->
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    rowOptions.forEach { option ->
                                                        val selected = newLoader == option.first
                                                        Surface(
                                                            modifier = Modifier.weight(1f).clickable {
                                                                newLoader = option.first
                                                                if (option.first != "Vanilla") installOptiFine = false
                                                            },
                                                            shape = RoundedCornerShape(12.dp),
                                                            color = if (selected) Accent.copy(alpha = 0.18f) else SurfaceB,
                                                            border = androidx.compose.foundation.BorderStroke(
                                                                if (selected) 1.5.dp else 1.dp,
                                                                if (selected) Accent else SurfaceB
                                                            )
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                LoaderLogo(option.second, option.first, option.third, 30.dp)
                                                                Text(option.first, color = TextMain, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                                            }
                                                        }
                                                    }
                                                    if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
                                                }
                                            }
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = SurfaceB,
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                installOptiFine = !installOptiFine
                                                if (installOptiFine) newLoader = "Vanilla"
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                LoaderLogo("optifine.net", "OptiFine", Color(0xFF4D8C58), 34.dp)
                                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                                    Text("OptiFine", color = TextMain, fontWeight = FontWeight.Medium)
                                                    Text(tr("оптимизация графики и шейдеры"), color = TextMuted, fontSize = 11.sp)
                                                }
                                                Checkbox(checked = installOptiFine, onCheckedChange = {
                                                    installOptiFine = it
                                                    if (it) newLoader = "Vanilla"
                                                })
                                            }
                                        }
                                        Text(
                                            tr("Выбор загрузчика будет установлен после создания экземпляра. OptiFine поддерживается только для совместимых версий Minecraft."),
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = showSnapshots, onCheckedChange = { showSnapshots = it })
                                            Text(tr("Показывать снапшоты"), color = TextMain, fontSize = 13.sp)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = showAlphaBeta, onCheckedChange = { showAlphaBeta = it })
                                            Text(tr("Показывать альфа- и бета-версии"), color = TextMain, fontSize = 13.sp)
                                        }
                                        OutlinedTextField(
                                            value = newName,
                                            onValueChange = { newName = it; createError = null },
                                            label = { Text(tr("Название экземпляра")) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            tr("Название автоматически подставляется по выбранной версии, его можно изменить."),
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                        createError?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            val version = newVersion.trim()
                                            val name = newName.trim().ifBlank { "Minecraft $version" }

                                            if (version.isEmpty()) {
                                                createError = tr("Выбери версию Minecraft")
                                            } else if (profiles.any { it.name.equals(name, ignoreCase = true) }) {
                                                createError = tr("Экземпляр с таким названием уже существует")
                                            } else {
                                                val savedLoader = if (installOptiFine) "OptiFine" else newLoader
                                                val created = Instance(name, version, savedLoader)
                                                profiles = profiles + created
                                                selectedName = created.name
                                                if (modTargetInstance.isBlank()) modTargetInstance = created.name
                                                ProfileStore.save(profiles)
                                                status = tr("Профиль сохранён")
                                                newName = ""
                                                newLoader = "Vanilla"
                                                installOptiFine = false
                                                createError = null
                                                showCreateDialog = false
                                            }
                                        }
                                    ) {
                                        Text(tr("Создать"))
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showCreateDialog = false
                                        }
                                    ) {
                                        Text(tr("Отмена"))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

private fun applyWindowsTitleBarTheme() {
    if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return
    try {
        // Find the native Compose/AWT window after it has been created.
        val target = java.awt.Window.getWindows().firstOrNull {
            it.isDisplayable && it.isShowing && it.name != null
        } ?: java.awt.Window.getWindows().firstOrNull { it.isDisplayable && it.isShowing }
            ?: return
        val hwnd = com.sun.jna.Pointer(com.sun.jna.Native.getComponentID(target))
        val api = com.sun.jna.Native.load("dwmapi", DwmWindowApi::class.java)
        val enabled = com.sun.jna.ptr.IntByReference(1)
        // 20 is DWMWA_USE_IMMERSIVE_DARK_MODE on current Windows 10/11;
        // 19 is the attribute index used by older Windows 10 builds.
        val currentResult = api.DwmSetWindowAttribute(hwnd, 20, enabled, 4)
        if (currentResult != 0) api.DwmSetWindowAttribute(hwnd, 19, enabled, 4)
    } catch (_: Throwable) {
        // Unsupported Windows versions simply keep their default system title bar.
    }
}

// JNA создаёт прокси только для доступного интерфейса, поэтому он не private.
internal interface DwmWindowApi : com.sun.jna.Library {
    fun DwmSetWindowAttribute(
        hwnd: com.sun.jna.Pointer,
        attribute: Int,
        value: com.sun.jna.ptr.IntByReference,
        valueSize: Int
    ): Int
}

/** Язык интерфейса как состояние Compose: tr() читает его при отрисовке, поэтому экран обновляется сразу после смены. */
internal class ComposeLanguageStore(initial: String) : LanguageStore {
    private var state by mutableStateOf(initial)

    override var language: String
        get() = state
        set(value) {
            state = value
        }
}

fun main() = androidx.compose.ui.window.application {
    // Язык нужно установить до первой отрисовки, чтобы окно сразу открылось на сохранённом языке.
    remember { I18n.store = ComposeLanguageStore(GameSettings.language) }
    // Прокси и системные сетевые настройки нужно применить до первого сетевого запроса.
    remember { Net.init() }
    val windowState = androidx.compose.ui.window.rememberWindowState(
        size = androidx.compose.ui.unit.DpSize(1200.dp, 800.dp)
    )
    androidx.compose.ui.window.Window(
        onCloseRequest = ::exitApplication,
        title = "Material Client",
        state = windowState
    ) {
        SideEffect {
            try {
                Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use {
                    window.iconImage = javax.imageio.ImageIO.read(it)
                }
            } catch (_: Exception) {
                // Иконка окна необязательна.
            }
            window.minimumSize = java.awt.Dimension(1000, 650)
        }
        LaunchedEffect(Unit) {
            // Let Compose/AWT create the native window before calling the Windows API.
            kotlinx.coroutines.delay(350)
            applyWindowsTitleBarTheme()
        }
        App()
    }
}
