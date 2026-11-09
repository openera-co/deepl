package app.carnet.core.text

/**
 * Corrects the systematic mistakes the on-device recognizer makes on French text (measured on the
 * OCR benchmark, tools/ocr): accents dropped on capitals ("Etats", "A la"), "l'" read as "I'",
 * "1er" read as "ler", Roman numerals with l for I ("XVIlle"), œ written "oe", ï read as i, and
 * French spacing lost before : ; ! ?. Only applied to French text.
 */
object OcrFix {
    /** Capitalised words that always take an accent on their capital in French. */
    private val capitals = listOf(
        "Etat", "Etats", "Etats-Unis", "Ecole", "Ecoles", "Eglise", "Eglises", "Egypte", "Egyptiens", "Etude", "Etudes",
        "Etudiant", "Etudiants", "Elève", "Elèves", "Eleve", "Eleves", "Economie", "Energie", "Equateur", "Equation", "Equations", "Epoque",
        "Evolution", "Edition", "Education", "Election", "Elections", "Electricité", "Electron", "Electrons", "Elément",
        "Eléments", "Etape", "Etapes", "Evaluation", "Evénement", "Evénements", "Eté", "Etoile", "Etoiles", "Ecrire", "Ecrivez",
        "Ecris", "Ecriture", "Ecoutez", "Ecoute", "Etablissement", "Egalité", "Egalement", "Evidemment", "Etre", "Age", "Ages", "Ame", "Ile", "Iles",
        "Ilot", "Ecosse", "Equipe", "Equipes", "Ethique", "Eviter", "Evitez", "Ecart", "Echelle", "Echange", "Echanges", "Eclairage",
    )
    private val accentedCapital = mapOf('E' to 'É', 'A' to 'Â', 'I' to 'Î')
    private val capitalFixes: Map<String, String> = capitals.associateWith { w ->
        val first = w[0]
        val fixed = when {
            w.startsWith("Age") || w.startsWith("Ame") -> "Â" + w.substring(1)
            w.startsWith("Ile") || w.startsWith("Ilot") -> "Î" + w.substring(1)
            else -> (accentedCapital[first] ?: first) + w.substring(1)
        }
        if (w.startsWith("Eleve")) fixed.replace("Eleve", "Élève") else fixed
    }

    private val oe = mapOf("coeur" to "cœur", "coeurs" to "cœurs", "soeur" to "sœur", "soeurs" to "sœurs", "oeuvre" to "œuvre", "oeuvres" to "œuvres",
        "oeil" to "œil", "voeu" to "vœu", "voeux" to "vœux", "noeud" to "nœud", "noeuds" to "nœuds", "oeuf" to "œuf", "oeufs" to "œufs",
        "boeuf" to "bœuf", "manoeuvre" to "manœuvre", "moeurs" to "mœurs", "choeur" to "chœur", "foetus" to "fœtus", "oesophage" to "œsophage",
        "Oeuvre" to "Œuvre", "Oeuvres" to "Œuvres", "Coeur" to "Cœur", "Soeur" to "Sœur", "Oeil" to "Œil", "rancoeur" to "rancœur", "écoeurant" to "écœurant")
    private val trema = mapOf("laicité" to "laïcité", "laique" to "laïque", "laiques" to "laïques", "mais" to null, "mosaique" to "mosaïque", "naif" to "naïf",
        "naive" to "naïve", "heroique" to "héroïque", "egoiste" to "égoïste", "egoisme" to "égoïsme", "archaique" to "archaïque", "stoique" to "stoïque",
        "coincidence" to "coïncidence", "Noel" to "Noël", "Israel" to "Israël", "aieul" to "aïeul", "aieux" to "aïeux", "ambigue" to "ambiguë",
        "egoïste" to null, "hebraique" to "hébraïque", "judaique" to "judaïque", "prosaique" to "prosaïque", "caraibes" to "caraïbes", "Caraibes" to "Caraïbes",
        "ouie" to "ouïe", "inoui" to "inouï", "inouie" to "inouïe", "Hawai" to "Hawaï", "Taiwan" to null).filterValues { it != null }.mapValues { it.value!! }

