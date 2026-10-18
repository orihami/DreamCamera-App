package jp.haruto.dreamcamera;

import android.graphics.*;
import java.util.List;
import java.util.Random;

/**
 * コメディアン（バナナをダクトテープで壁に貼った作品）ごっこ。
 * バナナ＋テープ、または真ん中に写っている物にテープだけを、プレビューと保存画像の両方に同じ形で描く。
 * 位置・大きさは画像に対する割合で持つので、プレビューでも保存でも同じ所に乗る。
 */
final class Comedian {
    static final int OFF = 0, BANANA = 1, TAPE = 2;
    static final String[] LABELS = {"ふつう", "🍌 バナナを貼る", "真ん中に貼る"};

    /** 今の状態。bx,by＝バナナの中心、blen＝バナナの長さ（幅に対する割合）、box＝テープを貼る物の範囲。 */
    static final class State {
        int mode;
        float bx = 0.5f, by = 0.5f, blen = 0.5f;
        float tx = 0.5f, ty = 0.5f;
        final RectF box = new RectF(0.35f, 0.38f, 0.65f, 0.62f);

        State copy() {
            State s = new State();
            s.mode = mode; s.bx = bx; s.by = by; s.blen = blen; s.tx = tx; s.ty = ty; s.box.set(box);
            return s;
        }
    }

    /** 画像（またはプレビュー）全体に描く。mirror＝前面カメラの保存画像用（プレビューは鏡像なので左右を戻す）。 */
    static void draw(Canvas c, State s, int w, int h, boolean mirror) {
        if (s.mode == OFF) return;
        c.save();
        if (mirror) c.scale(-1, 1, w / 2f, h / 2f);
        if (s.mode == BANANA) {
            float L = s.blen * w, x = s.bx * w, y = s.by * h;
            banana(c, x, y, L, -12);
            float mx = x, my = y - 0.08f * L;
            tape(c, mx, my, 0.44f * L, 0.14f * L, 70, true, 11);
        } else {
            float bw = s.box.width() * w, bh = s.box.height() * h, cx = s.box.centerX() * w, cy = s.box.centerY() * h;
            float shortSide = Math.min(bw, bh), longSide = Math.max(bw, bh);
            // 横長の物には縦向き、縦長の物には横向きに、短い方向をまたいで壁まで届くように貼る
            float angle = bw >= bh ? 72 : -18;
            float len = shortSide * 1.45f + 0.04f * w;
            float wid = Math.max(0.05f * w, Math.min(longSide * 0.2f, shortSide * 0.6f));
            tape(c, cx, cy, len, wid, angle, true, 23);
        }
        c.restore();
    }

    /**
     * 見つかった物（w×h の画像内の四角）から、target を含む一番小さい物を選ぶ。無ければ target に一番近い物。
     * それも無ければ target の周りの四角。画面ほぼ全体を覆う物（壁や机そのもの）は除く。
     */
    static RectF pick(List<Rect> boxes, int w, int h, float tx, float ty) {
        RectF best = null, near = null;
        float bestArea = Float.MAX_VALUE, nearDist = 0.3f;
        for (Rect r : boxes) {
            RectF f = new RectF(r.left / (float) w, r.top / (float) h, r.right / (float) w, r.bottom / (float) h);
            f.intersect(0, 0, 1, 1);
            float area = f.width() * f.height();
            if (area > 0.85f || area < 0.002f) continue;
            if (f.contains(tx, ty) && area < bestArea) { best = f; bestArea = area; }
            float d = (float) Math.hypot(f.centerX() - tx, f.centerY() - ty);
            if (d < nearDist) { near = f; nearDist = d; }
        }
        if (best != null) return best;
        if (near != null) return near;
        float hw = 0.15f, hh = 0.15f * w / h;
        return new RectF(tx - hw, ty - hh, tx + hw, ty + hh);
    }

    // ---------- バナナ ----------

    static void banana(Canvas c, float x, float y, float L, float angle) {
        final int n = 48;
        float B = 0.17f * L, T = 0.125f * L;
        float[] tx = new float[n + 1], ty = new float[n + 1], bx = new float[n + 1], by = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            float t = -1 + 2f * i / n;
            // 中心線：両端が下がる弓なり。左がおしり（黒い先）、右が軸
            float px = t * L / 2, py = B * t * t - B * 0.5f;
            float dx = L / 2, dy = 2 * B * t, k = (float) Math.hypot(dx, dy), nx = dy / k, ny = -dx / k;
            float prof = (float) Math.pow(Math.max(0, 1 - t * t), 0.55);
            float ht = T * prof * (t > 0 ? 1 - 0.25f * t : 1), hb = ht * 0.85f;
            tx[i] = px + nx * ht; ty[i] = py + ny * ht;
            bx[i] = px - nx * hb; by[i] = py - ny * hb;
        }
        Path body = new Path();
        body.moveTo(tx[0], ty[0]);
        for (int i = 1; i <= n; i++) body.lineTo(tx[i], ty[i]);
        for (int i = n; i >= 0; i--) body.lineTo(bx[i], by[i]);
        body.close();

