package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import kotlin.Unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Collects only the real launcher startup path for the release application.
 *
 * Run this manually on a supported rooted emulator or API 33+ device. The
 * generated rules are committed only after review; this test never runs as
 * part of a normal release build.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class BaselineProfileGenerator {
    private static final String TARGET_PACKAGE_NAME = "com.nuvio.media";
    private static final long MAIN_CONTENT_READY_TIMEOUT_MS = 30_000L;

    private static void awaitMainContentReady() {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        boolean ready = device.wait(
                Until.hasObject(By.res("nuvio_startup_ready")),
                MAIN_CONTENT_READY_TIMEOUT_MS
        );
        if (!ready) {
            throw new AssertionError(
                    "Nuvio did not reach the signed-in Home-ready state within 30 seconds. " +
                    "Sign in and complete onboarding before generating startup profiles."
            );
        }
    }

    @Rule
    public final BaselineProfileRule baselineProfileRule = new BaselineProfileRule();

    @Test
    public void startup() {
        baselineProfileRule.collect(
                TARGET_PACKAGE_NAME,
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
