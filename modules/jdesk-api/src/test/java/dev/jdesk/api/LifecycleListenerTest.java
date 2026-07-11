package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Default no-op behaviour of {@link LifecycleListener} hooks. */
class LifecycleListenerTest {

    @Test
    void defaultMethodsAreNoOpsAndCloseIsPermittedByDefault() {
        LifecycleListener listener = new LifecycleListener() { };

        // None of the void hooks should throw.
        listener.onStarting();
        listener.onReady();
        listener.onStopping();
        listener.onStopped();

        assertThat(listener.onCloseRequested(new WindowId("main"))).isTrue();
    }

    @Test
    void onReadyWithHandleDelegatesToNoArgOnReady() {
        AtomicBoolean noArgCalled = new AtomicBoolean(false);
        LifecycleListener listener = new LifecycleListener() {
            @Override
            public void onReady() {
                noArgCalled.set(true);
            }
        };

        listener.onReady((ApplicationHandle) null);

        assertThat(noArgCalled).isTrue();
    }
}
