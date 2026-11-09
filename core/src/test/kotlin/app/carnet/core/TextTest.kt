package app.carnet.core

import app.carnet.core.bench.FakeTranslator
import app.carnet.core.doc.RunMapper
import app.carnet.core.doc.RunText
import app.carnet.core.mt.GlossaryEntry
import app.carnet.core.mt.SentenceTranslator
import app.carnet.core.mt.TextTranslator
import app.carnet.core.text.Dictation
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
        assertEquals(listOf("Demain, a test de maths.", "Le tutorial est annulé.", "Le TDA aussi."), seen)
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
}
