package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The two "no ads" switches in Settings, both done through the TV's own ADB and both reversible:
 *  - Ad-blocking DNS: Android's Private DNS set to AdGuard's resolver. It blocks ads and trackers in other apps, but
 *    not YouTube's, which come from the same servers as the videos. Off: back to Android's automatic DNS.
 *  - YouTube without ads: installs SmartTube (open source, github.com/yuliskov/SmartTube) and puts it on the shelf in
 *    YouTube's place. Off: YouTube goes back and SmartTube is uninstalled.
 */
final class AdBlock {
    static final String DNS_HOST = "dns.adguard-dns.com";
    /** SmartTube's stable package: the new name first, then the one older releases used. */
    static final String[] SMARTTUBE = {"org.smarttube.stable", "com.teamsmart.videomanager.tv"};
    static final String YOUTUBE = "com.google.android.youtube.tv";
    private static volatile boolean busy;

    private AdBlock() {}

    static boolean busy() { return busy; }

    static boolean dnsOn(Context c) {
        String mode = Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
        String host = Settings.Global.getString(c.getContentResolver(), "private_dns_specifier");
        return "hostname".equals(mode) && DNS_HOST.equals(host);
    }

    static boolean youtubeNoAds(Context c) {
        return smartTube(c) != null;
    }

    /** The installed SmartTube's package, or null. */
    static String smartTube(Context c) {
        for (String pkg : SMARTTUBE) {
            try {
                c.getPackageManager().getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    static void toggleDns(Context c, Consumer<String> toast, Runnable done) {
        boolean on = !dnsOn(c);
        run(c, toast, done, adb -> {
            if (on) {
                adb.shell("settings put global private_dns_specifier " + DNS_HOST + "; settings put global private_dns_mode hostname");
                return "Anuncios bloqueados con DNS (no aplica a YouTube)";
            }
            adb.shell("settings put global private_dns_mode opportunistic");
            return "DNS normal otra vez";
        });
    }

    static void toggleYoutube(Context c, Consumer<String> toast, Runnable done) {
        boolean on = !youtubeNoAds(c);
        Context app = c.getApplicationContext();
        if (on) new Handler(Looper.getMainLooper()).post(() -> toast.accept("Descargando SmartTube… (1 minuto)"));
        run(c, toast, done, adb -> {
            if (on) {
                File apk = download(app);
                String out = adb.shell("cat '" + apk.getPath() + "' > /data/local/tmp/smarttube.apk && pm install -r /data/local/tmp/smarttube.apk; rm -f /data/local/tmp/smarttube.apk");
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                if (!out.contains("Success")) throw new Exception("no se pudo instalar SmartTube");
                String pkg = null;
                for (String p : SMARTTUBE) if (pkg == null && adb.shell("pm path " + p).contains("package:")) pkg = p;
                if (pkg == null) throw new Exception("SmartTube no aparece instalado");
                swapOnShelf(app, YOUTUBE, pkg);
                return "YouTube sin anuncios: SmartTube quedó en el estante";
            }
            String pkg = smartTube(app);
            if (pkg != null) {
                swapOnShelf(app, pkg, YOUTUBE);
                adb.shell("pm uninstall " + pkg);
            }
            return "YouTube normal otra vez";
        });
    }

    /** The shelf with `from` replaced by `to` (or `to` added at the end when `from` isn't there). */
    private static void swapOnShelf(Context c, String from, String to) {
        List<String> shelf = new ArrayList<>(HomeApps.shelfPackages(c));
        shelf.remove(to);
        int at = shelf.indexOf(from);
        if (at >= 0) shelf.set(at, to);
        else shelf.add(to);
        Prefs.setShelf(shelf);
    }

    /** SmartTube's latest stable release for this TV's processor, from its GitHub releases. */
    private static File download(Context c) throws Exception {
        String abi = Build.SUPPORTED_ABIS.length > 0 && Build.SUPPORTED_ABIS[0].contains("arm64") ? "arm64-v8a" : "armeabi-v7a";
        JSONObject release = new JSONObject(read(new URL("https://api.github.com/repos/yuliskov/SmartTube/releases/latest")));
        JSONArray assets = release.getJSONArray("assets");
        String url = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name");
            if (name.contains("stable") && name.endsWith(abi + ".apk")) url = a.optString("browser_download_url");
        }
        if (url == null) throw new Exception("no encontré SmartTube para " + abi);
        File dir = c.getExternalFilesDir("downloads");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, "smarttube.apk");
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

    private interface Job { String run(Adb adb) throws Exception; }

    private static void run(Context c, Consumer<String> toast, Runnable done, Job job) {
        if (busy) return;
        busy = true;
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String msg;
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                msg = job.run(adb);
            } catch (Exception e) {
                msg = "No se pudo: " + e.getMessage();
            } finally {
                busy = false;
            }
            String m = msg;
            main.post(() -> {
                toast.accept(m);
                done.run();
            });
        }, "ad-block").start();
    }
}
