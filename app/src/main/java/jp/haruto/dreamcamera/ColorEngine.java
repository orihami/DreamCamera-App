package jp.haruto.dreamcamera;

import android.graphics.*;

/**
 * 1画素の色変換（空間的な処理を含まないもの）。
 * これを 3D LUT に焼いて、プレビュー（GPU）と保存画像（CPU）の両方で同じ結果を出す。
 * 順序: ホワイトバランス・露光量 → かすみ → ハイライト/シャドウ/白/黒 → コントラスト → フェード
 *       → 彩度 → カラーミキサー → カラーグレーディング
 */
final class ColorEngine {
    static final int N = 33;
    private static final float[] CENTERS = {0, 30, 60, 120, 180, 240, 270, 300, 360};

    private final float rG, gG, bG, hl, sh, wh, bk, con, fade, sat, vib, hueRot, dehaze, pivot, width;
    private final float[] mixH = new float[8], mixS = new float[8], mixL = new float[8];
    private final float[][] tint = new float[3][3];
    private final float[] zoneLum = new float[3];
    private final boolean mix, grade;

    ColorEngine(Preset p) {
        float t = p.get("temp") / 100f, ti = p.get("tint") / 100f, e = (float) Math.pow(2, p.get("exposure") / 50f);
        rG = e * pw(1 + 0.25f * t) * pw(1 + 0.06f * ti) * pw(1 + p.get("red") / 100f);
        gG = e * pw(1 - 0.2f * ti) * pw(1 + p.get("green") / 100f);
        bG = e * pw(1 - 0.25f * t) * pw(1 + 0.06f * ti) * pw(1 + p.get("blue") / 100f);
        hl = p.get("highlights") / 100f; sh = p.get("shadows") / 100f; wh = p.get("whites") / 100f; bk = p.get("blacks") / 100f;
        con = p.get("contrast") / 100f; fade = p.get("fade") / 100f; sat = p.get("saturation") / 100f; vib = p.get("vibrance") / 100f;
        hueRot = p.get("hue") / 100f; dehaze = p.get("dehaze") / 100f;
        boolean m = hueRot != 0;
        for (int k = 0; k < 8; k++) {
            String c = Param.MIX_IDS[k];
            mixH[k] = p.get("mix_h_" + c) / 100f; mixS[k] = p.get("mix_s_" + c) / 100f; mixL[k] = p.get("mix_l_" + c) / 100f;
            m |= mixH[k] != 0 || mixS[k] != 0 || mixL[k] != 0;
        }
        mix = m;
        boolean g = false;
        float[] rgb = new float[3];
        for (int z = 0; z < 3; z++) {
            String id = Param.ZONES[z];
            float s = p.get(id + "_sat") / 100f;
            hsl2rgb(p.get(id + "_hue"), 1, 0.5f, rgb);
            float l = luma(rgb[0], rgb[1], rgb[2]);
            for (int ch = 0; ch < 3; ch++) tint[z][ch] = (rgb[ch] - l) * s * 0.2f;
            zoneLum[z] = p.get(id + "_lum") / 100f * 0.2f;
            g |= s > 0 || zoneLum[z] != 0;
        }
        grade = g;
        pivot = 0.5f - 0.25f * p.get("grade_balance") / 100f;
        width = 0.1f + 0.4f * p.get("grade_blend") / 100f;
    }

