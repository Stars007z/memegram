package com.example.translator

import android.content.Context
import org.json.JSONObject

class SimpleTokenizer(context: Context) {
    private val vocab: Map<String, Long>
    private val idsToTokens: Map<Long, String>
    
    init {
        val json = context.assets.open("model/tokenizer.json").bufferedReader().use { it.readText() }
        val jsonObj = JSONObject(json)
        val vocabObj = jsonObj.getJSONObject("model").getJSONObject("vocab")
        
        vocab = vocabObj.keys().associateWith { key ->
            vocabObj.getLong(key)
        }
        idsToTokens = vocab.entries.associate { it.value to it.key }
    }
    
    fun encode(text: String): List<Long> {
        return text.lowercase()
            .split(" ", ".", ",", "!", "?")
            .filter { it.isNotBlank() }
            .map { vocab[it] ?: vocab["[UNK]"] ?: 0L }
    }
    
    fun decode(ids: List<Long>): String {
        return ids.map { idsToTokens[it] ?: "" }
            .filter { it.isNotEmpty() && it !in listOf("<pad>", "</s>", "<s>") }
            .joinToString(" ")
    }
}