    private val word = Regex("[\\p{L}][\\p{L}-]*")
    private val elisionStrict = Regex("(?<![\\p{L}])I(['’])(?=[aeiouyhàâéèêëîïôûAEIOUYHÀÂÉÈÊÎÔÛ])")
    // Lowercase l only: "Ier" is a real Roman numeral ("François Ier").
    private val ordinal = Regex("(?<![\\p{L}\\p{N}])(l)(er|re|ère|ers|res)(?![\\p{L}])")
    private val roman = Regex("(?<![\\p{L}])([IVXLC]*[IVX])([Il1]{1,3})(e|er|ème)(?![\\p{L}])")
    private val aPrep = Regex("(?<![\\p{L}])A (?=(la|l['’]|partir|cause|travers|propos|nouveau|ce |cet|cette|ces|chaque|savoir|condition|moins|peine|côté|force|mesure|part|présent|tout|toute|tous|leur|leurs|son|sa|ses|mon|ma|mes|ton|ta|tes|votre|vos|notre|nos|quel|quelle|quoi|qui|lui|eux|elle|elles|l'inverse|l’inverse|titre|travers|bientôt|demain|plus|droite|gauche|retenir|faire|noter|rendre|vous|toi|moi)\\b)")
    /** Diacritics French doesn't use, read instead of the French ones (brůle, cộtés, Äge, Á). */
    private val foreignMarks = mapOf('á' to 'à', 'Á' to 'À', 'ů' to 'û', 'ű' to 'û', 'ú' to 'û', 'ő' to 'ô', 'ộ' to 'ô', 'ố' to 'ô', 'ồ' to 'ô', 'ó' to 'ô',
        'ò' to 'ô', 'ǒ' to 'ô', 'Ä' to 'Â', 'ǎ' to 'â', 'ạ' to 'a', 'ě' to 'ê', 'ẹ' to 'é', 'ệ' to 'ê', 'ế' to 'ê', 'ề' to 'ê', 'í' to 'î', 'ì' to 'î', 'ị' to 'i', 'ǐ' to 'î')
    private val strayX = Regex("(?<=[a-zà-ÿ]{2})X(?![\\p{L}])")
    private val letterO = Regex("(?<=\\d)[Oo](?=\\d|\\b)|(?<![\\p{L}])[Oo](?=\\d)")
    private val frenchSpace = Regex("(?<=[\\p{L}\\p{N})»\\]])([:;!?])(?=\\s|$)")

    /** Fixes [text] if it is French ([french] = null decides by itself). */
    fun fix(text: String, french: Boolean? = null): String {
        val fr = french ?: (LanguageGuess.guess(text) == "fr")
        if (!fr || text.isBlank()) return text
        var t = text
        t = buildString(t.length) { for (c in t) append(foreignMarks[c] ?: c) }
        // "travauxX" → "travaux", "deuX" → "deux"
        t = Regex("(?<=[a-zà-ÿ])xX(?![\\p{L}])").replace(t, "x")
        t = strayX.replace(t, "x")
        t = letterO.replace(t, "0")
        t = elisionStrict.replace(t) { "l" + it.groupValues[1] }
        t = ordinal.replace(t) { "1" + it.groupValues[2] }
        // At least two numeral letters before the l's, so "Ile" (island) stays a word.
        t = roman.replace(t) { m -> if (m.groupValues[1].length < 2) m.value else m.groupValues[1] + "I".repeat(m.groupValues[2].length) + m.groupValues[3] }
        t = aPrep.replace(t, "À ")
        t = word.replace(t) { m ->
            val w = m.value
            capitalFixes[w] ?: oe[w] ?: trema[w] ?: w.split('-').let { parts ->
                if (parts.size > 1 && parts.any { capitalFixes.containsKey(it) }) parts.joinToString("-") { capitalFixes[it] ?: it } else w
            }
        }
        t = frenchSpace.replace(t) { " " + it.value }
        return t
    }
}