    /** c: sRGB 0..1 を上書きする。 */
    void map(float[] c) {
        float r = gam(lin(c[0]) * rG), g = gam(lin(c[1]) * gG), b = gam(lin(c[2]) * bG);
        if (dehaze > 0) {
            float a = 0.12f * dehaze;
            r = (r - a) / (1 - a); g = (g - a) / (1 - a); b = (b - a) / (1 - a);
        } else if (dehaze < 0) {
            float a = -dehaze * 0.3f;
            r += (0.85f - r) * a; g += (0.85f - g) * a; b += (0.85f - b) * a;
        }

        // トーン（輝度で計算して色に戻す）
        float L = luma(r, g, b), T = L, x;
        if (sh != 0) { x = cl(T); T += sh * (sh > 0 ? 2.5f : 0.8f) * x * (1 - x) * (1 - x) * (1 - x); }
        if (hl != 0) { x = cl(T); T += hl * (hl < 0 ? 1.4f : 0.8f) * (1 - x) * x * x * x; }
        float wp = wh >= 0 ? 1 - 0.35f * wh : 1 - 0.3f * wh, bp = -0.1f * bk;
        T = (T - bp) / (wp - bp);
        if (con > 0) { x = cl(T); T += (x * x * (3 - 2 * x) - x) * con; }
        else if (con < 0) T += (0.5f + (T - 0.5f) * 0.5f - T) * -con;
        T = fade * 0.2f + T * (1 - fade * 0.25f);
        float d = T - L;
        if (L > 1e-4f) {
            float f = T / L;
            r = 0.5f * (r + d + r * f); g = 0.5f * (g + d + g * f); b = 0.5f * (b + d + b * f);
        } else { r += d; g += d; b += d; }

        // 彩度・自然な彩度
        L = luma(r, g, b);
        float s = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        float vb = vib > 0 ? vib * (1 - cl(s * 1.4f)) : vib;
        float k = (1 + sat) * (1 + vb) * (1 + Math.max(0, dehaze) * 0.2f);
        r = L + (r - L) * k; g = L + (g - L) * k; b = L + (b - L) * k;

        // カラーミキサー（HSL）
        if (mix) {
            c[0] = cl(r); c[1] = cl(g); c[2] = cl(b);
            rgb2hsl(c);
            float h = c[0], hs = c[1], hlum = c[2];
            int i = 0;
            while (i < 7 && h >= CENTERS[i + 1]) i++;
            float tt = (h - CENTERS[i]) / (CENTERS[i + 1] - CENTERS[i]);
            tt = tt * tt * (3 - 2 * tt);
            int j = (i + 1) % 8;
            float wi = 1 - tt, wj = tt;
            float dh = (wi * mixH[i] + wj * mixH[j]) * 30 + hueRot * 30;
            float ds = wi * mixS[i] + wj * mixS[j];
            float dl = wi * mixL[i] + wj * mixL[j];
            float chroma = Math.min(1, hs * 2);
            h = (h + dh) % 360; if (h < 0) h += 360;
            hlum = cl(hlum + dl * 0.4f * chroma * (dl > 0 ? 1 - hlum : hlum));
            hs = cl(hs * (1 + ds));
            hsl2rgb(h, hs, hlum, c);
            r = c[0]; g = c[1]; b = c[2];
        }

        // カラーグレーディング（シャドウ・中間調・ハイライト）
        if (grade) {
            x = cl(luma(r, g, b));
            float ws = 1 - smooth(pivot - width, pivot + width, x), wl = 1 - ws;
            float w0 = ws * ws, w1 = 2 * ws * wl, w2 = wl * wl;
            r += w0 * (tint[0][0] + zoneLum[0]) + w1 * (tint[1][0] + zoneLum[1]) + w2 * (tint[2][0] + zoneLum[2]);
            g += w0 * (tint[0][1] + zoneLum[0]) + w1 * (tint[1][1] + zoneLum[1]) + w2 * (tint[2][1] + zoneLum[2]);
            b += w0 * (tint[0][2] + zoneLum[0]) + w1 * (tint[1][2] + zoneLum[1]) + w2 * (tint[2][2] + zoneLum[2]);
        }
        c[0] = cl(r); c[1] = cl(g); c[2] = cl(b);
    }

    /** [(b*N+g)*N+r]*3 の順に RGB を並べた LUT。 */
    float[] lut() {
        float[] out = new float[N * N * N * 3], c = new float[3];
        int o = 0;
        for (int b = 0; b < N; b++)
            for (int g = 0; g < N; g++)
                for (int r = 0; r < N; r++) {
                    c[0] = r / (N - 1f); c[1] = g / (N - 1f); c[2] = b / (N - 1f);
                    map(c);
                    out[o++] = c[0]; out[o++] = c[1]; out[o++] = c[2];
                }
        return out;
    }

    /** GPU 用。横 N*N（青のスライス×赤）、縦 N（緑）。 */
    static Bitmap lutBitmap(float[] lut) {
        int[] px = new int[N * N * N];
        for (int b = 0; b < N; b++)
            for (int g = 0; g < N; g++)
                for (int r = 0; r < N; r++) {
                    int i = ((b * N + g) * N + r) * 3;
                    px[g * N * N + b * N + r] = Color.rgb(Math.round(lut[i] * 255), Math.round(lut[i + 1] * 255), Math.round(lut[i + 2] * 255));
                }
        Bitmap bm = Bitmap.createBitmap(N * N, N, Bitmap.Config.ARGB_8888);
        bm.setPixels(px, 0, N * N, 0, 0, N * N, N);
        return bm;
    }

    static float luma(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }
    static float cl(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    static float smooth(float a, float b, float x) { float t = cl((x - a) / (b - a)); return t * t * (3 - 2 * t); }
    private static float pw(float x) { return (float) Math.pow(Math.max(0, x), 2.2); }
    private static float lin(float v) { return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4); }
    private static float gam(float v) { return v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1 / 2.4) - 0.055f; }

    /** c = {r,g,b} → {h(0..360), s, l} */
    static void rgb2hsl(float[] c) {
        float r = c[0], g = c[1], b = c[2], mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), l = (mx + mn) / 2, h = 0, s = 0, d = mx - mn;
        if (d > 1e-6f) {
            s = l > 0.5f ? d / (2 - mx - mn) : d / (mx + mn);
            if (mx == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (mx == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h *= 60;
        }
        c[0] = h; c[1] = s; c[2] = l;
    }

    static void hsl2rgb(float h, float s, float l, float[] out) {
        if (s <= 0) { out[0] = out[1] = out[2] = l; return; }
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s, p = 2 * l - q, hh = h / 360f;
        out[0] = hue(p, q, hh + 1 / 3f); out[1] = hue(p, q, hh); out[2] = hue(p, q, hh - 1 / 3f);
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1 / 6f) return p + (q - p) * 6 * t;
        if (t < 0.5f) return q;
        if (t < 2 / 3f) return p + (q - p) * (2 / 3f - t) * 6;
        return p;
    }
}
