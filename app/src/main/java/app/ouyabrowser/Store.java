package app.ouyabrowser;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/** Bookmarks and history as two tiny tab-separated text files. Kept in memory, written on flush(). */
final class Store {
    static final class Entry {
        final String title;
        final String url;
        Entry(String title, String url) { this.title = title; this.url = url; }
    }

    private static final int MAX_HISTORY = 100;

    private final File bmFile;
    private final File hiFile;
    private final ArrayList<Entry> bookmarks = new ArrayList<Entry>();
    private final ArrayList<Entry> history = new ArrayList<Entry>();
    private boolean bmDirty;
    private boolean hiDirty;

    Store(Context c) {
        File dir = c.getFilesDir();
        bmFile = new File(dir, "bookmarks.txt");
        hiFile = new File(dir, "history.txt");
        if (bmFile.exists()) {
            load(bmFile, bookmarks);
        } else {
            bookmarks.add(new Entry("GitHub", "https://github.com/"));
            bookmarks.add(new Entry("DuckDuckGo", "https://html.duckduckgo.com/html/"));
            bookmarks.add(new Entry("Wikipedia", "https://en.wikipedia.org/"));
            bookmarks.add(new Entry("Hacker News", "https://news.ycombinator.com/"));
            bmDirty = true;
        }
        load(hiFile, history);
    }

    List<Entry> bookmarks() { return bookmarks; }
    List<Entry> history() { return history; }

    boolean isBookmarked(String url) {
        for (Entry e : bookmarks) if (e.url.equals(url)) return true;
        return false;
    }

    void addBookmark(String title, String url) {
        if (isBookmarked(url)) return;
        bookmarks.add(new Entry(clean(title), url));
        bmDirty = true;
        flush();
    }

    void removeBookmark(String url) {
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).url.equals(url)) {
                bookmarks.remove(i);
                bmDirty = true;
                break;
            }
        }
        flush();
    }

    void addHistory(String title, String url) {
        if (Urls.isInternal(url)) return;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).url.equals(url)) { history.remove(i); break; }
        }
        history.add(0, new Entry(clean(title), url));
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        hiDirty = true;
    }

    void clearHistory() {
        history.clear();
        hiDirty = true;
        flush();
    }

    void flush() {
        if (bmDirty) { save(bmFile, bookmarks); bmDirty = false; }
        if (hiDirty) { save(hiFile, history); hiDirty = false; }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void load(File f, List<Entry> out) {
        if (!f.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t < 0) continue;
                out.add(new Entry(line.substring(0, t), line.substring(t + 1)));
            }
        } catch (IOException ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) { }
        }
    }

    private static void save(File f, List<Entry> list) {
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            for (Entry e : list) {
                w.write(e.title);
                w.write('\t');
                w.write(e.url);
                w.write('\n');
            }
        } catch (IOException ignored) {
        } finally {
            if (w != null) try { w.close(); } catch (IOException ignored) { }
        }
    }
}
