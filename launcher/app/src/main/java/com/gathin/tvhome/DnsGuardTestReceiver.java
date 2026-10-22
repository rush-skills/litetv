package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Lets adb copy an outage of the ad-blocking DNS and check the fallback (README). The manifest guards it with
 * WRITE_SECURE_SETTINGS, which the adb shell holds and ordinary apps do not, as for ThemeProvider.
 *   am broadcast -n com.gathin.tvhome/.DnsGuardTestReceiver --es host broken.invalid
 *   am broadcast -n com.gathin.tvhome/.DnsGuardTestReceiver --es host "" --ez retry_now true
 */
public class DnsGuardTestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        DnsGuard guard = DnsGuard.current;
        if (guard != null) guard.test(intent.getStringExtra("host"), intent.getBooleanExtra("retry_now", false));
    }
}
