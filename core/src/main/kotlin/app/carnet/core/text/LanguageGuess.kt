package app.carnet.core.text

/** Tells French from English text (for "auto" direction): function words and French accents. */
object LanguageGuess {
    private val fr = setOf("le", "la", "les", "des", "du", "de", "un", "une", "et", "est", "sont", "dans", "pour", "par", "sur", "avec",
        "qui", "que", "pas", "plus", "aux", "au", "ces", "cette", "ce", "nous", "vous", "ils", "elles", "leur", "été", "être", "mais", "où",
        "dont", "très", "il", "elle", "on", "ne", "se", "en", "à", "son", "sa", "ses", "chez", "entre", "comme", "aussi", "c'est", "l", "d", "qu")
    private val en = setOf("the", "of", "and", "to", "in", "is", "are", "for", "with", "that", "this", "on", "by", "as", "be", "was", "were",
        "from", "at", "an", "or", "which", "it", "its", "has", "have", "had", "not", "but", "they", "their", "we", "you", "he", "she", "will",
        "would", "can", "there", "these", "those", "been", "into", "than", "what", "when", "who", "how", "a", "i")
    private val word = Regex("[\\p{L}]+")

    /** "fr", "en", or null when there is too little text to tell. */
    fun guess(text: String): String? {
        var f = 0.0; var e = 0.0; var n = 0
        for (m in word.findAll(text.lowercase())) {
            val w = m.value; n++
            if (w in fr) f += 1.0
            if (w in en) e += 1.0
            if (w.any { it in "éèêàùâîôûçëïœ" }) f += 0.5
            if (n > 4000) break
        }
        if (f + e < 2) return null
        return if (f >= e) "fr" else "en"
    }
}
