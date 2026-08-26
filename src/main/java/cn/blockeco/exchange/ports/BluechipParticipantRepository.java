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
}
