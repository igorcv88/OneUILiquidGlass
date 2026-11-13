package io.github.igorcv88.oneuiliquidglass.ui;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Sheet;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Ui;

/** The glass material choice from the surface screens, so it is reachable where it filters. */
final class MaterialSheet extends Sheet {
    private final MaterialPanel panel;
    private final AppModel.Listener listener;

    MaterialSheet(Host host) {
        super(host.activity(), host.theme(), host.activity().getString(R.string.material_title));
        panel = new MaterialPanel(host);
        body().addView(panel.view(), Ui.match());
        AppModel model = host.model();
        listener = panel::bind;
        model.listen(listener);
        onClosed(() -> model.unlisten(listener));
        panel.bind();
    }
}
