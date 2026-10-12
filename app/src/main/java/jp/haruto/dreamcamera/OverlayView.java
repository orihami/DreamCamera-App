package jp.haruto.dreamcamera;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/** プレビューの上に重ねる表示：三分割グリッド・フォーカス位置。 */
final class OverlayView extends View {
    private boolean grid;
    private float fx = -1, fy;
    private long focusAt;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    OverlayView(Context c) {
        super(c);
        line.setColor(0x66FFFFFF);
        line.setStrokeWidth(2);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4);
        ring.setColor(0xEEFFFFFF);
    }

    void setGrid(boolean g) { grid = g; invalidate(); }

    boolean grid() { return grid; }

    void showFocus(float x, float y) {
        fx = x; fy = y; focusAt = SystemClock.uptimeMillis();
        invalidate();
        postInvalidateDelayed(900);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (grid) for (int i = 1; i < 3; i++) {
            c.drawLine(w * i / 3f, 0, w * i / 3f, h, line);
            c.drawLine(0, h * i / 3f, w, h * i / 3f, line);
        }
        if (fx >= 0 && SystemClock.uptimeMillis() - focusAt < 800) c.drawCircle(fx, fy, 36 * getResources().getDisplayMetrics().density, ring);
    }
}
