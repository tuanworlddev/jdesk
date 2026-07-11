package dev.jdesk.ffm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Tests use-after-free protection for native upcalls (spec section 6.2). */
class CallbackGateTest {

    @Test
    void enterAndExitCountInFlight() {
        CallbackGate gate = new CallbackGate();
        assertThat(gate.inFlight()).isZero();

        assertThat(gate.enter()).isTrue();
        assertThat(gate.inFlight()).isEqualTo(1);

        assertThat(gate.enter()).isTrue();
        assertThat(gate.enter()).isTrue();
        assertThat(gate.inFlight()).isEqualTo(3);

        gate.exit();
        assertThat(gate.inFlight()).isEqualTo(2);
        gate.exit();
        gate.exit();
        assertThat(gate.inFlight()).isZero();
    }

    @Test
    void enterAfterCloseReturnsFalse() {
        CallbackGate gate = new CallbackGate();
        assertThat(gate.isClosed()).isFalse();

        assertThat(gate.closeAndAwaitQuiescence(Duration.ofSeconds(1))).isTrue();
        assertThat(gate.isClosed()).isTrue();

        assertThat(gate.enter()).isFalse();
        assertThat(gate.inFlight()).isZero();
    }

    @Test
    void closeWhenAlreadyQuiescentReturnsImmediately() {
        CallbackGate gate = new CallbackGate();
        gate.enter();
        gate.exit();

        long start = System.nanoTime();
        assertThat(gate.closeAndAwaitQuiescence(Duration.ofSeconds(10))).isTrue();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMillis).isLessThan(1000);
    }

    @Test
    void closeAndAwaitQuiescenceWaitsForInFlightCallback() throws Exception {
        CallbackGate gate = new CallbackGate();
        assertThat(gate.enter()).isTrue(); // in-flight callback held by "worker"

        CountDownLatch closerStarted = new CountDownLatch(1);
        CountDownLatch closerDone = new CountDownLatch(1);
        AtomicBoolean quiescent = new AtomicBoolean();

        Thread closer = new Thread(() -> {
            closerStarted.countDown();
            quiescent.set(gate.closeAndAwaitQuiescence(Duration.ofSeconds(5)));
            closerDone.countDown();
        });
        closer.start();

        assertThat(closerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // The closer must block while the callback is still in-flight.
        assertThat(closerDone.await(300, TimeUnit.MILLISECONDS))
                .as("closeAndAwaitQuiescence must not return while a callback is in-flight")
                .isFalse();
        // New entries are rejected as soon as the close began.
        assertThat(gate.isClosed()).isTrue();
        assertThat(gate.enter()).isFalse();

        gate.exit(); // callback finishes

        assertThat(closerDone.await(5, TimeUnit.SECONDS)).isTrue();
        closer.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(quiescent.get()).isTrue();
        assertThat(gate.inFlight()).isZero();
    }

    @Test
    void quiescenceTimesOutWhenCallbackNeverExits() {
        CallbackGate gate = new CallbackGate();
        assertThat(gate.enter()).isTrue(); // never exits

        long start = System.nanoTime();
        boolean quiescent = gate.closeAndAwaitQuiescence(Duration.ofMillis(200));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(quiescent).isFalse();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(150);
        assertThat(gate.isClosed()).isTrue();
        assertThat(gate.inFlight()).isEqualTo(1);
    }

    @Test
    void exitWithoutEnterThrows() {
        CallbackGate gate = new CallbackGate();
        assertThatThrownBy(gate::exit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit() without matching enter()");
    }

    @Test
    void extraExitAfterBalancedPairThrows() {
        CallbackGate gate = new CallbackGate();
        gate.enter();
        gate.exit();
        assertThatThrownBy(gate::exit).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void inFlightIsAccurateAcrossThreads() throws Exception {
        CallbackGate gate = new CallbackGate();
        final int workers = 5;
        CountDownLatch entered = new CountDownLatch(workers);
        CountDownLatch mayExit = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(workers);

        for (int i = 0; i < workers; i++) {
            new Thread(() -> {
                assertThat(gate.enter()).isTrue();
                entered.countDown();
                try {
                    if (!mayExit.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("exit latch never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                gate.exit();
                exited.countDown();
            }).start();
        }

        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.inFlight()).isEqualTo(workers);

        mayExit.countDown();
        assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gate.inFlight()).isZero();
    }
}
