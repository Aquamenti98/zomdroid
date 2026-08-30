package com.zomdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.zomdroid.LauncherPreferences;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentAppSettingsBinding;

/**
 * App-wide settings, which after the per-instance split is just the theme.
 *
 * <p>Renderer, Vulkan driver, JVM arguments, environment variables, render scale, the memory
 * saver, quick-save backup, debug and the on-screen control toggles all belong to a single game
 * instance now and are edited behind each instance card's gear button
 * ({@link SettingsFragment}). The audio API stopped being a setting when OpenSL ES was retired.
 */
public class AppSettingsFragment extends Fragment {
    private FragmentAppSettingsBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentAppSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ArrayAdapter<LauncherPreferences.ThemeMode> themeAdapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.spinner_item,
                LauncherPreferences.ThemeMode.values());
        themeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.appSettingsThemeS.setAdapter(themeAdapter);
        binding.appSettingsThemeS.setSelection(
                themeAdapter.getPosition(LauncherPreferences.requireSingleton().getThemeMode()));
        binding.appSettingsThemeS.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LauncherPreferences.ThemeMode mode =
                        (LauncherPreferences.ThemeMode) parent.getSelectedItem();
                LauncherPreferences.requireSingleton().setThemeMode(mode);
                AppCompatDelegate.setDefaultNightMode(mode.nightMode);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
