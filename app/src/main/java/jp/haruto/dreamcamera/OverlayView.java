package jp.haruto.dreamcamera;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.util.Locale;

/** プレビューの上に重ねる表示：三分割グリッド・フォーカス位置・ズーム倍率。 */
final class OverlayView extends View {
    private boolean grid;
    private float fx = -1, fy, zoom;
    private long focusAt, zoomAt;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), pill = new Paint(Paint.ANTI_ALIAS_FLAG);

    OverlayView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        line.setColor(0x66FFFFFF);
        line.setStrokeWidth(Math.max(1, d));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * d);
        ring.setColor(0xEEFFFFFF);
        text.setColor(Color.WHITE);
        text.setTextSize(15 * d);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setColor(0x99000000);
    }

    void setGrid(boolean g) { grid = g; invalidate(); }

    boolean grid() { return grid; }

    void showFocus(float x, float y) {
        fx = x; fy = y; focusAt = SystemClock.uptimeMillis();
        invalidate();
        postInvalidateDelayed(900);
    }

    void showZoom(float z) {
        zoom = z; zoomAt = SystemClock.uptimeMillis();
        invalidate();
        postInvalidateDelayed(1300);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        long now = SystemClock.uptimeMillis();
        if (grid) for (int i = 1; i < 3; i++) {
            c.drawLine(w * i / 3f, 0, w * i / 3f, h, line);
            c.drawLine(0, h * i / 3f, w, h * i / 3f, line);
        }
        if (fx >= 0 && now - focusAt < 800) c.drawCircle(fx, fy, 34 * d, ring);
        if (zoom > 0 && now - zoomAt < 1200) {
            String s = String.format(Locale.JAPAN, "%.1f×", zoom);
            c.drawRoundRect(w / 2f - 34 * d, 12 * d, w / 2f + 34 * d, 42 * d, 15 * d, 15 * d, pill);
            c.drawText(s, w / 2f, 33 * d, text);
        }
    }
}
