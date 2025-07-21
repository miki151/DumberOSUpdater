package eu.dumbdroid.updater;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;
import android.app.Activity;
import android.util.Log;
import java.util.zip.GZIPInputStream;
import com.google.common.io.CountingInputStream;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;

// Spinner & adapter
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;

// View callbacks
import org.json.JSONObject;

import android.app.AlertDialog;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import android.widget.ProgressBar;            
import java.io.BufferedOutputStream;          
import java.io.FileOutputStream;              
import java.util.Locale;                      
import androidx.appcompat.app.AppCompatActivity;

import java.lang.reflect.Method;
import android.view.MenuItem;
import android.view.Menu;
import android.content.res.Configuration;
import java.net.HttpURLConnection;
import java.net.URL;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import java.util.concurrent.TimeUnit;
import android.content.Intent;
import android.content.Context;

public class UpdaterActivity extends AppCompatActivity {
    public static final String KEY_UPDATE_VERSION = "pref_update_version";
    public static final String KEY_DOWNLOADED_BUILD = "pref_downloaded_build";
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

    private volatile boolean isDownloading = false;
    private Thread downloadThread;
    private HttpURLConnection downloadConnection;


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
        return "https://dumbdroid.eu/ota/" + branch + "/" + gapps + vndk + ".json";
    }

    /**
     * Downloads the update image and writes it to /dev/phh-ota.
     * Must be called off the UI thread.
     */
    private void downloadUpdate(String branch, String gapps) {
        UpdateInfo info = downloadUpdateManifest(branch, gapps);
        try {
            runOtaMakeCommand();
            if (!isDownloading)
                return;
/*            runOnUiThread(() ->
                Toast.makeText(this,
                    "Starting download from:\n" + info.url,
                    Toast.LENGTH_SHORT).show()
                );*/
            URL url = new URL(info.url);
            downloadConnection = (HttpURLConnection) url.openConnection();
            downloadConnection.setConnectTimeout(10000);
            downloadConnection.setReadTimeout(10000);
            downloadConnection.connect();
    
            int fileLength = downloadConnection.getContentLength();
            CountingInputStream in = new CountingInputStream(new BufferedInputStream(downloadConnection.getInputStream()));
        GZIPInputStream gzipIn = new GZIPInputStream(in);
            BufferedOutputStream out = new BufferedOutputStream(
                new FileOutputStream("/dev/phh-ota", false)
            );
    
            byte[] buffer = new byte[4096];
            long totalBytes = 0;
            long startTime  = System.currentTimeMillis();
    
            int bytesRead;
            while (isDownloading && (bytesRead = gzipIn.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytes = in.getCount();
    
                // update progress and speed (as before)...
                int percent = (int)(totalBytes * 100 / fileLength);
                long elapsed = System.currentTimeMillis() - startTime;
                double speed = totalBytes / (elapsed / 1000.0);
                final String speedText = String.format(Locale.US, "%.1f KB/s", speed/1024);
    
                runOnUiThread(() -> {
                    progressBar.setProgress(percent);
                    tvPercent.setText(percent + "%");
                    tvSpeed.setText(speedText);
                });
            }
    
            out.flush();
            in.close();
            out.close();
            SystemProperties.set("ctl.start", "phh-ota-switch");

            if (isDownloading) {
                // Completed normally
                runOnUiThread(() -> {
                    Toast.makeText(this, "Download complete!", Toast.LENGTH_LONG).show();
                    btnCheck.setText(R.string.check_updates);
                    prefs.edit()
                        .remove(KEY_UPDATE_VERSION)
                        .putLong(KEY_DOWNLOADED_BUILD, info.buildTimeStamp)
                        .apply();
                    recreate();
                });
            }

        } catch (Exception e) {
            // If canceled, exception is normal; only show errors when not user-canceled
            if (isDownloading) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        } finally {
            cancelDownload();
        }
    }
    
    void cancelDownload() {
        isDownloading = false;
        if (downloadConnection != null)
            downloadConnection.disconnect();
        downloadConnection = null;
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            tvPercent.setVisibility(View.GONE);
            tvSpeed.setVisibility(View.GONE);
            invalidateOptionsMenu();
            btnCheck.setText(R.string.install_update);
            Toast.makeText(this, "Download canceled", Toast.LENGTH_SHORT).show();
        });
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

    static public long checkForUpdates(SharedPreferences prefs, String branch, String gapps) {
	Log.i(TAG, "Checking for updates for branch " + branch + " " + gapps);
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

    private void runOtaMakeCommand() {
        SystemProperties.set("ctl.start", "phh-ota-make");
        try {
            Thread.sleep(1000);
        } catch (Exception e) {}
        while (isDownloading && !SystemProperties.get("init.svc.phh-ota-make", "").equals("stopped")) {
            String percent = SystemProperties.get("phh.ota.progress", "");
            if (!percent.isEmpty())
                runOnUiThread(() -> {
                    tvPercent.setText(percent + "%");
                    progressBar.setProgress(Integer.parseInt(percent));
                });
            String state = SystemProperties.get("init.svc.phh-ota-make", "not-defined");
            Log.d(TAG, "Current value of phh-ota-make svc is " + state);
            try {
                Thread.sleep(100);
            } catch (Exception e) {}
        }
        String error = SystemProperties.get("phh.ota.error", "");
        if (!error.isEmpty()) {
            runOnUiThread(() -> showErrorDialog("Error creating image: " + error));
            cancelDownload();
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
        new AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(
                    android.R.string.ok,
                    (dialog, which) -> {
                        dialog.dismiss();
                        onConfirm.run();
                    }
                    )
            .setNegativeButton(
                    android.R.string.cancel,
                    (dialog, which) -> dialog.dismiss()
                    )
            .setCancelable(true)
            .show();
    }

    private void setBranch(String name) {
        prefs.edit().putString(KEY_PREF_BRANCH, name).apply();
        Toast.makeText(this, "Branch set to: " + name, Toast.LENGTH_SHORT).show();
        updateActivityTitle(name);
        new Thread(() -> {
            if (checkForUpdates(prefs, name, getGapps()) == 0)
                runOnUiThread(() -> Toast.makeText(this, "No new updates", Toast.LENGTH_SHORT).show());
            runOnUiThread(this::recreate);
        }).start();
    }

    private void setGapps(String value) {
        prefs.edit().putString(KEY_PREF_GAPPS, value).apply();
        Toast.makeText(this, "Switched to: " + value, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            if (checkForUpdates(prefs, prefs.getString(KEY_PREF_BRANCH, "stable"), value) == 0)
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
            .setPeriodic(10000);

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
        if (updateVersion.isEmpty()) {
	    Log.i(TAG, "Update version empty " +SystemProperties.get("ro.system.build.date.utc", "0") + " " + prefs.getLong(KEY_DOWNLOADED_BUILD, 0));
            if (Long.parseLong(SystemProperties.get("ro.system.build.date.utc", "0")) < prefs.getLong(KEY_DOWNLOADED_BUILD, 0)) {
                tvAvailable.setVisibility(View.VISIBLE);
                tvAvailable.setText("Update installed. Reboot the phone to apply.");
                Log.i(TAG, "Update installed");
            } else {
                // No update: only show check button
                tvAvailable.setVisibility(View.GONE);
                Log.i(TAG, "No update available");
            }
        } else {
            tvAvailable.setVisibility(View.VISIBLE);
            tvAvailable.setText(getString(R.string.update_available) + "\n" + updateVersion);
            btnCheck.setText(R.string.install_update);
            Log.i(TAG, "Update available");
            // Only show patchnotes if non-empty
        }

        String branch = prefs.getString(KEY_PREF_BRANCH, "stable");
        btnCheck.setOnClickListener(v -> {
            if (updateVersion.isEmpty()) {
                Toast.makeText(this,
                    "Checking for updates.\n", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    if (checkForUpdates(prefs, branch, getGapps()) == 0)
                        runOnUiThread(() -> Toast.makeText(this, "No new updates", Toast.LENGTH_SHORT).show());
                    runOnUiThread(this::recreate);
                }).start();
            } else if (!isDownloading) {
                isDownloading = true;
                invalidateOptionsMenu();
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
                tvPercent.setVisibility(View.VISIBLE);
                tvPercent.setText("0%");
                tvSpeed.setVisibility(View.VISIBLE);
                btnCheck.setText(R.string.cancel_update);
                tvSpeed.setText("Preparing for update.");
                // Perform download off the UI thread
                downloadThread = new Thread(() -> downloadUpdate(branch, getGapps()));
                downloadThread.start();
            } else {
                cancelDownload();
            }
        });
    }
}

