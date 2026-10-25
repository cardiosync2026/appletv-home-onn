package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.function.Consumer;

/**
 * Two things done through the TV's own ADB (as HomeMode does):
 *  - "Ver en <app>": for a streaming app with no search links, the search is driven like a person would: open the
 *    app, tap its search button, clear the field, tap the title's letters on the app's own on-screen keyboard, search,
 *    open the first result. Each step checks the screen first and stops if the app isn't where it should be, so nothing
 *    is ever typed into another app. The app and its screen positions come from app/assets/search-app.json (yours,
 *    never committed; see search-app.example.json and the README). Without that file the button doesn't show.
 *  - "Liberar memoria": force-stops the apps you installed (not this one) and lets Android drop its cached processes.
 */
final class AppSearch {
    private static volatile boolean busy;
    private static JSONObject config;
    private static boolean loaded;

    private AppSearch() {}

    /** The app's name for the button ("Ver en …"), or null when no search-app.json is set up. */
    static String label(Context c) {
        JSONObject cfg = config(c);
        return cfg == null ? null : cfg.optString("label", null);
    }

    private static synchronized JSONObject config(Context c) {
        if (loaded) return config;
        loaded = true;
        try (InputStream in = c.getAssets().open("search-app.json")) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            config = new JSONObject(new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8));
        } catch (Exception e) {
            config = null;
        }
        return config;
    }

    static void run(Context c, String title, Consumer<String> toast) {
        JSONObject cfg = config(c);
        if (cfg == null || busy) return;
        busy = true;
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        String name = cfg.optString("label");
        main.post(() -> toast.accept("Buscando «" + title + "» en " + name + "…"));
        new Thread(() -> {
            String fail = null;
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                String pkg = cfg.getString("package");
                adb.shell("am start -n " + pkg + "/" + cfg.getString("launch_activity"));
                if (!waitFor(adb, pkg + "/" + cfg.getString("home_activity"), 25_000)) {
                    fail = "la app no abrió a tiempo";
                } else {
                    adb.shell("input tap " + xy(cfg, "search_button"));
                    String search = pkg + "/" + cfg.getString("search_activity");
                    if (!waitFor(adb, search, 8_000)) {
                        fail = "no encontré el buscador";
                    } else {
                        StringBuilder cmd = new StringBuilder("input tap " + xy(cfg, "clear") + "; sleep 0.4");
                        for (char ch : letters(title).toCharArray()) {
                            String k = key(cfg, ch);
                            if (k != null) cmd.append("; input tap ").append(k).append("; sleep 0.2");
                        }
                        cmd.append("; sleep 0.3; input tap ").append(xy(cfg, "submit"));
                        if (top(adb).contains(search)) adb.shell(cmd.toString());
                        Thread.sleep(3500);
                        if (cfg.has("first_result") && top(adb).contains(search)) adb.shell("input tap " + xy(cfg, "first_result"));
                    }
                }
            } catch (Exception e) {
                fail = e.getMessage();
            } finally {
                busy = false;
            }
            if (fail != null) {
                String msg = name + ": " + fail;
                main.post(() -> toast.accept(msg));
            }
        }, "app-search").start();
    }

    static void freeMemory(Context c, Consumer<String> toast) {
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String msg;
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                long before = available(adb);
                adb.shell("for p in $(pm list packages -3 | cut -d: -f2); do case $p in io.freemytv) ;; *) am force-stop $p ;; esac; done; am kill-all");
                Thread.sleep(1500);
                long freed = Math.max(0, available(adb) - before);
                msg = "Memoria liberada: " + freed / 1024 + " MB";
            } catch (Exception e) {
                msg = "No pude liberar memoria: " + e.getMessage();
            }
            String m = msg;
            main.post(() -> toast.accept(m));
        }, "free-memory").start();
    }

    /** MemAvailable, in kB. */
    private static long available(Adb adb) throws Exception {
        String digits = adb.shell("grep MemAvailable /proc/meminfo").replaceAll("[^0-9]", "");
        return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }

    private static String top(Adb adb) throws Exception {
        return adb.shell("dumpsys activity activities | grep -m1 topResumedActivity");
    }

    private static boolean waitFor(Adb adb, String activity, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (top(adb).contains(activity)) {
                Thread.sleep(1200);     // let the screen settle before tapping
                return true;
            }
            Thread.sleep(700);
        }
        return false;
    }

    private static String xy(JSONObject cfg, String name) throws Exception {
        JSONArray p = cfg.getJSONArray(name);
        return p.getInt(0) + " " + p.getInt(1);
    }

    /** The title in keyboard letters: capitals, no punctuation, the first 22 characters. */
    static String letters(String title) {
        StringBuilder out = new StringBuilder();
        for (char ch : title.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if ("ÁÉÍÓÚÜÑ".indexOf(ch) >= 0 || (ch >= 'A' && ch <= 'Z')) {
                out.append(ch);
            } else if (ch == ' ') {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
            } else {
                String base = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD);
                char b = base.isEmpty() ? 0 : base.charAt(0);
                if (b >= 'A' && b <= 'Z') out.append(b);
            }
        }
        String s = out.toString().trim();
        return s.length() > 22 ? s.substring(0, 22).trim() : s;
    }

    /** Where a letter is on the app's keyboard: rows of letters from a first key, a fixed step apart. */
    private static String key(JSONObject cfg, char ch) throws Exception {
        if (ch == ' ') return cfg.has("space") ? xy(cfg, "space") : null;
        JSONObject kb = cfg.getJSONObject("keyboard");
        JSONArray rows = kb.getJSONArray("rows"), first = kb.getJSONArray("first_key"), step = kb.getJSONArray("step");
        for (int r = 0; r < rows.length(); r++) {
            int i = rows.getString(r).indexOf(ch);
            if (i >= 0) return (first.getInt(0) + i * step.getInt(0)) + " " + (first.getInt(1) + r * step.getInt(1));
        }
        return null;
    }
}
