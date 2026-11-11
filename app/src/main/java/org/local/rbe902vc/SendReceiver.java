package org.local.rbe902vc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Debug hook so a sweep does not have to fight the on-screen keyboard:
 * <pre>
 *   adb shell am broadcast -a org.local.rbe902vc.SEND --es u AF      # send usage 0xAF
 *   adb shell am broadcast -a org.local.rbe902vc.SEND --es u B2,DD   # send a two-key combo
 *   adb shell am broadcast -a org.local.rbe902vc.SEND --es adv clone # start clone advertising
 *   adb shell am broadcast -a org.local.rbe902vc.SEND --ez stop true # stop advertising + scanning
 * </pre>
 */
public class SendReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        MainActivity activity = MainActivity.current;
        if (activity == null) return;
        String usages = intent.getStringExtra("u");
        String adv = intent.getStringExtra("adv");
        boolean stop = intent.getBooleanExtra("stop", false);
        if (usages != null) activity.sendHex(usages);
        if (adv != null) activity.startAdvertising(adv);
        if (stop) activity.stopAll();
    }
}
