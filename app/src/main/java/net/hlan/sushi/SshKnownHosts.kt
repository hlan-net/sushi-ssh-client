package net.hlan.sushi

import android.content.Context
import com.jcraft.jsch.JSch
import java.io.File

/**
 * Owns the location of the app's known-hosts store and wires a [JSch] instance to use it,
 * wrapped in a [TrackingHostKeyRepository] so callers can distinguish a first-trust prompt from
 * a changed-key warning. Host keys are public data — the server presents them to any client
 * that connects — so app-private file storage is sufficient; no SecurePrefs/encryption needed.
 */
object SshKnownHosts {
    fun file(context: Context): File = File(context.filesDir, "known_hosts")

    fun attach(jsch: JSch, knownHostsFile: File): TrackingHostKeyRepository {
        jsch.setKnownHosts(knownHostsFile.absolutePath)
        val tracking = TrackingHostKeyRepository(jsch.hostKeyRepository)
        jsch.setHostKeyRepository(tracking)
        return tracking
    }
}
