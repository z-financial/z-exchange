package com.exchange.app.ledger.dao.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exchange.app.ledger.dao.mapper.LedgerTxnMapper;
import com.exchange.app.ledger.po.ledger.LedgerTxn;
import com.exchange.common.db.manager.DbBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LedgerTxnRepository extends DbBaseRepository<LedgerTxn, LedgerTxnMapper> {
    @Autowired
    public LedgerTxnRepository(LedgerTxnMapper mapper) {
        super(mapper);
    }

    public LedgerTxn getByLedgerTxnRef(String ledgerTxnRef) {
        var query = Wrappers.<LedgerTxn>lambdaQuery()
                .eq(LedgerTxn::getLedgerTxnRef, ledgerTxnRef);
        return mapper.selectOne(query);
    }

    public LedgerTxn getBySourceTxnRef(String sourceTxnRef) {
        var query = Wrappers.<LedgerTxn>lambdaQuery()
                .eq(LedgerTxn::getSourceTxnRef, sourceTxnRef);
        return mapper.selectOne(query);
    }

    public int updateMetadataByPk(LedgerTxn txn) {
        LedgerTxn toUpdate = new LedgerTxn() {{
            setInternalId(txn.getInternalId());
            setMetadata(txn.getMetadata());
            setVersion(txn.getVersion());
        }};
        int affected = mapper.updateById(toUpdate);

        // update version
        txn.setVersion(toUpdate.getVersion());
        return affected;
    }
}
