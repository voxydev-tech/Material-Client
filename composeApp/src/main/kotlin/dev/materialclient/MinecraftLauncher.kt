package dev.materialclient

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

data class MinecraftLaunchResult(
    val success: Boolean,
    val message: String,
    val process: Process? = null,
    val logFile: File? = null
)

/** Общие пути лаунчера. */
object LauncherPaths {
    val root: File
        get() = File(System.getProperty("user.home"), ".material-client")

    /**
     * Безопасное имя папки экземпляра. Кириллица и другие буквы сохраняются, иначе разные
     * экземпляры с русскими названиями получили бы одну и ту же папку.
     */
    fun instanceDirName(name: String): String {
        var result = name.trim().replace(Regex("[^\\p{L}\\p{N}._-]"), "_").trimEnd('.', ' ')
        if (result.isEmpty() || result == "." || result == "..") result = "instance"
        if (Regex("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?").matches(result)) result = "_$result"
        return result
    }

    /** Папка игры конкретного экземпляра: в ней лежат mods, saves, options.txt и т.д. */
    fun instanceDir(name: String): File = File(root, "instances/${instanceDirName(name)}")
}

/** Правила Mojang (`rules`) для библиотек и аргументов запуска. */
object MinecraftRules {
    private val osName: String = System.getProperty("os.name", "").lowercase().let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") -> "osx"
            else -> "linux"
        }
    }

    private val osArch: String = System.getProperty("os.arch", "").lowercase().let {
        when (it) {
            "aarch64", "arm64" -> "arm64"
            "x86", "i386", "i486", "i586", "i686" -> "x86"
            else -> "x86_64"
        }
    }

    private fun ruleApplies(rule: JsonObject): Boolean {
        val os = rule.getAsJsonObject("os")
        if (os != null) {
            val name = os.get("name")?.takeUnless { it.isJsonNull }?.asString
            if (name != null && name != osName) return false

            val arch = os.get("arch")?.takeUnless { it.isJsonNull }?.asString
            if (arch != null && arch != osArch) return false

            val version = os.get("version")?.takeUnless { it.isJsonNull }?.asString
            if (version != null) {
                val matches = try {
                    Regex(version).containsMatchIn(System.getProperty("os.version", ""))
                } catch (_: Exception) {
                    false
                }
                if (!matches) return false
            }
        }

        // Все необязательные функции лаунчера (демо-режим, свой размер окна, quick play) выключены,
        // поэтому правило, которое требует включённую функцию, не применяется.
        val features = rule.getAsJsonObject("features")
        if (features != null) {
            for ((_, value) in features.entrySet()) {
                if (value.asBoolean) return false
            }
        }
        return true
    }

    /** Побеждает последнее подходящее правило; если правил нет, элемент разрешён. */
    fun allowed(rules: JsonArray?): Boolean {
        if (rules == null || rules.size() == 0) return true
        var allowed = false
        for (element in rules) {
            val rule = element.asJsonObject
            if (ruleApplies(rule)) allowed = rule.get("action")?.asString == "allow"
        }
        return allowed
    }

    fun libraryAllowed(library: JsonObject): Boolean = allowed(library.getAsJsonArray("rules"))
}

/** Версия Minecraft с уже применённым `inheritsFrom` (нужно для Forge, Fabric и т.п.). */
class ResolvedVersion(
    val id: String,
    val rootId: String,
    val mainClass: String,
    val libraries: List<JsonObject>,
    val jvmArguments: List<JsonElement>,
    val gameArguments: List<JsonElement>,
    val legacyArguments: String?,
    val assetIndexId: String,
    val javaMajor: Int?,
    val versionType: String,
    val loggingClient: JsonObject?
)

object VersionResolver {
    private val idRegex = Regex("[A-Za-z0-9._-]+")

    fun versionDir(id: String): File = File(LauncherPaths.root, "versions/$id")

    private fun read(id: String): JsonObject {
        require(id.matches(idRegex)) { tr("Некорректный идентификатор версии") }
        val file = File(versionDir(id), "$id.json")
        require(file.isFile) { tr("Не найден JSON версии: {0}", file.absolutePath) }
        return JsonParser.parseString(file.readText()).asJsonObject
    }

    private fun text(json: JsonObject, key: String): String? =
        json.get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun arguments(json: JsonObject, key: String): List<JsonElement> =
        json.getAsJsonObject("arguments")?.getAsJsonArray(key)?.toList() ?: emptyList()

