package com.gathin.tvhome;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * The backgrounds Launcher settings offers, chosen on the TV with no computer: a plain one, three looks bundled
 * with the launcher (assets/looks: the repo's aurora and scenes themes, and a festive one), and the animated
 * LiveBackdrop styles.
 *
 * A bundled look is applied the way tools/theme.sh would push it, into the same folder, so Config and
 * WallpaperPlayer need nothing new. Only the look's own keys change in config.json; a pushed theme's dock, hidden
 * apps and other keys stay. Config.acceptCurrentTheme then marks the result as known, so the change does not count
 * as a push that clears the choices made on the TV.
 */
final class Looks {
    private static final String TAG = "TvHome.Looks";
    static final String PLAIN = "plain";
    /** A LiveBackdrop style as a choice: "live_" and the style. */
    private static final String LIVE = "live_";
    /** The values of Launcher settings, Background, in the order shown. */
    static final String[] ALL = {PLAIN, "aurora", "scenes", "festive",
            LIVE + LiveBackdrop.AURORA, LIVE + LiveBackdrop.STARS, LIVE + LiveBackdrop.WAVES};
    /** Keys a look may set in config.json; applying another look removes all of them first. */
    private static final String[] LOOK_KEYS = {"background_color", "wallpaper", "wallpaper_rotation_minutes",
            "wallpaper_order", "wallpaper_dim", "sky_intensity", "festival", "festival_until", "greeting", "accent_color"};

    private Looks() {
    }

    static boolean isLive(String choice) {
        return choice.startsWith(LIVE) && LiveBackdrop.valid(choice.substring(LIVE.length()));
    }

    /** The background in use, as Launcher settings shows it: the live style, the bundled look, or plain. */
    static String current(Context ctx) {
        Config cfg = Config.load(ctx);
        if (!LiveBackdrop.OFF.equals(cfg.liveBackground)) return LIVE + cfg.liveBackground;
        String look = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(Config.LOOK_KEY, null);
        return look != null ? look : PLAIN;
    }

    static int label(String look) {
        switch (look) {
            case "aurora": return R.string.look_aurora;
            case "scenes": return R.string.look_scenes;
            case "festive": return R.string.look_festive;
            case LIVE + LiveBackdrop.AURORA: return R.string.look_live_aurora;
            case LIVE + LiveBackdrop.STARS: return R.string.look_live_stars;
            case LIVE + LiveBackdrop.WAVES: return R.string.look_live_waves;
            default: return R.string.look_plain;
        }
    }

    /**
     * Applies a choice from ALL ("plain", a bundled look, or "live_" and a LiveBackdrop style). Runs file work, so call it off
     * the main thread. False when the theme folder could not be written; nothing is half changed then that a
     * second try would not fix.
     */
    static boolean apply(Context ctx, String choice) {
        boolean live = isLive(choice);
        String bundled = live || PLAIN.equals(choice) ? null : choice;
        File dir = Config.dir(ctx);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return false;
        try {
            File configFile = new File(dir, Config.FILE_NAME);
            JSONObject json = readJson(configFile);
            if (json == null) json = new JSONObject();
            for (String key : LOOK_KEYS) json.remove(key);
            File wallpaper = new File(dir, Config.WALLPAPER_NAME);
            File wallpapers = new File(dir, "wallpapers");
            clearImages(wallpapers);
            if (!wallpaper.delete() && wallpaper.exists()) return false;
            if (bundled != null) {
                AssetManager assets = ctx.getAssets();
                String base = "looks/" + bundled;
                JSONObject look = new JSONObject(readAsset(assets, base + "/config.json"));
                for (Iterator<String> keys = look.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    json.put(key, look.get(key));
                }
                String[] files = assets.list(base);
                if (files != null) {
                    for (String name : files) {
                        if (name.equals(Config.WALLPAPER_NAME)) copyAsset(assets, base + "/" + name, wallpaper);
                    }
                }
                String[] images = assets.list(base + "/wallpapers");
                if (images != null && images.length > 0) {
                    if (!wallpapers.isDirectory() && !wallpapers.mkdirs()) return false;
                    for (String name : images) copyAsset(assets, base + "/wallpapers/" + name, new File(wallpapers, name));
                }
            }
            if (json.length() == 0) {
                if (!configFile.delete() && configFile.exists()) return false;
            } else {
                try (OutputStream out = new FileOutputStream(configFile)) {
                    out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "could not apply " + choice + ": " + e.getMessage());
            return false;
        }
        Config.acceptCurrentTheme(ctx);
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .remove(Config.WALLPAPER_CHOICE_KEY)
                .putString(Config.LOOK_KEY, live ? PLAIN : choice)
                .putString(Config.LIVE_BACKGROUND_KEY, live ? choice.substring(LIVE.length()) : LiveBackdrop.OFF)
                .commit();
        return true;
    }

    private static void clearImages(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                    || name.endsWith(".webp"))) {
                if (!f.delete()) Log.w(TAG, "could not delete " + f);
            }
        }
    }

    private static JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(file)) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static String readAsset(AssetManager assets, String path) throws IOException {
        try (InputStream in = assets.open(path)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void copyAsset(AssetManager assets, String path, File target) throws IOException {
        try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
