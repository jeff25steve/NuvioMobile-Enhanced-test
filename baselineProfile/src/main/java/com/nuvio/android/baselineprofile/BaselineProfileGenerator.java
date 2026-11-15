package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import kotlin.Unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Collects only the real launcher startup path for the release application.
 *
 * Run this manually on a supported physical API 33+ device. The generated
 * rules are committed only after review; this test never runs as part of a
 * normal release build.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class BaselineProfileGenerator {
    private static final long MAIN_CONTENT_READY_TIMEOUT_MS = 30_000L;

    // The test module is self-instrumenting, so InstrumentationRegistry's target
    // context is the test APK itself, not the app being measured. Both distribution
    // flavors deliberately share this isolated benchmark application ID.
    private static final String TARGET_APP_PACKAGE = "com.nuvio.media.benchmark";

    private static String targetPackageName() {
        return TARGET_APP_PACKAGE;
    }

    private static String readinessDiagnostics(UiDevice device) {
        String foregroundPackage = "<unavailable>";
        try {
            foregroundPackage = device.getCurrentPackageName();
            ByteArrayOutputStream hierarchyOutput = new ByteArrayOutputStream();
            device.dumpWindowHierarchy(hierarchyOutput);
            String hierarchy = new String(
                    hierarchyOutput.toByteArray(),
                    StandardCharsets.UTF_8
            ).replaceAll("(text|content-desc)=\"[^\"]*\"", "$1=\"[redacted]\"");
            final int maxHierarchyChars = 8_000;
            if (hierarchy.length() > maxHierarchyChars) {
                hierarchy = hierarchy.substring(0, maxHierarchyChars) + "\n...[truncated]";
            }
            return "\nForeground package: " + foregroundPackage +
                    "\nExpected package: " + TARGET_APP_PACKAGE +
                    "\nWindow hierarchy (text and content descriptions redacted):\n" + hierarchy;
        } catch (Exception error) {
            return "\nForeground package: " + foregroundPackage +
                    "\nCould not capture the accessibility hierarchy: " +
                    error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static void awaitMainContentReady() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        boolean ready = device.wait(
                Until.hasObject(By.res("nuvio_startup_ready")),
                MAIN_CONTENT_READY_TIMEOUT_MS
        );
        if (!ready) {
            throw new AssertionError(
                    "Nuvio did not expose the Home-ready marker 'nuvio_startup_ready' within 30 seconds." +
                    readinessDiagnostics(device)
            );
        }
    }

    @Rule
    public final BaselineProfileRule baselineProfileRule = new BaselineProfileRule();

    @Test
    public void startup() {
        baselineProfileRule.collect(
                targetPackageName(),
                15,
                3,
                "startup",
                true,
                true,
                rule -> true,
                scope -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    awaitMainContentReady();
                    return Unit.INSTANCE;
                }
        );
    }
}
