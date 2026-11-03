package app.ouyabrowser;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** JavaScript snippets run inside pages, and the built-in error page. */
final class Web {
    private Web() {}

    /** Records the time of every click so we only react to fields the user just clicked. Idempotent. */
    static final String INSTALL =
            "(function(){if(window.__ob)return;window.__ob=1;window.__obT=0;"
            + "document.addEventListener('click',function(){window.__obT=Date.now();},true);})()";

    /** Returns a JSON description of the focused text field if it was clicked within the last 800 ms, else ''. */
    static final String ACTIVE_FIELD =
            "(function(){if(!window.__ob||Date.now()-window.__obT>800)return '';"
            + "var e=document.activeElement;if(!e||e===document.body)return '';"
            + "var t=(e.tagName||'').toLowerCase(),y=(e.type||'').toLowerCase();"
            + "var ok=t==='textarea'||(t==='input'&&/^(|text|search|url|email|password|tel|number)$/.test(y))||e.isContentEditable;"
            + "if(!ok)return '';"
            + "var v=e.isContentEditable?(e.innerText||''):(e.value||'');"
            + "return JSON.stringify({t:t,y:y,v:v,p:e.getAttribute('placeholder')||e.getAttribute('aria-label')||''});})()";

    /** Replaces the value of the focused field and fires the events frameworks listen for. */
    static String setText(String text) {
        return "(function(t){var e=document.activeElement;if(!e)return;"
                + "if(e.isContentEditable){e.focus();document.execCommand('selectAll',false,null);"
                + "document.execCommand('insertText',false,t);return;}"
                + "var P=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var d=Object.getOwnPropertyDescriptor(P,'value');"
                + "if(d&&d.set){d.set.call(e,t);}else{e.value=t;}"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));})(" + JSONObject.quote(text) + ")";
    }

    static String scrollPage(int dirDown) {
        return "window.scrollBy(0," + dirDown + "*Math.round(window.innerHeight*0.85))";
    }

    static String scrollBy(int dx, int dy) {
        return "window.scrollBy(" + dx + "," + dy + ")";
    }

    /** CSS zoom applied to the document element; 100 removes it. */
    static String zoom(int percent) {
        String z = percent == 100 ? "''" : "'" + (percent / 100f) + "'";
        return "(function(){var d=document.documentElement;if(d)d.style.zoom=" + z + ";})()";
    }

    /** evaluateJavascript hands back the result JSON-encoded; this unwraps a string result. */
    static String unquote(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() < 2 || "null".equals(s)) return "";
        try {
            Object o = new JSONTokener(s).nextValue();
            return o instanceof String ? (String) o : "";
        } catch (JSONException e) {
            return "";
        }
    }

    static String errorPage(String description, String url) {
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;padding:8vh 8vw;background:#15151a;color:#eee;font:3vw sans-serif}"
                + "h1{color:#ff7a1a;font-size:5vw;margin:0 0 2vh}p{color:#aab;word-break:break-all}"
                + "small{color:#889;font-size:2.2vw}</style></head><body>"
                + "<h1>Can't open this page</h1><p>" + esc(description) + "</p><p>" + esc(url) + "</p>"
                + "<small>Check the network and the OUYA's date and time, then press Y &gt; Reload.</small>"
                + "</body></html>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }
}