        c.save();
        c.translate(x, y);
        c.rotate(angle);

        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(0x66000000);
        sh.setMaskFilter(new BlurMaskFilter(Math.max(1, 0.035f * L), BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0.025f * L, 0.045f * L);
        c.drawPath(body, sh);
        c.restore();

        // 軸：右端から弓の向きのまま短く伸びる
        float sx = L / 2, sy = B * 0.5f, dl = (float) Math.hypot(L / 2, 2 * B), ux = L / 2 / dl, uy = 2 * B / dl, vx = -uy, vy = ux;
        float s0 = -0.05f * L, s1 = 0.085f * L, w0 = 0.032f * L, w1 = 0.022f * L;
        Path stem = new Path();
        stem.moveTo(sx + ux * s0 + vx * w0, sy + uy * s0 + vy * w0);
        stem.lineTo(sx + ux * s1 + vx * w1, sy + uy * s1 + vy * w1);
        stem.lineTo(sx + ux * s1 - vx * w1, sy + uy * s1 - vy * w1);
        stem.lineTo(sx + ux * s0 - vx * w0, sy + uy * s0 - vy * w0);
        stem.close();
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setShader(new LinearGradient(sx + ux * s0, sy + uy * s0, sx + ux * s1, sy + uy * s1, 0xFFA8A040, 0xFF6E5A2A, Shader.TileMode.CLAMP));
        c.drawPath(stem, sp);
        Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
        cap.setColor(0xFF3E2F17);
        c.drawOval(sx + ux * s1 - w1 * 1.1f, sy + uy * s1 - w1 * 1.1f, sx + ux * s1 + w1 * 1.1f, sy + uy * s1 + w1 * 1.1f, cap);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new LinearGradient(0, -B - T, 0, T * 0.6f, new int[]{0xFFFFEC8A, 0xFFF6D23A, 0xFFE2AE24, 0xFFB98418}, new float[]{0, 0.35f, 0.75f, 1}, Shader.TileMode.CLAMP));
        c.drawPath(body, fill);

