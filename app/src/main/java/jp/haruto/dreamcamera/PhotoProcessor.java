package jp.haruto.dreamcamera;

import android.graphics.*;
import java.util.stream.IntStream;

/** 保存用のフル解像度処理。色は ColorEngine の LUT、その後に明瞭度・ソフト・光のにじみ・周辺光量・粒子。 */
public final class PhotoProcessor {
    public static Bitmap apply(Bitmap src, Preset p, long seed) {
        Bitmap out = src.isMutable() && src.getConfig() == Bitmap.Config.ARGB_8888 ? src : src.copy(Bitmap.Config.ARGB_8888, true);
        final int w = out.getWidth(), h = out.getHeight(), N = ColorEngine.N;
        final int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);

        final float[] lut = new ColorEngine(p).lut();
        final int[] I = new int[256];
        final float[] F = new float[256];
        for (int v = 0; v < 256; v++) {
            float t = v * (N - 1) / 255f;
            I[v] = Math.min((int) t, N - 2);
            F[v] = t - I[v];
        }
        final int dR = 3, dG = N * 3, dB = N * N * 3;
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0, o = y * w; x < w; x++, o++) {
                int q = px[o], r = (q >> 16) & 255, g = (q >> 8) & 255, b = q & 255;
                int i = ((I[b] * N + I[g]) * N + I[r]) * 3;
                float fr = F[r], fg = F[g], fb = F[b];
                px[o] = 0xFF000000 | (to8(tri(lut, i, dR, dG, dB, fr, fg, fb)) << 16) | (to8(tri(lut, i + 1, dR, dG, dB, fr, fg, fb)) << 8) | to8(tri(lut, i + 2, dR, dG, dB, fr, fg, fb));
            }
        });
        out.setPixels(px, 0, w, 0, 0, w, h);

        final float k = p.k(), clar = p.get("clarity") / 100f * k, soft = p.get("blur") / 100f * k, glow = p.get("glow") / 100f * k, vig = p.get("vignette") / 100f * k, grain = p.get("grain") / 100f * k;
        final Small cs = clar != 0 ? Small.of(out, 160, 2, 0) : null;
        final Small ss = soft > 0 ? Small.of(out, 480, 1, 0) : null;
        final Small gs = glow > 0 ? Small.of(out, 128, 2, 0.6f) : null;
        if (cs != null || ss != null || gs != null || vig != 0 || grain > 0) {
            final int s32 = (int) seed;
            IntStream.range(0, h).parallel().forEach(y -> {
                float[] t = new float[3];
                float v = (y + 0.5f) / h, dy = (v - 0.5f) * 2;
                for (int x = 0, o = y * w; x < w; x++, o++) {
                    int q = px[o];
                    float r = ((q >> 16) & 255) / 255f, g = ((q >> 8) & 255) / 255f, b = (q & 255) / 255f, u = (x + 0.5f) / w;
                    if (cs != null) {
                        cs.sample(u, v, t);
                        float lc = ColorEngine.luma(r, g, b), mid = 1 - (2 * lc - 1) * (2 * lc - 1);
                        float d = (lc - ColorEngine.luma(t[0], t[1], t[2])) * clar * 1.5f * Math.max(0, mid);
                        r += d; g += d; b += d;
                    }
                    if (ss != null) {
                        ss.sample(u, v, t);
                        float a = soft * 0.5f;
                        r += (t[0] - r) * a; g += (t[1] - g) * a; b += (t[2] - b) * a;
                    }
                    if (gs != null) {
                        gs.sample(u, v, t);
                        float kg = glow * 1.1f;
                        r = screen(r, t[0] * kg); g = screen(g, t[1] * kg); b = screen(b, t[2] * kg);
                    }
                    if (vig != 0) {
                        float dx = (u - 0.5f) * 2, m = ColorEngine.smooth(0.15f, 1f, 0.5f * (dx * dx + dy * dy));
                        if (vig > 0) { float f = 1 - vig * 0.6f * m; r *= f; g *= f; b *= f; }
                        else { float a = -vig * 0.6f * m; r += (1 - r) * a; g += (1 - g) * a; b += (1 - b) * a; }
                    }
                    if (grain > 0) {
                        float n = (hash(x, y, s32) - 0.5f) * grain * 0.22f;
                        r += n; g += n; b += n;
                    }
                    px[o] = 0xFF000000 | (to8(r) << 16) | (to8(g) << 8) | to8(b);
                }
            });
            out.setPixels(px, 0, w, 0, 0, w, h);
        }
        return out;
    }

    private static float tri(float[] l, int i, int dR, int dG, int dB, float fr, float fg, float fb) {
        float c00 = l[i] + (l[i + dR] - l[i]) * fr;
        float c10 = l[i + dG] + (l[i + dG + dR] - l[i + dG]) * fr;
        float c01 = l[i + dB] + (l[i + dB + dR] - l[i + dB]) * fr;
        float c11 = l[i + dB + dG] + (l[i + dB + dG + dR] - l[i + dB + dG]) * fr;
        float c0 = c00 + (c10 - c00) * fg, c1 = c01 + (c11 - c01) * fg;
        return c0 + (c1 - c0) * fb;
    }

    private static float screen(float a, float b) { a = ColorEngine.cl(a); b = ColorEngine.cl(b); return 1 - (1 - a) * (1 - b); }

    private static int to8(float v) { return v <= 0 ? 0 : v >= 1 ? 255 : (int) (v * 255 + 0.5f); }

    private static float hash(int x, int y, int s) {
        int n = x * 374761393 + y * 668265263 + s * 1274126177;
        n = (n ^ (n >>> 13)) * 1274126177;
        n ^= n >>> 16;
        return (n & 0xFFFFFF) / (float) 0x1000000;
    }

    /** 縮小してぼかした画像（明瞭度・ソフト・光のにじみ用）。threshold>0 なら明るい部分だけ残す。 */
    static final class Small {
        final int w, h;
        final float[] d;

        Small(int w, int h, float[] d) { this.w = w; this.h = h; this.d = d; }

        static Small of(Bitmap full, int maxDim, int radius, float threshold) {
            Bitmap s = full;
            while (Math.max(s.getWidth(), s.getHeight()) > maxDim * 2) {
                Bitmap n = Bitmap.createScaledBitmap(s, Math.max(1, s.getWidth() / 2), Math.max(1, s.getHeight() / 2), true);
                if (s != full) s.recycle();
                s = n;
            }
            float k = (float) maxDim / Math.max(s.getWidth(), s.getHeight());
            if (k < 1) {
                Bitmap n = Bitmap.createScaledBitmap(s, Math.max(1, Math.round(s.getWidth() * k)), Math.max(1, Math.round(s.getHeight() * k)), true);
                if (s != full) s.recycle();
                s = n;
            }
            int w = s.getWidth(), h = s.getHeight();
            int[] p = new int[w * h];
            s.getPixels(p, 0, w, 0, 0, w, h);
            if (s != full) s.recycle();
            float[] d = new float[w * h * 3];
            for (int i = 0; i < p.length; i++) {
                for (int ch = 0; ch < 3; ch++) {
                    float v = ((p[i] >> (16 - ch * 8)) & 255) / 255f;
                    if (threshold > 0) v = Math.max(0, (v - threshold) / (1 - threshold));
                    d[i * 3 + ch] = v;
                }
            }
            for (int pass = 0; pass < 2; pass++) box(d, w, h, radius);
            return new Small(w, h, d);
        }

        private static void box(float[] d, int w, int h, int r) {
            float[] t = new float[d.length];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    for (int ch = 0; ch < 3; ch++) {
                        float s = 0;
                        for (int k = -r; k <= r; k++) s += d[(y * w + Math.max(0, Math.min(w - 1, x + k))) * 3 + ch];
                        t[(y * w + x) * 3 + ch] = s / (2 * r + 1);
                    }
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    for (int ch = 0; ch < 3; ch++) {
                        float s = 0;
                        for (int k = -r; k <= r; k++) s += t[(Math.max(0, Math.min(h - 1, y + k)) * w + x) * 3 + ch];
                        d[(y * w + x) * 3 + ch] = s / (2 * r + 1);
                    }
        }

        void sample(float u, float v, float[] o) {
            float fx = u * w - 0.5f, fy = v * h - 0.5f;
            int x0 = Math.max(0, Math.min(w - 1, (int) Math.floor(fx))), y0 = Math.max(0, Math.min(h - 1, (int) Math.floor(fy)));
            int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
            float ax = ColorEngine.cl(fx - x0), ay = ColorEngine.cl(fy - y0);
            for (int ch = 0; ch < 3; ch++) {
                float a = d[(y0 * w + x0) * 3 + ch], b = d[(y0 * w + x1) * 3 + ch], c = d[(y1 * w + x0) * 3 + ch], e = d[(y1 * w + x1) * 3 + ch];
                float top = a + (b - a) * ax, bot = c + (e - c) * ax;
                o[ch] = top + (bot - top) * ay;
            }
        }
    }
}
