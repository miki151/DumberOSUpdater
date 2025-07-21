package eu.dumbdroid.updater;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class UpdateCheckWorker extends JobService {
    private static final String TAG = UpdaterActivity.TAG;
    private static final int JOB_ID = 1;
    private static final String CHANNEL_ID = "dumbdroid_updates";
    public static final String KEY_PREF_NOTIFIED_BUILD = "pref_build_notified_build";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Job started: checking for updates");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String branch = prefs.getString(UpdaterActivity.KEY_PREF_BRANCH, "stable");
        new Thread(() -> {
            long buildTs = UpdaterActivity.checkForUpdates(prefs, branch, UpdaterActivity.getGapps(prefs));

            if (buildTs > 0) {
                long lastNotified = prefs.getLong(KEY_PREF_NOTIFIED_BUILD, 0);
                if (lastNotified < buildTs) {
                    showNotification();
                    prefs.edit()
                        .putLong(KEY_PREF_NOTIFIED_BUILD, buildTs)
                        .apply();
                }
            }
            // No more work to do in background thread
            jobFinished(params, false);
        }).start();
        return true; // our work is done synchronously
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Called if the job is preempted; return true to reschedule
        return true;
    }

    private void showNotification() {
        NotificationManager mgr =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // Create channel on O+  
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Dumbdroid Update Checks",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Notifies when a new ROM build is available");
            mgr.createNotificationChannel(channel);
        }

        // Intent to launch your UpdaterActivity  
        Intent intent = new Intent(this, UpdaterActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("New update available")
            .setContentText("Tap to view and install")
            .setContentIntent(pi)
            .setAutoCancel(true);

        NotificationManagerCompat.from(this).notify(1001, nb.build());
    }
}


