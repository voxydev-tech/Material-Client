package dev.materialclient

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Адреса серверов загрузчиков. Изменяемы, чтобы их можно было подменить в тестах. */
object LoaderEndpoints {
    @JvmField var forgeMaven = "https://maven.minecraftforge.net"
    @JvmField var neoForgeMaven = "https://maven.neoforged.net"
    @JvmField var fabricMeta = "https://meta.fabricmc.net/v2"
    @JvmField var quiltMeta = "https://meta.quiltmc.org/v3"
    @JvmField var fabricMaven = "https://maven.fabricmc.net/"
    @JvmField var quiltMaven = "https://maven.quiltmc.org/repository/release/"

    /** Зеркало BMCLAPI: с него OptiFine скачивают и другие лаунчеры (например, HMCL). */
    @JvmField var optifineApi = "https://bmclapi2.bangbang93.com"

    /**
     * Запасное зеркало для Forge, NeoForge, Fabric и библиотек: туда лаунчер идёт, если официальный
     * сайт не отвечает. Пустая строка отключает зеркало.
     */
    @JvmField var mirror = "https://bmclapi2.bangbang93.com"

    /** Полный адрес на зеркале или null, если зеркало отключено. */
    fun mirrorUrl(path: String): String? {
        val root = mirror.trim().trimEnd('/')
        return if (root.isEmpty()) null else root + path
    }
}

/** Установка модлоадеров и OptiFine поверх уже скачанной ванильной версии Minecraft. */
object LoaderInstaller {
    private val versionIdRegex = Regex("[A-Za-z0-9._-]+")
    private const val MOJANG_LIBRARIES = "https://libraries.minecraft.net/"

    /**
     * Ставит загрузчик для версии Minecraft. Файлы самого Minecraft к этому моменту уже скачаны.
     * Vanilla ничего не требует.
     */
    suspend fun install(
        loader: String,
        minecraftVersion: String,
        javaPath: String,
        onStatus: (String) -> Unit
    ) {
        when (loader.lowercase()) {
            "vanilla" -> Unit
            "forge" -> MinecraftInstaller.installForge(minecraftVersion, javaPath, onStatus)
            "neoforge" -> MinecraftInstaller.installNeoForge(minecraftVersion, javaPath, onStatus)
            "fabric" -> installFabricLike(
                "Fabric", LoaderEndpoints.fabricMeta, LoaderEndpoints.mirrorUrl("/fabric-meta/v2"),
                "fabric-loader", LoaderEndpoints.fabricMaven, minecraftVersion, onStatus
            )
            // У зеркала нет метаданных Quilt, поэтому запасного адреса для них нет.
            "quilt" -> installFabricLike(
                "Quilt", LoaderEndpoints.quiltMeta, null,
                "quilt-loader", LoaderEndpoints.quiltMaven, minecraftVersion, onStatus
            )
            "optifine" -> installOptiFine(minecraftVersion, javaPath, onStatus)
            else -> error(tr("Загрузчик {0} не поддерживается", loader))
        }
    }

    // ---------------------------------------------------------------- версии

