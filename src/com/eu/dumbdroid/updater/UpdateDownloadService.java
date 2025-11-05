package eu.dumbdroid.updater;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.common.io.CountingInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Foreground service responsible for downloading OTA packages while displaying
 * a persistent notification to the user.
 */
public class UpdateDownloadService extends Service {
    public static final String ACTION_START_DOWNLOAD = "eu.dumbdroid.updater.action.START_DOWNLOAD";
    public static final String ACTION_CANCEL_DOWNLOAD = "eu.dumbdroid.updater.action.CANCEL_DOWNLOAD";
    public static final String ACTION_PROGRESS = "eu.dumbdroid.updater.action.PROGRESS";

    public static final String EXTRA_STATE = "extra_state";
    public static final String EXTRA_PERCENT = "extra_percent";
    public static final String EXTRA_INDETERMINATE = "extra_indeterminate";
    public static final String EXTRA_SPEED = "extra_speed";
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String EXTRA_ERROR = "extra_error";

    private static final String EXTRA_BRANCH = "extra_branch";
    private static final String EXTRA_GAPPS = "extra_gapps";

    public static final int STATE_PREPARING = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_COMPLETED = 2;
    public static final int STATE_ERROR = 3;
    public static final int STATE_CANCELED = 4;

    private static final String TAG = "UpdateDownloadService";
    private static final String CHANNEL_ID = "update_downloads";
    private static final int NOTIFICATION_ID = 1002;

    private static final AtomicBoolean sIsRunning = new AtomicBoolean(false);
    private static volatile ProgressInfo sLastProgress;

    private volatile boolean shouldContinue;
    private volatile boolean cancelRequested;
    private Thread downloadThread;
    private HttpURLConnection downloadConnection;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManagerCompat notificationManager;
    private String preparingText;
    private String downloadingText;

    public static class ProgressInfo {
        public final int state;
        public final int percent;
        public final boolean indeterminate;
        public final String speedText;
        public final String message;
        public final String error;

        ProgressInfo(int state, int percent, boolean indeterminate, String speedText,
                String message, String error) {
            this.state = state;
            this.percent = percent;
            this.indeterminate = indeterminate;
            this.speedText = speedText;
            this.message = message;
            this.error = error;
        }
    }

