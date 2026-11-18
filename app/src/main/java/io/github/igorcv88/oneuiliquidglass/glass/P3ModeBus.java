package io.github.igorcv88.oneuiliquidglass.glass;

import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Shares the heads-up pop-up's display-mode watcher with other visible pop-up surfaces (the live
 * card), so every route reads the same SurfaceFlinger signal at the same bounded cadence and
 * lifecycle: no second poller, no higher rate, nothing while no pop-up is visible. Main thread.
 */
public final class P3ModeBus {
    /** A surface that wants live display-mode updates while it is a visible pop-up. */
    public interface Client {
        /** The visible pop-up host to sample the display through, or null when not watching. */
        View watchAnchor();
        /** The display's active color mode as sampled by the shared watcher (-1 unknown). */
        void onMode(int mode);
    }

    private static final Set<Client> clients = Collections.newSetFromMap(new WeakHashMap<>());
    private static Runnable starter;

    private P3ModeBus() {}

    /** Installed by the owner of the watcher (heads-up hooks): asks it to start if eligible. */
    public static void setWatchStarter(Runnable r) { starter = r; }

    public static void add(Client c) { if (c != null) clients.add(c); }

    public static void remove(Client c) { if (c != null) clients.remove(c); }

    /** A client became a visible pop-up: start the shared watcher if it is not running. */
    public static void requestWatch() {
        Runnable r = starter;
        if (r != null) r.run();
    }

    /** First client host that currently wants updates, or null. */
    public static View watchAnchor() {
        for (Client c : snapshot()) {
            View v = c.watchAnchor();
            if (v != null) return v;
        }
        return null;
    }

    /** Delivers one watcher sample to every client that is currently watching. */
    public static void publish(int mode) {
        for (Client c : snapshot()) if (c.watchAnchor() != null) c.onMode(mode);
    }

    private static List<Client> snapshot() {
        return new ArrayList<>(clients);
    }

    /** Tests only. */
    static void clearForTest() { clients.clear(); starter = null; }
}
