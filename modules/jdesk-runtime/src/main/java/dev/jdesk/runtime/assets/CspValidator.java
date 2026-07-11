package dev.jdesk.runtime.assets;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import java.util.Locale;

/**
 * Release-build CSP screening (spec section 12.4): configurations containing unsafe
 * inline/eval allowances are rejected unless explicitly acknowledged through a named
 * option that surfaces in the build report.
 */
public final class CspValidator {
    public static final String DEFAULT_CSP =
            "default-src 'self'; script-src 'self'; style-src 'self'; "
                    + "img-src 'self' data:; connect-src 'self'; object-src 'none'; "
                    + "base-uri 'none'; frame-ancestors 'none'";

    private CspValidator() {
    }

    /**
     * @param acknowledgedUnsafe the named opt-in (e.g. Gradle property
     *        {@code jdesk.security.acknowledgeUnsafeCsp}) was set
     * @throws JDeskException when the CSP weakens script safety without acknowledgement
     */
    public static void validateForRelease(String csp, boolean acknowledgedUnsafe) {
        String lower = csp.toLowerCase(Locale.ROOT);
        boolean unsafe = lower.contains("'unsafe-inline'")
                || lower.contains("'unsafe-eval'")
                || lower.contains("'unsafe-hashes'");
        if (unsafe && !acknowledgedUnsafe) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Release CSP contains unsafe-inline/eval/hashes. Set the explicit "
                            + "acknowledgement option to accept this weakened policy.");
        }
    }
}
