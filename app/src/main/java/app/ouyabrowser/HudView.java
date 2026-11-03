package app.ouyabrowser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Transparent full-stage layer with the address/status bar (shown briefly on page and tab changes
 * and while loading), the load progress line, and short toast messages. It never takes input.
 */
final class HudView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private String title = "";
    private String url = "";
    private String toast = "";
    private boolean secure;
    private boolean loading;
    private boolean enabled = true;
    private int progress = 100;
    private int tabIndex;
    private int tabCount = 1;
    private long barUntil;
    private long toastUntil;
    private float u = 1f;

    HudView(Context c) {
        super(c);
        setClickable(false);
        setFocusable(false);
    }

    void setEnabledHud(boolean e) {
        enabled = e;
        invalidate();
    }

    void setPage(String title, String url, boolean secure, int tabIndex, int tabCount) {
        this.title = title == null ? "" : title;
        this.url = url == null ? "" : url;
        this.secure = secure;
        this.tabIndex = tabIndex;
        this.tabCount = tabCount;
        invalidate();
    }

    void setLoading(boolean l) {
        loading = l;
        if (l) progress = 0;
        invalidate();
    }

    void setProgress(int pr) {
        progress = pr;
        invalidate();
    }

    /** Shows the address bar for a few seconds. */
    void flash() {
        barUntil = SystemClock.uptimeMillis() + 3500;
        invalidate();
        postInvalidateDelayed(3600);
    }

    void toast(String s) {
        toast = s;
        toastUntil = SystemClock.uptimeMillis() + 2200;
        invalidate();
        postInvalidateDelayed(2300);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        u = h / 720f;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!enabled) return;
        long now = SystemClock.uptimeMillis();
        float w = getWidth();
        float h = getHeight();

        if (loading && progress < 100) {
            p.setColor(Ui.ACCENT);
            c.drawRect(0, 0, w * progress / 100f, 5 * u, p);
        }

        if (now < barUntil || loading) {
            float bh = 56 * u;
            p.setColor(Ui.BAR);
            c.drawRect(0, 0, w, bh, p);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(26 * u);
            p.setColor(secure ? 0xFF6BD36B : Ui.DIM);
            String lock = secure ? "https" : "http";
            float x = 18 * u;
            float base = bh / 2f + 9 * u;
            c.drawText(lock, x, base, p);
            x += p.measureText(lock) + 16 * u;

            String tabs = tabCount > 1 ? "Tab " + (tabIndex + 1) + "/" + tabCount : "";
            float tabsW = tabs.length() == 0 ? 0 : p.measureText(tabs) + 30 * u;
            p.setColor(Ui.TEXT);
            String host = Urls.host(url);
            String label = title.length() > 0 ? title : (host.length() > 0 ? host : url);
            if (host.length() > 0 && title.length() > 0) label = host + "  -  " + title;
            c.drawText(Ui.fit(p, label, w - x - tabsW - 18 * u), x, base, p);
            if (tabs.length() > 0) {
                p.setColor(Ui.ACCENT);
                c.drawText(tabs, w - tabsW + 10 * u, base, p);
            }
        }

        if (now < toastUntil && toast.length() > 0) {
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(28 * u);
            float tw = p.measureText(toast);
            float bw = tw + 56 * u;
            float bh = 56 * u;
            float x = (w - bw) / 2f;
            float y = h - 90 * u;
            r.set(x, y, x + bw, y + bh);
            p.setColor(0xE6000000);
            c.drawRoundRect(r, 28 * u, 28 * u, p);
            p.setColor(Ui.TEXT);
            c.drawText(toast, x + 28 * u, y + bh / 2f + 10 * u, p);
        }
    }
}
