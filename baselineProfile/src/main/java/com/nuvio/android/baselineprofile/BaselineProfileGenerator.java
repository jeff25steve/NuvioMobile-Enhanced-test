package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

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
 *
 * Phone-only (LADB) tip: start the instrument command, then immediately leave
 * the terminal so {@code com.nuvio.media.benchmark} stays in front. The readiness
 * helper also re-foregrounds Nuvio if a known shell package steals focus mid-wait.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class BaselineProfileGenerator {
    @Rule
    public final BaselineProfileRule baselineProfileRule = new BaselineProfileRule();

    @Test
    public void startup() {
        baselineProfileRule.collect(
                StartupReadiness.TARGET_APP_PACKAGE,
                15,
                3,
                "startup",
                true,
                true,
                rule -> true,
                scope -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    UiDevice device = UiDevice.getInstance(
                            InstrumentationRegistry.getInstrumentation());
                    StartupReadiness.awaitHomeAndFullyDrawn(device);
                    return Unit.INSTANCE;
                }
        );
    }
}
