package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Card art for the Up Next screen. Two threads fetch, decode at the card's size in RGB_565 (2 bytes a pixel) and
 * keep the results in a small memory cache: the TV has about 1 GB of RAM. Nothing is fetched until a card is
 * bound, and nothing is fetched again while the screen is open.
 */
final class ArtLoader {
    private static final int TIMEOUT_MS = 8_000, MAX_BYTES = 4 << 20;

    private final ContentResolver resolver;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;

    ArtLoader(Context ctx) {
        resolver = ctx.getContentResolver();
        int max = (int) Math.min(8L << 20, Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<String, Bitmap>(max) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** Shows the art in the view, or clears it; a recycled view keeps only the art bound to it last. */
    void load(String uri, ImageView view, int width, int height) {
        view.setTag(R.id.art_uri, uri);
        Bitmap cached = uri != null ? cache.get(uri) : null;
        view.setImageBitmap(cached);
        if (cached != null || !UpNext.fetchable(uri) || pool.isShutdown()) return;
        pool.execute(() -> {
            Bitmap bmp = decode(uri, width, height);
            main.post(() -> {
                if (bmp == null) return;
                cache.put(uri, bmp);
                if (uri.equals(view.getTag(R.id.art_uri))) view.setImageBitmap(bmp);
            });
        });
    }

    void trim() {
        cache.evictAll();
    }

    void shutdown() {
        pool.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }

    private Bitmap decode(String uri, int width, int height) {
        byte[] data;
        try {
            data = read(uri);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private byte[] read(String uri) throws IOException {
        Uri parsed = Uri.parse(uri);
        String scheme = parsed.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            try {
                if (conn.getResponseCode() / 100 != 2) return null;
                try (InputStream in = conn.getInputStream()) {
                    return readAll(in);
                }
            } finally {
                conn.disconnect();
            }
        }
        try (InputStream in = resolver.openInputStream(parsed)) {
            return in != null ? readAll(in) : null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 << 10];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) return null;
        }
        return out.toByteArray();
    }
}
