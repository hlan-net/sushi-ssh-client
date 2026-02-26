package net.hlan.sushi;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LocalSshIntegrationTest {
    private static final int DEFAULT_SSH_PORT = 22;

    private static final String ARG_HOST = "sshHost";
    private static final String ARG_PORT = "sshPort";
    private static final String ARG_USERNAME = "sshUsername";
    private static final String ARG_PASSWORD = "sshPassword";
    private static final String ARG_PRIVATE_KEY = "sshPrivateKey";

    @Test
    public void connectsToConfiguredHostViaSsh() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = valueOrEmpty(args.getString(ARG_HOST)).trim();
        String username = valueOrEmpty(args.getString(ARG_USERNAME)).trim();
        String password = valueOrEmpty(args.getString(ARG_PASSWORD));
        String privateKey = valueOrEmpty(args.getString(ARG_PRIVATE_KEY));
        int port = parsePort(args.getString(ARG_PORT));

        assumeTrue(
            "Set sshHost, sshUsername and either sshPassword or sshPrivateKey to run this test.",
            !host.isEmpty() && !username.isEmpty() && (!password.isEmpty() || !privateKey.isEmpty())
        );

        String marker = "SUSHI_LOCAL_TEST_OK_" + System.currentTimeMillis();
        List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch markerLatch = new CountDownLatch(1);

        SshConnectionConfig config = new SshConnectionConfig(
            host,
            port,
            username,
            password,
            privateKey.isEmpty() ? null : privateKey
        );
        SshClient client = new SshClient(config);

        SshConnectResult connectResult = client.connect(line -> {
            receivedLines.add(line);
            if (line.contains(marker)) {
                markerLatch.countDown();
            }
            return Unit.INSTANCE;
        });

        assertTrue("SSH connect failed: " + connectResult.getMessage(), connectResult.getSuccess());

        try {
            SshCommandResult commandResult = client.sendCommand("echo " + marker);
            assertTrue("Failed to send command: " + commandResult.getMessage(), commandResult.getSuccess());

            boolean markerReceived = markerLatch.await(15, TimeUnit.SECONDS);
            assertTrue(
                "Did not receive marker output within timeout. Received lines: " +
                    tail(receivedLines, 20),
                markerReceived
            );
        } finally {
            client.disconnect();
        }
    }

    private static int parsePort(String rawPort) {
        if (rawPort == null || rawPort.trim().isEmpty()) {
            return DEFAULT_SSH_PORT;
        }
        try {
            return Integer.parseInt(rawPort.trim());
        } catch (NumberFormatException ignored) {
            return DEFAULT_SSH_PORT;
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static List<String> tail(List<String> lines, int maxItems) {
        if (lines.size() <= maxItems) {
            return lines;
        }
        return lines.subList(lines.size() - maxItems, lines.size());
    }
}
