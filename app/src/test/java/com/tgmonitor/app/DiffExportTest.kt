package com.tgmonitor.app

import com.tgmonitor.app.data.ChangeType
import com.tgmonitor.app.data.ExportFormat
import com.tgmonitor.app.data.Table
import com.tgmonitor.app.data.TableFormatter
import com.tgmonitor.app.engine.ContentHasher
import com.tgmonitor.app.engine.DiffType
import com.tgmonitor.app.engine.EditClassifier
import com.tgmonitor.app.engine.WordDiff
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDiffTest {
    @Test fun marksAddedAndRemovedWords() {
        val spans = WordDiff.diff("the quick brown fox", "the slow brown fox jumps")
        assertEquals("the quick brown fox", spans.filter { it.type != DiffType.ADDED }.joinToString("") { it.text })
        assertEquals("the slow brown fox jumps", spans.filter { it.type != DiffType.REMOVED }.joinToString("") { it.text })
        assertTrue(spans.any { it.type == DiffType.REMOVED && it.text.contains("quick") })
        assertTrue(spans.any { it.type == DiffType.ADDED && it.text.contains("slow") })
        assertTrue(spans.any { it.type == DiffType.ADDED && it.text.contains("jumps") })
    }

    @Test fun identicalAndEmpty() {
        assertEquals(listOf(DiffType.SAME), WordDiff.diff("same", "same").map { it.type })
        assertTrue(WordDiff.diff("", "").isEmpty())
        assertEquals(listOf(DiffType.ADDED), WordDiff.diff("", "new").map { it.type })
    }

    @Test fun arabicText() {
        val spans = WordDiff.diff("مرحبا بالعالم", "مرحبا بكم جميعا")
        assertEquals("مرحبا بكم جميعا", spans.filter { it.type != DiffType.REMOVED }.joinToString("") { it.text })
    }
}

class EditClassifierTest {
    @Test fun textOnly() {
        val c = EditClassifier.classify("a", emptyList(), "a b", emptyList())
        assertEquals(ChangeType.TEXT_EDITED, c.type); assertEquals(listOf("TEXT_ADDED"), c.details)
        assertEquals(listOf("TEXT_REMOVED"), EditClassifier.classify("a b", emptyList(), "a", emptyList()).details)
        assertEquals(listOf("TEXT_MODIFIED"), EditClassifier.classify("a", emptyList(), "z", emptyList()).details)
    }

    @Test fun links() {
        val a = "https://a.com"; val b = "https://b.com"
        assertEquals(listOf("LINK_ADDED"), EditClassifier.classify("t", emptyList(), "t", listOf(a)).details)
        assertEquals(listOf("LINK_REMOVED"), EditClassifier.classify("t", listOf(a), "t", emptyList()).details)
        val m = EditClassifier.classify("t", listOf(a), "t", listOf(b))
        assertEquals(ChangeType.LINKS_EDITED, m.type); assertEquals(listOf("LINK_MODIFIED"), m.details)
        assertEquals(ChangeType.TEXT_AND_LINKS_EDITED, EditClassifier.classify("t", listOf(a), "u", listOf(b)).type)
    }

    @Test fun hashSensitiveToTextAndLinks() {
        val h = ContentHasher.hash("x", listOf("l"))
        assertEquals(h, ContentHasher.hash("x", listOf("l")))
        assertNotEquals(h, ContentHasher.hash("y", listOf("l")))
        assertNotEquals(h, ContentHasher.hash("x", listOf("m")))
        assertNotEquals(h, ContentHasher.hash("x", emptyList()))
    }
}

class ExportTest {
    private val table = Table("t", listOf("id", "text"), listOf(listOf(1L, "a,b"), listOf(2L, "say \"hi\"\nnow"), listOf(3L, null)))

    @Test fun csvEscaping() {
        val csv = TableFormatter.format(table, ExportFormat.CSV)
        assertTrue(csv.startsWith("\uFEFFid,text\r\n"))
        assertTrue(csv.contains("1,\"a,b\"\r\n"))
        assertTrue(csv.contains("2,\"say \"\"hi\"\"\nnow\"\r\n"))
        assertTrue(csv.contains("3,\r\n"))
    }

    @Test fun jsonRoundTrip() {
        val arr = JSONArray(TableFormatter.format(table, ExportFormat.JSON))
        assertEquals(3, arr.length())
        assertEquals("a,b", arr.getJSONObject(0).getString("text"))
        assertTrue(arr.getJSONObject(2).isNull("text"))
        assertEquals(2L, arr.getJSONObject(1).getLong("id"))
    }
}
