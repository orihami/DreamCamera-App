package jp.haruto.dreamcamera;

import android.graphics.*;

/** プリセット一覧の小さな見本。カメラの今の映像（無ければ空と芝生の絵）に色だけ当てる。 */
final class Thumbs {
    static Bitmap square(Bitmap src, int size) {
        int w = src.getWidth(), h = src.getHeight(), m = Math.min(w, h);
        Bitmap crop = Bitmap.createBitmap(src, (w - m) / 2, (h - m) / 2, m, m);
        Bitmap out = Bitmap.createScaledBitmap(crop, size, size, true);
        if (crop != src && crop != out) crop.recycle();
        return out;
    }

    static Bitmap render(Bitmap sq, Preset p) {
        int w = sq.getWidth(), h = sq.getHeight();
        int[] px = new int[w * h];
        sq.getPixels(px, 0, w, 0, 0, w, h);
        ColorEngine e = new ColorEngine(p);
        float vig = p.get("vignette") / 100f * p.k();
        float[] c = new float[3];
        for (int y = 0, o = 0; y < h; y++) {
            float dy = ((y + 0.5f) / h - 0.5f) * 2;
            for (int x = 0; x < w; x++, o++) {
                int q = px[o];
                c[0] = ((q >> 16) & 255) / 255f; c[1] = ((q >> 8) & 255) / 255f; c[2] = (q & 255) / 255f;
                e.map(c);
                if (vig != 0) {
                    float dx = ((x + 0.5f) / w - 0.5f) * 2, m = ColorEngine.smooth(0.15f, 1f, 0.5f * (dx * dx + dy * dy));
                    for (int ch = 0; ch < 3; ch++) c[ch] = vig > 0 ? c[ch] * (1 - vig * 0.6f * m) : c[ch] + (1 - c[ch]) * -vig * 0.6f * m;
                }
                px[o] = Color.rgb(Math.round(ColorEngine.cl(c[0]) * 255), Math.round(ColorEngine.cl(c[1]) * 255), Math.round(ColorEngine.cl(c[2]) * 255));
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** カメラが使えない時の見本：青空・雲・芝生。 */
    static Bitmap sample(int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, s * 0.68f, 0xFF5A96D7, 0xFFD2E6F5, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s * 0.68f, p);
        p.setShader(new RadialGradient(s * 0.68f, s * 0.42f, s * 0.3f, 0xFFF5F8FA, 0x00F5F8FA, Shader.TileMode.CLAMP));
        c.drawCircle(s * 0.68f, s * 0.42f, s * 0.3f, p);
        p.setShader(new LinearGradient(0, s * 0.68f, 0, s, 0xFF508C28, 0xFF286414, Shader.TileMode.CLAMP));
        c.drawRect(0, s * 0.68f, s, s, p);
        return b;
    }
}
