package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * "Mis gustos": three rounds of eight posters. The first round has one popular movie from each of eight very different
 * genres; the next two lean on the genres you liked and keep two exploring others. Each liked poster adds to its
 * genres, each skipped one takes a little from its main genre. The result (genre weights and the liked titles) is saved
 * in Recs' preferences, and the home screen's feed is built from it.
 */
public class TasteActivity extends Activity {
    static final String PREFS = "recs", KEY = "taste";
    /** TMDB movie genres, and the matching TV genre for the feed (0 = TV has none). */
    static final int[][] GENRES = {
        {28, 10759}, {35, 35}, {27, 9648}, {10749, 18}, {878, 10765}, {16, 16},
        {18, 18}, {99, 99}, {80, 80}, {14, 10765}, {53, 9648}, {10751, 10751},
        {36, 99}, {10402, 0}, {10752, 10768}, {37, 37},
    };
    static final String[] NAMES = {"Acción", "Comedia", "Terror", "Romance", "Ciencia ficción", "Animación",
            "Drama", "Documental", "Crimen", "Fantasía", "Suspenso", "Familia",
            "Historia y biografías", "Música", "Bélica", "Western"};
    /** Round 1 always spans these: no two posters alike (documentaries and true stories included). */
    private static final int[] FIRST_ROUND = {99, 36, 27, 35, 10749, 878, 80, 16};
    /** Small genres: fewer votes, so a lower bar for "well known". */
    private static final Set<Integer> NICHE = new HashSet<>(java.util.Arrays.asList(99, 36, 10402, 10752, 37));
    private static final int ROUNDS = 3, PER_ROUND = 8;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Map<Integer, Float> weights = new HashMap<>();
    private final Map<Integer, Integer> shownPerGenre = new HashMap<>();
    private final Set<Integer> seen = new HashSet<>();
    private final JSONArray liked = new JSONArray();
    private final List<JSONObject> round = new ArrayList<>();
    private final boolean[] marked = new boolean[PER_ROUND];
    private final FrameLayout[] cards = new FrameLayout[PER_ROUND];
    private final ImageView[] posters = new ImageView[PER_ROUND];
    private final TextView[] checks = new TextView[PER_ROUND], titles = new TextView[PER_ROUND];
    private TextView heading, next;
    private int roundNumber;
    private float k;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E0F14);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(110), px(64), px(110), px(40));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        heading = text(46, Color.WHITE, Typeface.BOLD);
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(heading);
        TextView sub = text(26, 0xB3FFFFFF, Typeface.NORMAL);
        sub.setText("Marca con OK las que te gustan (o te gustarían). No hace falta haberlas visto.");
        sub.setPadding(0, px(8), 0, px(28));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(sub);

        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            for (int c = 0; c < 4; c++) row.addView(card(r * 4 + c));
            col.addView(row);
        }

        next = text(30, 0xFF0E0F14, Typeface.BOLD);
        next.setGravity(Gravity.CENTER);
        next.setFocusable(true);
        next.setPadding(px(48), px(18), px(48), px(18));
        next.setBackground(pill(0xFFFFFFFF));
        next.setOnClickListener(v -> nextRound());
        next.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.06f : 1).scaleY(f ? 1.06f : 1).setDuration(120).start());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, px(110), px(56));
        root.addView(next, lp);
        setContentView(root);
        loadRound();
    }

    private View card(int i) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(18), 0, px(18), px(18));
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClipToOutline(true);
        card.setBackground(rounded(0xFF22242E, 0));
        card.setPadding(px(4), px(4), px(4), px(4));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setClipToOutline(true);
        img.setBackground(rounded(0xFF22242E, 0));
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView check = text(30, Color.WHITE, Typeface.BOLD);
        check.setText("✓");
        check.setGravity(Gravity.CENTER);
        check.setBackground(circle(0xFF34C759));
        check.setVisibility(View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(px(54), px(54), Gravity.TOP | Gravity.END);
        clp.setMargins(0, px(12), px(12), 0);
        card.addView(check, clp);
        card.setOnClickListener(v -> toggle(i));
        card.setOnFocusChangeListener((v, f) -> {
            v.animate().scaleX(f ? 1.07f : 1).scaleY(f ? 1.07f : 1).setDuration(120).start();
            v.setBackground(rounded(0xFF22242E, f ? 0xFFFFFFFF : (marked[i] ? 0xFF34C759 : 0)));
            v.setElevation(f ? px(16) : 0);
        });
        box.addView(card, new LinearLayout.LayoutParams(px(190), px(285)));
        TextView title = text(25, Color.WHITE, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setLineSpacing(0, 0.95f);
        title.setPadding(0, px(10), 0, 0);
        box.addView(title, new LinearLayout.LayoutParams(px(190), ViewGroup.LayoutParams.WRAP_CONTENT));
        cards[i] = card;
        posters[i] = img;
        checks[i] = check;
        titles[i] = title;
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(outer);
        return box;
    }

    private void toggle(int i) {
        if (i >= round.size()) return;
        marked[i] = !marked[i];
        checks[i].setVisibility(marked[i] ? View.VISIBLE : View.GONE);
        cards[i].setBackground(rounded(0xFF22242E, cards[i].hasFocus() ? 0xFFFFFFFF : (marked[i] ? 0xFF34C759 : 0)));
    }

    // MARK: - Rounds

    private void loadRound() {
        heading.setText("¿Qué te gusta ver?  " + (roundNumber + 1) + " de " + ROUNDS);
        next.setText(roundNumber + 1 < ROUNDS ? "Siguiente" : "Listo");
        for (int i = 0; i < PER_ROUND; i++) {
            marked[i] = false;
            checks[i].setVisibility(View.GONE);
            posters[i].setImageBitmap(null);
            titles[i].setText("");
            cards[i].setBackground(rounded(0xFF22242E, 0));
        }
        List<Integer> genres = pickGenres();
        new Thread(() -> {
            List<JSONObject> items = new ArrayList<>();
            // Three of the eight are series (where the genre has a TV twin), the rest movies.
            int series = 0;
            for (int i = 0; i < genres.size(); i++) {
                int g = genres.get(i);
                boolean tv = series < 3 && i % 3 == 1 && tvGenre(g) != 0;
                JSONObject o = tv ? popularSeries(g) : null;
                if (o != null) series++;
                else o = popular(g);
                if (o != null) items.add(o);
            }
            main.post(() -> show(items));
            for (int i = 0; i < items.size(); i++) {
                Bitmap bmp = poster(items.get(i).optString("poster_path"));
                int at = i;
                if (bmp != null) main.post(() -> { if (at < round.size() && round.get(at) == items.get(at)) posters[at].setImageBitmap(bmp); });
            }
        }, "taste").start();
    }

    private void show(List<JSONObject> items) {
        round.clear();
        round.addAll(items);
        for (int i = 0; i < PER_ROUND; i++) {
            boolean on = i < items.size();
            cards[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (on) titles[i].setText(titleOf(items.get(i)));
        }
        if (!items.isEmpty()) cards[0].requestFocus();
        else heading.setText("No pude cargar las películas. Revisa el internet.");
    }

    /** Round 1: eight different genres. Then: the three you like most (two each) and two to keep exploring. */
    private List<Integer> pickGenres() {
        List<Integer> all = new ArrayList<>();
        for (int[] g : GENRES) all.add(g[0]);
        Collections.shuffle(all, random);
        if (roundNumber == 0) {
            List<Integer> first = new ArrayList<>();
            for (int g : FIRST_ROUND) first.add(g);
            Collections.shuffle(first, random);
            return first;
        }
        List<Integer> top = new ArrayList<>(all);
        top.sort((a, b) -> Float.compare(weight(b), weight(a)));
        List<Integer> out = new ArrayList<>();
        for (int g : top) if (weight(g) > 0 && out.size() < 6) { out.add(g); out.add(g); }
        // Explore: the genres shown least so far.
        all.sort((a, b) -> Integer.compare(count(a), count(b)));
        for (int g : all) if (out.size() < PER_ROUND && !out.contains(g)) out.add(g);
        while (out.size() > PER_ROUND) out.remove(out.size() - 1);
        Collections.shuffle(out, random);
        return out;
    }

    private void nextRound() {
        for (int i = 0; i < round.size(); i++) {
            JSONObject o = round.get(i);
            int main = o.optInt("_genre");
            shownPerGenre.put(main, count(main) + 1);
            if (marked[i]) {
                boolean tv = "tv".equals(o.optString("_kind"));
                JSONArray ids = o.optJSONArray("genre_ids");
                for (int j = 0; !tv && ids != null && j < ids.length(); j++) add(ids.optInt(j), 0.6f);
                add(main, 1f);
                try {
                    liked.put(new JSONObject().put("id", o.optInt("id")).put("kind", tv ? "tv" : "movie").put("title", titleOf(o)));
                } catch (Exception ignored) {
                }
            } else {
                add(main, -0.4f);
            }
        }
        roundNumber++;
        if (roundNumber < ROUNDS) {
            loadRound();
            return;
        }
        save();
        android.widget.Toast.makeText(this, "Listo: el fondo se va a ajustar a tus gustos", android.widget.Toast.LENGTH_LONG).show();
        finish();
    }

    private void save() {
        try {
            JSONObject w = new JSONObject();
            for (Map.Entry<Integer, Float> e : weights.entrySet()) w.put(String.valueOf(e.getKey()), e.getValue());
            JSONObject taste = new JSONObject().put("weights", w).put("liked", liked).put("at", System.currentTimeMillis());
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, taste.toString()).putString("mode", "taste").commit();
        } catch (Exception ignored) {
        }
        Recs.get(this).tasteChanged();
    }

    private float weight(int g) { Float w = weights.get(g); return w == null ? 0 : w; }
    private int count(int g) { Integer c = shownPerGenre.get(g); return c == null ? 0 : c; }
    private void add(int g, float d) { if (isOurs(g)) weights.put(g, weight(g) + d); }

    private static boolean isOurs(int g) {
        for (int[] x : GENRES) if (x[0] == g) return true;
        return false;
    }

    /** A popular, well-known movie of that genre that hasn't been shown yet. */
    private JSONObject popular(int genre) {
        try {
            // Classics with a clear style, not this month's releases: the most-voted, well-rated films of the genre
            // (Good Will Hunting, Dead Poets Society, The Matrix…), from a random one of the first pages.
            int page = 1 + random.nextInt(NICHE.contains(genre) ? 2 : 4);
            int votes = NICHE.contains(genre) ? 300 : 3000;
            JSONArray r = get("/discover/movie?language=es-MX&sort_by=vote_count.desc&vote_average.gte=7&vote_count.gte=" + votes + "&with_genres=" + genre
                    + (genre == 16 ? "" : "&without_genres=16") + "&page=" + page).optJSONArray("results");
            List<JSONObject> pool = new ArrayList<>(), loose = new ArrayList<>();
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject o = r.getJSONObject(i);
                if (o.optString("poster_path").isEmpty() || seen.contains(o.optInt("id"))) continue;
                // Its main genre must be this one: a superhero film tagged "drama" doesn't stand for drama.
                JSONArray ids = o.optJSONArray("genre_ids");
                if (ids != null && ids.length() > 0 && ids.optInt(0) == genre) pool.add(o);
                else loose.add(o);
            }
            if (pool.isEmpty()) pool = loose;
            if (pool.isEmpty()) return null;
            JSONObject o = pool.get(random.nextInt(Math.min(pool.size(), 8)));
            seen.add(o.optInt("id"));
            o.put("_genre", genre);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** A well-loved, much-voted series of that genre (Cien años de soledad, Breaking Bad…) not shown yet. */
    private JSONObject popularSeries(int movieGenre) {
        try {
            int g = tvGenre(movieGenre), page = 1 + random.nextInt(3);
            JSONArray r = get("/discover/tv?language=es-MX&sort_by=vote_count.desc&vote_average.gte=7.3&vote_count.gte="
                    + (NICHE.contains(movieGenre) ? 100 : 800) + "&with_genres=" + g
                    + (g == 16 ? "" : "&without_genres=16") + "&page=" + page).optJSONArray("results");
            List<JSONObject> pool = new ArrayList<>();
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject o = r.getJSONObject(i);
                if (!o.optString("poster_path").isEmpty() && !seen.contains(-o.optInt("id"))) pool.add(o);
            }
            if (pool.isEmpty()) return null;
            JSONObject o = pool.get(random.nextInt(Math.min(pool.size(), 8)));
            seen.add(-o.optInt("id"));      // series ids are their own space: kept apart from movies'
            o.put("_genre", movieGenre).put("_kind", "tv");
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    static String titleOf(JSONObject o) {
        return o.optString("title", o.optString("name"));
    }

    static JSONObject get(String path) throws Exception {
        String key = Prefs.tmdbKey();
        boolean bearer = key.startsWith("eyJ");
        String url = "https://api.themoviedb.org/3" + path + (bearer ? "" : "&api_key=" + key);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private static Bitmap poster(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w342" + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The saved tastes, or null: {weights: {genre: w}, liked: [{id, kind, title}]}. */
    static JSONObject taste(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String s = p.getString(KEY, null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static int tvGenre(int movieGenre) {
        for (int[] g : GENRES) if (g[0] == movieGenre) return g[1];
        return 0;
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // MARK: - Small helpers

    private int px(float v) { return Math.round(v * k); }

    private TextView text(float size, int color, int style) {
        TextView t = new TextView(this);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(color);
        t.setTypeface(Ui.medium != null ? Ui.medium : Typeface.DEFAULT, style);
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(px(18));
        if (stroke != 0) d.setStroke(px(4), stroke);
        return d;
    }

    private GradientDrawable pill(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(px(60));
        return d;
    }

    private GradientDrawable circle(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }
}
