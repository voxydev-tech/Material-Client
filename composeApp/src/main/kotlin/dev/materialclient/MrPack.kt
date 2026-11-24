package dev.materialclient

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Один файл сборки: куда положить, как проверить и откуда качать. */
internal class MrPackFile(
    val path: String,
    val sha1: String?,
    val sha512: String?,
    val downloads: List<String>,
    val size: Long,
    /** false, если файл нужен только серверу (env.client = unsupported). */
    val clientSide: Boolean
)

internal class MrPackIndex(
    val name: String,
    val summary: String,
    val minecraft: String,
    /** Fabric, Quilt, Forge, NeoForge или Vanilla. */
    val loader: String,
    val files: List<MrPackFile>
)

internal class MrPackReport(val downloaded: Int, val skipped: Int, val overrides: Int, val failed: List<String>) {
    val message: String
        get() {
            val base = tr("Сборка импортирована: скачано файлов {0}, из архива {1}.", downloaded + skipped, overrides)
            return if (failed.isEmpty()) base
            else tr("Ошибка: часть файлов сборки не скачана ({0}): {1}.", failed.size, failed.take(4).joinToString("; ")) + " " + base
        }
}

/**
 * Импорт сборок Modrinth (.mrpack): архив с modrinth.index.json, списком файлов для загрузки
 * и папками overrides / client-overrides, которые копируются в папку экземпляра.
 */
internal object MrPack {
    /** Только для проверок: дополнительные разрешённые адреса вида «127.0.0.1:18790». */
    @Volatile
    internal var extraHosts: Set<String> = emptySet()

    /** Сайты, с которых по спецификации формата разрешено скачивать файлы сборки. */
    private val allowedHosts = setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com")

    private const val MAX_INDEX_BYTES = 8L * 1024 * 1024
    private const val MAX_UNPACKED_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 50_000

    private fun str(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeUnless { it.isJsonNull }?.asString

    /** Читает описание сборки из архива. */
    fun read(pack: File): MrPackIndex {
        try {
            ZipFile(pack).use { zip ->
                val entry = zip.getEntry("modrinth.index.json")
                    ?: error(tr("Это не сборка Modrinth (.mrpack): в архиве нет modrinth.index.json."))
                check(entry.size in 0..MAX_INDEX_BYTES) { tr("Файл сборки повреждён или слишком большой.") }
                return parse(zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() })
            }
        } catch (e: ZipException) {
            error(tr("Не удалось открыть файл сборки: {0}", e.message ?: tr("ошибка")))
        }
    }

