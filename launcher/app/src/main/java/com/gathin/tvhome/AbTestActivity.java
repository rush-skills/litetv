package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Compare versions: one row for each switch in Variants, and two rows that set them all. */
public final class AbTestActivity extends Activity {
    private LinearLayout list;
    private final List<Runnable> refreshers = new ArrayList<>();

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        float density = getResources().getDisplayMetrics().density;
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding((int) (64 * density), (int) (40 * density), (int) (64 * density), (int) (80 * density));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B0E13);
        scroll.setClipToPadding(false);
        scroll.addView(list);
        setContentView(scroll);

        TextView title = text(R.string.ab_title, 30, 0xFFFFFFFF);
        list.addView(title);
        TextView help = text(R.string.ab_help, 15, 0xB3FFFFFF);
        help.setPadding(0, (int) (6 * density), 0, (int) (14 * density));
        list.addView(help);

        View first = choice(R.string.ab_panel, Variants.PANEL);
        choice(R.string.launcher_settings, Variants.SETTINGS);
        choice(R.string.ab_inputs, Variants.INPUTS);
        choice(R.string.ab_dock, Variants.DOCK);
        choice(R.string.ab_tile_menu, Variants.TILE_MENU);
        choice(R.string.ab_reorder_hint, Variants.REORDER_HINT);
        all(R.string.ab_all_upstream, false);
        all(R.string.ab_all_ours, true);
        refresh();
        first.requestFocus();
    }

    private TextView text(int res, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(res);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private View choice(int title, String key) {
        View row = row(title);
        TextView value = row.findViewById(R.id.value);
        refreshers.add(() -> value.setText(Variants.ours(this, key) ? R.string.ab_ours : R.string.ab_upstream));
        row.setOnClickListener(v -> {
            Variants.set(this, key, !Variants.ours(this, key));
            refresh();
        });
        return row;
    }

    private void all(int title, boolean ours) {
        View row = row(title);
        row.findViewById(R.id.value).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            Variants.setAll(this, ours);
            refresh();
        });
    }

    private View row(int title) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_setting_row, list, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.chevron).setVisibility(View.GONE);
        list.addView(row);
        return row;
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }
}
