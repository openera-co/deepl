package app.carnet.core.text

/**
 * Writes spelled-out French numbers as digits when they precede a unit or time word
 * ("dix-sept heures" → "17 heures"). The translation model handles digits far more reliably:
 * it turned "de huit heures à dix-sept heures" into "8 a.m. to 7 p.m." but "8 heures à 17 heures"
 * correctly into "8 a.m. to 5 p.m.".
 */
object FrenchNumbers {
    private val units = mapOf(
        "zéro" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7,
        "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15,
        "seize" to 16, "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60,
    )
    private val unitWords = listOf(
        "heures?", "h", "minutes?", "min", "secondes?", "degrés?", "°C?", "euros?", "€", "centimes?", "kilomètres?", "km", "mètres?", "m",
        "centimètres?", "cm", "millimètres?", "mm", "kilos?", "kilogrammes?", "kg", "grammes?", "g", "litres?", "l", "millilitres?", "ml",
        "pour\\s+cent", "%", "km/h", "ans", "années", "mois", "jours", "semaines", "siècles", "heures?\\s+et\\s+demie",
    )
    private val numberWord = "(?:zéro|une?|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingts?|trente|quarante|cinquante|soixante|cents?|mille|et)"
    private val pattern = Regex(
        "(?i)(?<![\\p{L}-])($numberWord(?:[\\s-]+$numberWord)*)(?=\\s+(?:${unitWords.joinToString("|")})(?![\\p{L}]))",
    )

    /** Where [normalize] would change [text]: original range → replacement. */
    fun matches(text: String): List<Pair<IntRange, String>> = pattern.findAll(text).mapNotNull { m ->
        val v = parse(m.groupValues[1]) ?: return@mapNotNull null
        if (v <= 1) null else m.range to v.toString()
    }.toList()

    fun normalize(text: String): String = pattern.replace(text) { m ->
        val v = parse(m.groupValues[1]) ?: return@replace m.value
        // Leave "un/une" alone ("une heure" reads naturally and is translated correctly).
        if (v <= 1) m.value else v.toString()
    }

    /** Parses a French number phrase (up to 999 999), or null if it isn't one. */
    fun parse(phrase: String): Int? {
        val words = phrase.lowercase().split(Regex("[\\s-]+")).filter { it.isNotEmpty() && it != "et" }
        if (words.isEmpty()) return null
        var total = 0
        var current = 0
        var i = 0
        while (i < words.size) {
            val w = words[i]
            when {
                w == "mille" -> { total += (if (current == 0) 1 else current) * 1000; current = 0 }
                w == "cent" || w == "cents" -> { current = (if (current == 0) 1 else current) * 100 }
                w == "quatre" && words.getOrNull(i + 1)?.startsWith("vingt") == true -> { current += 80; i++ }
                w == "soixante" && words.getOrNull(i + 1) in setOf("dix", "onze", "douze", "treize", "quatorze", "quinze", "seize") -> { current += 60 + units[words[i + 1]]!!; i++ }
                w == "vingts" -> current += 20
                units.containsKey(w) -> {
                    val v = units[w]!!
                    // "dix-sept", "dix-huit", "dix-neuf"; "quatre-vingt-dix…" handled by accumulation.
                    current += v
                }
                else -> return null
            }
            i++
        }
        return total + current
    }
}
