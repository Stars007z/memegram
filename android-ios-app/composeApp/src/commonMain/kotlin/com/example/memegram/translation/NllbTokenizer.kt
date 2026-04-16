package com.example.memegram.translation

import kotlinx.serialization.json.*


class NllbTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val reverseVocab: Map<Int, String>,
    private val pieceScores: Map<String, Float>,
    private val langTokens: Map<String, Int>,
    private val maxPieceLength: Int,
    val eosTokenId: Int,
    val padTokenId: Int,
    val unkTokenId: Int,
    val bosTokenId: Int,
) {
    companion object {
        private const val WORD_PREFIX = "\u2581"
        private const val UNK_SCORE = -100f

        fun fromJson(tokenizerJson: String, configJson: String? = null): NllbTokenizer {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(tokenizerJson).jsonObject

            // ── Vocab: token → ID ────────────────────────────────
            val vocabObj = root["vocab"]?.jsonObject
                ?: error("tokenizer.json missing 'vocab'")
            val vocab = HashMap<String, Int>(vocabObj.size)
            for ((token, idElem) in vocabObj) {
                vocab[token] = idElem.jsonPrimitive.int
            }

            // ── Piece scores ─────────────────────────────────────
            val scoresObj = root["piece_scores"]?.jsonObject
            val pieceScores = HashMap<String, Float>(scoresObj?.size ?: 0)
            if (scoresObj != null) {
                for ((piece, scoreElem) in scoresObj) {
                    pieceScores[piece] = scoreElem.jsonPrimitive.float
                }
            }

            // ── Language tokens: FLORES code → token ID ──────────
            val langObj = root["lang_tokens"]?.jsonObject
            val langTokens = HashMap<String, Int>(langObj?.size ?: 0)
            if (langObj != null) {
                for ((code, idElem) in langObj) {
                    langTokens[code] = idElem.jsonPrimitive.int
                }
            }

            val maxPieceLen = root["max_piece_length"]?.jsonPrimitive?.int ?: 48

            // ── Special token IDs ────────────────────────────────
            var eosId = 2
            var padId = 1
            var unkId = 3
            var bosId = 0

            if (configJson != null) {
                try {
                    val config = json.parseToJsonElement(configJson).jsonObject
                    eosId = config["eos_token_id"]?.jsonPrimitive?.int ?: eosId
                    padId = config["pad_token_id"]?.jsonPrimitive?.int ?: padId
                    bosId = config["bos_token_id"]?.jsonPrimitive?.int ?: bosId
                } catch (_: Exception) { /* use defaults */ }
            }

            if (vocab.containsKey("</s>")) eosId = vocab["</s>"]!!
            if (vocab.containsKey("<pad>")) padId = vocab["<pad>"]!!
            if (vocab.containsKey("<unk>")) unkId = vocab["<unk>"]!!
            if (vocab.containsKey("<s>")) bosId = vocab["<s>"]!!

            val reverseVocab = HashMap<Int, String>(vocab.size)
            for ((k, v) in vocab) { reverseVocab[v] = k }

            return NllbTokenizer(
                vocab = vocab,
                reverseVocab = reverseVocab,
                pieceScores = pieceScores,
                langTokens = langTokens,
                maxPieceLength = maxPieceLen,
                eosTokenId = eosId,
                padTokenId = padId,
                unkTokenId = unkId,
                bosTokenId = bosId,
            )
        }
    }

    val vocabSize: Int get() = vocab.size

    fun getLangTokenId(floresCode: String): Int? = langTokens[floresCode]


    fun encode(text: String, srcLangFlores: String): List<Int> {
        val langId = langTokens[srcLangFlores]
            ?: error("Unknown NLLB language: $srcLangFlores")

        if (text.isBlank()) return listOf(langId, eosTokenId)

        val normalized = text.trim()
        val tokenIds = mutableListOf<Int>()

        tokenIds.add(langId)

        val words = normalized.split(Regex("\\s+"))
        for (word in words) {
            if (word.isEmpty()) continue
            val prefixedWord = "$WORD_PREFIX$word"
            val pieces = viterbiEncode(prefixedWord)
            for (piece in pieces) {
                tokenIds.add(vocab[piece] ?: unkTokenId)
            }
        }

        // Append EOS
        tokenIds.add(eosTokenId)
        return tokenIds
    }


    fun decoderStartIds(tgtLangFlores: String): List<Int> {
        val langId = langTokens[tgtLangFlores]
            ?: error("Unknown NLLB target language: $tgtLangFlores")
        return listOf(eosTokenId, langId)
    }


    fun decode(tokenIds: List<Int>): String {
        val langIdSet = langTokens.values.toHashSet()
        val specialIds = hashSetOf(eosTokenId, padTokenId, bosTokenId, unkTokenId)

        val tokens = tokenIds.mapNotNull { id ->
            if (id in specialIds || id in langIdSet) null
            else reverseVocab[id]
        }
        return tokens.joinToString("")
            .replace(WORD_PREFIX, " ")
            .trim()
    }

    // ── Viterbi (best-path) segmentation ─────────────────────────

    private fun viterbiEncode(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val n = text.length
        val dpScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val dpStart = IntArray(n + 1) { 0 }
        dpScore[0] = 0f

        for (i in 1..n) {
            val maxLen = minOf(i, maxPieceLength)
            for (length in 1..maxLen) {
                val j = i - length
                if (dpScore[j] == Float.NEGATIVE_INFINITY) continue

                val piece = text.substring(j, i)
                val score = pieceScores[piece]
                if (score != null) {
                    val total = dpScore[j] + score
                    if (total > dpScore[i]) {
                        dpScore[i] = total
                        dpStart[i] = j
                    }
                } else if (length == 1) {
                    val total = dpScore[j] + UNK_SCORE
                    if (total > dpScore[i]) {
                        dpScore[i] = total
                        dpStart[i] = j
                    }
                }
            }
        }

        // Backtrace
        val pieces = mutableListOf<String>()
        var pos = n
        while (pos > 0) {
            val start = dpStart[pos]
            pieces.add(text.substring(start, pos))
            pos = start
        }
        pieces.reverse()
        return pieces
    }
}
