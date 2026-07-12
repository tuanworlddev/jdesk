package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ActivationDispatcherTest {
    @Test
    void serializesCallbacksAwayFromTheCallingThreadAndSurvivesHandlerFailure()
            throws Exception {
        Thread caller = Thread.currentThread();
        List<String> order = new ArrayList<>();
        AtomicBoolean ranOffCaller = new AtomicBoolean();
        CountDownLatch completed = new CountDownLatch(2);
        try (ActivationDispatcher dispatcher = new ActivationDispatcher(arguments -> {
            ranOffCaller.set(Thread.currentThread() != caller);
            String value = arguments.getFirst();
            synchronized (order) {
                order.add(value);
            }
            completed.countDown();
            if ("first".equals(value)) {
                throw new IllegalStateException("expected test failure");
            }
        })) {
            dispatcher.dispatch(List.of("first"));
            dispatcher.dispatch(List.of("second"));
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(ranOffCaller).isTrue();
        assertThat(order).containsExactly("first", "second");
    }
}
