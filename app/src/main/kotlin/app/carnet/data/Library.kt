package app.carnet.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ItemType { TEXT, DOCUMENT, SCAN, RECORDING, CONVERSION }

/**
 * One saved result. Files live in filesDir/library/<id>/ (source.*, result.*, extra files).
 * [meta] holds type-specific values (page count, duration, text…).
 */
data class LibraryItem(
    val id: String,
    val type: ItemType,
    val title: String,
    val created: Long,
    val sourceName: String? = null,
    val resultName: String? = null,
    val meta: Map<String, String> = emptyMap(),
) {
    val badge: String get() = when (type) {
        ItemType.TEXT -> "TEXT"
        ItemType.SCAN -> "SCAN"
        ItemType.RECORDING -> "REC"
        else -> (resultName ?: sourceName)?.substringAfterLast('.', "")?.uppercase()?.ifEmpty { "FILE" } ?: "FILE"
    }
}

class Library(private val context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    private val index = File(root, "index.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<LibraryItem>> = _items

    fun dir(id: String) = File(root, id).apply { mkdirs() }
    fun file(item: LibraryItem, name: String?) = name?.let { File(dir(item.id), it) }
    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().take(12)

    suspend fun put(item: LibraryItem) = withContext(Dispatchers.IO) {
        synchronized(this@Library) {
            _items.value = listOf(item) + _items.value.filter { it.id != item.id }
            save()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        synchronized(this@Library) {
            _items.value = _items.value.filter { it.id != id }
            File(root, id).deleteRecursively()
            save()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(this@Library) {
            _items.value.forEach { File(root, it.id).deleteRecursively() }
            _items.value = emptyList(); save()
        }
    }

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun save() {
        val a = JSONArray()
        for (i in _items.value) {
            val m = JSONObject(); i.meta.forEach { (k, v) -> m.put(k, v) }
            a.put(JSONObject().put("id", i.id).put("type", i.type.name).put("title", i.title).put("created", i.created)
                .put("source", i.sourceName ?: JSONObject.NULL).put("result", i.resultName ?: JSONObject.NULL).put("meta", m))
        }
        val tmp = File(root, "index.json.tmp"); tmp.writeText(a.toString()); tmp.renameTo(index)
    }

    private fun load(): List<LibraryItem> = runCatching {
        if (!index.exists()) return emptyList()
        val a = JSONArray(index.readText())
        (0 until a.length()).map { k ->
            val o = a.getJSONObject(k)
            val m = o.optJSONObject("meta")
            LibraryItem(o.getString("id"), ItemType.valueOf(o.getString("type")), o.getString("title"), o.getLong("created"),
                o.optString("source").takeIf { it.isNotEmpty() && it != "null" }, o.optString("result").takeIf { it.isNotEmpty() && it != "null" },
                m?.keys()?.asSequence()?.associateWith { m.getString(it) } ?: emptyMap())
        }
    }.getOrDefault(emptyList())
}
