package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * System notices for the main process: the TV booted, this app was updated, or one of our alarms (night maintenance, the
 * VPN guard, the sleep timer) went off. Not exported.
 */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            AppLog.w("the TV booted");
            HomeMode.bootCheck(context);
            Maintenance.schedule(context);
            VpnGuard.schedule(context);
            VpnServers.schedule(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AppLog.w("the app was updated to " + Updater.installed(context));
            Updater.announce(context);   // an update started from Settings: say it is done
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
            ModeJob.schedule(context, ModeJob.MEMORY, HomeMode.FREE, 120_000);   // refresh the numbers on the settings screen
            Maintenance.schedule(context);
            VpnGuard.schedule(context);
            VpnServers.schedule(context);
        } else if (Maintenance.ACTION.equals(action)) {
            if (Prefs.maintenance()) ModeJob.schedule(context, ModeJob.MAINTENANCE, "", 0);
            Maintenance.schedule(context);   // the next night
        } else if (VpnGuard.NETWORK.equals(action)) {
            VpnGuard.onNetworkEvent(context);   // a VPN came or went: look again in a few seconds
        } else if (VpnGuard.ACTION.equals(action)) {
            if (Prefs.vpnGuard()) ModeJob.schedule(context, ModeJob.VPNGUARD, "", 0);
            VpnGuard.schedule(context);      // the next look (and the network callback stays registered)
        } else if (VpnServers.ACTION.equals(action)) {
            if (Prefs.serverPick()) ModeJob.schedule(context, ModeJob.SERVERS, "auto", 0);
            VpnServers.schedule(context);       // the next time, in a few hours
        } else if (SleepTimer.ACTION.equals(action)) {
            ModeJob.schedule(context, ModeJob.SLEEP, "", 0);
        }
    }
}
