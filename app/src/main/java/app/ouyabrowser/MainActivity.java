package app.ouyabrowser;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xwalk.core.XWalkNavigationHistory;
import org.xwalk.core.XWalkPreferences;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The whole browser. One live XWalkView (Chromium) is reused for every tab; background tabs are just
 * remembered URLs, which keeps memory low on a 1 GB console. A virtual cursor, scrolling, tabs and
 * text entry are all driven from the OUYA controller.
 */
public class MainActivity extends Activity {
    private static final String TAG = "OuyaBrowser";
    private static final int MAX_TABS = 6;
    /** Mouse-wheel "ticks" per second at full right-stick deflection. */
    private static final float SCROLL_TICKS_PER_SEC = 16f;
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.143 Safari/537.36";

    private static final class Tab {
        String url;
        String title;
        Tab(String url, String title) { this.url = url; this.title = title; }
    }

    private Prefs prefs;
    private Store store;
    private float u = 1f;
    private int sw;
    private int sh;

    private FrameLayout root;
    private FrameLayout stage;
    private FrameLayout holder;
    private XWalkView engine;
    private CursorView cursorView;
    private HudView hud;
    private ListOverlay list;
    private OskOverlay osk;

    private final ArrayList<Tab> tabs = new ArrayList<Tab>();
    private int active;
    private final HashSet<String> sslAllowed = new HashSet<String>();
    private final Handler ui = new Handler();

    // cursor and sticks
    private float cx;
    private float cy;
    private float lsX;
    private float lsY;
    private float rsX;
    private float rsY;
    private boolean slow;
    private boolean pumping;
    private boolean oDown;
    private boolean triggerKeySeen;
    private boolean l2Axis;
    private boolean r2Axis;
    private long lastPump;
    private long downTime;
    private long lastHover;
    private long lastJsScroll;
    private long exitArmedUntil;
    private Btn navDir;
    private long navNext;
    private boolean zoomApplied;
    private boolean pageInjected;

