package app.carnet.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.carnet.core.mt.GlossaryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.store by preferencesDataStore("settings")

data class AppSettings(
    val setupDone: Boolean = false,
    val theme: String = "system",
    val spelling: String = "UK",
    val saveAs: String = "same",
    val bilingual: Boolean = false,
    val autoCapture: Boolean = true,
    val spokenPunctuation: Boolean = true,
    val improveAfterStop: Boolean = true,
    val wifiOnly: Boolean = true,
    val quality: String = "fast",
    val glossary: List<GlossaryEntry> = DEFAULT_GLOSSARY,
    /** "auto", "fr-en" or "en-fr". */
    val direction: String = "auto",
)

val DEFAULT_GLOSSARY = listOf(
    GlossaryEntry("un contrôle", "a test"), GlossaryEntry("un devoir", "homework"), GlossaryEntry("un exposé", "a presentation"),
    GlossaryEntry("le bac", "the baccalaureate"), GlossaryEntry("la terminale", "final year"), GlossaryEntry("un TD", "a tutorial"),
    GlossaryEntry("un cours magistral", "a lecture"),
)

class Settings(private val context: Context) {
    private object K {
        val setup = booleanPreferencesKey("setup")
        val theme = stringPreferencesKey("theme")
        val spelling = stringPreferencesKey("spelling")
        val saveAs = stringPreferencesKey("saveAs")
        val bilingual = booleanPreferencesKey("bilingual")
        val autoCapture = booleanPreferencesKey("autoCapture")
        val punct = booleanPreferencesKey("punct")
        val improve = booleanPreferencesKey("improve")
        val wifi = booleanPreferencesKey("wifi")
        val quality = stringPreferencesKey("quality")
        val glossary = stringPreferencesKey("glossary")
        val direction = stringPreferencesKey("direction")
    }

    val flow: Flow<AppSettings> = context.store.data.map { p -> read(p) }

    private fun read(p: Preferences) = AppSettings(
        setupDone = p[K.setup] ?: false,
        theme = p[K.theme] ?: "system",
        spelling = p[K.spelling] ?: "UK",
        saveAs = p[K.saveAs] ?: "same",
        bilingual = p[K.bilingual] ?: false,
        autoCapture = p[K.autoCapture] ?: true,
        spokenPunctuation = p[K.punct] ?: true,
        improveAfterStop = p[K.improve] ?: true,
        wifiOnly = p[K.wifi] ?: true,
        quality = p[K.quality] ?: "fast",
        glossary = p[K.glossary]?.let { parseGlossary(it) } ?: DEFAULT_GLOSSARY,
        direction = p[K.direction] ?: "auto",
    )

    suspend fun update(f: (AppSettings) -> AppSettings) {
        context.store.edit { p ->
            val s = f(read(p))
            p[K.setup] = s.setupDone; p[K.theme] = s.theme; p[K.spelling] = s.spelling; p[K.saveAs] = s.saveAs
            p[K.bilingual] = s.bilingual; p[K.autoCapture] = s.autoCapture; p[K.punct] = s.spokenPunctuation
            p[K.improve] = s.improveAfterStop; p[K.wifi] = s.wifiOnly; p[K.quality] = s.quality
            p[K.direction] = s.direction
            p[K.glossary] = JSONArray(s.glossary.map { JSONObject().put("s", it.source).put("t", it.target) }).toString()
        }
    }

    private fun parseGlossary(s: String): List<GlossaryEntry> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> GlossaryEntry(o.getString("s"), o.getString("t")) } }
    }.getOrDefault(DEFAULT_GLOSSARY)
}