    /** group:artifact[:classifier] без версии: по этому ключу библиотека потомка заменяет библиотеку родителя. */
    private fun libraryKey(library: JsonObject): String {
        val name = text(library, "name") ?: return library.toString()
        val coords = name.substringBefore('@').split(':')
        if (coords.size < 2) return name
        val classifier = coords.getOrNull(3)
        return coords[0] + ":" + coords[1] + (if (classifier != null) ":$classifier" else "")
    }

    fun resolve(id: String): ResolvedVersion = resolve(id, 0)

    private fun resolve(id: String, depth: Int): ResolvedVersion {
        require(depth < 8) { tr("Слишком длинная цепочка inheritsFrom у версии {0}", id) }
        val json = read(id)
        val parentId = text(json, "inheritsFrom")
        val parent = parentId?.let { resolve(it, depth + 1) }

        val ownLibraries = json.getAsJsonArray("libraries")?.map { it.asJsonObject } ?: emptyList()
        val ownKeys = ownLibraries.map { libraryKey(it) }.toSet()
        val libraries = ownLibraries + (parent?.libraries ?: emptyList()).filter { libraryKey(it) !in ownKeys }

        val mainClass = text(json, "mainClass") ?: parent?.mainClass
            ?: error(tr("В JSON версии {0} отсутствует mainClass", id))

        val assetIndexId = json.getAsJsonObject("assetIndex")?.let { text(it, "id") }
            ?: text(json, "assets")
            ?: parent?.assetIndexId
            ?: id

        val javaMajor = json.getAsJsonObject("javaVersion")?.get("majorVersion")
            ?.takeUnless { it.isJsonNull }?.asInt
            ?: parent?.javaMajor

        return ResolvedVersion(
            id = id,
            rootId = parent?.rootId ?: id,
            mainClass = mainClass,
            libraries = libraries,
            jvmArguments = (parent?.jvmArguments ?: emptyList()) + arguments(json, "jvm"),
            gameArguments = (parent?.gameArguments ?: emptyList()) + arguments(json, "game"),
            legacyArguments = text(json, "minecraftArguments") ?: parent?.legacyArguments,
            assetIndexId = assetIndexId,
            javaMajor = javaMajor,
            versionType = text(json, "type") ?: parent?.versionType ?: "release",
            loggingClient = json.getAsJsonObject("logging")?.getAsJsonObject("client") ?: parent?.loggingClient
        )
    }

    /** Относительный путь библиотеки внутри папки libraries или null, если в classpath она не нужна. */
    fun libraryRelativePath(library: JsonObject): String? {
        val downloads = library.getAsJsonObject("downloads")
        if (downloads != null) {
            return downloads.getAsJsonObject("artifact")?.get("path")?.takeUnless { it.isJsonNull }?.asString
        }
        // Старые natives-библиотеки без downloads в classpath не попадают.
        if (library.has("natives")) return null

        // Библиотека задана только Maven-координатами (так делают Forge и Fabric).
        val name = text(library, "name") ?: return null
        val extension = name.substringAfter('@', "jar")
        val coords = name.substringBefore('@').split(':')
        if (coords.size < 3) return null
        val group = coords[0]
        val artifact = coords[1]
        val version = coords[2]
        val classifier = coords.getOrNull(3)
        val fileName = "$artifact-$version" + (if (classifier != null) "-$classifier" else "") + ".$extension"
        return group.replace('.', '/') + "/$artifact/$version/$fileName"
    }

    fun clientJar(resolved: ResolvedVersion): File =
        File(versionDir(resolved.rootId), "${resolved.rootId}.jar")

    fun classpathLibraries(resolved: ResolvedVersion): List<File> {
        val librariesDir = File(LauncherPaths.root, "libraries")
        return resolved.libraries
            .filter { MinecraftRules.libraryAllowed(it) }
            .mapNotNull { libraryRelativePath(it) }
            .map { File(librariesDir, it) }
    }

    /** Версия Java, которую требует Minecraft (поле javaVersion в JSON), или null для старых версий. */
    fun requiredJava(id: String): Int? = try {
        resolve(id).javaMajor
    } catch (_: Exception) {
        null
    }
}

object JavaInfo {
    /** Мажорная версия Java по файлу `release` в корне JDK/JRE; null, если определить не удалось. */
    fun detectMajor(javaExe: File): Int? {
        val home = javaExe.absoluteFile.parentFile?.parentFile ?: return null
        val release = File(home, "release")
        if (!release.isFile) return null
        val line = try {
            release.readLines().firstOrNull { it.startsWith("JAVA_VERSION=") }
        } catch (_: Exception) {
            null
        } ?: return null
        val parts = line.substringAfter('=').trim().trim('"').split('.', '_', '-', '+')
        val first = parts.firstOrNull()?.toIntOrNull() ?: return null
        return if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
    }

