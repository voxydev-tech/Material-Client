package dev.materialclient

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipFile

/** Мод в папке `mods` экземпляра. Выключенные моды лежат там же под именем `*.jar.disabled`. */
internal data class ModEntry(
    val fileName: String,
    val enabled: Boolean,
    val sizeBytes: Long,
    val name: String,
    val version: String?,
    val loader: String?
)

/** Название, версия и загрузчик мода из его служебных файлов (fabric.mod.json, mods.toml и т.д.). */
internal object ModMetadata {
    class Meta(val name: String?, val version: String?, val loader: String?)

    private const val MAX_ENTRY_BYTES = 1_048_576

    /** Версия вида `${file.jarVersion}` подставляется сборщиком мода: такое значение показывать бессмысленно. */
    private fun cleanVersion(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && !it.contains('$') }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    fun read(jar: File): Meta? = try {
        ZipFile(jar).use { zip ->
            fun text(entry: String): String? {
                val e = zip.getEntry(entry)?.takeIf { !it.isDirectory } ?: return null
                return zip.getInputStream(e).use { it.readNBytes(MAX_ENTRY_BYTES).toString(Charsets.UTF_8) }
            }

            fun json(entry: String): JsonElement? = text(entry)?.let {
                try {
                    JsonParser.parseString(it.removePrefix("\uFEFF"))
                } catch (_: Exception) {
                    null
                }
            }

            val fabric = json("fabric.mod.json")?.takeIf { it.isJsonObject }?.asJsonObject
            if (fabric != null) {
                return@use Meta(fabric.string("name") ?: fabric.string("id"), cleanVersion(fabric.string("version")), "Fabric")
            }

            val quilt = json("quilt.mod.json")?.takeIf { it.isJsonObject }?.asJsonObject
            if (quilt != null) {
                val loader = quilt.getAsJsonObject("quilt_loader")
                val name = loader?.getAsJsonObject("metadata")?.string("name") ?: loader?.string("id")
                return@use Meta(name, cleanVersion(loader?.string("version")), "Quilt")
            }

            for ((entry, loaderName) in listOf("META-INF/neoforge.mods.toml" to "NeoForge", "META-INF/mods.toml" to "Forge")) {
                val toml = text(entry) ?: continue
                // Нужны поля первого блока [[mods]]; всё, что выше (loaderVersion, license), к моду не относится.
                val block = toml.substringAfter("[[mods]]", "").substringBefore("[[")
                val name = Regex("(?m)^\\s*displayName\\s*=\\s*[\"']([^\"']*)[\"']").find(block)?.groupValues?.get(1)?.trim()
                    ?: Regex("(?m)^\\s*modId\\s*=\\s*[\"']([^\"']*)[\"']").find(block)?.groupValues?.get(1)?.trim()
                val version = Regex("(?m)^\\s*version\\s*=\\s*[\"']([^\"']*)[\"']").find(block)?.groupValues?.get(1)
                return@use Meta(name?.takeIf { it.isNotEmpty() }, cleanVersion(version), loaderName)
            }

            // Старые Forge (до 1.13): mcmod.info — массив объектов или объект с полем modList.
            val legacy = json("mcmod.info")
            if (legacy != null) {
                val first: JsonObject? = when {
                    legacy.isJsonArray -> legacy.asJsonArray.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                    legacy.isJsonObject -> legacy.asJsonObject.getAsJsonArray("modList")
                        ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: legacy.asJsonObject
                    else -> null
                }
                if (first != null) {
                    return@use Meta(first.string("name") ?: first.string("modid"), cleanVersion(first.string("version")), "Forge")
                }
            }
            null
        }
    } catch (_: Exception) {
        null
    }
}

/** Моды экземпляра: список, включение, выключение, удаление. */
internal object InstalledMods {
    private const val DISABLED = ".disabled"

    fun dir(instanceName: String): File = File(LauncherPaths.instanceDir(instanceName), "mods")

    private fun isJar(name: String) = name.endsWith(".jar", ignoreCase = true)
    private fun isDisabledJar(name: String) = name.endsWith(".jar$DISABLED", ignoreCase = true)

