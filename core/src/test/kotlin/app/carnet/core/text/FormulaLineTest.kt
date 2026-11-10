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

class AddressGuardTest {
    @Test
    fun emailsAndUrlsAreCopied() {
        val tr = app.carnet.core.mt.TextTranslator({ s, _ -> s.map { it.replace("greffe", "registry").replace("ta-toulon", "TA-Toulon") } })
        val out = tr.translate("Email : greffe.ta-toulon@juradm.fr et le site www.telerecours.fr pour en savoir plus.")
        kotlin.test.assertTrue("greffe.ta-toulon@juradm.fr" in out, out)
        kotlin.test.assertTrue("www.telerecours.fr" in out, out)
    }
}
