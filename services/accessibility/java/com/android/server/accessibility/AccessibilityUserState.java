/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.accessibility;

import static android.accessibilityservice.AccessibilityService.SHOW_MODE_AUTO;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HIDDEN;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD;
import static android.accessibilityservice.AccessibilityService.SHOW_MODE_MASK;

import android.accessibilityservice.AccessibilityService.SoftKeyboardShowMode;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.provider.Settings;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManagerClient;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class that hold states and settings per user and share between
 * {@link AccessibilityManagerService} and {@link AccessibilityServiceConnection}.
 */
class AccessibilityUserState {
    private static final String LOG_TAG = AccessibilityUserState.class.getSimpleName();

    final int mUserId;

    // Non-transient state.

    final RemoteCallbackList<IAccessibilityManagerClient> mUserClients = new RemoteCallbackList<>();

    // Transient state.

    final ArrayList<AccessibilityServiceConnection> mBoundServices = new ArrayList<>();

    final Map<ComponentName, AccessibilityServiceConnection> mComponentNameToServiceMap =
            new HashMap<>();

    final List<AccessibilityServiceInfo> mInstalledServices = new ArrayList<>();

    final List<AccessibilityShortcutInfo> mInstalledShortcuts = new ArrayList<>();

    final Set<ComponentName> mBindingServices = new HashSet<>();

    final Set<ComponentName> mEnabledServices = new HashSet<>();

    final Set<ComponentName> mTouchExplorationGrantedServices = new HashSet<>();

    private final ServiceInfoChangeListener mServiceInfoChangeListener;

    private ComponentName mServiceAssignedToAccessibilityButton;

    private ComponentName mServiceChangingSoftKeyboardMode;

    private ComponentName mServiceToEnableWithShortcut;

    private boolean mBindInstantServiceAllowed;
    private boolean mIsAutoclickEnabled;
    private boolean mIsDisplayMagnificationEnabled;
    private boolean mIsFilterKeyEventsEnabled;
    private boolean mIsNavBarMagnificationAssignedToAccessibilityButton;
    private boolean mIsNavBarMagnificationEnabled;
    private boolean mIsPerformGesturesEnabled;
    private boolean mIsTextHighContrastEnabled;
    private boolean mIsTouchExplorationEnabled;
    private int mUserInteractiveUiTimeout;
    private int mUserNonInteractiveUiTimeout;
    private int mNonInteractiveUiTimeout = 0;
    private int mInteractiveUiTimeout = 0;
    private int mLastSentClientState = -1;

    private Context mContext;

    @SoftKeyboardShowMode
    private int mSoftKeyboardShowMode = SHOW_MODE_AUTO;

    interface ServiceInfoChangeListener {
        void onServiceInfoChangedLocked(AccessibilityUserState userState);
    }

    AccessibilityUserState(int userId, @NonNull Context context,
            @NonNull ServiceInfoChangeListener serviceInfoChangeListener) {
        mUserId = userId;
        mContext = context;
        mServiceInfoChangeListener = serviceInfoChangeListener;
    }

    boolean isHandlingAccessibilityEventsLocked() {
        return !mBoundServices.isEmpty() || !mBindingServices.isEmpty();
    }

    void onSwitchToAnotherUserLocked() {
        // Unbind all services.
        unbindAllServicesLocked();

        // Clear service management state.
        mBoundServices.clear();
        mBindingServices.clear();

        // Clear event management state.
        mLastSentClientState = -1;

        // clear UI timeout
        mNonInteractiveUiTimeout = 0;
        mInteractiveUiTimeout = 0;

        // Clear state persisted in settings.
        mEnabledServices.clear();
        mTouchExplorationGrantedServices.clear();
        mIsTouchExplorationEnabled = false;
        mIsDisplayMagnificationEnabled = false;
        mIsNavBarMagnificationEnabled = false;
        mServiceAssignedToAccessibilityButton = null;
        mIsNavBarMagnificationAssignedToAccessibilityButton = false;
        mIsAutoclickEnabled = false;
        mUserNonInteractiveUiTimeout = 0;
        mUserInteractiveUiTimeout = 0;
    }

