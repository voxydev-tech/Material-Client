package dev.materialclient

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Java, которую лаунчер скачивает сам: те же сборки, что ставит официальный лаунчер Mojang
 * (jre-legacy = Java 8, java-runtime-gamma = Java 17, java-runtime-delta = Java 21 и т.д.).
 * Лежат в `~/.material-client/runtime/<компонент>`; признак полной установки — файл-маркер.
 */
internal object JavaRuntimes {
    /** Каталог Java-сборок Mojang. Изменяем, чтобы подменить в тестах. */
    @JvmField
    @Volatile
    var indexUrl =
        "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json"

    /** Для тестов: принудительный ключ платформы (например `windows-x64`). */
    @JvmField
    @Volatile
    var platformOverride: String? = null

    private const val MARKER = ".materialclient-java"
    private const val CONCURRENCY = 8
    private val lock = Mutex()

    val directory: File
        get() = File(LauncherPaths.root, "runtime")

    /** Ключ платформы в каталоге Mojang или null, если для этой системы готовых сборок нет. */
    fun platformKey(): String? {
        platformOverride?.let { return it }
        val os = System.getProperty("os.name", "").lowercase()
        // 32-разрядная Java на 64-разрядной Windows видит себя как x86: настоящую архитектуру берём из окружения.
        val arch = (System.getenv("PROCESSOR_ARCHITEW6432") ?: System.getProperty("os.arch", "")).lowercase()
        val arm = arch.contains("aarch64") || arch.contains("arm64")
        val x86 = arch in setOf("x86", "i386", "i486", "i586", "i686")
        return when {
            os.contains("win") -> if (arm) "windows-arm64" else if (x86) "windows-x86" else "windows-x64"
            os.contains("mac") -> if (arm) "mac-os-arm64" else "mac-os"
            os.contains("linux") -> if (arm) null else if (x86) "linux-i386" else "linux"
            else -> null
        }
    }

    fun isComplete(dir: File): Boolean = File(dir, MARKER).isFile

    /** Исполняемые файлы Java, скачанных лаунчером и докачанных до конца. */
    fun installedExecutables(): List<File> =
        directory.listFiles()
            ?.filter { it.isDirectory && isComplete(it) }
            ?.sortedBy { it.name }
            ?.mapNotNull { JavaInfo.executableIn(it) }
            ?: emptyList()

    // ---------------------------------------------------------------- выбор сборки

    internal class Component(
        val name: String,
        val major: Int,
        val versionName: String,
        val manifestUrl: String,
        val manifestSha1: String
    )

