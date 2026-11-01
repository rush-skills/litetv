package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The TV's inputs as Home's Inputs menu and the quick settings panel list them: HDMI ports (with connection
 * state) in port order, then AV and the rest, then Live TV. Inputs hidden in the TV's Settings > Inputs are
 * left out.
 */
final class TvInputs {

    /** One row of the list. */
    static final class Row {
        final int icon;
        final String label;
        /** A TvInputManager.INPUT_STATE_* value, or -1 where the TV's answer means nothing. */
        final int state;
        /** The first row of a new group (AV after the HDMI ports, Live TV), drawn with a line above it. */
        final boolean newGroup;
        /** The passthrough input this row switches to; null for Live TV. */
        final String inputId;
        /** Live TV's intent, when inputId is null. */
        final Intent liveTv;

        private Row(int icon, String label, int state, boolean newGroup, String inputId, Intent liveTv) {
            this.icon = icon;
            this.label = label;
            this.state = state;
            this.newGroup = newGroup;
            this.inputId = inputId;
            this.liveTv = liveTv;
        }
    }

    private TvInputs() {
    }

    static List<Row> list(Context ctx, String liveTvPkg, String liveTvAction) {
        List<Row> rows = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(ctx)).compareToIgnoreCase(String.valueOf(b.loadLabel(ctx)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(ctx)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(ctx)) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(ctx);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = info.loadCustomLabel(ctx);
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                boolean newGroup = !hdmi && !rows.isEmpty() && !sawNonHdmi;
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = device.loadCustomLabel(ctx);
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(ctx);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " · " + label;
                        int state = hdmiStateBogus ? -1 : tim.getInputState(device.getId());
                        rows.add(new Row(R.drawable.ic_input, deviceLabel, state, newGroup, device.getId(), null));
                        newGroup = false;
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " · " + label;
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(info.getId());
                rows.add(new Row(hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state, newGroup,
                        info.getId(), null));
            }
        }
        Intent liveTv = liveTvIntent(ctx, liveTvPkg, liveTvAction);
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId),
                            "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(ctx.getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            rows.add(new Row(R.drawable.ic_livetv, ctx.getString(R.string.live_tv), -1, !rows.isEmpty(), null, liveTv));
        }
        return rows;
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    static Intent liveTvIntent(Context ctx, String liveTvPkg, String liveTvAction) {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return ctx.getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /**
     * Switches to the row's input or opens Live TV; false when nothing on the TV takes it. An input opens
     * the standard way, ACTION_VIEW on its channel URI, which the Live TV app handles; where nothing takes
     * that, the configured `input_action` is sent with the same URI instead (XGIMI's HDMI player only
     * answers com.xgimi.action.hdmiPlayer).
     */
    static boolean open(Context ctx, Row row, String inputAction) {
        if (row.inputId == null) return start(ctx, new Intent(row.liveTv));
        Uri uri = TvContract.buildChannelUriForPassthroughInput(row.inputId);
        if (start(ctx, new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"))) {
            return true;
        }
        return !inputAction.isEmpty()
                && start(ctx, new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    private static boolean start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }
}
