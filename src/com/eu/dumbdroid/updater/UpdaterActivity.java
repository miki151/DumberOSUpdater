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

// Spinner & adapter
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

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
    public static final String KEY_PREF_GAPPS = "pref_gapps_variant";    
    private Spinner spinnerBranch;
    private Button btnCheck;
    private TextView tvAvailable;
    private TextView tvPatchnotes;
    private ScrollView patchnotesScroll;
    private SharedPreferences prefs;
    private ProgressBar progressBar;
    private TextView tvPercent, tvSpeed;

    private boolean isDownloading = false;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(UpdateDownloadService.EXTRA_STATE,
                    UpdateDownloadService.STATE_DOWNLOADING);
            int percent = intent.getIntExtra(UpdateDownloadService.EXTRA_PERCENT, 0);
            boolean indeterminate = intent.getBooleanExtra(UpdateDownloadService.EXTRA_INDETERMINATE, false);
            String speed = intent.getStringExtra(UpdateDownloadService.EXTRA_SPEED);
            String message = intent.getStringExtra(UpdateDownloadService.EXTRA_MESSAGE);
            String error = intent.getStringExtra(UpdateDownloadService.EXTRA_ERROR);
            handleDownloadState(state, percent, indeterminate, speed, message, error);
            if (isTerminalDownloadState(state)) {
                UpdateDownloadService.clearLastProgress();
            }
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

    static private String constructManifestUrl(String branch, String gapps) {
        String vndk   = SystemProperties.get("ro.vndk.version", "unknown");
        return "https://ota.dumbdroid.eu/" + branch + "/" + gapps + vndk + ".json";
    }

    // String moved to resources: R.string.update_preparing_text

    private void setDownloading(boolean downloading) {
        isDownloading = downloading;
        invalidateOptionsMenu();
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

    private long getCurrentBuildTimestamp() {
        try {
            return Long.parseLong(SystemProperties.get("ro.system.build.date.utc", "0"));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse current build timestamp", e);
            return 0;
        }
    }

    private boolean isRebootPending() {
        return prefs.getLong(KEY_DOWNLOADED_BUILD, 0) > getCurrentBuildTimestamp();
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
        btnCheck.setText(R.string.cancel_update);
        if (!isDownloading) {
            setDownloading(true);
        }
    }

    private void handleDownloadState(int state, int percent, boolean indeterminate,
            String speedText, String message, String error) {
        switch (state) {
            case UpdateDownloadService.STATE_PREPARING:
            case UpdateDownloadService.STATE_DOWNLOADING:
                updateProgressUi(state, percent, indeterminate, speedText, message);
                break;
            case UpdateDownloadService.STATE_SIGNATURE_WARNING:
                updateProgressUi(state, percent == 0 ? 100 : percent, false, speedText,
                        message != null ? message : getString(R.string.signature_warning_message));
                showSignatureWarningDialog(message);
                break;
            case UpdateDownloadService.STATE_COMPLETED:
                setDownloading(false);
                resetDownloadViews();
                btnCheck.setText(R.string.reboot_now);
                Toast.makeText(this, "Download complete!", Toast.LENGTH_LONG).show();
                recreate();
                break;
            case UpdateDownloadService.STATE_ERROR:
                setDownloading(false);
                resetDownloadViews();
                btnCheck.setText(R.string.install_update);
                String errorMessage = error != null ? error
                        : (message != null ? message : "Download failed");
                if (errorMessage.startsWith("Error creating image")) {
                    showErrorDialog(errorMessage);
                } else {
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                }
                break;
            case UpdateDownloadService.STATE_CANCELED:
                setDownloading(false);
                resetDownloadViews();
                btnCheck.setText(R.string.install_update);
                Toast.makeText(this, "Download canceled", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private boolean isTerminalDownloadState(int state) {
        return state == UpdateDownloadService.STATE_COMPLETED
                || state == UpdateDownloadService.STATE_ERROR
                || state == UpdateDownloadService.STATE_CANCELED;
    }

    private void refreshDownloadUiFromService() {
        UpdateDownloadService.ProgressInfo info = UpdateDownloadService.getLastProgress();
        boolean serviceRunning = UpdateDownloadService.isRunning();

        if (serviceRunning) {
            if (info != null) {
                handleDownloadState(info.state, info.percent, info.indeterminate, info.speedText,
                        info.message, info.error);
            } else {
                showDownloadViews();
                progressBar.setIndeterminate(true);
                progressBar.setProgress(0);
                tvPercent.setText("0%");
                tvSpeed.setText(getString(R.string.update_preparing_text));
                btnCheck.setText(R.string.cancel_update);
                if (!isDownloading) {
                    setDownloading(true);
                }
            }
            return;
        }

        if (info != null) {
            handleDownloadState(info.state, info.percent, info.indeterminate, info.speedText,
                    info.message, info.error);
            if (isTerminalDownloadState(info.state)) {
                UpdateDownloadService.clearLastProgress();
            }
            return;
        }

        if (isDownloading) {
            setDownloading(false);
        }
        resetDownloadViews();
        String currentVersion = prefs.getString(KEY_UPDATE_VERSION, "");
        if (isRebootPending()) {
            btnCheck.setText(R.string.reboot_now);
        } else {
            btnCheck.setText(currentVersion.isEmpty() ? R.string.check_updates : R.string.install_update);
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

    static public UpdateInfo downloadUpdateManifest(String branch, String gapps) {
        String manifestUrl = constructManifestUrl(branch, gapps);
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
/*            runOnUiThread(() ->
                    Toast.makeText(this, "Update check failed:\n" + e.getMessage(),
                        Toast.LENGTH_LONG).show()
                    );*/
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
	int currentBootCount = currentBootCount(context);
        int lastBootCount = prefs.getInt(KEY_LAST_BOOT_COUNT, -1);
        Log.i(TAG, "Current boot count = " + currentBootCount + ", last = " + lastBootCount);
        if (lastBootCount < currentBootCount) {
            Log.i(TAG, "Clearing downloaded build key.");
            prefs.edit()
                .putInt(KEY_LAST_BOOT_COUNT, currentBootCount)
                .remove(KEY_DOWNLOADED_BUILD)
                .apply();
        } else
            Log.i(TAG, "Not clearing downloaded build key.");
    }

    static public long checkForUpdates(Context context, SharedPreferences prefs, String branch,
            String gapps) {
	Log.i(TAG, "Checking for updates for branch " + branch + " " + gapps);
        clearDownloadedBuildOnceAfterBoot(context, prefs);
        try {
            UpdateInfo info = downloadUpdateManifest(branch, gapps);
            // 3. Get local build timestamp
            long localTs = Math.max(
                    Long.parseLong(SystemProperties.get("ro.system.build.date.utc", "0")),
                    prefs.getLong(KEY_DOWNLOADED_BUILD, 0)
                    );
	    String currentGapps = SystemProperties.get("ro.dumbdroid.branch", "vanilla");
	    boolean needsGappsUpdate = !prefs.getString(KEY_PREF_GAPPS, currentGapps).equals(currentGapps);
            if (info != null && (info.buildTimeStamp > localTs || needsGappsUpdate)) {
                Log.i(TAG, "New build!");
                // 4. Newer build! Save into prefs
                prefs.edit()
                    .putString(KEY_UPDATE_VERSION, info.version + " (" + info.size + ")")
                    .putString(KEY_UPDATE_PATCHNOTES, info.patchnotes)
                    .apply();
                return info.buildTimeStamp;

                // Refresh UI on main thread
            } else {
                prefs.edit()
                    .remove(KEY_UPDATE_VERSION)
                    .putString(KEY_UPDATE_PATCHNOTES, info != null ? info.patchnotes : "")
                    .apply();
                Log.i(TAG, "No new build.");
                return 0;
            }

        } catch (Exception e) {
	    Log.e(TAG, "Failed to check for updates: ", e);
            return 0;
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

    private void setBranch(String name) {
        prefs.edit().putString(KEY_PREF_BRANCH, name).apply();
        Toast.makeText(this, "Branch set to: " + name, Toast.LENGTH_SHORT).show();
        updateActivityTitle(name);
        new Thread(() -> {
            if (checkForUpdates(this, prefs, name, getGapps()) == 0)
                runOnUiThread(() -> Toast.makeText(this, "No new updates", Toast.LENGTH_SHORT).show());
            runOnUiThread(this::recreate);
        }).start();
    }

    private void setGapps(String value) {
        prefs.edit().putString(KEY_PREF_GAPPS, value).apply();
        Toast.makeText(this, "Switched to: " + value, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            if (checkForUpdates(this, prefs, prefs.getString(KEY_PREF_BRANCH, "stable"), value) == 0)
                runOnUiThread(() -> Toast.makeText(this, "No new updates", Toast.LENGTH_SHORT).show());
            runOnUiThread(this::recreate);
        }).start();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
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
                showConfirmationDialog("Are you sure you want to switch to the variant of Dumbdroid that includes Google apps?",
                        () -> setGapps("gapps") );
                return true;
            case R.id.menu_gapps_no:
                showConfirmationDialog("Are you sure you want to switch to the variant of Dumbdroid that's free of Google apps?",
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
        menu.findItem(R.id.menu_branch).setEnabled(!isDownloading);
        menu.findItem(R.id.menu_gapps).setEnabled(!isDownloading);
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
        recreate();
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

        String updateVersion      = prefs.getString(KEY_UPDATE_VERSION, "");
        String patchnotesText = prefs.getString(KEY_UPDATE_PATCHNOTES, "");
        updateActivityTitle(prefs.getString(KEY_PREF_BRANCH, "stable"));
        if (!patchnotesText.isEmpty()) {
            tvPatchnotes.setText(patchnotesText);
            patchnotesScroll.setVisibility(View.VISIBLE);
        } else {
            patchnotesScroll.setVisibility(View.GONE);
        }
        long currentBuildTimestamp = getCurrentBuildTimestamp();
        long downloadedBuildTimestamp = prefs.getLong(KEY_DOWNLOADED_BUILD, 0);
        if (updateVersion.isEmpty()) {
            Log.i(TAG, "Update version empty "
                    + currentBuildTimestamp
                    + " " + downloadedBuildTimestamp);
            if (downloadedBuildTimestamp > currentBuildTimestamp) {
                tvAvailable.setVisibility(View.VISIBLE);
                tvAvailable.setText(getString(R.string.update_installed_reboot_message));
                btnCheck.setText(R.string.reboot_now);
                Log.i(TAG, "Update installed");
            } else {
                // No update: only show check button
                tvAvailable.setVisibility(View.GONE);
                btnCheck.setText(R.string.check_updates);
                Log.i(TAG, "No update available");
            }
        } else {
            tvAvailable.setVisibility(View.VISIBLE);
            tvAvailable.setText(getString(R.string.update_available) + "\n" + updateVersion);
            btnCheck.setText(R.string.install_update);
            Log.i(TAG, "Update available");
            // Only show patchnotes if non-empty
        }

        refreshDownloadUiFromService();

        btnCheck.setOnClickListener(v -> {
            String currentBranch = prefs.getString(KEY_PREF_BRANCH, "stable");
            String currentUpdateVersion = prefs.getString(KEY_UPDATE_VERSION, "");
            if (isRebootPending()) {
                rebootDevice();
            } else if (currentUpdateVersion.isEmpty()) {
                Toast.makeText(this,
                    "Checking for updates.\n", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    if (checkForUpdates(this, prefs, currentBranch, getGapps()) == 0)
                        runOnUiThread(() -> Toast.makeText(this, "No new updates", Toast.LENGTH_SHORT).show());
                    runOnUiThread(this::recreate);
                }).start();
            } else if (!isDownloading) {
                UpdateDownloadService.startDownload(this, currentBranch, getGapps());
                showDownloadViews();
                progressBar.setIndeterminate(true);
                progressBar.setProgress(0);
                tvPercent.setText("0%");
                tvSpeed.setText(getString(R.string.update_preparing_text));
                btnCheck.setText(R.string.cancel_update);
                if (!isDownloading) {
                    setDownloading(true);
                }
            } else {
                UpdateDownloadService.cancelDownload(this);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(UpdateDownloadService.ACTION_PROGRESS);
            registerReceiver(downloadReceiver, filter);
            receiverRegistered = true;
        }
        refreshDownloadUiFromService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (receiverRegistered) {
            unregisterReceiver(downloadReceiver);
            receiverRegistered = false;
        }
    }
}
