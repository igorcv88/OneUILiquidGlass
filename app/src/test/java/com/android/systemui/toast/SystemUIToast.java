package com.android.systemui.toast;

import android.view.View;

/** Test double: the toast object ToastFactory returns; its view is what ToastPresenter shows. */
public class SystemUIToast {
    private final View view;
    public SystemUIToast(View view) { this.view = view; }
    public View getView() { return view; }
}
