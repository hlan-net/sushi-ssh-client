package net.hlan.sushi

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * Decorates JSch's own [HostKeyRepository] — backed by its battle-tested known_hosts parser and
 * writer, never reimplemented here — to record the result of the most recent [check] call.
 * [UserInfo.promptYesNo] only receives a message string from JSch, with no structured way to
 * tell a first-trust prompt (`NOT_INCLUDED`) apart from a changed-key warning (`CHANGED`); this
 * decorator lets [DialogUserInfo] read [lastCheckResult] instead of string-sniffing JSch's
 * internal message text.
 */
class TrackingHostKeyRepository(private val delegate: HostKeyRepository) : HostKeyRepository {

    var lastCheckResult: Int = HostKeyRepository.OK
        private set
    var lastCheckedHost: String? = null
        private set
    var lastCheckedKey: ByteArray? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val result = delegate.check(host, key)
        lastCheckResult = result
        lastCheckedHost = host
        lastCheckedKey = key
        return result
    }

    override fun add(hostkey: HostKey, userinfo: UserInfo?) {
        delegate.add(hostkey, userinfo)
    }

    override fun remove(host: String, type: String) {
        delegate.remove(host, type)
    }

    override fun remove(host: String, type: String, key: ByteArray) {
        delegate.remove(host, type, key)
    }

    override fun getKnownHostsRepositoryID(): String = delegate.knownHostsRepositoryID

    override fun getHostKey(): Array<HostKey> = delegate.hostKey

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = delegate.getHostKey(host, type)
}
