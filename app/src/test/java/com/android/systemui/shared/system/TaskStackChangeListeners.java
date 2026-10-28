package com.android.systemui.shared.system;

import java.util.ArrayList;
import java.util.List;

/** Test-only stand-in for SystemUI's listener registry. */
public final class TaskStackChangeListeners {
    private static final TaskStackChangeListeners INSTANCE = new TaskStackChangeListeners();
    public final List<TaskStackChangeListener> registered = new ArrayList<>();
    public static TaskStackChangeListeners getInstance() { return INSTANCE; }
    public void registerTaskStackListener(TaskStackChangeListener l) { registered.add(l); }
}
