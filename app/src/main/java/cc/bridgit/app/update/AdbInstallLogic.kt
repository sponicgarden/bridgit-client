package cc.bridgit.app.update

/**
 * Pure side of installing a verified update over Bridgit's own ADB link, with no Android confirm.
 * No android.* here, so it is unit-tested on the JVM.
 *
 * Every value that ends up in a shell command is checked against a strict pattern first. Anything odd
 * returns null and the caller falls back to Android's installer, which asks the owner to confirm.
 */
object AdbInstallLogic {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val CLASS = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    const val DIR = "/data/local/tmp"
    /** The last line the background job writes to its log: the exit code of `pm install`. */
    const val RC_MARKER = "__BRIDGIT_RC__="
    /** Printed after the APK has been written, so the writer knows every byte landed. */
    const val WROTE_MARKER = "__BRIDGIT_WROTE__"

    fun isSafePackage(pkg: String?): Boolean = pkg != null && pkg.length <= 200 && PACKAGE.matches(pkg)

    /** `pkg/full.class.Name`, both parts plain identifiers (no quotes, spaces, `$` or shell characters). */
    fun isSafeComponent(component: String?, pkg: String): Boolean {
        val parts = component?.split('/') ?: return false
        return parts.size == 2 && parts[0] == pkg && parts[1].length <= 300 && CLASS.matches(parts[1])
    }

    /** Only with Bridgit's own ADB link: the private build, paired, and not taken back by Revoke Super Powers. */
    fun available(privateControl: Boolean, paired: Boolean, revoked: Boolean): Boolean =
        privateControl && paired && !revoked

    /** Where uid shell keeps the APK and the job's log. Only uid shell can read or write /data/local/tmp. */
    data class Paths(val apk: String, val log: String)

    fun paths(pkg: String, versionCode: Long): Paths? {
        if (!isSafePackage(pkg) || versionCode <= 0) return null
        val base = "$DIR/bridgit-update-$pkg-$versionCode"
        return Paths("$base.apk", "$base.log")
    }

    /**
     * The `exec:` command that reads exactly [size] bytes from stdin into [path], then prints [WROTE_MARKER].
     * Waiting for the marker means the last bytes were written before the stream closes.
     */
    fun writeCommand(path: String, size: Long): String? {
        if (!isSafePath(path) || size <= 0) return null
        return "rm -f '$path' && head -c $size > '$path' && chmod 600 '$path' && echo $WROTE_MARKER"
    }

    /** Prints the size, then the SHA-256 line, of the file uid shell will install. */
    fun verifyCommand(path: String): String? =
        if (isSafePath(path)) "stat -c %s '$path' && sha256sum '$path'" else null

    /** The file on the shell side must be exactly the bytes Bridgit verified: same size, same SHA-256. */
    fun verifyMatches(output: String, size: Long, sha256: String): Boolean {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return false
        val gotSize = lines[0].toLongOrNull() ?: return false
        val gotSha = lines[1].substringBefore(' ').lowercase()
        return gotSize == size && SHA256.matches(gotSha) && gotSha == sha256.lowercase()
    }

    /**
     * One shell line that starts the install as its own session, so it keeps running when Android stops
     * Bridgit to replace it. It installs, deletes the APK, writes the exit code to the log and, on success,
     * starts Bridgit again. All three standard streams leave the ADB stream, so the call returns at once.
     */
    fun installCommand(pkg: String, paths: Paths, component: String): String? {
        if (!isSafePackage(pkg) || !isSafeComponent(component, pkg)) return null
        if (!isSafePath(paths.apk) || !isSafePath(paths.log)) return null
        val job = "pm install -r -i $pkg ${paths.apk} > ${paths.log} 2>&1; rc=\$?; rm -f ${paths.apk}; " +
            "echo $RC_MARKER\$rc >> ${paths.log}; [ \$rc -eq 0 ] && am start -n $component"
        return "rm -f '${paths.log}'; setsid nohup sh -c '$job' > /dev/null 2>&1 < /dev/null &"
    }

    sealed class JobState {
        object Running : JobState()
        object Installed : JobState()
        data class Failed(val reason: String) : JobState()
    }

    /** Reads the job's log: no exit code yet is Running; 0 with "Success" is Installed; anything else Failed. */
    fun parseLog(text: String?): JobState {
        if (text == null) return JobState.Running
        val rcLine = text.lines().lastOrNull { it.trim().startsWith(RC_MARKER) } ?: return JobState.Running
        val rc = rcLine.trim().removePrefix(RC_MARKER).trim().toIntOrNull()
        val failure = Regex("Failure \\[([^\\]]{1,200})]").find(text)?.groupValues?.get(1)
        return when {
            rc == 0 && failure == null && text.contains("Success") -> JobState.Installed
            failure != null -> JobState.Failed(failure)
            else -> JobState.Failed("exit ${rc ?: "?"}")
        }
    }

    private fun isSafePath(path: String): Boolean =
        path.startsWith("$DIR/bridgit-update-") && PATH.matches(path) && !path.contains("..")

    private val PATH = Regex("^[A-Za-z0-9_./-]{1,300}$")
}
