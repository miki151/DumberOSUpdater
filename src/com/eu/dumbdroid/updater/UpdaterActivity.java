package eu.dumbdroid.updater;

import android.os.Build;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;
import android.util.Log;
import android.provider.Settings;
import androidx.appcompat.widget.Toolbar;

// View callbacks
import org.json.JSONObject;

import android.app.AlertDialog;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;

import android.view.MenuItem;
import android.view.Menu;
import java.net.HttpURLConnection;
import java.net.URL;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Context;

public class UpdaterActivity extends AppCompatActivity {
    public static final String KEY_UPDATE_VERSION = "pref_update_version";
    public static final String KEY_DOWNLOADED_BUILD = "pref_downloaded_build";
    public static final String KEY_LAST_BOOT_COUNT = "pref_last_boot_count";
    public static final String KEY_UPDATE_PATCHNOTES = "pref_update_patchnotes";
    public static final String KEY_PREF_BRANCH = "pref_update_branch";
    public static final String KEY_PREF_ADAPTIVE_PACING = "pref_adaptive_pacing";
    public static final String KEY_PREF_GAPPS = "pref_gapps_variant";    
    static final Object STATE_LOCK = new Object();
    static long checkGeneration;
    static final long CHECK_FAILED = -1;
    static final long CHECK_SKIPPED = -2;
    private enum ScreenState { CHECK, CHECKING, AVAILABLE, DOWNLOADING, REBOOT }
    private ScreenState screenState = ScreenState.CHECK;
    private boolean isChecking;
    private AlertDialog signatureDialog;
    private Button btnCheck;
    private TextView tvAvailable;
    private TextView tvPatchnotes;
    private ScrollView patchnotesScroll;
    private SharedPreferences prefs;
    private ProgressBar progressBar;
    private TextView tvPercent, tvSpeed;

