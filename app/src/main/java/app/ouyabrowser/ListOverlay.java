package app.ouyabrowser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A modal list driven by the controller: D-pad up/down to move, O to select, left/right to change a
 * choice, U for the item's secondary action (e.g. remove), A/Y/Menu to go back. Used for the menu,
 * tabs, bookmarks, history, settings, help and the certificate warning.
 */
final class ListOverlay extends View implements Overlay {
    interface Action { void run(Item it); }
    interface Adjust { void adjust(Item it, int dir); }

    static final class Item {
        String label;
        String value;
        boolean info;
        Action action;
        Action secondary;
        Adjust adjust;

        Item(String label) { this.label = label; }
        Item(String label, String value) { this.label = label; this.value = value; }

        /** A read-only line (cannot be selected). */
        static Item info(String label, String value) {
            Item i = new Item(label, value);
            i.info = true;
            return i;
        }
    }

    private final ArrayList<Item> items = new ArrayList<Item>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private String title = "";
    private String hint = "";
    private int sel;
    private int top;
    private Runnable onClose;

    ListOverlay(Context c) {
        super(c);
        setVisibility(GONE);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    public boolean isOpen() { return getVisibility() == VISIBLE; }

    int selected() { return sel; }

    /** onClose runs when the user backs out (A / Y / Menu), not when an item closes the list itself. */
    void open(String title, List<Item> list, int selected, String hint, Runnable onClose) {
        this.title = title;
        this.hint = hint == null ? "" : hint;
        this.onClose = onClose;
        items.clear();
        items.addAll(list);
        sel = Math.max(0, Math.min(selected, items.size() - 1));
        if (items.size() > 0 && items.get(sel).info) {
            sel = -1;
            step(1);
            if (sel < 0) sel = 0;
        }
        top = 0;
        setVisibility(VISIBLE);
        invalidate();
    }

    /** Closes without running onClose. */
    void close() {
        onClose = null;
        setVisibility(GONE);
    }

    /** Backs out: closes and runs onClose. */
    void dismiss() {
        Runnable r = onClose;
        close();
        if (r != null) r.run();
    }

    private void step(int dir) {
        int i = sel;
        while (true) {
            i += dir;
            if (i < 0 || i >= items.size()) return;
            if (!items.get(i).info) {
                sel = i;
                return;
            }
        }
    }

    @Override
    public boolean onButton(Btn b, boolean repeat) {
        Item it = sel >= 0 && sel < items.size() ? items.get(sel) : null;
        switch (b) {
            case UP: step(-1); break;
            case DOWN: step(1); break;
            case L2: for (int i = 0; i < 5; i++) step(-1); break;
            case R2: for (int i = 0; i < 5; i++) step(1); break;
            case LEFT:
                if (it != null && it.adjust != null) it.adjust.adjust(it, -1);
                break;
            case RIGHT:
                if (it != null && it.adjust != null) it.adjust.adjust(it, 1);
                break;
            case O:
                if (repeat) break;
                if (it != null) {
                    if (it.action != null) it.action.run(it);
                    else if (it.adjust != null) it.adjust.adjust(it, 1);
                }
                break;
            case U:
                if (!repeat && it != null && it.secondary != null) it.secondary.run(it);
                break;
            case A:
            case Y:
            case MENU:
                if (!repeat) dismiss();
                return true;
            default:
                break;
        }
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float u = h / 720f;
        c.drawColor(Ui.BACKDROP);

        float pw = Math.min(w * 0.72f, 1100f * u);
        float ph = h * 0.86f;
        float px = (w - pw) / 2f;
        float py = (h - ph) / 2f;
        r.set(px, py, px + pw, py + ph);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.PANEL);
        c.drawRoundRect(r, 16 * u, 16 * u, p);

        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(34 * u);
        p.setColor(Ui.ACCENT);
        c.drawText(Ui.fit(p, title, pw - 56 * u), px + 28 * u, py + 56 * u, p);

        float rowH = 58 * u;
        float listTop = py + 84 * u;
        float listBottom = py + ph - 56 * u;
        int rows = Math.max(1, (int) ((listBottom - listTop) / rowH));
        if (sel < top) top = sel;
        if (sel >= top + rows) top = sel - rows + 1;
        if (top < 0) top = 0;

        float left = px + 20 * u;
        float right = px + pw - 20 * u;
        for (int i = 0; i < rows && top + i < items.size(); i++) {
            Item it = items.get(top + i);
            float y = listTop + i * rowH;
            boolean on = top + i == sel && !it.info;
            if (on) {
                r.set(left, y, right, y + rowH - 6 * u);
                p.setColor(Ui.ACCENT);
                c.drawRoundRect(r, 10 * u, 10 * u, p);
            }
            float base = y + rowH / 2f + 8 * u;
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(28 * u);

            float valueW = 0f;
            if (it.value != null && it.value.length() > 0) {
                valueW = Math.min(p.measureText(it.value), (right - left) * 0.5f);
            }
            float chevron = it.adjust != null ? 30 * u : 0f;

            p.setColor(on ? 0xFF000000 : (it.info ? Ui.DIM : Ui.TEXT));
            float labelMax = right - left - 40 * u - valueW - chevron * 2f;
            c.drawText(Ui.fit(p, it.label, labelMax), left + 18 * u, base, p);

            if (valueW > 0f) {
                p.setColor(on ? 0xFF000000 : (it.info ? Ui.TEXT : Ui.DIM));
                String v = Ui.fit(p, it.value, valueW);
                c.drawText(v, right - 18 * u - chevron - valueW, base, p);
            }
            if (it.adjust != null) {
                p.setColor(on ? 0xFF000000 : Ui.DIM);
                chevron(c, right - 18 * u - chevron - valueW - 12 * u, y + (rowH - 6 * u) / 2f, 9 * u, -1);
                chevron(c, right - 18 * u - 8 * u, y + (rowH - 6 * u) / 2f, 9 * u, 1);
            }
        }

        // scroll arrows
        p.setColor(Ui.DIM);
        if (top > 0) triangle(c, px + pw - 40 * u, listTop - 18 * u, 10 * u, true);
        if (top + rows < items.size()) triangle(c, px + pw - 40 * u, listBottom + 10 * u, 10 * u, false);

        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(23 * u);
        p.setColor(Ui.DIM);
        c.drawText(Ui.fit(p, hint, pw - 56 * u), px + 28 * u, py + ph - 20 * u, p);
    }

    private void chevron(Canvas c, float cx, float cy, float s, int dir) {
        path.reset();
        path.moveTo(cx - dir * s * 0.5f, cy - s);
        path.lineTo(cx + dir * s * 0.5f, cy);
        path.lineTo(cx - dir * s * 0.5f, cy + s);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, s * 0.3f));
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void triangle(Canvas c, float cx, float cy, float s, boolean up) {
        path.reset();
        if (up) {
            path.moveTo(cx - s, cy + s * 0.6f);
            path.lineTo(cx + s, cy + s * 0.6f);
            path.lineTo(cx, cy - s * 0.6f);
        } else {
            path.moveTo(cx - s, cy - s * 0.6f);
            path.lineTo(cx + s, cy - s * 0.6f);
            path.lineTo(cx, cy + s * 0.6f);
        }
        path.close();
        c.drawPath(path, p);
    }
}
