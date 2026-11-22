package dev.materialclient

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Загрузчики, для которых существуют моды. */
internal object ModLoaders {
    val all = listOf("Fabric", "Forge", "NeoForge", "Quilt")

    /** Каноническое имя загрузчика мода или null (Vanilla, OptiFine, неизвестное значение). */
    fun canonical(name: String?): String? = all.firstOrNull { it.equals(name?.trim(), ignoreCase = true) }

    /** Какие загрузчики спрашивать у Modrinth, по порядку: Quilt умеет запускать моды Fabric. */
    fun compatible(loader: String?): List<String> = when (val name = canonical(loader)) {
        null -> emptyList()
        "Quilt" -> listOf("quilt", "fabric")
        else -> listOf(name.lowercase())
    }
}

/** Итог установки мода: сам файл, поставленные зависимости и те, что поставить не удалось. */
internal class ModInstallReport(
    val fileName: String,
    val dir: File,
    val dependencies: List<String>,
    val failed: List<String>
) {
    val message: String
        get() {
            if (failed.isNotEmpty()) {
                return tr("Ошибка: мод {0} установлен, но не удалось установить зависимости: {1}. Поставь их вручную.", fileName, failed.joinToString(", "))
            }
            val base = tr("Установлен {0} в {1}", fileName, dir.absolutePath)
            return if (dependencies.isEmpty()) base else base + " " + tr("Зависимости установлены: {0}.", dependencies.joinToString(", "))
        }
}

object ModrinthApi {
    /** Адрес API. Меняется только в проверках. */
    @Volatile
    internal var apiBase = "https://api.modrinth.com/v2"

    private const val MAX_DEPTH = 6

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun str(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun getJson(path: String): JsonElement = JsonParser.parseString(Net.getText(apiBase + path))

    // ---------------------------------------------------------------- поиск

    private val safeFacetValue = Regex("[A-Za-z0-9._+-]+")

    /**
     * Адрес поиска. Если известны версия игры и загрузчик, Modrinth сам оставляет только подходящие моды
     * (для Quilt показываются и моды Fabric).
     */
    internal fun searchUrl(query: String, mcVersion: String? = null, loader: String? = null): String {
        val facets = mutableListOf(listOf("project_type:mod"))
        if (mcVersion != null && safeFacetValue.matches(mcVersion)) facets += listOf("versions:$mcVersion")
        val loaders = ModLoaders.compatible(loader)
        if (loaders.isNotEmpty()) facets += loaders.map { "categories:$it" }
        val facetsJson = facets.joinToString(",", "[", "]") { group -> group.joinToString(",", "[", "]") { "\"$it\"" } }
        val trimmed = query.trim()
        val queryPart = if (trimmed.isEmpty()) "" else "&query=${enc(trimmed)}"
        // Пустой запрос открывает самые скачиваемые моды.
        val sortPart = if (trimmed.isEmpty()) "&index=downloads" else "&index=relevance"
        return "$apiBase/search?facets=${enc(facetsJson)}$queryPart$sortPart&limit=24"
    }

    suspend fun search(query: String, mcVersion: String? = null, loader: String? = null): List<ModrinthMod> =
        withContext(Dispatchers.IO) {
            val json = JsonParser.parseString(Net.getText(searchUrl(query, mcVersion, loader))).asJsonObject
            json.getAsJsonArray("hits").map { element ->
                val item = element.asJsonObject
                ModrinthMod(
                    id = item.get("project_id").asString,
                    title = item.get("title").asString,
                    description = item.get("description").asString,
                    author = item.get("author").asString,
                    downloads = item.get("downloads").asLong,
                    iconUrl = str(item, "icon_url")
                )
            }
        }

    // ---------------------------------------------------------------- версии и зависимости

    /** Из списка версий (новые первыми) берёт последний релиз, а если релизов нет — самую новую. */
    internal fun pickVersion(versions: JsonArray): JsonObject? {
        val list = versions.map { it.asJsonObject }
        return list.firstOrNull { str(it, "version_type") == "release" } ?: list.firstOrNull()
    }

    private fun latest(projectId: String, mcVersion: String, loaders: List<String>): JsonObject? {
        for (loader in loaders) {
            val path = "/project/$projectId/version?game_versions=${enc("[\"$mcVersion\"]")}&loaders=${enc("[\"$loader\"]")}"
            val found = pickVersion(getJson(path).asJsonArray)
            if (found != null) return found
        }
        return null
    }

