package dev.materialclient

import java.util.concurrent.ConcurrentHashMap

/**
 * Где хранится выбранный язык. В окне лаунчера это состояние Compose (смена языка сразу перерисовывает
 * экран, см. ComposeLanguageStore в Main.kt), в проверках и консольных запусках — обычное поле.
 */
internal interface LanguageStore {
    var language: String
}

internal class PlainLanguageStore(override var language: String = I18n.RU) : LanguageStore

/**
 * Перевод интерфейса, статусов и ошибок.
 *
 * Русский текст в коде — это и есть ключ: `tr("Не удалось сохранить: {0}", причина)`.
 * На русском возвращается он же, на английском — перевод из [EnglishStrings]. Подстановки пишутся как
 * {0}, {1}, … и в переводе могут идти в другом порядке. Если перевода нет, показывается русский
 * оригинал, а ключ попадает в [missing] и один раз пишется в stderr — так забытую строку видно сразу.
 *
 * Внутренние названия (страницы, цвета, загрузчики), по которым код сравнивает значения, остаются
 * русскими и показываются пользователю только через tr().
 */
internal object I18n {
    const val RU = "ru"
    const val EN = "en"

    val languages = listOf(RU, EN)

    @Volatile
    var store: LanguageStore = PlainLanguageStore()

    /** Код языка: "ru" или "en". Любое другое значение считается русским. */
    var language: String
        get() = store.language
        set(value) {
            store.language = normalize(value)
        }

    val isEnglish: Boolean
        get() = language == EN

    fun normalize(code: String?): String = if (code?.trim()?.lowercase() == EN) EN else RU

    /** Словарь «русский текст → английский». */
    val english: Map<String, String> by lazy { EnglishStrings.all }

    /** Ключи, для которых в английском режиме не нашлось перевода (нужны проверке и отладке). */
    val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val cyrillic = Regex("[А-Яа-яЁё]")

    /** Текст без русских букв (названия загрузчиков, цвет #RRGGBB, бренд) переводить не нужно. */
    private fun reportMissing(ru: String) {
        if (cyrillic.containsMatchIn(ru) && missing.add(ru)) System.err.println("[i18n] нет английского перевода: $ru")
    }

    /** Перевод без подстановок. [lang] — язык, по умолчанию текущий. */
    fun translate(ru: String, lang: String = language): String {
        if (lang != EN) return ru
        val found = english[ru]
        if (found == null) reportMissing(ru)
        return found ?: ru
    }

    /** Подставляет {0}, {1}, … в одном проходе: текст самих значений повторно не разбирается. */
    fun format(template: String, args: Array<out Any?>): String {
        if (args.isEmpty() || template.indexOf('{') < 0) return template
        val out = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val end = template.indexOf('}', i + 1)
                val index = if (end > i + 1) template.substring(i + 1, end).toIntOrNull() else null
                if (index != null && index in args.indices) {
                    out.append(args[index].toString())
                    i = end + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    fun t(ru: String, vararg args: Any?): String = format(translate(ru), args)

    // --- Проверки по началу и точному тексту сообщения ---------------------------------------
    // Статусы хранятся строками, а цвет (ошибка или обычный текст) выбирается по их началу. Сообщение
    // могло быть создано до смены языка, поэтому проверяем сразу оба варианта.

    /** Русский ключ и его перевод для сравнения: оба варианта текста. */
    private fun variants(ruKey: String): List<String> {
        val en = english[ruKey]
        if (en == null) reportMissing(ruKey)
        return if (en == null || en == ruKey) listOf(ruKey) else listOf(ruKey, en)
    }

    /** Сообщение начинается с этого текста на русском или английском. */
    fun startsWithKey(message: String, ruKey: String): Boolean = variants(ruKey).any { message.startsWith(it) }

    /** Сообщение целиком совпадает с этим текстом на русском или английском. */
    fun isKey(message: String, ruKey: String): Boolean = variants(ruKey).any { message == it }

    /**
     * Если сообщение — один из этих постоянных текстов (на любом языке), возвращает его на текущем языке,
     * иначе null. Нужно, чтобы при смене языка обновить статус, который успел вывести один из них.
     */
    fun retranslate(message: String, vararg ruKeys: String): String? =
        ruKeys.firstOrNull { isKey(message, it) }?.let { translate(it) }

    /**
     * Начала сообщений, которые считаются проблемой и показываются красным. У каждого английского
     * начала в [EnglishStrings] должен быть такой же перевод у всех ключей, начинающихся с русского
     * начала (это проверяется тестом), поэтому достаточно сравнить начало текста.
     */
    val problemPrefixes: List<String> = listOf(
        "Ошибка",
        "Не удалось",
        "Файл не найден",
        "Ник должен",
        "Укажи",
    )

    fun isProblem(message: String): Boolean = problemPrefixes.any { startsWithKey(message, it) }

    /**
     * Тексты, которые переводятся по значению переменной (название страницы, цвета, источника журнала,
     * фильтра). В коде они стоят обычными строками-идентификаторами, а на экран выводятся через tr(x),
     * поэтому проверка полноты словаря берёт их отсюда.
     */
    val dynamicKeys: List<String> = listOf(
        "Экземпляры", "Каталог модов", "Установленные моды", "Журнал игры", "Настройки",
        "Бирюзовый", "Фиолетовый", "Синий", "Розовый", "Оранжевый", "Свой цвет",
        "Все",
        "Запуск", "Краш-репорт",
        "Ошибка: Minecraft завершился",
        "Внешний вид", "О лаунчере", "Java и память", "Сеть", "Скин", "Моды", "Ресурспаки", "Шейдеры",
        "В порядке создания", "Недавно играл", "По названию", "По версии Minecraft",
    ) + problemPrefixes
}

/** Перевод строки на текущий язык. Русский текст — ключ, {0}, {1}, … — подстановки. */
internal fun tr(ru: String, vararg args: Any?): String = I18n.t(ru, *args)
