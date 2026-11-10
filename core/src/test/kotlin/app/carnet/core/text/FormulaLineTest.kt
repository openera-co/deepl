package app.carnet.core.text

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormulaLineTest {
    @Test
    fun unitsAreNotFormulas() {
        assertFalse(Segmenter.isFormulaLine("Volume de O₂ (mL/h)"))
        assertFalse(Segmenter.isFormulaLine("Vitesse en km/h"))
    }

    @Test
    fun realFormulas() {
        assertTrue(Segmenter.isFormulaLine("Équation bilan : 6 CO₂ + 6 H₂O → C₆H₁₂O₆"))
    }
}

class FormulaWordsTest {
    @Test
    fun neighbouringWordsStayTogether() {
        val segs = Segmenter.split("Équation bilan : 6 CO₂ + 6 H₂O → C₆H₁₂O₆")
        kotlin.test.assertTrue(segs.any { it.translatable && it.text == "Équation bilan" }, segs.toString())
    }
}
