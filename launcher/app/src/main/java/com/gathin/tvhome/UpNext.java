package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.widget.Toast;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rows of the Up Next screen, read from the TV provider: what the apps put in Continue watching
 * (Watch Next), then one row per preview channel ("latest" rows). Reading other apps' rows needs
 * android.permission.READ_TV_LISTINGS, and the provider then returns only the rows each app marked
 * searchable (docs/watch-next.md). It throws on a selection, so every table is read whole and filtered here.
 * Netflix, among others, publishes nothing, so its titles cannot appear.
 */
final class UpNext {
    static final int MAX_CONTINUE = 20, MAX_PER_ROW = 12, MAX_CHANNEL_ROWS = 10;
    private static final int ASPECT_RATIO_MOVIE_POSTER = 5;
    /** A runtime permission defined by the TV provider; the public SDK has no constant for it. */
    static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";

    static final class Item {
        String pkg, app, title, subtitle, description, image, intentUri;
        long position, duration;
    }

    static final class Row {
        final String title;
        final boolean portrait;
        final List<Item> items;

        Row(String title, boolean portrait, List<Item> items) {
            this.title = title;
            this.portrait = portrait;
            this.items = items;
        }
    }

    /** Raw art choices for one program, before the row decides between landscape and portrait cards. */
    private static final class Art {
        String poster, thumbnail;
        int posterAspect = -1, thumbnailAspect = -1;
    }

    private UpNext() {
    }

