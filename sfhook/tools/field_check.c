// Host check of the refraction field in refract.h (OULG_FS_FIELD_FMT), ported line for line. It
// verifies on a dense grid of card shapes and lens strengths:
//  - the shift is zero outside the bevel band and C1 at its inner edge (depth = bevel);
//  - the shift points inward and never exceeds k * bevel;
//  - the 2-D Jacobian of the sampling map q -> q + s(q), by central differences, matches the closed
//    form det = m(t) * (1 - |s| / rho) (rho = distance to the corner centre; 1 on the straight
//    edges), with m = 1 - 2 k t for profile 0 (positive for k <= 0.5, folds exactly where
//    t > 1 / (2 k) above) and m = 1 - 6 A t (1 - t), A = min(k, 0.5), for profile 1 (never folds;
//    1 at the outline);
//  - the body blur radius rises monotonically from 0 at the rim to the core radius;
//  - the clip program's geometry (centre and half size rebuilt from uinnerRect and uradiusPlusHalf)
//    is the FillRRect program's, and both decode the same strength and profile from the corner tag;
//  - profile 1 keeps its slab refraction field; gradient changes the blur only.
// Build: cc -O2 -o fc field_check.c -lm
#include <math.h>
#include <stdio.h>

// OULG_PROFILES_DEFAULT; profile 1 also caps its body radius (v0.16).
static const double CORES[2] = {48.0, 80.0}, RAMPS[2] = {1.5, 1.5}, CCAP1 = 52.0;
static double brMax(int pf) { return pf ? fmin(CORES[1], CCAP1) : CORES[0]; }

