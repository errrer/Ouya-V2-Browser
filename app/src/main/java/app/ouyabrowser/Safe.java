package app.ouyabrowser;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Reflection helper for engine calls whose exact signature we could not verify at build time.
 * A failure here must never crash the browser, so everything is swallowed and logged.
 */
final class Safe {
    private Safe() {}

    static Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name, types);
            return m.invoke(target, args);
        } catch (Throwable t) {
            Log.w("OuyaBrowser", "reflective call " + name + " failed: " + t);
            return null;
        }
    }

    static Object callStatic(String className, String name, Class<?>[] types, Object... args) {
        try {
            Method m = Class.forName(className).getMethod(name, types);
            return m.invoke(null, args);
        } catch (Throwable t) {
            Log.w("OuyaBrowser", "reflective static call " + className + "." + name + " failed: " + t);
            return null;
        }
    }
}
