package quiz.client.util;

import javafx.application.Platform;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Dispatches UI callbacks, with a view-lifetime check performed on the UI thread. */
public final class FxAsync {
    private final Executor uiExecutor;

    public FxAsync() { this(Platform::runLater); }
    public FxAsync(Executor uiExecutor) { this.uiExecutor = Objects.requireNonNull(uiExecutor); }

    public void run(Runnable action) { uiExecutor.execute(action); }

    /** done resets busy state, including cancellation. A disposed view receives no callbacks. */
    public <T> CompletableFuture<Void> observe(CompletionStage<T> future, BooleanSupplier viewActive,
                                               Consumer<T> success, Consumer<Throwable> error, Runnable done) {
        return future.handleAsync((value, failure) -> {
            if (!viewActive.getAsBoolean()) return null;
            try {
                if (failure == null) success.accept(value); else error.accept(unwrap(failure));
            } finally { done.run(); }
            return (Void) null;
        }, uiExecutor).toCompletableFuture();
    }

    public static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