    private fun versionById(versionId: String): JsonObject {
        require(versionId.matches(Regex("[A-Za-z0-9_-]+"))) { tr("Некорректный ID проекта Modrinth") }
        return getJson("/version/$versionId").asJsonObject
    }

    private fun projectTitle(projectId: String): String = try {
        str(getJson("/project/$projectId").asJsonObject, "title") ?: projectId
    } catch (_: Exception) {
        projectId
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Что лаунчер уже ставил в экземпляр: ID проекта Modrinth → имя файла. Нужно, чтобы не ставить зависимость дважды. */
    private class Index(private val file: File) {
        private val data: JsonObject = try {
            if (file.isFile) JsonParser.parseString(file.readText()).asJsonObject else JsonObject()
        } catch (_: Exception) {
            JsonObject()
        }

        fun fileOf(projectId: String): String? = data.get(projectId)?.takeUnless { it.isJsonNull }?.asString

        fun put(projectId: String, fileName: String) {
            data.addProperty(projectId, fileName)
            file.parentFile?.mkdirs()
            file.writeText(data.toString())
        }
    }

    private class Context(
        val mcVersion: String,
        val loaders: List<String>,
        val modsDir: File,
        val index: Index,
        val onStatus: (String) -> Unit
    ) {
        val visited = mutableSetOf<String>()
        val installedDeps = mutableListOf<String>()
        val failedDeps = mutableListOf<String>()
    }

    private fun primaryFile(version: JsonObject): JsonObject {
        val files = version.getAsJsonArray("files")?.map { it.asJsonObject }.orEmpty()
        return files.firstOrNull { it.get("primary")?.asBoolean == true }
            ?: files.firstOrNull { str(it, "filename")?.endsWith(".jar", ignoreCase = true) == true }
            ?: files.firstOrNull()
            ?: error(tr("У мода нет доступных файлов"))
    }

    /** Скачивает файл версии в папку mods и возвращает его имя. */
    private fun download(ctx: Context, file: JsonObject, title: String): String {
        val fileName = str(file, "filename") ?: error(tr("У мода нет доступных файлов"))
        require(fileName.endsWith(".jar", ignoreCase = true)) { tr("Файл мода не является JAR: {0}", fileName) }
        val target = File(ctx.modsDir, fileName).canonicalFile
        check(target.parentFile == ctx.modsDir.canonicalFile) { tr("Некорректное имя файла мода") }
        ctx.onStatus(tr("Скачиваем {0}…", title))
        val temp = File(ctx.modsDir, "$fileName.part")
        val connection = Net.open(str(file, "url") ?: error(tr("У мода нет доступных файлов")), 10_000, 60_000)
        try {
            val code = connection.responseCode
            check(code in 200..299) { tr("Не удалось скачать мод: HTTP {0}", code) }
            connection.inputStream.use { input -> temp.outputStream().buffered().use { output -> input.copyTo(output) } }
            check(temp.length() > 0) { tr("Скачан пустой файл") }
            val expected = file.getAsJsonObject("hashes")?.let { str(it, "sha1") }
            check(expected == null || sha1(temp).equals(expected, ignoreCase = true)) { tr("Проверка SHA-1 не пройдена для {0}", fileName) }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            connection.disconnect()
            temp.delete()
        }
        return fileName
    }

    private fun alreadyInstalled(ctx: Context, projectId: String, fileName: String?): Boolean {
        val recorded = ctx.index.fileOf(projectId)
        fun present(name: String) = File(ctx.modsDir, name).isFile || File(ctx.modsDir, "$name.disabled").isFile
        return (recorded != null && present(recorded)) || (fileName != null && present(fileName))
    }

    /** Ставит версию мода и, рекурсивно, его обязательные зависимости. Возвращает имя файла или null, если мод уже стоит. */
    private fun installOne(ctx: Context, projectId: String, pinnedVersion: String?, isMain: Boolean, depth: Int): String? {
        ctx.visited += projectId
        val version = (if (pinnedVersion != null) versionById(pinnedVersion) else latest(projectId, ctx.mcVersion, ctx.loaders))
            ?: error(tr("Нет версии мода для Minecraft {0} и загрузчика {1}", ctx.mcVersion, ctx.loaders.firstOrNull() ?: ""))
        val file = primaryFile(version)
        val fileName = str(file, "filename")
        var installed: String? = null
        if (isMain || !alreadyInstalled(ctx, projectId, fileName)) {
            val previous = ctx.index.fileOf(projectId)
            val title = if (isMain) (str(version, "name") ?: projectId) else projectTitle(projectId)
            installed = download(ctx, file, title)
            // Обновление: старый файл, который ставил сам лаунчер, заменяется новым.
            if (previous != null && previous != installed) File(ctx.modsDir, previous).takeIf { it.isFile }?.delete()
            ctx.index.put(projectId, installed)
            if (!isMain) ctx.installedDeps += title
        }
        if (installed != null && depth < MAX_DEPTH) {
            val dependencies = version.getAsJsonArray("dependencies")?.map { it.asJsonObject }.orEmpty()
            for (dependency in dependencies) {
                if (str(dependency, "dependency_type") != "required") continue
                var depId = str(dependency, "project_id")
                val depVersion = str(dependency, "version_id")
                try {
                    if (depId == null && depVersion != null) depId = str(versionById(depVersion), "project_id")
                    if (depId == null || depId in ctx.visited) continue
                    installOne(ctx, depId, depVersion, isMain = false, depth = depth + 1)
                } catch (e: Exception) {
                    ctx.failedDeps += "${depId?.let { projectTitle(it) } ?: depVersion ?: "?"} (${e.message ?: e.javaClass.simpleName})"
                }
            }
        }
        return installed
    }

    /**
     * Ставит последнюю подходящую версию мода (релиз, если есть) вместе со всеми обязательными зависимостями,
     * например с Fabric API. Уже установленные зависимости пропускаются.
     */
    internal suspend fun install(projectId: String, profile: Instance, onStatus: (String) -> Unit = {}): ModInstallReport =
        withContext(Dispatchers.IO) {
            require(projectId.matches(Regex("[A-Za-z0-9_-]+"))) { tr("Некорректный ID проекта Modrinth") }
            val loaders = ModLoaders.compatible(profile.loader)
            // Раньше для Vanilla/OptiFine скачивались моды Fabric, которые в таком экземпляре никогда не загрузятся.
            require(loaders.isNotEmpty()) {
                tr("Экземпляр {0} использует {1}: моды работают только с Fabric, Forge, NeoForge или Quilt. Создай экземпляр с загрузчиком модов.", profile.name, profile.loader)
            }
            val instanceDir = LauncherPaths.instanceDir(profile.name)
            val modsDir = File(instanceDir, "mods").apply { mkdirs() }
            val ctx = Context(profile.version, loaders, modsDir, Index(File(instanceDir, "modrinth-mods.json")), onStatus)
            val fileName = installOne(ctx, projectId, null, isMain = true, depth = 0) ?: error(tr("У мода нет доступных файлов"))
            ModInstallReport(fileName, modsDir, ctx.installedDeps.toList(), ctx.failedDeps.toList())
        }

    suspend fun installLatestCompatible(projectId: String, profile: Instance, onStatus: (String) -> Unit = {}): String =
        install(projectId, profile, onStatus).message
}
