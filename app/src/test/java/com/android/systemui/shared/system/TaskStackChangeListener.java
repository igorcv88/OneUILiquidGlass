package com.android.systemui.shared.system;

/** Test-only stand-in for SystemUI's listener interface (default methods, as in AOSP). */
public interface TaskStackChangeListener {
    default void onTaskStackChanged() {}
    default void onTaskMovedToFront(int taskId) {}
    default void onLockTaskModeChanged(int mode) {}
    default boolean onTaskSnapshotChanged(int taskId, Object snapshot) { return false; }
}
