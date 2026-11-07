package cc.bridgit.app.sound

import java.util.concurrent.ConcurrentHashMap

/**
 * Who wants loud sounds, and from which microphones (the shared core of sound watching, see [SoundRules]).
 * A source is [BUILTIN] (the phone's own microphone) or an enrolled camera's microphone (`lan:<id>`).
 * The always-on listener (WakeListener) serves [BUILTIN]: one loudness detector per subscriber on the same audio
 * stream, so a sound watch, the Pet monitor and an agent's "listen for any sounds" never open the microphone twice.
 * Camera microphones are served by CameraMics: one bounded RTSP stream per camera, one detector per subscriber.
 * Each detector has its own 30 s gap. [Subscriber.onSound] gets the source, a WAV clip (16 kHz mono, from just
 * before the sound started), how long the sound lasted and when it started; it is called off the audio thread and
 * must return quickly. Subscribers check their own access ([Subscriber.allows] is asked again while a camera
 * stream runs); the listeners only check what every user of that microphone needs.
 */
class LoudSounds(private val changed: () -> Unit) {
    class Subscriber(val id: String, val sensitivity: String, val sources: Set<String> = setOf(BUILTIN),
                     /** Re-checked about once a second for camera sources; false drops the sound and lets the stream go. */
                     val allows: (source: String) -> Boolean = { true },
                     val onSound: (source: String, wav: ByteArray, durationMs: Long, at: Long) -> Unit)

    private val subs = ConcurrentHashMap<String, Subscriber>()
    /** Bumped on every change; the running listeners rebuild their detectors when it moves (no microphone restart). */
    @Volatile var version = 0; private set

    fun subscribe(s: Subscriber) {
        require(s.sensitivity in SoundRules.SENSITIVITIES)
        require(s.sources.isNotEmpty() && s.sources.all { it == BUILTIN || it.startsWith("lan:") })
        val old = subs.put(s.id, s)
        if (old == null || old.sensitivity != s.sensitivity || old.sources != s.sources) { version++; changed() }
    }
    fun unsubscribe(id: String) { if (subs.remove(id) != null) { version++; changed() } }
    fun get(id: String): Subscriber? = subs[id]
    fun all(): List<Subscriber> = subs.values.sortedBy { it.id }
    /** Id to sensitivity for one microphone, sorted: what its listener runs. */
    fun active(source: String = BUILTIN): List<Pair<String, String>> =
        subs.values.filter { source in it.sources }.map { it.id to it.sensitivity }.sortedBy { it.first }
    /** Every camera microphone someone wants. */
    fun cameraSources(): Set<String> = subs.values.flatMap { it.sources }.filter { it != BUILTIN }.toSortedSet()

    companion object { const val BUILTIN = "builtin" }
}
