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
