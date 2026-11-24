package dev.materialclient

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Версия этого лаунчера. Меняй её вместе с `version` и `packageVersion` в composeApp/build.gradle.kts. */
internal object AppInfo {
    const val VERSION = "1.0.0"

    /** Репозиторий релизов по умолчанию («владелец/имя»). Пользователь может задать свой в «О лаунчере». */
    const val DEFAULT_REPO = "voxydev-tech/Material-Client"

    /** Репозиторий, который реально используется: из настроек или встроенный. */
    fun updateRepo(): String = GameSettings.updateRepo.ifBlank { DEFAULT_REPO }
}

/** Найденное обновление: версия, куда за ней идти и что скачивать. */
internal data class UpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val assetName: String?,
    val assetUrl: String?,
    val assetSize: Long,
    val prerelease: Boolean
)

/**
 * Проверка и установка новой версии лаунчера через релизы GitHub.
 * Установщик скачивается и запускается как обычный файл; благодаря постоянному upgradeUuid он обновляет
 * уже установленный лаунчер, а не ставится рядом.
 */
internal object Updater {
    /** Адрес API. Изменяемый, чтобы тесты могли подставить свой сервер. */
    @JvmField var apiBase = "https://api.github.com"

    private val repoPattern = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

    fun isValidRepo(repo: String): Boolean {
        val clean = repo.trim()
        return repoPattern.matches(clean) && clean.split('/').none { part -> part.all { it == '.' } }
    }

    /** «v0.3.1-beta» → [0, 3, 1]. null, если в метке нет номера версии. */
    internal fun parseVersion(tag: String): List<Int>? {
        val match = Regex("^[vV]?(\\d+(?:\\.\\d+){0,3})").find(tag.trim()) ?: return null
        return match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    internal fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun isNewer(remoteTag: String, current: String = AppInfo.VERSION): Boolean {
        val remote = parseVersion(remoteTag) ?: return false
        val local = parseVersion(current) ?: return true
        return compare(remote, local) > 0
    }

    private fun str(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeUnless { it.isJsonNull }?.asString

    /** Выбирает установщик: .exe предпочтительнее .msi, портативный архив и прочее пропускается. */
    internal fun pickAsset(release: JsonObject): JsonObject? {
        val assets = release.getAsJsonArray("assets")?.map { it.asJsonObject }.orEmpty()
        fun named(ext: String) = assets.firstOrNull { str(it, "name")?.endsWith(ext, ignoreCase = true) == true }
        return named(".exe") ?: named(".msi")
    }

    /**
     * Из списка релизов берёт самый новый по номеру версии (бета тоже считается). Черновики пропускаются.
     * Возвращает null, если ничего новее текущей версии нет.
     */
    internal fun pick(releases: JsonArray, current: String = AppInfo.VERSION): UpdateInfo? {
        val best = releases.map { it.asJsonObject }
            .filter { it.get("draft")?.asBoolean != true }
            .mapNotNull { release -> str(release, "tag_name")?.let { tag -> parseVersion(tag)?.let { release to it } } }
            .maxWithOrNull { a, b -> compare(a.second, b.second) }
            ?: return null
        val (release, parsed) = best
        val tag = str(release, "tag_name") ?: return null
        if (!isNewer(tag, current)) return null
        val asset = pickAsset(release)
        return UpdateInfo(
            version = parsed.joinToString("."),
            tag = tag,
            notes = str(release, "body").orEmpty(),
            pageUrl = str(release, "html_url").orEmpty(),
            assetName = asset?.let { str(it, "name") },
            assetUrl = asset?.let { str(it, "browser_download_url") },
            assetSize = asset?.get("size")?.takeUnless { it.isJsonNull }?.asLong ?: -1L,
            prerelease = release.get("prerelease")?.asBoolean == true
        )
    }

    /** Запрашивает релизы репозитория «владелец/имя» и возвращает обновление, если оно есть. */
    fun check(repo: String, current: String = AppInfo.VERSION): UpdateInfo? {
        val clean = repo.trim()
        require(isValidRepo(clean)) { tr("Укажи репозиторий в виде «владелец/имя».") }
        val text = try {
            Net.getText("$apiBase/repos/$clean/releases?per_page=20")
        } catch (e: HttpStatusException) {
            if (e.code == 404) error(tr("Репозиторий {0} не найден или в нём нет релизов.", clean))
            if (e.code == 403) error(tr("GitHub временно отклонил запрос (лимит запросов). Попробуй позже."))
            throw e
        }
        return pick(JsonParser.parseString(text).asJsonArray, current)
    }

    /**
     * Скачивает установщик во временную папку и возвращает файл.
     * Адрес принимается только с GitHub этого репозитория, имя файла очищается от путей.
     */
    fun download(repo: String, info: UpdateInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val url = info.assetUrl ?: error(tr("В релизе нет установщика (.exe или .msi)."))
        val name = File(info.assetName ?: "").name
        require(name.endsWith(".exe", true) || name.endsWith(".msi", true)) { tr("В релизе нет установщика (.exe или .msi).") }
        require(url.startsWith("https://github.com/${repo.trim()}/releases/download/") || url.startsWith("http://127.0.0.1")) {
            tr("Адрес установщика не относится к репозиторию {0}.", repo.trim())
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "MaterialClient-update").apply { mkdirs() }
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        val connection = Net.open(url, 15_000, 60_000)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code, url)
            val total = if (info.assetSize > 0) info.assetSize else connection.contentLengthLong
            var done = 0L
            connection.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        onProgress(done, total)
                    }
                }
            }
            check(part.length() > 0 && (total <= 0 || part.length() == total)) { tr("Установщик скачан не полностью. Попробуй ещё раз.") }
            if (target.exists()) target.delete()
            check(part.renameTo(target)) { tr("Не удалось сохранить установщик.") }
            return target
        } finally {
            connection.disconnect()
            part.delete()
        }
    }
}
