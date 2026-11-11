package app.carnet.core.text

/**
 * French school vocabulary the general-purpose model gets wrong (found by reviewing its output on
 * school texts). Terms are swapped for their English equivalent before translation; the model
 * keeps English words as they are ("le homework est à rendre" → "the homework is due").
 * Only terms that are unambiguous in a school context are listed.
 */
object SchoolTerms {
    data class Term(val french: String, val english: String)

    val terms = listOf(
        Term("triangle rectangle", "right-angled triangle"), Term("triangles rectangles", "right-angled triangles"),
        Term("coefficient directeur", "gradient"), Term("États généraux", "Estates-General"), Term("Etats généraux", "Estates-General"),
        Term("devoir maison", "homework"), Term("devoirs maison", "homework"), Term("le devoir est à rendre", "the homework is due"),
        Term("les devoirs sont à rendre", "the homework is due"),
        Term("fin du trimestre", "end of term"), Term("fin de trimestre", "end of term"), Term("bon trimestre", "good term"),
        Term("premier trimestre", "first term"), Term("deuxième trimestre", "second term"), Term("second trimestre", "second term"), Term("troisième trimestre", "third term"),
        Term("professeur principal", "form tutor"), Term("professeure principale", "form tutor"), Term("sortie scolaire", "school trip"),
        Term("vie scolaire", "school office"), Term("conseil de classe", "class council"), Term("bulletin scolaire", "school report"),
        Term("carnet de correspondance", "home–school notebook"), Term("cahier de textes", "homework diary"), Term("cahier de texte", "homework diary"),
        Term("travaux dirigés", "tutorial work"), Term("travaux pratiques", "practical work"), Term("fiche de révision", "revision sheet"),
        Term("épreuve écrite", "written exam"), Term("épreuve orale", "oral exam"), Term("épreuves écrites", "written exams"), Term("épreuves orales", "oral exams"),
        Term("participe passé", "past participle"), Term("participes passés", "past participles"), Term("passé simple", "past historic"),
        Term("passé composé", "perfect tense"), Term("plus-que-parfait", "pluperfect"), Term("l'imparfait", "the imperfect tense"),
        Term("subjonctif", "subjunctive"), Term("complément d'objet direct", "direct object"), Term("figures de style", "figures of speech"),
        Term("figure de style", "figure of speech"), Term("chef-d'œuvre", "masterpiece"),
        Term("contrôle de maths", "maths test"), Term("contrôle d'histoire", "history test"), Term("prochain contrôle", "next test"),
        Term("le contrôle aura lieu", "the test will take place"),
        Term("exercices corrigés", "worked exercises"), Term("exercice corrigé", "worked exercise"), Term("corrigé de l'exercice", "answers to the exercise"),
        Term("devoir surveillé", "class test"), Term("devoirs surveillés", "class tests"),
        Term("à retenir", "to remember"), Term("rappel de cours", "lesson summary"), Term("rappels de cours", "lesson summary"),
        Term("classe de seconde", "Year 11 class"), Term("classe de première", "Year 12 class"), Term("classe de terminale", "Year 13 class"),
        Term("en terminale", "in Year 13"), Term("en première", "in Year 12"), Term("en seconde", "in Year 11"),
        Term("hockey sur gazon", "field hockey"), Term("fonction affine", "linear function"), Term("fonctions affines", "linear functions"),
        Term("équation bilan", "overall equation"), Term("équations bilans", "overall equations"), Term("Équation bilan", "Overall equation"),
        Term("dégagement gazeux", "release of gas"), Term("dégagement régulier", "steady release"), Term("dégagement important", "large release"),
        Term("dégagement de dioxygène", "release of oxygen"), Term("dégagement de dihydrogène", "release of hydrogen"),
        Term("Sciences de la Vie et de la Terre", "Biology"), Term("sciences de la vie et de la Terre", "biology"),
        Term("éducation physique et sportive", "PE"), Term("Éducation physique et sportive", "PE"), Term("Histoire-Géographie", "History and Geography"),
        Term("Physique-Chimie", "Physics and Chemistry"), Term("enseignement moral et civique", "moral and civic education"),
        Term("pente de la droite", "slope of the line"), Term("équation de la droite", "equation of the line"),
        Term("équation d'une droite", "equation of a line"), Term("la droite (AB)", "the line (AB)"),
        Term("berline", "sedan"), Term("berlines", "sedans"), Term("contrôle de gestion", "management control"),
    )

    /** Patterns with a captured part kept as is ("Exercice 3" → "Exercise 3"). */
    private val patterns: List<Pair<Regex, String>> = listOf(
        Regex("(?<![\\p{L}])(?i:exercice)s?\\s+(\\d+|[IVX]{1,4}|[A-Z])(?![\\p{L}\\p{N}])") to "Exercise $1",
        // A school subject named alone after a heading colon ("Chapitre 2 : Français" is the subject, not "the French").
        Regex(":\\s*Français\\s*$") to ": French", Regex(":\\s*Mathématiques\\s*$") to ": Maths", Regex(":\\s*Anglais\\s*$") to ": English",
        Regex(":\\s*Histoire-Géographie\\s*$") to ": History and Geography", Regex(":\\s*Physique-Chimie\\s*$") to ": Physics and Chemistry",
        Regex(":\\s*SVT\\s*$") to ": Biology", Regex(":\\s*Philosophie\\s*$") to ": Philosophy", Regex(":\\s*EPS\\s*$") to ": PE",
    )

    private val rules: List<Pair<Regex, String>> = terms.sortedByDescending { it.french.length }.map { t ->
        Regex("(?iu)(?<![\\p{L}\\p{N}])" + Regex.escape(t.french).replace("'", "['’]") + "(?![\\p{L}\\p{N}])") to t.english
    }

    fun matches(text: String): List<Pair<IntRange, String>> =
        rules.flatMap { (re, en) -> re.findAll(text).map { it.range to en }.toList() } +
            patterns.flatMap { (re, en) -> re.findAll(text).map { m -> m.range to en.replace("$1", m.groupValues.getOrElse(1) { "" }) }.toList() }

    fun apply(text: String): String {
        var out = text
        for ((re, en) in rules) out = re.replace(out) { m -> if (m.value.first().isUpperCase()) en.replaceFirstChar { it.uppercase() } else en }
        for ((re, en) in patterns) out = re.replace(out) { m -> en.replace("$1", m.groupValues.getOrElse(1) { "" }) }
        return out
    }
}
