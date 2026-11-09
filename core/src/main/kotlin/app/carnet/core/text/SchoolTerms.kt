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
    )

    private val rules: List<Pair<Regex, String>> = terms.sortedByDescending { it.french.length }.map { t ->
        Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(t.french).replace("'", "['’]") + "(?![\\p{L}\\p{N}])") to t.english
    }

    fun matches(text: String): List<Pair<IntRange, String>> = rules.flatMap { (re, en) -> re.findAll(text).map { it.range to en }.toList() }

    fun apply(text: String): String {
        var out = text
        for ((re, en) in rules) out = re.replace(out) { m -> if (m.value.first().isUpperCase()) en.replaceFirstChar { it.uppercase() } else en }
        return out
    }
}