    // ------------------------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        store = new Store(this);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        sw = Math.max(dm.widthPixels, dm.heightPixels);
        sh = Math.min(dm.widthPixels, dm.heightPixels);
        u = sh / 720f;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        // The "safe area" margin keeps the page inside the visible part of TVs that overscan.
        int mx = Math.round(sw * Prefs.SAFE[prefs.safe()] / 100f);
        int my = Math.round(sh * Prefs.SAFE[prefs.safe()] / 100f);
        stage = new FrameLayout(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        slp.setMargins(mx, my, mx, my);
        root.addView(stage, slp);

        holder = new FrameLayout(this);
        stage.addView(holder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hud = new HudView(this);
        stage.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int cs = Math.round(34 * u);
        cursorView = new CursorView(this, cs);
        stage.addView(cursorView, new FrameLayout.LayoutParams(cs, cs, Gravity.TOP | Gravity.LEFT));

        list = new ListOverlay(this);
        root.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        osk = new OskOverlay(this);
        root.addView(osk, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);

        cx = (sw - 2 * mx) / 2f;
        cy = (sh - 2 * my) / 2f;
        placeCursor();

        if (!startEngine()) return;
        restoreTabs();
        handleIntent(getIntent());
        hud.toast("Press Y for the menu");
    }

    private boolean startEngine() {
        try {
            setPref("animatable-xwalk-view", prefs.texture());
            setPref("support-multiple-windows", false);
            setPref("javascript-can-open-window", true);
            setPref("remote-debugging", (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            engine = new XWalkView(this);
        } catch (Throwable t) {
            fatal(t);
            return false;
        }
        holder.addView(engine, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        engine.setBackgroundColor(0xFF000000);
        applyUserAgent();

        engine.setResourceClient(new XWalkResourceClient(engine) {
            @Override
            public void onLoadStarted(XWalkView view, String url) {
                zoomApplied = false;
                pageInjected = false;
                hud.setLoading(true);
                Tab t = currentTab();
                if (t != null && url != null) t.url = url;
                refreshHud();
            }

            @Override
            public void onProgressChanged(XWalkView view, int progress) {
                hud.setProgress(progress);
                if (progress >= 35 && !pageInjected) {
                    pageInjected = true;
                    injectPageScripts();
                }
            }

            @Override
            public void onLoadFinished(XWalkView view, String url) {
                hud.setLoading(false);
                injectPageScripts();
                Tab t = currentTab();
                String title = view.getTitle();
                if (t != null) {
                    if (url != null) t.url = url;
                    if (title != null && title.length() > 0) t.title = title;
                }
                if (url != null) store.addHistory(title, url);
                refreshHud();
                hud.flash();
                saveTabs();
            }

            @Override
            public void onReceivedSslError(XWalkView view, ValueCallback<Boolean> callback, SslError error) {
                askSsl(callback, error);
            }

            @Override
            public void onReceivedLoadError(XWalkView view, int errorCode, String description, String failingUrl) {
                if (description != null && description.contains("ABORTED")) return;
                hud.setLoading(false);
                view.load(failingUrl, Web.errorPage(description, failingUrl));
            }
        });

        engine.setUIClient(new XWalkUIClient(engine) {
            @Override
            public void onReceivedTitle(XWalkView view, String title) {
                Tab t = currentTab();
                if (t != null && title != null && title.length() > 0) t.title = title;
                refreshHud();
            }

            @Override
            public void onFullscreenToggled(XWalkView view, boolean enterFullscreen) {
                hud.setEnabledHud(!enterFullscreen);
                if (enterFullscreen) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });

        CertTrust.install(this);
        engine.requestFocus();
        return true;
    }

    private static void setPref(String key, boolean value) {
        try {
            XWalkPreferences.setValue(key, value);
        } catch (Throwable t) {
            Log.w(TAG, "XWalkPreferences " + key + ": " + t);
        }
    }

    /** Shown instead of the browser if the engine cannot start (wrong CPU, missing libraries, ...). */
    private void fatal(Throwable t) {
        Log.e(TAG, "engine failed to start", t);
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFEEEEEE);
        tv.setTextSize(24f);
        tv.setPadding(80, 80, 80, 80);
        tv.setText("The browser engine could not start.\n\n" + t + "\n\nCPU features: " + cpuFeatures()
                + "\n\nThe engine (Chromium via Crosswalk) needs an ARMv7 CPU with NEON. "
                + "Press A to exit.");
        root.addView(tv);
    }

    private static String cpuFeatures() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Features")) return line;
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) { }
        }
        return "unknown";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null || engine == null) return;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            newTab(i.getData().toString());
            i.setData(null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) {
            captureTab();
            engine.pauseTimers();
            engine.onHide();
        }
        saveTabs();
        store.flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null) {
            engine.resumeTimers();
            engine.onShow();
            engine.requestFocus();
        }
    }

    @Override
    protected void onDestroy() {
        if (engine != null) engine.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && engine != null) engine.clearCache(false);
    }

    // ------------------------------------------------------------------------------------ tabs

    private Tab currentTab() {
        return active >= 0 && active < tabs.size() ? tabs.get(active) : null;
    }

    private void restoreTabs() {
        try {
            JSONArray a = new JSONArray(prefs.tabs());
            for (int i = 0; i < a.length() && i < MAX_TABS; i++) {
                JSONObject o = a.getJSONObject(i);
                tabs.add(new Tab(o.getString("u"), o.optString("t", "")));
            }
        } catch (JSONException ignored) {
            tabs.clear();
        }
        if (tabs.isEmpty()) tabs.add(new Tab(prefs.home(), "Home"));
        active = Math.max(0, Math.min(prefs.activeTab(), tabs.size() - 1));
        loadActive();
    }

    private void saveTabs() {
        JSONArray a = new JSONArray();
        try {
            for (Tab t : tabs) {
                JSONObject o = new JSONObject();
                o.put("u", t.url);
                o.put("t", t.title);
                a.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        prefs.tabs(a.toString());
        prefs.activeTab(active);
    }

    /** Copies the live page's URL and title into the current tab record. */
    private void captureTab() {
        Tab t = currentTab();
        if (t == null || engine == null) return;
        String url = engine.getUrl();
        if (url != null && url.length() > 0 && !url.equals("about:blank")) t.url = url;
        String title = engine.getTitle();
        if (title != null && title.length() > 0) t.title = title;
    }

    private void loadActive() {
        Tab t = currentTab();
        if (t == null || engine == null) return;
        engine.load(t.url, null);
        refreshHud();
        hud.flash();
    }

    private void loadUrl(String url) {
        Tab t = currentTab();
        if (t != null) t.url = url;
        if (engine != null) engine.load(url, null);
        refreshHud();
    }

    private void newTab(String url) {
        if (tabs.size() >= MAX_TABS) {
            hud.toast("Tab limit reached (" + MAX_TABS + ") - close one first");
            return;
        }
        captureTab();
        tabs.add(new Tab(url, "New tab"));
        active = tabs.size() - 1;
        loadActive();
        hud.toast("Tab " + (active + 1) + " of " + tabs.size());
    }

    private void switchTo(int i) {
        if (i < 0 || i >= tabs.size() || i == active) return;
        captureTab();
        active = i;
        loadActive();
    }

    private void switchTab(int dir) {
        if (tabs.size() < 2) {
            hud.toast("Only one tab open");
            return;
        }
        switchTo((active + dir + tabs.size()) % tabs.size());
        hud.toast("Tab " + (active + 1) + " of " + tabs.size());
    }

    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        if (tabs.size() == 1) {
            tabs.set(0, new Tab(prefs.home(), "Home"));
            active = 0;
            loadActive();
            return;
        }
        boolean wasActive = i == active;
        tabs.remove(i);
        if (i < active) active--;
        if (active >= tabs.size()) active = tabs.size() - 1;
        if (wasActive) loadActive();
        refreshHud();
    }

    private void refreshHud() {
        Tab t = currentTab();
        if (t == null) return;
        hud.setPage(t.title, t.url, Urls.isSecure(t.url), active, tabs.size());
    }

    // ------------------------------------------------------------------------------------ engine helpers

    private void applyUserAgent() {
        if (engine == null) return;
        String def = engine.getUserAgentString();
        engine.setUserAgentString(prefs.desktop() ? UA_DESKTOP : def);
    }

    private void injectPageScripts() {
        if (engine == null) return;
        engine.evaluateJavascript(Web.INSTALL, null);
        Tab t = currentTab();
        boolean internal = t == null || Urls.isInternal(t.url);
        int z = internal ? 100 : Prefs.ZOOMS[prefs.zoom()];
        if (z != 100 || zoomApplied) {
            engine.evaluateJavascript(Web.zoom(z), null);
            zoomApplied = z != 100;
        }
    }

    private void reload() {
        if (engine == null) return;
        engine.reload(XWalkView.RELOAD_NORMAL);
    }

    private boolean canGoBack() {
        return engine != null && engine.getNavigationHistory().canGoBack();
    }

    private boolean canGoForward() {
        return engine != null && engine.getNavigationHistory().canGoForward();
    }

    private void historyBack() {
        if (canGoBack()) engine.getNavigationHistory().navigate(XWalkNavigationHistory.Direction.BACKWARD, 1);
    }

    private void historyForward() {
        if (canGoForward()) engine.getNavigationHistory().navigate(XWalkNavigationHistory.Direction.FORWARD, 1);
    }

    // ------------------------------------------------------------------------------------ input routing

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_L2 || code == KeyEvent.KEYCODE_BUTTON_R2) triggerKeySeen = true;
        Btn b = Pad.fromKey(e);
        if (b == null) {
            // Ordinary keyboard keys go to the page, unless an overlay is open (then they are swallowed).
            boolean system = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER;
            if (!system && (list.isOpen() || osk.isOpen())) return true;
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) press(b, e.getRepeatCount() > 0);
        else if (e.getAction() == KeyEvent.ACTION_UP) release(b);
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        int src = e.getSource();
        if ((src & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && e.getAction() == MotionEvent.ACTION_MOVE) {
            onJoystick(e);
            return true;
        }
        if ((src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE && cursorView != null) {
            // A real mouse is in use; hide our pointer until the stick is touched again.
            cursorView.setVisibility(View.INVISIBLE);
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void onJoystick(MotionEvent e) {
        lsX = e.getAxisValue(MotionEvent.AXIS_X);
        lsY = e.getAxisValue(MotionEvent.AXIS_Y);
        rsX = e.getAxisValue(MotionEvent.AXIS_Z);
        rsY = e.getAxisValue(MotionEvent.AXIS_RZ);

        if (!triggerKeySeen) {
            // Only used when the controller reports triggers as axes and never as L2/R2 key events.
            float l2 = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            float r2 = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            if (l2 > 0.6f && !l2Axis) { l2Axis = true; press(Btn.L2, false); }
            else if (l2 < 0.3f) l2Axis = false;
            if (r2 > 0.6f && !r2Axis) { r2Axis = true; press(Btn.R2, false); }
            else if (r2 < 0.3f) r2Axis = false;
        }

        if (list.isOpen() || osk.isOpen()) {
            overlayStick();
            return;
        }
        navDir = null;
        if (Pad.dz(lsX) != 0 || Pad.dz(lsY) != 0 || Pad.dz(rsX) != 0 || Pad.dz(rsY) != 0) kick();
    }

    /** Turns left-stick deflection into repeating D-pad presses for the menu and keyboard. */
    private void overlayStick() {
        float ax = Math.abs(lsX);
        float ay = Math.abs(lsY);
        Btn d = null;
        if (Math.max(ax, ay) > 0.6f) {
            d = ax > ay ? (lsX > 0 ? Btn.RIGHT : Btn.LEFT) : (lsY > 0 ? Btn.DOWN : Btn.UP);
        }
        if (d == null) {
            navDir = null;
            return;
        }
        if (d != navDir) {
            navDir = d;
            navNext = SystemClock.uptimeMillis() + 380;
            overlayButton(d, false);
            kick();
        }
    }

    private void overlayButton(Btn b, boolean repeat) {
        if (list.isOpen()) list.onButton(b, repeat);
        else if (osk.isOpen()) osk.onButton(b, repeat);
    }

    private void kick() {
        if (pumping) return;
        pumping = true;
        lastPump = SystemClock.uptimeMillis() - 16;
        ui.post(pump);
    }

    /** ~60 Hz loop that runs only while a stick is deflected. */
    private final Runnable pump = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - lastPump) / 1000f);
            lastPump = now;
            boolean busy = false;
            if (list.isOpen() || osk.isOpen()) {
                if (navDir != null) {
                    busy = true;
                    if (now >= navNext) {
                        navNext = now + 120;
                        overlayButton(navDir, true);
                    }
                }
            } else if (engine != null) {
                float mx = Pad.dz(lsX);
                float my = Pad.dz(lsY);
                if (mx != 0f || my != 0f) {
                    float speed = 1000f * u * Prefs.SPEED[prefs.speed()] * (slow ? 0.35f : 1f);
                    moveCursor(mx * Math.abs(mx) * speed * dt, my * Math.abs(my) * speed * dt);
                    busy = true;
                }
                float sx = Pad.dz(rsX);
                float sy = Pad.dz(rsY);
                if (sx != 0f || sy != 0f) {
                    stickScroll(sx, sy, dt);
                    busy = true;
                }
            }
            if (busy) ui.postDelayed(this, 16);
            else pumping = false;
        }
    };

    private void press(Btn b, boolean repeat) {
        if (list.isOpen() || osk.isOpen()) {
            overlayButton(b, repeat);
            return;
        }
        if (engine == null) {
            if (b == Btn.A) finish();
            return;
        }
        showCursor();
        switch (b) {
            case O: if (!repeat) clickDown(); break;
            case U: if (!repeat) openUrlBar(); break;
            case Y:
            case MENU: if (!repeat) openMenu(); break;
            case A: if (!repeat) goBack(); break;
            case L1: if (!repeat) switchTab(-1); break;
            case R1: if (!repeat) switchTab(1); break;
            case L2: pageScroll(-1); break;
            case R2: pageScroll(1); break;
            case L3:
                if (!repeat) {
                    slow = !slow;
                    hud.toast(slow ? "Precision cursor: on" : "Precision cursor: off");
                }
                break;
            case R3: if (!repeat) cycleZoom(); break;
            case UP: stepScroll(-1); break;
            case DOWN: stepScroll(1); break;
            case LEFT: if (!repeat) historyBack(); break;
            case RIGHT: if (!repeat) historyForward(); break;
            default: break;
        }
    }

    private void release(Btn b) {
        if (b == Btn.O && oDown) clickUp();
    }

    private void goBack() {
        if (canGoBack()) {
            historyBack();
        } else if (tabs.size() > 1) {
            closeTab(active);
        } else {
            long now = SystemClock.uptimeMillis();
            if (now < exitArmedUntil) {
                finish();
            } else {
                exitArmedUntil = now + 2000;
                hud.toast("Press A again to exit");
            }
        }
    }

    // ------------------------------------------------------------------------------------ cursor

    private final Runnable hideCursor = new Runnable() {
        @Override
        public void run() {
            if (!oDown) cursorView.setVisibility(View.INVISIBLE);
        }
    };

    private void showCursor() {
        if (cursorView.getVisibility() != View.VISIBLE) cursorView.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideCursor);
        ui.postDelayed(hideCursor, 7000);
    }

    private void placeCursor() {
        cursorView.setTranslationX(cx);
        cursorView.setTranslationY(cy);
    }

    private void moveCursor(float dx, float dy) {
        float w = stage.getWidth() > 0 ? stage.getWidth() : sw;
        float h = stage.getHeight() > 0 ? stage.getHeight() : sh;
        cx = Math.max(0f, Math.min(w - 1f, cx + dx));
        cy = Math.max(0f, Math.min(h - 1f, cy + dy));
        showCursor();
        placeCursor();
        if (oDown) sendTouch(MotionEvent.ACTION_MOVE);
        else sendHover();
    }

    private void clickDown() {
        oDown = true;
        downTime = SystemClock.uptimeMillis();
        cursorView.setDown(true);
        sendTouch(MotionEvent.ACTION_DOWN);
    }

    private void clickUp() {
        oDown = false;
        cursorView.setDown(false);
        sendTouch(MotionEvent.ACTION_UP);
        ui.removeCallbacks(checkField);
        ui.postDelayed(checkField, 220);
    }

    /** The cursor and the engine view share the same coordinate space (both fill the stage). */
    private void sendTouch(int action) {
        if (engine == null) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, cx, cy, 0);
        engine.dispatchTouchEvent(ev);
        ev.recycle();
    }

    private void sendHover() {
        if (engine == null || !prefs.hover()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastHover < 40) return;
        lastHover = now;
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        engine.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    // ------------------------------------------------------------------------------------ scrolling

    /** Sends a mouse-wheel event at the cursor. Positive v scrolls up, positive h scrolls right. */
    private void wheel(float v, float h) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[1];
        pp[0] = new MotionEvent.PointerProperties();
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[1];
        pc[0] = new MotionEvent.PointerCoords();
        pc[0].x = cx;
        pc[0].y = cy;
        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, v);
        pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, h);
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, pp, pc,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        engine.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private void stickScroll(float sx, float sy, float dt) {
        if (prefs.jsScroll()) {
            long now = SystemClock.uptimeMillis();
            if (now - lastJsScroll < 33) return;
            lastJsScroll = now;
            engine.evaluateJavascript(Web.scrollBy(Math.round(sx * 40f), Math.round(sy * 40f)), null);
            return;
        }
        wheel(-sy * SCROLL_TICKS_PER_SEC * dt, sx * SCROLL_TICKS_PER_SEC * dt);
    }

    /** D-pad up/down: a few wheel notches (or 220 px in JavaScript mode). */
    private void stepScroll(int dirDown) {
        if (prefs.jsScroll()) engine.evaluateJavascript(Web.scrollBy(0, dirDown * 220), null);
        else wheel(-dirDown * 2.5f, 0f);
    }

    private void pageScroll(int dirDown) {
        engine.evaluateJavascript(Web.scrollPage(dirDown), null);
    }

    private void cycleZoom() {
        int i = (prefs.zoom() + 1) % Prefs.ZOOMS.length;
        prefs.zoom(i);
        zoomApplied = true;
        injectPageScripts();
        hud.toast("Page zoom " + Prefs.ZOOMS[i] + "%");
    }

    // ------------------------------------------------------------------------------------ text entry

    private final Runnable checkField = new Runnable() {
        @Override
        public void run() {
            if (engine == null || list.isOpen() || osk.isOpen() || prefs.sysKeyboard()) return;
            engine.evaluateJavascript(Web.ACTIVE_FIELD, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    onFieldResult(Web.unquote(value));
                }
            });
        }
    };

    private void onFieldResult(String json) {
        if (json.length() == 0 || osk.isOpen() || list.isOpen()) return;
        try {
            JSONObject o = new JSONObject(json);
            String hint = o.optString("p", "");
            boolean pw = "password".equals(o.optString("y"));
            // Keep the system keyboard from covering our own.
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && engine != null) imm.hideSoftInputFromWindow(engine.getWindowToken(), 0);
            osk.open(hint.length() > 0 ? hint : "Type text", o.optString("v", ""),
                    pw ? OskOverlay.PASSWORD : OskOverlay.TEXT, new OskOverlay.Callback() {
                        @Override
                        public void onResult(String text, boolean submit) { applyText(text, submit); }

                        @Override
                        public void onCancel() { }
                    });
        } catch (JSONException ignored) {
        }
    }

    private void applyText(String text, final boolean submit) {
        if (engine == null) return;
        engine.evaluateJavascript(Web.setText(text), null);
        if (submit) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    engine.requestFocus();
                    long t = SystemClock.uptimeMillis();
                    engine.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0));
                    engine.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0));
                }
            }, 80);
        }
    }

    private void openUrlBar() {
        Tab t = currentTab();
        String cur = t == null || Urls.isInternal(t.url) ? "" : t.url;
        osk.open("Go to address or search", cur, OskOverlay.URL, new OskOverlay.Callback() {
            @Override
            public void onResult(String text, boolean submit) {
                String s = text.trim();
                if (s.length() > 0) loadUrl(Urls.resolve(s, Prefs.SEARCH_URLS[prefs.search()]));
            }

            @Override
            public void onCancel() { }
        });
    }

    // ------------------------------------------------------------------------------------ certificate prompt

    private void askSsl(final ValueCallback<Boolean> callback, SslError error) {
        final String host = Urls.host(error.getUrl());
        if (sslAllowed.contains(host)) {
            callback.onReceiveValue(Boolean.TRUE);
            return;
        }
        ArrayList<ListOverlay.Item> l = new ArrayList<ListOverlay.Item>();
        l.add(ListOverlay.Item.info("Site", host));
        l.add(ListOverlay.Item.info("Problem", sslReason(error)));
        l.add(ListOverlay.Item.info("Certificate roots", CertTrust.status));
        ListOverlay.Item back = new ListOverlay.Item("Go back (safe)");
        back.action = new ListOverlay.Action() {
            @Override
            public void run(ListOverlay.Item it) {
                list.close();
                callback.onReceiveValue(Boolean.FALSE);
            }
        };
        ListOverlay.Item go = new ListOverlay.Item("Proceed anyway (unsafe, this session only)");
        go.action = new ListOverlay.Action() {
            @Override
            public void run(ListOverlay.Item it) {
                list.close();
                sslAllowed.add(host);
                callback.onReceiveValue(Boolean.TRUE);
            }
        };
        l.add(back);
        l.add(go);
        list.open("Connection is not trusted", l, 3, "O Select   A Go back", new Runnable() {
            @Override
            public void run() { callback.onReceiveValue(Boolean.FALSE); }
        });
    }

    private static String sslReason(SslError e) {
        switch (e.getPrimaryError()) {
            case SslError.SSL_UNTRUSTED:
                return "Certificate authority not trusted (the OUYA's certificate list is old)";
            case SslError.SSL_EXPIRED:
                return "Certificate has expired";
            case SslError.SSL_IDMISMATCH:
                return "Certificate belongs to a different site";
            case SslError.SSL_NOTYETVALID:
            case SslError.SSL_DATE_INVALID:
                return "Certificate date is invalid - check the OUYA's date and time";
            default:
                return "Certificate is invalid";
        }
    }

    // ------------------------------------------------------------------------------------ menus

    private ListOverlay.Item go(String label, final Runnable r) {
        ListOverlay.Item it = new ListOverlay.Item(label);
        it.action = new ListOverlay.Action() {
            @Override
            public void run(ListOverlay.Item i) {
                list.close();
                r.run();
            }
        };
        return it;
    }

    private ListOverlay.Item choice(String label, String value, ListOverlay.Adjust a) {
        ListOverlay.Item it = new ListOverlay.Item(label, value);
        it.adjust = a;
        return it;
    }

    private static int wrap(int v, int n) {
        return ((v % n) + n) % n;
    }

    private void openMenu() {
        ArrayList<ListOverlay.Item> l = new ArrayList<ListOverlay.Item>();
        l.add(go("Go to address / search", new Runnable() { public void run() { openUrlBar(); } }));
        l.add(go("Home", new Runnable() { public void run() { loadUrl(prefs.home()); } }));
        l.add(go("Reload", new Runnable() { public void run() { reload(); } }));
        if (canGoBack()) l.add(go("Back", new Runnable() { public void run() { historyBack(); } }));
        if (canGoForward()) l.add(go("Forward", new Runnable() { public void run() { historyForward(); } }));
        l.add(go("New tab", new Runnable() { public void run() { newTab(prefs.home()); } }));
        l.add(go("Tabs (" + tabs.size() + ")", new Runnable() { public void run() { openTabs(active); } }));
        l.add(go("Bookmarks", new Runnable() { public void run() { openBookmarks(0); } }));
        l.add(go("History", new Runnable() { public void run() { openHistory(); } }));
        l.add(go("Settings", new Runnable() { public void run() { openSettings(0); } }));
        l.add(go("Controls", new Runnable() { public void run() { openHelp(); } }));
        l.add(go("Exit browser", new Runnable() { public void run() { finish(); } }));
        list.open("OUYA Browser", l, 0, "O Select   A / Y Close", null);
    }

    private void openTabs(int sel) {
        ArrayList<ListOverlay.Item> l = new ArrayList<ListOverlay.Item>();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            String name = t.title.length() > 0 ? t.title : t.url;
            ListOverlay.Item it = new ListOverlay.Item((i + 1) + ".  " + name, i == active ? "current" : Urls.host(t.url));
            it.action = new ListOverlay.Action() {
                @Override
                public void run(ListOverlay.Item x) {
                    list.close();
                    switchTo(idx);
                }
            };
            it.secondary = new ListOverlay.Action() {
                @Override
                public void run(ListOverlay.Item x) {
                    closeTab(idx);
                    openTabs(Math.max(0, idx - 1));
                }
            };
            l.add(it);
        }
        l.add(go("+ New tab", new Runnable() { public void run() { newTab(prefs.home()); } }));
        list.open("Tabs", l, sel, "O Switch   U Close tab   A Back", new Runnable() {
            public void run() { openMenu(); }
        });
    }

    private void openBookmarks(int sel) {
        ArrayList<ListOverlay.Item> l = new ArrayList<ListOverlay.Item>();
        l.add(go("+ Bookmark this page", new Runnable() {
            public void run() {
                Tab t = currentTab();
                if (t == null || Urls.isInternal(t.url)) {
                    hud.toast("Nothing to bookmark on this page");
                    return;
                }
                captureTab();
                store.addBookmark(t.title, t.url);
                hud.toast("Bookmarked");
            }
        }));
        List<Store.Entry> bms = store.bookmarks();
        for (int i = 0; i < bms.size(); i++) {
            final Store.Entry e = bms.get(i);
            ListOverlay.Item it = new ListOverlay.Item(e.title.length() > 0 ? e.title : e.url, Urls.host(e.url));
            it.action = new ListOverlay.Action() {
                @Override
                public void run(ListOverlay.Item x) {
                    list.close();
                    loadUrl(e.url);
                }
            };
            it.secondary = new ListOverlay.Action() {
                @Override
                public void run(ListOverlay.Item x) {
                    int s = list.selected();
                    store.removeBookmark(e.url);
                    openBookmarks(Math.max(0, s - 1));
                }
            };
            l.add(it);
        }
        list.open("Bookmarks", l, sel, "O Open   U Remove   A Back", new Runnable() {
            public void run() { openMenu(); }
        });
    }

    private void openHistory() {
        ArrayList<ListOverlay.Item> l = new ArrayList<ListOverlay.Item>();
        List<Store.Entry> hist = store.history();
        for (int i = 0; i < hist.size(); i++) {
            final Store.Entry e = hist.get(i);
            ListOverlay.Item it = new ListOverlay.Item(e.title.length() > 0 ? e.title : e.url, Urls.host(e.url));
            it.action = new ListOverlay.Action() {
                @Override
                public void run(ListOverlay.Item x) {
                    list.close();
                    loadUrl(e.url);
                }
            };
            l.add(it);
        }
        if (hist.isEmpty()) l.add(ListOverlay.Item.info("No history yet", ""));
        else l.add(go("Clear history", new Runnable() {
            public void run() {
                store.clearHistory();
                hud.toast("History cleared");
            }
        }));
        list.open("History", l, 0, "O Open   A Back", new Runnable() {
            public void run() { openMenu(); }
        });
    }

    private void openSettings(int sel) {
        ArrayList<ListOverlay.Item> l = new ArrayList<ListOverlay.Item>();
        l.add(choice("Search engine", Prefs.SEARCH_NAMES[prefs.search()], new ListOverlay.Adjust() {
            public void adjust(ListOverlay.Item it, int dir) {
                int i = wrap(prefs.search() + dir, Prefs.SEARCH_NAMES.length);
                prefs.search(i);
                it.value = Prefs.SEARCH_NAMES[i];
            }
        }));
        l.add(choice("Site mode (next page)", prefs.desktop() ? "Desktop" : "Mobile", new ListOverlay.Adjust() {
            public void adjust(ListOverlay.Item it, int dir) {
                prefs.desktop(!prefs.desktop());
                applyUserAgent();
                it.value = prefs.desktop() ? "Desktop" : "Mobile";
            }
        }));
        l.add(choice("Page zoom", Prefs.ZOOMS[prefs.zoom()] + "%", new ListOverlay.Adjust() {
            public void adjust(ListOverlay.Item it, int dir) {
                int i = wrap(prefs.zoom() + dir, Prefs.ZOOMS.length);
                prefs.zoom(i);
                zoomApplied = true;
                injectPageScripts();
                it.value = Prefs.ZOOMS[i] + "%";
            }
        }));
        l.add(choice("Cursor speed", Prefs.SPEED_NAMES[prefs.speed()], new ListOverlay.Adjust() {
            public void adjust(ListOverlay.Item it, int dir) {
                int i = wrap(prefs.speed() + dir, Prefs.SPEED.length);
                prefs.speed(i);
                it.value = Prefs.SPEED_NAMES[i];
            }
        }));
        l.add(choice("Screen safe area (restart)", Prefs.SAFE[prefs.safe()] + "%", new ListOverlay.Adjust() {
            public void adjust(ListOverlay.Item it, int dir) {
                int i = wrap(prefs.safe() + dir, Prefs.SAFE.length);
                prefs.safe(i);
                it.value = Prefs.SAFE[i] + "%";
            }
        }));
        l.add(choice("Hover effects", prefs.hover() ? "On" : "Off", new ListOverlay.Adjust() {
            public void adjust(ListOverlay.Item it, int dir) {
                prefs.hover(!prefs.hover());
                it.value = prefs.hover() ? "On" : "Off";
            }
        }));
        l.add(choice("Scroll method", prefs.jsScroll() ? "JavaScript" : "Mouse wheel", new ListOverlay.Adjust() {
            public void adjust(ListOverlay.Item it, int dir) {
                prefs.jsScroll(!prefs.jsScroll());
                it.value = prefs.jsScroll() ? "JavaScript" : "Mouse wheel";
            }
        }));
        l.add(choice("Web text fields keyboard", prefs.sysKeyboard() ? "System" : "Built-in", new ListOverlay.Adjust() {
            public void adjust(ListOverlay.Item it, int dir) {
                prefs.sysKeyboard(!prefs.sysKeyboard());
                it.value = prefs.sysKeyboard() ? "System" : "Built-in";
            }
        }));
        l.add(choice("Rendering (restart)", prefs.texture() ? "Compatible" : "Fast", new ListOverlay.Adjust() {
            public void adjust(ListOverlay.Item it, int dir) {
                prefs.texture(!prefs.texture());
                it.value = prefs.texture() ? "Compatible" : "Fast";
            }
        }));
        l.add(go("Use this page as home", new Runnable() {
            public void run() {
                captureTab();
                Tab t = currentTab();
                if (t != null && !Urls.isInternal(t.url)) {
                    prefs.home(t.url);
                    hud.toast("Home page set");
                } else {
                    prefs.home(Prefs.HOME_DEFAULT);
                    hud.toast("Home page reset to the start page");
                }
            }
        }));
        l.add(go("Clear cache", new Runnable() {
            public void run() {
                if (engine != null) engine.clearCache(true);
                hud.toast("Cache cleared");
            }
        }));
        l.add(go("Clear cookies", new Runnable() {
            public void run() {
                clearCookies();
                hud.toast("Cookies cleared");
            }
        }));
        Object ver = Safe.call(engine, "getXWalkVersion", new Class<?>[0]);
        l.add(ListOverlay.Item.info("Engine", "Crosswalk " + (ver == null ? "?" : ver) + " (Chromium 53)"));
        l.add(ListOverlay.Item.info("Certificates", CertTrust.status));
        list.open("Settings", l, sel, "O / Left / Right Change   A Back", new Runnable() {
            public void run() { openMenu(); }
        });
    }

    private void clearCookies() {
        try {
            Object cm = Class.forName("org.xwalk.core.XWalkCookieManager").getConstructor().newInstance();
            Safe.call(cm, "removeAllCookie", new Class<?>[0]);
        } catch (Throwable t) {
            Log.w(TAG, "clear cookies: " + t);
        }
    }

    private void openHelp() {
        ArrayList<ListOverlay.Item> l = new ArrayList<ListOverlay.Item>();
        l.add(ListOverlay.Item.info("Left stick", "Move the cursor"));
        l.add(ListOverlay.Item.info("Right stick", "Scroll the page"));
        l.add(ListOverlay.Item.info("O", "Click (hold and move to drag)"));
        l.add(ListOverlay.Item.info("U", "Address bar / search"));
        l.add(ListOverlay.Item.info("Y", "Menu"));
        l.add(ListOverlay.Item.info("A", "Back / close tab / exit"));
        l.add(ListOverlay.Item.info("L1 / R1", "Previous / next tab"));
        l.add(ListOverlay.Item.info("L2 / R2", "Page up / page down"));
        l.add(ListOverlay.Item.info("D-pad up / down", "Scroll"));
        l.add(ListOverlay.Item.info("D-pad left / right", "History back / forward"));
        l.add(ListOverlay.Item.info("L3 (stick click)", "Precision cursor"));
        l.add(ListOverlay.Item.info("R3 (stick click)", "Cycle page zoom"));
        l.add(ListOverlay.Item.info("Text fields", "Click one to open the keyboard"));
        l.add(ListOverlay.Item.info("Touchpad / USB mouse", "Works too"));
        list.open("Controls", l, 0, "A Back", new Runnable() {
            public void run() { openMenu(); }
        });
    }
}
