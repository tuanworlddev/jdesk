package dev.jdesk.api;

import java.util.Collection;
import java.util.Set;

/**
 * Immutable, deny-by-default set of capability grants. Anything not explicitly granted
 * is denied. Parsing from {@code jdesk-capabilities.json} is provided by the runtime
 * ({@code Capabilities.fromResource}).
 */
public interface CapabilitySet {
    /** True when {@code capability} is granted to the window with id {@code windowId}. */
    boolean isGranted(String capability, WindowId windowId);

    Set<CapabilityGrant> grants();

    static CapabilitySet of(Collection<CapabilityGrant> grants) {
        Set<CapabilityGrant> copy = Set.copyOf(grants);
        return new CapabilitySet() {
            @Override
            public boolean isGranted(String capability, WindowId windowId) {
                for (CapabilityGrant grant : copy) {
                    if (grant.capability().equals(capability)
                            && (grant.windows().isEmpty()
                                    || grant.windows().contains(windowId.value()))) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public Set<CapabilityGrant> grants() {
                return copy;
            }
        };
    }

    static CapabilitySet empty() {
        return of(Set.of());
    }
}
