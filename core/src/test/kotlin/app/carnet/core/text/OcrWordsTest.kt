package app.carnet.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class OcrWordsTest {
    private val w = OcrWords(setOf("lundi", "lui", "même", "prochain", "devoir", "Toulon", "le", "est"))

    @Test fun fixesLookAlikes() {
        assertEquals("pour lundi prochain", w.fix("pour Jundi prochain"))
        assertEquals("1 et lui-même", w.fix("1 et tui-même"))
    }

    @Test fun leavesUnknownNamesAndNumbers() {
        assertEquals("Brignoles 83170 Toulon", w.fix("Brignoles 83170 Toulon"))
        assertEquals("CO2 et H2O", w.fix("CO2 et H2O"))
    }
}
