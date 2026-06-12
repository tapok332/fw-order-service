package kh.karazin.foodwise.order.adapter.out.client;

import kh.karazin.foodwise.common.dto.internal.InternalSurpriseBoxDto;
import kh.karazin.foodwise.common.money.Money;
import kh.karazin.foodwise.order.application.port.out.SurpriseBoxGateway;
import kh.karazin.foodwise.order.domain.SurpriseBoxId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SurpriseBoxGatewayAdapter}: maps a complete internal DTO
 * into a {@link SurpriseBoxGateway.ResolvedBox}, and collapses both an infra
 * failure ({@code null}) and an incomplete payload (missing price/title) to
 * {@code null} so the placement use case rejects the order uniformly.
 */
@ExtendWith(MockitoExtension.class)
class SurpriseBoxGatewayAdapterTest {

    private static final Currency UAH = Currency.getInstance("UAH");

    @Mock private SurpriseBoxServiceClient surpriseBoxServiceClient;

    @InjectMocks private SurpriseBoxGatewayAdapter adapter;

    @Test
    @DisplayName("maps a complete payload into a ResolvedBox")
    void resolve_mapsCompletePayload() {
        UUID boxId = UUID.randomUUID();
        Money price = Money.ofMinor(250L, UAH);
        when(surpriseBoxServiceClient.getSurpriseBox(boxId)).thenReturn(new InternalSurpriseBoxDto(
                boxId, "Box title", price, 10, null, null, null));

        SurpriseBoxGateway.ResolvedBox resolved = adapter.resolve(new SurpriseBoxId(boxId));

        assertThat(resolved).isNotNull();
        assertThat(resolved.title()).isEqualTo("Box title");
        assertThat(resolved.price()).isEqualTo(price);
    }

    @Test
    @DisplayName("returns null when the client signals an infra failure (null)")
    void resolve_nullOnInfraFailure() {
        UUID boxId = UUID.randomUUID();
        when(surpriseBoxServiceClient.getSurpriseBox(boxId)).thenReturn(null);

        assertThat(adapter.resolve(new SurpriseBoxId(boxId))).isNull();
    }

    @Test
    @DisplayName("collapses an incomplete payload (missing price) to null")
    void resolve_nullOnIncompletePayload() {
        UUID boxId = UUID.randomUUID();
        when(surpriseBoxServiceClient.getSurpriseBox(boxId)).thenReturn(new InternalSurpriseBoxDto(
                boxId, "Box title", /* price = */ null, 10, null, null, null));

        assertThat(adapter.resolve(new SurpriseBoxId(boxId))).isNull();
    }
}
