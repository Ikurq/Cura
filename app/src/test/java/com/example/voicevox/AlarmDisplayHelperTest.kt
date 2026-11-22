package com.example.voicevox

import android.os.Build
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmDisplayHelperTest {

    @Test
    fun decideLaunchPlan_lockedScreenWithAllPermissions_prefersFullScreenIntentWithoutOverlay() {
        val ctx = AlarmLaunchContext(
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            isInteractive = false,
            isKeyguardLocked = true,
            isAppInForeground = false,
            canUseFullScreenIntent = true,
            canDrawOverlays = true,
            hasPostNotifications = true
        )

        val plan = AlarmDisplayHelper.decideLaunchPlan(ctx)

        assertTrue(plan.attachFullScreenIntent)
        assertTrue(plan.tryDirectActivityLaunch)
        assertFalse(plan.useOverlayFallback)
        assertFalse(plan.requiresPermissionAttention)
    }

    @Test
    fun decideLaunchPlan_screenOnButKeyguardLocked_prefersFullScreenIntentWithoutOverlay() {
        val ctx = AlarmLaunchContext(
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            isInteractive = true,
            isKeyguardLocked = true,
            isAppInForeground = false,
            canUseFullScreenIntent = true,
            canDrawOverlays = true,
            hasPostNotifications = true
        )

        val plan = AlarmDisplayHelper.decideLaunchPlan(ctx)

        assertTrue(plan.attachFullScreenIntent)
        assertTrue(plan.tryDirectActivityLaunch)
        assertFalse(plan.useOverlayFallback)
        assertFalse(plan.requiresPermissionAttention)
    }

    @Test
    fun decideLaunchPlan_screenOnUnlockedOtherAppWithOverlay_enablesOverlayFallback() {
        val ctx = AlarmLaunchContext(
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            isInteractive = true,
            isKeyguardLocked = false,
            isAppInForeground = false,
            canUseFullScreenIntent = true,
            canDrawOverlays = true,
            hasPostNotifications = true
        )

        val plan = AlarmDisplayHelper.decideLaunchPlan(ctx)

        assertTrue(plan.attachFullScreenIntent)
        assertTrue(plan.tryDirectActivityLaunch)
        assertTrue(plan.useOverlayFallback)
        assertTrue(plan.overlayDelayMillis > 0L)
        assertFalse(plan.requiresPermissionAttention)
    }

    @Test
    fun decideLaunchPlan_appInForeground_launchesDirectlyWithoutOverlay() {
        val ctx = AlarmLaunchContext(
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            isInteractive = true,
            isKeyguardLocked = false,
            isAppInForeground = true,
            canUseFullScreenIntent = false,
            canDrawOverlays = true,
            hasPostNotifications = true
        )

        val plan = AlarmDisplayHelper.decideLaunchPlan(ctx)

        assertTrue(plan.attachFullScreenIntent)
        assertTrue(plan.tryDirectActivityLaunch)
        assertFalse(plan.useOverlayFallback)
        assertTrue(plan.requiresPermissionAttention)
    }

    @Test
    fun decideLaunchPlan_legacyApi28_allowsDirectLaunchWithoutOverlayWhenNoPermission() {
        val ctx = AlarmLaunchContext(
            sdkInt = Build.VERSION_CODES.P,
            isInteractive = false,
            isKeyguardLocked = true,
            isAppInForeground = false,
            canUseFullScreenIntent = true,
            canDrawOverlays = false,
            hasPostNotifications = true
        )

        val plan = AlarmDisplayHelper.decideLaunchPlan(ctx)

        assertTrue(plan.tryDirectActivityLaunch)
        assertFalse(plan.useOverlayFallback)
        assertFalse(plan.requiresPermissionAttention)
    }

    @Test
    fun decideLaunchPlan_missingFsiPermissionWithOverlayOnLockScreen_enablesOverlayFallbackAndAttention() {
        val ctx = AlarmLaunchContext(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            isInteractive = false,
            isKeyguardLocked = true,
            isAppInForeground = false,
            canUseFullScreenIntent = false,
            canDrawOverlays = true,
            hasPostNotifications = true
        )

        val plan = AlarmDisplayHelper.decideLaunchPlan(ctx)

        assertTrue(plan.attachFullScreenIntent)
        assertTrue(plan.useOverlayFallback)
        assertTrue(plan.requiresPermissionAttention)
    }

    @Test
    fun decideLaunchPlan_api33BoundaryForFsiPermissionAttention() {
        val api33Ctx = AlarmLaunchContext(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            isInteractive = false,
            isKeyguardLocked = true,
            isAppInForeground = false,
            canUseFullScreenIntent = false,
            canDrawOverlays = true,
            hasPostNotifications = true
        )
        assertFalse(AlarmDisplayHelper.decideLaunchPlan(api33Ctx).requiresPermissionAttention)

        val api34Ctx = api33Ctx.copy(sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertTrue(AlarmDisplayHelper.decideLaunchPlan(api34Ctx).requiresPermissionAttention)
    }

    @Test
    fun decideLaunchPlan_missingNotificationPermissionUnlocked_triesDirectAndOverlayFallback() {
        val ctx = AlarmLaunchContext(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            isInteractive = true,
            isKeyguardLocked = false,
            isAppInForeground = false,
            canUseFullScreenIntent = true,
            canDrawOverlays = true,
            hasPostNotifications = false
        )

        val plan = AlarmDisplayHelper.decideLaunchPlan(ctx)

        assertTrue(plan.tryDirectActivityLaunch)
        assertTrue(plan.useOverlayFallback)
        assertTrue(plan.requiresPermissionAttention)
    }

    @Test
    fun decideLaunchPlan_noOverlayPermission_neverUsesOverlayFallback() {
        val ctx = AlarmLaunchContext(
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            isInteractive = true,
            isKeyguardLocked = false,
            isAppInForeground = false,
            canUseFullScreenIntent = false,
            canDrawOverlays = false,
            hasPostNotifications = false
        )

        val plan = AlarmDisplayHelper.decideLaunchPlan(ctx)

        assertFalse(plan.useOverlayFallback)
        assertTrue(plan.requiresPermissionAttention)
    }

    @Test
    fun resolveFullScreenSettingsAction_returnsDedicatedActionOnApi34AndAbove() {
        assertEquals(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            AlarmDisplayHelper.resolveFullScreenSettingsAction(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
        assertEquals(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            AlarmDisplayHelper.resolveFullScreenSettingsAction(Build.VERSION_CODES.VANILLA_ICE_CREAM)
        )
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            AlarmDisplayHelper.resolveFullScreenSettingsAction(Build.VERSION_CODES.TIRAMISU)
        )
    }

    @Test
    fun resolveExactAlarmSettingsAction_returnsRequestActionOnApi31AndAbove() {
        assertEquals(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            AlarmDisplayHelper.resolveExactAlarmSettingsAction(Build.VERSION_CODES.S)
        )
        assertNull(
            AlarmDisplayHelper.resolveExactAlarmSettingsAction(Build.VERSION_CODES.R)
        )
    }

    @Test
    fun evaluatePermissionReadiness_identifiesMissingPermissionsAccurately() {
        val allGranted = AlarmPermissionStatus(
            hasPostNotifications = true,
            canUseFullScreenIntent = true,
            canDrawOverlays = true,
            canScheduleExactAlarms = true,
            isIgnoringBatteryOptimizations = true
        )
        assertTrue(allGranted.isLockScreenOccupancyReady)
        assertTrue(allGranted.isUnlockedOccupancyReady)
        assertFalse(allGranted.hasAnyMissingCriticalPermission)
        assertTrue(AlarmDisplayHelper.getMissingPermissions(allGranted).isEmpty())

        val missingCritical = AlarmPermissionStatus(
            hasPostNotifications = false,
            canUseFullScreenIntent = false,
            canDrawOverlays = false,
            canScheduleExactAlarms = false,
            isIgnoringBatteryOptimizations = false
        )
        assertFalse(missingCritical.isLockScreenOccupancyReady)
        assertFalse(missingCritical.isUnlockedOccupancyReady)
        assertTrue(missingCritical.hasAnyMissingCriticalPermission)

        val missingList = AlarmDisplayHelper.getMissingPermissions(missingCritical)
        assertEquals(5, missingList.size)
        assertTrue(missingList.contains(MissingAlarmPermission.POST_NOTIFICATIONS))
        assertTrue(missingList.contains(MissingAlarmPermission.FULL_SCREEN_INTENT))
        assertTrue(missingList.contains(MissingAlarmPermission.EXACT_ALARM))
        assertTrue(missingList.contains(MissingAlarmPermission.OVERLAY))
        assertTrue(missingList.contains(MissingAlarmPermission.BATTERY_OPTIMIZATION))
    }
}
