package app.ouyabrowser;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

final class Urls {
    private Urls() {}

    /** Turns whatever was typed into a loadable URL: a real URL, a bare host, or a search query. */
    static String resolve(String input, String searchTemplate) {
        String s = input.trim();
        if (s.length() == 0) return s;
        if (hasScheme(s)) return s;
        boolean noSpace = s.indexOf(' ') < 0;
        if (noSpace && (s.startsWith("localhost") || isIpish(s))) return "http://" + s;
        if (noSpace && s.indexOf('.') > 0 && !s.endsWith(".")) return "https://" + s;
        try {
            return searchTemplate.replace("%s", URLEncoder.encode(s, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            return searchTemplate.replace("%s", s);
        }
    }

    private static boolean hasScheme(String s) {
        int i = s.indexOf(':');
        if (i < 2) return false;
        for (int k = 0; k < i; k++) {
            char c = s.charAt(k);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (k > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!ok) return false;
        }
        // "example.com:8080" has a colon but no scheme; real schemes are followed by '/' or are about:/data:/file:.
        String rest = s.substring(i + 1);
        return rest.startsWith("//") || s.startsWith("about:") || s.startsWith("data:")
                || s.startsWith("file:") || s.startsWith("javascript:");
    }

    private static boolean isIpish(String s) {
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == ':') { end = i; break; }
        }
        if (end == 0) return false;
        int dots = 0;
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            if (c == '.') dots++;
            else if (c < '0' || c > '9') return false;
        }
        return dots == 3;
    }

    /** Host part of a URL, or "" when there is none (file:, about:, null). */
    static String host(String url) {
        if (url == null) return "";
        int i = url.indexOf("://");
        if (i < 0) return "";
        int start = i + 3;
        int end = url.length();
        for (int k = start; k < url.length(); k++) {
            char c = url.charAt(k);
            if (c == '/' || c == '?' || c == '#') { end = k; break; }
        }
        String h = url.substring(start, end);
        int at = h.lastIndexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) h = h.substring(0, colon);
        return h;
    }

    static boolean isSecure(String url) {
        return url != null && url.startsWith("https://");
    }

    static boolean isInternal(String url) {
        return url == null || url.startsWith("file:///android_asset") || url.equals("about:blank");
    }
}
