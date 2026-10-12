package quiz.client.util;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FxAsyncTest {
    @Test void successIsScheduledAndBusyIsResetEvenWhenCallbackThrows() {
        var queue = new ArrayDeque<Runnable>(); var fx = new FxAsync(queue::add);
        var actions = new ArrayList<String>();
        var complete = fx.observe(CompletableFuture.completedFuture("value"), () -> true,
            value -> { actions.add(value); throw new IllegalStateException("callback"); },
            error -> fail("Not expected"), () -> actions.add("done"));
        assertTrue(actions.isEmpty()); queue.remove().run();
        assertEquals(List.of("value", "done"), actions); assertTrue(complete.isCompletedExceptionally());
    }

    @Test void errorsAreUnwrappedAndCancellationResetsBusy() {
        var queue = new ArrayDeque<Runnable>(); var fx = new FxAsync(queue::add);
        var error = new AtomicReference<Throwable>(); var done = new AtomicInteger();
        var cause = new IllegalArgumentException("safe");
        fx.observe(CompletableFuture.failedFuture(new CompletionException(new ExecutionException(cause))),
            () -> true, value -> fail("Not expected"), error::set, done::incrementAndGet);
        queue.remove().run(); assertSame(cause, error.get()); assertEquals(1, done.get());
        var cancelled = new CompletableFuture<String>();
        fx.observe(cancelled, () -> true, value -> fail("Not expected"), error::set, done::incrementAndGet);
        cancelled.cancel(false); queue.remove().run();
        assertInstanceOf(CancellationException.class, error.get()); assertEquals(2, done.get());
    }

    @Test void viewLifetimeIsCheckedAtExecutionNotScheduling() {
        var queue = new ArrayDeque<Runnable>(); var fx = new FxAsync(queue::add);
        var active = new AtomicBoolean(true); var calls = new AtomicInteger();
        var done = fx.observe(CompletableFuture.completedFuture(1), active::get,
            value -> calls.incrementAndGet(), error -> calls.incrementAndGet(), calls::incrementAndGet);
        active.set(false); queue.remove().run();
        assertEquals(0, calls.get()); assertTrue(done.isDone()); assertFalse(done.isCompletedExceptionally());
    }
}
