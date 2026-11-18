package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/** Bounded, per-opening accounting. Tokens contain no view, text or artwork. */
final class QsOpticsCoverage {
    static final class Token {
        final long id;
        final String role;
        boolean registered, bound, attempted, drawn;
        /** The box (its BoxWithOutline restart scope) and whether its outline was built attenuated. */
        volatile java.lang.ref.WeakReference<Object> box;
        volatile boolean rimAttenuated;
        String fallback;
        Token(long id, String role) { this.id = id; this.role = role; }
    }
    private long next, session;
    private int evicted;
    private final Map<Long, Token> tokens = new LinkedHashMap<>();
    private final Map<String, Long> examples = new LinkedHashMap<>();
    private final Set<String> emitted = new HashSet<>();
    private final BiConsumer<String, String> log;
    QsOpticsCoverage(BiConsumer<String, String> log) { this.log = log; }
    long session() { return session; }
    void begin() {
        session++; tokens.clear(); examples.clear(); emitted.clear(); evicted = 0;
    }
    Token select(String role) {
        Token t = new Token(++next, role); adopt(t); path(t, "selected", "candidate"); return t;
    }
    void adopt(Token t) {
        if (tokens.containsKey(t.id)) return;
        if (tokens.size() == 512) { tokens.remove(tokens.keySet().iterator().next()); evicted++; }
        tokens.put(t.id, t);
        examples.putIfAbsent(t.role, t.id);
    }
    void stage(Token t, String stage, String reason) {
        adopt(t);
        if (stage.equals("registered")) t.registered = true;
        if (stage.equals("bound")) t.bound = true;
        if (stage.equals("attempt")) t.attempted = true;
        if (stage.equals("drawn")) { t.drawn = true; t.fallback = null; }
        if (stage.equals("fallback")) { t.drawn = false; t.fallback = reason; }
        path(t, stage, reason);
    }
    private void path(Token t, String stage, String reason) {
        if (examples.get(t.role) != t.id || !emitted.add(t.role + ":" + stage)) return;
        log.accept("QS_OPTICS_PATH", "session=" + session + " id=" + t.id + " role=" + t.role
                + " stage=" + stage + " reason=" + reason);
    }
    /**
     * The outline invariant is per box (its BoxWithOutline restart scope, the key QsLitRims uses),
     * not per token: every recomposition selects a new token, and once a box is lit its border is
     * built attenuated on that new token, while the drawing stays with the token already bound to
     * the box's background node (Compose does not update a node whose element is unchanged). A
     * token count therefore always reported rimWithoutOptics == rimAttenuated (R4 trace). A box is
     * "without optics" only if it has an attenuated outline and none of its tokens drew in this
     * session. The token count stays available as rimAttenuatedTokens.
     */
    String snapshot(String phase) {
        int registered=0, bound=0, attempted=0, drawn=0, fallback=0, noNode=0, attenuatedTokens=0;
        Map<String,Integer> roles = new LinkedHashMap<>();
        java.util.IdentityHashMap<Object, boolean[]> boxes = new java.util.IdentityHashMap<>();
        int orphanUnlit = 0;
        for (Token t : tokens.values()) {
            if(t.registered) registered++;
            if(t.bound) bound++; else noNode++;
            if(t.attempted) attempted++;
            if(t.drawn) drawn++; else fallback++;
            if(t.rimAttenuated) attenuatedTokens++;
            java.lang.ref.WeakReference<Object> ref = t.box;
            Object box = ref == null ? null : ref.get();
            if (box != null) {
                boolean[] f = boxes.computeIfAbsent(box, k -> new boolean[2]);
                f[0] |= t.rimAttenuated;
                f[1] |= t.drawn;
            } else if (t.rimAttenuated && !t.drawn) orphanUnlit++;
            roles.put(t.role, roles.getOrDefault(t.role, 0) + 1);
        }
        int attenuated = 0, unlit = orphanUnlit;
        for (boolean[] f : boxes.values()) if (f[0]) { attenuated++; if (!f[1]) unlit++; }
        return "session=" + session + " phase=" + phase + " counters=session eligible=" + tokens.size()
                + " selected=" + tokens.size() + " registered=" + registered + " bound=" + bound
                + " drawAttempt=" + attempted + " drawn=" + drawn + " fallback=" + fallback
                + " unmatched=" + noNode + " boxes=" + boxes.size() + " rimAttenuated=" + attenuated
                + " rimWithoutOptics=" + unlit + " rimAttenuatedTokens=" + attenuatedTokens
                + " evicted=" + evicted + " byRole=" + roles;
    }
    void summary(String phase) { log.accept("QS_OPTICS_COVERAGE", snapshot(phase)); }
}
