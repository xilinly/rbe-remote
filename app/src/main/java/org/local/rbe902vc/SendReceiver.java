package org.local.rbe902vc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Debug hook so tests do not have to fight the on-screen keyboard. Always send it with an explicit
 * component, otherwise Android 14 drops the implicit broadcast:
 * <pre>
 *   adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --es u AF
 *   adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --es u B2,DD
 *   adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --es adv clone
 *   adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --ez power true
 *   adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --es consumer 30
 *   adb shell am broadcast -n org.local.rbe902vc/.SendReceiver -a org.local.rbe902vc.SEND --ez stop true
 * </pre>
 */
public class SendReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        MainActivity activity = MainActivity.current;
        if (activity == null) return;
        String usages = intent.getStringExtra("u");
        String adv = intent.getStringExtra("adv");
        String consumer = intent.getStringExtra("consumer");
        String wifi = intent.getStringExtra("wifi");
        boolean power = intent.getBooleanExtra("power", false);
        boolean stop = intent.getBooleanExtra("stop", false);
        if (usages != null) activity.sendHex(usages);
        if (adv != null) activity.startAdvertising(adv);
        if (power) activity.sendPower();
        if (consumer != null) activity.sendConsumerHex(consumer);
        if (wifi != null) {
            if ("status".equals(wifi)) activity.tvStatus();
            else activity.tvPower(wifi);
        }
        if (stop) activity.stopAll();
    }
}
