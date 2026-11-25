package dev.materialclient

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Значки и цвета экземпляров. Сами картинки рисует интерфейс, здесь только допустимые значения и своя картинка. */
internal object InstanceIcons {
    const val IMAGE = "image"

    /** Ключи встроенных значков. Пустой ключ — значок по загрузчику. */
    val presets = listOf("cube", "grass", "sword", "pickaxe", "star", "flame", "heart", "bolt", "cloud", "book", "tree", "diamond")

    /** Цвета для выбора: #RRGGBB. */
    val palette = listOf("#72E5D1", "#8B70E8", "#F2778B", "#F5B35E", "#7FD66B", "#5EA9F5", "#E58AF0", "#B0B8C4")

    private const val MAX_SOURCE_BYTES = 8L * 1024 * 1024
    private const val SIZE = 128

    fun normalizeKey(key: String?): String = if (key == IMAGE || key in presets) key!! else ""

    fun normalizeColor(color: String?): String =
        color?.trim()?.takeIf { Regex("#[0-9A-Fa-f]{6}").matches(it) }?.uppercase() ?: ""

    fun imageFile(instanceName: String): File = File(LauncherPaths.instanceDir(instanceName), "icon.png")

    /** Своя картинка: проверяется, обрезается до квадрата по центру и сохраняется как icon.png 128×128. */
    fun setImage(instanceName: String, source: File) {
        require(source.isFile && source.length() in 1..MAX_SOURCE_BYTES) { tr("Не удалось использовать картинку: нужен PNG или JPG до 8 МБ.") }
        val image = try {
            ImageIO.read(source)
        } catch (_: Exception) {
            null
        } ?: error(tr("Не удалось использовать картинку: нужен PNG или JPG до 8 МБ."))
        val side = minOf(image.width, image.height)
        require(side >= 16) { tr("Не удалось использовать картинку: она слишком маленькая (минимум 16×16).") }
        val square = image.getSubimage((image.width - side) / 2, (image.height - side) / 2, side, side)
        val out = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(square, 0, 0, SIZE, SIZE, null)
        g.dispose()
        val target = imageFile(instanceName)
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, "icon.png.part")
        try {
            ImageIO.write(out, "png", temp)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    fun removeImage(instanceName: String) {
        imageFile(instanceName).delete()
    }
}

/** Время игры в экземпляре. */
internal object PlayTime {
    /** «меньше минуты», «35 мин», «2 ч», «2 ч 5 мин». */
    fun format(seconds: Long): String {
        val minutes = seconds / 60
        return when {
            minutes < 1 -> tr("меньше минуты")
            minutes < 60 -> tr("{0} мин", minutes)
            minutes % 60 == 0L -> tr("{0} ч", minutes / 60)
            else -> tr("{0} ч {1} мин", minutes / 60, minutes % 60)
        }
    }

    /** Добавляет секунды сессии экземпляру с таким именем. Нулевые и отрицательные значения игнорируются. */
    fun add(list: List<Instance>, name: String, seconds: Long): List<Instance> =
        if (seconds <= 0) list else list.map { if (it.name == name) it.copy(playTimeSec = it.playTimeSec + seconds) else it }
}

/** Описание мира на диске. */
internal class WorldInfo(val folder: String, val sizeBytes: Long, val lastModified: Long)

/** Резервная копия мира. */
internal class BackupInfo(val file: File, val world: String, val createdAt: Long, val sizeBytes: Long)

/** Резервные копии миров: zip-архивы лежат в папке лаунчера `backups/<экземпляр>/`, а не в самом экземпляре. */
internal object WorldBackups {
    private val stamp = "yyyy-MM-dd_HH-mm-ss"
    private val nameRegex = Regex("^(.+)_(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})\\.zip$")
    private const val MAX_ENTRIES = 500_000
    private const val MAX_BYTES = 64L * 1024 * 1024 * 1024

    fun savesDir(instanceName: String) = File(LauncherPaths.instanceDir(instanceName), "saves")

    fun backupsDir(instanceName: String) = File(LauncherPaths.root, "backups/${LauncherPaths.instanceDirName(instanceName)}")

