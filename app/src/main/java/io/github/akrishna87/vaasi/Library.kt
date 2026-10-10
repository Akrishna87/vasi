package io.github.akrishna87.vaasi

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import io.github.akrishna87.vaasi.text.Book
import io.github.akrishna87.vaasi.text.PdfText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A PDF in the library: just enough to list it without loading its text. */
data class BookEntry(
    val id: String,
    val title: String,
    val pages: Int,
    val sentences: Int,
    val addedAt: Long,
    val position: Int = 0,
    /** Where the main text begins; listening "starts" here, past the title page and contents. */
    val start: Int = 0,
) {
    val progress: Float get() = if (sentences <= 1 || position <= start) 0f else position.toFloat() / (sentences - 1)
}

class NoTextException : Exception(
    "This PDF has no text to read. It's probably scanned pages (pictures of text), which Vaasi can't read yet.",
)

/**
 * The imported PDFs. Only their text is kept (in files/books/<id>.json), plus how far
 * each one has been listened to.
 */
class Library(private val context: Context) {
    private val indexFile = File(context.filesDir, "library.json")
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val positions = context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    private val _books = MutableStateFlow(readIndex())
    val books: StateFlow<List<BookEntry>> = _books

    private fun readIndex(): List<BookEntry> = try {
        val a = JSONArray(indexFile.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val id = o.getString("id")
            BookEntry(
                id = id,
                title = o.getString("title"),
                pages = o.getInt("pages"),
                sentences = o.getInt("sentences"),
                addedAt = o.getLong("addedAt"),
                position = positions.getInt(id, 0),
                start = o.optInt("start", 0),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeIndex(entries: List<BookEntry>) {
        val a = JSONArray()
        for (e in entries) {
            a.put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("pages", e.pages)
                put("sentences", e.sentences)
                put("addedAt", e.addedAt)
                put("start", e.start)
            })
        }
        val tmp = File(indexFile.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(indexFile)
    }

    fun entry(id: String): BookEntry? = _books.value.firstOrNull { it.id == id }

    suspend fun load(id: String): Book = withContext(Dispatchers.IO) {
        val file = File(booksDir, "$id.json")
        val book = Book.fromJson(file.readText())
        if (!book.hasContents) {
            // Added before Vaasi found chapters: save them, and skip the front matter if
            // listening hasn't started yet.
            file.writeText(book.toJson())
            withContext(Dispatchers.Main) {
                _books.update { list -> list.map { if (it.id == id) it.copy(start = book.start) else it } }
                writeIndex(_books.value)
                if (positions.getInt(id, 0) == 0 && book.start > 0) savePosition(id, book.start)
            }
        }
        book
    }

    /** Reads a PDF's text and adds it to the library. Reports progress as (page, pages). */
    suspend fun import(uri: Uri, onProgress: (Int, Int) -> Unit): BookEntry = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val pdf = PdfText.extract(context, uri, onProgress)
        val title = pdf.title?.takeIf { it.isNotBlank() && it.length in 3..120 && !looksLikeFileName(it) }
            ?: name ?: "Untitled"
        val id = UUID.randomUUID().toString()
        val book = Book.fromPages(id, title, pdf.pages, pdf.outline)
        if (book.paragraphs.sumOf { p -> p.sentences.sumOf { it.count(Char::isLetter) } } < 20) throw NoTextException()
        File(booksDir, "$id.json").writeText(book.toJson())
        // Begin at the introduction or first chapter, not the title page and contents.
        positions.edit { putInt(id, book.start) }
        val entry = BookEntry(id, title, book.pages, book.sentenceCount, System.currentTimeMillis(), book.start, book.start)
        _books.update { listOf(entry) + it }
        writeIndex(_books.value)
        entry
    }

    fun delete(id: String) {
        File(booksDir, "$id.json").delete()
        positions.edit { remove(id) }
        _books.update { list -> list.filterNot { it.id == id } }
        writeIndex(_books.value)
    }

    fun savePosition(id: String, sentence: Int) {
        if (positions.getInt(id, -1) == sentence) return
        positions.edit { putInt(id, sentence) }
        _books.update { list -> list.map { if (it.id == id) it.copy(position = sentence) else it } }
    }

    private fun displayName(uri: Uri): String? {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
        return name?.removeSuffix(".pdf")?.removeSuffix(".PDF")?.replace('_', ' ')?.trim()?.ifEmpty { null }
    }

    private fun looksLikeFileName(s: String) =
        s.endsWith(".doc", true) || s.endsWith(".docx", true) || s.endsWith(".pdf", true) ||
            s.startsWith("Microsoft Word", true) || s.equals("untitled", true)
}