    /** Runs off the main thread. Apps in appOrder (the dock) get their rows first. */
    static List<Row> load(Context ctx, List<String> appOrder) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, String> labels = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        Row watchNext = continueWatching(ctx, pm, labels, false);
        if (watchNext != null) rows.add(watchNext);
        addChannelRows(ctx, pm, labels, appOrder, rows);
        return rows;
    }

    /** Only the Continue watching row, in landscape cards (Home's row); null when it is empty. Off the main thread. */
    static Row loadContinue(Context ctx) {
        return continueWatching(ctx, ctx.getPackageManager(), new HashMap<>(), true);
    }

    /** Continue watching, newest engagement first; null when no app has anything in it. */
    private static Row continueWatching(Context ctx, PackageManager pm, Map<String, String> labels, boolean landscape) {
        List<Item> watchNext = new ArrayList<>();
        List<Art> watchNextArt = new ArrayList<>();
        List<Long> engaged = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            if (c != null) {
                Columns col = new Columns(c);
                while (c.moveToNext()) {
                    if (col.integer(c, TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                    Art art = new Art();
                    Item item = read(c, col, pm, labels, art);
                    if (item.title == null) continue;
                    String kind = watchNextKind(ctx, col.integer(c, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, -1));
                    item.subtitle = join(item.app, kind, item.subtitle);
                    watchNext.add(item);
                    watchNextArt.add(art);
                    engaged.add(col.longValue(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, 0));
                }
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < watchNext.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Long.compare(engaged.get(b), engaged.get(a)));
        List<Item> sorted = new ArrayList<>();
        List<Art> sortedArt = new ArrayList<>();
        for (int i = 0; i < order.size() && i < MAX_CONTINUE; i++) {
            sorted.add(watchNext.get(order.get(i)));
            sortedArt.add(watchNextArt.get(order.get(i)));
        }
        return sorted.isEmpty() ? null : row(ctx.getString(R.string.continue_watching), sorted, sortedArt, landscape);
    }

    /** Preview channels: one row each, in the app's own order (weight, highest first). */
    private static void addChannelRows(Context ctx, PackageManager pm, Map<String, String> labels, List<String> appOrder,
                                       List<Row> rows) {
        Map<Long, String[]> channels = new HashMap<>();   // id -> {package, name}
        try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
            if (c != null) {
                Columns col = new Columns(c);
                while (c.moveToNext()) {
                    long id = col.longValue(c, TvContract.Channels._ID, -1);
                    String pkg = col.string(c, TvContract.Channels.COLUMN_PACKAGE_NAME);
                    if (id < 0 || pkg == null) continue;
                    channels.put(id, new String[]{pkg, col.string(c, TvContract.Channels.COLUMN_DISPLAY_NAME)});
                }
            }
        }
        Map<Long, List<Item>> byChannel = new LinkedHashMap<>();
        Map<Long, List<Art>> artByChannel = new HashMap<>();
        Map<Long, List<Integer>> weights = new HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            if (c != null) {
                Columns col = new Columns(c);
                while (c.moveToNext()) {
                    if (col.integer(c, TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1) == 0) continue;
                    long channel = col.longValue(c, TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, -1);
                    if (!channels.containsKey(channel)) continue;
                    Art art = new Art();
                    Item item = read(c, col, pm, labels, art);
                    if (item.title == null) continue;
                    item.subtitle = join(item.app, item.subtitle);
                    byChannel.computeIfAbsent(channel, k -> new ArrayList<>()).add(item);
                    artByChannel.computeIfAbsent(channel, k -> new ArrayList<>()).add(art);
                    weights.computeIfAbsent(channel, k -> new ArrayList<>())
                            .add(col.integer(c, TvContract.PreviewPrograms.COLUMN_WEIGHT, 0));
                }
            }
        }
        List<Long> channelOrder = new ArrayList<>(byChannel.keySet());
        Collections.sort(channelOrder, (a, b) -> {
            String pa = channels.get(a)[0], pb = channels.get(b)[0];
            int ia = appOrder.indexOf(pa), ib = appOrder.indexOf(pb);
            if (ia != ib) {
                if (ia < 0) return 1;
                if (ib < 0) return -1;
                return Integer.compare(ia, ib);
            }
            int byApp = label(pm, labels, pa).compareToIgnoreCase(label(pm, labels, pb));
            return byApp != 0 ? byApp : Long.compare(a, b);
        });
        int channelRows = 0;
        for (long id : channelOrder) {
            if (channelRows++ == MAX_CHANNEL_ROWS) break;
            List<Item> items = byChannel.get(id);
            List<Art> arts = artByChannel.get(id);
            List<Integer> w = weights.get(id);
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) idx.add(i);
            Collections.sort(idx, (a, b) -> Integer.compare(w.get(b), w.get(a)));
            List<Item> top = new ArrayList<>();
            List<Art> topArt = new ArrayList<>();
            for (int i = 0; i < idx.size() && i < MAX_PER_ROW; i++) {
                top.add(items.get(idx.get(i)));
                topArt.add(arts.get(idx.get(i)));
            }
            String app = label(pm, labels, channels.get(id)[0]);
            String name = channels.get(id)[1];
            String title = name == null || name.trim().isEmpty() || name.equalsIgnoreCase(app)
                    ? app : app + " · " + name;
            rows.add(row(title, top, topArt, false));
        }
    }

    /**
     * Portrait cards when most items only have portrait art (posters), landscape cards otherwise (always with
     * forceLandscape), and each item then takes the art that fits its card.
     */
    private static Row row(String title, List<Item> items, List<Art> arts, boolean forceLandscape) {
        int portraitOnly = 0;
        for (Art a : arts) if (portrait(a.posterAspect) && (a.thumbnail == null || portrait(a.thumbnailAspect))) portraitOnly++;
        boolean portrait = !forceLandscape && portraitOnly * 2 > arts.size();
        for (int i = 0; i < items.size(); i++) {
            Art a = arts.get(i);
            Item item = items.get(i);
            if (portrait) {
                item.image = a.poster != null && portrait(a.posterAspect) ? a.poster
                        : a.thumbnail != null && portrait(a.thumbnailAspect) ? a.thumbnail
                        : a.poster != null ? a.poster : a.thumbnail;
            } else {
                item.image = a.poster != null && !portrait(a.posterAspect) ? a.poster
                        : a.thumbnail != null ? a.thumbnail : a.poster;
            }
        }
        return new Row(title, portrait, items);
    }

    /** 2:3, or 5, the movie-poster ratio (AndroidX TvContractCompat names it; the platform SDK does not). */
    private static boolean portrait(int aspect) {
        return aspect == TvContract.PreviewPrograms.ASPECT_RATIO_2_3 || aspect == ASPECT_RATIO_MOVIE_POSTER;
    }

    private static Item read(Cursor c, Columns col, PackageManager pm, Map<String, String> labels, Art art) {
        Item item = new Item();
        item.pkg = col.string(c, TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME);
        item.app = item.pkg != null ? label(pm, labels, item.pkg) : null;
        item.title = col.string(c, TvContract.PreviewPrograms.COLUMN_TITLE);
        String season = col.string(c, TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
        String episode = col.string(c, TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
        String episodeTitle = col.string(c, TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE);
        String number = (season != null ? "S" + season : "") + (season != null && episode != null ? " " : "")
                + (episode != null ? "E" + episode : "");
        item.subtitle = join(number.isEmpty() ? null : number, episodeTitle);
        item.description = col.string(c, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION);
        item.intentUri = col.string(c, TvContract.PreviewPrograms.COLUMN_INTENT_URI);
        item.position = col.longValue(c, TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, 0);
        item.duration = col.longValue(c, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, 0);
        art.poster = col.string(c, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
        art.thumbnail = col.string(c, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI);
        art.posterAspect = col.integer(c, TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, -1);
        art.thumbnailAspect = col.integer(c, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO, -1);
        return item;
    }

    private static String watchNextKind(Context ctx, int type) {
        switch (type) {
            case TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE: return ctx.getString(R.string.watch_next_continue);
            case TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT: return ctx.getString(R.string.watch_next_next);
            case TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW: return ctx.getString(R.string.watch_next_new);
            case TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST: return ctx.getString(R.string.watch_next_watchlist);
            default: return null;
        }
    }

    static String label(PackageManager pm, Map<String, String> cache, String pkg) {
        String cached = cache.get(pkg);
        if (cached != null) return cached;
        String label;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = String.valueOf(pm.getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            label = pkg;
        }
        cache.put(pkg, label);
        return label;
    }

    /** The non-empty parts joined with a middle dot. */
    static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Column lookups by name, so a provider that lacks a column gives a default instead of an exception. */
    private static final class Columns {
        private final Cursor cursor;
        private final Map<String, Integer> index = new HashMap<>();

        Columns(Cursor cursor) {
            this.cursor = cursor;
        }

        private int at(String name) {
            Integer i = index.get(name);
            if (i == null) {
                i = cursor.getColumnIndex(name);
                index.put(name, i);
            }
            return i;
        }

        String string(Cursor c, String name) {
            int i = at(name);
            if (i < 0 || c.isNull(i)) return null;
            String s = c.getString(i);
            return s == null || s.trim().isEmpty() ? null : s;
        }

        int integer(Cursor c, String name, int fallback) {
            int i = at(name);
            return i < 0 || c.isNull(i) ? fallback : c.getInt(i);
        }

        long longValue(Cursor c, String name, long fallback) {
            int i = at(name);
            return i < 0 || c.isNull(i) ? fallback : c.getLong(i);
        }
    }

    /** "12 min left" or "1 h 5 min left", or null when the program has no usable position. */
    static String timeLeft(Context ctx, Item item) {
        if (item.duration <= 0 || item.position <= 0 || item.position >= item.duration) return null;
        long minutes = Math.max(1, (item.duration - item.position) / 60_000);
        return minutes < 60 ? ctx.getString(R.string.minutes_left, minutes)
                : ctx.getString(R.string.hours_left, minutes / 60, minutes % 60);
    }

    /** Opens the program in its app, or the app itself when the program's own link does not open. */
    static void open(Context ctx, Item item) {
        Intent program = programIntent(ctx, item);
        if (program != null && start(ctx, program)) return;
        if (item.pkg != null) {
            PackageManager pm = ctx.getPackageManager();
            Intent app = pm.getLeanbackLaunchIntentForPackage(item.pkg);
            if (app == null) app = pm.getLaunchIntentForPackage(item.pkg);
            if (app != null && start(ctx, app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        }
        Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    /**
     * The row's intent_uri comes from another app, so it is opened like a link: no URI permission grants, no
     * selector, never into this launcher, and kept in the publishing app when the link names no app itself.
     */
    private static Intent programIntent(Context ctx, Item item) {
        if (item.intentUri == null) return null;
        Intent i;
        try {
            if (item.intentUri.startsWith("intent:")) {
                i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
            } else if (item.intentUri.startsWith("android-app:")) {
                i = Intent.parseUri(item.intentUri, Intent.URI_ANDROID_APP_SCHEME);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(item.intentUri));
            }
        } catch (URISyntaxException e) {
            return null;
        }
        i.setFlags(i.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        i.setClipData(null);
        i.setSelector(null);
        ComponentName target = i.getComponent();
        if (target != null && ctx.getPackageName().equals(target.getPackageName())) return null;
        if (ctx.getPackageName().equals(i.getPackage())) return null;
        if (target == null && i.getPackage() == null && item.pkg != null) i.setPackage(item.pkg);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static boolean start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** True for art the loader may fetch: web images and other apps' content, nothing from the file system. */
    static boolean fetchable(String uri) {
        if (uri == null) return false;
        String scheme = Uri.parse(uri).getScheme();
        return "https".equals(scheme) || "http".equals(scheme) || "content".equals(scheme)
                || "android.resource".equals(scheme);
    }
}
