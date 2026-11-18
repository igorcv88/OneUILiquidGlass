package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.igorcv88.oneuiliquidglass.R;

import java.util.function.Consumer;

/**
 * Header of a collapsible group of controls: icon, title, a counter ("3 of 7 active") and a
 * chevron (docs/OULG_UI_PLAN/DESIGN_SYSTEM.md section 4). The body is owned by the caller; this
 * view only reports the open state. The accessibility description carries title, counter and state,
 * and reduced motion swaps the chevron instantly.
 */
public final class GroupHeader extends LinearLayout {
    private final Theme t;
    private final TextView title, counter;
    private final Icon.View chevron;
    private final Consumer<Boolean> changed;
    private boolean open;

    public GroupHeader(Context c, Theme t, Icon icon, CharSequence heading, boolean open, Consumer<Boolean> changed) {
        super(c);
        this.t = t;
        this.open = open;
        this.changed = changed;
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        setMinimumHeight(t.dp(Theme.ROW_MIN + 8));
        setPadding(t.dp(18), t.dp(10), t.dp(14), t.dp(10));
        addView(Ui.badge(c, t, icon, t.accent, t.accentSoft));
        LinearLayout texts = Ui.column(c);
        title = Ui.text(c, t, heading, Theme.HEADLINE, t.ink, true);
        counter = Ui.text(c, t, "", Theme.CAPTION, t.inkMuted, false);
        counter.setVisibility(GONE);
        texts.addView(title);
        texts.addView(counter);
        addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        chevron = new Icon.View(c, Icon.CHEVRON_DOWN, t.inkFaint);
        chevron.setLayoutParams(new LayoutParams(t.dp(20), t.dp(20)));
        chevron.glyphRotation(open ? 180 : 0);
        addView(chevron);
        setBackground(Ui.ripple(t, null, 0));
        setClickable(true);
        setOnClickListener(v -> setOpen(!this.open, true));
        describe();
    }

    public boolean isOpen() {
        return open;
    }

    /** "3 of 7 active"; an empty text hides the counter. */
    public void counter(String text) {
        counter.setText(text);
        counter.setVisibility(text == null || text.isEmpty() ? GONE : VISIBLE);
        describe();
    }

    public void setOpen(boolean now, boolean notify) {
        if (now == open) return;
        open = now;
        animateChevron(now);
        describe();
        if (notify && changed != null) changed.accept(now);
    }

    private void animateChevron(boolean now) {
        if (t.reduceMotion || !isAttachedToWindow()) {
            chevron.glyphRotation(now ? 180 : 0);
            return;
        }
        ValueAnimator rotate = ValueAnimator.ofFloat(now ? 0 : 180, now ? 180 : 0);
        rotate.setDuration(t.ms(Theme.STANDARD_MS));
        rotate.addUpdateListener(a -> chevron.glyphRotation((float) a.getAnimatedValue()));
        rotate.start();
    }

    private void describe() {
        Context c = getContext();
        String state = c.getString(open ? R.string.a11y_expanded : R.string.a11y_collapsed);
        CharSequence count = counter.getVisibility() == VISIBLE ? counter.getText() : "";
        setContentDescription(title.getText() + (count.length() == 0 ? "" : ", " + count));
        setStateDescription(state);
    }
}
