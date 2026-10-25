package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * "Buscar actualización": the latest GitHub release of this repo, installed through the TV's own ADB when it's newer.
 * Releases are built without a TMDB key; the key lives in the TV's settings (Prefs.tmdbKey), so it survives. An APK
 * signed with another key can't update this one: Android refuses it, and the toast says so.
 */
final class Updater {
    static final String REPO = "juandiegoandres/appletv-home-onn";
    private static volatile boolean busy;

    private Updater() {}

    static boolean busy() { return busy; }

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static void check(Context c, Consumer<String> toast, Runnable done) {
        if (busy) return;
        busy = true;
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> toast.accept("Buscando actualización…"));
        new Thread(() -> {
            String msg;
            try {
                JSONObject release = new JSONObject(read(new URL("https://api.github.com/repos/" + REPO + "/releases/latest")));
                String latest = release.optString("tag_name").replaceFirst("^v", ""), mine = version(app);
                if (compare(latest, mine) <= 0) {
                    msg = "Ya tienes la última versión (" + mine + ")";
                } else {
                    String url = null;
                    JSONArray assets = release.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url");
                    }
                    if (url == null) throw new Exception("la versión " + latest + " no trae APK");
                    main.post(() -> toast.accept("Descargando la versión " + latest + "…"));
                    File apk = download(app, url);
                    try (Adb adb = Adb.connect(app, 10_000, null)) {
                        String out = adb.shell("cat '" + apk.getPath() + "' > /data/local/tmp/update.apk && pm install -r /data/local/tmp/update.apk; rm -f /data/local/tmp/update.apk");
                        //noinspection ResultOfMethodCallIgnored
                        apk.delete();
                        if (out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) throw new Exception("esa versión está firmada con otra clave");
                        if (!out.contains("Success")) throw new Exception("no se pudo instalar");
                    }
                    msg = "Actualizado a la versión " + latest;
                }
            } catch (Exception e) {
                msg = "No se pudo actualizar: " + e.getMessage();
            } finally {
                busy = false;
            }
            String m = msg;
            main.post(() -> {
                toast.accept(m);
                done.run();
            });
        }, "updater").start();
    }

    /** "2.10" > "2.9": compared number by number. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        String d = s.replaceAll("[^0-9]", "");
        return d.isEmpty() ? 0 : Integer.parseInt(d);
    }

    private static File download(Context c, String url) throws Exception {
        File dir = c.getExternalFilesDir("downloads");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "update.apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        return f;
    }

    private static String read(URL url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