    /** Сравнение версий вида 21.1.172 или 0.27.0-beta.1: числа сравниваются как числа. */
    internal fun compareVersions(a: String, b: String): Int {
        val left = a.split('.', '-', '+', '_')
        val right = b.split('.', '-', '+', '_')
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i)
            val y = right.getOrNull(i)
            if (x == y) continue
            if (x == null) return -1
            if (y == null) return 1
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            val result = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (result != 0) return result
        }
        return 0
    }

    private fun newestFirst(versions: List<String>): List<String> =
        versions.sortedWith(Comparator { a, b -> compareVersions(b, a) })

    /** Для Minecraft 1.21.1 версии NeoForge начинаются с 21.1., для 1.21 с 21.0. Поддерживается 1.20.2 и новее. */
    internal fun neoForgePrefix(minecraftVersion: String): String? {
        val match = Regex("^1\\.(\\d+)(?:\\.(\\d+))?$").matchEntire(minecraftVersion) ?: return null
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].ifEmpty { "0" }.toInt()
        if (major < 20 || (major == 20 && minor < 2)) return null
        return "$major.$minor."
    }

    private val mavenVersionRegex = Regex("<version>\\s*([^<]+?)\\s*</version>")

    private fun versionsFromXml(xml: String): List<String> {
        val versions = mavenVersionRegex.findAll(xml).map { it.groupValues[1].trim() }.toList()
        // Страница-заглушка вместо метаданных — это ошибка источника, а не «версий нет».
        check(versions.isNotEmpty()) { tr("в ответе нет списка версий") }
        return versions
    }

    /** Версии NeoForge для Minecraft: сначала стабильные, потом бета-версии, новые раньше старых. */
    fun neoForgeCandidates(minecraftVersion: String): List<String> {
        val prefix = neoForgePrefix(minecraftVersion)
            ?: error(tr("Не удалось подобрать версию NeoForge для Minecraft {0}. NeoForge есть для релизов 1.20.2 и новее.", minecraftVersion))
        val base = LoaderEndpoints.neoForgeMaven
        val encodedMc = encode(minecraftVersion)
        // Официальный API, его XML-метаданные и два адреса на зеркале; если сайт молчит, остальные его адреса пропускаются.
        val sources = listOfNotNull(
            Net.Source("$base/api/maven/versions/releases/net/neoforged/neoforge", false) { text ->
                JsonParser.parseString(text).asJsonObject.getAsJsonArray("versions").map { it.asString }
            },
            Net.Source("$base/releases/net/neoforged/neoforge/maven-metadata.xml", false) { text -> versionsFromXml(text) },
            LoaderEndpoints.mirrorUrl("/maven/net/neoforged/neoforge/maven-metadata.xml")?.let { url ->
                Net.Source(url, true) { text -> versionsFromXml(text) }
            },
            LoaderEndpoints.mirrorUrl("/neoforge/list/$encodedMc")?.let { url ->
                Net.Source(url, true) { text ->
                    // Список зеркала: объекты с полем version (иногда с префиксом neoforge-).
                    JsonParser.parseString(text).asJsonArray.mapNotNull { element ->
                        string(element.asJsonObject, "version")?.removePrefix("neoforge-")
                    }
                }
            }
        )
        val all = Net.fetchFirst(sources)
        val matching = all.filter { it.startsWith(prefix) }
        return newestFirst(matching.filter { !it.contains('-') }) + newestFirst(matching.filter { it.contains('-') })
    }

    /** Версии Forge вида 1.21.1-52.0.0 для Minecraft, новые первыми. */
    fun forgeCandidates(minecraftVersion: String): List<String> {
        val encodedMc = encode(minecraftVersion)
        val sources = listOfNotNull(
            Net.Source("${LoaderEndpoints.forgeMaven}/net/minecraftforge/forge/maven-metadata.xml", false) { text ->
                versionsFromXml(text).filter { it.startsWith("$minecraftVersion-") }.asReversed()
            },
            LoaderEndpoints.mirrorUrl("/maven/net/minecraftforge/forge/maven-metadata.xml")?.let { url ->
                Net.Source(url, true) { text ->
                    versionsFromXml(text).filter { it.startsWith("$minecraftVersion-") }.asReversed()
                }
            },
            LoaderEndpoints.mirrorUrl("/forge/minecraft/$encodedMc")?.let { url ->
                Net.Source(url, true) { text ->
                    // Список зеркала: поле version без префикса Minecraft, build растёт с новизной.
                    JsonParser.parseString(text).asJsonArray
                        .map { it.asJsonObject }
                        .sortedByDescending { if (it.has("build") && !it.get("build").isJsonNull) it.get("build").asInt else 0 }
                        .mapNotNull { entry ->
                            val version = string(entry, "version") ?: return@mapNotNull null
                            val branch = string(entry, "branch")
                            "$minecraftVersion-$version" + if (branch.isNullOrBlank()) "" else "-$branch"
                        }
                }
            }
        )
        val versions = Net.fetchFirst(sources)
        return versions
    }

    // ---------------------------------------------------------------- общее

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun string(json: JsonObject, key: String): String? =
        json.get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun library(name: String, url: String? = null): JsonObject {
        val result = JsonObject()
        result.addProperty("name", name)
        if (url != null) result.addProperty("url", url)
        return result
    }

    /** Запасные адреса библиотеки на зеркале. Для репозиториев, которых на зеркале нет (Quilt), список пуст. */
    private fun libraryMirrors(base: String, relative: String): List<String> {
        val trimmed = base.trimEnd('/')
        return when (trimmed) {
            LoaderEndpoints.fabricMaven.trimEnd('/'), "https://maven.fabricmc.net" ->
                listOfNotNull(LoaderEndpoints.mirrorUrl("/maven/$relative"))
            MOJANG_LIBRARIES.trimEnd('/') -> listOfNotNull(LoaderEndpoints.mirrorUrl("/libraries/$relative"))
            else -> emptyList()
        }
    }

    /**
     * Скачивает библиотеки профиля, заданные только Maven-координатами (Fabric, Quilt, OptiFine):
     * у них нет блока downloads, поэтому основной установщик Minecraft их не видит.
     */
    internal suspend fun downloadNameOnlyLibraries(versionId: String, label: String, onStatus: (String) -> Unit) {
        val resolved = VersionResolver.resolve(versionId)
        val librariesDir = File(LauncherPaths.root, "libraries")
        val items = resolved.libraries
            .filter { MinecraftRules.libraryAllowed(it) && it.getAsJsonObject("downloads") == null }
            .mapNotNull { lib ->
                val relative = VersionResolver.libraryRelativePath(lib) ?: return@mapNotNull null
                val base = string(lib, "url")?.takeIf { it.isNotBlank() } ?: MOJANG_LIBRARIES
                val sha1 = string(lib, "sha1")
                MinecraftInstaller.DownloadItem(
                    base.trimEnd('/') + "/" + relative,
                    File(librariesDir, relative),
                    sha1,
                    libraryMirrors(base, relative)
                )
            }
            // Файл без известной контрольной суммы, который уже лежит на диске, второй раз не качаем.
            .filter { !(it.file.isFile && it.file.length() > 0L && it.sha1 == null) }

        items.forEachIndexed { index, item ->
            MinecraftInstaller.cancelled()
            onStatus(tr("{0}: библиотека {1} из {2}…", label, index + 1, items.size))
            MinecraftInstaller.download(item) { _, _, _ -> }
        }
    }

    private fun checkInstalled(versionId: String, label: String) {
        val problem = MinecraftLauncher.verify(versionId)
        check(problem == null) { tr("{0} установлен не полностью: {1}", label, problem) }
    }

    // ---------------------------------------------------------------- Fabric и Quilt

    private fun profileFromLauncherMeta(
        metaBase: String,
        mirrorMetaBase: String?,
        encodedMc: String,
        loaderVersion: String,
        loaderMaven: String
    ): JsonObject {
        // Запасной путь, если у сервера нет готового profile/json: профиль собираем из launcherMeta (как HMCL).
        val rest = "/versions/loader/$encodedMc/${encode(loaderVersion)}"
        val info = JsonParser.parseString(
            Net.getText("$metaBase$rest", mirrorMetaBase?.let { "$it$rest" })
        ).asJsonObject
        val meta = info.getAsJsonObject("launcherMeta") ?: error(tr("Сервер вернул профиль без launcherMeta"))
        val libraries = JsonArray()
        for (section in listOf("common", "client")) {
            meta.getAsJsonObject("libraries")?.getAsJsonArray(section)?.forEach { libraries.add(it) }
        }
        string(info.getAsJsonObject("loader") ?: JsonObject(), "maven")?.let { libraries.add(library(it, loaderMaven)) }
        string(info.getAsJsonObject("intermediary") ?: JsonObject(), "maven")?.let { libraries.add(library(it, LoaderEndpoints.fabricMaven)) }
        string(info.getAsJsonObject("hashed") ?: JsonObject(), "maven")?.let { libraries.add(library(it, LoaderEndpoints.quiltMaven)) }

        val mainClassElement = meta.get("mainClass")
        val mainClass = if (mainClassElement != null && mainClassElement.isJsonObject) {
            mainClassElement.asJsonObject.get("client").asString
        } else {
            mainClassElement?.asString ?: error(tr("В метаданных нет mainClass"))
        }

        val profile = JsonObject()
        profile.addProperty("mainClass", mainClass)
        val arguments = JsonObject()
        arguments.add("game", JsonArray())
        arguments.add("jvm", JsonArray().apply { add("-DFabricMcEmu= net.minecraft.client.main.Main ") })
        profile.add("arguments", arguments)
        profile.add("libraries", libraries)
        return profile
    }

    private suspend fun installFabricLike(
        name: String,
        metaBase: String,
        mirrorMetaBase: String?,
        idPrefix: String,
        loaderMaven: String,
        minecraftVersion: String,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(minecraftVersion.matches(versionIdRegex)) { tr("Некорректная версия Minecraft: {0}", minecraftVersion) }
        val encodedMc = encode(minecraftVersion)

        onStatus(tr("{0}: ищем версию загрузчика для Minecraft {1}…", name, minecraftVersion))
        MinecraftInstaller.cancelled()
        val listRest = "/versions/loader/$encodedMc"
        val list = JsonParser.parseString(
            Net.getText("$metaBase$listRest", mirrorMetaBase?.let { "$it$listRest" })
        ).asJsonArray
        check(list.size() > 0) { tr("{0} не поддерживает Minecraft {1}", name, minecraftVersion) }

        // Сервер отдаёт версии от новых к старым. У Fabric есть флаг stable, у Quilt его нет:
        // там пре-релизы отличаются суффиксом вроде «-beta.1».
        val loaders = list.map { it.asJsonObject.getAsJsonObject("loader") }
        val loader = loaders.firstOrNull { it.get("stable")?.takeUnless { e -> e.isJsonNull }?.asBoolean == true }
            ?: loaders.firstOrNull { !it.get("version").asString.contains('-') }
            ?: loaders.first()
        val loaderVersion = loader.get("version").asString
        val versionId = "$idPrefix-$loaderVersion-$minecraftVersion"
        require(versionId.matches(versionIdRegex)) { tr("Некорректный идентификатор профиля: {0}", versionId) }

        onStatus(tr("{0}: загружаем профиль {1}…", name, loaderVersion))
        MinecraftInstaller.cancelled()
        val profileRest = "/versions/loader/$encodedMc/${encode(loaderVersion)}/profile/json"
        val profile = try {
            JsonParser.parseString(
                Net.getText("$metaBase$profileRest", mirrorMetaBase?.let { "$it$profileRest" })
            ).asJsonObject
        } catch (cancel: java.util.concurrent.CancellationException) {
            throw cancel
        } catch (first: Exception) {
            // Нет готового профиля: собираем его сами. Если и это не вышло, показываем обе причины.
            try {
                profileFromLauncherMeta(metaBase, mirrorMetaBase, encodedMc, loaderVersion, loaderMaven)
            } catch (cancel: java.util.concurrent.CancellationException) {
                throw cancel
            } catch (second: Exception) {
                throw IllegalStateException(first.message ?: second.message ?: tr("не удалось получить профиль"), second)
            }
        }
        profile.addProperty("id", versionId)
        profile.addProperty("inheritsFrom", minecraftVersion)
        if (!profile.has("type")) profile.addProperty("type", "release")

        val versionDir = VersionResolver.versionDir(versionId)
        val existed = versionDir.exists()
        var installed = false
        try {
            versionDir.mkdirs()
            File(versionDir, "$versionId.json").writeText(profile.toString())
            downloadNameOnlyLibraries(versionId, name, onStatus)
            checkInstalled(versionId, name)
            installed = true
        } finally {
            // Отмена или ошибка не должны оставлять «полупрофиль», который лаунчер принял бы за установленный.
            if (!installed && !existed) versionDir.deleteRecursively()
        }
        onStatus(tr("{0} {1} успешно установлен.", name, loaderVersion))
    }

    // ---------------------------------------------------------------- OptiFine

    /** Оценка версии OptiFine: стабильная лучше пре-релиза, HD_U лучше остальных, потом буква и номер патча. */
    private fun optiFineScore(rest: String): List<Int> {
        val stable = if (rest.contains("pre", ignoreCase = true) || rest.contains("alpha", ignoreCase = true)) 0 else 1
        val ultra = if (rest.startsWith("HD_U")) 1 else 0
        val patch = Regex("([A-Z])(\\d+)").findAll(rest).lastOrNull()
        val letter = patch?.groupValues?.get(1)?.get(0)?.code ?: 0
        val number = patch?.groupValues?.get(2)?.toIntOrNull() ?: 0
        return listOf(stable, ultra, letter, number)
    }

    private fun compareScores(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            val result = a[i].compareTo(b[i])
            if (result != 0) return result
        }
        return 0
    }

    private fun bestOptiFine(candidates: List<String>): String? =
        candidates.maxWithOrNull(Comparator { a, b -> compareScores(optiFineScore(a), optiFineScore(b)) })

    /** В BMCLAPI версии 1.8 и 1.9 записаны как 1.8.0 и 1.9.0. */
    private fun optiFineGameVersion(minecraftVersion: String): String = when (minecraftVersion) {
        "1.8" -> "1.8.0"
        "1.9" -> "1.9.0"
        else -> minecraftVersion
    }

    private fun findLocalOptiFine(dir: File, gameVersion: String): File? {
        val pattern = Regex("^OptiFine_${Regex.escape(gameVersion)}_(.+)\\.jar$")
        val matches = dir.listFiles()
            ?.filter { it.isFile && it.length() > 100_000L }
            ?.mapNotNull { file -> pattern.matchEntire(file.name)?.let { it.groupValues[1] to file } }
            ?: return null
        val best = bestOptiFine(matches.map { it.first }) ?: return null
        return matches.first { it.first == best }.second
    }

    private suspend fun downloadOptiFine(dir: File, gameVersion: String, onStatus: (String) -> Unit): File {
        onStatus(tr("OptiFine: ищем версию для Minecraft {0}…", gameVersion))
        val api = LoaderEndpoints.optifineApi.trimEnd('/')
        // Сначала короткий список для одной версии Minecraft (меньше данных), потом полный список.
        val sources = listOf(
            Net.Source("$api/optifine/${encode(gameVersion)}", false) { text ->
                JsonParser.parseString(text).asJsonArray.map { it.asJsonObject }
            },
            Net.Source("$api/optifine/versionList", false) { text ->
                JsonParser.parseString(text).asJsonArray.map { it.asJsonObject }
            }
        )
        val entries = Net.fetchFirst(sources).filter { string(it, "mcversion") == gameVersion }
        check(entries.isNotEmpty()) {
            tr("Для Minecraft {0} нет версии OptiFine. Выбери другую версию Minecraft или скачай OptiFine_{1}_….jar с optifine.net и положи в папку {2}.", gameVersion, gameVersion, dir.absolutePath)
        }
        val byRest = entries.associateBy { "${string(it, "type")}_${string(it, "patch")}" }
        val bestRest = bestOptiFine(byRest.keys.toList()) ?: error(tr("Не удалось выбрать версию OptiFine"))
        val best = byRest.getValue(bestRest)
        val type = string(best, "type") ?: error(tr("В списке OptiFine нет поля type"))
        val patch = string(best, "patch") ?: error(tr("В списке OptiFine нет поля patch"))
        require(type.matches(versionIdRegex) && patch.matches(versionIdRegex)) { tr("Некорректная версия OptiFine: {0}_{1}", type, patch) }

        val target = File(dir, "OptiFine_${gameVersion}_${type}_$patch.jar")
        onStatus(tr("OptiFine: скачиваем OptiFine {0} {1}…", type, patch))
        MinecraftInstaller.download(
            MinecraftInstaller.DownloadItem("$api/optifine/$gameVersion/$type/$patch", target, null)
        ) { _, read, total ->
            val kb = read / 1024
            val size = if (total > 0) tr("{0} / {1} КБ", kb, total / 1024) else tr("{0} КБ", kb)
            onStatus(tr("OptiFine: скачиваем OptiFine {0} {1} — {2}", type, patch, size))
        }
        check(target.length() > 100_000L) { tr("Скачанный файл OptiFine слишком маленький ({0} байт)", target.length()) }
        return target
    }

    private fun runTool(command: List<String>, dir: File, log: File, timeoutMinutes: Long): Pair<Int, String> {
        log.parentFile?.mkdirs()
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
            .start()
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(timeoutMinutes)
        try {
            while (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                MinecraftInstaller.cancelled()
                if (System.nanoTime() > deadline) error(tr("Процесс не завершился за {0} мин.", timeoutMinutes))
            }
            return process.exitValue() to log.readText().takeLast(1500)
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }

    /**
     * OptiFine ставится так же, как это делает его установщик: патчер собирает библиотеку из ванильного
     * клиента, затем создаётся профиль с launchwrapper и tweaker-ом OptiFine.
     */
    private suspend fun installOptiFine(
        minecraftVersion: String,
        javaPath: String,
        onStatus: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(minecraftVersion.matches(versionIdRegex)) { tr("Некорректная версия Minecraft: {0}", minecraftVersion) }
        val root = LauncherPaths.root
        val vanillaJar = File(root, "versions/$minecraftVersion/$minecraftVersion.jar")
        val vanillaJson = File(root, "versions/$minecraftVersion/$minecraftVersion.json")
        require(vanillaJar.isFile && vanillaJson.isFile) { tr("Сначала нужно установить файлы Minecraft {0}", minecraftVersion) }

        val requiredJava = VersionResolver.requiredJava(minecraftVersion)
        val selection = JavaProvider.ensure(
            javaPath, requiredJava, JavaProvider.maxMajorFor("OptiFine", requiredJava),
            onStatus = { message -> onStatus("OptiFine: $message") }
        )
        val java = selection.java
        if (selection.note != null) onStatus("OptiFine: ${selection.note}")

        val gameVersion = optiFineGameVersion(minecraftVersion)
        val optifineDir = File(root, "optifine").apply { mkdirs() }
        MinecraftInstaller.cancelled()
        // Сначала смотрим, не лежит ли нужный файл OptiFine в папке optifine (например, скачанный вручную).
        val installerJar = findLocalOptiFine(optifineDir, gameVersion) ?: try {
            downloadOptiFine(optifineDir, gameVersion, onStatus)
        } catch (cancel: java.util.concurrent.CancellationException) {
            throw cancel
        } catch (error: IllegalStateException) {
            if (error.message?.contains("optifine.net") == true) throw error
            throw IllegalStateException(
                tr("{0} Если не получается, скачай OptiFine_{1}_….jar с optifine.net вручную и положи в папку {2}: лаунчер возьмёт файл оттуда.", error.message, gameVersion, optifineDir.absolutePath),
                error
            )
        }

        val optifineVersion = installerJar.name.removePrefix("OptiFine_").removeSuffix(".jar")
        val rest = optifineVersion.removePrefix("${gameVersion}_")
        val versionId = "$minecraftVersion-OptiFine_$rest"
        require(optifineVersion.matches(versionIdRegex) && versionId.matches(versionIdRegex)) {
            tr("Некорректное имя файла OptiFine: {0}", installerJar.name)
        }

        val versionDir = VersionResolver.versionDir(versionId)
        val existed = versionDir.exists()
        var installed = false
        try {
            val looksLikeInstaller = try {
                ZipFile(installerJar).use { zip -> zip.getEntry("optifine/Patcher.class") != null }
            } catch (_: java.io.IOException) {
                false
            }
            check(looksLikeInstaller) {
                tr("Файл {0} не является установщиком OptiFine или повреждён. Удали его из папки {1} и повтори установку.", installerJar.name, installerJar.parentFile.absolutePath)
            }

            val libraryFile = File(root, "libraries/optifine/OptiFine/$optifineVersion/OptiFine-$optifineVersion.jar")
            if (!libraryFile.isFile || libraryFile.length() < 100_000L) {
                onStatus(tr("OptiFine: собираем библиотеку из клиента Minecraft…"))
                libraryFile.parentFile.mkdirs()
                val temp = File(libraryFile.parentFile, libraryFile.name + ".part")
                temp.delete()
                val log = File(root, "logs/optifine-install.log")
                val (exitCode, tail) = runTool(
                    listOf(
                        java.absolutePath, "-cp", installerJar.absolutePath, "optifine.Patcher",
                        vanillaJar.absolutePath, installerJar.absolutePath, temp.absolutePath
                    ),
                    root, log, 10
                )
                check(exitCode == 0 && temp.length() > 100_000L) {
                    tr("Не удалось собрать OptiFine (код {0}). Полный вывод: {1}. {2}", exitCode, log.absolutePath, tail)
                }
                Files.move(temp.toPath(), libraryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            val libraries = JsonArray()
            libraries.add(library("optifine:OptiFine:$optifineVersion"))
            ZipFile(installerJar).use { zip ->
                val wrapperVersionEntry = zip.getEntry("launchwrapper-of.txt")
                if (wrapperVersionEntry != null) {
                    val wrapperVersion = zip.getInputStream(wrapperVersionEntry).bufferedReader().use { it.readText() }.trim()
                    require(wrapperVersion.matches(versionIdRegex)) { tr("Некорректная версия launchwrapper-of: {0}", wrapperVersion) }
                    val entry = zip.getEntry("launchwrapper-of-$wrapperVersion.jar")
                        ?: error(tr("В установщике OptiFine нет launchwrapper-of-{0}.jar", wrapperVersion))
                    val target = File(root, "libraries/optifine/launchwrapper-of/$wrapperVersion/launchwrapper-of-$wrapperVersion.jar")
                    if (!target.isFile) {
                        target.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
                    libraries.add(library("optifine:launchwrapper-of:$wrapperVersion"))
                } else if (zip.getEntry("launchwrapper-2.0.jar") != null) {
                    val target = File(root, "libraries/optifine/launchwrapper/2.0/launchwrapper-2.0.jar")
                    if (!target.isFile) {
                        target.parentFile.mkdirs()
                        zip.getInputStream(zip.getEntry("launchwrapper-2.0.jar")).use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                    }
                    libraries.add(library("optifine:launchwrapper:2.0"))
                } else {
                    libraries.add(library("net.minecraft:launchwrapper:1.12", MOJANG_LIBRARIES))
                    libraries.add(library("org.ow2.asm:asm-all:5.0.3", "https://repo1.maven.org/maven2/"))
                }
            }

            val parent = JsonParser.parseString(vanillaJson.readText()).asJsonObject
            val profile = JsonObject()
            profile.addProperty("id", versionId)
            profile.addProperty("inheritsFrom", minecraftVersion)
            profile.addProperty("type", "release")
            profile.addProperty("mainClass", "net.minecraft.launchwrapper.Launch")
            profile.add("libraries", libraries)
            val legacyArguments = string(parent, "minecraftArguments")
            if (legacyArguments != null) {
                // Версии до 1.13 передают аргументы одной строкой.
                profile.addProperty("minecraftArguments", "$legacyArguments --tweakClass optifine.OptiFineTweaker")
            } else {
                val arguments = JsonObject()
                arguments.add("game", JsonArray().apply { add("--tweakClass"); add("optifine.OptiFineTweaker") })
                profile.add("arguments", arguments)
            }

            versionDir.mkdirs()
            File(versionDir, "$versionId.json").writeText(profile.toString())
            downloadNameOnlyLibraries(versionId, "OptiFine", onStatus)
            checkInstalled(versionId, "OptiFine")
            installed = true
        } finally {
            if (!installed && !existed) versionDir.deleteRecursively()
        }
        onStatus(tr("OptiFine {0} успешно установлен.", rest))
    }
}
