package app.carnet.core.text

/** US ↔ UK spelling for the most common school vocabulary. Case is preserved. */
object Spelling {
    private val usToUk = mapOf(
        "color" to "colour", "colors" to "colours", "colored" to "coloured", "favorite" to "favourite", "favorites" to "favourites",
        "behavior" to "behaviour", "behaviors" to "behaviours", "neighbor" to "neighbour", "neighbors" to "neighbours", "neighborhood" to "neighbourhood",
        "honor" to "honour", "honors" to "honours", "humor" to "humour", "labor" to "labour", "flavor" to "flavour", "harbor" to "harbour",
        "rumor" to "rumour", "vapor" to "vapour", "center" to "centre", "centers" to "centres", "centered" to "centred", "meter" to "metre", "meters" to "metres",
        "kilometer" to "kilometre", "kilometers" to "kilometres", "centimeter" to "centimetre", "centimeters" to "centimetres", "millimeter" to "millimetre",
        "millimeters" to "millimetres", "liter" to "litre", "liters" to "litres", "theater" to "theatre", "theaters" to "theatres", "fiber" to "fibre",
        "organize" to "organise", "organized" to "organised", "organization" to "organisation", "organizations" to "organisations", "realize" to "realise",
        "realized" to "realised", "recognize" to "recognise", "recognized" to "recognised", "analyze" to "analyse", "analyzed" to "analysed",
        "summarize" to "summarise", "memorize" to "memorise", "apologize" to "apologise", "civilization" to "civilisation", "civilizations" to "civilisations",
        "colonization" to "colonisation", "industrialization" to "industrialisation", "globalization" to "globalisation", "urbanization" to "urbanisation",
        "modernization" to "modernisation", "characterize" to "characterise", "characterized" to "characterised", "emphasize" to "emphasise",
        "criticize" to "criticise", "minimize" to "minimise", "maximize" to "maximise", "symbolize" to "symbolise", "symbolizes" to "symbolises",
        "defense" to "defence", "offense" to "offence", "license" to "licence", "practice" to "practise", "catalog" to "catalogue", "dialog" to "dialogue",
        "program" to "programme", "programs" to "programmes", "traveling" to "travelling", "traveled" to "travelled", "canceled" to "cancelled",
        "modeling" to "modelling", "labeled" to "labelled", "fulfill" to "fulfil", "enroll" to "enrol", "skillful" to "skilful", "gray" to "grey",
        "aluminum" to "aluminium", "sulfur" to "sulphur", "fetus" to "foetus", "pediatric" to "paediatric", "anemia" to "anaemia", "esophagus" to "oesophagus",
        "estrogen" to "oestrogen", "maneuver" to "manoeuvre", "mold" to "mould", "plow" to "plough", "math" to "maths", "jewelry" to "jewellery",
    )
    private val ukToUs = usToUk.entries.associate { (k, v) -> v to k }.filterKeys { it != "practise" && it != "programme" }

    private val word = Regex("[A-Za-z]+")

    fun apply(text: String, variant: String): String {
        val map = if (variant.equals("UK", true) || variant.equals("en-GB", true)) usToUk else ukToUs
        return word.replace(text) { m ->
            val w = m.value
            val r = map[w.lowercase()] ?: return@replace w
            when {
                w.all { it.isUpperCase() } -> r.uppercase()
                w.first().isUpperCase() -> r.replaceFirstChar { it.uppercase() }
                else -> r
            }
        }
    }
}