    /** `8u202` и `1.8.0_202` — это 8; `17.0.8` — это 17. */
    internal fun majorOf(versionName: String): Int? {
        val text = versionName.trim()
        Regex("^1\\.(\\d+)").find(text)?.let { return it.groupValues[1].toIntOrNull() }
        return Regex("^(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun versionKey(name: String): List<Int> =
        Regex("\\d+").findAll(name).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun compareVersionNames(a: String, b: String): Int {
        val x = versionKey(a)
        val y = versionKey(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * Из каталога выбирается сборка с наименьшей версией Java, которая не меньше нужной и не больше предела
     * (предел нужен старым загрузчикам, которые не работают на новой Java).
     */
    internal fun pickComponent(index: JsonObject, platform: String, required: Int?, maxMajor: Int?): Component? {
        val entries = index.getAsJsonObject(platform) ?: return null
        val minMajor = required ?: 17
        val candidates = mutableListOf<Component>()
        for ((name, value) in entries.entrySet()) {
            if (!name.startsWith("jre") && !name.startsWith("java-runtime")) continue
            if (!value.isJsonArray) continue
            val entry = value.asJsonArray.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val versionName = entry.getAsJsonObject("version")?.get("name")?.takeUnless { it.isJsonNull }?.asString ?: continue
            val manifest = entry.getAsJsonObject("manifest") ?: continue
            val url = manifest.get("url")?.takeUnless { it.isJsonNull }?.asString ?: continue
            val sha1 = manifest.get("sha1")?.takeUnless { it.isJsonNull }?.asString ?: ""
            val major = majorOf(versionName) ?: continue
            if (major < 8) continue
            candidates += Component(name, major, versionName, url, sha1)
        }
        return candidates
            .filter { it.major >= minMajor && (maxMajor == null || it.major <= maxMajor) }
            .sortedWith(compareBy<Component> { it.major }.thenByDescending { it.versionName }.thenBy { it.name })
            .let { sorted ->
                val best = sorted.firstOrNull() ?: return null
                // Среди сборок одной версии (например beta и gamma для Java 17) берём самую свежую.
                sorted.filter { it.major == best.major }.reduce { a, b -> if (compareVersionNames(a.versionName, b.versionName) >= 0) a else b }
            }
    }

    // ---------------------------------------------------------------- сеть

    /** Сайты Mojang, которые есть на зеркале BMCLAPI. Изменяемый список нужен тестам. */
    internal val mirrorHosts = mutableListOf(
        "piston-meta.mojang.com", "piston-data.mojang.com", "launchermeta.mojang.com", "launcher.mojang.com"
    )

    /** Адрес того же файла на зеркале BMCLAPI или null, если зеркало отключено или адрес чужой. */
    internal fun mirrorOf(url: String): String? {
        val root = LoaderEndpoints.mirror.trim().trimEnd('/')
        if (root.isEmpty()) return null
        val uri = try {
            java.net.URI.create(url)
        } catch (_: Exception) {
            return null
        }
        if (uri.host !in mirrorHosts) return null
        return root + (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun fetchJson(url: String): JsonObject {
        val sources = listOfNotNull(
            Net.Source(url, false) { JsonParser.parseString(it).asJsonObject },
            mirrorOf(url)?.let { Net.Source(it, true) { text -> JsonParser.parseString(text).asJsonObject } }
        )
        return Net.fetchFirst(sources)
    }

    // ---------------------------------------------------------------- пути

    /** Путь внутри папки Java. Всё, что выходит за её пределы, отвергается: манифест приходит из сети. */
    private fun safeChild(root: File, relative: String): File {
        val segments = relative.split('/')
        require(
            relative.isNotEmpty() && segments.none { it.isEmpty() || it == "." || it == ".." || it.contains(':') || it.contains('\\') }
        ) { tr("Недопустимый путь в манифесте Java: {0}", relative) }
        // Папка проверяется с раскрытием ссылок, а сам файл — нет: иначе путь уже созданной ссылки
        // превратился бы в путь её цели, и повторная установка испортила бы файл.
        val child = File(root, relative)
        val parent = child.parentFile.canonicalFile
        require(parent.toPath().startsWith(root.canonicalFile.toPath())) { tr("Недопустимый путь в манифесте Java: {0}", relative) }
        return File(parent, child.name)
    }

    // ---------------------------------------------------------------- установка

    /**
     * Скачивает подходящую Java и возвращает путь к её java/javaw. Уже скачанные и проверенные файлы
     * не загружаются заново, поэтому прерванную загрузку можно просто повторить.
     */
    suspend fun install(required: Int?, maxMajor: Int?, onStatus: (String) -> Unit): File = lock.withLock {
        withContext(Dispatchers.IO) {
            val platform = platformKey()
                ?: error(tr("Для этой системы у Mojang нет готовой Java. Установи Java сам и укажи путь к ней в настройках."))
            onStatus(tr("Java: получаем список сборок…"))
            val index = fetchJson(indexUrl)
            val component = pickComponent(index, platform, required, maxMajor)
                ?: error(
                    if (required != null && maxMajor != null) {
                        tr("В каталоге Mojang нет Java {0} (не новее {1}) для платформы {2}. Установи подходящую Java сам и укажи путь к ней в настройках.", required, maxMajor, platform)
                    } else if (required != null) {
                        tr("В каталоге Mojang нет Java {0} для платформы {1}. Установи подходящую Java сам и укажи путь к ней в настройках.", required, platform)
                    } else if (maxMajor != null) {
                        tr("В каталоге Mojang нет Java (не новее {0}) для платформы {1}. Установи подходящую Java сам и укажи путь к ней в настройках.", maxMajor, platform)
                    } else {
                        tr("В каталоге Mojang нет Java для платформы {0}. Установи подходящую Java сам и укажи путь к ней в настройках.", platform)
                    }
                )

            val target = File(directory, component.name)
            val label = "Java ${component.major}"
            val existing = JavaInfo.executableIn(target)
            val markerText = "component=${component.name}\nversion=${component.versionName}\nmanifest=${component.manifestSha1}\n"
            val marker = File(target, MARKER)
            if (existing != null && marker.isFile && runCatching { marker.readText() }.getOrNull() == markerText) {
                return@withContext existing
            }
            // Пока файлы докачиваются, сборка не считается готовой.
            marker.delete()

            onStatus(tr("{0}: получаем список файлов…", label))
            val manifest = fetchJson(component.manifestUrl)
            val entries = manifest.getAsJsonObject("files") ?: error(tr("Манифест Java {0} пуст", component.name))
            target.mkdirs()

            class FileJob(val file: File, val raw: JsonObject, val executable: Boolean)

            val jobs = mutableListOf<FileJob>()
            val links = mutableListOf<Pair<String, String>>()
            for ((path, value) in entries.entrySet()) {
                val entry = value.asJsonObject
                when (entry.get("type")?.asString) {
                    "directory" -> safeChild(target, path).mkdirs()
                    "link" -> {
                        val linkTarget = entry.get("target")?.takeUnless { it.isJsonNull }?.asString
                            ?: error(tr("У ссылки {0} нет цели", path))
                        require(!linkTarget.startsWith("/") && !linkTarget.contains(':') && !linkTarget.contains('\\')) {
                            tr("Недопустимая ссылка в манифесте Java: {0} -> {1}", path, linkTarget)
                        }
                        safeChild(target, path)
                        links += path to linkTarget
                    }
                    "file" -> {
                        val raw = entry.getAsJsonObject("downloads")?.getAsJsonObject("raw")
                            ?: error(tr("У файла {0} нет прямой ссылки на скачивание", path))
                        jobs += FileJob(safeChild(target, path), raw, entry.get("executable")?.asBoolean == true)
                    }
                }
            }

            val totalBytes = jobs.sumOf { it.raw.get("size")?.takeUnless { s -> s.isJsonNull }?.asLong ?: 0L }.coerceAtLeast(1L)
            val done = ConcurrentHashMap<String, Long>()
            val lastReport = AtomicLong(0)
            var finished = 0
            val semaphore = Semaphore(CONCURRENCY)
            val total = jobs.size

            fun report(force: Boolean) {
                val now = System.nanoTime()
                val last = lastReport.get()
                if (!force && now - last < 250_000_000L) return
                if (!lastReport.compareAndSet(last, now)) return
                val read = done.values.sum().coerceAtMost(totalBytes)
                onStatus(tr("{0}: скачано {1} из {2} МБ ({3}%)", label, read / 1_048_576, totalBytes / 1_048_576, (read * 100 / totalBytes)))
            }

            onStatus(tr("{0}: скачиваем ({1} МБ, {2} файлов)…", label, totalBytes / 1_048_576, total))
            coroutineScope {
                jobs.map { job ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val url = job.raw.get("url").asString
                            val sha1 = job.raw.get("sha1")?.takeUnless { it.isJsonNull }?.asString
                            MinecraftInstaller.download(
                                MinecraftInstaller.DownloadItem(url, job.file, sha1, listOfNotNull(mirrorOf(url)))
                            ) { _, read, _ ->
                                done[job.file.path] = read
                                report(false)
                            }
                            if (job.executable) job.file.setExecutable(true, false)
                            synchronized(this@coroutineScope) { finished++ }
                            report(false)
                        }
                    }
                }.awaitAll()
            }
            report(true)

            // Ссылки (в основном macOS и Linux). Если ОС не даёт создать символическую ссылку, копируем файл.
            for ((path, linkTarget) in links) {
                val link = safeChild(target, path)
                val destination = File(link.parentFile, linkTarget).canonicalFile
                require(destination.toPath().startsWith(target.canonicalFile.toPath())) {
                    tr("Недопустимая ссылка в манифесте Java: {0} -> {1}", path, linkTarget)
                }
                try {
                    Files.deleteIfExists(link.toPath())
                    Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(linkTarget))
                } catch (_: Exception) {
                    when {
                        destination.isFile -> destination.copyTo(link, overwrite = true)
                        destination.isDirectory -> destination.copyRecursively(link, overwrite = true)
                    }
                }
            }

            val executable = JavaInfo.executableIn(target)
                ?: error(tr("После скачивания в {0} не найден java. Удали папку и повтори.", target.absolutePath))
            val major = JavaInfo.detectMajor(executable)
            check(major == null || major >= (required ?: 0)) {
                tr("Скачана Java {0}, а нужна не меньше {1}. Каталог Mojang изменился: напиши об этом разработчику.", major, required)
            }
            marker.writeText(markerText)
            onStatus(tr("{0}: готово", label))
            executable
        }
    }
}

/**
 * Подбор Java для Minecraft и загрузчиков: сначала выбранная вручную, потом найденные в системе,
 * потом скачанная лаунчером.
 */
internal object JavaProvider {
    class Selection(val java: File, val major: Int?, val note: String?)

    /**
     * Forge и OptiFine для Minecraft 1.16 и старше работают только на Java 8 (launchwrapper ломается на Java 9+).
     * Возвращает верхний предел версии Java или null, если ограничений нет.
     */
    fun maxMajorFor(loader: String, required: Int?): Int? {
        val legacy = loader.equals("Forge", ignoreCase = true) || loader.equals("OptiFine", ignoreCase = true)
        return if (legacy && required != null && required <= 8) 8 else null
    }

    /**
     * @param configured путь из настроек; пустая строка — выбирать автоматически
     * @param required минимальная версия Java по данным версии Minecraft (null — неизвестно)
     * @param maxMajor предел версии Java или null
     * @param autoDownload разрешено ли скачивать Java, если подходящей нет
     */
    suspend fun ensure(
        configured: String,
        required: Int?,
        maxMajor: Int? = null,
        autoDownload: Boolean = GameSettings.autoJava,
        onStatus: (String) -> Unit = {}
    ): Selection {
        val minMajor = required ?: 0
        fun fits(major: Int?) = major != null && major >= minMajor && (maxMajor == null || major <= maxMajor)

        var rejected: String? = null
        // Указанная вручную Java, которая новее предела: пригодится, только если ничего лучше нет.
        var tooNew: Pair<File, Int>? = null
        val text = configured.trim()
        if (text.isNotEmpty()) {
            val file = File(text)
            if (file.isFile) {
                val major = JavaInfo.detectMajor(file)
                // Версию не определить (нет файла release): доверяем выбору пользователя.
                if (major == null) return Selection(file, null, null)
                if (fits(major)) return Selection(file, major, null)
                if (major < minMajor) {
                    rejected = tr("указанная Java {0} слишком старая", major)
                } else {
                    rejected = tr("указанная Java {0} не подходит этому загрузчику (нужна Java {1} или старше)", major, maxMajor)
                    tooNew = file to major
                }
            } else {
                rejected = tr("указанная Java не найдена")
            }
        }

        val found = JavaInfo.findInstalled()
            .mapNotNull { file -> JavaInfo.detectMajor(file)?.let { it to file } }
            .filter { fits(it.first) }
        // Для старых загрузчиков берём самую новую из допустимых, иначе самую близкую к нужной.
        val best = if (maxMajor != null) found.maxByOrNull { it.first } else found.minByOrNull { it.first }
        if (best != null) {
            val note = if (rejected != null) tr("выбрана Java {0}: {1}", best.first, rejected) else null
            return Selection(best.second, best.first, note)
        }

        var downloadError: Exception? = null
        if (autoDownload) {
            try {
                val exe = JavaRuntimes.install(required, maxMajor, onStatus)
                val major = JavaInfo.detectMajor(exe)
                val downloaded = if (major != null) tr("скачана Java {0}", major) else tr("скачана Java")
                return Selection(exe, major, downloaded + if (rejected != null) " ($rejected)" else "")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                downloadError = e
            }
        }

        tooNew?.let { (file, major) ->
            return Selection(file, major, tr("Java {0} может не подойти этому загрузчику, подходящую найти не удалось", major))
        }

        val need = when {
            required != null && maxMajor != null -> tr("Нужна Java {0} (не новее {1})", required, maxMajor)
            required != null -> tr("Нужна Java {0}", required)
            maxMajor != null -> tr("Нужна подходящая Java (не новее {0})", maxMajor)
            else -> tr("Нужна подходящая Java")
        }
        throw IllegalStateException(
            when {
                downloadError != null ->
                    tr("{0}. Подходящей Java на компьютере нет, а скачать её не удалось: {1}", need, downloadError.message)
                autoDownload -> tr("{0}. Подходящей Java не найдено.", need)
                else ->
                    tr("{0}. Подходящей Java на компьютере нет. Включи автоматическую загрузку Java в настройках или установи Java сам и укажи путь к ней.", need)
            },
            downloadError
        )
    }
}
