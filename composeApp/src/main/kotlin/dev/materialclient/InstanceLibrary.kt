package dev.materialclient

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** Порядок экземпляров в библиотеке. */
internal object InstanceSort {
    const val ADDED = "added"
    const val RECENT = "recent"
    const val NAME = "name"
    const val VERSION = "version"

    val all = listOf(ADDED, RECENT, NAME, VERSION)

    fun normalize(mode: String?): String = if (mode in all) mode!! else ADDED

    private fun versionKey(version: String): List<Int> =
        Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()

    private fun compareVersions(a: String, b: String): Int {
        val x = versionKey(a)
        val y = versionKey(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p.compareTo(q)
        }
        return 0
    }

    /** Возвращает новый список; одинаковые по ключу экземпляры идут по названию, порядок создания сохраняется при ADDED. */
    fun sort(list: List<Instance>, mode: String): List<Instance> {
        val byName = Comparator<Instance> { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        return when (normalize(mode)) {
            RECENT -> list.sortedWith(compareByDescending<Instance> { it.lastPlayed }.then(byName))
            NAME -> list.sortedWith(byName)
            VERSION -> list.sortedWith(Comparator<Instance> { a, b -> compareVersions(b.version, a.version) }.then(byName))
            else -> list
        }
    }
}

/** Копирование экземпляра вместе со всеми его файлами (миры, моды, настройки). */
internal object InstanceCopy {
    /** Папки верхнего уровня, которые не копируются: это журналы, а не данные экземпляра. */
    private val skippedFolders = setOf("logs", "crash-reports")

    /** Файлы, которые не копируются: блокировки миров и недокачанное. */
    private fun skippedFile(name: String) = name == "session.lock" || name.endsWith(".part")

    /**
     * Копирует папку [source] в папку нового экземпляра [newName] и возвращает запись о нём.
     * При ошибке недокопированная папка удаляется, чтобы не оставлять половину экземпляра.
     */
    fun copy(source: Instance, newName: String, onProgress: (Int) -> Unit = {}): Instance {
        require(newName.isNotBlank()) { tr("Укажи название экземпляра") }
        val from = LauncherPaths.instanceDir(source.name).toPath()
        val to = LauncherPaths.instanceDir(newName).toPath()
        require(!Files.exists(to) || Files.list(to).use { !it.findAny().isPresent }) { tr("Папка нового экземпляра уже существует.") }
        val created = !Files.exists(to)
        Files.createDirectories(to)
        var count = 0
        try {
            if (Files.isDirectory(from)) {
                Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != from && dir.parent == from && dir.fileName.toString() in skippedFolders) return FileVisitResult.SKIP_SUBTREE
                        Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // Символические ссылки и служебные файлы пропускаем.
                        if (attrs.isRegularFile && !skippedFile(file.fileName.toString())) {
                            Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                            count++
                            if (count % 25 == 0) onProgress(count)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
                })
            }
            onProgress(count)
        } catch (e: Exception) {
            if (created) to.toFile().deleteRecursively() else to.toFile().listFiles()?.forEach { it.deleteRecursively() }
            throw e
        }
        return source.copy(name = newName, lastPlayed = 0L, playTimeSec = 0L)
    }
}
