package cc.bridgit.app.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Ids from the Hugging Face tokenizer of onnx-community/Florence-2-base-ft (tokenizers 0.22, 5 Nov 2026). */
class BartTokenizerTest {
    private val tok by lazy {
        val dir = listOf(File("src/main/assets/florence2"), File("app/src/main/assets/florence2")).first { it.isDirectory }
        BartTokenizer(File(dir, "vocab.json").readText(), File(dir, "merges.txt").readText())
    }

    private fun ids(text: String) = tok.encode(text)

    @Test fun matchesHuggingFace() {
        val cases = mapOf(
            "Locate dog in the image." to longArrayOf(0, 574, 22486, 2335, 11, 5, 2274, 4, 2),
            "Locate dog's face in the image." to longArrayOf(0, 574, 22486, 2335, 18, 652, 11, 5, 2274, 4, 2),
            "Locate dirty dish in the image." to longArrayOf(0, 574, 22486, 11216, 8847, 11, 5, 2274, 4, 2),
            "Locate remote control in the image." to longArrayOf(0, 574, 22486, 6063, 797, 11, 5, 2274, 4, 2),
            "Locate each DIRTY Dish! in the image." to longArrayOf(0, 574, 22486, 349, 211, 5216, 6175, 25847, 328, 11, 5, 2274, 4, 2),
            "Locate the café  au lait 2 mugs in the image." to longArrayOf(0, 574, 22486, 5, 26059, 1437, 8477, 784, 5236, 132, 475, 16148, 11, 5, 2274, 4, 2),
            "Locate   leading space in the image." to longArrayOf(0, 574, 22486, 1437, 1437, 981, 980, 11, 5, 2274, 4, 2),
            "Locate emoji 🐶 dog in the image." to longArrayOf(0, 574, 22486, 21554, 8103, 16948, 19002, 2335, 11, 5, 2274, 4, 2),
            "Locate ${"x".repeat(40)} in the image." to longArrayOf(0, 574, 22486, 3023, 42523, 42523, 42523, 42523, 47596, 46628, 11, 5, 2274, 4, 2),
        )
        for ((text, want) in cases) assertArrayEquals(text, want, ids(text))
    }

    @Test fun decodesWordsAndSkipsSpecialAndLocationTokens() {
        assertEquals("Locate's face", tok.decode(listOf(0L, 574, 22486, 18, 652, 50269, 50300, 2)))
        val t = "Locate the café  au lait in the image."
        assertEquals(t, tok.decode(ids(t).toList()))
    }
}
