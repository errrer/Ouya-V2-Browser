package app.ouyabrowser;

import android.graphics.Paint;

/** Shared colours and text helpers for the custom-drawn overlays. */
final class Ui {
    static final int PANEL = 0xF01E1E24;
    static final int KEY = 0xFF3A3A44;
    static final int KEY_SPECIAL = 0xFF2A2A32;
    static final int ACCENT = 0xFFFF7A1A;
    static final int TEXT = 0xFFF2F2F2;
    static final int DIM = 0xFF9A9AA6;
    static final int BAR = 0xE6101014;
    static final int BACKDROP = 0xB3000000;

    private Ui() {}

    /** Shortens text with "..." so it fits in maxW pixels using the paint's current size. */
    static String fit(Paint p, String s, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        float dots = p.measureText("...");
        int n = p.breakText(s, true, Math.max(0f, maxW - dots), null);
        return s.substring(0, Math.max(0, n)) + "...";
    }
}
