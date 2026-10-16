package app.foctuner.ui.tools.lisp

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException

/** Files picked through the Storage Access Framework: scripts, and folders that imports are read from or saved to. */
class LispFiles(private val resolver: ContentResolver) {
    fun read(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException(uri.toString())

    /** Replaces the file's content ("wt" truncates; some providers only know "w", which is tried next). */
    fun write(uri: Uri, bytes: ByteArray) {
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        }
        stream?.use { it.write(bytes) } ?: throw FileNotFoundException(uri.toString())
    }

    fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: SecurityException) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Keeps access to [uri] across restarts so it can be reopened from the recent list. */
    fun keep(uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        try {
            resolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // The provider doesn't offer lasting access; the file works until the app restarts.
        }
    }

    /**
     * The file at [path] (relative, `/`-separated) inside the picked folder [tree].
     *
     * @throws FileNotFoundException if a part of the path doesn't exist
     */
    fun readInTree(tree: Uri, path: String): ByteArray {
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (part in path.split('/').filter { it.isNotEmpty() && it != "." }) {
            id = child(tree, id, part) ?: throw FileNotFoundException(path)
        }
        return read(DocumentsContract.buildDocumentUriUsingTree(tree, id))
    }

    /** Writes [bytes] to [path] inside [tree], creating folders on the way and replacing a file of the same name. */
    fun writeInTree(tree: Uri, path: String, bytes: ByteArray) {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) throw IOException("Empty file name")
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (folder in parts.dropLast(1)) {
            id = child(tree, id, folder) ?: create(tree, id, DocumentsContract.Document.MIME_TYPE_DIR, folder)
        }
        val target = child(tree, id, parts.last()) ?: create(tree, id, BINARY, parts.last())
        write(DocumentsContract.buildDocumentUriUsingTree(tree, target), bytes)
    }

    private fun child(tree: Uri, parent: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
        }
        return null
    }

    private fun create(tree: Uri, parent: String, mime: String, name: String): String {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
        val created = DocumentsContract.createDocument(resolver, parentUri, mime, name) ?: throw IOException("Could not create $name")
        return DocumentsContract.getDocumentId(created)
    }

    companion object {
        /** Keeps the name the user typed: providers append an extension for most other types. */
        const val BINARY = "application/octet-stream"
    }
}
