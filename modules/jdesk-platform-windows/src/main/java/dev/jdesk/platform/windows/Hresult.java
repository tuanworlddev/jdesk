package dev.jdesk.platform.windows;

/**
 * HRESULT handling: failures preserve the numeric code and the operation name
 * (spec section 6.4). Never surfaces to the frontend.
 */
final class Hresult {
    static final int S_OK = 0;
    static final int E_NOINTERFACE = 0x80004002;
    static final int E_NOTIMPL = 0x80004001;
    static final int E_FAIL = 0x80004005;

    private Hresult() {
    }

    static void check(int hr, String operation) {
        if (hr < 0) {
            throw new ComException(operation, hr);
        }
    }

    static final class ComException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int hresult;

        ComException(String operation, int hresult) {
            super(operation + " failed with HRESULT 0x" + Integer.toHexString(hresult));
            this.hresult = hresult;
        }

        int hresult() {
            return hresult;
        }
    }
}
