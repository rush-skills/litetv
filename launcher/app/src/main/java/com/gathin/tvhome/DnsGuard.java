package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Ad-blocking Private DNS with a way back. In hostname (strict) mode Private DNS has no second server: when the
 * TV cannot reach it, every lookup fails and every app loses the internet, and Google TV's Settings has no
 * Private DNS screen to switch it off. This guard watches the default network. When strict Private DNS keeps it
 * unvalidated for GRACE_MS, it switches to Android's automatic mode (the router's DNS); once the ad-blocking
 * server completes a TLS handshake on port 853 again, it switches back. The retry runs only while the fallback
 * is active, at most once every RETRY_MS.
 *
 * Writing the setting needs android.permission.WRITE_SECURE_SETTINGS, which only adb can grant
 * (build-and-install.sh does). Without it the guard does nothing.
 */
final class DnsGuard {
    private static final String TAG = "DnsGuard";
    static final String HOST = "dns.adguard-dns.com";
    private static final String MODE = "private_dns_mode", SPECIFIER = "private_dns_specifier";
    private static final String STRICT = "hostname", AUTOMATIC = "opportunistic";
    private static final long GRACE_MS = 30_000, RETRY_MS = 10 * 60_000;
    private static final int TIMEOUT_MS = 5_000;
    private static final String PREFS = "dns_guard", KEY_ON = "on", KEY_FALLBACK = "fallback_since";
    /** A stand-in for HOST set by DnsGuardTestReceiver, so an outage can be tested over adb. */
    private static final String KEY_TEST_HOST = "test_host";
    /** The running guard (Home's), for DnsGuardTestReceiver; null while Home is not created. */
    static DnsGuard current;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ConnectivityManager cm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService probe = Executors.newSingleThreadExecutor();
    private final Runnable fallbackCheck = this::fallBackIfStillBroken;
    private final Runnable retry = this::retryAdBlocking;
    private boolean fallbackPending, started;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            evaluate();
        }

        @Override
        public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties lp) {
            evaluate();
        }

        @Override
        public void onLost(@NonNull Network network) {
            evaluate();
        }
    };

    DnsGuard(Context context) {
        ctx = context.getApplicationContext();
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        cm = ctx.getSystemService(ConnectivityManager.class);
    }

    /** Watches the default network until stop(); the callbacks arrive on the main thread. */
    void start() {
        if (started || cm == null) return;
        started = true;
        if (probe.isShutdown()) probe = Executors.newSingleThreadExecutor();
        cm.registerDefaultNetworkCallback(callback, handler);
        current = this;
        evaluate();
    }

    void stop() {
        if (!started) return;
        started = false;
        if (current == this) current = null;
        cm.unregisterNetworkCallback(callback);
        handler.removeCallbacksAndMessages(null);
        fallbackPending = false;
        probe.shutdownNow();
    }

    boolean canWrite() {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The owner's choice. Until the switch is used it follows the TV: on when Private DNS is already this
     * server in strict mode (set over adb before the launcher had a switch).
     */
    boolean isOn() {
        if (!prefs.contains(KEY_ON)) {
            String mode = read(MODE);
            boolean on = mode != null ? STRICT.equals(mode) && HOST.equals(read(SPECIFIER)) : HOST.equals(activeServer());
            prefs.edit().putBoolean(KEY_ON, on).apply();
            return on;
        }
        return prefs.getBoolean(KEY_ON, false);
    }

    /** True while the guard has switched to the router's DNS because the ad-blocking server failed. */
    boolean isFallback() {
        return prefs.getLong(KEY_FALLBACK, 0) > 0;
    }

    /** The settings menu switch. Returns false when the setting could not be written. */
    boolean setOn(boolean on) {
        if (!canWrite()) return false;
        handler.removeCallbacks(fallbackCheck);
        handler.removeCallbacks(retry);
        fallbackPending = false;
        boolean ok = on ? useStrict() : write(MODE, AUTOMATIC);
        if (ok) prefs.edit().putBoolean(KEY_ON, on).putLong(KEY_FALLBACK, 0).apply();
        return ok;
    }

    /**
     * adb test hook (DnsGuardTestReceiver). host: a stand-in for HOST ("" puts HOST back); while ad blocking
     * is on, a stand-in is written as the strict server at once, so a name that does not exist copies an
     * outage. retryNow: run the fallback's retry now instead of after RETRY_MS.
     */
    void test(String testHost, boolean retryNow) {
        if (testHost != null) {
            if (testHost.isEmpty()) prefs.edit().remove(KEY_TEST_HOST).apply();
            else prefs.edit().putString(KEY_TEST_HOST, testHost).apply();
            if (!testHost.isEmpty() && canWrite() && isOn() && !isFallback()) useStrict();
            Log.i(TAG, "test host: " + host());
        }
        if (retryNow && isFallback()) {
            prefs.edit().putLong(KEY_FALLBACK, System.currentTimeMillis() - RETRY_MS).apply();
            scheduleRetry();
        }
        evaluate();
    }

    private String host() {
        return prefs.getString(KEY_TEST_HOST, HOST);
    }

    /** Called on every network change and on Home's onResume. */
    void evaluate() {
        if (!started || !canWrite() || !isOn()) {
            handler.removeCallbacks(fallbackCheck);
            fallbackPending = false;
            return;
        }
        if (isFallback()) {
            scheduleRetry();
            return;
        }
        if (strictAndBroken()) {
            if (!fallbackPending) {
                fallbackPending = true;
                handler.postDelayed(fallbackCheck, GRACE_MS);
            }
        } else if (fallbackPending) {
            handler.removeCallbacks(fallbackCheck);
            fallbackPending = false;
        }
    }

    /**
     * Strict Private DNS with this guard's server is active on the default network, and Android has not
     * validated that network. With no internet at all this is true as well; falling back then costs nothing,
     * and the retry restores ad blocking once the server answers. Another strict server, set by hand, is
     * left alone.
     */
    private boolean strictAndBroken() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;   // Private DNS came in Android 9
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        LinkProperties lp = cm.getLinkProperties(n);
        if (caps == null || lp == null) return false;
        return host().equals(lp.getPrivateDnsServerName())
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void fallBackIfStillBroken() {
        fallbackPending = false;
        if (!started || !canWrite() || !isOn() || isFallback() || !strictAndBroken()) return;
        if (!write(MODE, AUTOMATIC)) return;
        prefs.edit().putLong(KEY_FALLBACK, System.currentTimeMillis()).apply();
        Log.w(TAG, host() + " cannot be reached: switched to automatic DNS");
        Toast.makeText(ctx, R.string.dns_fallback, Toast.LENGTH_LONG).show();
        scheduleRetry();
    }

    private void scheduleRetry() {
        handler.removeCallbacks(retry);
        long due = prefs.getLong(KEY_FALLBACK, 0) + RETRY_MS;
        handler.postDelayed(retry, Math.max(0, due - System.currentTimeMillis()));
    }

    private void retryAdBlocking() {
        if (!started || !canWrite() || !isOn() || !isFallback()) return;
        final String server = host();
        probe.execute(() -> {
            boolean reachable = handshake(server);
            handler.post(() -> {
                if (!started || !isOn() || !isFallback()) return;
                if (reachable && useStrict()) {
                    prefs.edit().putLong(KEY_FALLBACK, 0).apply();
                    Log.i(TAG, server + " answers again: ad-blocking DNS is back on");
                } else {
                    Log.i(TAG, server + " still does not answer");
                    prefs.edit().putLong(KEY_FALLBACK, System.currentTimeMillis()).apply();
                    scheduleRetry();
                }
            });
        });
    }

    /** A TLS handshake with the DNS-over-TLS port, with the certificate checked against the server name. */
    private static boolean handshake(String server) {
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(server, 853), TIMEOUT_MS);
            plain.setSoTimeout(TIMEOUT_MS);
            try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, server, 853, false)) {
                tls.startHandshake();
                return HttpsURLConnection.getDefaultHostnameVerifier().verify(server, tls.getSession());
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The strict Private DNS server Android uses on the default network, or null. */
    private String activeServer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null;
        Network n = cm != null ? cm.getActiveNetwork() : null;
        LinkProperties lp = n != null ? cm.getLinkProperties(n) : null;
        return lp != null ? lp.getPrivateDnsServerName() : null;
    }

    private boolean useStrict() {
        return write(SPECIFIER, host()) && write(MODE, STRICT);
    }

    private boolean write(String key, String value) {
        try {
            return Settings.Global.putString(ctx.getContentResolver(), key, value);
        } catch (SecurityException e) {
            Log.w(TAG, "cannot write " + key, e);
            return false;
        }
    }

    private String read(String key) {
        try {
            return Settings.Global.getString(ctx.getContentResolver(), key);
        } catch (SecurityException e) {
            return null;
        }
    }
}
