package androidx.recyclerview.widget;

/** Test double: only the ViewHolder signature the chip adapter binds. */
public class RecyclerView {
    public static class ViewHolder {
        public final android.view.View itemView;
        public ViewHolder(android.view.View itemView) { this.itemView = itemView; }
    }
}
