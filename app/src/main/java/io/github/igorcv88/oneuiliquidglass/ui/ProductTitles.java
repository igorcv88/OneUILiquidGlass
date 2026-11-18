package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;

/** The everyday name of a control: the product title when the layout names one, else the existing title. */
final class ProductTitles {
    static String title(Context context, Controls.Control control) {
        int res = ProductLayout.productTitle(control.key);
        return res != 0 ? context.getString(res) : ControlTitles.title(context, control);
    }

    private ProductTitles() {}
}
