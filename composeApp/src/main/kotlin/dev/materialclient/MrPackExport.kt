package dev.materialclient

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class MrPackExportReport(val linked: Int, val bundled: Int, val lookupFailed: Boolean) {
    val message: String
        get() {
            val base = tr("Сборка сохранена: ссылок на Modrinth {0}, файлов внутри архива {1}.", linked, bundled)
            return if (lookupFailed) tr("Ошибка: не удалось связаться с Modrinth, все файлы положены внутрь архива.") + " " + base else base
        }
}

/**
 * Экспорт экземпляра в сборку Modrinth (.mrpack). Моды, ресурспаки и шейдеры, которые Modrinth знает по контрольной сумме,
 * записываются ссылками (архив получается маленьким), остальное кладётся в overrides.
 */
internal object MrPackExport {
    private val excludedTop = setOf("logs", "crash-reports", "screenshots", "backups", ".fabric", ".mixin.out", "modrinth-mods.json", "icon.png", ".modrinth-unused")
    private val linkable = mapOf("mods" to ".jar", "resourcepacks" to ".zip", "shaderpacks" to ".zip")

    private fun skipFile(name: String) = name.endsWith(".part") || name == "session.lock" || name == ".DS_Store" || name == "Thumbs.db"

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

    /** Версия загрузчика, которую лаунчер уже установил для этой версии Minecraft (по папкам versions). */
    internal fun detectLoaderVersion(root: File, loader: String, minecraft: String): String? {
        val names = File(root, "versions").listFiles().orEmpty().filter { it.isDirectory }.map { it.name }
        val found: List<String> = when (loader.lowercase()) {
            "fabric" -> names.filter { it.startsWith("fabric-loader-") && it.endsWith("-$minecraft") }.map { it.removePrefix("fabric-loader-").removeSuffix("-$minecraft") }
            "quilt" -> names.filter { it.startsWith("quilt-loader-") && it.endsWith("-$minecraft") }.map { it.removePrefix("quilt-loader-").removeSuffix("-$minecraft") }
            "forge" -> names.filter { it.startsWith("$minecraft-forge-") }.map { it.removePrefix("$minecraft-forge-") }
            "neoforge" -> {
                val prefix = LoaderInstaller.neoForgePrefix(minecraft)
                names.filter { it.startsWith("neoforge-") && prefix != null && it.removePrefix("neoforge-").startsWith(prefix) }.map { it.removePrefix("neoforge-") }
            }
            else -> emptyList()
        }
        return found.filter { it.isNotBlank() }.maxWithOrNull { a, b -> LoaderInstaller.compareVersions(a, b) }
    }

    private fun dependencyKey(loader: String) = when (loader.lowercase()) {
        "fabric" -> "fabric-loader"
        "quilt" -> "quilt-loader"
        "forge" -> "forge"
        "neoforge" -> "neoforge"
        else -> null
    }

    /** Ищет файлы в Modrinth по SHA-1 пачками. Возвращает sha1 → (ссылка, sha512, размер). */
    private class Known(val url: String, val sha512: String, val size: Long)

    private fun lookup(hashes: Collection<String>): Map<String, Known> {
        val result = HashMap<String, Known>()
        for (batch in hashes.chunked(100)) {
            val body = JsonObject().apply {
                add("hashes", JsonArray().also { array -> batch.forEach { array.add(it) } })
                addProperty("algorithm", "sha1")
            }
            val response = JsonParser.parseString(Net.postJson("${ModrinthApi.apiBase}/version_files", body.toString())).asJsonObject
            for (sha1 in batch) {
                val version = response.get(sha1)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                for (element in version.getAsJsonArray("files") ?: continue) {
                    val file = element.asJsonObject
                    val hashesObject = file.getAsJsonObject("hashes") ?: continue
                    val fileSha1 = hashesObject.get("sha1")?.asString ?: continue
                    val sha512 = hashesObject.get("sha512")?.asString ?: continue
                    val url = file.get("url")?.asString ?: continue
                    if (fileSha1.equals(sha1, true) && MrPack.urlAllowed(url)) {
                        result[sha1] = Known(url, sha512, file.get("size")?.asLong ?: -1L)
                    }
                }
            }
        }
        return result
    }

