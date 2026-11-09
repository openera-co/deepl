package app.carnet.core.doc

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** An OOXML/ODF package held in memory, entry order preserved. */
class ZipPackage(val entries: LinkedHashMap<String, ByteArray>) {

    fun names() = entries.keys.toList()
    operator fun get(name: String) = entries[name]
    operator fun set(name: String, data: ByteArray) { entries[name] = data }

    fun write(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                // ODF requires "mimetype" to be the first entry and stored uncompressed.
                if (name == "mimetype") {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(e); zip.write(data); zip.closeEntry()
            }
        }
    }

    companion object {
        /** Reads a zip package. Limits protect against zip bombs from untrusted files. */
        fun read(input: InputStream, maxTotal: Long = 1L shl 30, maxEntries: Int = 20000): ZipPackage {
            val map = LinkedHashMap<String, ByteArray>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    require(!e.name.contains("..")) { "Invalid entry name" }
                    require(map.size < maxEntries) { "Too many entries" }
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= maxTotal) { "File too large" }
                        bos.write(buf, 0, n)
                    }
                    map[e.name] = bos.toByteArray()
                }
            }
            require(map.isNotEmpty()) { "Not a valid document (empty or not a zip)" }
            return ZipPackage(map)
        }
    }
}

object Xml {
    private val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // No external entities/DTDs from untrusted documents.
        trySet("http://apache.org/xml/features/disallow-doctype-decl", true)
        trySet("http://xml.org/sax/features/external-general-entities", false)
        trySet("http://xml.org/sax/features/external-parameter-entities", false)
        isExpandEntityReferences = false
    }

    private fun DocumentBuilderFactory.trySet(f: String, v: Boolean) = try { setFeature(f, v) } catch (_: Exception) {}

    fun parse(bytes: ByteArray): Document = synchronized(factory) { factory.newDocumentBuilder() }.parse(bytes.inputStream())

    fun serialize(doc: Document): ByteArray {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.INDENT, "no")
        val bos = ByteArrayOutputStream()
        bos.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n".toByteArray())
        t.transform(DOMSource(doc), StreamResult(bos))
        return bos.toByteArray()
    }

    /** Child elements of [e] in order. */
    fun children(e: Node): List<Element> {
        val out = ArrayList<Element>()
        var c = e.firstChild
        while (c != null) { if (c is Element) out.add(c); c = c.nextSibling }
        return out
    }

    fun child(e: Element, ns: String, local: String): Element? =
        children(e).firstOrNull { it.namespaceURI == ns && it.localName == local }

    fun all(doc: Document, ns: String, local: String): List<Element> {
        val nl = doc.getElementsByTagNameNS(ns, local)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    /** Canonical string of an element subtree (attribute order normalised), used as a format key. */
    fun canonical(e: Element?, skip: (Element) -> Boolean = { false }): String {
        if (e == null) return ""
        val sb = StringBuilder()
        fun walk(n: Element) {
            if (skip(n)) return
            sb.append('<').append(n.localName)
            val attrs = (0 until n.attributes.length).map { n.attributes.item(it) }
                .filter { !(it.nodeName.startsWith("xmlns")) && !it.localName.orEmpty().startsWith("rsid") }
                .sortedBy { it.localName ?: it.nodeName }
            for (a in attrs) sb.append(' ').append(a.localName ?: a.nodeName).append('=').append(a.nodeValue)
            sb.append('>')
            for (c in children(n)) walk(c)
            sb.append("</>")
        }
        walk(e)
        return sb.toString()
    }
}