    internal fun executableIn(home: File): File? =
        listOf("bin/javaw.exe", "bin/java.exe", "bin/java", "jre.bundle/Contents/Home/bin/java")
            .map { File(home, it) }.firstOrNull { it.isFile }

    /**
     * Java, установленные в стандартные папки Windows, из JAVA_HOME, из официального лаунчера Minecraft
     * и скачанные самим лаунчером.
     */
    fun findInstalled(): List<File> {
        val result = linkedMapOf<String, File>()
        val roots = listOf(
            "C:/Program Files/Eclipse Adoptium", "C:/Program Files/Java", "C:/Program Files/Microsoft",
            "C:/Program Files/Zulu", "C:/Program Files/Amazon Corretto", "C:/Program Files/BellSoft",
            "C:/Program Files/Semeru", "C:/Program Files/Eclipse Foundation", "C:/Program Files (x86)/Java"
        )
        for (rootPath in roots) {
            File(rootPath).listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                executableIn(dir)?.let { result[it.absolutePath] = it }
            }
        }
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { home ->
            executableIn(File(home))?.let { result[it.absolutePath] = it }
        }
        System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let { home ->
            executableIn(File(home))?.let { result[it.absolutePath] = it }
        }
        // Официальный лаунчер Mojang кладёт Java в .minecraft/runtime/<компонент>/<платформа>/<компонент>.
        System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { appData ->
            File(appData, ".minecraft/runtime").listFiles()?.filter { it.isDirectory }?.forEach { component ->
                component.listFiles()?.filter { it.isDirectory }?.forEach { platform ->
                    platform.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                        executableIn(dir)?.let { result[it.absolutePath] = it }
                    }
                }
            }
        }
        JavaRuntimes.installedExecutables().forEach { result[it.absolutePath] = it }
        return result.values.toList()
    }
}

object MinecraftLauncher {
    private val placeholder = Regex("\\$\\{([^}]+)}")

    private fun substitute(value: String, variables: Map<String, String>): String =
        placeholder.replace(value) { match -> variables[match.groupValues[1]] ?: match.value }

    /** Разворачивает элементы `arguments.jvm` / `arguments.game` с учётом правил и подстановок. */
    private fun expand(elements: List<JsonElement>, variables: Map<String, String>): List<String> {
        val result = mutableListOf<String>()
        for (element in elements) {
            if (element.isJsonPrimitive) {
                result += substitute(element.asString, variables)
                continue
            }
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            if (!MinecraftRules.allowed(obj.getAsJsonArray("rules"))) continue
            val value = obj.get("value") ?: continue
            if (value.isJsonArray) {
                value.asJsonArray.forEach { result += substitute(it.asString, variables) }
            } else if (value.isJsonPrimitive) {
                result += substitute(value.asString, variables)
            }
        }
        return result
    }

    /** Загрузчики, для которых лаунчер умеет и ставить, и запускать профиль. */
    val supportedLoaders = listOf("Vanilla", "Fabric", "Forge", "Quilt", "NeoForge", "OptiFine")

    private fun inheritsFromOf(versionDir: File): String? = try {
        JsonParser.parseString(File(versionDir, "${versionDir.name}.json").readText())
            .asJsonObject.get("inheritsFrom")?.takeUnless { it.isJsonNull }?.asString
    } catch (_: Exception) {
        null
    }

    /** Имя папки профиля загрузчика для версии Minecraft. */
    private fun profileNameMatches(loader: String, baseVersion: String, dirName: String): Boolean = when (loader.lowercase()) {
        "forge" -> dirName.startsWith("$baseVersion-forge", ignoreCase = true)
        "neoforge" -> dirName.startsWith("neoforge-")
        "fabric" -> dirName.startsWith("fabric-loader-") && dirName.endsWith("-$baseVersion")
        "quilt" -> dirName.startsWith("quilt-loader-") && dirName.endsWith("-$baseVersion")
        "optifine" -> dirName.startsWith("$baseVersion-OptiFine_")
        else -> false
    }

