package quiz.client.ui;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScreenControllerTest {
    @Test void disposalInvalidatesCallbacksAndRemovesEverySubscriptionOnce() {
        var first = new AtomicInteger();
        var second = new AtomicInteger();
        var cleaned = new AtomicInteger();
        ScreenController screen = new ScreenController() {
            { own(() -> { first.incrementAndGet(); throw new Exception("fixture failure"); }); own(second::incrementAndGet); }
            @Override protected void onDispose() { cleaned.incrementAndGet(); }
        };
        var current = new AtomicBoolean(true);
        screen.mount(current::get);
        assertTrue(screen.isActive());
        current.set(false); assertFalse(screen.isActive());
        current.set(true); screen.dispose(); screen.dispose();
        assertFalse(screen.isActive());
        assertEquals(1, first.get()); assertEquals(1, second.get()); assertEquals(1, cleaned.get());
    }
}
