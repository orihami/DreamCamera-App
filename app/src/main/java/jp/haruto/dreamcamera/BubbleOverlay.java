package jp.haruto.dreamcamera;

import android.graphics.*;
import java.util.Random;

/** Frutiger Aero 風のシャボン玉。位置は画像サイズに対する割合なので、プレビューと保存画像でほぼ同じ配置になる。 */
final class BubbleOverlay {
    private static final int[] RIM = {0x88FF7AD9, 0x8866E0FF, 0x88FFF27A, 0x887CFFB0, 0x88B48CFF, 0x88FF7AD9};

    static void draw(Canvas c, int w, int h, float amount, long seed) {
        int n = Math.round(amount / 100f * 12);
        if (n <= 0 || w <= 0 || h <= 0) return;
        Random rnd = new Random(seed);
        float m = Math.min(w, h);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG), spec = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < n; i++) {
            float rad = m * (0.025f + 0.13f * rnd.nextFloat() * rnd.nextFloat());
            // 主役を隠さないよう左右に寄せる
            float x = w * (rnd.nextBoolean() ? 0.03f + 0.3f * rnd.nextFloat() : 0.67f + 0.3f * rnd.nextFloat());
            float y = h * (0.04f + 0.92f * rnd.nextFloat());
            body.setShader(new RadialGradient(x, y, rad, new int[]{0x00FFFFFF, 0x12E0F7FF, 0x48FFFFFF, 0x00FFFFFF}, new float[]{0, 0.72f, 0.96f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, rad, body);
            SweepGradient sg = new SweepGradient(x, y, RIM, null);
            Matrix rot = new Matrix();
            rot.setRotate(rnd.nextFloat() * 360, x, y);
            sg.setLocalMatrix(rot);
            rim.setShader(sg);
            rim.setStrokeWidth(Math.max(1.5f, rad * 0.06f));
            c.drawCircle(x, y, rad * 0.97f, rim);
            float sx = x - rad * 0.38f, sy = y - rad * 0.42f;
            spec.setShader(new RadialGradient(sx, sy, rad * 0.28f, 0xCCFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.save();
            c.rotate(-35, sx, sy);
            c.drawOval(new RectF(sx - rad * 0.28f, sy - rad * 0.15f, sx + rad * 0.28f, sy + rad * 0.15f), spec);
            c.restore();
            float bx = x + rad * 0.45f, by = y + rad * 0.5f;
            spec.setShader(new RadialGradient(bx, by, rad * 0.12f, 0x88FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(bx, by, rad * 0.12f, spec);
        }
    }
}
