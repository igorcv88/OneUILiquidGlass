package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.ui.SettingStatus;

import org.junit.Test;

import java.util.*;

public class SettingStatusTest {
    @Test
    public void nativeAndLiveDependenciesDoNotClaimBothModes() {
        Map<String, String> values = new HashMap<>();
        assertNotNull(SettingStatus.inactive(SettingsRegistry.get("sf.kgcore"), values));
        assertNull(SettingStatus.inactive(SettingsRegistry.get("liveglint"), values));
        values.put("sfrefract", "1");
        assertNull(SettingStatus.inactive(SettingsRegistry.get("sf.kgcore"), values));
        assertNotNull(SettingStatus.inactive(SettingsRegistry.get("liveglint"), values));
    }
}