static double clampd(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
static double smoothstepd(double e0, double e1, double x) { double t = clampd((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t); }
static double signd(double v) { return v > 0 ? 1 : v < 0 ? -1 : 0; }

// oulg_field: kp = k + 2 x profile; returns the shift in s[2], the body blur radius in *br.
static void field(double qx, double qy, double hx, double hy, double r, double kp, double s[2], double *br) {
    int pf = kp >= 2;
    double k = kp - 2 * pf;
    double wx = fabs(qx) - (hx - r), wy = fabs(qy) - (hy - r);
    double vx = fmax(wx, 0), vy = fmax(wy, 0);
    double l = sqrt(vx * vx + vy * vy);
    double bevel = clampd(r * 0.42, 16, 56);
    double depth = r - l - fmin(fmax(wx, wy), 0);
    *br = CORES[pf] * smoothstepd(0.25 * bevel, RAMPS[pf] * bevel, depth);
    if (pf) {
        double start = fmax(8.0, 0.25 * bevel);
        double u = clampd((depth - start) / (fmax(start + 1, RAMPS[pf] * bevel) - start), 0, 1);
        double body = u*u*u*(u*(u*6-15)+10);
        u = clampd((depth - 4) / (start - 4), 0, 1);
        double skin = fmin(8, brMax(pf)) * u*u*u*(u*(u*6-15)+10);
        *br = skin + (brMax(pf) - skin) * body;
    }
    s[0] = s[1] = 0;
    if (l <= 0 || depth <= 0 || depth >= bevel) return;
    double t = 1 - depth / bevel;
    double mag = pf ? fmin(k, 0.5) * bevel * t * t * (3 - 2 * t) : k * bevel * t * t;
    s[0] = -(vx / l) * signd(qx) * mag;
    s[1] = -(vy / l) * signd(qy) * mag;
}

// SemBlurBridge.sfTag and the two decoders (OULG_DECODE; FillRRect: radius fraction, clip: fraction
// of radius + 0.5). Both return kp = k + 2 x profile, 0 off the bands.
static double tag(double r, double k, int pf) {
    double s = clampd((k - 0.1) / 1.1, 0, 1);
    if (!pf) return floor(r) - 1 + 0.55 + 0.15 * s;
    double t = floor(r) + (pf == 1 ? 0.30 : 0.05) + 0.15 * s;
    return t <= r ? t : t - 1;
}
static double strength(double f, double base) { return 0.1 + 1.1 * clampd((f - base) / 0.15, 0, 1); }
static double decodeFill(double f) {
    return f > 0.54 && f < 0.71 ? strength(f, 0.55) : f > 0.29 && f < 0.46 ? 2 + strength(f, 0.30) : f > 0.04 && f < 0.21 ? 4 + strength(f, 0.05) : 0;
}
static double decodeClip(double f) {
    return f > 0.04 && f < 0.21 ? strength(f, 0.05) : f > 0.79 && f < 0.96 ? 2 + strength(f, 0.80) : f > 0.54 && f < 0.71 ? 4 + strength(f, 0.55) : 0;
}

// oulg_tone (profile 2), ported: light text (k < 0.65) caps luma, dark text lifts it.
static double toneLuma(double y, double k, double cap, double lift) {
    if (k < 0.65) { double k0 = cap - 0.12; return y > k0 ? k0 + 0.12 * (1 - exp((k0 - y) / 0.12)) : y; }
    double k1 = lift + 0.12;
    return y < k1 ? k1 - 0.12 * (1 - exp((y - k1) / 0.12)) : y;
}

static int failures;
#define CHECK(c, ...) do { if (!(c)) { if (failures++ < 20) { printf("FAIL: "); printf(__VA_ARGS__); printf("\n"); } } } while (0)

int main(void) {
    const double sizes[][3] = {{670, 108, 108}, {670, 300, 92}, {700, 160, 56}, {540, 400, 140}, {670, 96, 48}, {670, 200, 91.875}, {670, 200, 108.4}};
    const double ks[] = {0.1, 0.3, 0.4, 0.45, 0.5, 0.7, 1.2};
    long points = 0;
    double worstErr = 0, minDetLow = 1e9, minDet1 = 1e9, minM1[7] = {1e9, 1e9, 1e9, 1e9, 1e9, 1e9, 1e9}, maxMag[2][7] = {{0}};
    for (unsigned si = 0; si < sizeof sizes / sizeof sizes[0]; si++) {
        double hx = sizes[si][0], hy = sizes[si][1], r0 = sizes[si][2];
        for (unsigned ki = 0; ki < 2 * sizeof ks / sizeof ks[0]; ki++) {
            int pf = ki % 2;
            double k = ks[ki / 2], r = tag(r0, k, pf), kp = k + 2 * pf;
            double bevel = clampd(r * 0.42, 16, 56);
            CHECK(bevel < r, "bevel %.2f >= r %.2f", bevel, r);
            // Tag round trip, both programs.
            double kf = decodeFill(r - floor(r)), kc = decodeClip((r + 0.5) - floor(r + 0.5));
            CHECK(r <= r0 && r0 - r < 1.5, "tag %.3f for radius %.1f", r, r0);
            CHECK(fabs(kf - kp) < 1e-6 && fabs(kc - kp) < 1e-6, "tag k=%.2f profile %d fill=%.4f clip=%.4f", k, pf, kf, kc);
            // A mediump (fp16) uradiusPlusHalf keeps the profile, the strength only coarsely, while
            // r + 0.5 < 128 (step 1/16). From 128 the step is 1/8 and a tag can fall between the bands:
            // a clip-drawn card that large may lose its tag (both profiles; the FillRRect path is highp).
            if (r + 0.5 < 128) {
                double v = r + 0.5, ulp = ldexp(1, (int) floor(log2(v)) - 10), h16 = round(v / ulp) * ulp;
                double kh = decodeClip(h16 - floor(h16));
                CHECK(kh > 0 && (kh >= 2) == pf, "fp16 clip tag r=%.3f -> %.4f decodes %.3f", r, h16, kh);
            }
            // Clip geometry: Skia uploads the rect inset by r and r + 0.5; the rewrite rebuilds it.
            double L = 100, T = 400, inner[4] = {L + r, T + r, L + 2 * hx - r, T + 2 * hy - r}, rph = r + 0.5;
            double fullx = (inner[2] - inner[0]) + 2 * (rph - 0.5), fully = (inner[3] - inner[1]) + 2 * (rph - 0.5);
            double cx = 0.5 * (inner[0] + inner[2]), cy = 0.5 * (inner[1] + inner[3]);
            CHECK(fabs(fullx - 2 * hx) < 1e-9 && fabs(fully - 2 * hy) < 1e-9 && fabs(cx - (L + hx)) < 1e-9 && fabs(cy - (T + hy)) < 1e-9,
                  "clip geometry");
            const double h = 1e-3, step = 0.73;
            for (double qy = -hy + 0.05; qy < hy; qy += step) for (double qx = -hx + 0.05; qx < hx; qx += step) {
                double s[2], br, sx1[2], sx0[2], sy1[2], sy0[2], b2;
                field(qx, qy, hx, hy, r, kp, s, &br);
                double wx = fabs(qx) - (hx - r), wy = fabs(qy) - (hy - r);
                double vx = fmax(wx, 0), vy = fmax(wy, 0), l = sqrt(vx * vx + vy * vy);
                double depth = r - l - fmin(fmax(wx, wy), 0);
                if (depth <= 0) continue;  // outside the card: coverage is zero there
                points++;
                double mag = hypot(s[0], s[1]);
                CHECK(mag <= (pf ? fmin(k, 0.5) : k) * bevel + 1e-9, "shift %.3f > k*bevel", mag);
                if (depth >= bevel) { CHECK(mag == 0, "shift outside band"); }
                else {
                    // Inward: against the outward normal (v / l) * sign(q).
                    double nx = vx / l * signd(qx), ny = vy / l * signd(qy);
                    CHECK(s[0] * nx + s[1] * ny < 0, "shift not inward at %.2f,%.2f", qx, qy);
                }
                CHECK(br >= 0 && br <= brMax(pf) + 1e-9, "br range");
                // Jacobian away from the kinks of the field: the band edges and the inner-rect lines.
                double t = 1 - depth / bevel;
                if (depth < 0.02 || fabs(depth - bevel) < 0.02 || fabs(wx) < 0.02 || fabs(wy) < 0.02) continue;
                field(qx + h, qy, hx, hy, r, kp, sx1, &b2); field(qx - h, qy, hx, hy, r, kp, sx0, &b2);
                field(qx, qy + h, hx, hy, r, kp, sy1, &b2); field(qx, qy - h, hx, hy, r, kp, sy0, &b2);
                double j00 = 1 + (sx1[0] - sx0[0]) / (2 * h), j10 = (sx1[1] - sx0[1]) / (2 * h);
                double j01 = (sy1[0] - sy0[0]) / (2 * h), j11 = 1 + (sy1[1] - sy0[1]) / (2 * h);
                double det = j00 * j11 - j01 * j10;
                // Singular values of the sampling map: a texture detail grows 1 / sigma_min on screen.
                {
                    double a2 = j00 * j00 + j10 * j10, b2 = j00 * j01 + j10 * j11, c2 = j01 * j01 + j11 * j11;
                    double tr = a2 + c2, disc = sqrt(fmax((a2 - c2) * (a2 - c2) + 4 * b2 * b2, 0));
                    double smin = sqrt(fmax((tr - disc) / 2, 0)), smax = sqrt((tr + disc) / 2);
                    if (det > 0) CHECK(smax <= 1 + 1e-6, "sampling map minifies the texture (sigma_max %.4f)", smax);
                    if (det > 0 && smin > 0 && 1 / smin > maxMag[pf][ki / 2]) maxMag[pf][ki / 2] = 1 / smin;
                }
                double expect = 1, m = 1;
                if (depth < bevel) {
                    int corner = wx > 0 && wy > 0;
                    double A = fmin(k, 0.5);
                    m = pf ? 1 - 6 * A * t * (1 - t) : 1 - 2 * k * t;
                    expect = m * (corner ? 1 - mag / l : 1);
                }
                double err = fabs(det - expect);
                if (err > worstErr) worstErr = err;
                CHECK(err < 1e-4, "jacobian k=%.2f at %.2f,%.2f det=%.6f expect=%.6f", k, qx, qy, det, expect);
                if (pf) {
                    CHECK(det > 0.1, "profile 1 fold at k=%.2f det=%.6f", k, det);
                    if (det < minDet1) minDet1 = det;
                    if (depth < bevel && m < minM1[ki / 2]) minM1[ki / 2] = m;
                } else if (k <= 0.5) { CHECK(det > 0 || (k == 0.5 && det > -1e-6), "fold at k=%.2f det=%.6f", k, det); if (det < minDetLow) minDetLow = det; }
                else CHECK((det < 0) == (t > 1 / (2 * k)), "fold region k=%.2f t=%.3f det=%.4f", k, t, det);
            }
            // C1 at the inner band edge and monotone body radius, along a straight-edge normal.
            double sa[2], sb[2], bra, brb, last = -1;
            field(0, hy - (bevel - 1e-4), hx, hy, r, kp, sa, &bra);
            field(0, hy - (bevel + 1e-4), hx, hy, r, kp, sb, &brb);
            CHECK(fabs(sa[1]) < 1e-6 && fabs(sb[1]) == 0, "C1 at band edge");
            for (double dep = 0; dep < hy; dep += 0.25) {
                double sv[2], b; field(0, hy - dep, hx, hy, r, kp, sv, &b);
                CHECK(b >= last - 1e-12, "br not monotone"); last = b;
                if (dep <= (pf ? 4.0 : 0.25 * bevel)) CHECK(b < 1e-12, "br at rim");
                if (dep >= RAMPS[pf] * bevel && dep <= hy - 1) CHECK(fabs(b - brMax(pf)) < 1e-9, "br at body");
            }
        }
    }
    // Profile 2 (shade legibility): tags survive both decoders (fp16 too below 128) and carry the text
    // colour; the tone curve is monotone, C1 at its knee, identity between the knees, and bounded.
    for (double r0 = 42.5; r0 < 200; r0 += 0.37) for (int dark = 0; dark < 2; dark++) {
        double k = dark ? 1.0 : 0.3, r = tag(r0, k, 2);
        CHECK(r <= r0 && r0 - r <= 1.15 + 1e-9, "shade tag %.3f for %.2f", r, r0);
        double kf = decodeFill(r - floor(r)), kc = decodeClip((r + 0.5) - floor(r + 0.5));
        CHECK(fabs(kf - (4 + k)) < 1e-6 && fabs(kc - (4 + k)) < 1e-6, "shade tag decode r=%.3f fill=%.4f clip=%.4f", r, kf, kc);
        if (r + 0.5 < 128) {
            double v = r + 0.5, ulp = ldexp(1, (int) floor(log2(v)) - 10), h16 = round(v / ulp) * ulp;
            double kh = decodeClip(h16 - floor(h16));
            CHECK(kh >= 4 && ((kh - 4) > 0.65) == dark, "fp16 shade tag r=%.3f decodes %.3f", r, kh);
        }
    }
    for (int dark = 0; dark < 2; dark++) {
        double k = dark ? 1.0 : 0.3, cap = 0.55, lift = 0.45, prev = -1, maxSlopeJump = 0;
        for (double y = 0; y <= 1.0 + 1e-12; y += 1e-4) {
            double o = toneLuma(y, k, cap, lift);
            CHECK(o >= prev - 1e-12, "tone not monotone at %.4f", y);
            if (!dark) { CHECK(o <= cap + 1e-9, "tone above cap at %.3f", y); if (y <= cap - 0.12) CHECK(o == y, "tone moved %.3f", y); }
            else { CHECK(o >= lift - 1e-9, "tone below lift at %.3f", y); if (y >= lift + 0.12) CHECK(o == y, "tone moved %.3f", y); }
            prev = o;
        }
        double knee = dark ? lift + 0.12 : cap - 0.12, e = 1e-6;
        double sl = (toneLuma(knee, k, cap, lift) - toneLuma(knee - e, k, cap, lift)) / e;
        double sr = (toneLuma(knee + e, k, cap, lift) - toneLuma(knee, k, cap, lift)) / e;
        maxSlopeJump = fabs(sl - sr);
        CHECK(maxSlopeJump < 1e-4, "tone not C1 at the knee (%.5f vs %.5f)", sl, sr);
        printf("tone: %s text, white -> %.3f, black -> %.3f, mid 0.5 -> %.3f\n", dark ? "dark" : "light",
               toneLuma(1, k, cap, lift), toneLuma(0, k, cap, lift), toneLuma(0.5, k, cap, lift));
    }

    printf("field: %ld points, max |det - closed form| = %.2e, min det profile 0 (k <= 0.5) = %.3f, profile 1 (all k) = %.3f\n",
           points, worstErr, minDetLow, minDet1);
    printf("field: profile 1 min radial stretch by k:");
    for (unsigned i = 0; i < sizeof ks / sizeof ks[0]; i++) printf(" %.2f->%.3f", ks[i], minM1[i]);
    printf("\nfield: max texture magnification 1/sigma_min (no fold), profile 0 | profile 1:");
    for (unsigned i = 0; i < sizeof ks / sizeof ks[0]; i++) {
        if (ks[i] < 0.5) printf(" %.2f->%.2fx|%.2fx", ks[i], maxMag[0][i], maxMag[1][i]);
        else printf(" %.2f->fold|%.2fx", ks[i], maxMag[1][i]);
    }
    // The lockscreen default (kglens 0.40) magnifies no more than the established heads-up (sflens 0.30).
    CHECK(maxMag[1][2] <= maxMag[0][1] * 1.01, "profile 1 at 0.40 magnifies %.3fx > profile 0 at 0.30 %.3fx", maxMag[1][2], maxMag[0][1]);
    printf("\nfield: failures = %d\n", failures);
    return failures != 0;
}
