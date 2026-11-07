package cc.bridgit.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max

/**
 * Runs the opt-in on-phone engines ([OnDeviceEngines]) with ONNX Runtime: Florence-2 base finds things named in
 * any words, MobileSAM outlines boxes. Only when a caller names them; never part of the default routing.
 *
 * What the A11 benchmark taught (docs/design/on-device-detection-benchmark.md): one inference at a time ([lock]),
 * ONNX Runtime's memory arena and pattern planning off (Android killed Florence-2 for low memory with them on),
 * and the models leave memory [IDLE_MS] after their last use.
 */
class OnDeviceVision(private val ctx: Context, val models: OnDeviceModels) {
    class NotReady(val engine: OnDeviceEngines.Engine, val state: String, message: String) : Exception(message)

    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Loaded models, by folder (a new version from the manifest is a new folder, so it loads fresh). */
    private var florence: Pair<String, List<OrtSession>>? = null
    private var sam: Pair<String, List<OrtSession>>? = null
    private var idleJob: Job? = null
    private val tokenizer by lazy {
        BartTokenizer(ctx.assets.open("florence2/vocab.json").use { it.readBytes().toString(Charsets.UTF_8) },
            ctx.assets.open("florence2/merges.txt").use { it.readBytes().toString(Charsets.UTF_8) })
    }

    /**
     * ONNX Runtime, loaded from its downloaded libraries (it isn't in the APK). Returns false while they download;
     * throws [NotReady] for [e] when this phone has no runtime build or the libraries won't load.
     */
    fun loadRuntime(e: OnDeviceEngines.Engine? = null): Boolean {
        if (OrtLoader.loaded) return true
        val rt = models.runtime ?: throw NotReady(e ?: return false, OnDeviceModels.UNSUPPORTED, "There is no on-phone runtime for this phone's processor (${android.os.Build.SUPPORTED_ABIS.joinToString()}).")
        val fs = models.files(rt) ?: run { models.ensure(e ?: rt, remember = e != null); return false }
        try { OrtLoader.load(fs) } catch (x: Throwable) {
            throw NotReady(e ?: rt, OnDeviceModels.FAILED, "The on-phone runtime didn't load (${x.message ?: x.javaClass.simpleName}).")
        }
        return true
    }

    /** The engine called [name], or [NotReady] / [Unknown] when the manifest has none this app can run. */
    fun engine(name: String): OnDeviceEngines.Engine = models.engine(name) ?: throw Unknown(name, models.whyNot(name) ?: "unknown engine")

    class Unknown(val name: String, message: String) : Exception(message)

    /** Throws [NotReady] (and starts the download) unless [name] can run now. */
    fun requireReady(name: String) { resolve(name) }

    /**
     * The engine version that runs for [name] now and its files: the manifest's active one, or, while that one is
     * still downloading or failed to check, the earlier version still on the phone. Else [NotReady] (download started).
     */
    fun resolve(name: String): Pair<OnDeviceEngines.Engine, List<File>> {
        val e = engine(name)
        models.files(e)?.takeIf { loadRuntime(e) }?.let { return e to it }
        // A new version that is still downloading, or failed, doesn't take the working one away.
        models.fallback(name, e)?.let { old -> models.files(old)?.takeIf { loadRuntime(old) }?.let { models.ensure(e); return old to it } }
        return e to files(e)
    }

