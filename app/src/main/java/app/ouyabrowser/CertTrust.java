package app.ouyabrowser;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

/**
 * Android 4.1.2 ships a 2012 root certificate list, so many current HTTPS sites (anything chaining to
 * ISRG Root X1, DigiCert G2, GTS and others) fail with "not trusted". This registers an up-to-date set
 * of public root certificates (assets/roots.pem) with Chromium's certificate verifier.
 *
 * It uses X509Util.addTestRootCertificate, an internal Chromium hook that Crosswalk 23 still contains.
 * That is reached by reflection and is best-effort: if it is missing the browser still works and the
 * per-host "proceed anyway" prompt in MainActivity takes over. The outcome is shown in Settings.
 */
final class CertTrust {
    private static final String TAG = "OuyaBrowser";
    static String status = "not started";

    private CertTrust() {}

    static void install(Context ctx) {
        int total = 0;
        int added = 0;
        InputStream in = null;
        try {
            Class<?> util = Class.forName("org.chromium.net.X509Util");
            Method add = util.getMethod("addTestRootCertificate", byte[].class);
            in = ctx.getAssets().open("roots.pem");
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            for (Certificate c : certs) {
                total++;
                try {
                    add.invoke(null, (Object) c.getEncoded());
                    added++;
                } catch (Throwable t) {
                    Log.w(TAG, "root cert rejected: " + t);
                }
            }
            status = "added " + added + " of " + total + " extra root certificates";
        } catch (Throwable t) {
            status = "unavailable (" + t.getClass().getSimpleName() + "); using device certificate list";
            Log.w(TAG, "CertTrust: " + t);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
        Log.i(TAG, "CertTrust: " + status);
    }
}
