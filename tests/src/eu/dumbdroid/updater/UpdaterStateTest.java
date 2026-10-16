package eu.dumbdroid.updater;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import androidx.appcompat.view.menu.MenuBuilder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(UpdaterStateTest.TestRunner.class)
@Config(sdk = 32, shadows = UpdaterStateTest.ManifestShadow.class)
public class UpdaterStateTest {
    public static class TestRunner extends RobolectricTestRunner {
        static {
            // The platform tree ships plain android-all jars, not preinstrumented Maven jars.
            System.setProperty("robolectric.usePreinstrumentedJars", "false");
        }

        public TestRunner(Class<?> testClass) throws org.junit.runners.model.InitializationError {
            super(testClass);
        }

        @Override
        protected java.util.Properties getBuildSystemApiProperties() {
            java.util.Properties properties = super.getBuildSystemApiProperties();
            try (java.io.InputStream input = getClass().getResourceAsStream(
                    "/updater-test-resources.apk")) {
                java.nio.file.Path apk = java.nio.file.Files.createTempFile("updater-test-", ".apk");
                apk.toFile().deleteOnExit();
                java.nio.file.Files.copy(input, apk, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                properties.setProperty("android_resource_apk", apk.toString());
                return properties;
            } catch (java.io.IOException e) {
                throw new AssertionError("Unable to load test APK resources", e);
            }
        }

    }

