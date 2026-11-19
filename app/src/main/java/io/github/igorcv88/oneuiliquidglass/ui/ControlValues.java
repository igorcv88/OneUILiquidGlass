package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.ValueFormat;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;

/** Formats visible values using app resources and the app locale. */
final class ControlValues {
    static String display(Context context, Controls.Control c, String raw) {
        if (raw == null) return "";
        if (ValueFormat.isAuto(c, raw)) return ControlOptions.text(context, c.autoLabel);
        if (ValueFormat.isOff(c, raw)) return ControlOptions.text(context, c.offLabel);
        if (c.format == Controls.Format.TOGGLE)
            return context.getString("1".equals(raw) ? R.string.switch_on : R.string.switch_off);
        if (c.format == Controls.Format.CHOICE) {
            String[] choice = c.choices.get(raw);
            return choice == null ? raw : ControlOptions.text(context, choice[0]);
        }
        if (c.format == Controls.Format.READONLY && raw.isEmpty())
            return context.getString(R.string.control_not_reported);
        java.util.Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        if (ProductLayout.display(c.key) == ProductLayout.Display.MULTIPLIER_PERCENT) {
            try {
                return ValueFormat.percentOfMultiplier(Float.parseFloat(raw.trim()), locale);
            } catch (NumberFormatException e) {
                return raw;
            }
        }
        return ValueFormat.display(c, raw, locale);
    }
    private ControlValues() {}
}
