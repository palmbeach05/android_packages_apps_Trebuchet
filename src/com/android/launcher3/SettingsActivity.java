/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.launcher3;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.NumberPicker;

import com.android.launcher3.protect.ProtectedManagerActivity;

import com.android.launcher3.graphics.IconShapeOverride;
import com.android.launcher3.icons.IconsHandler;
import com.android.launcher3.notification.NotificationListener;
import com.android.launcher3.searchlauncher.SearchLauncherCallbacks;
import com.android.launcher3.util.SettingsObserver;
import com.android.launcher3.views.ButtonPreference;

/**
 * Settings activity for Launcher. Currently implements the following setting: Allow rotation
 */
public class SettingsActivity extends Activity {

    private static final String ICON_BADGING_PREFERENCE_KEY = "pref_icon_badging";
    /** Hidden field Settings.Secure.NOTIFICATION_BADGING */
    public static final String NOTIFICATION_BADGING = "notification_badging";
    /** Hidden field Settings.Secure.ENABLED_NOTIFICATION_LISTENERS */
    private static final String NOTIFICATION_ENABLED_LISTENERS = "enabled_notification_listeners";

    // Hide labels
    private static final String KEY_SHOW_DESKTOP_LABELS = "pref_desktop_show_labels";
    private static final String KEY_SHOW_DRAWER_LABELS = "pref_drawer_show_labels";

    public static final String KEY_MINUS_ONE = "pref_enable_minus_one";
    static final String KEY_PREDICTIVE_APPS = "pref_predictive_apps";
    public static final String KEY_WORKSPACE_EDIT = "pref_workspace_edit";
    public static final String KEY_ADAPTIVE_ICONS = "pref_icon_adaptive";
    public static final String KEY_THEME_BUILTIN_ICONS = "pref_icon_builtin_theme";
    public static final String KEY_THEME_DARK = "pref_ui_darktheme";

    static final String EXTRA_SCHEDULE_RESTART = "extraScheduleRestart";
    private static final String KEY_GRID_SIZE = "pref_grid_size";
    public static final String KEY_ICON_PACK = "pref_icon_pack";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LauncherSettingsFragment fragment = new LauncherSettingsFragment();
        fragment.mShouldRestart = getIntent().getBooleanExtra(EXTRA_SCHEDULE_RESTART, false);

