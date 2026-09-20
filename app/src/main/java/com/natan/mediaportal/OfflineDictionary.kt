package com.natan.mediaportal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WordMeaning(val word: String, val partOfSpeech: String, val definition: String, val examples: List<String>)

/** WordNet is bundled with the app. Lookups never send reading text to a server. */
internal object OfflineDictionary {
    private val lock = Any()
    private fun database(context: Context): File = synchronized(lock) {
        val file = File(context.filesDir, "dictionary-wordnet-3.0-v1.db")
        if (!file.exists()) {
            val temporary = File(context.filesDir, "dictionary-wordnet-3.0-v1.tmp")
            try {
                context.assets.open("dictionary/wordnet.db.gzip").use { source ->
                    GZIPInputStream(source).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                }
                check(temporary.renameTo(file)) { "Could not prepare the dictionary." }
            } finally { temporary.delete() }
        }
        file
    }

    suspend fun lookup(context: Context, text: String): List<WordMeaning> = withContext(Dispatchers.IO) {
        val word = text.trim().trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (word.isEmpty() || word.length > 80) return@withContext emptyList()
        SQLiteDatabase.openDatabase(database(context).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val found = mutableListOf<WordMeaning>()
            for (pos in listOf("noun", "verb", "adj", "adv")) {
                val candidates = linkedSetOf(word)
                db.rawQuery("SELECT lemma FROM exceptions WHERE word=? AND pos=?", arrayOf(word, pos)).use { cursor ->
                    while (cursor.moveToNext()) candidates += cursor.getString(0)
                }
                val endings = when (pos) {
                    "noun" -> listOf("s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y")
                    "verb" -> listOf("s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to "")
                    "adj" -> listOf("er" to "", "est" to "", "er" to "e", "est" to "e", "ier" to "y", "iest" to "y")
                    else -> emptyList()
                }
                endings.filter { word.endsWith(it.first) && word.length > it.first.length + 1 }.forEach { (suffix, replacement) ->
                    val base = word.dropLast(suffix.length) + replacement
                    candidates += base
                    if (suffix in listOf("ed", "ing", "er", "est") && replacement.isEmpty() && base.length > 2 && base.last() == base[base.lastIndex - 1]) candidates += base.dropLast(1)
                }
                for (candidate in candidates) {
                    val meanings = mutableListOf<WordMeaning>()
                    db.rawQuery("SELECT s.gloss FROM entries e JOIN senses s ON s.pos=e.pos AND s.offset=e.offset WHERE e.word=? AND e.pos=? ORDER BY e.rank LIMIT 12", arrayOf(candidate, pos)).use { cursor ->
                        while (cursor.moveToNext()) {
                            val gloss = cursor.getString(0)
                            val examples = Regex(";\\s*\"([^\"]+)\"").findAll(gloss).toList()
                            meanings += WordMeaning(candidate, when (pos) { "adj" -> "adjective"; "adv" -> "adverb"; else -> pos },
                                gloss.take(examples.firstOrNull()?.range?.first ?: gloss.length).trim(), examples.map { it.groupValues[1] })
                        }
                    }
                    if (meanings.isNotEmpty()) { found += meanings; break }
                }
            }
            found.distinct()
        }
    }
}