    internal fun parse(text: String): MrPackIndex {
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            error(tr("Файл сборки повреждён или слишком большой."))
        }
        val format = root.get("formatVersion")?.takeUnless { it.isJsonNull }?.asInt
        check(format == 1) { tr("Неподдерживаемая версия формата сборки: {0}.", format ?: "?") }
        check(str(root, "game") == "minecraft") { tr("Сборка сделана не для Minecraft.") }
        val dependencies = root.getAsJsonObject("dependencies") ?: JsonObject()
        val minecraft = str(dependencies, "minecraft") ?: error(tr("В сборке не указана версия Minecraft."))
        check(Regex("[A-Za-z0-9._+-]+").matches(minecraft)) { tr("В сборке не указана версия Minecraft.") }
        val loader = when {
            dependencies.has("neoforge") -> "NeoForge"
            dependencies.has("forge") -> "Forge"
            dependencies.has("fabric-loader") -> "Fabric"
            dependencies.has("quilt-loader") -> "Quilt"
            else -> "Vanilla"
        }
        val files = root.getAsJsonArray("files")?.map { element ->
            val file = element.asJsonObject
            val hashes = file.getAsJsonObject("hashes")
            val client = file.getAsJsonObject("env")?.let { str(it, "client") }
            MrPackFile(
                path = str(file, "path") ?: "",
                sha1 = hashes?.let { str(it, "sha1") },
                sha512 = hashes?.let { str(it, "sha512") },
                downloads = file.getAsJsonArray("downloads")?.map { it.asString }.orEmpty(),
                size = file.get("fileSize")?.takeUnless { it.isJsonNull }?.asLong ?: -1L,
                clientSide = client != "unsupported"
            )
        }.orEmpty()
        return MrPackIndex(
            name = str(root, "name") ?: "",
            summary = str(root, "summary").orEmpty(),
            minecraft = minecraft,
            loader = loader,
            files = files
        )
    }

    /** Имя нового экземпляра из названия сборки: без управляющих символов, не длиннее 40 знаков. */
    fun suggestedName(index: MrPackIndex, fallback: String): String =
        index.name.filter { !it.isISOControl() }.trim().take(40).trim().ifEmpty { fallback }

    /** Делает имя уникальным: «Сборка», «Сборка (2)»… Совпадение папок на диске тоже считается. */
    fun uniqueName(base: String, existing: Collection<String>): String {
        fun taken(name: String) = existing.any {
            it.equals(name, ignoreCase = true) ||
                LauncherPaths.instanceDirName(it).equals(LauncherPaths.instanceDirName(name), ignoreCase = true)
        }
        if (!taken(base)) return base
        var n = 2
        while (taken("$base ($n)")) n++
        return "$base ($n)"
    }

    /** Путь внутри папки экземпляра или null, если он выходит за её пределы. */
    internal fun safeTarget(root: File, relative: String): File? {
        if (relative.isBlank() || relative.contains('\u0000')) return null
        val normalized = relative.replace('\\', '/')
        if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
        if (normalized.split('/').any { it == ".." }) return null
        val rootCanonical = root.canonicalFile
        val target = File(rootCanonical, normalized).canonicalFile
        return if (target.path.startsWith(rootCanonical.path + File.separator)) target else null
    }

    internal fun urlAllowed(url: String): Boolean = try {
        val uri = URI(url)
        (uri.scheme == "https" && uri.host?.lowercase() in allowedHosts) ||
            (uri.authority != null && uri.authority in extraHosts)
    } catch (_: Exception) {
        false
    }

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun matches(file: File, entry: MrPackFile): Boolean =
        (entry.sha1 != null || entry.sha512 != null) &&
            (entry.sha1 == null || digest(file, "SHA-1").equals(entry.sha1, true)) &&
            (entry.sha512 == null || digest(file, "SHA-512").equals(entry.sha512, true))

    private fun downloadOne(entry: MrPackFile, target: File) {
        check(entry.sha1 != null || entry.sha512 != null) { tr("у файла нет контрольной суммы") }
        val urls = entry.downloads.filter { urlAllowed(it) }
        check(urls.isNotEmpty()) { tr("адрес загрузки не разрешён") }
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        var last: Exception? = null
        for (url in urls) {
            try {
                val connection = Net.open(url, 15_000, 60_000)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw HttpStatusException(code, url)
                    connection.inputStream.use { input -> temp.outputStream().buffered().use { input.copyTo(it) } }
                } finally {
                    connection.disconnect()
                }
                check(temp.length() > 0 && (entry.size < 0 || temp.length() == entry.size)) { tr("файл скачан не полностью") }
                check(matches(temp, entry)) { tr("не совпала контрольная сумма") }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            } finally {
                temp.delete()
            }
        }
        throw last ?: IllegalStateException()
    }

    /**
     * Скачивает файлы сборки в папку экземпляра и копирует overrides. Файлы, которые уже лежат с верной суммой, пропускаются.
     * Ошибка одного файла не останавливает остальные: она попадает в отчёт.
     */
    fun install(
        pack: File,
        index: MrPackIndex,
        instanceDir: File,
        onStatus: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): MrPackReport {
        instanceDir.mkdirs()
        val wanted = index.files.filter { it.clientSide }
        var downloaded = 0
        var skipped = 0
        val failed = mutableListOf<String>()
        wanted.forEachIndexed { number, entry ->
            val target = safeTarget(instanceDir, entry.path)
            if (target == null) {
                failed += tr("{0} (недопустимый путь)", entry.path)
            } else if (target.isFile && matches(target, entry)) {
                skipped++
            } else {
                onStatus(tr("Скачиваем {0} ({1}/{2})…", target.name, number + 1, wanted.size))
                try {
                    downloadOne(entry, target)
                    downloaded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += "${entry.path} (${e.message ?: e.javaClass.simpleName})"
                }
            }
            onProgress((number + 1).toFloat() / wanted.size)
        }
        onStatus(tr("Копируем файлы из сборки…"))
        return MrPackReport(downloaded, skipped, applyOverrides(pack, instanceDir), failed)
    }

    /** Копирует overrides, затем client-overrides (они главнее). Папка server-overrides игнорируется. */
    private fun applyOverrides(pack: File, instanceDir: File): Int {
        val copied = mutableSetOf<String>()
        var written = 0L
        ZipFile(pack).use { zip ->
            val entries = zip.entries().toList()
            check(entries.size <= MAX_ENTRIES) { tr("Файл сборки повреждён или слишком большой.") }
            for (prefix in listOf("overrides/", "client-overrides/")) {
                for (entry in entries) {
                    if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                    val target = safeTarget(instanceDir, entry.name.removePrefix(prefix)) ?: continue
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                check(written <= MAX_UNPACKED_BYTES) { tr("Файл сборки повреждён или слишком большой.") }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    copied += target.path
                }
            }
        }
        return copied.size
    }
}