        // Display the fragment as the main content.
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
    }

    /**
     * This fragment shows the launcher preferences.
     */
    public static class LauncherSettingsFragment extends PreferenceFragment
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private SystemDisplayRotationLockObserver mRotationLockObserver;
        private IconBadgingObserver mIconBadgingObserver;

        private SharedPreferences mPrefs;

        private String mDefaultIconPack;
        private boolean mShouldRestart = false;
        private Preference mGridPref;
        private IconsHandler mIconsHandler;
        private PackageManager mPackageManager;
        private Preference mIconPackPref;
        private Preference mDarkThemePref;
        private ListPreference mAdaptiveIconsPref;
        private SwitchPreference mThemeBuiltinIconsPref;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            getPreferenceManager().setSharedPreferencesName(LauncherFiles.SHARED_PREFERENCES_KEY);
            addPreferencesFromResource(R.xml.launcher_preferences);

            ContentResolver resolver = getActivity().getContentResolver();
            mPrefs = Utilities.getPrefs(getActivity().getApplicationContext());
            mPrefs.registerOnSharedPreferenceChangeListener(this);

            PreferenceGroup homeGroup = (PreferenceGroup) findPreference("category_home");
            PreferenceGroup iconGroup = (PreferenceGroup) findPreference("category_icons");

            // Setup allow rotation preference
            Preference rotationPref = findPreference(Utilities.ALLOW_ROTATION_PREFERENCE_KEY);
            if (getResources().getBoolean(R.bool.allow_rotation)) {
                // Launcher supports rotation by default. No need to show this setting.
                homeGroup.removePreference(rotationPref);
            } else {
                mRotationLockObserver = new SystemDisplayRotationLockObserver(rotationPref, resolver);

                // Register a content observer to listen for system setting changes while
                // this UI is active.
                mRotationLockObserver.register(Settings.System.ACCELEROMETER_ROTATION);

                // Initialize the UI once
                rotationPref.setDefaultValue(Utilities.getAllowRotationDefaultValue(getActivity()));
            }

            ButtonPreference iconBadgingPref =
                    (ButtonPreference) findPreference(ICON_BADGING_PREFERENCE_KEY);
            if (!Utilities.ATLEAST_OREO) {
                homeGroup.removePreference(
                        findPreference(SessionCommitReceiver.ADD_ICON_PREFERENCE_KEY));
            }
            if (!getResources().getBoolean(R.bool.notification_badging_enabled)) {
                iconGroup.removePreference(iconBadgingPref);
            }
            else {
                // Listen to system notification badge settings while this UI is active.
                mIconBadgingObserver = new IconBadgingObserver(
                        iconBadgingPref, resolver, getFragmentManager());
                mIconBadgingObserver.register(NOTIFICATION_BADGING, NOTIFICATION_ENABLED_LISTENERS);
            }

            Preference protectedApps = findPreference("pref_protected_apps");
            if (protectedApps != null) {
                protectedApps.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(getActivity(), ProtectedManagerActivity.class);
                    getActivity().startActivity(intent);
                    getActivity().finish();
                    return true;
                });
            }

            mDarkThemePref = findPreference(KEY_THEME_DARK);
            updatDarkThemeEntry();
            mIconPackPref = findPreference(KEY_ICON_PACK);
            mIconPackPref.setOnPreferenceClickListener(preference -> {
                mIconsHandler.showDialog(getActivity());
                return true;
            });
            mPackageManager = getActivity().getPackageManager();
            mDefaultIconPack = mPrefs.getString(KEY_ICON_PACK, getString(R.string.icon_pack_default));
            mIconsHandler = IconCache.getIconsHandler(getActivity().getApplicationContext());
            if (mIconPackPref.getIcon() == null) updateIconPackEntry();

            mGridPref = findPreference(KEY_GRID_SIZE);
            if (mGridPref != null) {
                mGridPref.setOnPreferenceClickListener(preference -> {
                    setCustomGridSize();
                    return true;
                });

                mGridPref.setSummary(mPrefs.getString(KEY_GRID_SIZE, getDefaulGridSize()));
            }

            SwitchPreference minusOne = (SwitchPreference) findPreference(KEY_MINUS_ONE);
            if (!Utilities.hasPackageInstalled(Utilities.ATLEAST_MARSHMALLOW?getContext():getActivity().getApplicationContext(),
                    SearchLauncherCallbacks.SEARCH_PACKAGE)) {
                homeGroup.removePreference(minusOne);
            }

            mAdaptiveIconsPref = (ListPreference)
                    findPreference(KEY_ADAPTIVE_ICONS);
            updatAdaptiveIconsEntry();
            if (mAdaptiveIconsPref != null) {
                mAdaptiveIconsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    // Clear the icon cache.
                    LauncherAppState.getInstance(Utilities.ATLEAST_MARSHMALLOW?getContext():getActivity().getApplicationContext()).getIconCache().clear();
                    return true;
                });
            }

            mThemeBuiltinIconsPref = (SwitchPreference)
                    findPreference(KEY_THEME_BUILTIN_ICONS);
            updatAdaptiveIconsEntry();
            if (mThemeBuiltinIconsPref != null) {
                mThemeBuiltinIconsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    // Clear the icon cache.
                    LauncherAppState.getInstance(Utilities.ATLEAST_MARSHMALLOW?getContext():getActivity().getApplicationContext()).getIconCache().clear();
                    return true;
                });
            }

            Preference iconShapeOverride = findPreference(IconShapeOverride.KEY_PREFERENCE);
            if (iconShapeOverride != null) {
                //if (IconShapeOverride.isSupported(getActivity())) {
                IconShapeOverride.handlePreferenceUi((ListPreference) iconShapeOverride);
                /*} else {
                    iconGroup.removePreference(iconShapeOverride);
                }*/
            }
        }

        private String getDefaulGridSize() {
            InvariantDeviceProfile profile = new InvariantDeviceProfile(getActivity());
            return Utilities.getGridValue(profile.numColumns, profile.numRows);
        }

        private void updateIconPackEntry() {
            ApplicationInfo info = null;
            String iconPack = mPrefs.getString(KEY_ICON_PACK, mDefaultIconPack);
            String summary = getString(R.string.icon_pack_system);
            Drawable icon = getResources().getDrawable(android.R.mipmap.sym_def_app_icon);

            if (!mIconsHandler.isDefaultIconPack()) {
                try {
                    info = mPackageManager.getApplicationInfo(iconPack, PackageManager.GET_META_DATA);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                if (info != null) {
                    summary = mPackageManager.getApplicationLabel(info).toString();
                    icon = mPackageManager.getApplicationIcon(info);
                }
            }

            mIconPackPref.setSummary(summary);
            mIconPackPref.setIcon(icon);
        }

        private void updatDarkThemeEntry() {
            Context context = getActivity().getApplicationContext();
            String darkThemeMode = mPrefs.getString(KEY_THEME_DARK, context.getString(R.string.darktheme_auto));

            if (darkThemeMode.equals(context.getString(R.string.darktheme_off))) mDarkThemePref.setSummary(R.string.darktheme_off_desc);
            else if (darkThemeMode.equals(context.getString(R.string.darktheme_drawer))) mDarkThemePref.setSummary(R.string.darktheme_drawer_desc);
            else if (darkThemeMode.equals(context.getString(R.string.darktheme_full))) mDarkThemePref.setSummary(R.string.darktheme_full_desc);
            else mDarkThemePref.setSummary(R.string.darktheme_auto_desc);
        }

        private void updatAdaptiveIconsEntry() {
            Context context = getActivity().getApplicationContext();
            String adaptiveIconsMode = mPrefs.getString(KEY_ADAPTIVE_ICONS, context.getString(R.string.icon_adaptive_default));

            if (adaptiveIconsMode.equals(context.getString(R.string.icon_adaptive_disabled))) mAdaptiveIconsPref.setSummary(R.string.settings_icon_adaptive_desc_disabled);
            else if (adaptiveIconsMode.equals(context.getString(R.string.icon_adaptive_force))) mAdaptiveIconsPref.setSummary(R.string.settings_icon_force_adaptive_desc_on);
            else if (adaptiveIconsMode.equals(context.getString(R.string.icon_adaptive_enabled_bypass)))
                mAdaptiveIconsPref.setSummary(context.getString(R.string.settings_icon_force_adaptive_desc_off)+'\n'+context.getString(R.string.settings_icon_adaptive_desc_bypass));
            else if (adaptiveIconsMode.equals(context.getString(R.string.icon_adaptive_force_bypass)))
                mAdaptiveIconsPref.setSummary(context.getString(R.string.settings_icon_force_adaptive_desc_on)+'\n'+context.getString(R.string.settings_icon_adaptive_desc_bypass));
            else mAdaptiveIconsPref.setSummary(R.string.settings_icon_force_adaptive_desc_off);
        }

        private void setCustomGridSize() {
            int minValue = 3;
            int maxValue = 9;

            String storedValue = mPrefs.getString(KEY_GRID_SIZE, "4x5");
            Pair<Integer, Integer> currentValues = Utilities.extractCustomGrid(storedValue);

            LayoutInflater inflater = (LayoutInflater)
                    getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            if (inflater == null) {
                return;
            }
            View contentView = inflater.inflate(R.layout.dialog_custom_grid, null);
            NumberPicker columnPicker = (NumberPicker)
                    contentView.findViewById(R.id.dialog_grid_column);
            NumberPicker rowPicker = (NumberPicker)
                    contentView.findViewById(R.id.dialog_grid_row);

            columnPicker.setMinValue(minValue);
            rowPicker.setMinValue(minValue);
            columnPicker.setMaxValue(maxValue);
            rowPicker.setMaxValue(maxValue);
            columnPicker.setValue(currentValues.first);
            rowPicker.setValue(currentValues.second);

            new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.grid_size_text)
                    .setView(contentView)
                    .setPositiveButton(R.string.grid_size_custom_positive, (dialog, i) -> {
                        String newValues = Utilities.getGridValue(columnPicker.getValue(),
                                rowPicker.getValue());
                        mPrefs.edit().putString(KEY_GRID_SIZE, newValues).apply();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        @Override
        public void onPause() {
            super.onPause();
            mIconsHandler.hideDialog();
        }

        @Override
        public void onStop() {
            super.onStop();
            if (mShouldRestart) this.getActivity().finish();
        }

        @Override
        public void onDestroy() {
            if (mRotationLockObserver != null) {
                mRotationLockObserver.unregister();
                mRotationLockObserver = null;
            }
            if (mIconBadgingObserver != null) {
                mIconBadgingObserver.unregister();
                mIconBadgingObserver = null;
            }
            mPrefs.unregisterOnSharedPreferenceChangeListener(this);

            if (mShouldRestart) {
                triggerRestart();
            }
            super.onDestroy();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            switch (key) {
                case KEY_THEME_DARK:
                    updatDarkThemeEntry();
                case KEY_SHOW_DESKTOP_LABELS:
                case KEY_SHOW_DRAWER_LABELS:
                case KEY_THEME_BUILTIN_ICONS:
                    mShouldRestart = true;
                    break;
                case KEY_ADAPTIVE_ICONS:
                    updatAdaptiveIconsEntry();
                    mShouldRestart = true;
                    break;
                case KEY_GRID_SIZE:
                    mGridPref.setSummary(mPrefs.getString(KEY_GRID_SIZE, getDefaulGridSize()));
                    mShouldRestart = true;
                    break;
                case KEY_ICON_PACK:
                    updateIconPackEntry();
                    break;
            }
        }

        private void triggerRestart() {
            Context context = getActivity().getApplicationContext();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, 41, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT);
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            manager.set(AlarmManager.RTC, java.lang.System.currentTimeMillis() + 1, pi);
            java.lang.System.exit(0);
        }
    }

    /**
     * Content observer which listens for system auto-rotate setting changes, and enables/disables
     * the launcher rotation setting accordingly.
     */
    private static class SystemDisplayRotationLockObserver extends SettingsObserver.System {

        private final Preference mRotationPref;

        public SystemDisplayRotationLockObserver(
                Preference rotationPref, ContentResolver resolver) {
            super(resolver);
            mRotationPref = rotationPref;
        }

        @Override
        public void onSettingChanged(boolean enabled) {
            mRotationPref.setEnabled(enabled);
            mRotationPref.setSummary(enabled
                    ? R.string.settings_allow_rotation_desc : R.string.allow_rotation_blocked_desc);
        }
    }

    /**
     * Content observer which listens for system badging setting changes,
     * and updates the launcher badging setting subtext accordingly.
     */
    private static class IconBadgingObserver extends SettingsObserver.Secure
            implements Preference.OnPreferenceClickListener {

        private final ButtonPreference mBadgingPref;
        private final ContentResolver mResolver;
        private final FragmentManager mFragmentManager;
        private boolean serviceEnabled = true;

        public IconBadgingObserver(ButtonPreference badgingPref, ContentResolver resolver,
                FragmentManager fragmentManager) {
            super(resolver);
            mBadgingPref = badgingPref;
            mResolver = resolver;
            mFragmentManager = fragmentManager;
        }

        @Override
        public void onSettingChanged(boolean enabled) {
            int summary = enabled ? R.string.icon_badging_desc_on : R.string.icon_badging_desc_off;

            if (enabled) {
                // Check if the listener is enabled or not.
                String enabledListeners =
                        Settings.Secure.getString(mResolver, NOTIFICATION_ENABLED_LISTENERS);
                ComponentName myListener =
                        new ComponentName(mBadgingPref.getContext(), NotificationListener.class);
                serviceEnabled = enabledListeners != null &&
                        (enabledListeners.contains(myListener.flattenToString()) ||
                                enabledListeners.contains(myListener.flattenToShortString()));
                if (!serviceEnabled) {
                    summary = R.string.title_missing_notification_access;
                }
            }
            mBadgingPref.setWidgetFrameVisible(!serviceEnabled);
            mBadgingPref.setOnPreferenceClickListener(serviceEnabled && Utilities.ATLEAST_OREO ? null : this);
            mBadgingPref.setSummary(summary);
            if (((ActivityManager) LauncherAppState.getInstanceNoCreate().getContext().getSystemService(Context.ACTIVITY_SERVICE)).isLowRamDevice() && (!enabled || !serviceEnabled))
                mBadgingPref.setSummary(mBadgingPref.getSummary()+"\nWARNING: low ram device");
        }

        @Override
        public boolean onPreferenceClick(Preference preference) {
            if (!Utilities.ATLEAST_OREO && serviceEnabled) {
                ComponentName cn = new ComponentName(preference.getContext(), NotificationListener.class);
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(":settings:fragment_args_key", cn.flattenToString());
                preference.getContext().startActivity(intent);
            } else {
                new NotificationAccessConfirmation().show(mFragmentManager, "notification_access");
            }
            return true;
        }
    }

    public static class NotificationAccessConfirmation
            extends DialogFragment implements DialogInterface.OnClickListener {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();
            String msg = context.getString(R.string.msg_missing_notification_access,
                    context.getString(R.string.derived_app_name));
            return new AlertDialog.Builder(context)
                    .setTitle(R.string.title_missing_notification_access)
                    .setMessage(msg)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.title_change_settings, this)
                    .create();
        }

        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
            ComponentName cn = new ComponentName(getActivity(), NotificationListener.class);
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(":settings:fragment_args_key", cn.flattenToString());
            getActivity().startActivity(intent);
        }
    }
}
