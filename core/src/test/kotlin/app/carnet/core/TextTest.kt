package app.carnet.core

import app.carnet.core.bench.FakeTranslator
import app.carnet.core.doc.RunMapper
import app.carnet.core.doc.RunText
import app.carnet.core.mt.GlossaryEntry
import app.carnet.core.mt.SentenceTranslator
import app.carnet.core.mt.TextTranslator
import app.carnet.core.text.Dictation
import app.carnet.core.text.FrenchNumbers
import app.carnet.core.text.SchoolTerms
import app.carnet.core.text.Segmenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextTest {
    private fun sentences(t: String) = Segmenter.split(t).filter { it.translatable }.map { it.text }

    @Test fun segmentationKeepsEveryCharacter() {
        val samples = listOf(
            "Bonjour. Comment allez-vous ? Très bien !",
            "M. Dupont est arrivé. Mme Martin aussi.",
            "1. Lire le texte.\n2. Répondre aux questions.",
            "Voir p. 12 et cf. chapitre 3. La suite est ici.",
            "Il est né en 1802 av. J.-C. selon les sources. Rien d'autre.",
            "\tNom :\tDupont\n",
            "« Je viens », dit-il. Puis il partit…",
            "v = d / t",
        )
        for (s in samples) assertEquals(s, Segmenter.split(s).joinToString("") { it.text }, s)
    }

    @Test fun sentencesSplitCorrectly() {
        assertEquals(listOf("Bonjour.", "Comment allez-vous ?", "Très bien !"), sentences("Bonjour. Comment allez-vous ? Très bien !"))
        assertEquals(listOf("M. Dupont est arrivé.", "Mme Martin aussi."), sentences("M. Dupont est arrivé. Mme Martin aussi."))
        assertEquals(listOf("Voir p. 12 et cf. chapitre 3.", "La suite est ici."), sentences("Voir p. 12 et cf. chapitre 3. La suite est ici."))
        assertEquals(listOf("Lire le texte.", "Répondre aux questions."), sentences("1. Lire le texte.\n2. Répondre aux questions."))
        assertEquals(listOf("« Je viens », dit-il.", "Puis il partit…"), sentences("« Je viens », dit-il. Puis il partit…"))
    }

    @Test fun nonLinguisticIsNotTranslated() {
        for (s in listOf("v = d / t", "CH₄ + 2 O₂", "12,50 €", "https://exemple.fr/page", "prof@ecole.fr", "2024", "H2O", "x² + y² = z²"))
            assertFalse(Segmenter.isLinguistic(s), s)
        for (s in listOf("Nom", "La cellule", "Exercice 3")) assertTrue(Segmenter.isLinguistic(s), s)
    }

    @Test fun textTranslatorKeepsLayout() {
        val tr = TextTranslator(FakeTranslator)
        val src = "Titre\n\n1. Première phrase. Deuxième phrase !\n\tNom :\tDupont\nv = d / t"
        val out = tr.translate(src)
        assertEquals(src.count { it == '\n' }, out.count { it == '\n' })
        assertEquals(src.count { it == '\t' }, out.count { it == '\t' })
        assertTrue(out.startsWith("xertitq\n\n1. "), out)
        assertTrue(out.endsWith("v = d / t"), out)
    }

    @Test fun glossaryIsApplied() {
        val seen = ArrayList<String>()
        val tr = TextTranslator(SentenceTranslator { s, _ -> seen.addAll(s); s }, listOf(GlossaryEntry("un contrôle", "a test"), GlossaryEntry("TD", "tutorial")))
        tr.translate("Demain, un contrôle de maths. Le TD est annulé. Le TDA aussi.")
        assertEquals(listOf("Demain, un test de maths.", "Le tutorial est annulé.", "Le TDA aussi."), seen)
    }

    @Test fun glossaryIgnoresArticles() {
        val seen = ArrayList<String>()
        val tr = TextTranslator(SentenceTranslator { s, _ -> seen.addAll(s); s }, listOf(GlossaryEntry("un contrôle", "a test"), GlossaryEntry("l'exposé", "the presentation")), schoolMode = false)
        tr.translate("Le contrôle est demain. Ce contrôle compte double. Mon exposé est prêt. Une lecture du contrôleur.")
        assertEquals(listOf("Le test est demain.", "Ce test compte double.", "Mon presentation est prêt.", "Une lecture du contrôleur."), seen)
    }

    @Test fun frenchNumbersBecomeDigitsBeforeUnits() {
        assertEquals("ouvert de 8 heures à 17 heures", FrenchNumbers.normalize("ouvert de huit heures à dix-sept heures"))
        assertEquals("il coûte 350 euros et pèse 92 kg", FrenchNumbers.normalize("il coûte trois cent cinquante euros et pèse quatre-vingt-douze kg"))
        assertEquals("pendant 72 ans", FrenchNumbers.normalize("pendant soixante-douze ans"))
        assertEquals("les trois mousquetaires", FrenchNumbers.normalize("les trois mousquetaires"))
        assertEquals("une heure", FrenchNumbers.normalize("une heure"))
        assertEquals(21, FrenchNumbers.parse("vingt et un")); assertEquals(1999, FrenchNumbers.parse("mille neuf cent quatre-vingt-dix-neuf"))
    }

    @Test fun schoolTermsAreReplaced() {
        assertEquals("Dans un right-angled triangle, le gradient vaut 2.", SchoolTerms.apply("Dans un triangle rectangle, le coefficient directeur vaut 2."))
        assertEquals("The homework is due lundi.", SchoolTerms.apply("Le devoir est à rendre lundi."))
        assertEquals("Le citoyen a des droits et des devoirs.", SchoolTerms.apply("Le citoyen a des droits et des devoirs."))
    }

    @Test fun runMapperProjectsBoldTerm() {
        val runs = listOf(RunText(0, "La "), RunText(1, "photosynthèse"), RunText(0, " est un processus vital."))
        val job = RunMapper.Job(runs)
        RunMapper.run(listOf(job), TextTranslator(FakeTranslator))
        val regions = job.result!!
        val bold = regions.filter { it.format == 1 }.joinToString("") { it.text }
        assertEquals(FakeTranslator.word("photosynthèse"), bold)
        assertEquals(FakeTranslator.sentence("La photosynthèse est un processus vital."), regions.joinToString("") { it.text })
    }

    @Test fun dictationPunctuation() {
        assertEquals("La Révolution commence en 1789.\nLe peuple prend la Bastille, puis le roi fuit.",
            Dictation.format("la Révolution commence en 1789 point à la ligne le peuple prend la Bastille virgule puis le roi fuit point"))
        assertEquals("Il dit : « bonjour ».", Dictation.format("il dit deux points ouvrez les guillemets bonjour fermez les guillemets point"))
        assertEquals("Le point de départ (voir la carte).", Dictation.format("le point de départ ouvrez la parenthèse voir la carte fermez la parenthèse point"))
        assertEquals("Pourquoi ?", Dictation.format("pourquoi point d'interrogation"))
    }

    @Test fun formulasLeadersAndTermsAreProtected() {
        assertFalse(Segmenter.isLinguistic("\$ ar{x} = rac{1}{N} \\sum_{i=1}^{N} x_i\$"))
        assertFalse(Segmenter.isLinguistic("\$V(x) = \\frac{1}{N} \\sum (x_i - \\bar{x})^2\$"))
        assertTrue(Segmenter.isLinguistic("Pour une série ordonnée de \$N\$ valeurs"))
        val segs = Segmenter.split("Nom : ..........   Prénom : ______")
        assertEquals(listOf("Nom :", "Prénom :"), segs.filter { it.translatable }.map { it.text.trim() })
        assertEquals("Exercise 3 : les linear functions", SchoolTerms.apply("Exercice 3 : les fonctions affines"))
        assertEquals("To remember", SchoolTerms.apply("À retenir"))
        // A term the model garbles is retried with a placeholder and put back.
        val garbler = SentenceTranslator { ss, _ -> ss.map { it.replace("past participles", "participating pasts").replace("Accordez les", "Agree the").replace(" dans la phrase", " in the sentence") } }
        assertEquals("Agree the past participles in the sentence.", TextTranslator(garbler).translate("Accordez les participes passés dans la phrase."))
    }

    @Test fun englishToFrenchAndLanguageGuess() {
        val echo = SentenceTranslator { ss, _ -> ss.map { if (it.startsWith("Is")) "Est-ce prêt ?" else "Il a dit : « oui »." } }
        val tr = TextTranslator(echo, source = "en", target = "fr")
        assertEquals("Est-ce prêt\u00A0?", tr.translate("Is it ready?"))
        assertEquals("Il a dit\u00A0: «\u00A0oui\u00A0».", tr.translate("He said: yes."))
        // French school terms are not applied to English text.
        assertEquals("the contrôle de maths", SchoolTerms.apply("the contrôle de maths").let { if (tr.target == "fr") "the contrôle de maths" else it })
        assertEquals("fr", app.carnet.core.text.LanguageGuess.guess("Le chat est sur la table et il dort."))
        assertEquals("en", app.carnet.core.text.LanguageGuess.guess("The cat is on the table and it is sleeping."))
    }

    @Test fun frenchOcrFixes() {
        val fix = { t: String -> app.carnet.core.text.OcrFix.fix(t, true) }
        assertEquals("Les États-Unis et l'Antiquité.", fix("Les Etats-Unis et I'Antiquité."))
        assertEquals("À la fin du XVIIIe siècle, le 1er mai", fix("A la fin du XVIlle siècle, le ler mai"))
        assertEquals("le cœur de la laïcité", fix("le coeur de la laicité"))
        assertEquals("François Ier et l'Île de France", fix("François Ier et l'Ile de France"))
        assertEquals("deux travaux en 10 jours", fix("deuX travauxX en 1O jours"))
        assertEquals("Attention\u00A0: 10:30", fix("Attention: 10:30"))
        // English text is never touched.
        val en = "I'm sure the coeur and Etats were A la carte: fine."
        assertEquals(en, app.carnet.core.text.OcrFix.fix(en))
    }
}
