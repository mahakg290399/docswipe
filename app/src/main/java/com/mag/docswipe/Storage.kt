package com.mag.docswipe

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class Triage { UNREVIEWED, KEEP, STAGED_DELETE, SKIPPED }

data class Document(
    val id: String,
    val path: String,
    val name: String,
    val extension: String,
    val size: Long,
    val modified: Long,
    val month: String,
    val status: Triage,
    val sha256: String? = null,
    val duplicateGroup: String? = null,
    val original: Boolean = false
)

data class MonthSummary(val month: String, val count: Int, val bytes: Long, val pending: Int)
data class FailedDeletion(val id: String, val name: String, val path: String, val reason: String)

class DocSwipeDatabase(context: Context) : SQLiteOpenHelper(context, "docswipe.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE documents(
            id TEXT PRIMARY KEY, path TEXT UNIQUE NOT NULL, name TEXT NOT NULL,
            extension TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL,
            month TEXT NOT NULL, status TEXT NOT NULL, sha256 TEXT,
            duplicate_group TEXT, original INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX documents_month ON documents(month)")
        db.execSQL("CREATE INDEX documents_status ON documents(status)")
        db.execSQL("""CREATE TABLE failed_deletions(
            id TEXT PRIMARY KEY, name TEXT NOT NULL, path TEXT NOT NULL, reason TEXT NOT NULL,
            updated INTEGER NOT NULL)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replaceScan(files: List<Document>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val seen = files.map { it.path }.toSet()
            files.forEach { file ->
                val oldStatus = db.rawQuery("SELECT status FROM documents WHERE path = ?", arrayOf(file.path)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                val values = ContentValues().apply {
                    put("id", file.id); put("path", file.path); put("name", file.name)
                    put("extension", file.extension); put("size", file.size); put("modified", file.modified)
                    put("month", file.month); put("status", oldStatus ?: file.status.name)
                    put("sha256", file.sha256); put("duplicate_group", file.duplicateGroup); put("original", if (file.original) 1 else 0)
                }
                db.insertWithOnConflict("documents", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.query("documents", arrayOf("path"), null, null, null, null, null).use { c ->
                val removed = mutableListOf<String>()
                while (c.moveToNext()) if (c.getString(0) !in seen) removed += c.getString(0)
                removed.forEach { db.delete("documents", "path = ?", arrayOf(it)) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun months(): List<MonthSummary> = readableDatabase.rawQuery(
        "SELECT month, COUNT(*), SUM(size), SUM(CASE WHEN status IN ('UNREVIEWED','SKIPPED') THEN 1 ELSE 0 END) FROM documents GROUP BY month ORDER BY month ASC", null
    ).use { c ->
        buildList { while (c.moveToNext()) add(MonthSummary(c.getString(0), c.getInt(1), c.getLong(2), c.getInt(3))) }
    }

    fun documents(month: String, includeSkipped: Boolean = false): List<Document> = readableDatabase.rawQuery(
        "SELECT id,path,name,extension,size,modified,month,status,sha256,duplicate_group,original FROM documents WHERE month = ? AND status IN ('UNREVIEWED'${if (includeSkipped) ", 'SKIPPED'" else ""}) ORDER BY modified ASC", arrayOf(month)
    ).use(::readDocuments)

    fun hasSkipped(month: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM documents WHERE month = ? AND status = 'SKIPPED' LIMIT 1", arrayOf(month)).use { it.moveToFirst() }

    fun count(month: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM documents WHERE month = ?", arrayOf(month)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun staged(month: String): List<Document> = readableDatabase.rawQuery(
        "SELECT id,path,name,extension,size,modified,month,status,sha256,duplicate_group,original FROM documents WHERE month = ? AND status = 'STAGED_DELETE' ORDER BY modified ASC", arrayOf(month)
    ).use(::readDocuments)

    fun failed(): List<FailedDeletion> = readableDatabase.rawQuery("SELECT id,name,path,reason FROM failed_deletions ORDER BY updated DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(FailedDeletion(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
    }

    fun setStatus(id: String, status: Triage) {
        writableDatabase.update("documents", ContentValues().apply { put("status", status.name) }, "id = ?", arrayOf(id))
    }

    fun remove(id: String) {
        val db = writableDatabase
        val group = db.rawQuery("SELECT duplicate_group FROM documents WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
        db.delete("documents", "id = ?", arrayOf(id))
        if (!group.isNullOrBlank()) {
            db.execSQL("UPDATE documents SET original = 0 WHERE duplicate_group = ?", arrayOf(group))
            db.execSQL("UPDATE documents SET original = 1 WHERE id = (SELECT id FROM documents WHERE duplicate_group = ? ORDER BY modified ASC, path ASC LIMIT 1)", arrayOf(group))
        }
    }

    fun addFailure(document: Document, reason: String) {
        writableDatabase.insertWithOnConflict("failed_deletions", null, ContentValues().apply {
            put("id", document.id); put("name", document.name); put("path", document.path); put("reason", reason); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        remove(document.id)
    }

    fun removeFailure(id: String) { writableDatabase.delete("failed_deletions", "id = ?", arrayOf(id)) }

    private fun readDocuments(c: android.database.Cursor): List<Document> = buildList {
        while (c.moveToNext()) add(Document(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getString(6), Triage.valueOf(c.getString(7)), c.getString(8), c.getString(9), c.getInt(10) != 0))
    }
}

class StorageScanner(private val database: DocSwipeDatabase) {
    private val extensions = setOf("pdf", "docx", "xlsx", "pptx", "txt", "csv")
    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.ROOT)

    suspend fun scan(includeHidden: Boolean) = withContext(Dispatchers.IO) {
        val root = File("/storage/emulated/0")
        if (!root.exists() || !root.canRead()) return@withContext
        val files = mutableListOf<Document>()
        walk(root, includeHidden, files)
        database.replaceScan(markDuplicates(files))
    }

    private fun markDuplicates(files: List<Document>): List<Document> {
        val candidates = files.groupBy { it.size }.filterValues { it.size > 1 }
        val updates = files.associateBy { it.path }.toMutableMap()
        candidates.values.forEach { group ->
            val byHash = group.mapNotNull { doc -> sha256(File(doc.path))?.let { it to doc } }.groupBy({ it.first }, { it.second })
            byHash.filterValues { it.size > 1 }.forEach { (hash, members) ->
                val original = members.minWith(compareBy<Document> { it.modified }.thenBy { it.path })
                members.forEach { member ->
                    updates[member.path] = member.copy(
                        month = original.month,
                        sha256 = hash,
                        duplicateGroup = hash,
                        original = member.path == original.path
                    )
                }
            }
        }
        return files.map { updates.getValue(it.path) }
    }

    private fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    private fun walk(dir: File, includeHidden: Boolean, output: MutableList<Document>) {
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return
        if (dir.name == "Android" || dir.name.equals("data", true) || dir.name.equals("obb", true)) return
        if (!includeHidden && dir.name.startsWith(".")) return
        val children = try { dir.listFiles() ?: return } catch (_: SecurityException) { return }
        children.forEach { file ->
            if (file.isDirectory) walk(file, includeHidden, output)
            else if (!file.isSymbolicLink() && file.length() > 0 && file.extension.lowercase(Locale.ROOT) in extensions) {
                val modified = file.lastModified()
                output += Document(UUID.nameUUIDFromBytes(file.absolutePath.toByteArray()).toString(), file.absolutePath, file.name, file.extension.lowercase(Locale.ROOT), file.length(), modified, monthFormat.format(Date(modified)), Triage.UNREVIEWED)
            }
        }
    }

    private fun File.isSymbolicLink(): Boolean = try { canonicalFile != absoluteFile } catch (_: Exception) { true }
}
