package io.github.emmanuelquintana.capri

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest

/**
 * (1.14.0) The duplicate finder (Su mente): you pick a folder — Downloads, DCIM, any folder —
 * with Android's own picker (that's the whole permission: access to that folder only, while
 * you leave it granted), and she groups the files that are byte-for-byte the same: first by
 * size (cheap), and only the ties get an MD5. Nothing is deleted here and nothing runs on
 * its own: MainActivity shows the groups and only deletes the extra copies when you say so,
 * always keeping the first copy of each group.
 *
 * Written on DocumentsContract directly (no extra library): the tree you picked is walked
 * document by document.
 */
object Duplicates {

    data class Dup(val name: String, val size: Long, val docId: String)

    data class Group(val size: Long, val keep: Dup, val extra: List<Dup>) {
        /** What deleting the extra copies would give back. */
        val wasted: Long get() = size * extra.size
    }

    private data class Found(val name: String, val size: Long, val docId: String, val uri: Uri)

    /** Every file under the picked tree, recursively. Blocking: call it off the main thread. */
    private fun walk(context: Context, tree: Uri): List<Found> {
        val out = ArrayList<Found>()
        val resolver = context.contentResolver
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        fun go(docId: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            runCatching {
                resolver.query(children, cols, null, null, null)?.use { c ->
                    val idI = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameI = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val sizeI = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val mimeI = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (c.moveToNext()) {
                        val id = c.getString(idI) ?: continue
                        val mime = c.getString(mimeI)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) go(id)
                        else {
                            val size = if (sizeI >= 0 && !c.isNull(sizeI)) c.getLong(sizeI) else 0L
                            if (size > 0) out += Found(c.getString(nameI) ?: id, size, id, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                        }
                    }
                }
            }
        }
        go(DocumentsContract.getTreeDocumentId(tree))
        return out
    }

    private fun md5(context: Context, uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** The groups of identical files in the picked folder (2 or more copies). Blocking. */
    fun scan(context: Context, tree: Uri): List<Group> {
        val bySize = walk(context, tree).groupBy { it.size }.filterValues { it.size > 1 }
        val out = ArrayList<Group>()
        for ((size, same) in bySize) {
            val byHash = same.groupBy { md5(context, it.uri) }
            for ((hash, copies) in byHash) {
                if (hash == null || copies.size < 2) continue
                val sorted = copies.sortedBy { it.name.lowercase() }
                out += Group(size, Dup(sorted.first().name, size, sorted.first().docId),
                    sorted.drop(1).map { Dup(it.name, size, it.docId) })
            }
        }
        return out.sortedByDescending { it.wasted }
    }

    /**
     * Delete the extra copies of each group (never the kept one), in the tree you picked.
     * How many files went. Only ever called after you saw the groups and said yes.
     */
    fun deleteExtras(context: Context, tree: Uri, groups: List<Group>): Int {
        var gone = 0
        for (g in groups) for (d in g.extra) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.docId)
            if (runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)) gone++
        }
        return gone
    }
}
