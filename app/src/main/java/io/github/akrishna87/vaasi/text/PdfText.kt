package io.github.akrishna87.vaasi.text

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException

/** Pulls the text out of a PDF, a page at a time, with PdfBox. */
object PdfText {

    /** Returns the PDF's own title (if it has one) and the text of each page. */
    fun extract(context: Context, uri: Uri, onProgress: (Int, Int) -> Unit): Pair<String?, List<String>> {
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
            return doc.documentInformation?.title to pages
        }
    }
}
