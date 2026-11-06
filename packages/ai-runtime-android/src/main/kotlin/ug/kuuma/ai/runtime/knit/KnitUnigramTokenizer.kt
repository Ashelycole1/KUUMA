package ug.kuuma.ai.runtime.knit

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/** Single-text ALBERT Unigram tokenizer for the pinned Knit Hugging Face tokenizer data. */
internal class KnitUnigramTokenizer(json: String) {
    data class Encoding(val ids: IntArray, val mask: IntArray)

    private data class Piece(val id: Int, val score: Double)
    private val pieces: Map<String, Piece>
    private val unknownId: Int
    private val unknownScore: Double
    private val maxPieceLength: Int
    private val specialIds: Map<String, Int>
    private val specialPattern: Regex

    init {
        val root = JSONObject(json)
        val model = root.getJSONObject("model")
        check(model.getString("type") == "Unigram" && !model.getBoolean("byte_fallback"))
        val vocab = model.getJSONArray("vocab")
        pieces = buildMap(vocab.length()) {
            for (id in 0 until vocab.length()) {
                val entry = vocab.getJSONArray(id)
                val value = entry.getString(0)
                val score = entry.getDouble(1)
                check(value.isNotEmpty() && score.isFinite())
                check(put(value, Piece(id, score)) == null)
            }
        }
        unknownId = model.getInt("unk_id")
        unknownScore = pieces.values.minOf { it.score } - 10.0
        maxPieceLength = pieces.keys.maxOf { it.length }
        specialIds = listOf("<pad>", "<unk>", "[CLS]", "[SEP]", "[MASK]").associateWith {
            pieces.getValue(it).id
        }
        check(specialIds.getValue("<unk>") == unknownId)
        check(root.getJSONObject("padding").getInt("pad_id") == specialIds.getValue("<pad>"))
        specialPattern = Regex(specialIds.keys.joinToString("|") { Regex.escape(it) })
    }

    /** Returns null when the full input cannot fit in the fixed window. */
    fun encode(text: String): Encoding? {
        val ids = ArrayList<Int>()
        var from = 0
        for (match in specialPattern.findAll(text)) {
            if (!appendSegment(text.substring(from, match.range.first), ids)) return null
            ids.add(specialIds.getValue(match.value))
            if (ids.size > BODY_LENGTH) return null
            from = match.range.last + 1
        }
        if (!appendSegment(text.substring(from), ids)) return null

        val padded = IntArray(INPUT_LENGTH) { specialIds.getValue("<pad>") }
        padded[0] = specialIds.getValue("[CLS]")
        ids.forEachIndexed { index, id -> padded[index + 1] = id }
        padded[ids.size + 1] = specialIds.getValue("[SEP]")
        return Encoding(padded, IntArray(INPUT_LENGTH) { if (it < ids.size + 2) 1 else 0 })
    }

    private fun appendSegment(segment: String, ids: MutableList<Int>): Boolean {
        val normalized = normalize(segment)
        for (word in whitespace.splitToSequence(normalized).filter(String::isNotEmpty)) {
            val prefixed = if (word.startsWith(METASPACE)) word else "$METASPACE$word"
            for (part in metaspacePieces.findAll(prefixed)) {
                ids.addAll(segmentWord(part.value))
                if (ids.size > BODY_LENGTH) return false
            }
        }
        return true
    }

    private fun normalize(segment: String): String {
        var text = segment.replace("``", "\"").replace("''", "\"")
        repeat(2) {
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
            text = buildString {
                var offset = 0
                while (offset < decomposed.length) {
                    val codePoint = decomposed.codePointAt(offset)
                    offset += Character.charCount(codePoint)
                    when (Character.getType(codePoint)) {
                        Character.NON_SPACING_MARK.toInt(),
                        Character.COMBINING_SPACING_MARK.toInt(),
                        Character.ENCLOSING_MARK.toInt() -> Unit
                        else -> append(String(Character.toChars(codePoint)).lowercase(Locale.ROOT))
                    }
                }
            }.replace(repeatedSpaces, " ")
        }
        return text
    }

    /** Find the most likely vocabulary path through Unicode scalar boundaries. */
    private fun segmentWord(word: String): List<Int> {
        val bestScore = DoubleArray(word.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(word.length + 1)
        val chosen = IntArray(word.length + 1)
        bestScore[0] = 0.0
        var start = 0
        while (start < word.length) {
            val next = word.offsetByCodePoints(start, 1)
            var end = next
            var singleKnown = false
            while (end - start <= maxPieceLength) {
                val piece = pieces[word.substring(start, end)]
                if (piece != null) {
                    if (end == next) singleKnown = true
                    val score = bestScore[start] + piece.score
                    if (score > bestScore[end]) {
                        bestScore[end] = score
                        previous[end] = start
                        chosen[end] = piece.id
                    }
                }
                if (end == word.length) break
                end = word.offsetByCodePoints(end, 1)
            }
            if (!singleKnown && bestScore[start] + unknownScore > bestScore[next]) {
                bestScore[next] = bestScore[start] + unknownScore
                previous[next] = start
                chosen[next] = unknownId
            }
            start = next
        }

        val tokens = ArrayList<Int>()
        var end = word.length
        while (end > 0) {
            val id = chosen[end]
            if (id != unknownId || tokens.lastOrNull() != unknownId) tokens.add(id)
            end = previous[end]
        }
        tokens.reverse()
        return tokens
    }

    companion object {
        const val INPUT_LENGTH = 128
        private const val BODY_LENGTH = INPUT_LENGTH - 2
        private const val METASPACE = '▁'
        private val whitespace =
            Regex("[\\u0009-\\u000d\\u0020\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+")
        private val metaspacePieces = Regex("▁[^▁]*")
        private val repeatedSpaces = Regex(" {2,}")
    }
}