    /**
     * Какую версию реально запускать для экземпляра. Для Vanilla это сама версия Minecraft, для остальных —
     * установленный профиль загрузчика (например `1.21.1-forge-52.0.0` или `fabric-loader-0.16.5-1.21.1`).
     * null означает, что загрузчик ещё не установлен.
     */
    fun resolveLaunchVersionId(baseVersion: String, loader: String): String? {
        // Vanilla и неизвестные загрузчики запускаются как обычный Minecraft.
        if (supportedLoaders.none { it.equals(loader, ignoreCase = true) } || loader.equals("Vanilla", ignoreCase = true)) {
            return baseVersion
        }
        val versionsDir = File(LauncherPaths.root, "versions")
        return versionsDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory &&
                    File(dir, "${dir.name}.json").isFile &&
                    profileNameMatches(loader, baseVersion, dir.name) &&
                    (loader.equals("Forge", ignoreCase = true) || inheritsFromOf(dir) == baseVersion)
            }
            ?.maxByOrNull { File(it, "${it.name}.json").lastModified() }
            ?.name
    }

    /** Проверяет, что все файлы версии на месте. Возвращает текст проблемы или null. */
    fun verify(versionId: String): String? {
        val resolved = try {
            VersionResolver.resolve(versionId)
        } catch (e: Exception) {
            return tr("Не удалось прочитать данные версии {0}: {1}. Сначала установи Minecraft.", versionId, e.message)
        }
        val jar = VersionResolver.clientJar(resolved)
        if (!jar.isFile || jar.length() == 0L) {
            return tr("Не найден клиентский JAR ({0}). Сначала установи Minecraft.", jar.name)
        }
        val missing = VersionResolver.classpathLibraries(resolved).firstOrNull { !it.isFile }
        if (missing != null) {
            return tr("Не найдена библиотека: {0}. Нажми «Установить / проверить файлы».", missing.absolutePath)
        }
        return null
    }

    /** Раньше все экземпляры одной версии делили папку instances/<версия>; отдаём её первому запущенному. */
    private fun migrateLegacyGameDir(gameDir: File, baseVersion: String) {
        if (gameDir.exists()) return
        val legacy = File(LauncherPaths.root, "instances/$baseVersion")
        if (legacy.isDirectory) {
            try {
                legacy.renameTo(gameDir)
            } catch (_: Exception) {
                // Не получилось: игра просто создаст чистую папку.
            }
        }
    }

    suspend fun launchProfile(
        baseVersion: String,
        loader: String,
        profileName: String,
        username: String,
        javaPath: String,
        memoryMb: Int = 0,
        extraJvmArgs: String = "",
        onStatus: (String) -> Unit = {}
    ): MinecraftLaunchResult {
        // Флаг отмены мог остаться от прерванной установки: он не должен ломать загрузку Java.
        MinecraftInstaller.resetCancel()
        val versionId = resolveLaunchVersionId(baseVersion, loader)
            ?: return MinecraftLaunchResult(
                false,
                tr("Ошибка запуска: {0} для Minecraft {1} не установлен. Нажми «Установить / проверить файлы».", loader, baseVersion)
            )
        val problem = verify(versionId)
        if (problem != null) return MinecraftLaunchResult(false, tr("Ошибка запуска: {0}", problem))

        val result = launch(versionId, username, javaPath, profileName, loader, memoryMb, extraJvmArgs, onStatus)
        val supported = supportedLoaders.any { it.equals(loader, ignoreCase = true) }
        return if (result.success && !supported) {
            result.copy(message = tr("{0}. Загрузчик {1} пока не поддерживается, запущен Vanilla", result.message, loader))
        } else {
            result
        }
    }

    suspend fun launch(
        version: String,
        username: String,
        javaPath: String,
        instanceName: String = version,
        loader: String = "Vanilla",
        memoryMb: Int = 0,
        extraJvmArgs: String = "",
        onStatus: (String) -> Unit = {}
    ): MinecraftLaunchResult {
        return try {
            require(username.isNotBlank()) { tr("Укажи имя игрока") }

            val root = LauncherPaths.root
            val resolved = VersionResolver.resolve(version)

            val jarFile = VersionResolver.clientJar(resolved)
            require(jarFile.isFile) { tr("Не найден клиентский JAR: {0}", jarFile.absolutePath) }

            // Пустой путь — Java выбирается автоматически (при необходимости скачивается).
            val selection = JavaProvider.ensure(
                javaPath, resolved.javaMajor, JavaProvider.maxMajorFor(loader, resolved.javaMajor), onStatus = onStatus
            )
            val java = selection.java
            val javaNote = selection.note

            val libraryFiles = VersionResolver.classpathLibraries(resolved)
            val missing = libraryFiles.firstOrNull { !it.isFile }
            if (missing != null) error(tr("Не найдена библиотека: {0}", missing.absolutePath))

            val classpathFiles = LinkedHashSet<File>()
            classpathFiles.addAll(libraryFiles)
            classpathFiles.add(jarFile)
            val classpath = classpathFiles.joinToString(File.pathSeparator) { it.absolutePath }

            val nativesDir = File(VersionResolver.versionDir(resolved.rootId), "natives")
            nativesDir.mkdirs()

            val gameDir = LauncherPaths.instanceDir(instanceName)
            migrateLegacyGameDir(gameDir, resolved.rootId)
            gameDir.mkdirs()

            val assetsDir = File(root, "assets")
            val librariesDir = File(root, "libraries")
            val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))
                .toString().replace("-", "")

            val variables = mapOf(
                "auth_player_name" to username,
                "version_name" to version,
                "game_directory" to gameDir.absolutePath,
                "assets_root" to assetsDir.absolutePath,
                "game_assets" to assetsDir.absolutePath,
                "assets_index_name" to resolved.assetIndexId,
                "auth_uuid" to uuid,
                "auth_access_token" to "0",
                "auth_session" to "token:0:$uuid",
                "clientid" to "0",
                "auth_xuid" to "0",
                "user_type" to "legacy",
                "version_type" to resolved.versionType,
                "user_properties" to "{}",
                "natives_directory" to nativesDir.absolutePath,
                "launcher_name" to "MaterialClient",
                "launcher_version" to "0.1.0",
                "classpath" to classpath,
                "classpath_separator" to File.pathSeparator,
                "library_directory" to librariesDir.absolutePath
            )

            val jvmArgs = mutableListOf<String>()
            val gameArgs = mutableListOf<String>()
            val legacyArguments = resolved.legacyArguments

            if (legacyArguments != null && resolved.gameArguments.isEmpty()) {
                // Старый формат (до 1.13): JVM-аргументы задаёт сам лаунчер, строку игры делим до подстановки,
                // чтобы пути с пробелами не разваливались на части.
                jvmArgs += listOf("-Djava.library.path=${nativesDir.absolutePath}", "-cp", classpath)
                legacyArguments.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    .forEach { gameArgs += substitute(it, variables) }
            } else {
                jvmArgs += expand(resolved.jvmArguments, variables)
                gameArgs += expand(resolved.gameArguments, variables)
                if (jvmArgs.none { it == "-cp" || it == "-classpath" }) {
                    jvmArgs += listOf("-cp", classpath)
                }
                if (jvmArgs.none { it.startsWith("-Djava.library.path=") }) {
                    jvmArgs += "-Djava.library.path=${nativesDir.absolutePath}"
                }
            }

            // Конфигурация log4j от Mojang (защита от Log4Shell в версиях 1.7-1.18.0), если она скачана.
            resolved.loggingClient?.let { logging ->
                val argument = logging.get("argument")?.takeUnless { it.isJsonNull }?.asString
                val logId = logging.getAsJsonObject("file")?.get("id")?.takeUnless { it.isJsonNull }?.asString
                if (argument != null && logId != null) {
                    val logConfig = File(assetsDir, "log_configs/${File(logId).name}")
                    if (logConfig.isFile) jvmArgs.add(0, argument.replace("\${path}", logConfig.absolutePath))
                }
            }

            // Память идёт первой, пользовательские аргументы — после аргументов версии: так последнее слово
            // (например собственный -Xmx) остаётся за пользователем.
            val memory = GameMemory.effectiveMb(memoryMb, loader)
            val custom = (JvmArgs.parse(GameSettings.jvmArgs) + JvmArgs.parse(extraJvmArgs)).filter { it.startsWith("-") }
            val command = listOf(java.absolutePath) + listOf("-Xmx${memory}M") + jvmArgs + custom +
                listOf(resolved.mainClass) + gameArgs

            // Вывод игры пишем в файл: если его никто не читает, буфер канала переполняется и игра зависает.
            val logFile = File(gameDir, "launcher-output.log")
            val process = ProcessBuilder(command)
                .directory(gameDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
                .start()

            MinecraftLaunchResult(
                success = true,
                message = tr("Minecraft запущен") + (if (javaNote != null) " ($javaNote)" else ""),
                process = process,
                logFile = logFile
            )
        } catch (e: Exception) {
            MinecraftLaunchResult(
                success = false,
                message = tr("Ошибка запуска: {0}", e.message ?: e.javaClass.simpleName)
            )
        }
    }
}