    /** The engine's files, or [NotReady] after starting the download (an explicit request never falls back). */
    private fun files(e: OnDeviceEngines.Engine): List<File> = models.files(e)?.takeIf { loadRuntime(e) } ?: run {
        models.ensure(e)
        val rt = models.runtime?.let { models.state(it) }
        val s = if (models.files(e) != null && rt != null) rt else models.state(e)
        val why = when (s.state) {
            OnDeviceModels.DOWNLOADING -> if (s === rt) "The on-phone runtime for ${e.id} is downloading (${Math.round(s.doneBytes / 1e6)} of ${models.runtime!!.sizeMb} MB)."
                else "The ${e.id} model is downloading (${Math.round(s.doneBytes / 1e6)} of ${e.sizeMb} MB)."
            OnDeviceModels.WAITING_FOR_WIFI -> "The ${e.id} model (${e.sizeMb} MB) downloads on Wi-Fi only; the phone is on a metered network."
            OnDeviceModels.FAILED -> "The ${e.id} model couldn't be downloaded: ${s.reason}. Bridgit retries on its own."
            else -> "The ${e.id} model (${e.sizeMb} MB) isn't on the phone yet; the download has started."
        }
        throw NotReady(e, if (s.state == OnDeviceModels.NOT_DOWNLOADED) OnDeviceModels.DOWNLOADING else s.state, why)
    }

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setCPUArenaAllocator(false); setMemoryPatternOptimization(false)
    }

    private fun scheduleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            lock.withLock { florence?.second?.forEach { it.close() }; florence = null; sam?.second?.forEach { it.close() }; sam = null }
        }
    }

    /** The sessions for [e]'s files in [order] (matched by file-name start), reusing loaded ones of the same folder. */
    private fun sessions(cur: Pair<String, List<OrtSession>>?, e: OnDeviceEngines.Engine, fs: List<File>, order: List<String>): Pair<String, List<OrtSession>> {
        if (cur?.first == e.folder) return cur
        cur?.second?.forEach { it.close() }
        val picked = order.map { start -> fs.firstOrNull { it.name.startsWith(start) } ?: throw NotReady(e, OnDeviceModels.FAILED, "The ${e.model} model has no $start file.") }
        return e.folder to picked.map { env.createSession(it.path, options()) }
    }

    /** Things matching [query] with the detector engine [name] (Florence-2 "<OPEN_VOCABULARY_DETECTION>"), most prominent first. */
    suspend fun detect(bmp: Bitmap, query: String, max: Int, name: String = OnDeviceEngines.DETECTOR): List<FindRules.Item> {
        if (engine(name).pipeline != "florence2-ovd/1") throw Unknown(name, "$name is not a detector")
        val (e, fs) = resolve(name)
        return lock.withLock {
            withContext(Dispatchers.Default) {
                idleJob?.cancel()
                val s = sessions(florence, e, fs, listOf("vision_encoder", "embed_tokens", "encoder_model", "decoder_model_merged")).also { florence = it }
                try { runFlorence(s.second, bmp, phrase(query), max) } finally { scheduleRelease() }
            }
        }
    }

    /** An outline per box, as [x, y] fractions of the photo (empty when nothing stood out in the box). */
    suspend fun outline(bmp: Bitmap, boxes: List<FindRules.Box>): List<List<DoubleArray>> {
        if (boxes.isEmpty()) return emptyList()
        val (e, fs) = resolve(OnDeviceEngines.OUTLINE)
        return lock.withLock {
            withContext(Dispatchers.Default) {
                idleJob?.cancel()
                val s = sessions(sam, e, fs, listOf("mobile_sam_image_encoder", "sam_mask_decoder")).also { sam = it }
                try { runSam(s.second[0], s.second[1], bmp, boxes) } finally { scheduleRelease() }
            }
        }
    }

    private fun copy(t: OnnxTensor): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.allocate(t.floatBuffer.remaining()).put(t.floatBuffer).also { it.rewind() }, t.info.shape)

    private fun runFlorence(s: List<OrtSession>, src: Bitmap, phrase: String, max: Int): List<FindRules.Item> {
        val (vis, emb, enc, dec) = s
        fun embed(ids: LongArray): OnnxTensor =
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t -> emb.run(mapOf("input_ids" to t)).use { r -> copy(r.get(0) as OnnxTensor) } }
        val small = Bitmap.createScaledBitmap(src, 768, 768, true)
        val px = chw(small, floatArrayOf(0.485f, 0.456f, 0.406f), floatArrayOf(0.229f, 0.224f, 0.225f))
        if (small !== src) small.recycle()
        val feats = px.use { vis.run(mapOf("pixel_values" to it)).use { r -> copy(r.get(0) as OnnxTensor) } }
        val text = embed(tokenizer.encode("Locate $phrase in the image."))
        val nImg = feats.info.shape[1].toInt(); val nTxt = text.info.shape[1].toInt(); val seq = nImg + nTxt
        val all = FloatBuffer.allocate(seq * 768).put(feats.floatBuffer).put(text.floatBuffer).also { it.rewind() }
        feats.close(); text.close()
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seq) { 1 }), longArrayOf(1, seq.toLong()))
        val hidden = OnnxTensor.createTensor(env, all, longArrayOf(1, seq.toLong(), 768)).use { e ->
            enc.run(mapOf("attention_mask" to mask, "inputs_embeds" to e)).use { r -> copy(r.get(0) as OnnxTensor) }
        }
        val empty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, 12, 0, 64))
        val past = HashMap<String, OnnxTensor>()
        for (l in 0 until 6) for (k in listOf("decoder.key", "decoder.value", "encoder.key", "encoder.value")) past["past_key_values.$l.$k"] = empty
        val outNames = dec.outputNames.toList()
        var first: OrtSession.Result? = null; var prev: OrtSession.Result? = null
        val tokens = ArrayList<Long>(); var cur = BartTokenizer.EOS.toLong()
        try {
            for (step in 0 until MAX_TOKENS) {
                val ie = embed(longArrayOf(cur))
                val branch = OnnxTensor.createTensor(env, booleanArrayOf(step > 0))
                val r = try { dec.run(mapOf("encoder_attention_mask" to mask, "encoder_hidden_states" to hidden, "inputs_embeds" to ie, "use_cache_branch" to branch) + past) }
                    finally { ie.close(); branch.close() }
                for ((j, name) in outNames.withIndex()) {
                    if (j == 0 || (step > 0 && "encoder" in name)) continue
                    past[name.replace("present", "past_key_values")] = r.get(j) as OnnxTensor
                }
                if (step == 0) first = r else { prev?.close(); prev = r }
                val logits = (r.get(0) as OnnxTensor).floatBuffer
                var best = 0; var bv = Float.NEGATIVE_INFINITY
                for (v in 0 until logits.remaining()) { val x = logits.get(v); if (x > bv) { bv = x; best = v } }
                cur = if (step == 0) BartTokenizer.BOS.toLong() else best.toLong()
                if (cur == BartTokenizer.EOS.toLong()) break
                tokens += cur
            }
        } finally { prev?.close(); first?.close(); empty.close(); hidden.close(); mask.close() }
        return parse(tokens, LOC0) { tokenizer.decode(it).trim() }
            .map { (label, b) -> FindRules.Item(label.ifEmpty { phrase }, FindRules.Box(b[0], b[1], b[2], b[3])) }
            .filter { it.box.x1 > it.box.x0 && it.box.y1 > it.box.y0 }
            .take(max)
    }

    private fun runSam(enc: OrtSession, dec: OrtSession, src: Bitmap, boxes: List<FindRules.Box>): List<List<DoubleArray>> {
        val sc = 1024f / max(src.width, src.height)
        val rs = Bitmap.createScaledBitmap(src, Math.round(src.width * sc), Math.round(src.height * sc), true)
        val px = IntArray(rs.width * rs.height); rs.getPixels(px, 0, rs.width, 0, 0, rs.width, rs.height)
        val rw = rs.width; val rh = rs.height
        if (rs !== src) rs.recycle()
        val hwc = FloatBuffer.allocate(px.size * 3)
        for (p in px) { hwc.put(Color.red(p).toFloat()); hwc.put(Color.green(p).toFloat()); hwc.put(Color.blue(p).toFloat()) }
        hwc.rewind()
        val emb = OnnxTensor.createTensor(env, hwc, longArrayOf(rh.toLong(), rw.toLong(), 3)).use { t ->
            enc.run(mapOf("input_image" to t)).use { r -> copy(r.get(0) as OnnxTensor) }
        }
        // The mask comes back at a small size of the photo's shape: enough for an outline, and cheap to make.
        val mw = Math.round(rw * MASK_EDGE / 1024f).coerceAtLeast(8); val mh = Math.round(rh * MASK_EDGE / 1024f).coerceAtLeast(8)
        val noMask = OnnxTensor.createTensor(env, FloatBuffer.allocate(256 * 256), longArrayOf(1, 1, 256, 256))
        val hasMask = OnnxTensor.createTensor(env, floatArrayOf(0f))
        val size = OnnxTensor.createTensor(env, floatArrayOf(mh.toFloat(), mw.toFloat()))
        try {
            return boxes.map { b ->
                val pts = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf((b.x0 * rw).toFloat(), (b.y0 * rh).toFloat(), (b.x1 * rw).toFloat(), (b.y1 * rh).toFloat())), longArrayOf(1, 2, 2))
                val labels = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(2f, 3f)), longArrayOf(1, 2))
                try {
                    dec.run(mapOf("image_embeddings" to emb, "point_coords" to pts, "point_labels" to labels, "mask_input" to noMask,
                        "has_mask_input" to hasMask, "orig_im_size" to size), setOf("masks")).use { r ->
                        val t = r.get(0) as OnnxTensor; val shape = t.info.shape
                        val w = shape[3].toInt(); val h = shape[2].toInt(); val f = t.floatBuffer
                        Outlines.polygon(BooleanArray(w * h) { f.get(it) > 0f }, w, h)
                    }
                } finally { pts.close(); labels.close() }
            }
        } finally { noMask.close(); hasMask.close(); size.close(); emb.close() }
    }

    private fun chw(b: Bitmap, mean: FloatArray, std: FloatArray): OnnxTensor {
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(3 * w * h)
        for (c in 0 until 3) for (p in px) {
            val v = when (c) { 0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p) } / 255f
            buf.put((v - mean[c]) / std[c])
        }
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    companion object {
        const val IDLE_MS = 60_000L
        const val MAX_TOKENS = 256
        const val MASK_EDGE = 320
        /** Florence-2's <loc_0>; <loc_N> is N/1000 of the photo's side. */
        const val LOC0 = 50269L

        private val LEAD = Regex("^(?:(?:find|locate|show me|where is|where are)\\s+)?(?:(?:all of the|all the|each of the|each|every|all|any|the|a|an)\\s+)+", RegexOption.IGNORE_CASE)

        private val OF_EACH = Regex("^(.+?) (?:of|on|in) (?:each|every|all(?: the)?|the) (.+)$", RegexOption.IGNORE_CASE)

        /**
         * The thing to look for, worded the way Florence-2 answers best (checked on the benchmark photos): leading
         * "find / each / the ..." dropped; "the face of each person" → "faces" (the plural finds every one, while
         * "face of person" found one wrong box). Pure.
         */
        fun phrase(query: String): String {
            val q = query.trim().trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
            var p = q.replace(LEAD, "").trim()
            OF_EACH.matchEntire(p)?.let { m -> p = plural(m.groupValues[1].replace(LEAD, "").trim()) }
            return p.ifEmpty { q }
        }

        /** Rough English plural of the last word: dish → dishes, puppy → puppies, face → faces. Pure. */
        fun plural(words: String): String {
            val i = words.lastIndexOf(' ') + 1; val w = words.substring(i); val head = words.substring(0, i)
            val lw = w.lowercase()
            return head + when {
                lw.endsWith("s") || lw.endsWith("x") || lw.endsWith("ch") || lw.endsWith("sh") -> if (lw.endsWith("ss") || !lw.endsWith("s")) w + "es" else w
                lw.endsWith("y") && lw.length > 1 && lw[lw.length - 2] !in "aeiou" -> w.dropLast(1) + "ies"
                else -> w + "s"
            }
        }

        /** Florence-2's answer: a label, then 4 <loc_N> tokens per box. Returns (label, [x0, y0, x1, y1] fractions). Pure. */
        fun parse(tokens: List<Long>, loc0: Long, words: (List<Long>) -> String): List<Pair<String, DoubleArray>> {
            val out = ArrayList<Pair<String, DoubleArray>>()
            var label = ""; val text = ArrayList<Long>(); var locs = ArrayList<Int>()
            for (t in tokens) {
                val l = t - loc0
                if (l in 0..999) {
                    if (text.isNotEmpty()) { label = words(text); text.clear() }
                    locs += l.toInt()
                    if (locs.size == 4) {
                        out += label to doubleArrayOf((locs[0] + 0.5) / 1000, (locs[1] + 0.5) / 1000, (locs[2] + 0.5) / 1000, (locs[3] + 0.5) / 1000)
                        locs = ArrayList()
                    }
                } else { locs = ArrayList(); text += t }
            }
            return out
        }
    }
}