        c.save();
        c.clipPath(body);
        // 軸側は少し青っぽい
        Paint green = new Paint(Paint.ANTI_ALIAS_FLAG);
        green.setShader(new LinearGradient(L * 0.25f, 0, L * 0.5f, 0, 0x00A0B040, 0x6690A030, Shader.TileMode.CLAMP));
        c.drawRect(-L, -L, L, L, green);
        // 稜線
        Paint ridge = new Paint(Paint.ANTI_ALIAS_FLAG);
        ridge.setStyle(Paint.Style.STROKE);
        ridge.setStrokeWidth(Math.max(1, 0.008f * L));
        ridge.setColor(0x40A06A10);
        for (float off : new float[]{0.4f, -0.3f}) {
            Path r = new Path();
            for (int i = 2; i <= n - 2; i++) {
                float px = (tx[i] + bx[i]) / 2 + (tx[i] - bx[i]) / 2 * off, py = (ty[i] + by[i]) / 2 + (ty[i] - by[i]) / 2 * off;
                if (i == 2) r.moveTo(px, py); else r.lineTo(px, py);
            }
            c.drawPath(r, ridge);
        }
        // つや
        Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG);
        hi.setStyle(Paint.Style.STROKE);
        hi.setStrokeCap(Paint.Cap.ROUND);
        hi.setStrokeWidth(0.03f * L);
        hi.setColor(0x55FFFFFF);
        hi.setMaskFilter(new BlurMaskFilter(Math.max(1, 0.012f * L), BlurMaskFilter.Blur.NORMAL));
        Path g = new Path();
        for (int i = n / 5; i <= n * 4 / 5; i++) {
            float px = (tx[i] + bx[i]) / 2 + (tx[i] - bx[i]) / 2 * 0.62f, py = (ty[i] + by[i]) / 2 + (ty[i] - by[i]) / 2 * 0.62f;
            if (i == n / 5) g.moveTo(px, py); else g.lineTo(px, py);
        }
        c.drawPath(g, hi);
        // 茶色の点（毎回同じ位置）
        Random rnd = new Random(7);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int k = 0; k < 14; k++) {
            int i = 6 + rnd.nextInt(n - 12);
            float a = rnd.nextFloat() * 1.4f - 0.7f;
            float px = (tx[i] + bx[i]) / 2 + (tx[i] - bx[i]) / 2 * a, py = (ty[i] + by[i]) / 2 + (ty[i] - by[i]) / 2 * a;
            dot.setColor(rnd.nextBoolean() ? 0x55704010 : 0x33603008);
            c.drawCircle(px, py, (0.004f + rnd.nextFloat() * 0.006f) * L, dot);
        }
        c.restore();

        // おしりの黒い先
        Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
        tip.setColor(0xFF3A2812);
        c.drawOval(tx[0] - 0.03f * L, (ty[0] + by[0]) / 2 - 0.022f * L, tx[0] + 0.022f * L, (ty[0] + by[0]) / 2 + 0.022f * L, tip);
        c.restore();
    }

    // ---------- ダクトテープ ----------

    /** (x,y) を中心に、長さ len・幅 wid のテープを angle 度で貼る。両端はちぎった形。bulge＝下の物のふくらみの光。 */
    static void tape(Canvas c, float x, float y, float len, float wid, float angle, boolean bulge, long seed) {
        Random rnd = new Random(seed);
        float hl = len / 2, hw = wid / 2;
        Path p = new Path();
        p.moveTo(-hl, -hw);
        p.lineTo(hl, -hw);
        int teeth = 7;
        for (int i = 1; i < teeth; i++) p.lineTo(hl + (rnd.nextFloat() - 0.5f) * 0.22f * wid, -hw + wid * i / teeth);
        p.lineTo(hl + (rnd.nextFloat() - 0.5f) * 0.1f * wid, hw);
        p.lineTo(-hl, hw);
        for (int i = teeth - 1; i > 0; i--) p.lineTo(-hl + (rnd.nextFloat() - 0.5f) * 0.22f * wid, -hw + wid * i / teeth);
        p.close();

        c.save();
        c.translate(x, y);
        c.rotate(angle);

        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setColor(0x55000000);
        sh.setMaskFilter(new BlurMaskFilter(Math.max(1, 0.12f * wid), BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0.06f * wid, 0.1f * wid);
        c.drawPath(p, sh);
        c.restore();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new LinearGradient(0, -hw, 0, hw, new int[]{0xFFD3D6D9, 0xFFB4B8BC, 0xFFA3A7AC, 0xFFBBBFC3}, new float[]{0, 0.4f, 0.8f, 1}, Shader.TileMode.CLAMP));
        c.drawPath(p, fill);

        c.save();
        c.clipPath(p);
        // 布目
        Paint weave = new Paint(Paint.ANTI_ALIAS_FLAG);
        weave.setStrokeWidth(Math.max(1, wid / 90));
        weave.setColor(0x10000000);
        for (int i = 1; i < 20; i++) { float yy = -hw + wid * i / 20f; c.drawLine(-hl - wid, yy, hl + wid, yy, weave); }
        weave.setColor(0x07000000);
        for (float xx = -hl - wid; xx < hl + wid; xx += wid / 14f) c.drawLine(xx, -hw, xx, hw, weave);
        // 縁の光とちぎれた端の白っぽさ
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStrokeWidth(Math.max(1, wid / 30));
        edge.setColor(0x66FFFFFF);
        c.drawLine(-hl - wid, -hw + wid / 50, hl + wid, -hw + wid / 50, edge);
        Paint torn = new Paint(Paint.ANTI_ALIAS_FLAG);
        torn.setStyle(Paint.Style.STROKE);
        torn.setStrokeWidth(Math.max(1, wid / 25));
        torn.setColor(0x44FFFFFF);
        c.drawPath(p, torn);
        if (bulge) {
            // 真ん中が盛り上がって光り、物の縁でうっすら影
            Paint b = new Paint(Paint.ANTI_ALIAS_FLAG);
            b.setShader(new LinearGradient(-hl, 0, hl, 0, new int[]{0x00000000, 0x14000000, 0x30FFFFFF, 0x14000000, 0x00000000}, new float[]{0, 0.28f, 0.5f, 0.72f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(-hl, -hw, hl, hw, b);
        }
        c.restore();
        c.restore();
    }
}