    public static void startDownload(Context context, String branch, String gapps) {
        if (!sIsRunning.get()) {
            sLastProgress = null;
        }
        Intent intent = new Intent(context, UpdateDownloadService.class);
        intent.setAction(ACTION_START_DOWNLOAD);
        intent.putExtra(EXTRA_BRANCH, branch);
        intent.putExtra(EXTRA_GAPPS, gapps);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void cancelDownload(Context context) {
        Intent intent = new Intent(context, UpdateDownloadService.class);
        intent.setAction(ACTION_CANCEL_DOWNLOAD);
        context.startService(intent);
    }

    public static boolean isRunning() {
        return sIsRunning.get();
    }

    public static ProgressInfo getLastProgress() {
        return sLastProgress;
    }

    public static void clearLastProgress() {
        sLastProgress = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = NotificationManagerCompat.from(this);
        createNotificationChannel();
        preparingText = getString(R.string.update_preparing_text);
        downloadingText = getString(R.string.update_downloading_text);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Dumbdroid update downloads");
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START_DOWNLOAD.equals(action)) {
            if (sIsRunning.get()) {
                ProgressInfo info = sLastProgress;
                if (info != null) {
                    broadcastState(info.state, info.percent, info.indeterminate,
                            info.speedText, info.message, info.error);
                }
                return START_STICKY;
            }

            String branch = intent.getStringExtra(EXTRA_BRANCH);
            String gapps = intent.getStringExtra(EXTRA_GAPPS);
            if (branch == null || gapps == null) {
                Log.e(TAG, "Missing download parameters");
                stopSelf();
                return START_NOT_STICKY;
            }

            shouldContinue = true;
            cancelRequested = false;
            sIsRunning.set(true);
            notificationBuilder = buildNotification();
            updateNotification(preparingText, -1, true, preparingText);
            startForeground(NOTIFICATION_ID, notificationBuilder.build());
            broadcastState(STATE_PREPARING, 0, true, preparingText, preparingText, null);

            downloadThread = new Thread(() -> performDownload(branch, gapps), "UpdateDownload");
            downloadThread.start();
            return START_STICKY;
        } else if (ACTION_CANCEL_DOWNLOAD.equals(action)) {
            requestCancel();
            if (!sIsRunning.get()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        requestCancel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void performDownload(String branch, String gapps) {
        UpdaterActivity.UpdateInfo info = UpdaterActivity.downloadUpdateManifest(branch, gapps);
        if (info == null) {
            finishWithError("Couldn't download manifest.");
            return;
        }
        if (!shouldContinue) {
            finishAsCanceled();
            return;
        }

        String prepareError = runOtaMakeCommand();
        if (!shouldContinue) {
            finishAsCanceled();
            return;
        }
        if (prepareError != null && !prepareError.isEmpty()) {
            finishWithError("Error creating image: " + prepareError);
            return;
        }

        downloadPackage(info);
    }

    private void sleepFor(int milli) {
        try {
            Thread.sleep(milli);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String runOtaMakeCommand() {
        updateNotification(preparingText, -1, true, preparingText);
        broadcastState(STATE_PREPARING, 0, true, preparingText, preparingText, null);
        SystemProperties.set("ctl.start", "phh-ota-make");
	sleepFor(1000);
        while (shouldContinue && !"stopped".equals(SystemProperties.get("init.svc.phh-ota-make", ""))) {
            String percent = SystemProperties.get("phh.ota.progress", "");
            if (!percent.isEmpty()) {
                try {
                    int value = Integer.parseInt(percent);
                    broadcastState(STATE_PREPARING, value, false, preparingText, preparingText, null);
                    updateNotification(preparingText, value, false, preparingText);
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse preparation percent: " + percent, e);
                }
            }
            sleepFor(100);
        }

        if (!shouldContinue) {
            return null;
        }
        return SystemProperties.get("phh.ota.error", "");
    }

    private String runOtaSwitchCommand() {
/*        updateNotification(preparingText, -1, true, preparingText);
        broadcastState(STATE_PREPARING, 0, true, preparingText, preparingText, null);*/
        SystemProperties.set("ctl.start", "phh-ota-switch");
	sleepFor(1000);
        while (shouldContinue && !"stopped".equals(SystemProperties.get("init.svc.phh-ota-switch", ""))) {
            sleepFor(100);
        }

        if (!shouldContinue) {
            return null;
        }
        return SystemProperties.get("phh.ota.error", "");
    }


    private void downloadPackage(UpdaterActivity.UpdateInfo info) {
        InputStream rawInput = null;
        CountingInputStream countingInput = null;
        GZIPInputStream gzipInput = null;
        BufferedOutputStream out = null;

        try {
            URL url = new URL(info.url);
            downloadConnection = (HttpURLConnection) url.openConnection();
            downloadConnection.setConnectTimeout(10000);
            downloadConnection.setReadTimeout(10000);
            downloadConnection.connect();

            int fileLength = downloadConnection.getContentLength();
            boolean indeterminate = fileLength <= 0;

            rawInput = new BufferedInputStream(downloadConnection.getInputStream());
            countingInput = new CountingInputStream(rawInput);
            gzipInput = new GZIPInputStream(countingInput);
            out = new BufferedOutputStream(new FileOutputStream("/dev/phh-ota", false));

            byte[] buffer = new byte[4096];
            long startTime = System.currentTimeMillis();
            long lastUpdate = 0;
            String lastSpeedText = null;

            broadcastState(STATE_DOWNLOADING, 0, indeterminate,
                    indeterminate ? preparingText : null, downloadingText, null);
            updateNotification(downloadingText, 0, indeterminate,
                    indeterminate ? preparingText : null);

            int bytesRead;
            while (shouldContinue && (bytesRead = gzipInput.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                long totalBytes = countingInput.getCount();
                int percent = indeterminate ? 0 : (int) (totalBytes * 100 / fileLength);
                long now = System.currentTimeMillis();
                if (now - lastUpdate >= 500) {
                    double speed = totalBytes / Math.max((now - startTime) / 1000.0, 0.001);
                    lastSpeedText = String.format(Locale.US, "%.1f KB/s", speed / 1024);
                    broadcastState(STATE_DOWNLOADING, percent, indeterminate, lastSpeedText,
                            downloadingText, null);
                    updateNotification(downloadingText, percent, indeterminate, lastSpeedText);
                    lastUpdate = now;
                }
            }

            out.flush();

            if (!indeterminate) {
                broadcastState(STATE_DOWNLOADING, 100, false, lastSpeedText, downloadingText, null);
                updateNotification(downloadingText, 100, false, lastSpeedText);
            }
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            if (cancelRequested || !shouldContinue) {
                finishAsCanceled();
            } else {
                finishWithError("Download failed: " + e.getMessage());
            }
            return;
        } finally {
            closeQuietly(gzipInput);
            closeQuietly(countingInput);
            closeQuietly(rawInput);
            closeQuietly(out);
            if (downloadConnection != null) {
                downloadConnection.disconnect();
                downloadConnection = null;
            }
        }

        if (!shouldContinue || cancelRequested) {
            finishAsCanceled();
            return;
        }
        String verifyError = runOtaSwitchCommand();
        if (!shouldContinue) {
            finishAsCanceled();
            return;
        }
        if (verifyError != null && !verifyError.isEmpty()) {
            finishWithError("Error verifying image: " + verifyError);
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
                .remove(UpdaterActivity.KEY_UPDATE_VERSION)
                .putLong(UpdaterActivity.KEY_DOWNLOADED_BUILD, info.buildTimeStamp)
                .apply();

        finishSuccessfully();
    }

    private void requestCancel() {
        cancelRequested = true;
        shouldContinue = false;
        if (downloadConnection != null) {
            downloadConnection.disconnect();
            downloadConnection = null;
        }
    }

    private void finishSuccessfully() {
        complete(STATE_COMPLETED, 100, null, "Download complete!", null, false);
    }

    private void finishWithError(String message) {
        Log.e(TAG, message);
        complete(STATE_ERROR, 0, null, message, message, false);
    }

    private void finishAsCanceled() {
        complete(STATE_CANCELED, 0, null, "Download canceled", null, true);
    }

    private synchronized void complete(int state, int percent, String speedText,
            String message, String error, boolean removeNotification) {
        if (!sIsRunning.getAndSet(false)) {
            return;
        }

        shouldContinue = false;
        cancelRequested = false;
        downloadThread = null;

        if (notificationBuilder != null) {
            notificationBuilder.setOngoing(false);
            notificationBuilder.setProgress(0, 0, false);
            notificationBuilder.setContentText(message);
            notificationBuilder.setSubText(speedText);
            notificationBuilder.setAutoCancel(!removeNotification);
            if (!removeNotification) {
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
            }
        }

        if (removeNotification) {
            stopForeground(true);
        } else {
            stopForeground(false);
        }

        broadcastState(state, percent, false, speedText, message, error);
        stopSelf();
    }

    private NotificationCompat.Builder buildNotification() {
        Intent launchIntent = new Intent(this, UpdaterActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update)
                .setContentTitle(getString(R.string.app_name))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(pendingIntent);
    }

    private void updateNotification(String message, int percent, boolean indeterminate, String speedText) {
        if (notificationBuilder == null) {
            return;
        }

        notificationBuilder.setContentText(message);
        notificationBuilder.setSubText(speedText);
        boolean useIndeterminate = indeterminate || percent < 0;
        int progressValue = percent < 0 ? 0 : Math.max(0, Math.min(100, percent));
        notificationBuilder.setProgress(100, progressValue, useIndeterminate);
        notificationBuilder.setOngoing(true);
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private void broadcastState(int state, int percent, boolean indeterminate, String speedText,
            String message, String error) {
        sLastProgress = new ProgressInfo(state, percent, indeterminate, speedText, message, error);

        Intent progressIntent = new Intent(ACTION_PROGRESS);
        progressIntent.setPackage(getPackageName());
        progressIntent.putExtra(EXTRA_STATE, state);
        progressIntent.putExtra(EXTRA_PERCENT, percent);
        progressIntent.putExtra(EXTRA_INDETERMINATE, indeterminate);
        if (speedText != null) {
            progressIntent.putExtra(EXTRA_SPEED, speedText);
        }
        if (message != null) {
            progressIntent.putExtra(EXTRA_MESSAGE, message);
        }
        if (error != null) {
            progressIntent.putExtra(EXTRA_ERROR, error);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserHandle currentUser = Process.myUserHandle();
            try {
                sendBroadcastAsUser(progressIntent, currentUser);
                return;
            } catch (SecurityException se) {
                Log.w(TAG, "Failed to send broadcast for user " + currentUser + ", falling back", se);
            }
        }
        sendBroadcast(progressIntent);
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close stream", e);
            }
        }
    }
}