    /** Имя папки мира: без путей и служебных значений. */
    internal fun validFolder(name: String): Boolean =
        name.isNotBlank() && name == File(name).name && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

    private fun folderSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Миры экземпляра: папки в saves, где есть level.dat. Недавно менявшиеся первыми. */
    fun worlds(instanceName: String): List<WorldInfo> =
        savesDir(instanceName).listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "level.dat").isFile }
            .map { WorldInfo(it.name, folderSize(it), File(it, "level.dat").lastModified()) }
            .sortedByDescending { it.lastModified }

    fun list(instanceName: String): List<BackupInfo> =
        backupsDir(instanceName).listFiles().orEmpty().mapNotNull { file ->
            val match = nameRegex.matchEntire(file.name) ?: return@mapNotNull null
            if (!file.isFile) return@mapNotNull null
            val time = try {
                SimpleDateFormat(stamp, Locale.US).parse(match.groupValues[2])?.time ?: file.lastModified()
            } catch (_: Exception) {
                file.lastModified()
            }
            BackupInfo(file, match.groupValues[1], time, file.length())
        }.sortedByDescending { it.createdAt }

    /** Сохраняет мир в zip. Возвращает файл копии. Блокировка session.lock в архив не попадает. */
    fun backup(instanceName: String, world: String, onProgress: (Int) -> Unit = {}): File {
        require(validFolder(world)) { tr("Не удалось найти мир: {0}", world) }
        val source = File(savesDir(instanceName), world)
        require(source.isDirectory && File(source, "level.dat").isFile) { tr("Не удалось найти мир: {0}", world) }
        val dir = backupsDir(instanceName).apply { mkdirs() }
        val target = File(dir, "${world}_${SimpleDateFormat(stamp, Locale.US).format(Date())}.zip")
        check(!target.exists()) { tr("Копия с таким временем уже есть. Подожди секунду.") }
        val temp = File(dir, target.name + ".part")
        var count = 0
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.setLevel(6)
                source.walkTopDown().forEach { file ->
                    if (!file.isFile || file.name == "session.lock") return@forEach
                    val relative = source.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                    zip.putNextEntry(ZipEntry("$world/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                    if (count % 50 == 0) onProgress(count)
                }
            }
            onProgress(count)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
        return target
    }

    private fun uniqueFolder(saves: File, base: String): String {
        var name = base
        var n = 2
        while (File(saves, name).exists()) name = "$base $n".also { n++ }
        return name
    }

    /**
     * Восстанавливает мир из копии. Если такой мир уже есть, он не удаляется, а переименовывается
     * в «<мир> (до восстановления)». Возвращает имя восстановленной папки мира.
     */
    fun restore(instanceName: String, backup: File, onProgress: (Int) -> Unit = {}): String {
        val match = nameRegex.matchEntire(backup.name) ?: error(tr("Это не копия мира."))
        val world = match.groupValues[1]
        require(validFolder(world) && backup.isFile) { tr("Это не копия мира.") }
        val saves = savesDir(instanceName).apply { mkdirs() }
        val temp = File(saves, ".restore-${System.currentTimeMillis()}")
        try {
            var written = 0L
            var count = 0
            ZipFile(backup).use { zip ->
                val entries = zip.entries().toList()
                check(entries.size <= MAX_ENTRIES) { tr("Копия повреждена или слишком большая.") }
                for (entry in entries) {
                    val name = entry.name.replace('\\', '/')
                    check(name.startsWith("$world/") && name.split('/').none { it == ".." }) { tr("Копия повреждена или слишком большая.") }
                    val target = File(temp, name).canonicalFile
                    check(target.path.startsWith(temp.canonicalPath + File.separator)) { tr("Копия повреждена или слишком большая.") }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                check(written <= MAX_BYTES) { tr("Копия повреждена или слишком большая.") }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    count++
                    if (count % 50 == 0) onProgress(count)
                }
            }
            check(File(temp, "$world/level.dat").isFile) { tr("В копии нет мира (level.dat).") }
            val destination = File(saves, world)
            if (destination.exists()) {
                val moved = uniqueFolder(saves, tr("{0} (до восстановления)", world))
                check(destination.renameTo(File(saves, moved))) { tr("Не удалось заменить текущий мир. Закрой Minecraft и попробуй ещё раз.") }
            }
            Files.move(File(temp, world).toPath(), destination.toPath())
            onProgress(count)
        } finally {
            temp.deleteRecursively()
        }
        return world
    }

    fun delete(backup: File) {
        require(nameRegex.matches(backup.name)) { tr("Это не копия мира.") }
        backup.delete()
    }
}

