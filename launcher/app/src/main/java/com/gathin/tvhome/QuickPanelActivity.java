package com.gathin.tvhome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Our version of the quick panel (A/B test, panel; upstream's is QuickSettingsActivity). The panel the remote's
 * Settings button opens when {@code settings_key_panel} is on (HomeKeyService): a glass
 * card at the top right with the time, six tiles (Picture, Sound, Inputs, Bluetooth, Network, Ad blocking) and rows
 * for TV settings, Apps and Launcher settings, over Home or whatever app is playing. Translucent and in a task of its own,
 * so the app behind keeps running and Back returns to it. Pressing Settings again closes the panel. The Ad
 * blocking tile switches in place, and the Inputs tile lists the TV's inputs in the same card; every other tile
 * opens a Settings screen and closes the panel. HomeKeyService opens the panel straight on its inputs with
 * EXTRA_INPUTS for the remote's Input button, so the TV's own source panel is not needed.
 */
public final class QuickPanelActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    /** Open on the input list (the remote's Input button); Back then closes the panel. */
    static final String EXTRA_INPUTS = "com.gathin.tvhome.extra.INPUTS";
    /** The TV's own input picker, for a TV whose TvInputManager lists nothing. */
    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** Android TV Settings' own sound screen; android.settings.SOUND_SETTINGS can match two screens there. */
    private static final String ACTION_VIEW_SOUND = "com.android.tv.action.VIEW_SOUND_SETTINGS";
    /**
     * Android TV Settings' "Remotes and accessories" page (Bluetooth switch, Pair accessory, the paired devices).
     * Google TV has no Bluetooth settings action, so it opens as a slice page; SliceActivity requires
     * WRITE_SECURE_SETTINGS, which build-and-install.sh grants for the DNS switch.
     */
    private static final String ACTION_SLICE_SETTINGS = "android.settings.SLICE_SETTINGS";
    private static final String ACCESSORIES_SLICE = "content://com.android.tv.settings.accessories.sliceprovider/general";
    /** Android TV's "pair a remote or accessory" screen, for a TV with neither of the screens above. */
    private static final String ACTION_PAIR_ACCESSORY = "com.google.android.intent.action.CONNECT_INPUT";

    private LinearLayout tiles, tileRow;
    private DnsGuard dns;
    private View dnsTile, inputsTile, mainPage, inputsPage;
    /** The input list was opened from the Inputs tile, so Back returns to the tiles. */
    private boolean inputsFromTiles;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_panel);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        // Home's guard when Home is up, so a switch here also cancels its pending fallback and retry.
        dns = DnsGuard.current != null ? DnsGuard.current : new DnsGuard(this);
        tiles = findViewById(R.id.tiles);

        View first = addTile(R.drawable.ic_picture, R.string.quick_picture_tile, null,
                opener(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        addTile(R.drawable.ic_sound, R.string.quick_sound, null,
                opener(new Intent(ACTION_VIEW_SOUND), new Intent(Settings.ACTION_SOUND_SETTINGS)));
        inputsTile = addTile(R.drawable.ic_input, R.string.inputs, null, v -> showInputs(true));
        addTile(R.drawable.ic_bluetooth, R.string.quick_bluetooth, bluetoothState(),
                opener(new Intent(ACTION_SLICE_SETTINGS).setPackage("com.android.tv.settings")
                                .putExtra("slice_uri", ACCESSORIES_SLICE)
                                .putExtra("slice_title", getString(R.string.quick_accessories)),
                        new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), new Intent(ACTION_PAIR_ACCESSORY)));
        boolean ethernet = onEthernet();
        addTile(ethernet ? R.drawable.ic_ethernet : R.drawable.ic_wifi, R.string.quick_network, networkName(ethernet),
                opener(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        dnsTile = addTile(R.drawable.ic_shield, R.string.quick_ad_blocking, "", v -> toggleAdBlocking());
        showAdBlocking();

        LinearLayout links = findViewById(R.id.links);
        addLink(links, R.drawable.ic_settings, R.string.tv_settings, new Intent(Settings.ACTION_SETTINGS));
        addLink(links, R.drawable.ic_apps, R.string.quick_apps, new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addLink(links, R.drawable.ic_home_settings, R.string.launcher_settings,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        mainPage = findViewById(R.id.main_page);
        inputsPage = findViewById(R.id.inputs_page);
        if (getIntent().getBooleanExtra(EXTRA_INPUTS, false)) showInputs(false);
        else first.requestFocus();

        // Slides in from the screen edge it is pinned to.
        View panel = findViewById(R.id.panel);
        float shift = 24 * getResources().getDisplayMetrics().density;
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        panel.setAlpha(0f);
        panel.setTranslationX(rtl ? -shift : shift);
        panel.animate().alpha(1f).translationX(0f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Settings pressed again while the panel is up closes it; Input shows the inputs, or closes them. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_INPUTS, false) && inputsPage.getVisibility() != View.VISIBLE) {
            showInputs(true);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (inputsPage.getVisibility() == View.VISIBLE && inputsFromTiles) {
            inputsPage.setVisibility(View.GONE);
            mainPage.setVisibility(View.VISIBLE);
            inputsTile.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    /** Two tiles to a row, in the order added. */
    private View addTile(int icon, int title, CharSequence status, View.OnClickListener action) {
        if (tileRow == null || tileRow.getChildCount() == 2) {
            tileRow = new LinearLayout(this);
            tileRow.setOrientation(LinearLayout.HORIZONTAL);
            tiles.addView(tileRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        View tile = getLayoutInflater().inflate(R.layout.item_quick_tile, tileRow, false);
        ((ImageView) tile.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
        setStatus(tile, status);
        tile.setOnClickListener(action);
        tile.setOnFocusChangeListener((v, focused) ->
                v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(120).start());
        tileRow.addView(tile);
        return tile;
    }

    private static void setStatus(View tile, CharSequence status) {
        TextView line = tile.findViewById(R.id.status);
        line.setText(status);
        line.setVisibility(status == null || status.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addLink(LinearLayout links, int icon, int title, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_quick_link, links, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(opener(target));
        links.addView(row);
    }

    /** Opens the first of the intents the TV has a screen for, then closes the panel. */
    private View.OnClickListener opener(Intent... targets) {
        return v -> {
            for (Intent target : targets) {
                try {
                    startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                }
            }
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        };
    }

    /** Swaps the tiles for the TV's inputs: HDMI ports with their state, AV, then Live TV. */
    private void showInputs(boolean fromTiles) {
        Config config = Config.load(this);
        LinearLayout rows = findViewById(R.id.input_rows);
        rows.removeAllViews();
        for (TvInputs.Row row : TvInputs.list(this, config.liveTvPackage, config.liveTvAction)) {
            if ((row.newGroup || row.inputId == null) && rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, row, config.inputAction);
        }
        if (rows.getChildCount() == 0) {
            // Nothing in TvInputManager: hand over to the TV's own picker, if it has one.
            opener(new Intent(ACTION_VIEW_INPUTS)).onClick(inputsTile);
            return;
        }
        inputsFromTiles = fromTiles;
        mainPage.setVisibility(View.GONE);
        inputsPage.setVisibility(View.VISIBLE);
        rows.getChildAt(0).requestFocus();
    }

    private void addInputRow(LinearLayout rows, TvInputs.Row input, String inputAction) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(input.icon);
        ((TextView) row.findViewById(R.id.title)).setText(input.label);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // Only definite states are shown, as in Home's Inputs menu.
        if (input.state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
        } else if (input.state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            if (TvInputs.open(this, input, inputAction)) finish();
            else Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
        int m = Math.round(5 * getResources().getDisplayMetrics().density);
        lp.setMargins(m, 0, m, 0);
        rows.addView(row, lp);
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
        int v = Math.round(6 * density), h = Math.round(17 * density);
        lp.setMargins(h, v, h, v);
        rows.addView(line, lp);
    }

    private void toggleAdBlocking() {
        if (!dns.canWrite()) {
            Toast.makeText(this, R.string.dns_no_permission, Toast.LENGTH_LONG).show();
            return;
        }
        if (!dns.setOn(!dns.isOn())) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        showAdBlocking();
    }

    private void showAdBlocking() {
        boolean on = dns.canWrite() && dns.isOn();
        int status = !dns.canWrite() ? R.string.quick_needs_adb
                : !on ? R.string.quick_off
                : dns.isFallback() ? R.string.quick_paused : R.string.quick_on;
        setStatus(dnsTile, getText(status));
        dnsTile.setActivated(on && !dns.isFallback());
    }

    /** On or Off; null where the TV has no Bluetooth or does not say. */
    private CharSequence bluetoothState() {
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) return null;
        try {
            return getText(adapter.isEnabled() ? R.string.quick_on : R.string.quick_off);
        } catch (SecurityException e) {
            return null;
        }
    }

    private boolean onEthernet() {
        NetworkCapabilities caps = activeCaps();
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    /** The Wi-Fi name (Home holds the location permission Android asks for it), Ethernet or Offline. */
    private CharSequence networkName(boolean ethernet) {
        NetworkCapabilities caps = activeCaps();
        if (caps == null) return getText(R.string.offline);
        if (ethernet) return getText(R.string.ethernet);
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            String ssid = info != null ? info.getSSID() : null;
            if (ssid != null) {
                ssid = ssid.replace("\"", "").trim();
                if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) return ssid;
            }
        } catch (RuntimeException ignored) {
        }
        return getText(R.string.wifi);
    }

    private NetworkCapabilities activeCaps() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        return n != null ? cm.getNetworkCapabilities(n) : null;
    }
}
