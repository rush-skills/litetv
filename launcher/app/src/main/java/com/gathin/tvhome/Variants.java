package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The A/B test between this fork and upstream. Where both built the same thing their own way, one build holds both,
 * and each switch here picks one on the TV. Upstream's is the default. Compare versions (AbTestActivity) and
 * AbTestReceiver set the switches; a change bumps the settings revision, so Home rebuilds when it next resumes.
 */
final class Variants {
    /** The quick panel on the gear button; ours also takes the Settings pill and the Input button. */
    static final String PANEL = "panel";
    static final String SETTINGS = "settings";
    /** The rows of Home's Inputs menu. */
    static final String INPUTS = "inputs";
    /** The dock band's padding and corners. */
    static final String DOCK = "dock";
    /** The menu when OK is held on a tile. */
    static final String TILE_MENU = "tile_menu";
    /** What tells the owner the keys in reorder mode. */
    static final String REORDER_HINT = "reorder_hint";
    static final String[] ALL = {PANEL, SETTINGS, INPUTS, DOCK, TILE_MENU, REORDER_HINT};

    static final String OURS = "ours", UPSTREAM = "upstream";
    private static final String PREFS = "ab_test";

    private Variants() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean ours(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, false);
    }

    static void set(Context ctx, String key, boolean ours) {
        prefs(ctx).edit().putBoolean(key, ours).commit();
        rebuildHome(ctx);
    }

    static void setAll(Context ctx, boolean ours) {
        SharedPreferences.Editor editor = prefs(ctx).edit();
        for (String key : ALL) editor.putBoolean(key, ours);
        editor.commit();
        rebuildHome(ctx);
    }

    private static void rebuildHome(Context ctx) {
        SharedPreferences choices = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
    }

    /** The switches as "panel=ours settings=upstream ...", for adb. */
    static String describe(Context ctx) {
        StringBuilder out = new StringBuilder();
        for (String key : ALL) {
            if (out.length() > 0) out.append(' ');
            out.append(key).append('=').append(ours(ctx, key) ? OURS : UPSTREAM);
        }
        return out.toString();
    }

    /** Points an intent for upstream's quick panel or Launcher settings at our copy when its switch says so. */
    static Intent route(Context ctx, Intent intent) {
        ComponentName target = intent.getComponent();
        if (target == null) return intent;
        if (QuickSettingsActivity.class.getName().equals(target.getClassName()) && ours(ctx, PANEL)) {
            intent.setClass(ctx, QuickPanelActivity.class);
        } else if (LauncherSettingsActivity.class.getName().equals(target.getClassName()) && ours(ctx, SETTINGS)) {
            intent.setClass(ctx, OursLauncherSettingsActivity.class);
        }
        return intent;
    }
}
