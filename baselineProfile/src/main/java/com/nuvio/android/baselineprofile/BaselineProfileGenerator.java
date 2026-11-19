package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.uiautomator.Direction;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import kotlin.Unit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Collects the launcher startup path and a separate authenticated Home browse journey.
 *
 * The browse pass is excluded from the Startup Profile; it contributes only
 * Baseline Profile rules for the Home feed and catalogue shelf scroll paths.
 * Run this manually on a supported physical API 33+ device with a signed-in
 * benchmark account and at least one populated, horizontally scrollable catalogue.
 * The generated rules are committed only after review; these tests never run as
 * part of a normal release build.
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

    @Test
    public void homeBrowseJourneys() {
        baselineProfileRule.collect(
                StartupReadiness.TARGET_APP_PACKAGE,
                15,
                3,
                "home_browse", false, true,
                rule -> true,
                scope -> {
                    scope.pressHome();
                    scope.startActivityAndWait();
                    UiDevice device = UiDevice.getInstance(
                            InstrumentationRegistry.getInstrumentation());
                    StartupReadiness.awaitHomeAndFullyDrawn(device);
                    StartupReadiness.scrollRequired(
                            device,
                            "nuvio_benchmark_home_scroll",
                            Direction.DOWN,
                            "Home feed");
                    StartupReadiness.scrollUntilMarker(
                            device,
                            "nuvio_benchmark_home_scroll",
                            "nuvio_benchmark_catalog_row",
                            Direction.DOWN,
                            "Home feed");
                    StartupReadiness.scrollRequired(
                            device,
                            "nuvio_benchmark_catalog_row",
                            Direction.RIGHT,
                            "catalogue shelf");
                    StartupReadiness.scrollRequired(
                            device,
                            "nuvio_benchmark_catalog_row",
                            Direction.LEFT,
                            "catalogue shelf");
                    StartupReadiness.scrollRequired(
                            device,
                            "nuvio_benchmark_home_scroll",
                            Direction.UP,
                            "Home feed");
                    return Unit.INSTANCE;
                }
        );
    }
}
