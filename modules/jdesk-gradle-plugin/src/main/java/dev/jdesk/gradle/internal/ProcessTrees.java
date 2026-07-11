package dev.jdesk.gradle.internal;

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
        try {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            } else {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
            }
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
