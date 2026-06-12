package kh.karazin.foodwise.order.adapter.out.client;

import kh.karazin.foodwise.order.application.port.out.ProfileGateway;
import kh.karazin.foodwise.order.domain.ProfileId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing the {@link ProfileGateway} outbound port.
 */
@Component
@RequiredArgsConstructor
class ProfileGatewayAdapter implements ProfileGateway {

    private final ProfileServiceClient profileServiceClient;

    @Override
    public boolean profileExists(ProfileId profileId) {
        return profileServiceClient.profileExists(profileId.value());
    }
}