/**
 * Скины для офлайн-аккаунта. В офлайн-режиме Minecraft не получает скин с сервера Mojang, поэтому лаунчер кладёт PNG
 * туда, откуда его читает мод CustomSkinLoader: `CustomSkinLoader/LocalSkin/skins/<ник>.png`.
 */
internal object OfflineSkins {
    const val MOD_PROJECT = "customskinloader"
    private val nickRegex = Regex("[A-Za-z0-9_]{3,16}")

    fun validNick(nick: String) = nickRegex.matches(nick)

    fun libraryFile(nick: String) = File(LauncherPaths.root, "skins/$nick.png")

    fun hasSkin(nick: String) = validNick(nick) && libraryFile(nick).isFile

    /** Проверяет PNG: 64×64 или 64×32. */
    fun validate(file: File): BufferedImage {
        require(file.isFile && file.length() in 1..1_048_576) { tr("Не удалось использовать скин: нужен PNG 64×64 или 64×32 до 1 МБ.") }
        val image = try {
            ImageIO.read(file)
        } catch (_: Exception) {
            null
        } ?: error(tr("Не удалось использовать скин: нужен PNG 64×64 или 64×32 до 1 МБ."))
        require(image.width == 64 && (image.height == 64 || image.height == 32)) { tr("Не удалось использовать скин: нужен PNG 64×64 или 64×32 до 1 МБ.") }
        return image
    }

    /** Запоминает скин для ника в папке лаунчера. */
    fun save(nick: String, source: File) {
        require(validNick(nick)) { tr("Ник должен содержать 3–16 латинских букв, цифр или _") }
        val image = validate(source)
        val target = libraryFile(nick)
        target.parentFile.mkdirs()
        ImageIO.write(image, "png", target)
    }

    fun remove(nick: String) {
        if (validNick(nick)) libraryFile(nick).delete()
    }

    /** Кладёт скин ника в папку экземпляра для CustomSkinLoader. Возвращает true, если файл скопирован. */
    fun applyTo(instanceName: String, nick: String): Boolean {
        if (!hasSkin(nick)) return false
        val target = File(LauncherPaths.instanceDir(instanceName), "CustomSkinLoader/LocalSkin/skins/$nick.png")
        target.parentFile.mkdirs()
        Files.copy(libraryFile(nick).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    /** Убирает скин ника из папки экземпляра. */
    fun removeFrom(instanceName: String, nick: String) {
        if (validNick(nick)) File(LauncherPaths.instanceDir(instanceName), "CustomSkinLoader/LocalSkin/skins/$nick.png").delete()
    }

    /** Лицо персонажа (голова со вторым слоем), увеличенное в [scale] раз без сглаживания. */
    fun face(skin: File, scale: Int = 8): BufferedImage {
        val image = validate(skin)
        val out = BufferedImage(8 * scale, 8 * scale, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 8) for (x in 0 until 8) {
            var argb = image.getRGB(8 + x, 8 + y)
            // Второй слой головы есть только у скинов 64×64 (у 64×32 там пустота или мусор не читается).
            if (image.height == 64) {
                val hat = image.getRGB(40 + x, 8 + y)
                if ((hat ushr 24) > 0) argb = hat
            }
            argb = argb or (0xFF shl 24)
            for (dy in 0 until scale) for (dx in 0 until scale) out.setRGB(x * scale + dx, y * scale + dy, argb)
        }
        return out
    }
}
