package com.exchange.app.ledger.processor.post;

import com.exchange.app.ledger.dao.store.LedgerTxnStore;
import com.exchange.app.ledger.po.ledger.LedgerEntry;
import com.exchange.app.ledger.po.ledger.LedgerTxn;
import com.exchange.app.ledger.result.LedgerServiceErrorCode;
import com.exchange.common.identity.EntityRefCodec;
import com.exchange.common.identity.EntityRefType;
import com.exchange.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.exchange.app.ledger.dao.repository.LedgerEntryRepository;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class GetLedgerTxnProcessor {
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerTxnStore ledgerTxnStore;
    private final EntityRefCodec entityRefCodec;

    public enum LookupType {
        LEDGER_TXN_REF(EntityRefType.LEDGER_TRANSACTION),
        // TODO: Resolve the expected reference type from sourceServiceId when more upstreams are supported.
        SOURCE_TXN_REF(EntityRefType.WALLET_TRANSACTION);

        private final EntityRefType entityRefType;

        LookupType(EntityRefType entityRefType) {
            this.entityRefType = entityRefType;
        }
    }

    @RequiredArgsConstructor
    public static class LedgerTxnInfo {
        public final LedgerTxn txn;
        public final List<LedgerEntry> entries;
    }

    public Result<LedgerTxnInfo> getLedgerTxn(LookupType type, String lookupValue, boolean includeEntries) {
        // Step 1: Validate parameters
        if (Strings.isEmpty(lookupValue)) {
            log.error("lookup_value is required");
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, "lookup_value is required");
        }
        if (type == null) {
            log.error("lookup_type is required");
            return Result.failure(LedgerServiceErrorCode.INTERNAL_ERROR, "lookup_type is required");
        }
        Result<Void> referenceValidation = entityRefCodec.validate(
                lookupValue, type.entityRefType);
        if (referenceValidation.isFailed()) {
            return Result.failure(
                    LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER,
                    referenceValidation.getDetail());
        }

        // Step 2: Query the ledger transaction
        LedgerTxn ledgerTxn;
        Result<LedgerTxn> ledgerResult = null;
        switch (type) {
            case LEDGER_TXN_REF:
                ledgerResult = ledgerTxnStore.getByLedgerTxnRef(lookupValue);
                break;
            case SOURCE_TXN_REF:
                ledgerResult = ledgerTxnStore.getBySourceTxnRef(lookupValue);
                break;
            default:
                log.error("Invalid lookup type: {}", type);
                return Result.failure(LedgerServiceErrorCode.INTERNAL_ERROR, "invalid lookup type: " + type);
        }
        if (!ledgerResult.isSuccess()) {
            log.error("load ledger txn failed, {}: {}", type.name(), lookupValue);
            return Result.failure(LedgerServiceErrorCode.INTERNAL_ERROR, "load ledger txn failed");
        }
        ledgerTxn = ledgerResult.getValue();

        if (ledgerTxn == null) {
            log.error("Ledger not found for {}: {}", type, lookupValue);
            return Result.failure(LedgerServiceErrorCode.LEDGER_NOT_FOUND, "ledger not found");
        }

        // Step 3: Query entries if requested
        List<LedgerEntry> ledgerEntries = includeEntries
                ? ledgerEntryRepository.getByLedgerTxnRef(ledgerTxn.getLedgerTxnRef())
                : null;

        if (includeEntries && CollectionUtils.isEmpty(ledgerEntries)) {
            log.error("internal data error, abnormal ledger, entry not found for {}: {}", type, lookupValue);
            throw new DataIntegrityViolationException(String.format("internal data error, abnormal ledger, entry not found, %s: %s", type, lookupValue));
        }

        // Step 4: Combine transaction and entries into a result object
        LedgerTxnInfo txnInfo = new LedgerTxnInfo(ledgerTxn, ledgerEntries);
        return Result.success(txnInfo);
    }
}
