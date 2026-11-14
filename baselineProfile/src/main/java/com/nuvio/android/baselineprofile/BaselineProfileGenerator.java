package com.nuvio.android.baselineprofile;

import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

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
                false,
                rule -> true,
                scope -> {
                    scope.startActivityAndWait();
                    return Unit.INSTANCE;
                }
        );
    }
}
