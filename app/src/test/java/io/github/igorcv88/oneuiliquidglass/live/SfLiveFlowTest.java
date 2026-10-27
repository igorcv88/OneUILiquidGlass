package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public final class SfLiveFlowTest {
    private static String status(String update, String tested, String loaded) {
        return "helper=1\nfingerprint_ok=1\nbundled_ok=1\ninstalled_ok=1\nupdate_needed="
                + update
                + "\nselftest_ok="
                + tested
                + "\nsf_loaded="
                + loaded
                + "\n";
    }

    private static final class Fake implements SfLiveFlow.Runner {
        final Queue<SfLiveFlow.Reply> replies = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        Fake add(int code, String output) {
            replies.add(new SfLiveFlow.Reply(code, output));
            return this;
        }

        final Queue<SfLiveFlow.Reply> props = new ArrayDeque<>();

        @Override
        public SfLiveFlow.Reply setprops(Map<String, String> values) {
            calls.add("SETPROPS " + new TreeMap<>(values));
            assertFalse("Unexpected setprops", props.isEmpty());
            return props.remove();
        }

        @Override
        public SfLiveFlow.Reply run(SfLive.Command command, String mode) {
            calls.add(command.name() + (mode == null ? "" : " " + mode));
            assertFalse("Unexpected command: " + command, replies.isEmpty());
            return replies.remove();
        }
    }

    @Test
    public void updateInstallsThenSelftestsAndNeverRestarts() throws Exception {
        Fake fake =
                new Fake()
                        .add(0, status("1", "0", "0"))
                        .add(0, "install=ok\n")
                        .add(0, status("0", "0", "0"))
                        .add(0, "selftest=ok\nrc=0\n")
                        .add(0, status("0", "1", "0"));
        Map<String, String> ready = new SfLiveFlow(fake).prepare();
        assertEquals(SfLive.State.RESTART, SfLive.state(ready));
        assertEquals(
                Arrays.asList("STATUS", "INSTALL", "STATUS", "SELFTEST", "STATUS"), fake.calls);
    }

    @Test
    public void selftestFailureStopsBeforeRestart() throws Exception {
        Fake fake = new Fake().add(0, status("0", "0", "0")).add(1, "selftest=failed\nrc=1\n");
        SfLiveFlow.Failure failure =
                assertThrows(SfLiveFlow.Failure.class, () -> new SfLiveFlow(fake).prepare());
        assertTrue(failure.selftest);
        assertEquals(Arrays.asList("STATUS", "SELFTEST"), fake.calls);
    }

    @Test
    public void unsupportedOrCorruptBundleCannotInstall() throws Exception {
        for (String bad :
                new String[] {
                    status("1", "0", "0").replace("fingerprint_ok=1", "fingerprint_ok=0"),
                    status("1", "0", "0").replace("bundled_ok=1", "bundled_ok=0")
                }) {
            Fake fake = new Fake().add(0, bad);
            assertThrows(SfLiveFlow.Failure.class, () -> new SfLiveFlow(fake).prepare());
            assertEquals(Collections.singletonList("STATUS"), fake.calls);
        }
    }

    @Test
    public void restartRechecksAndOnlyUsesRewriteAfterExplicitCall() throws Exception {
        Fake fake = new Fake().add(0, status("0", "1", "0")).add(0, "restart=started\n");
        assertEquals("started", new SfLiveFlow(fake).restartConfirmed().values().get("restart"));
        assertEquals(Arrays.asList("STATUS", "RESTART rewrite"), fake.calls);
    }

    @Test
    public void staleUpdateOrMissingStampCannotRestart() throws Exception {
        for (String bad :
                new String[] {
                    status("1", "1", "0"),
                    status("0", "0", "0"),
                    status("0", "1", "0").replace("installed_ok=1", "installed_ok=0")
                }) {
            Fake fake = new Fake().add(0, bad);
            assertThrows(SfLiveFlow.Failure.class, () -> new SfLiveFlow(fake).restartConfirmed());
            assertEquals(Collections.singletonList("STATUS"), fake.calls);
        }
    }

    @Test
    public void loadedCurrentVersionDoesNotInstallOrRestart() throws Exception {
        Fake fake = new Fake().add(0, status("0", "1", "1"));
        assertEquals(SfLive.State.LOADED, SfLive.state(new SfLiveFlow(fake).prepare()));
        assertEquals(Collections.singletonList("STATUS"), fake.calls);
    }

    @Test
    public void falseSuccessOrInvalidReplyNeverAdvances() throws Exception {
        Fake fake = new Fake().add(0, status("0", "0", "0")).add(0, "selftest=failed\nrc=1\n");
        assertThrows(SfLiveFlow.Failure.class, () -> new SfLiveFlow(fake).prepare());
        Fake denied = new Fake().add(1, "permission denied\n");
        assertThrows(SfLiveFlow.Failure.class, () -> new SfLiveFlow(denied).status());
    }

    @Test
    public void applyWritesConfirmsThenRestarts() throws Exception {
        Fake fake = new Fake().add(0, status("0", "1", "1")).add(0, status("0", "1", "1")).add(0, "restart=started\n");
        fake.props.add(new SfLiveFlow.Reply(0, "set_kgcore=96\nsetprops=ok\n"));
        new SfLiveFlow(fake).applyAndRestart(Collections.singletonMap("sf.kgcore", "96"));
        assertEquals(Arrays.asList("STATUS", "SETPROPS {sf.kgcore=96}", "STATUS", "RESTART rewrite"), fake.calls);
    }

    @Test
    public void unconfirmedValueNeverRestarts() throws Exception {
        Fake fake = new Fake().add(0, status("0", "1", "1"));
        fake.props.add(new SfLiveFlow.Reply(0, "set_kgcore=\nsetprops=ok\n"));
        assertThrows(SfLiveFlow.Failure.class,
                () -> new SfLiveFlow(fake).applyAndRestart(Collections.singletonMap("sf.kgcore", "96")));
        assertFalse(fake.calls.contains("RESTART rewrite"));
    }

    @Test
    public void applyRefusesWithoutSelftest() throws Exception {
        Fake fake = new Fake().add(0, status("0", "0", "1"));
        assertThrows(SfLiveFlow.Failure.class,
                () -> new SfLiveFlow(fake).applyAndRestart(Collections.singletonMap("sf.kgcore", "96")));
        assertEquals(Collections.singletonList("STATUS"), fake.calls);
    }
}
