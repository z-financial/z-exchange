package com.exchange.app.ledger.dao.repository;

import com.exchange.app.ledger.config.DbQueryProperties;
import com.exchange.app.ledger.dao.mapper.LedgerEntryMapper;
import com.exchange.app.ledger.po.ledger.LedgerEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LedgerEntryRepositoryTest {
    private LedgerEntryRepository repo;
    private DbQueryProperties dbQueryConfig;

    @BeforeEach
    void setUp() {
        dbQueryConfig = mock(DbQueryProperties.class);
        repo = spy(new LedgerEntryRepository(mock(LedgerEntryMapper.class), dbQueryConfig));
    }

    @Test
    void getByLedgerTxnRefShouldPaginateUntilBatchSmallerThanBatchSize() {
        // Arrange
        String ledgerTxnRef = "LTX_TEST_001"; // placeholder

        when(dbQueryConfig.getDefaultBatchSize()).thenReturn(2);

        LedgerEntry e1 = new LedgerEntry();
        e1.setInternalId(1L);
        LedgerEntry e2 = new LedgerEntry();
        e2.setInternalId(2L);
        LedgerEntry e3 = new LedgerEntry();
        e3.setInternalId(3L);

        // Page 1: returns batchSize => loop continues, lastId becomes 2
        doReturn(List.of(e1, e2))
                .when(repo).getByLedgerTxnRefAndLastInternalId(ledgerTxnRef, 0L, 2);

        // Page 2: returns smaller batch => loop ends
        doReturn(List.of(e3))
                .when(repo).getByLedgerTxnRefAndLastInternalId(ledgerTxnRef, 2L, 2);

        // Act
        List<LedgerEntry> all = repo.getByLedgerTxnRef(ledgerTxnRef);

        // Assert
        assertThat(all).extracting(LedgerEntry::getInternalId).containsExactly(1L, 2L, 3L);

        verify(repo, times(2)).getByLedgerTxnRefAndLastInternalId(anyString(), anyLong(), anyInt());
        verify(repo).getByLedgerTxnRefAndLastInternalId(ledgerTxnRef, 0L, 2);
        verify(repo).getByLedgerTxnRefAndLastInternalId(ledgerTxnRef, 2L, 2);
    }

    @Test
    void getByLedgerTxnRefShouldStopImmediatelyWhenFirstBatchIsEmpty() {
        // Arrange
        String ledgerTxnRef = "LTX_TEST_002"; // placeholder

        when(dbQueryConfig.getDefaultBatchSize()).thenReturn(3);

        doReturn(List.of())
                .when(repo).getByLedgerTxnRefAndLastInternalId(ledgerTxnRef, 0L, 3);

        // Act
        List<LedgerEntry> all = repo.getByLedgerTxnRef(ledgerTxnRef);

        // Assert
        assertThat(all).isEmpty();
        verify(repo).getByLedgerTxnRefAndLastInternalId(ledgerTxnRef, 0L, 3);
        verify(repo, times(1)).getByLedgerTxnRefAndLastInternalId(anyString(), anyLong(), anyInt());
    }
}
