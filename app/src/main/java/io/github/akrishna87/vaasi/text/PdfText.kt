package io.github.akrishna87.vaasi.text

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

/** Pulls the text out of a PDF, a page at a time, with PdfBox. */
object PdfText {

    class Extracted(val title: String?, val pages: List<String>, val outline: List<OutlineEntry>)

    /** Returns the PDF's own title (if it has one), the text of each page, and its bookmarks. */
    fun extract(context: Context, uri: Uri, onProgress: (Int, Int) -> Unit): Extracted {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the file")
        val document = try {
            input.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
        } catch (e: InvalidPasswordException) {
            throw IOException("This PDF is locked with a password. Remove the password and try again.")
        }
        document.use { doc ->
            val count = doc.numberOfPages
            val stripper = PDFTextStripper()
            stripper.setLineSeparator("\n")
            // A blank line wherever PdfBox sees a paragraph break; TextCleaner relies on it.
            stripper.setParagraphEnd("\n\n")
            val pages = (1..count).map { page ->
                onProgress(page, count)
                stripper.setStartPage(page)
                stripper.setEndPage(page)
                stripper.getText(doc)
            }
            return Extracted(doc.documentInformation?.title, pages, outline(doc))
        }
    }

    /**
     * The PDF's bookmarks with the (1-based) page each points to: the top level, or the level
     * below when the top is a single entry (usually the book's own title).
     */
    private fun outline(doc: PDDocument): List<OutlineEntry> = try {
        val root = doc.documentCatalog?.documentOutline
        var items: List<PDOutlineItem> = root?.children()?.toList().orEmpty()
        if (items.size == 1) items = items[0].children().toList().ifEmpty { items }
        items.take(500).mapNotNull { item ->
            val title = item.title?.trim().orEmpty()
            val page = item.findDestinationPage(doc) ?: return@mapNotNull null
            val index = doc.pages.indexOf(page)
            if (title.isEmpty() || index < 0) null else OutlineEntry(title, index + 1)
        }
    } catch (e: Exception) {
        emptyList() // Broken bookmarks shouldn't stop the PDF being read.
    }
}