    private Context context;
    private SharedPreferences prefs;
    private ActivityController<UpdaterActivity> activity;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().putInt(UpdaterActivity.KEY_LAST_BOOT_COUNT, 10).commit();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 10);
        SystemProperties.set("ro.system.build.date.utc", "200");
        SystemProperties.set("ro.dumbdroid.branch", "vanilla");
        running().set(false);
        ReflectionHelpers.setStaticField(UpdateDownloadService.class, "sLastProgress", null);
        ManifestShadow.requested = new CountDownLatch(1);
        ManifestShadow.release = new CountDownLatch(0);
        ManifestShadow.finished = new CountDownLatch(1);
        ManifestShadow.result = null;
        ManifestShadow.onRequest = null;
        ManifestShadow.checkThread = null;
    }

    @After
    public void tearDown() throws Exception {
        ManifestShadow.release.countDown();
        if (ManifestShadow.requested.getCount() == 0) {
            assertTrue(ManifestShadow.finished.await(5, TimeUnit.SECONDS));
            if (ManifestShadow.checkThread != Thread.currentThread()) {
                ManifestShadow.checkThread.join(5000);
                assertFalse(ManifestShadow.checkThread.isAlive());
            }
        }
        if (activity != null) activity.pause().stop().destroy();
        running().set(false);
    }

    private AtomicBoolean running() {
        return ReflectionHelpers.getStaticField(UpdateDownloadService.class, "sIsRunning");
    }

    private Button openActivity() {
        activity = Robolectric.buildActivity(UpdaterActivity.class).setup();
        return activity.get().findViewById(R.id.btn_check_updates);
    }

    private Menu preparationMenu() {
        Menu menu = new MenuBuilder(activity.get());
        activity.get().onCreateOptionsMenu(menu);
        activity.get().onPrepareOptionsMenu(menu);
        return menu;
    }

    @Test
    public void adaptivePacingToggleDefaultsOnAndPersistsAcrossRecreation() {
        openActivity();
        MenuItem item = preparationMenu().findItem(R.id.menu_adaptive_pacing);
        assertTrue(item.isCheckable());
        assertTrue(item.isChecked());
        assertTrue(activity.get().onOptionsItemSelected(item));
        assertFalse(item.isChecked());
        assertFalse(prefs.getBoolean(UpdaterActivity.KEY_PREF_ADAPTIVE_PACING, true));
        activity.pause().stop().destroy();
        activity = null;
        openActivity();
        item = preparationMenu().findItem(R.id.menu_adaptive_pacing);
        assertFalse(item.isChecked());
        assertTrue(activity.get().onOptionsItemSelected(item));
        assertTrue(item.isChecked());
        assertTrue(prefs.getBoolean(UpdaterActivity.KEY_PREF_ADAPTIVE_PACING, false));
    }

    @Test
    public void adaptivePacingToggleCannotChangeAnActiveUpdate() {
        openActivity();
        running().set(true);
        MenuItem item = preparationMenu().findItem(R.id.menu_adaptive_pacing);
        assertFalse(item.isEnabled());
        activity.get().onOptionsItemSelected(item);
        assertTrue(prefs.getBoolean(UpdaterActivity.KEY_PREF_ADAPTIVE_PACING, true));
        running().set(false);
        assertTrue(preparationMenu().findItem(R.id.menu_adaptive_pacing).isEnabled());
    }

    @Test
    public void checkButtonDoesNotDownloadWhenWorkerChangesPreferences() throws Exception {
        Button button = openActivity();
        assertEquals(context.getString(R.string.check_updates), button.getText().toString());
        prefs.edit().putString(UpdaterActivity.KEY_UPDATE_VERSION, "New build").commit();
        ManifestShadow.release = new CountDownLatch(1);
        button.performClick();
        assertTrue(ManifestShadow.requested.await(5, TimeUnit.SECONDS));
        assertFalse(UpdateDownloadService.isRunning());
        assertFalse(button.isEnabled());
        assertEquals(context.getString(R.string.checking_updates), button.getText().toString());
    }

    @Test
    public void sameTimestampVariantSwitchRemainsRebootReadyAfterReopen() {
        prefs.edit().putString(UpdaterActivity.KEY_PREF_GAPPS, "gapps")
                .putLong(UpdaterActivity.KEY_DOWNLOADED_BUILD, 200).commit();
        assertEquals(context.getString(R.string.reboot_now), openActivity().getText().toString());
        activity.pause().stop().destroy();
        activity = null;
        assertEquals(context.getString(R.string.reboot_now), openActivity().getText().toString());
        assertEquals(-2, UpdaterActivity.checkForUpdates(context, prefs, "stable", "gapps"));
        assertEquals(1, ManifestShadow.requested.getCount());
    }

    @Test
    public void olderVariantBuildTakesPriorityOverCachedAvailability() {
        prefs.edit().putLong(UpdaterActivity.KEY_DOWNLOADED_BUILD, 100)
                .putString(UpdaterActivity.KEY_UPDATE_VERSION, "Stale available update").commit();
        assertEquals(context.getString(R.string.reboot_now), openActivity().getText().toString());
    }

    @Test
    public void rebootClearsPendingState() {
        prefs.edit().putLong(UpdaterActivity.KEY_DOWNLOADED_BUILD, 200).commit();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 11);
        assertEquals(context.getString(R.string.check_updates), openActivity().getText().toString());
        assertFalse(UpdaterActivity.isRebootPending(prefs));
    }

    @Test
    public void failedCheckKeepsKnownUpdate() {
        prefs.edit().putString(UpdaterActivity.KEY_UPDATE_VERSION, "Known update").commit();
        assertEquals(-1, UpdaterActivity.checkForUpdates(context, prefs, "stable", "vanilla"));
        assertEquals("Known update", prefs.getString(UpdaterActivity.KEY_UPDATE_VERSION, ""));
    }

    @Test
    public void inFlightCheckCannotOverwriteDownloadState() {
        ManifestShadow.result = manifest();
        ManifestShadow.onRequest = () -> running().set(true);
        assertEquals(-2, UpdaterActivity.checkForUpdates(context, prefs, "stable", "vanilla"));
        assertFalse(prefs.contains(UpdaterActivity.KEY_UPDATE_VERSION));
    }

    @Test
    public void inFlightCheckForOldSelectionIsDiscarded() {
        ManifestShadow.result = manifest();
        ManifestShadow.onRequest = () -> prefs.edit()
                .putString(UpdaterActivity.KEY_PREF_GAPPS, "gapps").commit();
        assertEquals(-2, UpdaterActivity.checkForUpdates(context, prefs, "stable", "vanilla"));
        assertFalse(prefs.contains(UpdaterActivity.KEY_UPDATE_VERSION));
    }

    @Test
    public void downloadClearsAvailabilityNotificationAndReservesStateImmediately() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("test", "test", 3));
        manager.notify(1001, new Notification.Builder(context, "test").build());
        UpdateDownloadService.startDownload(context, "stable", "vanilla");
        assertTrue(UpdateDownloadService.isRunning());
        assertNull(Shadows.shadowOf(manager).getNotification(1001));
        assertEquals(context.getString(R.string.cancel_update), openActivity().getText().toString());
    }

    @Test
    public void sameTimestampVariantSwitchIsAvailable() {
        prefs.edit().putString(UpdaterActivity.KEY_PREF_GAPPS, "gapps").commit();
        ManifestShadow.result = manifest();
        ManifestShadow.result.buildTimeStamp = 200;
        assertEquals(200, UpdaterActivity.checkForUpdates(context, prefs, "stable", "gapps"));
        assertEquals(context.getString(R.string.install_update), openActivity().getText().toString());
    }

    @Test
    public void completionLeavesRebootMessageAndRemovesWarningActions() {
        UpdateDownloadService service = Robolectric.buildService(UpdateDownloadService.class)
                .create().get();
        running().set(true);
        prefs.edit().putLong(UpdaterActivity.KEY_DOWNLOADED_BUILD, 200).commit();
        ReflectionHelpers.callInstanceMethod(service, "showSignatureWarningNotification",
                ReflectionHelpers.ClassParameter.from(String.class, "Missing signature"));
        ReflectionHelpers.callInstanceMethod(service, "finishSuccessfully");
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Notification notification = Shadows.shadowOf(
                context.getSystemService(NotificationManager.class)).getNotification(1002);
        assertNotNull(notification);
        assertEquals(context.getString(R.string.update_installed_reboot_message),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(1, notification.actions.length);
        assertEquals(context.getString(R.string.reboot_now), notification.actions[0].title);
        assertFalse(UpdateDownloadService.isRunning());
        assertEquals(context.getString(R.string.reboot_now), openActivity().getText().toString());
    }

    @Test
    public void preDownloadCheckStaysInvalidAfterDownloadStops() {
        ManifestShadow.result = manifest();
        ManifestShadow.onRequest = () -> {
            UpdateDownloadService.startDownload(context, "stable", "vanilla");
            running().set(false); // The download failed before the manifest request returned.
        };
        assertEquals(UpdaterActivity.CHECK_SKIPPED,
                UpdaterActivity.checkForUpdates(context, prefs, "stable", "vanilla"));
        assertFalse(prefs.contains(UpdaterActivity.KEY_UPDATE_VERSION));
    }

    @Test
    public void finalizingDoesNotOfferCancellation() {
        running().set(true);
        ReflectionHelpers.setStaticField(UpdateDownloadService.class, "sLastProgress",
                new UpdateDownloadService.ProgressInfo(UpdateDownloadService.STATE_FINALIZING,
                        0, true, null, context.getString(R.string.update_finalizing_text), null));
        Button button = openActivity();
        assertFalse(button.isEnabled());
        assertEquals(context.getString(R.string.update_finalizing_text), button.getText().toString());
    }

    @Test
    public void preparationCancellationWaitsForNativeCleanup() throws Exception {
        UpdateDownloadService service = Robolectric.buildService(UpdateDownloadService.class)
                .create().get();
        ReflectionHelpers.setField(service, "shouldContinue", true);
        SystemProperties.set("ctl.start", "");
        SystemProperties.set("init.svc.phh-ota-make", "stopped");
        SystemProperties.set("phh.ota.cancel", "true"); // stale previous cancellation
        SystemProperties.set("phh.ota.adaptive_pacing", "false"); // stale previous attempt
        ManifestShadow.result = manifest();
        FutureTask<Void> task = new FutureTask<>(() -> {
            runDownload(service);
            return null;
        });
        Thread worker = new Thread(task);
        worker.start();
        try {
            awaitProperty("ctl.start", "phh-ota-make");
            assertEquals("false", SystemProperties.get("phh.ota.cancel"));
            assertEquals("true", SystemProperties.get("phh.ota.adaptive_pacing"));
            PowerManager.WakeLock wakeLock = ShadowPowerManager.getLatestWakeLock();
            assertTrue(wakeLock.isHeld());
            Thread.sleep(250);
            assertFalse("A stale stopped state is not completion", task.isDone());
            SystemProperties.set("init.svc.phh-ota-make", "running");
            SystemProperties.set("phh.ota.error", "");
            ReflectionHelpers.callInstanceMethod(service, "requestCancel");
            awaitProperty("phh.ota.cancel", "true");
            assertFalse("Cancellation must wait for native cleanup", task.isDone());
            assertTrue("Native cleanup must stay awake", wakeLock.isHeld());
            SystemProperties.set("phh.ota.error", "Operation canceled");
            SystemProperties.set("init.svc.phh-ota-make", "stopped");
            assertNull(task.get(5, TimeUnit.SECONDS));
            assertFalse(wakeLock.isHeld());
        } finally {
            SystemProperties.set("phh.ota.error", "");
            SystemProperties.set("init.svc.phh-ota-make", "stopped");
            worker.join(5000);
            assertFalse(worker.isAlive());
        }
    }

    private static void runDownload(UpdateDownloadService service) {
        ReflectionHelpers.callInstanceMethod(service, "performDownload",
                ClassParameter.from(String.class, "stable"),
                ClassParameter.from(String.class, "vanilla"));
    }

    @Test
    public void manifestFailureReleasesWakeLock() {
        UpdateDownloadService service = Robolectric.buildService(UpdateDownloadService.class)
                .create().get();
        ReflectionHelpers.setField(service, "shouldContinue", true);
        ManifestShadow.onRequest = () -> assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld());
        runDownload(service);
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld());
    }

    @Test
    public void unexpectedDownloadExceptionReleasesWakeLock() {
        UpdateDownloadService service = Robolectric.buildService(UpdateDownloadService.class)
                .create().get();
        ReflectionHelpers.setField(service, "shouldContinue", true);
        ManifestShadow.onRequest = () -> {
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld());
            throw new IllegalStateException("test failure");
        };
        runDownload(service);
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld());
    }

    @Test
    public void signatureDecisionReleasesWakeLockAndReacquiresForFinalization() throws Exception {
        checkSignatureWaitWakeLock(true);
    }

    @Test
    public void signatureCancellationReacquiresWakeLockForCleanup() throws Exception {
        checkSignatureWaitWakeLock(false);
    }

    private void checkSignatureWaitWakeLock(boolean proceed) throws Exception {
        UpdateDownloadService service = Robolectric.buildService(UpdateDownloadService.class)
                .create().get();
        ReflectionHelpers.setField(service, "shouldContinue", true);
        PowerManager.WakeLock wakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:update");
        wakeLock.acquire();
        FutureTask<Boolean> task = new FutureTask<>(() -> ReflectionHelpers.callInstanceMethod(
                service, "promptUserForUnverifiedInstall",
                ClassParameter.from(String.class, "Test signature warning"),
                ClassParameter.from(PowerManager.WakeLock.class, wakeLock)));
        Thread worker = new Thread(task);
        worker.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (wakeLock.isHeld() && !task.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse("Waiting for user input must allow sleep", wakeLock.isHeld());
            assertFalse(task.isDone());
            if (proceed) {
                ReflectionHelpers.callInstanceMethod(service, "resolveSignatureDecision",
                        ClassParameter.from(boolean.class, true));
            } else {
                ReflectionHelpers.callInstanceMethod(service, "requestCancel");
            }
            assertEquals(proceed, task.get(5, TimeUnit.SECONDS));
            assertTrue("Finalization or cancellation cleanup must stay awake", wakeLock.isHeld());
        } finally {
            ReflectionHelpers.callInstanceMethod(service, "requestCancel");
            worker.join(5000);
            if (wakeLock.isHeld()) wakeLock.release();
            assertFalse(worker.isAlive());
        }
    }

    @Test
    public void preparationReportsNativePressureTimeout() throws Exception {
        UpdateDownloadService service = Robolectric.buildService(UpdateDownloadService.class)
                .create().get();
        ReflectionHelpers.setField(service, "shouldContinue", true);
        SystemProperties.set("ctl.start", "");
        SystemProperties.set("init.svc.phh-ota-make", "");
        FutureTask<String> task = new FutureTask<>(() ->
                ReflectionHelpers.callInstanceMethod(service, "runOtaMakeCommand"));
        Thread worker = new Thread(task);
        worker.start();
        try {
            awaitProperty("ctl.start", "phh-ota-make");
            SystemProperties.set("init.svc.phh-ota-make", "running");
            SystemProperties.set("phh.ota.error", "Connection timed out");
            SystemProperties.set("init.svc.phh-ota-make", "stopped");
            assertEquals("Connection timed out", task.get(5, TimeUnit.SECONDS));
        } finally {
            SystemProperties.set("phh.ota.error", "");
            SystemProperties.set("init.svc.phh-ota-make", "stopped");
            worker.join(5000);
            assertFalse(worker.isAlive());
        }
    }

    @Test
    public void preparationPassesDisabledPacingToNativeBeforeStarting() throws Exception {
        prefs.edit().putBoolean(UpdaterActivity.KEY_PREF_ADAPTIVE_PACING, false).commit();
        UpdateDownloadService service = Robolectric.buildService(UpdateDownloadService.class)
                .create().get();
        ReflectionHelpers.setField(service, "shouldContinue", true);
        SystemProperties.set("ctl.start", "");
        SystemProperties.set("init.svc.phh-ota-make", "stopped");
        SystemProperties.set("phh.ota.adaptive_pacing", "true");
        FutureTask<String> task = new FutureTask<>(() ->
                ReflectionHelpers.callInstanceMethod(service, "runOtaMakeCommand"));
        Thread worker = new Thread(task);
        worker.start();
        try {
            awaitProperty("ctl.start", "phh-ota-make");
            assertEquals("false", SystemProperties.get("phh.ota.adaptive_pacing"));
            SystemProperties.set("phh.ota.error", "");
            assertEquals("", task.get(5, TimeUnit.SECONDS));
        } finally {
            SystemProperties.set("phh.ota.error", "");
            SystemProperties.set("init.svc.phh-ota-make", "stopped");
            worker.join(5000);
            assertFalse(worker.isAlive());
        }
    }

    private static void awaitProperty(String key, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!expected.equals(SystemProperties.get(key)) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, SystemProperties.get(key));
    }

    private static UpdaterActivity.UpdateInfo manifest() {
        UpdaterActivity.UpdateInfo info = new UpdaterActivity.UpdateInfo();
        info.buildTimeStamp = 300;
        info.version = "new";
        info.size = "1 GB";
        info.patchnotes = "Notes";
        return info;
    }

    @Implements(value = UpdaterActivity.class, isInAndroidSdk = false)
    public static class ManifestShadow extends org.robolectric.shadows.ShadowActivity {
        static CountDownLatch requested, release, finished;
        static UpdaterActivity.UpdateInfo result;
        static Runnable onRequest;
        static Thread checkThread;

        @Implementation
        protected static UpdaterActivity.UpdateInfo downloadUpdateManifest(
                String branch, String gapps, long current) {
            checkThread = Thread.currentThread();
            requested.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Check timed out");
                if (onRequest != null) onRequest.run();
                return result;
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            } finally {
                finished.countDown();
            }
        }
    }
}
