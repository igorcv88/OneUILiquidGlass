package io.github.igorcv88.oneuiliquidglass.config;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;

/**
 * Pure-Java contract shared by storage, provider and controls. No Android or renderer dependency.
 */
public final class SettingSpec {
    public enum Type {
        BOOLEAN,
        FLOAT,
        INT,
        ENUM,
        RGB,
        READONLY
    }

    public enum ApplyMode {
        LIVE,
        SF_RECOMPILE,
        READONLY
    }

    public final String key,
            group,
            surfaceScope,
            title,
            explanation,
            defaultValue,
            unit,
            choices,
            dependencies,
            previewStrategy,
            applicability,
            risk,
            storageOwner;
    public final Type type;
    public final ApplyMode applyMode;
    public final double min, max, step;
    public final boolean advanced;

    SettingSpec(
            String key,
            String group,
            String scope,
            String title,
            String explanation,
            Type type,
            String def,
            double min,
            double max,
            double step,
            String unit,
            String choices,
            String dependencies,
            boolean advanced,
            String previewStrategy) {
        this.key = key;
        this.group = group;
        surfaceScope = scope;
        this.title = title;
        this.explanation = explanation;
        this.type = type;
        defaultValue = def;
        this.min = min;
        this.max = max;
        this.step = step;
        this.unit = unit;
        this.choices = choices;
        this.dependencies = dependencies;
        this.advanced = advanced;
        this.previewStrategy = previewStrategy;
        applyMode =
                type == Type.READONLY
                        ? ApplyMode.READONLY
                        : key.startsWith("sf.") ? ApplyMode.SF_RECOMPILE : ApplyMode.LIVE;
        applicability = key.startsWith("sf.") || dependencies.equals("sfrefract=1") ? "SF" : "BOTH";
        risk =
                key.equals("sf.norewrite") || key.equals("sf.debug")
                        ? "danger"
                        : advanced ? "expert" : "ordinary";
        storageOwner =
                key.equals("sfrefract")
                        ? "live-helper"
                        : applyMode == ApplyMode.SF_RECOMPILE
                                ? "manual-system-property"
                                : "app-preference";
    }

    /**
     * Reject invalid values; never silently convert NaN, infinities, invalid enums or sentinel
     * gaps.
     */
    public String validate(String input) {
        if (input == null || input.length() > 96)
            throw new IllegalArgumentException("Valor inválido");
        String value = input.trim();
        switch (type) {
            case READONLY:
                throw new IllegalArgumentException("Somente leitura");
            case BOOLEAN:
                if (value.equals("1") || value.equals("0")) return value;
                break;
            case RGB:
                value = value.replaceFirst("^#", "");
                if (value.matches("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) return value.toLowerCase(Locale.ROOT);
                break;
            case ENUM:
                if (Arrays.asList(choices.split(",")).contains(value)) return value;
                // Samsung accepts an explicit six-component curve. Limit its components before IPC.
                if (key.equals("semcurve") && value.matches("[-0-9.,]+")) {
                    String[] parts = value.split(",", -1);
                    if (parts.length == 6) {
                        boolean valid = true;
                        for (String part : parts) {
                            try {
                                double n = Double.parseDouble(part);
                                valid &= Double.isFinite(n) && n >= -255 && n <= 255;
                            } catch (NumberFormatException e) {
                                valid = false;
                            }
                        }
                        if (valid) return value;
                    }
                }
                break;
            default:
                try {
                    double n = Double.parseDouble(value);
                    // Migrate valid pre-corrective QS profiles instead of rejecting the import.
                    // Values beyond the old domains remain errors; no general numeric clamping.
                    double legacyMax=key.equals("qs.lightfill")?.90:
                            key.equals("qs.darkfill")||key.equals("qs.idle")?1:-1;
                    if(Double.isFinite(n)&&legacyMax>max&&n>max&&n<=legacyMax)n=max;
                    if (!Double.isFinite(n)
                            || n < min
                            || n > max
                            || type == Type.INT && n != Math.rint(n)
                            || key.equals("kglens") && n != -1 && n < .1) break;
                    return BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException ignored) {
                    break;
                }
        }
        throw new IllegalArgumentException("Valor fora do domínio de debug.oulg." + key);
    }

    public boolean helperOwned() {
        return key.equals("sfrefract");
    }

    public String fullKey() {
        return "debug.oulg." + key;
    }
}
