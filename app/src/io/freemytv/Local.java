package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your own additions, kept out of the repo (app/assets/local/ is gitignored): extra shelf apps with their tile colour
 * and logo, tile styles that override the built-in ones, and the shelf to start with.
 *
 * app/assets/local/apps.json:
 *   {"apps": [{"key": "myapp", "label": "My App", "package": "com.example", "tile": "1B1D2B", "mode": 0, "scale": 0.62}],
 *    "tiles": {"youtube": ["FFFFFF", 0]},
 *    "shelf": ["com.example", "com.google.android.youtube.tv"]}
 * app/assets/local/logos/<key>.png: the logo drawn on that app's tile (white, or in its colours on a plain tile).
 * "mode": 0 = the logo as it is, 1 = the logo in white.
 */
final class Local {
    private static boolean loaded;
    private static final List<String[]> apps = new ArrayList<>();
    private static final Map<String, int[]> tiles = new HashMap<>();
    private static List<String> shelf;

    private static Context app;

    private Local() {}

    /** Called once per process (App.onCreate): reads the file with the app's context. */
    static void init(Context c) {
        app = c.getApplicationContext();
        load(app);
    }

    private static synchronized void load(Context c) {
        if (loaded || c == null) return;
        loaded = true;
        try (InputStream in = c.getAssets().open("local/apps.json")) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            JSONObject o = new JSONObject(new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray("apps");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                String key = x.getString("key");
                apps.add(new String[] {key, x.optString("label", key), x.getString("package"), x.optString("tile", "1B1D2B"),
                        "", String.valueOf(x.optDouble("scale", 1))});
                tiles.put(key, style(x.optString("tile", "1B1D2B"), x.optInt("mode", 0)));
            }
            JSONObject t = o.optJSONObject("tiles");
            if (t != null) for (java.util.Iterator<String> it = t.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONArray v = t.getJSONArray(key);
                tiles.put(key, style(v.getString(0), v.optInt(1, 0)));
            }
            JSONArray s = o.optJSONArray("shelf");
            if (s != null) {
                shelf = new ArrayList<>();
                for (int i = 0; i < s.length(); i++) shelf.add(s.getString(i));
            }
        } catch (Exception ignored) {
            // No local file: the built-in look.
        }
    }

    private static int[] style(String hex, int mode) {
        int color = 0xFF000000 | Integer.parseInt(hex.replace("#", ""), 16);
        return new int[] {color, color, mode};
    }

    /** Extra shelf apps, in SHELF's row format: {key, label, package, colour, mode, scale}. */
    static List<String[]> apps(Context c) {
        load(c != null ? c : app);
        return apps;
    }

    /** A tile style for this app key ({top, bottom, logo mode}), or null for the built-in one. */
    static int[] tile(Context c, String key) {
        load(c != null ? c : app);
        return tiles.get(key);
    }

    /** The shelf to start with, or null. */
    static List<String> shelf(Context c) {
        load(c != null ? c : app);
        return shelf;
    }
}
