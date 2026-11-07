package cc.bridgit.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Debug
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import kotlin.concurrent.thread
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Debug-only on-device detection benchmark (docs/design/on-device-detection-benchmark.md). Not in release builds.
 *
 *   adb push bench/ /sdcard/Android/data/<pkg>/files/bench/      (img/<photo>.jpg, queries.json, models/)
 *   adb shell am start-foreground-service -n <pkg>/cc.bridgit.app.DetectBenchService \
 *       --es model owlv2 [--ei runs 3] [--ei threads 4] [--es ep xnnpack]
 *
 * A foreground service, started by the shell: as a plain broadcast the cached process got frozen mid-run (one run took 228 s),
 * and the app itself may not start a foreground service from the background.
 *
 * model: effdet0 | effdet2 (MediaPipe Object Detector), owlv2 | owlvit (ONNX Runtime), florence (Florence-2 base, ONNX Runtime),
 * mobilesam (outlines for the boxes in bench-out/result-florence.json, so run florence first).
 * Writes bench-out/result-<model>.json and logs "detect_bench done <model>" under the tag BridgitBench.
 * Questions arrive pre-tokenised in queries.json, so no tokenizer ships on the phone.
 */
class DetectBenchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val note = NotificationCompat.Builder(this, BridgeApp.CHANNEL_LISTENER).setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Detection benchmark").setOngoing(true).build()
        startForeground(77, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val i = intent ?: return START_NOT_STICKY
        val model = i.getStringExtra("model") ?: return START_NOT_STICKY.also { stopSelf() }
        val runs = i.getIntExtra("runs", 3)
        val threads = i.getIntExtra("threads", 4)
        val ep = i.getStringExtra("ep") ?: "cpu"
        val ctx = applicationContext
        thread(name = "detect-bench") {
            val dir = requireNotNull(ctx.getExternalFilesDir("bench")) // created by the app first, then filled over adb
            val out = requireNotNull(ctx.getExternalFilesDir("bench-out")) // the app must create it; adb-made folders are read-only to the app
            for (d in listOf("img", "models/effdet", "models/owlv2", "models/owlvit", "models/florence", "models/mobilesam")) File(dir, d).mkdirs()
            val tag = if (ep == "cpu") model else "$model-$ep"
            try {
                // ONNX Runtime's libraries aren't in the APK: wait for them to download, then load them (vision/OrtLoader).
                val vision = (ctx as BridgeApp).onDeviceVision
                val until = SystemClock.elapsedRealtime() + 600_000
                while (!vision.loadRuntime()) { check(SystemClock.elapsedRealtime() < until) { "ONNX Runtime didn't download in 10 minutes" }; SystemClock.sleep(2000) }
                val res = DetectBench(ctx, dir, threads, runs, ep).run(model)
                File(out, "result-$tag.json").writeText(res.toString(1))
                Log.i(TAG, "detect_bench done $tag")
            } catch (t: Throwable) {
                Log.e(TAG, "detect_bench failed $tag", t)
                File(out, "result-$tag.json").writeText(JSONObject().put("model", tag).put("error", t.toString()).toString())
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object { const val TAG = "BridgitBench" }
}

private class Box(val label: String, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun json() = JSONObject().put("label", label).put("score", score.toDouble())
        .put("box", JSONArray(listOf(x1, y1, x2, y2).map { it.toDouble() }))
    fun iou(o: Box): Float {
        val w = max(0f, min(x2, o.x2) - max(x1, o.x1)); val h = max(0f, min(y2, o.y2) - max(y1, o.y1))
        val i = w * h; val u = (x2 - x1) * (y2 - y1) + (o.x2 - o.x1) * (o.y2 - o.y1) - i
        return if (u > 0) i / u else 0f
    }
}

private fun nms(boxes: List<Box>, thr: Float = 0.3f): List<Box> {
    val keep = ArrayList<Box>()
    for (b in boxes.sortedByDescending { it.score }) if (keep.all { b.iou(it) < thr }) keep += b
    return keep
}

/** One answer: boxes plus optional per-stage timings / raw tokens. */
private class Answer(val boxes: List<Box>, val extra: JSONObject = JSONObject())

private class DetectBench(val ctx: Context, val dir: File, val threads: Int, val runs: Int, val ep: String) {
    private val env = OrtEnvironment.getEnvironment()
    private val queries = JSONObject(File(dir, "queries.json").readText())
    private val photos = HashMap<String, Bitmap>()
    @Volatile private var peakRssKb = 0L
    @Volatile private var sampling = true

    private fun rssKb(): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
        ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L

    private fun photo(name: String) = photos.getOrPut(name) {
        BitmapFactory.decodeFile(File(dir, "img/$name.jpg").path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    fun run(model: String): JSONObject {
        System.gc(); SystemClock.sleep(300)
        val baseRss = rssKb(); val basePss = Debug.getPss()
        peakRssKb = baseRss
        val sampler = thread(name = "bench-mem") { while (sampling) { peakRssKb = max(peakRssKb, rssKb()); SystemClock.sleep(25) } }
        val files = ArrayList<File>()
        val t0 = SystemClock.elapsedRealtime()
        val runner: (JSONObject) -> Answer? = when (model) {
            "effdet0", "effdet2" -> effdet(File(dir, "models/effdet/efficientdet_lite${model.last()}.tflite").also { files += it })
            "owlv2" -> owl(File(dir, "models/owlv2/model_quantized.onnx").also { files += it }, "owlv2", 960, pad = true)
            "owlvit" -> owl(File(dir, "models/owlvit/model_quantized.onnx").also { files += it }, "owlvit", 768, pad = false)
            "mobilesam" -> mobileSam(File(dir, "models/mobilesam/mobile_sam_image_encoder.onnx").also { files += it },
                File(dir, "models/mobilesam/sam_mask_decoder_single.onnx").also { files += it })
            "florence" -> florence(listOf("vision_encoder", "embed_tokens", "encoder_model", "decoder_model_merged")
                .map { File(dir, "models/florence/${it}_quantized.onnx").also { f -> files += f } })
            else -> error("unknown model $model")
        }
        val loadMs = SystemClock.elapsedRealtime() - t0
        val rssAfterLoad = rssKb()
        val items = JSONArray()
        val qs = queries.getJSONArray("questions")
        for (i in 0 until qs.length()) {
            val q = qs.getJSONObject(i)
            val cold = SystemClock.elapsedRealtime()
            val first = runner(q)
            val coldMs = SystemClock.elapsedRealtime() - cold
            val item = JSONObject().put("photo", q.getString("photo")).put("q", q.getString("q"))
            if (first == null) { items.put(item.put("supported", false)); continue }
            val warm = (1..runs).map { val s = SystemClock.elapsedRealtime(); runner(q); SystemClock.elapsedRealtime() - s }
            item.put("supported", true).put("coldMs", coldMs).put("warmMs", JSONArray(warm)).put("medianMs", warm.sorted()[warm.size / 2])
                .put("boxes", JSONArray(first.boxes.map { it.json() })).put("extra", first.extra)
            Log.i(DetectBenchService.TAG, "$model ${q.getString("q")}: ${first.boxes.size} boxes, median ${warm.sorted()[warm.size / 2]} ms")
            items.put(item)
        }
        sampling = false; sampler.join()
        val pssEnd = Debug.getPss()
        return JSONObject().put("model", model).put("threads", threads).put("ep", ep).put("runs", runs)
            .put("device", "${android.os.Build.MODEL} ${android.os.Build.SOC_MODEL}")
            .put("files", JSONArray(files.map { JSONObject().put("name", it.name).put("bytes", it.length()) }))
            .put("diskMb", files.sumOf { it.length() } / 1e6)
            .put("loadMs", loadMs).put("baseRssMb", baseRss / 1024.0).put("rssAfterLoadMb", rssAfterLoad / 1024.0)
            .put("peakRssMb", peakRssKb / 1024.0).put("peakDeltaMb", (peakRssKb - baseRss) / 1024.0)
            .put("basePssMb", basePss / 1024.0).put("endPssMb", pssEnd / 1024.0)
            .put("items", items)
    }

    private fun options() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Lower peak memory: on the A11 Florence-2 was killed for low memory with the default arena (5 Nov 2026).
        setCPUArenaAllocator(false); setMemoryPatternOptimization(false)
        if (ep == "xnnpack") addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        if (ep == "nnapi") addNnapi()
    }

    /** NCHW float tensor from a bitmap already at the model's input size. */
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

    private fun longs(a: JSONArray) = LongArray(a.length()) { a.getLong(it) }

    // ---- Outline add-on: MobileSAM turns each Florence-2 box (bench-out/result-florence.json) into an outline ----
    private fun mobileSam(encF: File, decF: File): (JSONObject) -> Answer? {
        val enc = env.createSession(encF.path, options()); val dec = env.createSession(decF.path, options())
        val prior = JSONObject(File(ctx.getExternalFilesDir("bench-out"), "result-florence.json").readText()).getJSONArray("items")
        return { q ->
            val item = (0 until prior.length()).map { prior.getJSONObject(it) }.first { it.getString("q") == q.getString("q") }
            val src = photo(q.getString("photo")); val sc = 1024f / max(src.width, src.height)
            val t0 = SystemClock.elapsedRealtime()
            val rs = Bitmap.createScaledBitmap(src, Math.round(src.width * sc), Math.round(src.height * sc), true)
            val px = IntArray(rs.width * rs.height); rs.getPixels(px, 0, rs.width, 0, 0, rs.width, rs.height)
            val hwc = FloatBuffer.allocate(px.size * 3); for (p in px) { hwc.put(Color.red(p).toFloat()); hwc.put(Color.green(p).toFloat()); hwc.put(Color.blue(p).toFloat()) }
            hwc.rewind()
            val input = OnnxTensor.createTensor(env, hwc, longArrayOf(rs.height.toLong(), rs.width.toLong(), 3))
            val emb = enc.run(mapOf("input_image" to input)).use { r ->
                val t = r.get(0) as OnnxTensor
                OnnxTensor.createTensor(env, FloatBuffer.allocate(t.floatBuffer.remaining()).put(t.floatBuffer).also { it.rewind() }, t.info.shape)
            }
            input.close()
            val t1 = SystemClock.elapsedRealtime()
            val boxes = ArrayList<Box>(); val masks = JSONArray()
            val noMask = OnnxTensor.createTensor(env, FloatBuffer.allocate(256 * 256), longArrayOf(1, 1, 256, 256))
            val hasMask = OnnxTensor.createTensor(env, floatArrayOf(0f))
            val size = OnnxTensor.createTensor(env, floatArrayOf(src.height.toFloat(), src.width.toFloat()))
            val bx = item.getJSONArray("boxes")
            for (k in 0 until bx.length()) {
                val b = bx.getJSONObject(k).getJSONArray("box")
                val pts = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(b.getDouble(0).toFloat() * sc, b.getDouble(1).toFloat() * sc,
                    b.getDouble(2).toFloat() * sc, b.getDouble(3).toFloat() * sc)), longArrayOf(1, 2, 2))
                val labels = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(2f, 3f)), longArrayOf(1, 2))
                dec.run(mapOf("image_embeddings" to emb, "point_coords" to pts, "point_labels" to labels, "mask_input" to noMask,
                    "has_mask_input" to hasMask, "orig_im_size" to size), setOf("low_res_masks", "iou_predictions")).use { r ->
                    // 256x256 mask over the 1024-pixel padded frame, one bit per cell, packed for drawing on the Mac.
                    val low = (r.get("low_res_masks").get() as OnnxTensor).floatBuffer
                    val bits = ByteArray(256 * 256 / 8); var x1 = 256; var y1 = 256; var x2 = -1; var y2 = -1
                    for (i in 0 until 256 * 256) if (low.get(i) > 0f) {
                        bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
                        val x = i % 256; val y = i / 256; x1 = min(x1, x); y1 = min(y1, y); x2 = max(x2, x); y2 = max(y2, y)
                    }
                    val iou = (r.get("iou_predictions").get() as OnnxTensor).floatBuffer.get(0)
                    val f = 1024f / 256f / sc
                    boxes += Box(q.getString("phrase"), iou, x1 * f, y1 * f, (x2 + 1) * f, (y2 + 1) * f)
                    masks.put(android.util.Base64.encodeToString(bits, android.util.Base64.NO_WRAP))
                }
                pts.close(); labels.close()
            }
            noMask.close(); hasMask.close(); size.close(); emb.close()
            val t2 = SystemClock.elapsedRealtime()
            Answer(boxes, JSONObject().put("encoderMs", t1 - t0).put("decoderMs", t2 - t1).put("masks", masks).put("scale", sc.toDouble()))
        }
    }

    // ---- Fixed list: MediaPipe Object Detector + EfficientDet-Lite (COCO 80 labels) ----
    private fun effdet(f: File): (JSONObject) -> Answer? {
        val buf = RandomAccessFile(f, "r").channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        val det = ObjectDetector.createFromOptions(ctx, ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).setDelegate(Delegate.CPU).build())
            .setMaxResults(40).setScoreThreshold(0.25f).build())
        return { q ->
            val wanted = q.getJSONArray("coco").let { a -> (0 until a.length()).map { a.getString(it) } }
            if (wanted.isEmpty()) null else {
                val r = det.detect(BitmapImageBuilder(photo(q.getString("photo"))).build())
                var boxes = r.detections().mapNotNull { d ->
                    val c = d.categories().first(); if (c.categoryName() !in wanted) null
                    else d.boundingBox().let { Box(c.categoryName(), c.score(), it.left, it.top, it.right, it.bottom) }
                }
                if (!q.getBoolean("many")) boxes = boxes.sortedByDescending { it.score }.take(1)
                Answer(boxes)
            }
        }
    }

    // ---- Any word: OWLv2 / OWL-ViT (text query -> boxes) ----
    private fun owl(f: File, key: String, size: Int, pad: Boolean): (JSONObject) -> Answer? {
        val s = env.createSession(f.path, options())
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f); val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        return { q ->
            val src = photo(q.getString("photo"))
            val sw: Float; val sh: Float
            val input = if (pad) {
                val side = max(src.width, src.height); sw = side.toFloat(); sh = side.toFloat()
                val sq = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                Canvas(sq).apply { drawColor(Color.rgb(128, 128, 128)); drawBitmap(src, 0f, 0f, null) }
                Bitmap.createScaledBitmap(sq, size, size, true)
            } else { sw = src.width.toFloat(); sh = src.height.toFloat(); Bitmap.createScaledBitmap(src, size, size, true) }
            val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(longs(q.getJSONArray("${key}_ids"))), longArrayOf(1, 16))
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(longs(q.getJSONArray("${key}_mask"))), longArrayOf(1, 16))
            val px = chw(input, mean, std)
            s.run(mapOf("input_ids" to ids, "pixel_values" to px, "attention_mask" to mask), setOf("logits", "pred_boxes")).use { r ->
                val logits = (r.get("logits").get() as OnnxTensor).floatBuffer
                val pb = (r.get("pred_boxes").get() as OnnxTensor).floatBuffer
                val n = logits.remaining()
                val many = q.getBoolean("many")
                val cands = (0 until n).map { it to (1f / (1f + exp(-logits.get(it)))) }.sortedByDescending { it.second }
                val boxes = ArrayList<Box>()
                for ((i, sc) in cands.take(200)) {
                    if (sc < 0.1f && (boxes.isNotEmpty() || many)) break
                    val cx = pb.get(4 * i); val cy = pb.get(4 * i + 1); val w = pb.get(4 * i + 2); val h = pb.get(4 * i + 3)
                    boxes += Box(q.getString("phrase"), sc, (cx - w / 2) * sw, (cy - h / 2) * sh, (cx + w / 2) * sw, (cy + h / 2) * sh)
                }
                ids.close(); mask.close(); px.close()
                val kept = nms(boxes)
                Answer(if (many) kept else kept.take(1))
            }
        }
    }

    // ---- Small vision model: Florence-2 base, "<OPEN_VOCABULARY_DETECTION>" prompt, greedy decode ----
    private fun florence(files: List<File>): (JSONObject) -> Answer? {
        val (vis, emb, enc, dec) = files.map { env.createSession(it.path, options()) }
        val loc0 = queries.getInt("florence_loc0")
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val layers = 6
        fun embed(ids: LongArray): OnnxTensor = emb.run(mapOf("input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())))).use { r ->
            val t = r.get(0) as OnnxTensor; val shape = t.info.shape
            val copy = FloatBuffer.allocate(t.floatBuffer.remaining()).put(t.floatBuffer).also { it.rewind() }
            OnnxTensor.createTensor(env, copy, shape)
        }
        return { q ->
            val src = photo(q.getString("photo")); val W = src.width.toFloat(); val H = src.height.toFloat()
            val t0 = SystemClock.elapsedRealtime()
            val px = chw(Bitmap.createScaledBitmap(src, 768, 768, true), mean, std)
            val feats = vis.run(mapOf("pixel_values" to px)).use { r ->
                val t = r.get(0) as OnnxTensor; Pair(FloatBuffer.allocate(t.floatBuffer.remaining()).put(t.floatBuffer).also { it.rewind() }, t.info.shape)
            }
            px.close()
            val t1 = SystemClock.elapsedRealtime()
            val textEmb = embed(longs(q.getJSONArray("florence_ids")))
            val nImg = feats.second[1].toInt(); val nTxt = textEmb.info.shape[1].toInt(); val seq = nImg + nTxt
            val all = FloatBuffer.allocate(seq * 768).put(feats.first).put(textEmb.floatBuffer); all.rewind(); textEmb.close()
            val embeds = OnnxTensor.createTensor(env, all, longArrayOf(1, seq.toLong(), 768))
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(seq) { 1 }), longArrayOf(1, seq.toLong()))
            val hidden = enc.run(mapOf("attention_mask" to mask, "inputs_embeds" to embeds)).use { r ->
                val t = r.get(0) as OnnxTensor
                OnnxTensor.createTensor(env, FloatBuffer.allocate(t.floatBuffer.remaining()).put(t.floatBuffer).also { it.rewind() }, t.info.shape)
            }
            embeds.close()
            val t2 = SystemClock.elapsedRealtime()
            val empty = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, 12, 0, 64))
            val past = HashMap<String, OnnxTensor>()
            for (l in 0 until layers) for (k in listOf("decoder.key", "decoder.value", "encoder.key", "encoder.value")) past["past_key_values.$l.$k"] = empty
            val outNames = dec.outputNames.toList()
            var first: OrtSession.Result? = null; var prev: OrtSession.Result? = null
            val tokens = ArrayList<Long>(); var cur = 2L
            for (step in 0 until 256) {
                val ie = embed(longArrayOf(cur))
                val branch = OnnxTensor.createTensor(env, booleanArrayOf(step > 0))
                val r = dec.run(mapOf("encoder_attention_mask" to mask, "encoder_hidden_states" to hidden, "inputs_embeds" to ie, "use_cache_branch" to branch) + past)
                ie.close(); branch.close()
                val logits = (r.get(0) as OnnxTensor).floatBuffer
                for ((j, name) in outNames.withIndex()) {
                    if (j == 0 || (step > 0 && "encoder" in name)) continue
                    past[name.replace("present", "past_key_values")] = r.get(j) as OnnxTensor
                }
                if (step == 0) first = r else { prev?.close(); prev = r }
                var best = 0; var bv = Float.NEGATIVE_INFINITY
                for (v in 0 until logits.remaining()) { val x = logits.get(v); if (x > bv) { bv = x; best = v } }
                cur = if (step == 0) 0L else best.toLong()
                if (cur == 2L) break
                tokens += cur
            }
            prev?.close(); first?.close(); empty.close(); hidden.close(); mask.close()
            val t3 = SystemClock.elapsedRealtime()
            // Every run of 4 consecutive <loc_N> tokens is one box; N is a 1/1000 bin of the image side.
            val boxes = ArrayList<Box>(); var run = ArrayList<Int>()
            for (t in tokens + listOf(-1L)) {
                val l = (t - loc0).toInt()
                if (t >= loc0 && l < 1000) { run += l; if (run.size == 4) { boxes += Box(q.getString("phrase"), 1f,
                    (run[0] + .5f) / 1000 * W, (run[1] + .5f) / 1000 * H, (run[2] + .5f) / 1000 * W, (run[3] + .5f) / 1000 * H); run = ArrayList() } }
                else run = ArrayList()
            }
            Answer(if (q.getBoolean("many")) boxes else boxes.take(1), JSONObject().put("visionMs", t1 - t0).put("encoderMs", t2 - t1)
                .put("decodeMs", t3 - t2).put("tokens", tokens.size).put("ids", JSONArray(tokens)))
        }
    }
}