    fun list(instanceName: String): List<ModEntry> {
        if (instanceName.isBlank()) return emptyList()
        val files = dir(instanceName).listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && (isJar(it.name) || isDisabledJar(it.name)) }
            .map { file ->
                val enabled = !isDisabledJar(file.name)
                val meta = ModMetadata.read(file)
                val base = file.name.let { if (enabled) it.dropLast(".jar".length) else it.dropLast(".jar$DISABLED".length) }
                ModEntry(file.name, enabled, file.length(), meta?.name ?: base, meta?.version, meta?.loader)
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.fileName.lowercase() }))
    }

    /** Имя файла приходит из интерфейса: пропускаем только имя файла мода без каких-либо путей. */
    private fun resolve(instanceName: String, fileName: String): File? {
        if (instanceName.isBlank() || fileName.isBlank()) return null
        if (fileName.contains('/') || fileName.contains('\\') || fileName == "." || fileName == "..") return null
        if (!isJar(fileName) && !isDisabledJar(fileName)) return null
        return File(dir(instanceName), fileName)
    }

    /** Включает или выключает мод переименованием. Возвращает текст ошибки или null, если всё получилось. */
    fun setEnabled(instanceName: String, fileName: String, enabled: Boolean): String? {
        val file = resolve(instanceName, fileName) ?: return tr("Недопустимое имя файла.")
        if (!file.isFile) return tr("Файл не найден: {0}", fileName)
        val isOff = isDisabledJar(fileName)
        if (enabled != isOff) return null // уже в нужном состоянии
        val target = File(file.parentFile, if (enabled) fileName.dropLast(DISABLED.length) else fileName + DISABLED)
        if (target.exists()) return tr("Рядом уже лежит файл {0}. Удали лишний и повтори.", target.name)
        return try {
            Files.move(file.toPath(), target.toPath())
            null
        } catch (e: Exception) {
            tr("Не удалось переименовать {0}: {1}. Если игра запущена, закрой её.", fileName, e.message ?: e.javaClass.simpleName)
        }
    }

    fun delete(instanceName: String, fileName: String): String? {
        val file = resolve(instanceName, fileName) ?: return tr("Недопустимое имя файла.")
        return try {
            Files.deleteIfExists(file.toPath())
            null
        } catch (e: Exception) {
            tr("Не удалось удалить {0}: {1}. Если игра запущена, закрой её.", fileName, e.message ?: e.javaClass.simpleName)
        }
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1_048_576L -> tr("{0} МБ", String.format(java.util.Locale.US, "%.1f", bytes / 1_048_576.0))
        bytes >= 1024L -> tr("{0} КБ", bytes / 1024)
        else -> tr("{0} Б", bytes)
    }
}

/** Содержимое выбранного журнала и подсказки о причине проблемы. */
internal class LogView(
    val sources: List<String>,
    val selected: String?,
    val file: File?,
    val text: String,
    val hints: List<String>
)

/** Журналы экземпляра: вывод запуска, `logs/latest.log` и последний краш-репорт. */
internal object GameLogs {
    const val LAUNCH = "Запуск"
    const val LATEST = "latest.log"
    const val CRASH = "Краш-репорт"