    private boolean receiverRegistered = false;
    private UpdateDownloadService.ProgressInfo lastHandledTerminal;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Broadcasts wake the screen; the service snapshot is the source of truth.
            refreshDownloadUiFromService();
        }
    };


    private static String readStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    static private String constructManifestUrl(String branch, String gapps, long current) {
        String vndk   = SystemProperties.get("ro.vndk.version", "unknown");
        return "https://ota.dumbermini.com/" + branch + "/" + gapps + vndk + ".json?current=" + current;
    }

    private void showDownloadViews() {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
        tvPercent.setVisibility(View.VISIBLE);
        tvSpeed.setVisibility(View.VISIBLE);
    }

    private void resetDownloadViews() {
        progressBar.setVisibility(View.GONE);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        tvPercent.setVisibility(View.GONE);
        tvPercent.setText("");
        tvSpeed.setVisibility(View.GONE);
        tvSpeed.setText("");
    }

    static boolean isRebootPending(SharedPreferences prefs) {
        // A variant switch can have the same (or an older) build timestamp.
        return prefs.contains(KEY_DOWNLOADED_BUILD);
    }

    private void renderIdleState() {
        resetDownloadViews();
        String version = prefs.getString(KEY_UPDATE_VERSION, "");
        String notes = prefs.getString(KEY_UPDATE_PATCHNOTES, "");
        tvPatchnotes.setText(notes);
        patchnotesScroll.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        if (isRebootPending(prefs)) {
            screenState = ScreenState.REBOOT;
            btnCheck.setText(R.string.reboot_now);
            tvAvailable.setText(R.string.update_installed_reboot_message);
        } else if (isChecking) {
            screenState = ScreenState.CHECKING;
            btnCheck.setText(R.string.checking_updates);
            tvAvailable.setText(R.string.checking_updates);
        } else if (!version.isEmpty()) {
            screenState = ScreenState.AVAILABLE;
            btnCheck.setText(R.string.install_update);
            tvAvailable.setText(getString(R.string.update_available) + "\n" + version);
        } else {
            screenState = ScreenState.CHECK;
            btnCheck.setText(R.string.check_updates);
        }
        tvAvailable.setVisibility(screenState == ScreenState.CHECK ? View.GONE : View.VISIBLE);
        btnCheck.setEnabled(screenState != ScreenState.CHECKING);
        invalidateOptionsMenu();
    }

    private void startUpdateCheck() {
        if (isChecking || UpdateDownloadService.isRunning() || isRebootPending(prefs)) return;
        isChecking = true;
        renderIdleState();
        String branch = prefs.getString(KEY_PREF_BRANCH, "stable");
        String gapps = getGapps();
        new Thread(() -> {
            long result = checkForUpdates(this, prefs, branch, gapps);
            runOnUiThread(() -> {
                isChecking = false;
                if (isDestroyed() || isFinishing()) return;
                refreshDownloadUiFromService();
                if (result == 0 || result == CHECK_FAILED) {
                    Toast.makeText(this, result == 0 ? "No new updates" : "Update check failed",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "UpdateCheck").start();
    }

    private void rebootDevice() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            Toast.makeText(this, "Unable to reboot device", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            powerManager.reboot(null);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to reboot device", e);
            Toast.makeText(this, "Unable to reboot device", Toast.LENGTH_LONG).show();
        }
    }

    private void updateProgressUi(int state, int percent, boolean indeterminate, String speedText,
            String message) {
        showDownloadViews();
        progressBar.setIndeterminate(indeterminate);
        if (!indeterminate) {
            progressBar.setProgress(percent);
            tvPercent.setText(percent + "%");
        } else if (message != null) {
            tvPercent.setText(message);
        } else {
            tvPercent.setText("");
        }
        String displayText = speedText != null ? speedText : message;
        if (displayText != null && !displayText.isEmpty()) {
            tvSpeed.setText(displayText);
            tvSpeed.setVisibility(View.VISIBLE);
        } else {
            tvSpeed.setVisibility(View.GONE);
        }
        boolean finalizing = state == UpdateDownloadService.STATE_FINALIZING;
        btnCheck.setText(finalizing ? R.string.update_finalizing_text : R.string.cancel_update);
        screenState = ScreenState.DOWNLOADING;
        btnCheck.setEnabled(!finalizing);
        tvAvailable.setVisibility(View.VISIBLE);
        tvAvailable.setText(message != null ? message : getString(R.string.update_downloading_text));
        invalidateOptionsMenu();
    }

    private void handleDownloadState(int state, int percent, boolean indeterminate,
            String speedText, String message, String error) {
        if (state != UpdateDownloadService.STATE_SIGNATURE_WARNING && signatureDialog != null) {
            signatureDialog.dismiss();
            signatureDialog = null;
        }
        switch (state) {
            case UpdateDownloadService.STATE_PREPARING:
            case UpdateDownloadService.STATE_DOWNLOADING:
            case UpdateDownloadService.STATE_FINALIZING:
                updateProgressUi(state, percent, indeterminate, speedText, message);
                break;
            case UpdateDownloadService.STATE_SIGNATURE_WARNING:
                updateProgressUi(state, percent == 0 ? 100 : percent, false, speedText,
                        message != null ? message : getString(R.string.signature_warning_message));
                showSignatureWarningDialog(message);
                break;
            case UpdateDownloadService.STATE_COMPLETED:
                renderIdleState();
                break;
            case UpdateDownloadService.STATE_ERROR:
                renderIdleState();
                String errorMessage = error != null ? error
                        : (message != null ? message : "Download failed");
                if (errorMessage.startsWith("Error creating image")) {
                    showErrorDialog(errorMessage);
                } else {
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                }
                break;
            case UpdateDownloadService.STATE_CANCELED:
                renderIdleState();
                Toast.makeText(this, "Download canceled", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private void refreshDownloadUiFromService() {
        UpdateDownloadService.ProgressInfo info = UpdateDownloadService.getLastProgress();
        if (UpdateDownloadService.isRunning()) {
            if (info != null) {
                handleDownloadState(info.state, info.percent, info.indeterminate, info.speedText,
                        info.message, info.error);
            } else {
                updateProgressUi(UpdateDownloadService.STATE_PREPARING, 0, true, null,
                        getString(R.string.update_preparing_text));
            }
        } else if (info != null && info != lastHandledTerminal) {
            lastHandledTerminal = info;
            handleDownloadState(info.state, info.percent, info.indeterminate, info.speedText,
                    info.message, info.error);
        } else {
            renderIdleState();
        }
    }

    public static final String TAG = "DumbdroidUpdater";

    static public class UpdateInfo {
        long buildTimeStamp;
        String version;
        String patchnotes;
        String size;
        String url;
    }

    static public UpdateInfo downloadUpdateManifest(String branch, String gapps, long currentBuild) {
        String manifestUrl = constructManifestUrl(branch, gapps, currentBuild);
        try {
            // 1. Download JSON
            Log.w(TAG, "Download update manifest from " + manifestUrl);
            URL url = new URL(manifestUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            InputStream in = new BufferedInputStream(conn.getInputStream());
            String jsonText = readStream(in);
            conn.disconnect();

            Log.i(TAG, "Update manifest: " + jsonText);
            // 2. Parse manifest
            JSONObject manifest = new JSONObject(jsonText);
            UpdateInfo ret = new UpdateInfo();
            ret.buildTimeStamp = manifest.getLong("build_timestamp");
            ret.version =  manifest.getString("version");
            ret.patchnotes = manifest.getString("patchnotes");
            ret.size = manifest.getString("size");
            ret.url = manifest.getString("url");
            return ret;
        } catch (Exception e) {
            Log.e(TAG, "Couldn't download manifest", e);
            return null;
        }
    }

    static int currentBootCount(Context ctx) {
        if (Build.VERSION.SDK_INT >= 24) {
            try { return Settings.Global.getInt(
                    ctx.getContentResolver(), Settings.Global.BOOT_COUNT); }
            catch (Settings.SettingNotFoundException e) {
                Log.e(TAG, "Couldn't get boot count", e);
            }
        }
        Log.i(TAG, "Couldn't get boot count due to old SDK version.");
        return -1; // fallback for pre-24 or read failure
    }

    static public void clearDownloadedBuildOnceAfterBoot(Context context, SharedPreferences prefs) {
        synchronized (STATE_LOCK) {
            int bootCount = currentBootCount(context);
            int lastBootCount = prefs.getInt(KEY_LAST_BOOT_COUNT, -1);
            if (bootCount >= 0 && lastBootCount != bootCount) {
                prefs.edit().putInt(KEY_LAST_BOOT_COUNT, bootCount)
                        .remove(KEY_DOWNLOADED_BUILD).apply();
            }
        }
    }

    static public long checkForUpdates(Context context, SharedPreferences prefs, String branch,
            String gapps) {
        final long generation;
        synchronized (STATE_LOCK) {
            clearDownloadedBuildOnceAfterBoot(context, prefs);
            if (UpdateDownloadService.isRunning() || isRebootPending(prefs)) return CHECK_SKIPPED;
            generation = ++checkGeneration;
        }
        long localTs;
        try {
            localTs = Long.parseLong(SystemProperties.get("ro.system.build.date.utc", "0"));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid build timestamp", e);
            return CHECK_FAILED;
        }
        UpdateInfo info = downloadUpdateManifest(branch, gapps, localTs);
        if (info == null) return CHECK_FAILED; // A failed check must not erase a known update.
        synchronized (STATE_LOCK) {
            // Ignore results for a selection that changed while the request was in flight,
            // or once installation has started. The worker uses the same lock to notify.
            if (generation != checkGeneration
                    || UpdateDownloadService.isRunning() || isRebootPending(prefs)
                    || !branch.equals(prefs.getString(KEY_PREF_BRANCH, "stable"))
                    || !gapps.equals(getGapps(prefs))) return CHECK_SKIPPED;
            boolean available = info.buildTimeStamp > localTs
                    || !gapps.equals(SystemProperties.get("ro.dumbdroid.branch", "vanilla"));
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(KEY_UPDATE_PATCHNOTES, info.patchnotes);
            if (available) {
                editor.putString(KEY_UPDATE_VERSION, info.version + " (" + info.size + ")");
            } else {
                editor.remove(KEY_UPDATE_VERSION);
                UpdateCheckWorker.clearNotification(context);
            }
            editor.apply();
            return available ? info.buildTimeStamp : 0;
        }
    }

    private void showErrorDialog(String error) {
        new AlertDialog.Builder(this)
            .setMessage(error)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
            .setCancelable(true)
            .show();
    }

    private void showSignatureWarningDialog(String message) {
        if (signatureDialog != null && signatureDialog.isShowing()) return;
        final String dialogMessage = message != null ? message
                : getString(R.string.signature_warning_message);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setMessage(dialogMessage)
            .setPositiveButton(
                    R.string.continue_unverified,
                    (d, which) -> {
                        d.dismiss();
                        UpdateDownloadService.continueWithoutSignature(this);
                    })
            .setNegativeButton(
                    android.R.string.cancel,
                    (d, which) -> {
                        d.dismiss();
                        UpdateDownloadService.cancelDownload(this);
                    })
            .setCancelable(false)
            .show();

        signatureDialog = dialog;
        TextView messageText = dialog.findViewById(android.R.id.message);
        if (messageText != null) {
            int color = messageText.getCurrentTextColor();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
        }
    }

    private void updateActivityTitle(String branch) {
        String baseTitle = getString(R.string.updater_tile_title);
        if (!branch.equals("stable"))
            baseTitle += " (" + branch + ")";
        getSupportActionBar().setTitle(baseTitle);
    }

    private String getGapps() {
        return getGapps(prefs);
    }

    public static String getGapps(SharedPreferences prefs) {
        return prefs.getString(KEY_PREF_GAPPS, SystemProperties.get("ro.dumbdroid.branch", "vanilla"));
    }

    public void showConfirmationDialog(String message, Runnable onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(
                    android.R.string.ok,
                    (d, which) -> {
                        d.dismiss();
                        onConfirm.run();
                    }
                    )
            .setNegativeButton(
                    android.R.string.cancel,
                    (d, which) -> d.dismiss()
                    )
            .setCancelable(true)
            .show();

        TextView messageText = dialog.findViewById(android.R.id.message);
        if (messageText != null) {
            int color = messageText.getCurrentTextColor();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color);
        }
    }

    private void changeSelection(String key, String value) {
        synchronized (STATE_LOCK) {
            if (isChecking || UpdateDownloadService.isRunning() || isRebootPending(prefs)) return;
            ++checkGeneration;
            prefs.edit().putString(key, value).remove(KEY_UPDATE_VERSION)
                    .remove(KEY_UPDATE_PATCHNOTES).apply();
            UpdateCheckWorker.clearNotification(this);
        }
        updateActivityTitle(prefs.getString(KEY_PREF_BRANCH, "stable"));
        startUpdateCheck();
    }

    private void setBranch(String name) {
        changeSelection(KEY_PREF_BRANCH, name);
    }

    private void setGapps(String value) {
        changeSelection(KEY_PREF_GAPPS, value);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_adaptive_pacing:
                synchronized (STATE_LOCK) {
                    if (UpdateDownloadService.isRunning()) return true;
                    boolean enabled = !prefs.getBoolean(KEY_PREF_ADAPTIVE_PACING, true);
                    prefs.edit().putBoolean(KEY_PREF_ADAPTIVE_PACING, enabled).apply();
                    item.setChecked(enabled);
                }
                return true;
            case R.id.menu_branch_stable:
                setBranch("stable");
                return true;
            case R.id.menu_branch_testing:
                showConfirmationDialog("The testing branch contains more frequently updated builds that can have more bugs. Continue?",
                        () -> setBranch("testing"));
                return true;
            case R.id.menu_branch_unstable:
                showConfirmationDialog("The unstable branch contains development builds that can have bugs or missing features. Continue?",
                        () -> setBranch("unstable"));
                return true;
            case R.id.menu_gapps_yes:
                showConfirmationDialog("Are you sure you want to switch to the variant of DumberOS that includes Google apps?",
                        () -> setGapps("gapps") );
                return true;
            case R.id.menu_gapps_no:
                showConfirmationDialog("Are you sure you want to switch to the variant of DumberOS that's free of Google apps?",
                        () -> setGapps("vanilla") );
                return true;
            case android.R.id.home:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }


    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_adaptive_pacing)
                .setChecked(prefs.getBoolean(KEY_PREF_ADAPTIVE_PACING, true))
                .setEnabled(!UpdateDownloadService.isRunning());
        boolean enabled = screenState == ScreenState.CHECK || screenState == ScreenState.AVAILABLE;
        menu.findItem(R.id.menu_branch).setEnabled(enabled);
        menu.findItem(R.id.menu_gapps).setEnabled(enabled);
        return super.onPrepareOptionsMenu(menu);
    }

    public static void schedulePeriodicWorker(Context context) {
        ComponentName serviceComponent = new ComponentName(context, UpdateCheckWorker.class);
        JobInfo.Builder builder = new JobInfo.Builder(1, serviceComponent)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)                  // survive reboots
            .setPeriodic(3 * 24 * 60 * 60 * 1000L);

        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
        int result = jobScheduler.schedule(builder.build());
        if (result != JobScheduler.RESULT_SUCCESS) {
            Log.e(TAG, "Job scheduling failed: " + result);
        } else
            Log.i(TAG, "Job scheduling succeded");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshDownloadUiFromService();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updater);
        schedulePeriodicWorker(this);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);
        btnCheck     = findViewById(R.id.btn_check_updates);
        tvAvailable  = findViewById(R.id.tv_update_available);
        tvPatchnotes = findViewById(R.id.tv_patchnotes);
        patchnotesScroll = findViewById(R.id.scroll_patchnotes);
        progressBar = findViewById(R.id.progressBar);
        tvPercent   = findViewById(R.id.tv_percent);
        tvSpeed     = findViewById(R.id.tv_speed);

        clearDownloadedBuildOnceAfterBoot(this, prefs);
        updateActivityTitle(prefs.getString(KEY_PREF_BRANCH, "stable"));
        refreshDownloadUiFromService();

        btnCheck.setOnClickListener(v -> {
            // Dispatch the action that was rendered, never an unseen preference change.
            switch (screenState) {
                case CHECK:
                    startUpdateCheck();
                    break;
                case AVAILABLE:
                    UpdateDownloadService.startDownload(this,
                            prefs.getString(KEY_PREF_BRANCH, "stable"), getGapps());
                    refreshDownloadUiFromService();
                    break;
                case DOWNLOADING:
                    UpdateDownloadService.cancelDownload(this);
                    break;
                case REBOOT:
                    rebootDevice();
                    break;
                case CHECKING:
                    break;
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(UpdateDownloadService.ACTION_PROGRESS);
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        refreshDownloadUiFromService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (signatureDialog != null) {
            signatureDialog.dismiss();
            signatureDialog = null;
        }
        if (receiverRegistered) {
            unregisterReceiver(downloadReceiver);
            receiverRegistered = false;
        }
    }
}
