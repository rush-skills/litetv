package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Remote-friendly settings owned by the launcher, in Home's style: rows on the left with the value in use, and on the
 * right what the focused row does. A row with more than two values (Sort apps, Background, Wallpaper) opens its
 * choices on the right instead. Every change is saved at once; Home rebuilds with it when this screen closes
 * (RESULT_OK). Reorder apps and Edit dock close the screen and start that mode on Home (EXTRA_THEN).
 */
public final class OursLauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    private static final int UPSTREAM_SETTINGS_REQUEST = 1;

    /** Result extra: a Home mode to start once Home is back ("reorder" or "dock"). */
    static final String EXTRA_THEN = "then";
    static final String THEN_REORDER = "reorder", THEN_DOCK = "dock";

    private static final String[] SORTS = {Config.SORT_CUSTOM, Config.SORT_RECENT, Config.SORT_MOST_USED, Config.SORT_NAME};
    private static final int[] SORT_LABELS = {R.string.sort_custom, R.string.sort_recent, R.string.sort_most_used,
            R.string.sort_name};

    private SharedPreferences choices;
    private LinearLayout list;
    private TextView infoTitle, infoText;
    private View info, choicesScroll;
    private LinearLayout choicesList;
    private TextView choicesTitle;
    /** The row whose choices are open, to give focus back to when they close. */
    private View choicesOwner;
    /** Rows whose value text follows the saved choices; refreshed after every change. */
    private final List<Runnable> refreshers = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean changed;

    private int columns, rowsBelowDock;
    private boolean animations, advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings_ours);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        list = findViewById(R.id.list);
        info = findViewById(R.id.info);
        infoTitle = findViewById(R.id.infoTitle);
        infoText = findViewById(R.id.infoText);
        choicesScroll = findViewById(R.id.choicesScroll);
        choicesList = findViewById(R.id.choices);
        choicesTitle = findViewById(R.id.choicesTitle);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        header(R.string.settings_section_home);
        View firstFocus = segmented(R.string.columns_label, R.string.columns_info, new String[]{"4", "5", "6", "7"},
                () -> columns - 4, i -> {
                    columns = i + 4;
                    choices.edit().putInt("columns", columns).apply();
                });
        segmented(R.string.rows_below_dock_label, R.string.rows_info, new String[]{"0", "1", "2", "3"},
                () -> rowsBelowDock, i -> {
                    rowsBelowDock = i;
                    choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, i).apply();
                });
        row(R.string.sort_apps, () -> getString(SORT_LABELS[indexOf(SORTS, Config.load(this).appSort)]), () -> {
            String text = getString(R.string.sort_info);
            return AppUsage.hasAccess(this) ? text : text + "\n\n" + getString(R.string.sort_info_no_access);
        }, v -> showChoices(v, R.string.sort_apps, labels(SORT_LABELS), indexOf(SORTS, Config.load(this).appSort), i -> {
            choices.edit().putString(Config.APP_SORT_KEY, SORTS[i]).apply();
            changed();
        }));
        toggle(R.string.continue_row, R.string.continue_row_info, () -> Config.load(this).continueRow,
                on -> choices.edit().putBoolean(Config.CONTINUE_ROW_KEY, on).apply());
        row(R.string.reorder, null, () -> getString(R.string.reorder_info), v -> finishWith(THEN_REORDER));
        row(R.string.dock_edit, null, () -> getString(R.string.dock_edit_info), v -> finishWith(THEN_DOCK));

        header(R.string.settings_section_look);
        row(R.string.background, () -> getString(Looks.label(Looks.current(this))),
                () -> getString(R.string.background_info), this::showBackgrounds);
        View wallpaperRow = row(R.string.wallpaper_label, this::wallpaperValue, () -> getString(R.string.wallpaper_info),
                this::showWallpapers);
        refreshers.add(() -> wallpaperRow.setVisibility(wallpaperImages().size() > 1 ? View.VISIBLE : View.GONE));
        toggle(R.string.parallax, R.string.parallax_info, () -> Config.load(this).parallax,
                on -> choices.edit().putBoolean(Config.PARALLAX_KEY, on).apply());

        header(R.string.settings_section_motion);
        toggle(R.string.animations, R.string.animations_info, () -> animations, on -> {
            animations = on;
            if (!on) advancedEffects = false;
            choices.edit().putBoolean("animations", on).putBoolean("advanced_effects", advancedEffects).apply();
        });
        View advanced = toggle(R.string.advanced_effects, R.string.advanced_effects_warning, () -> advancedEffects, on -> {
            advancedEffects = on && animations;
            choices.edit().putBoolean("advanced_effects", advancedEffects).apply();
        });
        refreshers.add(() -> {
            advanced.setEnabled(animations);
            advanced.setAlpha(animations ? 1f : 0.5f);
        });

        header(R.string.settings_section_system);
        row(R.string.follow_theme_short, null, () -> getString(R.string.follow_theme_hint), v -> {
            Config.clearLookChoices(choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY)
                    .remove("animations").remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
        });
        row(R.string.home_settings, null, () -> getString(R.string.home_settings_help), v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        row(R.string.tv_settings, null, () -> getString(R.string.tv_settings_info),
                v -> open(new Intent(Settings.ACTION_SETTINGS)));
        row(R.string.ab_upstream_settings, null, () -> getString(R.string.ab_upstream_settings_info),
                v -> startActivityForResult(new Intent(this, LauncherSettingsActivity.class), UPSTREAM_SETTINGS_REQUEST));
        row(R.string.ab_title, null, () -> getString(R.string.ab_help),
                v -> startActivity(new Intent(this, AbTestActivity.class)));

        // Choices close as soon as focus leaves them (Left, or Back), so they never stay open beside another row.
        list.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                if (choicesScroll.getVisibility() == View.VISIBLE && newFocus != null && !isInside(newFocus, choicesList)) {
                    closeChoices(false);
                }
            }
        });
        refresh();
        firstFocus.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (choicesScroll.getVisibility() == View.VISIBLE) {
            closeChoices(true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
    }

    // ---- rows ------------------------------------------------------------------------------------

    private void header(int text) {
        TextView h = (TextView) LayoutInflater.from(this).inflate(R.layout.item_setting_header, list, false);
        h.setText(text);
        list.addView(h);
    }

    /** A row with its value on the right (null for an action row) and what it does on the right of the screen. */
    private View row(int title, Supplier<String> value, Supplier<String> about, View.OnClickListener action) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_setting_row, list, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView valueView = row.findViewById(R.id.value);
        if (value == null) {
            valueView.setVisibility(View.GONE);
        } else {
            refreshers.add(() -> valueView.setText(value.get()));
        }
        row.setOnClickListener(action);
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) showInfo(getString(title), about.get());
        });
        list.addView(row);
        return row;
    }

    private View toggle(int title, int about, Supplier<Boolean> state, java.util.function.Consumer<Boolean> set) {
        View row = row(title, () -> getString(state.get() ? R.string.on : R.string.off), () -> getString(about), v -> {
            if (!v.isEnabled()) return;
            set.accept(!state.get());
            changed();
        });
        row.findViewById(R.id.chevron).setVisibility(View.GONE);
        return row;
    }

    /** A title and a row of equal choices; returns the first choice, for the first focus. */
    private View segmented(int title, int about, String[] values, Supplier<Integer> selected, IntConsumer pick) {
        TextView label = (TextView) LayoutInflater.from(this).inflate(R.layout.item_setting_header, list, false);
        label.setText(title);
        label.setAllCaps(false);
        label.setTextSize(16);
        label.setLetterSpacing(0f);
        label.setTextColor(0xFFFFFFFF);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) label.getLayoutParams();
        lp.topMargin = Math.round(14 * getResources().getDisplayMetrics().density);
        list.addView(label);
        LinearLayout rowView = new LinearLayout(this);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        list.addView(rowView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView[] views = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            TextView choice = (TextView) LayoutInflater.from(this).inflate(R.layout.item_setting_choice, rowView, false);
            choice.setText(values[i]);
            if (i == values.length - 1) ((ViewGroup.MarginLayoutParams) choice.getLayoutParams()).setMarginEnd(0);
            choice.setOnClickListener(v -> {
                if (selected.get() == index) return;
                pick.accept(index);
                changed();
            });
            choice.setOnFocusChangeListener((v, focused) -> {
                if (focused) showInfo(getString(title), getString(about));
            });
            views[i] = choice;
        }
        // Keep the numbers increasing in the Hebrew reading direction: reorder the views, so D-pad focus follows
        // their positions, without changing the saved values.
        boolean reverse = getResources().getBoolean(R.bool.rtl_app_grid);
        for (int i = 0; i < views.length; i++) rowView.addView(views[reverse ? views.length - 1 - i : i]);
        refreshers.add(() -> {
            for (int i = 0; i < views.length; i++) views[i].setSelected(selected.get() == i);
        });
        return views[0];
    }

    private void showInfo(String title, String text) {
        infoTitle.setText(title);
        infoText.setText(text);
    }

    private void changed() {
        changed = true;
        setResult(RESULT_OK);
        // Home rebuilds on resume when the count moved, however this screen was left.
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        refresh();
    }

    /** Reorder tiles chosen on upstream's screen, opened from the row below: Home starts it. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == UPSTREAM_SETTINGS_REQUEST && data != null
                && data.getBooleanExtra(LauncherSettingsActivity.EXTRA_REORDER, false)) {
            setResult(resultCode, data);
            finish();
        }
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    private void finishWith(String then) {
        setResult(changed ? RESULT_OK : RESULT_FIRST_USER, new Intent().putExtra(EXTRA_THEN, then));
        finish();
    }

    // ---- choices ---------------------------------------------------------------------------------

    /** Opens a row's choices on the right, with focus on the one in use; OK picks, Back or Left closes. */
    private void showChoices(View owner, int title, String[] labels, int checked, IntConsumer pick) {
        choicesOwner = owner;
        while (choicesList.getChildCount() > 1) choicesList.removeViewAt(1);
        choicesTitle.setText(title);
        View focus = null;
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            View row = LayoutInflater.from(this).inflate(R.layout.item_setting_row, choicesList, false);
            ((TextView) row.findViewById(R.id.title)).setText(labels[i]);
            TextView mark = row.findViewById(R.id.value);
            mark.setText(i == checked ? "✓" : "");
            row.findViewById(R.id.chevron).setVisibility(View.GONE);
            row.setSelected(i == checked);
            row.setOnClickListener(v -> {
                closeChoices(true);
                pick.accept(index);
            });
            choicesList.addView(row);
            if (i == checked || focus == null) focus = row;
        }
        info.setVisibility(View.GONE);
        choicesScroll.setVisibility(View.VISIBLE);
        if (focus != null) focus.requestFocus();
    }

    private void closeChoices(boolean refocus) {
        choicesScroll.setVisibility(View.GONE);
        info.setVisibility(View.VISIBLE);
        if (refocus && choicesOwner != null) choicesOwner.requestFocus();
    }

    private static boolean isInside(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == parent) return true;
        }
        return false;
    }

    private void showBackgrounds(View owner) {
        String[] labels = new String[Looks.ALL.length];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(Looks.label(Looks.ALL[i]));
        showChoices(owner, R.string.background, labels, indexOf(Looks.ALL, Looks.current(this)), i -> {
            String look = Looks.ALL[i];
            new Thread(() -> {
                boolean ok = Looks.apply(this, look);
                main.post(() -> {
                    if (isDestroyed()) return;
                    if (!ok) Toast.makeText(this, R.string.look_failed, Toast.LENGTH_LONG).show();
                    changed();
                });
            }, "looks").start();
        });
    }

    /** The theme's pictures: its wallpapers/ collection, and its single wallpaper.jpg too. */
    private List<File> wallpaperImages() {
        File dir = Config.dir(this);
        List<File> images = new ArrayList<>(WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers")));
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        return images;
    }

    private String wallpaperValue() {
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        if ("none".equals(current)) return getString(R.string.wallpaper_none);
        return current != null ? pictureName(current) : getString(R.string.wallpaper_rotate);
    }

    /** "07-mirror-lake.jpg" as "Mirror lake". */
    private static String pictureName(String file) {
        String name = file.replaceFirst("\\.[A-Za-z]+$", "").replaceFirst("^\\d+[-_ ]*", "").replace('-', ' ').replace('_', ' ');
        return name.isEmpty() ? file : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private void showWallpapers(View owner) {
        List<File> images = wallpaperImages();
        String[] labels = new String[images.size() + 2];
        labels[0] = getString(R.string.wallpaper_rotate);
        for (int i = 0; i < images.size(); i++) labels[i + 1] = pictureName(images.get(i).getName());
        labels[labels.length - 1] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = 0;
        if ("none".equals(current)) checked = labels.length - 1;
        for (int i = 0; current != null && i < images.size(); i++) {
            if (images.get(i).getName().equals(current)) checked = i + 1;
        }
        showChoices(owner, R.string.wallpaper_label, labels, checked, i -> {
            SharedPreferences.Editor e = choices.edit();
            if (i == 0) e.remove(Config.WALLPAPER_CHOICE_KEY);
            else if (i == labels.length - 1) e.putString(Config.WALLPAPER_CHOICE_KEY, "none");
            else e.putString(Config.WALLPAPER_CHOICE_KEY, images.get(i - 1).getName());
            e.apply();
            changed();
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private String[] labels(int[] ids) {
        String[] out = new String[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = getString(ids[i]);
        return out;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
