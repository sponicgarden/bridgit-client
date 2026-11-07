package cc.bridgit.app.vision

import java.io.File

/**
 * Loads ONNX Runtime from libraries downloaded at run time instead of the APK ([OnDeviceEngines.RUNTIMES]).
 *
 * On Android, ONNX Runtime's own start-up ([ai.onnxruntime.OnnxRuntime].init) only calls System.loadLibrary, which
 * looks in the APK. So Bridgit loads the two files itself with System.load (allowed from the app's own folder; only
 * exec() is barred there) and then does the rest of init: the same native calls and fields, read for 1.22.0. With
 * `loaded` set, ONNX Runtime skips its own loading. Pinned to the version in build.gradle.kts and the RUNTIMES table.
 */
object OrtLoader {
    @Volatile var loaded = false; private set

    @Synchronized fun load(files: List<File>) {
        if (loaded) return
        val core = files.first { it.name == "libonnxruntime.so" }
        val jni = files.first { it.name == "libonnxruntime4j_jni.so" }
        System.load(core.absolutePath) // first, so the JNI library finds it already loaded
        System.load(jni.absolutePath)
        val c = Class.forName("ai.onnxruntime.OnnxRuntime")
        fun m(name: String, vararg types: Class<*>) = c.getDeclaredMethod(name, *types).apply { isAccessible = true }
        fun f(name: String) = c.getDeclaredField(name).apply { isAccessible = true }
        val int = Int::class.javaPrimitiveType!!; val long = Long::class.javaPrimitiveType!!
        val api = m("initialiseAPIBase", int).invoke(null, API_VERSION) as Long
        check(api != 0L) { "the runtime library doesn't match ONNX Runtime's classes" }
        f("ortApiHandle").setLong(null, api)
        val training = m("initialiseTrainingAPIBase", long, int).invoke(null, api, API_VERSION) as Long
        f("ortTrainingApiHandle").setLong(null, training)
        f("trainingEnabled").setBoolean(null, training != 0L)
        f("providers").set(null, m("initialiseProviders", long).invoke(null, api))
        f("version").set(null, m("initialiseVersion").invoke(null))
        f("loaded").setBoolean(null, true)
        loaded = true
    }

    /** OnnxRuntime.ORT_API_VERSION_14 in 1.22.0. */
    private const val API_VERSION = 14
}
