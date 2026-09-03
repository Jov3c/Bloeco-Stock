package cn.blockeco.exchange.paper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.money.Money;
import org.junit.jupiter.api.Test;

class IpoOfferPreviewTest {
    @Test
    void calculatesTheSharesShownBeforeAnIpoIsPublished() {
        Money target = Money.fromMajor(java.math.BigDecimal.valueOf(500_000), 2);
        Money price = Money.fromMajor(java.math.BigDecimal.valueOf(100), 2);

        assertThat(IpoOfferPreview.estimatedShares(target, price)).isEqualTo(5_000L);
    }
}
