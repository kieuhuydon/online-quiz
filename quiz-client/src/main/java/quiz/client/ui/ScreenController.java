package quiz.client.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** A controller is mounted once. All lifecycle calls and UI callbacks belong on the FX thread. */
public abstract class ScreenController {
    private static final Logger log = LoggerFactory.getLogger(ScreenController.class);
    private final List<AutoCloseable> subscriptions = new ArrayList<>();
    private BooleanSupplier current = () -> false;
    private boolean disposed;

    final void mount(BooleanSupplier current) { this.current = current; }

    /** Pass this::isActive to FxAsync.observe so late callbacks cannot change an old screen. */
    public final boolean isActive() { return !disposed && current.getAsBoolean(); }

    protected final void own(AutoCloseable subscription) {
        if (disposed) throw new IllegalStateException("Controller has been disposed");
        subscriptions.add(subscription);
    }

    public final void dispose() {
        if (disposed) return;
        disposed = true;
        current = () -> false;
        for (AutoCloseable subscription : subscriptions) {
            try { subscription.close(); }
            catch (Exception failed) { log.warn("Cannot remove screen subscription: {}", failed.getClass().getSimpleName()); }
        }
        subscriptions.clear();
        onDispose();
    }

    protected void onDispose() { }
}
