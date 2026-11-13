// Host check of the Clear Glass variant in refract.h (ClearGlass_Handoff/OPTICS_SPEC.md sections 6, 7
// and 9.1). A C twin of OULG_FS_CLEAR_FIELD_FMT, in double, with the same amplitude cap as the
// generated GLSL (oulg_clear_acap, rounded down to 4 decimals); the shader itself is compared with
// this twin by clear_render_check.mjs through the debug-3/4 field maps.
//  U1  bound: sigma_min >= max(0.25, 1 - rho (l0 + 3 l2 + 1.5 A)), sigma_max <= 1, det > 0, J symmetric,
//      on a grid of shapes x the parameter product of the specification;
//  U2  no sample leaves the card: depth(q + F) >= depth(q); prints the margin rho A band - b_e;
//  U3  C1 at depth = band, across the inner-rect diagonals and the medial axis (pills, beta = 1);
//  U4  the straight/arc junction jump equals rho S / rho_c and stays <= rho 1.125 A (reported);
//  U5  blur: b_e at the outline, b_c past the band, monotone, flat at both ends;
//  U6  with the v0.23 parameters the clear field is the lockscreen field (twin of the baseline);
//  U7  the mode-2 tag slice in both decoders, with an fp16 radius uniform on the clip program;
//  U8  baseline report (no change): profile 0's v0.7 lens with its dome folds above k*;
// plus the range checks of oulg_fix_clear and the generated library's size against its buffer in
// every mode/profile/debug/external combination (>= 25 % headroom) and the text invariance of the
// glow, light, edge, tone, disc and B-spline functions (I4).
// Meniscus (heads-up, fold > 0), a second twin (OULG_FS_MENISCUS_FIELD_FMT), which folds by design:
//  M1  every sample stays inside the card and never moves outward;
//  M2  the shift and its slope vanish at depth = band;
//  M3  on a straight edge the source depth has one minimum, at band (1 - 1 / sqrt(1 + (a / band)^2));
//  M4  the outline samples a px inside (a = min(fold band, cap min(hs)));
// and the heads-up clear material: neutral defaults, ranges, output luma increasing with the backdrop's.
// Build: cc -std=c99 -O2 -I.. -o cc clear_check.c -lm
#include <math.h>
#include "refract.h"

static int fails;
#define CHECK(cond, ...) do { if (!(cond)) { fails++; if (fails <= 20) { printf("FAIL: "); printf(__VA_ARGS__); printf("\n"); } } } while (0)

