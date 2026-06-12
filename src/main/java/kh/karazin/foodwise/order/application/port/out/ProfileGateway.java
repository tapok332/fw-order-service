package kh.karazin.foodwise.order.application.port.out;

import kh.karazin.foodwise.order.domain.ProfileId;

/**
 * Outbound port for profile-service.
 */
public interface ProfileGateway {

    /**
     * Whether the profile exists. Degrades pessimistically to {@code false} on a
     * downstream outage, so an order is rejected rather than created for a
     * possibly-non-existent user (ADR 0006).
     */
    boolean profileExists(ProfileId profileId);
}