    fun export(
        profile: Instance,
        target: File,
        packName: String,
        packVersion: String,
        includeWorlds: Boolean,
        onStatus: (String) -> Unit = {},
        root: File = LauncherPaths.root
    ): MrPackExportReport {
        val dir = File(root, "instances/${LauncherPaths.instanceDirName(profile.name)}")
        require(dir.isDirectory) { tr("Папка экземпляра не найдена.") }
        val loaderKey = dependencyKey(profile.loader)
        val dependencies = JsonObject().apply { addProperty("minecraft", profile.version) }
        if (loaderKey != null) {
            val loaderVersion = detectLoaderVersion(root, profile.loader, profile.version)
                ?: error(tr("Не найден установленный загрузчик {0}. Запусти экземпляр один раз и повтори экспорт.", profile.loader))
            dependencies.addProperty(loaderKey, loaderVersion)
        }
        val cleanName = packName.filter { !it.isISOControl() }.trim().take(60).ifEmpty { profile.name }
        val cleanVersion = packVersion.filter { it.isLetterOrDigit() || it in ".-_+" }.ifEmpty { "1.0.0" }

        // Собираем файлы: путь внутри экземпляра → файл.
        val all = linkedMapOf<String, File>()
        dir.walkTopDown().onEnter { folder ->
            val relative = dir.toPath().relativize(folder.toPath()).toString().replace('\\', '/')
            !(relative.isNotEmpty() && !relative.contains('/') && (relative in excludedTop || (relative == "saves" && !includeWorlds)))
        }.forEach { file ->
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || skipFile(file.name)) return@forEach
            val relative = dir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            if (!relative.contains('/') && relative in excludedTop) return@forEach
            all[relative] = file
        }

        onStatus(tr("Считаем контрольные суммы…"))
        val candidates = all.filter { (path, _) ->
            val parts = path.split('/')
            parts.size == 2 && linkable[parts[0]]?.let { parts[1].endsWith(it, ignoreCase = true) } == true
        }
        val hashes = candidates.mapValues { digest(it.value, "SHA-1") }
        var failed = false
        val known: Map<String, Known> = if (hashes.isEmpty()) emptyMap() else try {
            onStatus(tr("Ищем файлы в Modrinth…"))
            lookup(hashes.values.toSet())
        } catch (_: Exception) {
            failed = true
            emptyMap()
        }

        val files = JsonArray()
        val linkedPaths = mutableSetOf<String>()
        for ((path, sha1) in hashes) {
            val info = known[sha1] ?: continue
            files.add(JsonObject().apply {
                addProperty("path", path)
                add("hashes", JsonObject().apply { addProperty("sha1", sha1); addProperty("sha512", info.sha512) })
                add("downloads", JsonArray().also { it.add(info.url) })
                addProperty("fileSize", all.getValue(path).length())
            })
            linkedPaths += path
        }
        val index = JsonObject().apply {
            addProperty("formatVersion", 1)
            addProperty("game", "minecraft")
            addProperty("versionId", cleanVersion)
            addProperty("name", cleanName)
            add("files", files)
            add("dependencies", dependencies)
        }

        target.absoluteFile.parentFile?.mkdirs()
        val temp = File(target.absoluteFile.parentFile, target.name + ".part")
        var bundled = 0
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.setLevel(6)
                zip.putNextEntry(ZipEntry("modrinth.index.json"))
                zip.write(index.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for ((path, file) in all) {
                    if (path in linkedPaths) continue
                    zip.putNextEntry(ZipEntry("overrides/$path"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    bundled++
                    if (bundled % 25 == 0) onStatus(tr("Упаковываем файлы: {0}…", bundled))
                }
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        return MrPackExportReport(linkedPaths.size, bundled, failed)
    }
}
