package io.github.igorcv88.oneuiliquidglass.ui.surfaces;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.ui.kit.GroupHeader;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Icon;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Theme;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Ui;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * One collapsible group of controls on a card: header with its counter, the rows, and "Restore
 * group". The rows themselves are created by the page, which keeps them bound; this class owns only
 * the frame and the open state.
 */
public final class GroupSection {
    /** Adds the row of one key to the body (and registers it for binding). */
    public interface RowAdder {
        void add(LinearLayout body, String key);
    }

    public final LinearLayout card;
    public final List<String> keys;
    private final GroupHeader header;
    private final LinearLayout body;
    private final Context context;

    public GroupSection(Context c, Theme t, Icon icon, CharSequence title, List<String> keys, boolean open,
            Consumer<Boolean> openChanged, RowAdder rows, Runnable restore) {
        this.context = c;
        this.keys = Collections.unmodifiableList(keys);
        card = Ui.card(c, t);
        LinearLayout content = Ui.column(c);
        body = content;
        header = new GroupHeader(c, t, icon, title, open, now -> {
            Ui.reveal(content, now, t);
            if (openChanged != null) openChanged.accept(now);
        });
        card.addView(header);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        for (String key : keys) rows.add(body, key);
        TextView reset = Ui.button(c, t, c.getString(R.string.restore_group), Icon.RESET, Ui.ButtonStyle.TEXT, true);
        reset.setContentDescription(c.getString(R.string.restore_group) + ", " + title);
        reset.setOnClickListener(v -> restore.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(t.dp(8), t.dp(Theme.ROW_GAP_TIGHT), 0, t.dp(8));
        body.addView(reset, p);
        card.addView(body);
    }

    /** "3 of 7 active" for the keys of this group that take effect. */
    public void counter(int active) {
        header.counter(context.getString(R.string.group_active_count, active, keys.size()));
    }
}
