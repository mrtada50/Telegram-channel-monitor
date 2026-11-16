package com.tgmonitor.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val ext: String) { JSON("json"), CSV("csv") }

class Table(val name: String, val columns: List<String>, val rows: List<List<Any?>>)

object TableFormatter {
    fun format(table: Table, format: ExportFormat): String =
        if (format == ExportFormat.CSV) csv(table) else json(table)

    fun csv(t: Table): String {
        val sb = StringBuilder("\uFEFF") // BOM so Excel reads Arabic correctly
        sb.append(t.columns.joinToString(",") { esc(it) }).append("\r\n")
        t.rows.forEach { r -> sb.append(r.joinToString(",") { esc(it) }).append("\r\n") }
        return sb.toString()
    }

    fun json(t: Table): String {
        val arr = JSONArray()
        for (r in t.rows) {
            val o = JSONObject()
            t.columns.forEachIndexed { i, c -> o.put(c, r[i] ?: JSONObject.NULL) }
            arr.put(o)
        }
        return arr.toString(2)
    }

    private fun esc(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

class DataExporter(private val context: Context, private val db: AppDatabase) {

    suspend fun exportZip(uri: Uri, format: ExportFormat) = withContext(Dispatchers.IO) {
        val tables = buildTables()
        val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("تعذر فتح ملف الوجهة")
        out.use { stream ->
            ZipOutputStream(stream).use { zip ->
                for (t in tables) {
                    zip.putNextEntry(ZipEntry("${t.name}.${format.ext}"))
                    zip.write(TableFormatter.format(t, format).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    private suspend fun buildTables(): List<Table> {
        val events = db.changeDao().getAll()
        val eventCols = listOf("id", "channelId", "messageId", "changeType", "detail", "oldContent", "newContent", "detectedAt", "verificationStatus")
        fun eventRows(list: List<ChangeEventEntity>) = list.map {
            listOf(it.id, it.channelId, it.messageId, it.changeType, it.detail, it.oldContent, it.newContent, it.detectedAt, it.verificationStatus)
        }
        return listOf(
            Table(
                "channels",
                listOf("id", "username", "title", "url", "dateAdded", "lastSuccessfulCheck", "monitoringEnabled", "lastKnownMessageId", "status"),
                db.channelDao().getAll().map {
                    listOf(it.id, it.username, it.title, it.url, it.dateAdded, it.lastSuccessfulCheck, it.monitoringEnabled, it.lastKnownMessageId, it.status)
                },
            ),
            Table(
                "messages",
                listOf("id", "channelId", "messageId", "originalText", "currentText", "originalLinks", "currentLinks", "contentHash", "publishedAt", "firstSeenAt", "lastCheckedAt", "deletionStatus", "deletionDetectedAt", "url"),
                db.messageDao().getAll().map {
                    listOf(it.id, it.channelId, it.messageId, it.originalText, it.currentText, it.originalLinks, it.currentLinks, it.contentHash, it.publishedAt, it.firstSeenAt, it.lastCheckedAt, it.deletionStatus, it.deletionDetectedAt, it.url)
                },
            ),
            Table(
                "message_versions",
                listOf("id", "messageRowId", "versionNumber", "content", "links", "contentHash", "detectedAt"),
                db.versionDao().getAll().map { listOf(it.id, it.messageRowId, it.versionNumber, it.content, it.links, it.contentHash, it.detectedAt) },
            ),
            Table("edit_log", eventCols, eventRows(events.filter { it.changeType in ChangeType.EDITS })),
            Table("deletion_log", eventCols, eventRows(events.filter { it.changeType in ChangeType.DELETIONS })),
            Table(
                "scan_history",
                listOf("id", "channelId", "startedAt", "completedAt", "status", "postsScanned", "errorMessage"),
                db.scanDao().getAll().map { listOf(it.id, it.channelId, it.startedAt, it.completedAt, it.status, it.postsScanned, it.errorMessage) },
            ),
        )
    }
}
