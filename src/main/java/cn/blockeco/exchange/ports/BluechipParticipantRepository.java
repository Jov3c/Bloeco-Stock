package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Atomically moves a finite slice of the maker fund to the non-player market participant once. */
public interface BluechipParticipantRepository {
    boolean allocateOnce(Connection connection, UUID makerAccountId, UUID participantAccountId, Money cash,
                         long sharesPerCompany, List<BluechipRepository.BluechipCompany> bluechips) throws SQLException;

    /**
     * Reallocates already-owned system liquidity to its distinct quant participant when it falls
     * below its operating floor.  It never credits external/player money and never creates shares.
     */
    boolean rebalanceBelowFloor(Connection connection, UUID makerAccountId, UUID participantAccountId,
                                Money cashFloor, Money cashTarget, long sharesFloor, long sharesTarget,
                                List<BluechipRepository.BluechipCompany> bluechips) throws SQLException;
}