    private fun sources(instanceName: String): List<Pair<String, File>> {
        if (instanceName.isBlank()) return emptyList()
        val dir = LauncherPaths.instanceDir(instanceName)
        val result = mutableListOf<Pair<String, File>>()
        File(dir, "launcher-output.log").takeIf { it.isFile }?.let { result += LAUNCH to it }
        File(dir, "logs/latest.log").takeIf { it.isFile }?.let { result += LATEST to it }
        File(dir, "crash-reports").listFiles { f -> f.isFile && f.name.endsWith(".txt", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
            ?.let { result += CRASH to it }
        return result
    }

    fun load(instanceName: String, wanted: String?, maxLines: Int = 500): LogView {
        val all = sources(instanceName)
        val picked = all.firstOrNull { it.first == wanted } ?: all.firstOrNull()
            ?: return LogView(emptyList(), null, null, "", emptyList())
        val text = try {
            tail(picked.second, maxLines)
        } catch (e: Exception) {
            tr("Не удалось прочитать {0}: {1}", picked.second.name, e.message ?: e.javaClass.simpleName)
        }
        return LogView(all.map { it.first }, picked.first, picked.second, text, CrashAnalyzer.analyze(text))
    }

    /** Последние строки файла. Читается только конец, чтобы многомегабайтные журналы не тормозили окно. */
    fun tail(file: File, maxLines: Int = 500, maxBytes: Long = 400_000): String {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = maxOf(0L, length - maxBytes)
            raf.seek(start)
            val buffer = ByteArray((length - start).toInt())
            raf.readFully(buffer)
            var text = String(buffer, Charsets.UTF_8)
            // Начало могло попасть на середину строки (или символа): первую строку отбрасываем.
            if (start > 0) text = text.substringAfter('\n', text)
            return text.trimEnd().lines().takeLast(maxLines).joinToString("\n")
        }
    }
}

/** Подсказки по типичным причинам падения игры. Работает по тексту журнала. */
internal object CrashAnalyzer {
    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    fun analyze(log: String): List<String> {
        if (log.isBlank()) return emptyList()
        val hints = linkedSetOf<String>()

        if (rx("Could not create the Java Virtual Machine|Unrecognized VM option|Unrecognized option|Invalid maximum heap size|" +
                "Invalid initial heap size|Error occurred during initialization of VM|Too small (initial|maximum) heap").containsMatchIn(log)
        ) {
            hints += tr("Java не смогла запуститься: проверь JVM-аргументы и объём памяти (правая кнопка по экземпляру → «Настройки» и настройки лаунчера). Возможно, неверный параметр или слишком большой -Xmx.")
        }
        if (rx("Could not reserve enough space|insufficient memory for the Java Runtime|Native memory allocation \\(mmap\\) failed").containsMatchIn(log)) {
            hints += tr("Системе не хватило памяти под игру: уменьши объём памяти в настройках экземпляра и закрой лишние программы.")
        }
        if (rx("OutOfMemoryError|Java heap space|GC overhead limit exceeded").containsMatchIn(log)) {
            hints += tr("Игре не хватило памяти. Увеличь её: правая кнопка по экземпляру → «Настройки» → «Память».")
        }

        val classVersion = rx("UnsupportedClassVersionError[^\\n]*?(?:class file version|version) (\\d+)\\.\\d+").find(log)
        if (classVersion != null) {
            val needed = (classVersion.groupValues[1].toIntOrNull() ?: 0) - 44
            hints += if (needed >= 8) {
                tr("Мод или игра собраны для Java {0}, а запущена более старая. Оставь путь к Java пустым в настройках: лаунчер подберёт нужную сам.", needed)
            } else {
                tr("Версия Java не подходит этому экземпляру. Оставь путь к Java пустым в настройках: лаунчер подберёт нужную сам.")
            }
        } else if (rx("UnsupportedClassVersionError").containsMatchIn(log)) {
            hints += tr("Версия Java не подходит этому экземпляру. Оставь путь к Java пустым в настройках: лаунчер подберёт нужную сам.")
        }

        if (rx("cannot be cast to (?:class )?(?:java\\.net\\.)?URLClassLoader").containsMatchIn(log)) {
            hints += tr("Этот загрузчик (Forge или OptiFine для старой версии) работает только на Java 8. Оставь путь к Java пустым: лаунчер возьмёт Java 8 сам.")
        }

        var dependencyFound = false
        // Fabric и Quilt: «Mod 'X' (id) 1.0 requires version 0.5 or later of Fabric API (fabric-api), which is missing!»
        for (m in rx("Mod '([^']+)' \\(([^)]+)\\)[^\\n]*? requires [^\\n]*? of (?:mod )?'?([^'(\\n]+?)'? \\(([^)]+)\\), which is missing").findAll(log)) {
            hints += tr("Мод «{0}» требует мод «{1}» ({2}), а его нет. Установи его через каталог модов.", m.groupValues[1], m.groupValues[3].trim(), m.groupValues[4])
            dependencyFound = true
        }
        for (m in rx("Mod '([^']+)' \\(([^)]+)\\)[^\\n]*? requires [^\\n]*? of (?:mod )?'?([^'(\\n]+?)'? \\(([^)]+)\\), but only the wrong version is present: ([^!\\n]+)").findAll(log)) {
            hints += tr("Мод «{0}» требует другую версию мода «{1}» (сейчас {2}). Обнови или замени его.", m.groupValues[1], m.groupValues[3].trim(), m.groupValues[5].trim())
            dependencyFound = true
        }
        // Forge и NeoForge: «Mod ID: 'architectury', Requested by: 'xyz', Expected range: '[9.0,)', Actual version: '[MISSING]'»
        for (m in rx("Mod ID: '([^']+)', Requested by: '([^']+)'(?:, Expected range: '([^']*)')?(?:, Actual version: '([^']*)')?").findAll(log)) {
            val actual = m.groupValues[4]
            hints += if (actual.isBlank() || actual.contains("MISSING", ignoreCase = true)) {
                tr("Мод «{0}» требует мод «{1}», а его нет. Установи его через каталог модов.", m.groupValues[2], m.groupValues[1])
            } else {
                tr("Мод «{0}» требует мод «{1}» версии {2}, а стоит {3}. Обнови или замени его.", m.groupValues[2], m.groupValues[1], m.groupValues[3], actual)
            }
            dependencyFound = true
        }
        if (!dependencyFound && rx("Missing or unsupported mandatory dependencies|Incompatible mods found|Mod resolution failed").containsMatchIn(log)) {
            hints += tr("Не хватает обязательных модов или версии модов не подходят друг другу. Подробности в журнале, строки со словами «requires» и «Mod ID».")
        }

        if (rx("Mixin apply failed|InvalidInjectionException|Mixin transformation of|MixinTransformerError|Critical injection failure").containsMatchIn(log)) {
            hints += tr("Мод несовместим с этой версией игры или с другим модом (ошибка Mixin). Отключи последний установленный мод на странице «Установленные моды».")
        }
        if (rx("Duplicate (?:mod|ID)|duplicate mods? found|found duplicate mods|Mod '[^']+' is (?:also )?present more than once").containsMatchIn(log)) {
            hints += tr("Один и тот же мод лежит в папке mods несколько раз (разные версии). Оставь один файл на странице «Установленные моды».")
        }
        if (rx("UnsatisfiedLinkError|Failed to locate library|no lwjgl[^\\n]* in java.library.path|Failed to load native").containsMatchIn(log)) {
            hints += tr("Не загрузились нативные библиотеки игры (LWJGL). Нажми «Установить / проверить файлы» на главной странице.")
        }
        if (rx("GLFW error|GLFW_API_UNAVAILABLE|pixel format not accelerated|Failed to create window|OpenGL [^\\n]*(?:not supported|unavailable)|WGL: ").containsMatchIn(log)) {
            hints += tr("Проблема с видеодрайвером или OpenGL: обнови драйвер видеокарты. На ноутбуке с двумя видеокартами запусти Java на дискретной.")
        }
        if (rx("Could not find or load main class|NoClassDefFoundError: net/minecraft|ClassNotFoundException: net\\.minecraft\\.client").containsMatchIn(log)) {
            hints += tr("Файлы игры не найдены или повреждены. Нажми «Установить / проверить файлы» на главной странице.")
        }
        if (rx("Invalid or corrupt jarfile|Unable to access jarfile|zip END header not found|error in opening zip file|ZipException").containsMatchIn(log)) {
            hints += tr("Какой-то jar-файл повреждён или скачан не до конца. Нажми «Установить / проверить файлы» или удали недавно добавленный мод.")
        }
        if (!dependencyFound && rx("NoClassDefFoundError|ClassNotFoundException").containsMatchIn(log)) {
            hints += tr("Не хватает класса: чаще всего это мод без своей зависимости (например без Fabric API) или мод для другого загрузчика. Проверь список модов экземпляра.")
        }
        return hints.take(4)
    }
}
