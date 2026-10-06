package app.hanime.shell;

import android.os.Handler;
import android.os.Looper;

/** Posts back to the UI thread without dragging in a scheduler library. */
final class MainThread {

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private MainThread() {
    }

    static void post(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            HANDLER.post(runnable);
        }
    }
}
