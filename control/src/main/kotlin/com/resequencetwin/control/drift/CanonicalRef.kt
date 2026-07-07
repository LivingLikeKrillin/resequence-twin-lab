package com.resequencetwin.control.drift

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Computes the Git last-commit SHA of the canonical setpoints file, mirroring koshei's CanonicalProvenance
 * so both lineages name the SAME reference. The twin reads koshei's canonical file at the configured
 * canonical-path (which points INTO the koshei checkout), so this resolves koshei's repo and yields koshei's SHA.
 * Returns null on any git failure — the finding then carries defRef=null and the gate treats that as a failure.
 */
object CanonicalRef {
    fun resolve(canonicalPath: String): String? {
        val abs = File(canonicalPath).absoluteFile
        if (!abs.exists()) return null
        val dir = abs.parentFile ?: return null
        val repo = git(dir, "rev-parse", "--show-toplevel")?.trim() ?: return null
        val sha = git(File(repo), "log", "-1", "--format=%H", "--", abs.path)?.trim()
        return sha?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }
    }

    // stdout drained on a daemon thread so a stalled git can't block past the timeout (readText() would
    // otherwise return only at process exit, defeating waitFor()); destroyForcibly hard-bounds a hung git.
    private fun git(dir: File, vararg args: String): String? {
        return try {
            val p = ProcessBuilder(listOf("git", "-C", dir.path) + args).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                try { p.inputStream.bufferedReader().use { out.append(it.readText()) } } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            reader.join(2000)
            if (p.exitValue() == 0) out.toString() else null
        } catch (_: Exception) { null }
    }
}
