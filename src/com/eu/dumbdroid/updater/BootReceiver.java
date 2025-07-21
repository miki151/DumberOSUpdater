package eu.dumbdroid.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;




public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        UpdaterActivity.schedulePeriodicWorker(ctx);
    }
}

