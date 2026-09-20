package com.natan.mediaportal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.drinkless.tdlib.JsonClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class TelegramLibraryTest {
    private fun message(name: String = "Book.m4b", kind: String = "messageDocument") = JSONObject("""{
        "id": 8000000000000001, "chat_id": -1001234567890,
        "content": {"@type":"$kind", "document": {"file_name":"$name", "document":{"id":92,"size":4000000000,"expected_size":0}}}
    }""")
    @Test fun preservesLargeIdentifiersAndFileSizes() {
        val file = TelegramFile.parse(message())!!
        assertEquals(8000000000000001L, file.message)
        assertEquals(-1001234567890L, file.chat)
        assertEquals(4000000000L, file.size)
        assertTrue(file.audio)
        assertEquals("-1001234567890:8000000000000001", file.key)
    }
    @Test fun filtersUnsupportedMessagesAndDistinguishesDuplicateFilenames() {
        assertNull(TelegramFile.parse(message("install.apk")))
        assertNull(TelegramFile.parse(message(kind = "messageText")))
        val first = TelegramFile.parse(message("Same.epub"))!!
        val second = TelegramFile.parse(message("Same.epub").put("id", 8000000000000002L))!!
        assertNotEquals(first.key, second.key)
        assertFalse(first.audio)
    }
    @Test fun readsAudioAttachments() {
        val result = TelegramFile.parse(JSONObject("""{"id":15,"chat_id":42,"content":{"@type":"messageAudio","audio":{"file_name":"Chapter.M4B","audio":{"id":6,"size":40}}}}"""))!!
        assertTrue(result.audio); assertEquals(6, result.file)
    }
    @Test fun officialNativeJsonInterfaceLoadsAndExecutes() {
        val result = JSONObject(JsonClient.execute("""{"@type":"getTextEntities","text":"hello @telegram"}"""))
        assertEquals("textEntities", result.getString("@type"))
        assertEquals(1, result.getJSONArray("entities").length())
    }
    @Test fun repeatedTelegramImportKeepsOneBookAndItsIdentity() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val root = File(inst.targetContext.cacheDir, "telegram-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(inst.targetContext) { override fun getFilesDir() = root }
        try {
            val fixture = File(root, "chaptered.m4b")
            inst.context.assets.open("chaptered.m4b").use { input -> fixture.outputStream().use { input.copyTo(it) } }
            val store = LibraryStore(context)
            val first = store.import(Uri.fromFile(fixture), true, null, telegramSource = "1:-100123:9000000001")
            val second = store.import(Uri.fromFile(fixture), true, null, telegramSource = "1:-100123:9000000001")
            assertEquals(first.id, second.id)
            assertEquals(1, store.all().size)
            assertEquals(first.audio, second.audio)
        } finally { root.deleteRecursively() }
    }
}
