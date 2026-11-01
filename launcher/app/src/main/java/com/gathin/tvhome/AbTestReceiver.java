package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Sets the A/B switches over adb, so both versions can be compared from a computer; the answer lists the switches:
 * am broadcast -n com.gathin.tvhome/.AbTestReceiver --es key panel --es value ours
 * "key" is one name from Variants.ALL, or "all". With no extras it only answers. Protected by
 * WRITE_SECURE_SETTINGS, which the adb shell has and ordinary apps do not.
 */
public final class AbTestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String key = intent.getStringExtra("key"), value = intent.getStringExtra("value");
        if (key != null && value != null) {
            boolean ours = Variants.OURS.equals(value);
            if (key.equals("all")) Variants.setAll(context, ours);
            else Variants.set(context, key, ours);
        }
        setResultData(Variants.describe(context));
    }
}
