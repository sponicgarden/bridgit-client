package cc.bridgit.app.vision

import org.json.JSONObject

/**
 * Florence-2's tokenizer (BART: byte-level BPE, the GPT-2 scheme) in plain Kotlin, so the on-phone detector can
 * turn a query into token ids and its answer back into words. [vocabJson] is vocab.json, [merges] is merges.txt
 * (both shipped in assets/florence2). Pure: no Android. Checked against the Hugging Face tokenizer in BartTokenizerTest.
 */
class BartTokenizer(vocabJson: String, merges: String) {
    private val vocab: Map<String, Int>
    private val byId: Map<Int, String>
    private val ranks: Map<Pair<String, String>, Int>
    private val cache = HashMap<String, List<String>>()

    init {
        val o = JSONObject(vocabJson)
        val v = HashMap<String, Int>(o.length() * 2)
        o.keys().forEach { k -> v[k] = o.getInt(k) }
        vocab = v
        byId = v.entries.associate { (k, id) -> id to k }
        val r = HashMap<Pair<String, String>, Int>()
        var rank = 0
        for (line in merges.lineSequence()) {
            if (line.isEmpty() || line.startsWith("#version")) continue
            val sp = line.indexOf(' '); if (sp <= 0) continue
            r[line.substring(0, sp) to line.substring(sp + 1)] = rank++
        }
        ranks = r
    }

    /** "<s> text </s>" as ids. */
    fun encode(text: String): LongArray {
        val out = ArrayList<Long>()
        out += BOS.toLong()
        for (m in PRE.findAll(text)) {
            val mapped = buildString { for (b in m.value.toByteArray(Charsets.UTF_8)) append(BYTE_TO_CHAR[b.toInt() and 0xff]) }
            for (piece in bpe(mapped)) out += (vocab[piece] ?: UNK).toLong()
        }
        out += EOS.toLong()
        return out.toLongArray()
    }

    /** The words for [ids], leaving out special and location tokens. */
    fun decode(ids: List<Long>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id in SPECIAL || id >= byId.size) continue
            val piece = byId[id.toInt()] ?: continue
            for (ch in piece) CHAR_TO_BYTE[ch]?.let { bytes.write(it) }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var word = token.map { it.toString() }
        while (word.size > 1) {
            var best = -1; var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val rk = ranks[word[i] to word[i + 1]] ?: continue
                if (rk < bestRank) { bestRank = rk; best = i }
            }
            if (best < 0) break
            val a = word[best]; val b = word[best + 1]
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == a && word[i + 1] == b) { merged += a + b; i += 2 } else { merged += word[i]; i++ }
            }
            word = merged
        }
        if (cache.size < 4096) cache[token] = word
        return word
    }

    companion object {
        const val BOS = 0; const val PAD = 1; const val EOS = 2; const val UNK = 3
        private val SPECIAL = setOf(BOS.toLong(), PAD.toLong(), EOS.toLong(), UNK.toLong())

        /** GPT-2's pre-tokenizer: contractions, letters, numbers, other symbols, spaces (a space joins the next word). */
        private val PRE = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        /** GPT-2's reversible byte → printable character table. */
        private val BYTE_TO_CHAR: CharArray = run {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            val t = CharArray(256)
            for (i in bs.indices) t[bs[i]] = cs[i].toChar()
            t
        }
        private val CHAR_TO_BYTE: Map<Char, Int> = BYTE_TO_CHAR.withIndex().associate { (b, c) -> c to b }
    }
}