static double clampd(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
static double signd(double v) { return v > 0 ? 1 : v < 0 ? -1 : 0; }
static double smootherd(double lo, double hi, double x) { double u = clampd((x - lo) / (hi - lo), 0, 1); return u * u * u * (u * (u * 6 - 15) + 10); }

struct cfg { double beta, acap, l0, l2, rho, be, bc, bexp; int mode; };

static double depth_of(double qx, double qy, double hx, double hy, double r) {
    double wx = fabs(qx) - (hx - r), wy = fabs(qy) - (hy - r);
    double vx = fmax(wx, 0), vy = fmax(wy, 0);
    return r - sqrt(vx * vx + vy * vy) - fmin(fmax(wx, wy), 0);
}

// The twin: F = rho (s_body + s_edge) and the blur radius (before the profile's global blur scale).
static void field_clear(double qx, double qy, double hx, double hy, double r, double k, const struct cfg *c, double s[2], double *br) {
    double wx = fabs(qx) - (hx - r), wy = fabs(qy) - (hy - r);
    double vx = fmax(wx, 0), vy = fmax(wy, 0), l = sqrt(vx * vx + vy * vy);
    double bevel = clampd(r * 0.42, 16, 56), band = clampd(c->beta * r, bevel, r);
    double depth = r - l - fmin(fmax(wx, wy), 0);
    double sb = smootherd(0, band, depth);
    *br = c->be + (c->bc - c->be) * (sb > 0 ? pow(sb, c->bexp) : 0);
    double R = fmin(hx, hy), ux = qx / fmax(hx, 1), uy = qy / fmax(hy, 1);
    s[0] = -R * ux * (c->l0 + c->l2 * ux * ux);
    s[1] = -R * uy * (c->l0 + c->l2 * uy * uy);
    if (l > 0 && depth > 0 && depth < band) {
        double A = c->mode == 1 ? fmin(k, c->acap) : c->acap, t = 1 - depth / band, mag = A * band * t * t * (3 - 2 * t);
        s[0] -= vx / l * signd(qx) * mag;
        s[1] -= vy / l * signd(qy) * mag;
    }
    s[0] *= c->rho; s[1] *= c->rho;
}

// The lockscreen's v0.23 field as optics 11 emits it (band1 > 0 branch of oulg_shift, then refract).
static void field23(double qx, double qy, double hx, double hy, double r, double k, double band1, double l0, double acap,
                    double edge, double core, double bexp, double s[2], double *br) {
    double wx = fabs(qx) - (hx - r), wy = fabs(qy) - (hy - r);
    double vx = fmax(wx, 0), vy = fmax(wy, 0), l = sqrt(vx * vx + vy * vy);
    double bevel = clampd(r * 0.42, 16, 56), depth = r - l - fmin(fmax(wx, wy), 0);
    double band = clampd(band1 * r, bevel, r), sb = smootherd(0, band, depth);
    double bw = sb > 0 ? pow(sb, bexp) : 0;
    *br = edge + (core - edge) * bw;
    double R = fmin(hx, hy);
    s[0] = -l0 * qx * (R / hx); s[1] = -l0 * qy * (R / hy);
    if (l <= 0 || depth <= 0 || depth >= band) return;
    double t = 1 - depth / band, mag = fmin(k, acap) * band * t * t * (3 - 2 * t);
    s[0] -= vx / l * signd(qx) * mag; s[1] -= vy / l * signd(qy) * mag;
}

// J = I + dF/dq by central differences: j[0] = J00, j[1] = J01 (d sx / d qy), j[2] = J10, j[3] = J11.
static void jac(double qx, double qy, double hx, double hy, double r, double k, const struct cfg *c, double h, double j[4]) {
    double a[2], b[2], d[2], e[2], br;
    field_clear(qx + h, qy, hx, hy, r, k, c, a, &br); field_clear(qx - h, qy, hx, hy, r, k, c, b, &br);
    field_clear(qx, qy + h, hx, hy, r, k, c, d, &br); field_clear(qx, qy - h, hx, hy, r, k, c, e, &br);
    j[0] = 1 + (a[0] - b[0]) / (2 * h); j[1] = (d[0] - e[0]) / (2 * h);
    j[2] = (a[1] - b[1]) / (2 * h); j[3] = 1 + (d[1] - e[1]) / (2 * h);
}
static void svd2(const double j[4], double *lo, double *hi) {
    double a2 = j[0] * j[0] + j[2] * j[2], b = j[0] * j[1] + j[2] * j[3], c2 = j[1] * j[1] + j[3] * j[3];
    double tr = a2 + c2, disc = sqrt(fmax((a2 - c2) * (a2 - c2) + 4 * b * b, 0));
    *lo = sqrt(fmax((tr - disc) / 2, 0)); *hi = sqrt((tr + disc) / 2);
}

static const double SHAPES[][3] = {{670, 108, 108}, {670, 108, 107.4}, {670, 300, 92}, {700, 160, 56}, {540, 400, 140},
                                   {670, 96, 48}, {670, 200, 108.4}, {660, 150, 92}, {660, 90, 90}, {300, 300, 120}};
#define NSHAPES ((int) (sizeof SHAPES / sizeof SHAPES[0]))

// The generated amplitude cap for a parameter set (as oulg_clear_field computes it).
static double acap_of(double amp, double l0, double l2, double rho) {
    struct oulg_clear c = OULG_CLEAR_DEFAULT;
    c.amp[0] = (float) amp; c.body[0] = (float) l0; c.body2[0] = (float) l2;
    return (double) (long) (oulg_clear_acap(&c, 0, rho) * 1e4) / 1e4;
}

static void u1_u2(void) {
    static const double betas[] = {0.42, 0.85, 1.0}, amps[] = {0.1, 0.33, 0.4, 0.5}, l0s[] = {0, 0.06, 0.10, 0.15},
                        l2s[] = {0, 0.03, 0.05}, rhos[] = {0.8, 1.0, 1.2};
    long n = 0; double worstMargin = 1e9, smaxAll = 0, sminAll = 9, asymAll = 0, outward = 1e9, marginBeta = 0;
    int marginShape = 0;
    for (int bi = 0; bi < 3; bi++) for (int ai = 0; ai < 4; ai++) for (int li = 0; li < 4; li++) for (int mi = 0; mi < 3; mi++) for (int ri = 0; ri < 3; ri++) {
        struct cfg c = {betas[bi], 0, l0s[li], l2s[mi], rhos[ri], 4, 24, 2, 1};
        c.acap = acap_of(amps[ai], c.l0, c.l2, c.rho);
        double bound = 1 - c.rho * (c.l0 + 3 * c.l2 + 1.5 * c.acap);
        for (int si = 0; si < NSHAPES; si++) {
            double hx = SHAPES[si][0] / 2, hy = SHAPES[si][1] / 2, r = SHAPES[si][2];
            if (r > hy) r = hy;
            double bevel = clampd(r * 0.42, 16, 56), band = clampd(c.beta * r, bevel, r);
            // Dense (1.37 px) where the band acts, 6.1 px in the affine interior (constant Jacobian).
            for (double qy = -hy + 0.31; qy < hy; ) {
                for (double qx = -hx + 0.17; qx < hx; ) {
                    double d = depth_of(qx, qy, hx, hy, r);
                    double step = d < band + 3 ? 1.37 : 6.1;
                    if (d > 0.05) {
                        double j[4], lo, hi, s[2], br;
                        jac(qx, qy, hx, hy, r, 1.2, &c, 1e-4, j); svd2(j, &lo, &hi); n++;
                        double det = j[0] * j[3] - j[1] * j[2], asym = fabs(j[1] - j[2]);
                        CHECK(lo >= bound - 1e-4 && lo >= OULG_CLEAR_M - 1e-4, "U1 sigma_min %.5f < bound %.5f at (%.2f,%.2f) shape %d beta %.2f A %.4f l0 %.2f l2 %.2f rho %.1f", lo, bound, qx, qy, si, c.beta, c.acap, c.l0, c.l2, c.rho);
                        CHECK(hi <= 1 + 1e-5, "U1 sigma_max %.6f > 1", hi);
                        CHECK(det > 0, "U1 det %.5f <= 0", det);
                        CHECK(asym < 1e-6, "U1 asymmetry %.2e", asym);
                        sminAll = fmin(sminAll, lo); smaxAll = fmax(smaxAll, hi); asymAll = fmax(asymAll, asym);
                        field_clear(qx, qy, hx, hy, r, 1.2, &c, s, &br);
                        double d2 = depth_of(qx + s[0], qy + s[1], hx, hy, r);
                        CHECK(d2 >= d - 1e-9, "U2 sample outward by %.3g px", d - d2);
                        outward = fmin(outward, d2 - d);
                    }
                    qx += d < band + 3 ? 1.37 : step;
                }
                qy += 1.37;
            }
            // The rim margin (spec 5.3, a soft rule printed, not enforced) at the starting A and dome.
            if (c.l2 == 0 && c.rho == 1 && ai == 2 && li == 1 && c.rho * c.acap * band - c.be < worstMargin) {
                worstMargin = c.rho * c.acap * band - c.be;
                marginShape = si; marginBeta = c.beta;
            }
        }
    }
    printf("U1 clear field: %ld points over 432 parameter sets x %d shapes; sigma_min %.4f (floor 0.25), sigma_max %.6f, |J01-J10| <= %.1e\n",
           n, NSHAPES, sminAll, smaxAll, asymAll);
    printf("U2 samples: min depth(q+F) - depth(q) = %+.4f px (never outward); rim margin rho A band - b_e >= %.1f px "
           "(A 0.40, l0 0.06, b_e 4; smallest on shape %.0fx%.0f r %.0f, beta %.2f)\n", outward, worstMargin,
           SHAPES[marginShape][0], SHAPES[marginShape][1], SHAPES[marginShape][2], marginBeta);
}

static double jump_at(double ax, double ay, double bx, double by, double hx, double hy, double r, const struct cfg *c) {
    double ja[4], jb[4], m = 0;
    jac(ax, ay, hx, hy, r, 1.2, c, 1e-4, ja); jac(bx, by, hx, hy, r, 1.2, c, 1e-4, jb);
    for (int i = 0; i < 4; i++) m = fmax(m, fabs(ja[i] - jb[i]));
    return m;
}

static void u3_u4(void) {
    const double e = 0.01;
    double worstBand = 0, worstMedial = 0, worstInner = 0, worstJunction = 0, junctionMax = 0;
    for (int bi = 0; bi < 2; bi++) for (int pi = 0; pi < 4; pi++) {
        // Pills r = hy and r = hy - 0.6, a card with r << hy, and the 660x150 heads-up shape.
        static const double shp[4][3] = {{670, 108, 108}, {670, 109.2, 108}, {660, 150, 92}, {670, 300, 92}};
        double hx = shp[pi][0] / 2, hy = shp[pi][1] / 2, r = fmin(shp[pi][2], hy);
        struct cfg c = {bi ? 1.0 : 0.85, 0, 0.10, 0.03, 1.0, 4, 24, 2, 2};
        c.acap = acap_of(0.40, c.l0, c.l2, c.rho);
        double bevel = clampd(r * 0.42, 16, 56), band = clampd(c.beta * r, bevel, r);
        for (double x = 0; x < hx - r - 1; x += 37) {
            if (hy - band > 0.02) worstBand = fmax(worstBand, jump_at(x, hy - band - e, x, hy - band + e, hx, hy, r, &c));
            worstMedial = fmax(worstMedial, jump_at(x, -e, x, e, hx, hy, r, &c));
        }
        // Inner-rect diagonal (outside the band) and its corner.
        if (hy - r > band - r + 25) worstInner = fmax(worstInner, jump_at(hx - r - 20 - e, hy - r - 20, hx - r - 20 + e, hy - r - 20, hx, hy, r, &c));
        // Junction: |qx| = hx - r inside the band, at several depths.
        for (int di = 1; di <= 9; di++) {
            double d = band * di / 10.0, y = hy - d;
            if (y < 0) continue;
            double t = 1 - d / band, S = c.acap * band * t * t * (3 - 2 * t), want = c.rho * S / (r - d);
            double got = jump_at(hx - r - e, y, hx - r + e, y, hx, hy, r, &c);
            worstJunction = fmax(worstJunction, fabs(got - want));
            junctionMax = fmax(junctionMax, got);
            CHECK(fabs(got - want) < 1e-3, "U4 junction jump %.5f != rho S / rho_c %.5f (shape %d, beta %.2f, depth %.1f)", got, want, pi, c.beta, d);
            CHECK(got <= c.rho * 1.125 * c.acap + 1e-3, "U4 junction jump %.4f above rho 1.125 A", got);
        }
    }
    CHECK(worstBand < 1e-3, "U3 J jump at depth = band %.2e", worstBand);
    CHECK(worstMedial < 1e-3, "U3 J jump across the medial axis %.2e", worstMedial);
    CHECK(worstInner < 1e-3, "U3 J jump across an inner-rect diagonal %.2e", worstInner);
    printf("U3 C1: |dJ| across depth = band %.1e, medial axis %.1e, inner-rect diagonal %.1e (limit 1e-3)\n", worstBand, worstMedial, worstInner);
    printf("U4 straight/arc junction: |jump - rho S/rho_c| <= %.1e; largest jump %.3f (bound rho 1.125 A = %.3f); inherent, reported\n",
           worstJunction, junctionMax, 1.125 * acap_of(0.40, 0.10, 0.03, 1.0));
}

static void u5(void) {
    static const double exps[] = {1, 2, 4};
    for (int ei = 0; ei < 3; ei++) for (int si = 0; si < NSHAPES; si++) {
        struct cfg c = {0.85, 0.4, 0.06, 0, 1, 4, 24, exps[ei], 1};
        double hx = SHAPES[si][0] / 2, hy = SHAPES[si][1] / 2, r = fmin(SHAPES[si][2], hy);
        double bevel = clampd(r * 0.42, 16, 56), band = clampd(c.beta * r, bevel, r), prev = -1, s[2], br;
        for (double d = 0; d <= hy; d += 0.25) {
            field_clear(0, hy - d, hx, hy, r, 1.2, &c, s, &br);
            CHECK(br >= prev - 1e-12, "U5 blur not monotone at depth %.2f", d);
            prev = br;
            if (d == 0) CHECK(fabs(br - c.be) < 1e-12, "U5 br(0) = %.4f != b_e", br);
            if (d >= band) CHECK(fabs(br - c.bc) < 1e-12, "U5 br past the band %.4f != b_c", br);
        }
        double a, b;
        field_clear(0, hy - 0.01, hx, hy, r, 1.2, &c, s, &a); field_clear(0, hy - 0.02, hx, hy, r, 1.2, &c, s, &b);
        CHECK(fabs(b - a) / 0.01 < 1e-3, "U5 slope at the outline %.2e", (b - a) / 0.01);
        field_clear(0, hy - band + 0.01, hx, hy, r, 1.2, &c, s, &a); field_clear(0, hy - band + 0.02, hx, hy, r, 1.2, &c, s, &b);
        if (band < hy) CHECK(fabs(b - a) / 0.01 < 1e-3, "U5 slope at the band's end %.2e", (b - a) / 0.01);
    }
    printf("U5 blur: b_e at the outline, b_c past the band, monotone and flat at both ends (exponents 1, 2, 4)\n");
}

static void u6(void) {
    double worst = 0;
    struct oulg_profile kg = OULG_PROFILES_DEFAULT[1];
    double acap23 = fmin(0.5, (0.75 - kg.lensbody) / 1.5);
    struct cfg c = {kg.band, acap23, kg.lensbody, 0, 1, kg.edge, fmin(kg.core, kg.ccap), kg.blurexp, 1};
    for (int si = 0; si < NSHAPES; si++) {
        double hx = SHAPES[si][0] / 2, hy = SHAPES[si][1] / 2, r = fmin(SHAPES[si][2], hy);
        for (double qy = -hy + 0.3; qy < hy; qy += 2.3) for (double qx = -hx + 0.2; qx < hx; qx += 2.9)
            for (double k = 0.1; k <= 1.2; k += 0.35) {
                double a[2], b[2], bra, brb;
                field_clear(qx, qy, hx, hy, r, k, &c, a, &bra);
                field23(qx, qy, hx, hy, r, k, kg.band, kg.lensbody, acap23, kg.edge, fmin(kg.core, kg.ccap), kg.blurexp, b, &brb);
                worst = fmax(worst, fmax(fmax(fabs(a[0] - b[0]), fabs(a[1] - b[1])), fabs(bra - brb)));
            }
    }
    CHECK(worst < 1e-12, "U6 clear(v0.23 parameters) differs from field23 by %.2e", worst);
    printf("U6 clear with the v0.23 parameters reproduces the lockscreen field: max |difference| %.1e\n", worst);
}

// fp16 rounding (round to nearest even on the 11-bit significand), normal range only.
static double fp16(double v) {
    int e = (int) floor(log2(v));
    double ulp = ldexp(1.0, e - 10);
    return nearbyint(v / ulp) * ulp;
}
// SemBlurBridge.sfTag (as render_check.mjs models it): profile 0 from floor(r) - 1 + 0.55, profiles 1
// and 2 the largest radius <= r in their band, profile 3 a fixed .875.
static double tag(double r, double s, int pf) {
    if (pf == 3) return floor(r) + 0.875 <= r ? floor(r) + 0.875 : floor(r) - 0.125;
    if (pf == 0) return floor(r) - 1 + 0.55 + 0.15 * s;
    double t = floor(r) + (pf == 1 ? 0.30 : 0.05) + 0.15 * s;
    return t <= r ? t : t - 1;
}
// OULG_DECODE on a fraction (float arithmetic, as highp), for the FillRRect (clip = 0) or clip windows.
static float decode(float f, int clip) {
    static const float fill[4][3] = {{0.54f, 0.71f, 0.55f}, {0.29f, 0.46f, 0.30f}, {0.04f, 0.21f, 0.05f}, {0.79f, 0.96f, 0.80f}};
    static const float clp[4][3] = {{0.04f, 0.21f, 0.05f}, {0.79f, 0.96f, 0.80f}, {0.54f, 0.71f, 0.55f}, {0.29f, 0.46f, 0.30f}};
    const float (*w)[3] = clip ? clp : fill;
    for (int i = 0; i < 4; i++)
        if (f > w[i][0] && f < w[i][1]) {
            float s = (f - w[i][2]) / 0.15f;
            s = s < 0 ? 0 : s > 1 ? 1 : s;
            return 2.0f * (float) i + 0.1f + 1.1f * s;
        }
    return 0.0f;
}
static int is_clear(float kp) {
    float pf = kp >= 6.0f ? 3.0f : kp >= 4.0f ? 2.0f : kp >= 2.0f ? 1.0f : 0.0f;
    return kp > 0.0f && pf < 1.5f && kp - 2.0f * pf >= 1.035f;
}

static void u7(void) {
    int radii = 0, clearBad = 0, baseBad = 0, otherBad = 0; double firstMisread = 9;
    for (double r = 42.01; r < 127.5; r += 0.37) {
        radii++;
        for (int pf = 0; pf < 2; pf++) {
            double t = tag(r, 11.0 / 12.0, pf);
            float ff = (float) t; float f1 = ff - floorf(ff);
            double h = fp16(t + 0.5); float f2 = (float) (h - floor(h));
            float k1 = decode(f1, 0), k2 = decode(f2, 1);
            if (!is_clear(k1) || !is_clear(k2) || fabs(h - (t + 0.5)) > 1e-9 || (int) (k1 / 2) != pf || (int) (k2 / 2) != pf) clearBad++;
            for (double k = 0.10; k <= 0.85 + 1e-9; k += 0.005) {
                t = tag(r, (k - 0.1) / 1.1, pf); ff = (float) t; f1 = ff - floorf(ff);
                h = fp16(t + 0.5); f2 = (float) (h - floor(h));
                if (is_clear(decode(f1, 0)) || is_clear(decode(f2, 1))) baseBad++;
            }
            for (double k = 0.85; k <= 1.2; k += 0.005) {
                t = tag(r, (k - 0.1) / 1.1, pf); h = fp16(t + 0.5); f2 = (float) (h - floor(h));
                if (is_clear(decode(f2, 1)) && k < firstMisread) firstMisread = k;
            }
        }
        // Profiles 2 (both text colours) and 3 never read as clear.
        double others[3][2] = {{2, 0.30}, {2, 0.818 * 1.1 + 0.1}, {3, 0}};
        for (int i = 0; i < 3; i++) {
            int pf = (int) others[i][0];
            double t = tag(r, (others[i][1] - 0.1) / 1.1, pf), h = fp16(t + 0.5);
            float ff = (float) t;
            if (is_clear(decode(ff - floorf(ff), 0)) || is_clear(decode((float) (h - floor(h)), 1))) otherBad++;
        }
    }
    CHECK(clearBad == 0, "U7 clear tag misdecoded %d times", clearBad);
    CHECK(baseBad == 0, "U7 baseline strength <= 0.85 read as clear %d times", baseBad);
    CHECK(otherBad == 0, "U7 profile 2/3 tag read as clear %d times", otherBad);
    printf("U7 tag slice s = 11/12 over %d radii (42, 127.5), both profiles: clear in fill and fp16 clip %s; baseline k in [0.10, 0.85] never clear; "
           "profiles 2/3 never clear; first fp16 misread above the 0.85 limit: k = %.3f\n", radii, clearBad ? "FAILED" : "always", firstMisread);
}

static void u8(void) {
    // Profile 0 (v0.7 lens + dome, refract 1): radial Jacobian 1 - (l0 + 2 k t) on a straight edge.
    double l0 = OULG_PROFILES_DEFAULT[0].lensbody, rho = OULG_PROFILES_DEFAULT[0].refract, kstar = (1 / rho - l0) / 2;
    printf("U8 baseline report (unchanged): profile 0 folds for k > %.3f;", kstar);
    static const double ks[] = {0.30, 0.45, 0.55}, rs[] = {92, 108};
    for (int i = 0; i < 3; i++) for (int j = 0; j < 2; j++) {
        double bevel = clampd(0.42 * rs[j], 16, 56), tf = (1 / rho - l0) / (2 * ks[i]);
        printf(" k %.2f r %.0f: %.1f px folded%s", ks[i], rs[j], tf < 1 ? bevel * (1 - tf) : 0.0, i == 2 && j == 1 ? "\n" : ";");
    }
}

static void fix_ranges(void) {
    struct oulg_clear c = OULG_CLEAR_DEFAULT, d = OULG_CLEAR_DEFAULT;
    c.mode[0] = 3; c.mode[1] = -1;
    c.band[0] = 0.41f; c.band[1] = 1.01f; c.amp[0] = -0.01f; c.amp[1] = 0.51f; c.body[0] = 0.16f; c.body[1] = NAN;
    c.body2[0] = 0.051f; c.edge[0] = 49; c.core[0] = 48.5f; c.blurexp[0] = 0.9f; c.blurexp[1] = 4.1f;
    c.veil = 0.21f; c.level = 0.86f; c.dim = 0.31f; c.knee = 0.61f; c.sat = 0.89f; c.cool = 2.1f;
    c.edge[1] = 30; c.core[1] = 10;
    oulg_fix_clear(&c);
    CHECK(c.mode[0] == 0 && c.mode[1] == 0, "fix: invalid modes not 0");
    CHECK(c.band[0] == d.band[0] && c.band[1] == d.band[1] && c.amp[0] == d.amp[0] && c.amp[1] == d.amp[1], "fix: band/amp");
    CHECK(c.body[0] == d.body[0] && c.body[1] == d.body[1] && c.body2[0] == d.body2[0], "fix: dome");
    CHECK(c.edge[0] == d.edge[0] && c.core[0] == d.core[0] && c.blurexp[0] == d.blurexp[0] && c.blurexp[1] == d.blurexp[1], "fix: blur");
    CHECK(c.veil == d.veil && c.level == d.level && c.dim == d.dim && c.knee == d.knee && c.sat == d.sat && c.cool == d.cool, "fix: material");
    CHECK(c.edge[1] == 10 && c.core[1] == 10, "fix: b_e > b_c not lowered to b_c (%.1f, %.1f)", c.edge[1], c.core[1]);
    struct oulg_clear e = OULG_CLEAR_DEFAULT;
    e.mode[0] = 2; e.band[0] = 0.42f; e.amp[0] = 0.5f; e.body[0] = 0.15f; e.body2[0] = 0.05f; e.edge[0] = 0; e.core[0] = 48;
    e.blurexp[0] = 1; e.veil = 0.2f; e.level = 0.1f; e.dim = 0.3f; e.knee = 0.6f; e.sat = 1.3f; e.cool = 0;
    struct oulg_clear e0 = e;
    oulg_fix_clear(&e);
    CHECK(memcmp(&e, &e0, sizeof e) == 0, "fix: in-range bounds were changed");
    // amax < 0 (only reachable outside the domain): A = 0; rho = 0: the cap is the amplitude itself.
    struct oulg_clear g = OULG_CLEAR_DEFAULT; g.body[0] = 0.8f;
    CHECK(oulg_clear_acap(&g, 0, 1.0) == 0.0, "acap: negative amax not clamped to 0");
    CHECK(fabs(oulg_clear_acap(&d, 0, 0.0) - 0.40) < 1e-6, "acap: rho 0");
    CHECK(fabs(oulg_clear_acap(&d, 1, 1.2) - fmin(0.40, (0.75 / 1.2 - 0.05) / 1.5)) < 1e-6, "acap: rho 1.2");
    printf("fix: modes, every clear domain (NaN, below, above), b_e > b_c, amax < 0 and rho in {0, 1.2} checked\n");
}

static void sizes(void) {
    size_t worst = 0, worstCap = 1, clearMax = 0; const char *worstName = "";
    char *buf = malloc(1 << 17);
    struct oulg_profile p[OULG_PROFILES] = {OULG_PROFILES_DEFAULT[0], OULG_PROFILES_DEFAULT[1], OULG_PROFILES_DEFAULT[2], OULG_PROFILES_DEFAULT[3]};
    p[0].bloom = 1.5f;  // the bloom disc is the longer one
    static char names[64][48];
    int nn = 0;
    for (int hu = 0; hu < 3; hu++) for (int kg = 0; kg < 3; kg++) for (int dbg = 0; dbg <= 4; dbg++) for (int ext = 0; ext < 2; ext++) {
        struct oulg_clear c = OULG_CLEAR_DEFAULT; c.mode[0] = hu; c.mode[1] = kg;
        CHECK(oulg_lib(buf, 1 << 17, ext, dbg, p, &c), "size: library failed hu %d kg %d debug %d ext %d", hu, kg, dbg, ext);
        size_t n = strlen(buf), cap = oulg_lib_cap(dbg, &c);
        CHECK(n * 4 <= cap * 3, "size: hu %d kg %d debug %d ext %d uses %zu of %zu (< 25 %% headroom)", hu, kg, dbg, ext, n, cap);
        if ((double) n / cap > (double) worst / worstCap) {
            worst = n; worstCap = cap;
            if (nn < 64) { snprintf(names[nn], sizeof names[nn], "hu %d kg %d debug %d%s", hu, kg, dbg, ext ? " external" : ""); worstName = names[nn++]; }
        }
        if (hu == 0 && kg == 0 && !ext && dbg == 0) printf("size: optics-11 library (bloom on) %zu of %zu bytes\n", n, cap);
        if ((hu || kg) && n > clearMax) clearMax = n;
    }
    printf("size: largest clear library %zu of 32768 bytes (hu 2, kg 2 is the dual-variant program)\n", clearMax);
    // An undersized buffer must fail cleanly (the rewrite then keeps the original shader).
    struct oulg_clear c = OULG_CLEAR_DEFAULT; c.mode[0] = c.mode[1] = 2;
    CHECK(!oulg_lib(buf, 4096, 0, 0, p, &c), "size: overflow not reported");
    printf("size: worst library %zu of %zu bytes (%.0f %% used, %s); overflow returns 0\n", worst, worstCap, 100.0 * worst / worstCap, worstName);
    free(buf);
}

// I4: the light, edge, tone, disc and B-spline text is the same in every mode.
static void text_invariance(void) {
    struct oulg_profile p[OULG_PROFILES] = {OULG_PROFILES_DEFAULT[0], OULG_PROFILES_DEFAULT[1], OULG_PROFILES_DEFAULT[2], OULG_PROFILES_DEFAULT[3]};
    char piece[8][4096]; size_t at;
    at = 0; oulg_append(piece[0], sizeof piece[0], &at, "%s", OULG_FS_GLOW);
    at = 0; oulg_append(piece[1], sizeof piece[1], &at, OULG_FS_EDGE_FMT, (double) p[3].light, (double) p[3].lwidth, (double) p[3].lfloor, (double) p[3].lcap, (double) p[3].body);
    at = 0; oulg_append(piece[2], sizeof piece[2], &at, OULG_FS_TONE_FMT, (double) p[2].cap, (double) p[2].lift);
    at = 0; oulg_append(piece[3], sizeof piece[3], &at, OULG_FS_DISC_FMT, 0, p[0].taps, p[0].taps);
    at = 0; oulg_append(piece[4], sizeof piece[4], &at, OULG_FS_DISC_FMT, 1, p[1].taps, p[1].taps);
    at = 0; oulg_append(piece[5], sizeof piece[5], &at, "%s", OULG_FS_BSPLINE);
    at = 0; oulg_append(piece[6], sizeof piece[6], &at, OULG_FS_LIGHT_CASE_FMT, -0.5, 0.5, (double) p[0].light, (double) p[0].lwidth, (double) p[0].lfloor, (double) p[0].lcap);
    at = 0; oulg_append(piece[7], sizeof piece[7], &at, OULG_FS_LIGHT_CASE_FMT, 0.5, 1.5, (double) p[1].light, (double) p[1].lwidth, (double) p[1].lfloor, (double) p[1].lcap);
    char *buf = malloc(1 << 17);
    for (int hu = 0; hu < 3; hu++) for (int kg = 0; kg < 3; kg++) {
        struct oulg_clear c = OULG_CLEAR_DEFAULT; c.mode[0] = hu; c.mode[1] = kg;
        oulg_lib(buf, 1 << 17, 0, 0, p, &c);
        for (int i = 0; i < 8; i++) CHECK(strstr(buf, piece[i]) != NULL, "I4 piece %d changed in hu %d kg %d", i, hu, kg);
        // The baseline material and field are kept verbatim under their _base names.
        if (kg) CHECK(strstr(buf, "mediump vec4 oulg_material_base(mediump vec4 c) {\n") && strstr(buf, "oulg_material_clear"), "I4 material dispatch");
        if (hu || kg) CHECK(strstr(buf, "highp vec2 oulg_field_base(") && strstr(buf, "highp vec2 oulg_shift_base("), "I4 field dispatch");
    }
    free(buf);
    printf("I4 glow, light cases, edge, tone, discs and B-spline: identical text in all 9 mode pairs\n");
}

// ---- Meniscus (heads-up, fold > 0) and the heads-up clear material ----
struct mcfg { double beta, fold, cap, l0, l2, rho, be, bc, bexp; };

// Twin of OULG_FS_MENISCUS_FIELD_FMT, times the profile's global refraction scale.
static void field_meniscus(double qx, double qy, double hx, double hy, double r, const struct mcfg *c, double s[2], double *br) {
    double wx = fabs(qx) - (hx - r), wy = fabs(qy) - (hy - r);
    double vx = fmax(wx, 0), vy = fmax(wy, 0), l = sqrt(vx * vx + vy * vy);
    double bevel = clampd(r * 0.42, 16, 56), band = clampd(c->beta * r, bevel, r);
    double depth = r - l - fmin(fmax(wx, wy), 0);
    double sb = smootherd(0, band, depth);
    *br = c->be + (c->bc - c->be) * (sb > 0 ? pow(sb, c->bexp) : 0);
    double R = fmin(hx, hy), ux = qx / fmax(hx, 1), uy = qy / fmax(hy, 1);
    s[0] = -R * ux * (c->l0 + c->l2 * ux * ux);
    s[1] = -R * uy * (c->l0 + c->l2 * uy * uy);
    if (l > 0 && depth > 0 && depth < band) {
        double x = 1 - depth / band, a = fmin(c->fold * band, c->cap * R), mag = a * (1 - sqrt(fmax(1 - x * x, 0)));
        s[0] -= vx / l * signd(qx) * mag;
        s[1] -= vy / l * signd(qy) * mag;
    }
    s[0] *= c->rho; s[1] *= c->rho;
}
static double mcap_of(double l0, double l2, double rho) {
    struct oulg_clear c = OULG_CLEAR_DEFAULT;
    c.body[0] = (float) l0; c.body2[0] = (float) l2;
    return (double) (long) (oulg_meniscus_cap(&c, rho) * 1e4) / 1e4;
}

// M1: no sample leaves the card or moves outward, over shapes x band x fold x dome x rho.
static void m1(void) {
    static const double betas[] = {0.42, 0.85, 1.0}, folds[] = {0.5, 1, 2, 3}, domes[][2] = {{0, 0}, {0.06, 0}, {0.15, 0.05}},
                        rhos[] = {0.8, 1.0, 1.2};
    long n = 0; double inward = 1e9, deepest = 0;
    for (int bi = 0; bi < 3; bi++) for (int fi = 0; fi < 4; fi++) for (int di = 0; di < 3; di++) for (int ri = 0; ri < 3; ri++) {
        struct mcfg c = {betas[bi], folds[fi], mcap_of(domes[di][0], domes[di][1], rhos[ri]), domes[di][0], domes[di][1], rhos[ri], 24, 24, 1};
        for (int si = 0; si < NSHAPES; si++) {
            double hx = SHAPES[si][0] / 2, hy = SHAPES[si][1] / 2, r = fmin(SHAPES[si][2], hy);
            for (double qy = -hy + 0.31; qy < hy; qy += 1.93) for (double qx = -hx + 0.17; qx < hx; qx += 1.93) {
                double d = depth_of(qx, qy, hx, hy, r);
                if (d <= 0.05) continue;
                double s[2], br;
                field_meniscus(qx, qy, hx, hy, r, &c, s, &br); n++;
                double d2 = depth_of(qx + s[0], qy + s[1], hx, hy, r);
                CHECK(d2 > 0, "M1 sample outside the card (depth %.3f) at (%.2f,%.2f) shape %d beta %.2f fold %.1f rho %.1f", d2, qx, qy, si, c.beta, c.fold, c.rho);
                CHECK(d2 >= d - 1e-9, "M1 sample outward by %.3g px at (%.2f,%.2f) shape %d beta %.2f fold %.1f l0 %.2f rho %.1f", d - d2, qx, qy, si, c.beta, c.fold, c.l0, c.rho);
                CHECK(fabs(qx + s[0]) <= hx && fabs(qy + s[1]) <= hy, "M1 sample beyond the box of the card");
                inward = fmin(inward, d2 - d);
                deepest = fmax(deepest, hypot(s[0], s[1]) / fmin(hx, hy));
            }
        }
    }
    printf("M1 meniscus samples: %ld points over 108 parameter sets x %d shapes; all inside the card, min depth(q+F) - depth(q) = %+.4f px, "
           "largest |F| / min(hs) = %.3f\n", n, NSHAPES, inward, deepest);
}

// M2/M3/M4 on the straight top edge (qx = 0), where the map is one-dimensional in depth.
static void m2_m3_m4(void) {
    static const double folds[] = {0.5, 1, 2, 3};
    double worstC1 = 0, worstFold = 0, worstRim = 0, worstC0 = 0;
    for (int si = 0; si < NSHAPES; si++) for (int fi = 0; fi < 4; fi++) for (int bi = 0; bi < 2; bi++) {
        struct mcfg c = {bi ? 0.85 : 0.42, folds[fi], mcap_of(0, 0, 1), 0, 0, 1, 24, 24, 1};
        double hx = SHAPES[si][0] / 2, hy = SHAPES[si][1] / 2, r = fmin(SHAPES[si][2], hy);
        double bevel = clampd(r * 0.42, 16, 56), band = clampd(c.beta * r, bevel, r), a = fmin(c.fold * band, c.cap * fmin(hx, hy));
        double s[2], t[2], br;
        // M2: the shift and its slope vanish at depth = band (C1), and the shift is continuous there.
        if (band < hy - 0.05) {
            field_meniscus(0, -hy + band - 0.01, hx, hy, r, &c, s, &br); field_meniscus(0, -hy + band - 0.02, hx, hy, r, &c, t, &br);
            worstC0 = fmax(worstC0, fabs(s[1]));
            worstC1 = fmax(worstC1, fabs(s[1] - t[1]) / 0.01);
        }
        // M4: at the outline the sample is a px inside.
        field_meniscus(0, -hy + 1e-10, hx, hy, r, &c, s, &br);  // the profile is vertical at the outline: stay very close
        worstRim = fmax(worstRim, fabs(s[1] - a));
        // M3: the source depth falls to one minimum at band (1 - 1 / sqrt(1 + (a / band)^2)), then rises.
        double best = 1e9, at = 0, prev = 1e9; int rising = 0, turns = 0;
        for (double d = 0.005; d < band; d += 0.01) {
            field_meniscus(0, -hy + d, hx, hy, r, &c, s, &br);
            double src = d + s[1];
            if (src < best) { best = src; at = d; }
            if (rising && src < prev - 1e-9) turns++;
            if (src > prev + 1e-9) rising = 1;
            prev = src;
        }
        double want = band * (1 - 1 / sqrt(1 + (a / band) * (a / band)));
        CHECK(fabs(at - want) < 0.03, "M3 fold at depth %.3f, expected %.3f (shape %d fold %.1f band %.1f a %.1f)", at, want, si, c.fold, band, a);
        CHECK(turns == 0, "M3 source depth turns %d more times after the fold (shape %d fold %.1f)", turns, si, c.fold);
        worstFold = fmax(worstFold, fabs(at - want));
    }
    CHECK(worstC0 < 1e-5, "M2 shift at depth = band %.2e px", worstC0);
    CHECK(worstC1 < 2e-3, "M2 slope at depth = band %.2e", worstC1);
    CHECK(worstRim < 1e-3, "M4 outline shift differs from a by %.2e px", worstRim);
    // The two shapes the proposal is sized for (printed): the heads-up card and a 2 x band pill.
    static const double shown[2][3] = {{1340, 374, 108}, {1340, 216, 108}};
    for (int i = 0; i < 2; i++) {
        double hy = shown[i][1] / 2, r = fmin(shown[i][2], hy), band = clampd(0.42 * r, clampd(r * 0.42, 16, 56), r);
        double a = fmin(2.0 * band, mcap_of(0, 0, 1) * hy), f = band * (1 - 1 / sqrt(1 + (a / band) * (a / band)));
        printf("M3 card %.0fx%.0f r %.0f, band 0.42, fold 2: band %.1f px, a %.1f px, mirrored rim %.1f px, stretched zone %.1f px\n",
               shown[i][0], shown[i][1], r, band, a, f, band - f);
    }
    printf("M2 C1 at depth = band: |shift| <= %.1e px, |slope| <= %.1e; M3 fold depth within %.3f px of band (1 - 1/sqrt(1 + (a/band)^2)), "
           "one minimum; M4 outline shift = a within %.1e px\n", worstC0, worstC1, worstFold, worstRim);
}

// The heads-up clear material: ranges, the monotone bound and the luma it outputs.
static void hu_material(void) {
    struct oulg_clear c = OULG_CLEAR_DEFAULT, d = OULG_CLEAR_DEFAULT;
    CHECK(d.fold == 0 && d.hsat == 1 && d.scrim == 0 && d.scrimmax == 0, "hu material: defaults are not neutral");
    c.mode[0] = 1;
    CHECK(!oulg_hu_material_on(&c), "hu material: emitted with neutral values");
    c.fold = 3.1f; c.hsat = 2.1f; c.scrim = 0.61f; c.scrimmax = 0.81f; oulg_fix_clear(&c);
    CHECK(c.fold == 0 && c.hsat == 1 && c.scrim == 0 && c.scrimmax == 0, "hu material: out-of-range values kept");
    c.fold = NAN; c.hsat = NAN; c.scrim = NAN; c.scrimmax = NAN; oulg_fix_clear(&c);
    CHECK(c.fold == 0 && c.hsat == 1 && c.scrim == 0 && c.scrimmax == 0, "hu material: NaN kept");
    c.scrim = 0.3f; c.scrimmax = 0.1f; oulg_fix_clear(&c);
    CHECK(c.scrimmax == c.scrim, "hu material: scrimmax below scrim not raised");
    double worst = 1e9;
    for (double lo = 0; lo <= 0.6001; lo += 0.05) for (double hi = 0; hi <= 0.8001; hi += 0.05) {
        struct oulg_clear e = OULG_CLEAR_DEFAULT; e.scrim = (float) lo; e.scrimmax = (float) hi; oulg_fix_clear(&e);
        double prev = -1;
        for (double y = 0; y <= 1.0001; y += 0.001) {
            double out = y * (1 - (e.scrim + (e.scrimmax - e.scrim) * (y * y * (3 - 2 * y))));
            CHECK(out >= prev - 1e-9, "hu material: luma falls at y %.3f (scrim %.2f, scrimmax %.2f)", y, e.scrim, e.scrimmax);
            if (prev >= 0) worst = fmin(worst, (out - prev) / 0.001);
            prev = out;
        }
    }
    struct oulg_clear s = OULG_CLEAR_DEFAULT; s.mode[0] = 1; s.hsat = 1.5f; s.scrim = 0.12f; s.scrimmax = 0.5f; oulg_fix_clear(&s);
    CHECK(s.scrim == 0.12f && s.scrimmax == 0.5f && oulg_hu_material_on(&s), "hu material: the reference values were changed");
    printf("hu material: neutral by default, ranges and NaN checked, output luma never falls as y rises (smallest slope %.3f); "
           "scrim 0.12-0.50 leaves y 0.25 at %.3f, 0.50 at %.3f, 0.75 at %.3f and white at %.2f\n", worst,
           0.25 * (1 - (0.12 + 0.38 * 0.15625)), 0.5 * (1 - (0.12 + 0.38 * 0.5)), 0.75 * (1 - (0.12 + 0.38 * 0.84375)), 1 - 0.5);
    CHECK(strstr(OULG_FS_HU_MATERIAL_FMT, "w = y * y * (3.0 - 2.0 * y)") && strstr(OULG_FS_HU_MATERIAL_FMT, "* w);"),
          "hu material: the shader weight is not the one checked here");
}

// ---- Heads-up adaptive blur: a model over patterns that vary along x only ----
#define AN 801   // x = -400 .. 400
static void aconv(const double *a, const double *k, int h, double *out) {
    for (int x = 0; x < AN; x++) {
        double acc = 0;
        for (int m = -h; m <= h; m++) { int i = x + m < 0 ? 0 : x + m >= AN ? AN - 1 : x + m; acc += k[m + h] * a[i]; }
        out[x] = acc;
    }
}
// The marginal along x of the shader's disc (weights exp(-2 rho^2)); returns the half width.
static int disc_kernel(double R, double *k) {
    int h = (int) R; double sum = 0;
    for (int i = -h; i <= h; i++) {
        double acc = 0;
        for (int j = -h; j <= h; j++) { double rr = (i * i + j * j) / (R * R); if (rr <= 1) acc += exp(-2 * rr); }
        k[i + h] = acc; sum += acc;
    }
    for (int i = 0; i <= 2 * h; i++) k[i] /= sum;
    return h;
}
static int gauss_kernel(double sig, double *k) {
    int h = (int) (4 * sig); double sum = 0;
    for (int i = -h; i <= h; i++) { k[i + h] = exp(-i * i / (2 * sig * sig)); sum += k[i + h]; }
    for (int i = 0; i <= 2 * h; i++) k[i] /= sum;
    return h;
}
static double sstep(double lo, double hi, double d) { double t = fmin(fmax((d - lo) / (hi - lo), 0), 1); return t * t * (3 - 2 * t); }
// Direction changes of y over the central 500 px, ignoring steps below 1e-4.
static int extrema(const double *y) {
    int n = 0; double last = 0;
    for (int x = 151; x < 651; x++) {
        double d = y[x] - y[x - 1];
        if (fabs(d) <= 1e-4) continue;
        if (last != 0 && (d > 0) != (last > 0)) n++;
        last = d;
    }
    return n;
}
static double span(const double *y) { double lo = 1e9, hi = -1e9; for (int x = 150; x < 651; x++) { lo = fmin(lo, y[x]); hi = fmax(hi, y[x]); } return hi - lo; }
struct amodel { double a[AN], now[AN], old[AN]; };
// src through an optional Gaussian pre-blur (the region blur), then the body: A, B, the shader's mix
// (now) and the first version's |yA - yB| mix (old).
static void amodel_run(const double *src, double pre, double R, struct amodel *m) {
    static double s[AN], s2[AN], b[AN], q[AN], k[1024];
    if (pre > 0) aconv(src, k, gauss_kernel(pre, k), s); else memcpy(s, src, sizeof s);
    aconv(s, k, disc_kernel(R, k), m->a);
    int h = disc_kernel(R * OULG_ADAPT_WIDE, k);
    for (int x = 0; x < AN; x++) s2[x] = s[x] * s[x];
    aconv(s, k, h, b); aconv(s2, k, h, q);
    for (int x = 0; x < AN; x++) {
        double sd = sqrt(fmax(q[x] - b[x] * b[x], 0));
        m->now[x] = m->a[x] + sstep(OULG_ADAPT_LO, OULG_ADAPT_HI, sd) * (b[x] - m->a[x]);
        m->old[x] = m->a[x] + sstep(0.06, 0.30, fabs(m->a[x] - b[x])) * (b[x] - m->a[x]);
    }
}
static double pat_at(int kind, int x) {
    switch (kind) {
    case 0: return abs(x) < 20 ? 1.0 : 0.25;                                  // one 40 px stroke
    case 1: return abs(x) < 40 ? 1.0 : 0.25;                                  // one 80 px stroke
    case 2: return abs(x - 50) < 20 || abs(x + 50) < 20 ? 1.0 : 0.25;         // two 40 px strokes, 60 px apart
    case 3: return x > 0 ? 1.0 : 0.25;                                        // a step
    case 4: return ((x + 400) / 4) % 2 == 0 ? 1.0 : 0.0;                      // 8 px stripes
    default: return abs(x) < 3 ? 1.0 : 0.0;                                   // a 6 px line
    }
}
// The heads-up adaptive blur: neutral default and range; then, on a 24 px disc with and without a
// 7.5 px region blur in front of it: the mix adds no extremum to what the plain disc shows (no
// doubled contour), lowers the residual of a stroke the disc cannot average out, and leaves fine
// detail as the plain disc renders it. The first version's |yA - yB| weight is the positive control.
static void hu_adapt(void) {
    struct oulg_clear c = OULG_CLEAR_DEFAULT; c.mode[0] = 1; oulg_fix_clear(&c);
    CHECK(OULG_CLEAR_DEFAULT.adapt == 0 && !oulg_hu_adapt_on(&c), "hu adapt: on by default");
    c.adapt = 1.1f; oulg_fix_clear(&c); CHECK(c.adapt == 0, "hu adapt: value above the range kept");
    c.adapt = NAN; oulg_fix_clear(&c); CHECK(c.adapt == 0, "hu adapt: NaN kept");
    c.adapt = 1; oulg_fix_clear(&c); CHECK(c.adapt == 1 && oulg_hu_adapt_on(&c), "hu adapt: 1 rejected");
    c.mode[0] = 0; CHECK(!oulg_hu_adapt_on(&c), "hu adapt: on with the heads-up clear off");
    static struct amodel m; static double src[AN];
    double keep40 = 0, keep80 = 0, fine = 0, bare = 0; int oldx = 0;
    for (int pre = 0; pre < 2; pre++) for (int kind = 0; kind < 6; kind++) {
        for (int x = 0; x < AN; x++) src[x] = pat_at(kind, x - 400);
        amodel_run(src, pre ? 7.5 : 0.0, 24.0, &m);
        if (kind < 4) CHECK(extrema(m.now) == extrema(m.a), "hu adapt: pattern %d pre %d has %d extrema, the plain disc %d",
                            kind, pre, extrema(m.now), extrema(m.a));
        // Fine detail: stripes always, the hairline behind the region blur (what the device samples).
        // The hairline on an unblurred source is reported: its 0.15 residual is spread, with no new extremum.
        for (int x = 150; x < 651 && kind >= 4; x++) {
            double d = fabs(m.now[x] - m.a[x]);
            if (kind == 5 && !pre) bare = fmax(bare, d); else fine = fmax(fine, d);
        }
        if (kind == 5) CHECK(extrema(m.now) == extrema(m.a), "hu adapt: the 6 px line gains extrema (pre %d)", pre);
        if (pre && kind == 0) { keep40 = span(m.now) / span(m.a); oldx = extrema(m.old); }
        if (pre && kind == 1) keep80 = span(m.now) / span(m.a);
    }
    CHECK(oldx > 1, "hu adapt: the |yA - yB| weight no longer shows the doubled contours (positive control, %d extrema)", oldx);
    CHECK(keep40 < 0.7, "hu adapt: a 40 px stroke keeps %.2f of the plain disc's residual", keep40);
    CHECK(fine < 0.02, "hu adapt: fine detail differs from the plain disc by %.3f", fine);
    printf("hu adapt: off by default, range and NaN checked; disc 24 px, wide x%.1f, with and without a 7.5 px region blur: strokes of 40 and 80 px, "
           "a pair of strokes and a step keep the plain disc's count of extrema (the |yA - yB| weight gave %d across one 40 px stroke instead of 1); "
           "residual of a 40 px stroke %.2f and of an 80 px one %.2f of the plain disc's; 8 px stripes, and a 6 px line behind the region blur, within %.3f of the plain disc (the line on an unblurred source: %.3f)\n",
           OULG_ADAPT_WIDE, oldx, keep40, keep80, fine, bare);
}

// Sizes and text with the meniscus and the heads-up material compiled.
static void meniscus_text(void) {
    char *buf = malloc(1 << 17), *ref = malloc(1 << 17); size_t most = 0;
    struct oulg_profile p[OULG_PROFILES] = {OULG_PROFILES_DEFAULT[0], OULG_PROFILES_DEFAULT[1], OULG_PROFILES_DEFAULT[2], OULG_PROFILES_DEFAULT[3]};
    p[0].bloom = 1.5f;
    for (int hu = 0; hu < 3; hu++) for (int kg = 0; kg < 3; kg++) for (int dbg = 0; dbg <= 4; dbg++) for (int ext = 0; ext < 2; ext++) {
        struct oulg_clear c = OULG_CLEAR_DEFAULT; c.mode[0] = hu; c.mode[1] = kg; c.fold = 2; c.hsat = 1.5f; c.scrim = 0.12f; c.scrimmax = 0.5f; c.adapt = 1;
        CHECK(oulg_lib(buf, 1 << 17, ext, dbg, p, &c), "meniscus: library failed hu %d kg %d debug %d ext %d", hu, kg, dbg, ext);
        size_t n = strlen(buf), cap = oulg_lib_cap(dbg, &c);
        CHECK(n * 4 <= cap * 3, "meniscus size: hu %d kg %d debug %d ext %d uses %zu of %zu", hu, kg, dbg, ext, n, cap);
        if (hu && n > most) most = n;
        int field = strstr(buf, "sqrt(max(1.0 - x * x, 0.0))") != NULL, mat = strstr(buf, "mediump vec4 oulg_material_hu(") != NULL;
        int routed = strstr(buf, "return oulg_cl > 0.5 ? oulg_material_hu(c) : c;") != NULL;
        CHECK(field == (hu != 0), "meniscus field presence wrong: hu %d kg %d debug %d ext %d", hu, kg, dbg, ext);
        CHECK(mat == (hu != 0 && !ext) && routed == mat, "hu material presence wrong: hu %d kg %d debug %d ext %d", hu, kg, dbg, ext);
        int wide = strstr(buf, "mediump vec4 oulg_body_hu(") != NULL, wrouted = strstr(buf, OULG_HU_BODY_TO) != NULL;
        CHECK(wide == (hu != 0 && !ext) && wrouted == wide, "hu adaptive body presence wrong: hu %d kg %d debug %d ext %d", hu, kg, dbg, ext);
        // With the heads-up off, the text is the one the same modes give without these parameters.
        if (!hu) {
            struct oulg_clear z = OULG_CLEAR_DEFAULT; z.mode[1] = kg;
            oulg_lib(ref, 1 << 17, ext, dbg, p, &z);
            CHECK(strcmp(ref, buf) == 0, "meniscus parameters changed the text with the heads-up off (kg %d debug %d ext %d)", kg, dbg, ext);
        }
    }
    printf("meniscus text: field, material and adaptive body only in heads-up clear programs; largest library with all %zu of 32768 bytes\n", most);
    free(buf); free(ref);
}

int main(void) {
    u1_u2(); u3_u4(); u5(); u6(); u7(); u8(); fix_ranges(); sizes(); text_invariance();
    m1(); m2_m3_m4(); hu_material(); hu_adapt(); meniscus_text();
    if (fails) { printf("clear_check: %d FAILURES\n", fails); return 1; }
    printf("clear_check: all checks passed\n");
    return 0;
}
