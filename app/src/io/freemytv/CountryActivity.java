package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** "País": whose trends the home screen's background shows. One press picks it, and the feed is fetched again. */
public class CountryActivity extends Activity {
    private static final String[][] COUNTRIES = {
        {"CO", "Colombia"}, {"MX", "México"}, {"AR", "Argentina"}, {"CL", "Chile"}, {"PE", "Perú"},
        {"EC", "Ecuador"}, {"VE", "Venezuela"}, {"BO", "Bolivia"}, {"UY", "Uruguay"}, {"PY", "Paraguay"},
        {"CR", "Costa Rica"}, {"PA", "Panamá"}, {"GT", "Guatemala"}, {"DO", "Rep. Dominicana"}, {"ES", "España"},
        {"US", "Estados Unidos"}, {"BR", "Brasil"}, {"GB", "Reino Unido"}, {"CA", "Canadá"}, {"FR", "Francia"},
    };
    private float k;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        String current = Recs.get(this).country();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E0F14);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(px(110), px(80), px(110), px(40));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title = text(46, Color.WHITE, Typeface.BOLD);
        title.setText("Tendencias de…");
        col.addView(title);
        TextView sub = text(26, 0xB3FFFFFF, Typeface.NORMAL);
        sub.setText("El fondo muestra lo más popular para ver en ese país, y lo que está en cines.");
        sub.setPadding(0, px(8), 0, px(40));
        col.addView(sub);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        TextView focus = null;
        for (String[] c : COUNTRIES) {
            TextView t = text(30, Color.WHITE, Typeface.BOLD);
            boolean on = c[0].equals(current);
            t.setText((on ? "✓  " : "") + c[1]);
            t.setGravity(Gravity.CENTER);
            t.setFocusable(true);
            t.setBackground(pill(on ? 0xFF2C6BED : 0xFF22242E, 0));
            t.setOnFocusChangeListener((v, f) -> {
                v.animate().scaleX(f ? 1.06f : 1).scaleY(f ? 1.06f : 1).setDuration(120).start();
                ((TextView) v).setTextColor(f ? 0xFF0E0F14 : Color.WHITE);
                v.setBackground(pill(f ? 0xFFFFFFFF : (on ? 0xFF2C6BED : 0xFF22242E), 0));
            });
            t.setOnClickListener(v -> {
                Recs.get(this).setCountry(c[0]);
                Toast.makeText(this, "Tendencias de " + c[1], Toast.LENGTH_SHORT).show();
                finish();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = px(370);
            lp.height = px(96);
            lp.setMargins(px(12), px(12), px(12), px(12));
            grid.addView(t, lp);
            if (on) focus = t;
        }
        col.addView(grid);
        setContentView(root);
        if (focus != null) focus.requestFocus();
    }

    private int px(float v) { return Math.round(v * k); }

    private TextView text(float size, int color, int style) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(color);
        t.setTypeface(Ui.medium != null ? Ui.medium : Typeface.DEFAULT, style);
        return t;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(px(48));
        if (stroke != 0) d.setStroke(px(3), stroke);
        return d;
    }
}
