package dev.jdesk.testapps.nativesmoke;

/** native-smoke probe. Not implemented yet; exits non-zero so it can never fake a pass. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.err.println("native-smoke: NOT IMPLEMENTED (phase 0 scaffold). Refusing to report success.");
        System.exit(64);
    }
}