    void addServiceLocked(AccessibilityServiceConnection serviceConnection) {
        if (!mBoundServices.contains(serviceConnection)) {
            serviceConnection.onAdded();
            mBoundServices.add(serviceConnection);
            mComponentNameToServiceMap.put(serviceConnection.getComponentName(), serviceConnection);
            mServiceInfoChangeListener.onServiceInfoChangedLocked(this);
        }
    }

    /**
     * Removes a service.
     * There are three states to a service here: off, bound, and binding.
     * This stops tracking the service as bound.
     *
     * @param serviceConnection The service.
     */
    void removeServiceLocked(AccessibilityServiceConnection serviceConnection) {
        mBoundServices.remove(serviceConnection);
        serviceConnection.onRemoved();
        if ((mServiceChangingSoftKeyboardMode != null)
                && (mServiceChangingSoftKeyboardMode.equals(
                serviceConnection.getServiceInfo().getComponentName()))) {
            setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null);
        }
        // It may be possible to bind a service twice, which confuses the map. Rebuild the map
        // to make sure we can still reach a service
        mComponentNameToServiceMap.clear();
        for (int i = 0; i < mBoundServices.size(); i++) {
            AccessibilityServiceConnection boundClient = mBoundServices.get(i);
            mComponentNameToServiceMap.put(boundClient.getComponentName(), boundClient);
        }
        mServiceInfoChangeListener.onServiceInfoChangedLocked(this);
    }

    /**
     * Make sure a services disconnected but still 'on' state is reflected in AccessibilityUserState
     * There are three states to a service here: off, bound, and binding.
     * This drops a service from a bound state, to the binding state.
     * The binding state describes the situation where a service is on, but not bound.
     *
     * @param serviceConnection The service.
     */
    void serviceDisconnectedLocked(AccessibilityServiceConnection serviceConnection) {
        removeServiceLocked(serviceConnection);
        mBindingServices.add(serviceConnection.getComponentName());
    }

    /**
     * Set the soft keyboard mode. This mode is a bit odd, as it spans multiple settings.
     * The ACCESSIBILITY_SOFT_KEYBOARD_MODE setting can be checked by the rest of the system
     * to see if it should suppress showing the IME. The SHOW_IME_WITH_HARD_KEYBOARD setting
     * setting can be changed by the user, and prevents the system from suppressing the soft
     * keyboard when the hard keyboard is connected. The hard keyboard setting needs to defer
     * to the user's preference, if they have supplied one.
     *
     * @param newMode The new mode
     * @param requester The service requesting the change, so we can undo it when the
     *                  service stops. Set to null if something other than a service is forcing
     *                  the change.
     *
     * @return Whether or not the soft keyboard mode equals the new mode after the call
     */
    boolean setSoftKeyboardModeLocked(@SoftKeyboardShowMode int newMode,
            @Nullable ComponentName requester) {
        if ((newMode != SHOW_MODE_AUTO)
                && (newMode != SHOW_MODE_HIDDEN)
                && (newMode != SHOW_MODE_IGNORE_HARD_KEYBOARD)) {
            Slog.w(LOG_TAG, "Invalid soft keyboard mode");
            return false;
        }
        if (mSoftKeyboardShowMode == newMode) {
            return true;
        }

        if (newMode == SHOW_MODE_IGNORE_HARD_KEYBOARD) {
            if (hasUserOverriddenHardKeyboardSetting()) {
                // The user has specified a default for this setting
                return false;
            }
            // Save the original value. But don't do this if the value in settings is already
            // the new mode. That happens when we start up after a reboot, and we don't want
            // to overwrite the value we had from when we first started controlling the setting.
            if (getSoftKeyboardValueFromSettings() != SHOW_MODE_IGNORE_HARD_KEYBOARD) {
                setOriginalHardKeyboardValue(getSecureInt(
                        Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0) != 0);
            }
            putSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 1, mUserId);
        } else if (mSoftKeyboardShowMode == SHOW_MODE_IGNORE_HARD_KEYBOARD) {
            putSecureIntForUser(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                    getOriginalHardKeyboardValue() ? 1 : 0, mUserId);
        }

        saveSoftKeyboardValueToSettings(newMode);
        mSoftKeyboardShowMode = newMode;
        mServiceChangingSoftKeyboardMode = requester;
        for (int i = mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = mBoundServices.get(i);
            service.notifySoftKeyboardShowModeChangedLocked(mSoftKeyboardShowMode);
        }
        return true;
    }

    @SoftKeyboardShowMode
    int getSoftKeyboardShowModeLocked() {
        return mSoftKeyboardShowMode;
    }

    /**
     * If the settings are inconsistent with the internal state, make the internal state
     * match the settings.
     */
    void reconcileSoftKeyboardModeWithSettingsLocked() {
        final boolean showWithHardKeyboardSettings =
                getSecureInt(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0) != 0;
        if (mSoftKeyboardShowMode == SHOW_MODE_IGNORE_HARD_KEYBOARD) {
            if (!showWithHardKeyboardSettings) {
                // The user has overridden the setting. Respect that and prevent further changes
                // to this behavior.
                setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null);
                setUserOverridesHardKeyboardSetting();
            }
        }

        // If the setting and the internal state are out of sync, set both to default
        if (getSoftKeyboardValueFromSettings() != mSoftKeyboardShowMode) {
            Slog.e(LOG_TAG, "Show IME setting inconsistent with internal state. Overwriting");
            setSoftKeyboardModeLocked(SHOW_MODE_AUTO, null);
            putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                    SHOW_MODE_AUTO, mUserId);
        }
    }

    boolean getBindInstantServiceAllowedLocked() {
        return mBindInstantServiceAllowed;
    }

    /* Need to have a permission check on callee */
    void setBindInstantServiceAllowedLocked(boolean allowed) {
        mBindInstantServiceAllowed = allowed;
    }

    Set<ComponentName> getBindingServicesLocked() {
        return mBindingServices;
    }

    /**
     * Returns enabled service list.
     */
    Set<ComponentName> getEnabledServicesLocked() {
        return mEnabledServices;
    }

    List<AccessibilityServiceConnection> getBoundServicesLocked() {
        return mBoundServices;
    }

    int getClientStateLocked(boolean isUiAutomationRunning) {
        int clientState = 0;
        final boolean a11yEnabled = isUiAutomationRunning
                || isHandlingAccessibilityEventsLocked();
        if (a11yEnabled) {
            clientState |= AccessibilityManager.STATE_FLAG_ACCESSIBILITY_ENABLED;
        }
        // Touch exploration relies on enabled accessibility.
        if (a11yEnabled && mIsTouchExplorationEnabled) {
            clientState |= AccessibilityManager.STATE_FLAG_TOUCH_EXPLORATION_ENABLED;
        }
        if (mIsTextHighContrastEnabled) {
            clientState |= AccessibilityManager.STATE_FLAG_HIGH_TEXT_CONTRAST_ENABLED;
        }
        return clientState;
    }

    private void setUserOverridesHardKeyboardSetting() {
        final int softKeyboardSetting = getSecureInt(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO);
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                softKeyboardSetting | SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN,
                mUserId);
    }

    private boolean hasUserOverriddenHardKeyboardSetting() {
        final int softKeyboardSetting = getSecureInt(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO);
        return (softKeyboardSetting & SHOW_MODE_HARD_KEYBOARD_OVERRIDDEN)
                != 0;
    }

    private void setOriginalHardKeyboardValue(boolean originalHardKeyboardValue) {
        final int oldSoftKeyboardSetting = getSecureInt(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO);
        final int newSoftKeyboardSetting = oldSoftKeyboardSetting
                & (~SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE)
                | ((originalHardKeyboardValue) ? SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE : 0);
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                newSoftKeyboardSetting, mUserId);
    }

    private void saveSoftKeyboardValueToSettings(int softKeyboardShowMode) {
        final int oldSoftKeyboardSetting = getSecureInt(
                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO);
        final int newSoftKeyboardSetting = oldSoftKeyboardSetting & (~SHOW_MODE_MASK)
                | softKeyboardShowMode;
        putSecureIntForUser(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                newSoftKeyboardSetting, mUserId);
    }

    private int getSoftKeyboardValueFromSettings() {
        return getSecureInt(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO)
                & SHOW_MODE_MASK;
    }

    private boolean getOriginalHardKeyboardValue() {
        return (getSecureInt(Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, SHOW_MODE_AUTO)
                & SHOW_MODE_HARD_KEYBOARD_ORIGINAL_VALUE) != 0;
    }

    private void unbindAllServicesLocked() {
        final List<AccessibilityServiceConnection> services = mBoundServices;
        for (int count = services.size(); count > 0; count--) {
            // When the service is unbound, it disappears from the list, so there's no need to
            // keep track of the index
            services.get(0).unbindLocked();
        }
    }

    private int getSecureInt(String key, int def) {
        return Settings.Secure.getInt(mContext.getContentResolver(), key, def);
    }

    private void putSecureIntForUser(String key, int value, int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(), key, value, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.append("User state[");
        pw.println();
        pw.append("     attributes:{id=").append(String.valueOf(mUserId));
        pw.append(", touchExplorationEnabled=").append(String.valueOf(mIsTouchExplorationEnabled));
        pw.append(", displayMagnificationEnabled=").append(String.valueOf(
                mIsDisplayMagnificationEnabled));
        pw.append(", navBarMagnificationEnabled=").append(String.valueOf(
                mIsNavBarMagnificationEnabled));
        pw.append(", autoclickEnabled=").append(String.valueOf(mIsAutoclickEnabled));
        pw.append(", nonInteractiveUiTimeout=").append(String.valueOf(mNonInteractiveUiTimeout));
        pw.append(", interactiveUiTimeout=").append(String.valueOf(mInteractiveUiTimeout));
        pw.append(", installedServiceCount=").append(String.valueOf(mInstalledServices.size()));
        pw.append("}");
        pw.println();
        pw.append("     Bound services:{");
        final int serviceCount = mBoundServices.size();
        for (int j = 0; j < serviceCount; j++) {
            if (j > 0) {
                pw.append(", ");
                pw.println();
                pw.append("                     ");
            }
            AccessibilityServiceConnection service = mBoundServices.get(j);
            service.dump(fd, pw, args);
        }
        pw.println("}");
        pw.append("     Enabled services:{");
        Iterator<ComponentName> it = mEnabledServices.iterator();
        if (it.hasNext()) {
            ComponentName componentName = it.next();
            pw.append(componentName.toShortString());
            while (it.hasNext()) {
                componentName = it.next();
                pw.append(", ");
                pw.append(componentName.toShortString());
            }
        }
        pw.println("}");
        pw.append("     Binding services:{");
        it = mBindingServices.iterator();
        if (it.hasNext()) {
            ComponentName componentName = it.next();
            pw.append(componentName.toShortString());
            while (it.hasNext()) {
                componentName = it.next();
                pw.append(", ");
                pw.append(componentName.toShortString());
            }
        }
        pw.println("}]");
    }

    public boolean isAutoclickEnabledLocked() {
        return mIsAutoclickEnabled;
    }

    public void setAutoclickEnabledLocked(boolean enabled) {
        mIsAutoclickEnabled = enabled;
    }

    public boolean isDisplayMagnificationEnabledLocked() {
        return mIsDisplayMagnificationEnabled;
    }

    public void setDisplayMagnificationEnabledLocked(boolean enabled) {
        mIsDisplayMagnificationEnabled = enabled;
    }

    public boolean isFilterKeyEventsEnabledLocked() {
        return mIsFilterKeyEventsEnabled;
    }

    public void setFilterKeyEventsEnabledLocked(boolean enabled) {
        mIsFilterKeyEventsEnabled = enabled;
    }

    public int getInteractiveUiTimeoutLocked() {
        return mInteractiveUiTimeout;
    }

    public void setInteractiveUiTimeoutLocked(int timeout) {
        mInteractiveUiTimeout = timeout;
    }

    public int getLastSentClientStateLocked() {
        return mLastSentClientState;
    }

    public void setLastSentClientStateLocked(int state) {
        mLastSentClientState = state;
    }

    public boolean isNavBarMagnificationAssignedToAccessibilityButtonLocked() {
        return mIsNavBarMagnificationAssignedToAccessibilityButton;
    }

    public void setNavBarMagnificationAssignedToAccessibilityButtonLocked(boolean assigned) {
        mIsNavBarMagnificationAssignedToAccessibilityButton = assigned;
    }

    public boolean isNavBarMagnificationEnabledLocked() {
        return mIsNavBarMagnificationEnabled;
    }

    public void setNavBarMagnificationEnabledLocked(boolean enabled) {
        mIsNavBarMagnificationEnabled = enabled;
    }

    public int getNonInteractiveUiTimeoutLocked() {
        return mNonInteractiveUiTimeout;
    }

    public void setNonInteractiveUiTimeoutLocked(int timeout) {
        mNonInteractiveUiTimeout = timeout;
    }

    public boolean isPerformGesturesEnabledLocked() {
        return mIsPerformGesturesEnabled;
    }

    public void setPerformGesturesEnabledLocked(boolean enabled) {
        mIsPerformGesturesEnabled = enabled;
    }

    public ComponentName getServiceAssignedToAccessibilityButtonLocked() {
        return mServiceAssignedToAccessibilityButton;
    }

    public void setServiceAssignedToAccessibilityButtonLocked(ComponentName componentName) {
        mServiceAssignedToAccessibilityButton = componentName;
    }

    public ComponentName getServiceChangingSoftKeyboardModeLocked() {
        return mServiceChangingSoftKeyboardMode;
    }

    public void setServiceChangingSoftKeyboardModeLocked(
            ComponentName serviceChangingSoftKeyboardMode) {
        mServiceChangingSoftKeyboardMode = serviceChangingSoftKeyboardMode;
    }

    public ComponentName getServiceToEnableWithShortcutLocked() {
        return mServiceToEnableWithShortcut;
    }

    public void setServiceToEnableWithShortcutLocked(ComponentName componentName) {
        mServiceToEnableWithShortcut = componentName;
    }

    public boolean isTextHighContrastEnabledLocked() {
        return mIsTextHighContrastEnabled;
    }

    public void setTextHighContrastEnabledLocked(boolean enabled) {
        mIsTextHighContrastEnabled = enabled;
    }

    public boolean isTouchExplorationEnabledLocked() {
        return mIsTouchExplorationEnabled;
    }

    public void setTouchExplorationEnabledLocked(boolean enabled) {
        mIsTouchExplorationEnabled = enabled;
    }

    public int getUserInteractiveUiTimeoutLocked() {
        return mUserInteractiveUiTimeout;
    }

    public void setUserInteractiveUiTimeoutLocked(int timeout) {
        mUserInteractiveUiTimeout = timeout;
    }

    public int getUserNonInteractiveUiTimeoutLocked() {
        return mUserNonInteractiveUiTimeout;
    }

    public void setUserNonInteractiveUiTimeoutLocked(int timeout) {
        mUserNonInteractiveUiTimeout = timeout;
    }
}
