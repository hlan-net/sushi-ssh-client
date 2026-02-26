package net.hlan.sushi;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        LocalSshCredentials credentials = readCredentialsOrSkip();

        String marker = "SUSHI_LOCAL_TEST_OK_" + System.currentTimeMillis();
        List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch markerLatch = new CountDownLatch(1);

        SshConnectionConfig config = new SshConnectionConfig(
            credentials.host,
            credentials.port,
            credentials.username,
            credentials.password,
            credentials.privateKey
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

    @Test
    public void connectsToConfiguredHostViaMainUi() throws Exception {
        LocalSshCredentials credentials = readCredentialsOrSkip();
        SshSettings sshSettings = new SshSettings(InstrumentationRegistry.getInstrumentation().getTargetContext());
        sshSettings.setHost(credentials.host);
        sshSettings.setPort(credentials.port);
        sshSettings.setUsername(credentials.username);
        sshSettings.setPassword(credentials.password);
        sshSettings.setPrivateKey(credentials.privateKey);

        String marker = "SUSHI_UI_TEST_OK_" + System.currentTimeMillis();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View startButton = activity.findViewById(R.id.startSessionButton);
                startButton.performClick();
            });

            waitUntil(
                scenario,
                20_000,
                activity -> {
                    TextView statusView = activity.findViewById(R.id.sessionStatusText);
                    return activity.getString(R.string.session_status_connected)
                        .contentEquals(statusView.getText());
                },
                "Session did not reach connected state from UI"
            );

            scenario.onActivity(activity -> {
                TextView commandInput = activity.findViewById(R.id.commandInput);
                View runButton = activity.findViewById(R.id.runCommandButton);
                commandInput.setText("echo " + marker);
                runButton.performClick();
            });

            waitUntil(
                scenario,
                20_000,
                activity -> {
                    TextView logView = activity.findViewById(R.id.sessionLogText);
                    CharSequence text = logView.getText();
                    return text != null && text.toString().contains(marker);
                },
                "Command output marker not found in terminal log"
            );

            scenario.onActivity(activity -> {
                TextView statusView = activity.findViewById(R.id.sessionStatusText);
                if (activity.getString(R.string.session_status_connected).contentEquals(statusView.getText())) {
                    View startButton = activity.findViewById(R.id.startSessionButton);
                    startButton.performClick();
                }
            });
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

    private static LocalSshCredentials readCredentialsOrSkip() {
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

        return new LocalSshCredentials(
            host,
            port,
            username,
            password,
            privateKey.isEmpty() ? null : privateKey
        );
    }

    private static void waitUntil(
        ActivityScenario<MainActivity> scenario,
        long timeoutMs,
        ActivityCondition condition,
        String timeoutMessage
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean satisfied = new AtomicBoolean(false);
            scenario.onActivity(activity -> satisfied.set(condition.check(activity)));
            if (satisfied.get()) {
                return;
            }
            Thread.sleep(250);
        }

        AtomicReference<String> debugState = new AtomicReference<>("(unavailable)");
        scenario.onActivity(activity -> {
            TextView statusView = activity.findViewById(R.id.sessionStatusText);
            TextView targetView = activity.findViewById(R.id.sessionTargetText);
            debugState.set("status=" + statusView.getText() + ", target=" + targetView.getText());
        });
        assertTrue(timeoutMessage + " | " + debugState.get(), false);
    }

    private interface ActivityCondition {
        boolean check(MainActivity activity);
    }

    private static final class LocalSshCredentials {
        final String host;
        final int port;
        final String username;
        final String password;
        final String privateKey;

        LocalSshCredentials(String host, int port, String username, String password, String privateKey) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.privateKey = privateKey;
        }
    }
}
