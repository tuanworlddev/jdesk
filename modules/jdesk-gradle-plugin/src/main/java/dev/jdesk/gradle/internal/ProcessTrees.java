package dev.jdesk.gradle.internal;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Terminates a child process together with its descendants (dev-server trees). */
public final class ProcessTrees {
    private ProcessTrees() {
    }

    /**
     * Politely asks the whole tree to stop, then force-kills whatever is still alive.
     * Blocks briefly; never throws.
     */
    public static void destroy(Process process) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = process.descendants().toList();
        try {
            descendants.reversed().forEach(ProcessHandle::destroy);
            process.destroy();
            process.waitFor(3, TimeUnit.SECONDS);
            descendants.reversed().stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            waitForExit(descendants, process.toHandle(), 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            descendants.reversed().stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void waitForExit(List<ProcessHandle> descendants, ProcessHandle root,
                                    long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (!root.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive)) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
