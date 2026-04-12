package net.hlan.sushi

import net.hlan.sushi.CommandSafety.SafetyLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandSafetyTest {

    // -------------------------------------------------------------------------
    // SAFE commands
    // -------------------------------------------------------------------------

    @Test
    fun safeCommand_ls() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("ls"))
    }

    @Test
    fun safeCommand_lsWithArgs() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("ls -la /var/log"))
    }

    @Test
    fun safeCommand_pwd() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("pwd"))
    }

    @Test
    fun safeCommand_cat() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("cat /etc/os-release"))
    }

    @Test
    fun safeCommand_grep() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("grep -r foo /var/log"))
    }

    @Test
    fun safeCommand_ps() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("ps aux"))
    }

    @Test
    fun safeCommand_df() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("df -h"))
    }

    @Test
    fun safeCommand_whoami() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("whoami"))
    }

    @Test
    fun safeCommand_caseInsensitive() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("LS -LA"))
    }

    // -------------------------------------------------------------------------
    // SAFE pattern-based commands (regression for matches() → containsMatchIn())
    // -------------------------------------------------------------------------

    @Test
    fun safePattern_systemctlStatusWithUnit() {
        // Previously broken: matches() required full-string match, so "systemctl status nginx"
        // never matched Regex("^systemctl\\s+status") and fell through to CONFIRM.
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("systemctl status nginx"))
    }

    @Test
    fun safePattern_systemctlStatusNoUnit() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("systemctl status"))
    }

    @Test
    fun safePattern_systemctlListUnits() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("systemctl list-units --type=service"))
    }

    @Test
    fun safePattern_systemctlIsActive() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("systemctl is-active sshd"))
    }

    @Test
    fun safePattern_journalctl() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("journalctl -u nginx -f"))
    }

    @Test
    fun safePattern_dockerPs() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("docker ps -a"))
    }

    @Test
    fun safePattern_dockerLogs() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("docker logs my-container"))
    }

    @Test
    fun safePattern_aptList() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("apt list --installed"))
    }

    @Test
    fun safePattern_dpkgList() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("dpkg -l"))
    }

    @Test
    fun safePattern_vcgencmdTemp() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("vcgencmd measure_temp"))
    }

    // -------------------------------------------------------------------------
    // BLOCKED commands
    // -------------------------------------------------------------------------

    @Test
    fun blocked_shutdown() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("shutdown now"))
    }

    @Test
    fun blocked_reboot() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("reboot"))
    }

    @Test
    fun blocked_rmRfRoot() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("rm -rf /"))
    }

    @Test
    fun blocked_mkfs() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("mkfs.ext4 /dev/sda1"))
    }

    @Test
    fun blocked_systemctlReboot() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("systemctl reboot"))
    }

    @Test
    fun blocked_forkBomb() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify(":(){ :|:& };:"))
    }

    // -------------------------------------------------------------------------
    // CONFIRM commands
    // -------------------------------------------------------------------------

    @Test
    fun confirm_aptGetInstall() {
        assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("apt-get install vim"))
    }

    @Test
    fun confirm_systemctlStart() {
        assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("systemctl start nginx"))
    }

    @Test
    fun confirm_systemctlStop() {
        assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("systemctl stop nginx"))
    }

    @Test
    fun confirm_touch() {
        assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("touch /tmp/foo"))
    }

    @Test
    fun confirm_mkdir() {
        assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("mkdir /tmp/newdir"))
    }

    // -------------------------------------------------------------------------
    // Chained commands — SAFE first segment must not hide a dangerous second
    // -------------------------------------------------------------------------

    @Test
    fun chain_safeAndConfirm_semicolon() {
        // Previously broken: "ls" matched SAFE, remaining "; apt-get install foo" was ignored.
        assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("ls; apt-get install foo"))
    }

    @Test
    fun chain_safeAndConfirm_and() {
        assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("pwd && systemctl start nginx"))
    }

    @Test
    fun chain_safeAndBlocked_or() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("echo hi || shutdown now"))
    }

    @Test
    fun chain_safeAndBlocked_pipe() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("cat /etc/fstab | reboot"))
    }

    @Test
    fun chain_bothSafe() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("ls -la | grep foo"))
    }

    @Test
    fun chain_confirmAndBlocked() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("apt-get install vim && reboot"))
    }
}
