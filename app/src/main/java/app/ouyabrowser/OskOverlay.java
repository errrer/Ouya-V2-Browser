package app.ouyabrowser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * On-screen keyboard for the controller. D-pad / left stick to move, O to press a key.
 * L1 = delete, R1 = space, U = shift, Y = done, A = cancel. Used for the address bar and for text
 * fields on web pages, so the OUYA never depends on a system keyboard.
 */
final class OskOverlay extends View implements Overlay {
    interface Callback {
        /** submit is true when Go was pressed (the page should also receive Enter). */
        void onResult(String text, boolean submit);
        void onCancel();
    }

    static final int TEXT = 0;
    static final int URL = 1;
    static final int PASSWORD = 2;

    private static final String[][] LOWER = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "BKSP"},
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", "-"},
            {"SHIFT", "z", "x", "c", "v", "b", "n", "m", ",", "."},
            {"SYM", "/", "@", "SPACE", ".com", "CLEAR", "GO"}
    };
    private static final String[][] UPPER = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "BKSP"},
            {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
            {"A", "S", "D", "F", "G", "H", "J", "K", "L", "_"},
            {"SHIFT", "Z", "X", "C", "V", "B", "N", "M", ";", ":"},
            {"SYM", "/", "@", "SPACE", ".com", "CLEAR", "GO"}
    };
    private static final String[][] SYM = {
            {"!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "BKSP"},
            {"~", "`", "|", "\\", "{", "}", "[", "]", "<", ">"},
            {"_", "=", "+", ";", ":", "'", "\"", "?", "/", "-"},
            {"ABC", ",", ".", "!", "?", "#", "&", "*", "%", "+"},
            {"ABC", "/", "@", "SPACE", ".com", "CLEAR", "GO"}
    };

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF k = new RectF();

    private String title = "";
    private String text = "";
    private int mode;
    private Callback cb;
    private boolean shift;
    private boolean sym;
    private int row = 1;
    private int col = 4;

    OskOverlay(Context c) {
        super(c);
        setVisibility(GONE);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    public boolean isOpen() { return getVisibility() == VISIBLE; }

    void open(String title, String initial, int mode, Callback cb) {
        this.title = title == null ? "" : title;
        this.text = initial == null ? "" : initial;
        this.mode = mode;
        this.cb = cb;
        shift = false;
        sym = false;
        row = 1;
        col = 4;
        setVisibility(VISIBLE);
        invalidate();
    }

    private void finish(boolean ok, boolean submit) {
        Callback c = cb;
        cb = null;
        setVisibility(GONE);
        if (c == null) return;
        if (ok) c.onResult(text, submit);
        else c.onCancel();
    }

    private String[][] layer() { return sym ? SYM : (shift ? UPPER : LOWER); }

    private static float weight(String t) {
        if (t.equals("BKSP") || t.equals("SHIFT")) return 1.6f;
        if (t.equals("SYM") || t.equals("ABC") || t.equals("CLEAR") || t.equals("GO")) return 1.4f;
        if (t.equals("SPACE")) return 4f;
        if (t.equals(".com")) return 1.6f;
        return 1f;
    }

    private static String label(String t, boolean shiftOn) {
        if (t.equals("BKSP")) return "Del";
        if (t.equals("SHIFT")) return shiftOn ? "SHIFT*" : "Shift";
        if (t.equals("SYM")) return "?123";
        if (t.equals("SPACE")) return "Space";
        if (t.equals("CLEAR")) return "Clear";
        if (t.equals("GO")) return "Go";
        return t;
    }

    // ---- geometry (pure functions of the view size so navigation never depends on draw order) ----

    private float panelW() { return getWidth() * 0.92f; }
    private float panelH() { return 480f * getHeight() / 720f; }
    private float panelX() { return (getWidth() - panelW()) / 2f; }
    private float panelY() { return getHeight() - panelH() - 18f * getHeight() / 720f; }

    private RectF keyRect(int rr, int cc, RectF out) {
        float u = getHeight() / 720f;
        String[] rowKeys = layer()[rr];
        float total = 0f;
        for (String s : rowKeys) total += weight(s);
        float areaX = panelX() + 20 * u;
        float areaW = panelW() - 40 * u;
        float x = areaX;
        for (int i = 0; i < cc; i++) x += areaW * weight(rowKeys[i]) / total;
        float kw = areaW * weight(rowKeys[cc]) / total;
        float rowH = 58 * u;
        float y = panelY() + 160 * u + rr * rowH;
        out.set(x + 3 * u, y + 3 * u, x + kw - 3 * u, y + rowH - 3 * u);
        return out;
    }

    private int nearest(int rr, float x) {
        String[] rowKeys = layer()[rr];
        int best = 0;
        float bestD = Float.MAX_VALUE;
        RectF t = new RectF();
        for (int i = 0; i < rowKeys.length; i++) {
            keyRect(rr, i, t);
            float d = Math.abs(t.centerX() - x);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void move(int dr, int dc) {
        String[][] l = layer();
        if (dr != 0) {
            int nr = Math.max(0, Math.min(l.length - 1, row + dr));
            if (nr != row) {
                float x = keyRect(row, col, k).centerX();
                row = nr;
                col = nearest(nr, x);
            }
        } else {
            int n = l[row].length;
            col = (col + dc + n) % n;
        }
    }

    private void press(String t) {
        if (t.equals("BKSP")) {
            if (text.length() > 0) text = text.substring(0, text.length() - 1);
        } else if (t.equals("CLEAR")) {
            text = "";
        } else if (t.equals("SPACE")) {
            text += " ";
        } else if (t.equals("SHIFT")) {
            shift = !shift;
            clampCol();
        } else if (t.equals("SYM")) {
            sym = true;
            clampCol();
        } else if (t.equals("ABC")) {
            sym = false;
            clampCol();
        } else if (t.equals("GO")) {
            finish(true, true);
        } else {
            text += t;
            if (shift && !sym && t.length() == 1 && Character.isLetter(t.charAt(0))) shift = false;
        }
    }

    private void clampCol() {
        int n = layer()[row].length;
        if (col >= n) col = n - 1;
    }

    @Override
    public boolean onButton(Btn b, boolean repeat) {
        switch (b) {
            case UP: move(-1, 0); break;
            case DOWN: move(1, 0); break;
            case LEFT: move(0, -1); break;
            case RIGHT: move(0, 1); break;
            case O:
                if (!repeat) press(layer()[row][col]);
                break;
            case L1: press("BKSP"); break;
            case R1: press("SPACE"); break;
            case U:
                if (!repeat) { shift = !shift; sym = false; clampCol(); }
                break;
            case Y:
            case MENU:
                if (!repeat) finish(true, false);
                return true;
            case A:
                if (!repeat) finish(false, false);
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
        c.drawColor(0x66000000);

        float px = panelX();
        float py = panelY();
        float pw = panelW();
        float ph = panelH();
        r.set(px, py, px + pw, py + ph);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.PANEL);
        c.drawRoundRect(r, 16 * u, 16 * u, p);

        // title + text box
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(23 * u);
        p.setColor(Ui.DIM);
        c.drawText(Ui.fit(p, title, pw - 40 * u), px + 24 * u, py + 36 * u, p);

        r.set(px + 20 * u, py + 48 * u, px + pw - 20 * u, py + 112 * u);
        p.setColor(0xFF0F0F13);
        c.drawRoundRect(r, 10 * u, 10 * u, p);
        p.setTextSize(32 * u);
        p.setColor(Ui.TEXT);
        String shown = mode == PASSWORD ? bullets(text.length()) : text;
        float maxW = pw - 80 * u;
        // keep the end of long text visible
        while (shown.length() > 1 && p.measureText(shown) > maxW) shown = shown.substring(1);
        c.drawText(shown, px + 34 * u, py + 92 * u, p);
        float cx = px + 34 * u + p.measureText(shown) + 3 * u;
        p.setColor(Ui.ACCENT);
        c.drawRect(cx, py + 58 * u, cx + 3 * u, py + 102 * u, p);

        // keys
        String[][] l = layer();
        p.setTextSize(27 * u);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        for (int rr = 0; rr < l.length; rr++) {
            for (int cc = 0; cc < l[rr].length; cc++) {
                String t = l[rr][cc];
                keyRect(rr, cc, k);
                boolean on = rr == row && cc == col;
                boolean special = t.length() > 1;
                boolean active = t.equals("SHIFT") && shift;
                p.setColor(on ? Ui.ACCENT : (active ? 0xFF5A4A3A : (special ? Ui.KEY_SPECIAL : Ui.KEY)));
                c.drawRoundRect(k, 8 * u, 8 * u, p);
                p.setColor(on ? 0xFF000000 : Ui.TEXT);
                String lab = label(t, shift);
                float tw = p.measureText(lab);
                c.drawText(lab, k.centerX() - tw / 2f, k.centerY() + 9 * u, p);
            }
        }

        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(22 * u);
        p.setColor(Ui.DIM);
        c.drawText("O Type    A Cancel    L1 Delete    R1 Space    U Shift    Y Done", px + 24 * u, py + ph - 16 * u, p);
    }

    private static String bullets(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('*');
        return sb.toString();
    }
}
