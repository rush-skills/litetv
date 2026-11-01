package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

import java.util.HashMap;
import java.util.Map;

/**
 * How recently and how much each app was used, for Launcher settings > Sort apps. Android's usage statistics
 * see every launch (from Home, a remote button or the voice search), but need usage access, which only adb can
 * give (build-and-install.sh: appops set ... GET_USAGE_STATS allow). Home also keeps its own small record of the
 * launches it makes, which is all there is without that access, and which survives the power cuts that empty
 * the usage statistics on some TVs.
 */
final class AppUsage {
    private static final String PREFS = "app_usage";
    private static final long DAYS_30_MS = 30L * 24 * 60 * 60 * 1000;
    /** Home reloads its tiles on every return from an app; the statistics are read at most once a minute. */
    private static final long CACHE_MS = 60_000;
    private static Stats cached;
    private static long cachedAt;

    /** Per package: when it was last used and a measure of how much (ms in front, or launches when that is all). */
    static final class Stats {
        final Map<String, Long> lastUsed = new HashMap<>();
        final Map<String, Long> amount = new HashMap<>();
    }

    private AppUsage() {
    }

    /** Called for every launch from Home. */
    static void recordLaunch(Context ctx, String pkg) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long count = prefs.getLong(pkg + "#n", 0);
        prefs.edit().putLong(pkg + "#t", System.currentTimeMillis()).putLong(pkg + "#n", count + 1).apply();
        cached = null;
    }

    /** True when Android lets Home read the usage statistics. */
    static boolean hasAccess(Context ctx) {
        AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** The last 30 days. With usage access the amount is time in front; without it, Home's own launch count. */
    static Stats read(Context ctx) {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < CACHE_MS && now >= cachedAt) return cached;
        cached = load(ctx);
        cachedAt = now;
        return cached;
    }

    private static Stats load(Context ctx) {
        Stats stats = new Stats();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Map<String, Long> launches = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            if (!(e.getValue() instanceof Long) || key.length() < 3) continue;
            String pkg = key.substring(0, key.length() - 2);
            if (key.endsWith("#t")) stats.lastUsed.put(pkg, (Long) e.getValue());
            else if (key.endsWith("#n")) launches.put(pkg, (Long) e.getValue());
        }
        Map<String, UsageStats> system = null;
        if (hasAccess(ctx)) {
            UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            try {
                system = usm != null ? usm.queryAndAggregateUsageStats(now - DAYS_30_MS, now) : null;
            } catch (RuntimeException e) {
                system = null;
            }
        }
        if (system != null && !system.isEmpty()) {
            for (Map.Entry<String, UsageStats> e : system.entrySet()) {
                UsageStats u = e.getValue();
                Long ours = stats.lastUsed.get(e.getKey());
                stats.lastUsed.put(e.getKey(), Math.max(u.getLastTimeUsed(), ours != null ? ours : 0));
                stats.amount.put(e.getKey(), u.getTotalTimeInForeground());
            }
        } else {
            stats.amount.putAll(launches);
        }
        return stats;
    }
}
