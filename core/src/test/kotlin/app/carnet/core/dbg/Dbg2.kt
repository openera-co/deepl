package app.carnet.core.dbg
import app.carnet.core.bench.FakeTranslator
import app.carnet.core.doc.*
import app.carnet.core.mt.TextTranslator
import app.carnet.core.text.Segmenter
fun main() {
    val runs = listOf(RunText(0, "Ils le font déjà - "), RunText(1, "bientôt ce sera l'herbe"), RunText(0, " !"))
    println(Segmenter.split("Ils le font déjà - bientôt ce sera l'herbe !"))
    val j = RunMapper.Job(runs); RunMapper.run(listOf(j), TextTranslator(FakeTranslator)); println(j.result)
    println(TextTranslator(FakeTranslator).translate("bientôt ce sera l'herbe"))
}
