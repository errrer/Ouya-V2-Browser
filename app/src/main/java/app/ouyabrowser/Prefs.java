package app.ouyabrowser;

import android.content.Context;
import android.content.SharedPreferences;

/** Small typed wrapper around SharedPreferences. Choice settings are stored as indices. */
final class Prefs {
    static final String HOME_DEFAULT = "file:///android_asset/home.html";

    static final String[] SEARCH_NAMES = {"DuckDuckGo (HTML)", "Google (basic)", "Bing", "Wikipedia"};
    static final String[] SEARCH_URLS = {
            "https://html.duckduckgo.com/html/?q=%s",
            "https://www.google.com/search?gbv=1&q=%s",
            "https://www.bing.com/search?q=%s",
            "https://en.wikipedia.org/w/index.php?search=%s"
    };
    static final int[] ZOOMS = {100, 125, 150, 175, 200};
    static final String[] SPEED_NAMES = {"Slow", "Normal", "Fast"};
    static final float[] SPEED = {0.6f, 1f, 1.5f};
    static final int[] SAFE = {0, 2, 4, 6, 8};

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("ouya_browser", Context.MODE_PRIVATE);
    }

    private int idx(String key, int def, int n) {
        int v = sp.getInt(key, def);
        return v < 0 || v >= n ? def : v;
    }

    String home() { return sp.getString("home", HOME_DEFAULT); }
    void home(String v) { sp.edit().putString("home", v).apply(); }

    int search() { return idx("search", 0, SEARCH_NAMES.length); }
    void search(int v) { sp.edit().putInt("search", v).apply(); }

    boolean desktop() { return sp.getBoolean("desktop", true); }
    void desktop(boolean v) { sp.edit().putBoolean("desktop", v).apply(); }

    int zoom() { return idx("zoom", 1, ZOOMS.length); }
    void zoom(int v) { sp.edit().putInt("zoom", v).apply(); }

    int speed() { return idx("speed", 1, SPEED.length); }
    void speed(int v) { sp.edit().putInt("speed", v).apply(); }

    int safe() { return idx("safe", 1, SAFE.length); }
    void safe(int v) { sp.edit().putInt("safe", v).apply(); }

    boolean hover() { return sp.getBoolean("hover", true); }
    void hover(boolean v) { sp.edit().putBoolean("hover", v).apply(); }

    boolean jsScroll() { return sp.getBoolean("jsScroll", false); }
    void jsScroll(boolean v) { sp.edit().putBoolean("jsScroll", v).apply(); }

    /** true = TextureView rendering (overlays always draw correctly), false = SurfaceView (faster). */
    boolean texture() { return sp.getBoolean("texture", true); }
    void texture(boolean v) { sp.edit().putBoolean("texture", v).apply(); }

    /** true = leave web text fields to the system keyboard instead of the built-in one. */
    boolean sysKeyboard() { return sp.getBoolean("sysKeyboard", false); }
    void sysKeyboard(boolean v) { sp.edit().putBoolean("sysKeyboard", v).apply(); }

    String tabs() { return sp.getString("tabs", ""); }
    void tabs(String v) { sp.edit().putString("tabs", v).apply(); }

    int activeTab() { return sp.getInt("activeTab", 0); }
    void activeTab(int v) { sp.edit().putInt("activeTab", v).apply(); }
}
