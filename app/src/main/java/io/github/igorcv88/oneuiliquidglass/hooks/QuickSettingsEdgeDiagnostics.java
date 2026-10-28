package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.EnumSet;
import java.util.function.Consumer;

/** Bounded, content-free QS diagnostics. The owner serializes calls, including Compose prefetch. */
final class QuickSettingsEdgeDiagnostics {
    enum Event {
        ELEMENT, BIND, NODE_RELEASED, ELEMENT_UNMATCHED, UNBOUND_NODE, PENDING_EVICTED,
        INVALID_SIZE, INVALID_RADIUS_TAG, NONHARDWARE_CANVAS, MISSING_VIEWROOT,
        BLUR_FACTORY_FAILED, REGION_CREATED, REGION_UPDATED, REGION_DRAWN, REGION_RELEASED
    }

    private final Consumer<String> log;
    private final EnumSet<Event> seen = EnumSet.noneOf(Event.class);
    private final long[] totals = new long[Event.values().length];
    private final long[] baseline = new long[Event.values().length];
    private int session, lastGate = -1;
    private int lastFade = -1;
    private boolean opening, settled, visible;

    QuickSettingsEdgeDiagnostics(Consumer<String> log) { this.log = log; }

    void begin(String reason) {
        session++;
        seen.clear();
        lastGate = -1;
        lastFade = -1;
        settled = visible = false;
        log.accept("phase=session session=" + session + " reason=" + reason);
    }

    /** No strings, logs or allocations after the first occurrence of a reason in this session. */
    boolean count(Event event) {
        totals[event.ordinal()]++;
        return seen.add(event);
    }

    void event(Event event, String detail) {
        log.accept("phase=path session=" + session + " reason="
                + event.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + " " + detail);
    }

    static String blocked(boolean ready, boolean option, boolean master, boolean usable,
            boolean refract, int optics, boolean host) {
        if (!ready) return "not-ready";
        if (!master) return "master-off";
        if (!option) return "option-off";
        if (!usable) return "compose-failed";
        if (!refract) return "sfrefract-off";
        if (optics < 10) return "optics-version";
        return host ? "none" : "missing-host";
    }

    void gate(boolean ready, boolean option, boolean master, boolean usable,
            boolean refract, int optics, boolean host) {
        // Version is diagnostic data too: 10 -> 11 must be observable, although both are active.
        int bits = (ready ? 1 : 0) | (option ? 2 : 0) | (master ? 4 : 0)
                | (usable ? 8 : 0) | (refract ? 16 : 0) | (host ? 32 : 0);
        int state = 64 * optics + bits;
        if (state == lastGate) return;
        lastGate = state;
        log.accept("phase=gate session=" + session + " ready=" + ready + " option=" + option
                + " master=" + master + " enabled=" + usable + " sfRefract=" + refract
                + " sfOptics=" + optics + " host=" + host + " blocked="
                + blocked(ready, option, master, usable, refract, optics, host));
    }

    /** Log categories, not animation values: a smoothly changing alpha must not log every frame. */
    void fade(boolean shown, boolean scaled, float ancestors, float expansion, float fade) {
        int state = !shown ? 0 : scaled ? 1 : expansion < 0f ? 2
                : ancestors <= 0f ? 3 : fade <= 0f ? 4 : 5;
        if (state == lastFade) return;
        lastFade = state;
        String reason;
        switch (state) {
            case 0: reason = "host-hidden"; break;
            case 1: reason = "scaled-ancestor"; break;
            case 2: reason = "expansion-unreadable"; break;
            case 3: reason = "ancestor-transparent"; break;
            case 4: reason = "expansion-below-fade"; break;
            default: reason = "positive-fade";
        }
        log.accept("phase=fade session=" + session + " reason=" + reason + " shown=" + shown
                + " scaled=" + scaled + " ancestorAlpha=" + ancestors + " expansion=" + expansion
                + " edgeFade=" + fade);
    }

    /** One open, one settled and one visible observation, rearmed only by a close or a toggle. */
    String observe(boolean shown, float expansion, int positiveDrawn, boolean postDraw) {
        boolean now = shown && (expansion < 0f || expansion > 0f);
        if (!now) {
            if (!opening) return null;
            opening = false;
            settled = visible = false;
            return "exit";
        }
        if (!opening) {
            opening = true;
            begin("opening");
            return expansion < 0f ? "expansion-unreadable" : "opening";
        }
        if (expansion >= 0.95f && !settled && !postDraw) {
            settled = true;
            return "settled";
        }
        if (expansion >= 0.95f && positiveDrawn > 0 && !visible) {
            visible = true;
            return "region-visible";
        }
        return null;
    }

    void summary(String phase, String state) {
        StringBuilder s = new StringBuilder("phase=").append(phase).append(" session=").append(session);
        for (Event e : Event.values()) {
            int i = e.ordinal();
            String name = e == Event.ELEMENT ? "specsCreated" : e == Event.BIND ? "specsBound"
                    : e == Event.NODE_RELEASED ? "nodesReleased" : e.name().toLowerCase(java.util.Locale.ROOT);
            s.append(' ').append(name).append('=')
                    .append(totals[i] - baseline[i]);
        }
        log.accept(s.append(' ').append(state).toString());
        // Keep events since the preceding exit, including composition before the next opening.
        if (phase.equals("exit")) System.arraycopy(totals, 0, baseline, 0, totals.length);
    }
}
