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

    /**
     * The same, a moment later.
     *
     * For work that has to see the result of something the framework does
     * asynchronously: the window's insets are not final when a system bar is
     * asked to hide, and a page told where the bars are too early keeps the
     * inset of a bar that is already gone.
     */
    static void postDelayed(Runnable runnable, long delayMs) {
        HANDLER.postDelayed(runnable, delayMs);
    }
}
