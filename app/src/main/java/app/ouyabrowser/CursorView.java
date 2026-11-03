package app.ouyabrowser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The on-screen mouse pointer. It is a small fixed-size view that MainActivity moves with
 * setTranslationX/Y, which is much cheaper on a Tegra 3 than redrawing a full-screen layer.
 * The pointer tip is the view's top-left corner.
 */
final class CursorView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private boolean down;

    CursorView(Context c, int size) {
        super(c);
        float s = size * 0.9f;
        float o = size * 0.06f;
        arrow.moveTo(o, o);
        arrow.lineTo(o, o + 0.82f * s);
        arrow.lineTo(o + 0.20f * s, o + 0.64f * s);
        arrow.lineTo(o + 0.34f * s, o + 0.98f * s);
        arrow.lineTo(o + 0.47f * s, o + 0.92f * s);
        arrow.lineTo(o + 0.33f * s, o + 0.60f * s);
        arrow.lineTo(o + 0.60f * s, o + 0.60f * s);
        arrow.close();
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0xFF000000);
        line.setStrokeWidth(Math.max(2f, size * 0.07f));
        line.setStrokeJoin(Paint.Join.ROUND);
        setClickable(false);
        setFocusable(false);
    }

    void setDown(boolean d) {
        if (down != d) {
            down = d;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        fill.setColor(down ? Ui.ACCENT : 0xFFFFFFFF);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, line);
    }
}
