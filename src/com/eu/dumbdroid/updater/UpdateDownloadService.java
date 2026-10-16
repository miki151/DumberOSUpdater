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
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Base64;

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
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
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

    private static final String EXTRA_BRANCH = "extra_branch";
    private static final String EXTRA_GAPPS = "extra_gapps";

    public static final int STATE_PREPARING = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_COMPLETED = 2;
    public static final int STATE_ERROR = 3;
    public static final int STATE_CANCELED = 4;
    public static final int STATE_SIGNATURE_WARNING = 5;
    public static final int STATE_FINALIZING = 6;

    private static final String TAG = "UpdateDownloadService";
    private static final String CHANNEL_ID = "update_downloads";
    private static final int NOTIFICATION_ID = 1002;
    private static final String ACTION_CONTINUE_WITHOUT_SIGNATURE =
            "eu.dumbdroid.updater.action.CONTINUE_WITHOUT_SIGNATURE";

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
    private final Object decisionLock = new Object();
    private SignatureDecisionWaiter pendingSignatureDecision;

    private static final class SignatureDecisionWaiter {
        boolean decided;
        boolean proceed;
    }

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
        synchronized (UpdaterActivity.STATE_LOCK) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (sIsRunning.get() || UpdaterActivity.isRebootPending(prefs)) return;
            ++UpdaterActivity.checkGeneration;
            sLastProgress = null;
            sIsRunning.set(true); // Reserve immediately, before the service is dispatched.
            Intent intent = new Intent(context, UpdateDownloadService.class);
            intent.setAction(ACTION_START_DOWNLOAD);
            intent.putExtra(EXTRA_BRANCH, branch);
            intent.putExtra(EXTRA_GAPPS, gapps);
            try {
                ContextCompat.startForegroundService(context, intent);
                UpdateCheckWorker.clearNotification(context);
            } catch (RuntimeException e) {
                sIsRunning.set(false);
                throw e;
            }
        }
    }

    public static void cancelDownload(Context context) {
        Intent intent = new Intent(context, UpdateDownloadService.class);
        intent.setAction(ACTION_CANCEL_DOWNLOAD);
        context.startService(intent);
    }

    public static void continueWithoutSignature(Context context) {
        Intent intent = new Intent(context, UpdateDownloadService.class);
        intent.setAction(ACTION_CONTINUE_WITHOUT_SIGNATURE);
        context.startService(intent);
    }

    public static boolean isRunning() {
        return sIsRunning.get();
    }

    public static ProgressInfo getLastProgress() {
        return sLastProgress;
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
            if (downloadThread != null) {
                ProgressInfo info = sLastProgress;
                if (info != null) {
                    broadcastState(info.state, info.percent, info.indeterminate,
                            info.speedText, info.message, info.error);
                }
                return START_NOT_STICKY;
            }

            String branch = intent.getStringExtra(EXTRA_BRANCH);
            String gapps = intent.getStringExtra(EXTRA_GAPPS);
            if (branch == null || gapps == null) {
                sIsRunning.set(false);
                Log.e(TAG, "Missing download parameters");
                stopSelf();
                return START_NOT_STICKY;
            }

            shouldContinue = true;
            cancelRequested = false;
            synchronized (UpdaterActivity.STATE_LOCK) {
                sIsRunning.set(true);
                UpdateCheckWorker.clearNotification(this);
            }
            notificationBuilder = buildNotification();
            updateNotification(preparingText, -1, true, preparingText);
            startForeground(NOTIFICATION_ID, notificationBuilder.build());
            broadcastState(STATE_PREPARING, 0, true, preparingText, preparingText, null);

            downloadThread = new Thread(() -> performDownload(branch, gapps), "UpdateDownload");
            downloadThread.start();
            return START_NOT_STICKY;
        } else if (ACTION_CANCEL_DOWNLOAD.equals(action)) {
            requestCancel();
            if (!sIsRunning.get()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        } else if (ACTION_CONTINUE_WITHOUT_SIGNATURE.equals(action)) {
            resolveSignatureDecision(true);
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
        // A foreground service alone does not prevent CPU suspend. Each worker
        // owns its lock through native cancellation cleanup and stream closure.
        PowerManager.WakeLock wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":Update");
        try {
            // No fixed expiry: preparation or a slow download can take a long
            // time. Every exit releases the lock in finally.
            wakeLock.acquire();
            prepareAndDownload(branch, gapps, wakeLock);
        } catch (Exception e) {
            Log.e(TAG, "Update failed", e);
            if (cancelRequested || !shouldContinue) {
                finishAsCanceled();
            } else {
                finishWithError("Update failed: " + e.getMessage());
            }
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    private void prepareAndDownload(String branch, String gapps, PowerManager.WakeLock wakeLock) {
        UpdaterActivity.UpdateInfo info = UpdaterActivity.downloadUpdateManifest(branch, gapps, 0);
        if (!shouldContinue) {
            finishAsCanceled();
            return;
        }
        if (info == null) {
            finishWithError("Couldn't download manifest.");
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

        downloadPackage(info, wakeLock);
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
        final String pending = "updater_waiting_for_start";
        // Snapshot the preference before starting native preparation. Disabling
        // it selects libfiemap's original allocation/zeroing path for this attempt.
        boolean adaptivePacing = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(UpdaterActivity.KEY_PREF_ADAPTIVE_PACING, true);
        SystemProperties.set("phh.ota.adaptive_pacing", Boolean.toString(adaptivePacing));
        SystemProperties.set("phh.ota.cancel", "false");
        SystemProperties.set("phh.ota.progress", "0");
        SystemProperties.set("phh.ota.error", pending);
        SystemProperties.set("ctl.start", "phh-ota-make");
        int lastPercent = -1;
        boolean cancelSent = false;
        while (true) {
            // Native preparation cooperatively cancels even during a pressure
            // pause. Wait for its cleanup before allowing another attempt.
            if (!shouldContinue && !cancelSent) {
                SystemProperties.set("phh.ota.cancel", "true");
                cancelSent = true;
            }
            String error = SystemProperties.get("phh.ota.error", "");
            String state = SystemProperties.get("init.svc.phh-ota-make", "");
            if (!pending.equals(error) && "stopped".equals(state)) {
                return shouldContinue ? error : null;
            }
            String percent = SystemProperties.get("phh.ota.progress", "");
            if (shouldContinue && !percent.isEmpty()) {
                try {
                    int value = Integer.parseInt(percent);
                    if (value != lastPercent) {
                        broadcastState(STATE_PREPARING, value, false, preparingText, preparingText, null);
                        updateNotification(preparingText, value, false, preparingText);
                        lastPercent = value;
                    }
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Failed to parse preparation percent: " + percent, e);
                }
            }
            sleepFor(200);
        }
    }

    private String runOtaSwitchCommand() {
        SystemProperties.set("ctl.start", "phh-ota-switch");
	sleepFor(1000);
        // Once the slot switch starts, wait for its result even if a late cancel arrives.
        // Reporting cancellation here could hide an image that is already staged for reboot.
        while (!"stopped".equals(SystemProperties.get("init.svc.phh-ota-switch", ""))) {
            sleepFor(100);
        }

        return SystemProperties.get("phh.ota.error", "");
    }


    private void downloadPackage(UpdaterActivity.UpdateInfo info, PowerManager.WakeLock wakeLock) {
        final Signature signatureVerifier;
        try {
            signatureVerifier = createSignatureVerifier();
        } catch (Exception e) {
            finishWithError("Failed to load public key: " + e.getMessage());
            return;
        }

        InputStream rawInput = null;
        CountingInputStream countingInput = null;
        GZIPInputStream gzipInput = null;
        BufferedOutputStream out = null;
        Exception downloadError = null;

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
                signatureVerifier.update(buffer, 0, bytesRead);
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
            downloadError = e;
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
        if (downloadError != null) {
            finishWithError("Download failed: " + downloadError.getMessage());
            return;
        }
        showFinalizing();
        String signatureWarning = null;
        try {
            byte[] signatureBytes = downloadSignatureFile(info.url);
            if (!shouldContinue || cancelRequested) {
                finishAsCanceled();
                return;
            }
            if (signatureBytes == null || signatureBytes.length == 0) {
                signatureWarning = "Signature missing for this build.";
            } else if (!signatureVerifier.verify(signatureBytes)) {
                signatureWarning = "Invalid image signature.";
            }
        } catch (Exception e) {
            signatureWarning = "Signature verification failed: " + e.getMessage();
        }

        if (signatureWarning != null) {
            if (!promptUserForUnverifiedInstall(signatureWarning, wakeLock)) {
                finishAsCanceled();
                return;
            }
        }
        if (!shouldContinue || cancelRequested) {
            finishAsCanceled();
            return;
        }
        showFinalizing();
        String verifyError = runOtaSwitchCommand();
        if (verifyError != null && !verifyError.isEmpty()) {
            finishWithError("Error verifying image: " + verifyError);
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        synchronized (UpdaterActivity.STATE_LOCK) {
            prefs.edit()
                    .remove(UpdaterActivity.KEY_UPDATE_VERSION)
                    .putInt(UpdaterActivity.KEY_LAST_BOOT_COUNT, UpdaterActivity.currentBootCount(this))
                    .putLong(UpdaterActivity.KEY_DOWNLOADED_BUILD, info.buildTimeStamp)
                    .apply();
        }

        finishSuccessfully();
    }

    private void showFinalizing() {
        String message = getString(R.string.update_finalizing_text);
        notificationBuilder = buildNotification();
        broadcastState(STATE_FINALIZING, 0, true, null, message, null);
        updateNotification(message, 0, true, null);
    }

    private Signature createSignatureVerifier() throws GeneralSecurityException, IOException {
        PublicKey publicKey = loadPublicKey();
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        return signature;
    }

    private PublicKey loadPublicKey() throws IOException, GeneralSecurityException {
        try (InputStream keyStream = getResources().openRawResource(R.raw.pubkey);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[4096];
            int read;
            while ((read = keyStream.read(data)) != -1) {
                buffer.write(data, 0, read);
            }
            String pem = buffer.toString(StandardCharsets.US_ASCII.name());
            String cleanPem = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.decode(cleanPem, Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(spec);
        }
    }

    private byte[] downloadSignatureFile(String baseUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            String urlString = baseUrl.endsWith(".gz")
                    ? baseUrl.substring(0, baseUrl.length() - 3)
                    : baseUrl;
            urlString = urlString + ".sig";
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.connect();
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            downloadConnection = connection;
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while (shouldContinue && (read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                if (!shouldContinue) {
                    return null;
                }
                return out.toByteArray();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            downloadConnection = null;
        }
    }

    private boolean promptUserForUnverifiedInstall(String warningMessage,
            PowerManager.WakeLock wakeLock) {
        String message = warningMessage != null ? warningMessage
                : getString(R.string.signature_warning_message);
        SignatureDecisionWaiter waiter = new SignatureDecisionWaiter();
        synchronized (decisionLock) {
            pendingSignatureDecision = waiter;
        }
        showSignatureWarningNotification(message);
        broadcastState(STATE_SIGNATURE_WARNING, 100, false, null, message, warningMessage);
        // Waiting for a person is not active update work. Do not keep the phone
        // awake indefinitely if the signature warning is left unanswered.
        wakeLock.release();
        try {
            synchronized (decisionLock) {
                while (!waiter.decided && shouldContinue && !cancelRequested) {
                    try {
                        decisionLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        } finally {
            synchronized (decisionLock) {
                pendingSignatureDecision = null;
            }
            // Also protect the short cancellation/error cleanup after a refusal.
            wakeLock.acquire();
        }
        boolean proceed = waiter.decided && waiter.proceed && shouldContinue && !cancelRequested;
        return proceed;
    }

    private void showSignatureWarningNotification(String message) {
        Intent continueIntent = new Intent(this, UpdateDownloadService.class);
        continueIntent.setAction(ACTION_CONTINUE_WITHOUT_SIGNATURE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent continuePending = PendingIntent.getService(this, 1, continueIntent, flags);

        Intent cancelIntent = new Intent(this, UpdateDownloadService.class);
        cancelIntent.setAction(ACTION_CANCEL_DOWNLOAD);
        PendingIntent cancelPending = PendingIntent.getService(this, 2, cancelIntent, flags);

        NotificationCompat.Builder builder = buildNotification()
                .setContentText(message)
                .setSubText(getString(R.string.signature_warning_subtext))
                .setProgress(0, 0, false)
                .setOngoing(true)
                .addAction(0, getString(R.string.continue_unverified), continuePending)
                .addAction(0, getString(android.R.string.cancel), cancelPending);
        notificationBuilder = builder;
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void requestCancel() {
        cancelRequested = true;
        shouldContinue = false;
        resolveSignatureDecision(false);
        if (downloadConnection != null) {
            downloadConnection.disconnect();
            downloadConnection = null;
        }
    }

    private void finishSuccessfully() {
        complete(STATE_COMPLETED, 100, null,
                getString(R.string.update_installed_reboot_message), null, false);
    }

    private void finishWithError(String message) {
        Log.e(TAG, message);
        complete(STATE_ERROR, 0, null, message, message, false);
    }

    private void finishAsCanceled() {
        complete(STATE_CANCELED, 0, null, "Download canceled", null, true);
    }

    private void resolveSignatureDecision(boolean proceed) {
        synchronized (decisionLock) {
            if (pendingSignatureDecision != null) {
                pendingSignatureDecision.decided = true;
                pendingSignatureDecision.proceed = proceed;
                decisionLock.notifyAll();
            }
        }
    }

    private void complete(int state, int percent, String speedText,
            String message, String error, boolean removeNotification) {
        // Finish on the service's main thread so a retry cannot race stopSelf/onDestroy.
        new android.os.Handler(getMainLooper()).post(() -> {
            shouldContinue = false;
            cancelRequested = false;
            downloadThread = null;
            if (!removeNotification) {
                // Use a fresh builder to discard signature-warning actions.
                notificationBuilder = buildNotification()
                        .setOngoing(false)
                        .setContentText(message)
                        .setAutoCancel(state != STATE_COMPLETED);
                if (state == STATE_COMPLETED) {
                    notificationBuilder.addAction(0, getString(R.string.reboot_now),
                            createLaunchIntent());
                }
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
            }
            stopForeground(removeNotification);
            stopSelf();
            synchronized (UpdaterActivity.STATE_LOCK) {
                broadcastState(state, percent, false, speedText, message, error);
                sIsRunning.set(false);
            }
        });
    }

    private PendingIntent createLaunchIntent() {
        Intent intent = new Intent(this, UpdaterActivity.class);
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private NotificationCompat.Builder buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update_notification)
                .setContentTitle(getString(R.string.app_name))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(createLaunchIntent());
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
