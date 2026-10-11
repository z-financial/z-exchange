package com.exchange.app.ledger.dao.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exchange.app.ledger.config.DbQueryProperties;
import com.exchange.app.ledger.dao.mapper.LedgerEntryMapper;
import com.exchange.app.ledger.exception.LedgerExceptionFactory;
import com.exchange.app.ledger.po.ledger.LedgerEntry;
import com.exchange.common.db.manager.DbBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Repository
@EnableConfigurationProperties(DbQueryProperties.class)
public class LedgerEntryRepository extends DbBaseRepository<LedgerEntry, LedgerEntryMapper> {
    private final DbQueryProperties dbQueryConfig;

    public LedgerEntryRepository(LedgerEntryMapper mapper, DbQueryProperties dbQueryConfig) {
        super(mapper);
        this.dbQueryConfig = dbQueryConfig;
    }

    public List<LedgerEntry> getByLedgerTxnRef(String ledgerTxnRef) {
        List<LedgerEntry> result = new ArrayList<>();
        long lastInternalId = 0L;
        List<LedgerEntry> batch;
        int batchSize = dbQueryConfig.getDefaultBatchSize();

        do {
            batch = getByLedgerTxnRefAndLastInternalId(
                    ledgerTxnRef, lastInternalId, batchSize);
            result.addAll(batch);

            if (!batch.isEmpty()) {
                lastInternalId = batch.getLast().getInternalId();
            }

            log.debug("fetched ledger entry batch, size={}, ledgerTxnRef={}",
                    batch.size(), ledgerTxnRef);
        } while (batch.size() == batchSize);

        log.debug("completed ledger entry fetch, ledgerTxnRef={}", ledgerTxnRef);
        return result;
    }

    public int batchInsert(List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            throw LedgerExceptionFactory.invalidValue("ledger entry batch insert list must not be empty");
        }
        return mapper.batchInsert(entries);
    }

    // set to protected scope for unit test
    protected List<LedgerEntry> getByLedgerTxnRefAndLastInternalId(
            String ledgerTxnRef, long lastInternalId, int size) {
        return mapper.selectList(
                Wrappers.<LedgerEntry>lambdaQuery()
                        .eq(LedgerEntry::getLedgerTxnRef, ledgerTxnRef)
                        .gt(LedgerEntry::getInternalId, lastInternalId)
                        .orderByAsc(LedgerEntry::getInternalId)
                        .last("LIMIT " + size)
        );
    }
}
