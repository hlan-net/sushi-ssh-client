package net.hlan.sushi

import net.hlan.sushi.CommandSafety.SafetyLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandSafetyTest {

    // -------------------------------------------------------------------------
    // SAFE — simple commands
    // -------------------------------------------------------------------------

    @Test
    fun safeCommand_ls() = assertEquals(SafetyLevel.SAFE, CommandSafety.classify("ls"))

    @Test
    fun safeCommand_lsWithArgs() = assertEquals(SafetyLevel.SAFE, CommandSafety.classify("ls -la /var/log"))

    @Test
    fun safeCommand_pwd() = assertEquals(SafetyLevel.SAFE, CommandSafety.classify("pwd"))

    @Test
    fun safeCommand_catWithPath() = assertEquals(SafetyLevel.SAFE, CommandSafety.classify("cat /etc/os-release"))

    @Test
    fun safeCommand_grepWithArgs() = assertEquals(SafetyLevel.SAFE, CommandSafety.classify("grep -r foo /var/log"))

    @Test
    fun safeCommand_ps() = assertEquals(SafetyLevel.SAFE, CommandSafety.classify("ps aux"))

    @Test
    fun safeCommand_df() = assertEquals(SafetyLevel.SAFE, CommandSafety.classify("df -h"))

    @Test
    fun safeCommand_whoami() = assertEquals(SafetyLevel.SAFE, CommandSafety.classify("whoami"))

    @Test
    fun safeCommand_caseInsensitive() = assertEquals(SafetyLevel.SAFE, CommandSafety.classify("LS -LA"))

    @Test
    fun safeCommand_emptyString() {
        // Empty input has no segments → no non-safe segments → SAFE
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify(""))
    }

    @Test
    fun safeCommand_whitespaceOnly() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("   "))
    }

    // -------------------------------------------------------------------------
    // SAFE — pattern-based commands (regression for matches() → containsMatchIn())
    // -------------------------------------------------------------------------

    @Test
    fun safePattern_systemctlStatusWithUnit() {
        // Was broken: Regex.matches() is a full-string match so "systemctl status nginx"
        // never matched Regex("^systemctl\\s+status") and fell through to CONFIRM.
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("systemctl status nginx"))
    }

    @Test
    fun safePattern_systemctlStatusBare() {
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
    fun safePattern_journalctlWithFlags() {
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
    // BLOCKED — explicit blocked patterns
    // -------------------------------------------------------------------------

    @Test
    fun blocked_shutdown() = assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("shutdown now"))

    @Test
    fun blocked_reboot() = assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("reboot"))

    @Test
    fun blocked_rmRfRoot() = assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("rm -rf /"))

    @Test
    fun blocked_mkfs() = assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("mkfs.ext4 /dev/sda1"))

    @Test
    fun blocked_systemctlReboot() = assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("systemctl reboot"))

    @Test
    fun blocked_forkBomb() {
        // The fork-bomb contains `|` and `;`, which the splitter would normally break apart.
        // isBlocked() is run on the full string first to prevent this.
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify(":(){ :|:& };:"))
    }

    // -------------------------------------------------------------------------
    // BLOCKED — shell interpreter as pipe/chain target
    // -------------------------------------------------------------------------

    @Test
    fun blocked_curlPipeBash() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("curl something | bash"))
    }

    @Test
    fun blocked_curlPipeSh() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("curl example.com/install.sh | sh"))
    }

    @Test
    fun blocked_wgetPipePython3() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("wget -O- url | python3"))
    }

    @Test
    fun blocked_chainToPerl() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("cat exploit.pl | perl"))
    }

    @Test
    fun blocked_bashSegmentAfterSemicolon() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("echo test; bash"))
    }

    // -------------------------------------------------------------------------
    // CONFIRM — commands that need user approval
    // -------------------------------------------------------------------------

    @Test
    fun confirm_aptGetInstall() = assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("apt-get install vim"))

    @Test
    fun confirm_systemctlStart() = assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("systemctl start nginx"))

    @Test
    fun confirm_systemctlStop() = assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("systemctl stop nginx"))

    @Test
    fun confirm_touch() = assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("touch /tmp/foo"))

    @Test
    fun confirm_mkdir() = assertEquals(SafetyLevel.CONFIRM, CommandSafety.classify("mkdir /tmp/newdir"))

    // -------------------------------------------------------------------------
    // Chained commands — SAFE first segment must not hide a non-safe second
    // -------------------------------------------------------------------------

    @Test
    fun chain_safeAndConfirm_semicolon() {
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
    fun chain_bothSafe_pipe() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("ls -la | grep foo"))
    }

    @Test
    fun chain_confirmAndBlocked() {
        assertEquals(SafetyLevel.BLOCKED, CommandSafety.classify("apt-get install vim && reboot"))
    }

    @Test
    fun chain_threeSafeSegments() {
        assertEquals(SafetyLevel.SAFE, CommandSafety.classify("pwd && ls -la && whoami"))
    }
}
