package dev.materialclient

import java.io.File
import java.lang.management.ManagementFactory
import java.util.Properties

/**
 * Общие параметры запуска игры: автоматическая загрузка Java, память и JVM-аргументы по умолчанию.
 * Лежат в том же файле настроек, что тема и путь к Java, поэтому каждое значение обновляется отдельно.
 */
internal object GameSettings {
    private val file: File
        get() = File(System.getProperty("user.home"), ".material-client/settings.properties")

    private fun read(): Properties = try {
        Properties().also { props -> if (file.isFile) file.inputStream().use { props.load(it) } }
    } catch (_: Exception) {
        Properties()
    }

    private fun write(key: String, value: String) {
        val props = read()
        props.setProperty(key, value)
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "Material Client settings") }
    }

    /** Путь к Java из настроек. Пустая строка — выбирать Java автоматически. */
    val javaPath: String
        get() = read().getProperty("javaPath", "")

    fun saveJavaPath(path: String) = write("javaPath", path.trim())

    /** Скачивать подходящую Java, если на компьютере её нет. По умолчанию включено. */
    val autoJava: Boolean
        get() = read().getProperty("autoJava", "true") != "false"

    fun saveAutoJava(value: Boolean) = write("autoJava", value.toString())

    /** Память игры по умолчанию, МБ. 0 — подобрать автоматически. */
    val memoryMb: Int
        get() = read().getProperty("memoryMb", "0").trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

    fun saveMemoryMb(value: Int) = write("memoryMb", value.toString())

    /** Дополнительные JVM-аргументы для всех экземпляров. */
    val jvmArgs: String
        get() = read().getProperty("jvmArgs", "")

    fun saveJvmArgs(value: String) = write("jvmArgs", value.trim())

    /** Язык интерфейса: "ru" (по умолчанию) или "en". */
    val language: String
        get() = I18n.normalize(read().getProperty("language"))

    fun saveLanguage(code: String) = write("language", I18n.normalize(code))

    /** Порядок экземпляров в библиотеке: added, recent, name или version. */
    val instanceSort: String
        get() = read().getProperty("instanceSort", "added")

    fun saveInstanceSort(mode: String) = write("instanceSort", mode)

    /** Репозиторий GitHub «владелец/имя», где лежат релизы лаунчера. Пусто — обновления не проверяются. */
    val updateRepo: String
        get() = read().getProperty("updateRepo", "").trim()

    fun saveUpdateRepo(value: String) = write("updateRepo", value.trim())

    /** Проверять обновления при запуске. По умолчанию включено. */
    val autoUpdateCheck: Boolean
        get() = read().getProperty("autoUpdateCheck", "true") != "false"

    fun saveAutoUpdateCheck(value: Boolean) = write("autoUpdateCheck", value.toString())
}

/** Разбор и проверка строки JVM-аргументов. */
internal object JvmArgs {
    /** Делит строку по пробелам; кавычки `"` и `'` объединяют части с пробелами в один аргумент. */
    fun parse(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var started = false
        for (ch in text) {
            when {
                quote != null -> if (ch == quote) quote = null else current.append(ch)
                ch == '"' || ch == '\'' -> { quote = ch; started = true }
                ch.isWhitespace() -> if (started) { result += current.toString(); current.setLength(0); started = false }
                else -> { current.append(ch); started = true }
            }
        }
        if (started) result += current.toString()
        return result
    }

    /** Текст ошибки или null, если строка корректна (или пуста). */
    fun validate(text: String): String? {
        if (text.isBlank()) return null
        var quotes = 0
        var single = 0
        for (ch in text) {
            if (ch == '"') quotes++
            if (ch == '\'') single++
        }
        if (quotes % 2 != 0 || single % 2 != 0) return tr("В JVM-аргументах не закрыта кавычка.")
        val bad = parse(text).firstOrNull { !it.startsWith("-") }
        if (bad != null) {
            return tr("Каждый JVM-аргумент должен начинаться с «-» (нашлось «{0}»). Параметры вида --add-opens пиши через «=», например --add-opens=java.base/java.lang=ALL-UNNAMED.", bad)
        }
        val forbidden = parse(text).firstOrNull { it == "-jar" || it == "-cp" || it == "-classpath" || it == "--class-path" }
        if (forbidden != null) return tr("Аргумент {0} задаёт сам лаунчер, убери его.", forbidden)
        return null
    }
}

/** Память игры: автоподбор, проверка введённого значения и итоговый аргумент -Xmx. */
internal object GameMemory {
    const val MIN_MB = 512

    /** Объём оперативной памяти компьютера, МБ, или null, если определить не удалось. */
    fun totalMb(): Long? = try {
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize?.takeIf { it > 0 }?.div(1024 * 1024)
    } catch (_: Throwable) {
        null
    }

    /** Верхняя граница, которую разрешаем вводить: вся память компьютера (или 64 ГБ, если она неизвестна). */
    fun maxMb(): Int = (totalMb() ?: 65_536L).coerceIn(MIN_MB.toLong(), 1_048_576L).toInt()

    /**
     * Автоматический объём: для чистой игры хватает 2–4 ГБ, с модами нужно больше. Не больше половины
     * оперативной памяти, чтобы система не уходила в подкачку.
     */
    fun autoMb(loader: String, total: Long? = totalMb()): Int {
        val modded = !loader.equals("Vanilla", ignoreCase = true)
        val ram = total ?: 8192L
        val wanted = if (modded) (ram / 3).coerceIn(2048L, 6144L) else (ram / 4).coerceIn(2048L, 4096L)
        val capped = minOf(wanted, ram / 2).coerceAtLeast(1024L)
        return (capped / 256 * 256).toInt()
    }

    /** Текст ошибки или null, если значение корректно. Пустое значение означает «как в общих настройках». */
    fun validate(text: String): String? {
        if (text.isBlank()) return null
        val value = text.trim().toIntOrNull()
            ?: return tr("Память указывай числом в мегабайтах, например 4096.")
        val max = maxMb()
        if (value != 0 && (value < MIN_MB || value > max)) return tr("Память должна быть от {0} до {1} МБ.", MIN_MB, max)
        return null
    }

    /** Значение 0 или пустая строка превращается в 0 («не задано»). */
    fun parse(text: String): Int = text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

    /** Сколько памяти получит игра: значение экземпляра, иначе общее, иначе автоматическое. */
    fun effectiveMb(instanceMb: Int, loader: String, globalMb: Int = GameSettings.memoryMb): Int =
        if (instanceMb > 0) instanceMb else if (globalMb > 0) globalMb else autoMb(loader)
}
