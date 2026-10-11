package com.exchange.app.wallet.dao.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exchange.app.wallet.dao.mapper.WalletTransactionMapper;
import com.exchange.app.wallet.exception.WalletExceptionFactory;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.transaction.TransactionStatus;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.common.db.manager.DbBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component
public class WalletTransactionRepository extends DbBaseRepository<WalletTransaction, WalletTransactionMapper> {
    @Autowired
    public WalletTransactionRepository(WalletTransactionMapper walletTransactionMapper) {
        super(walletTransactionMapper);
    }

    public WalletTransaction selectByInternalId(Long internalId) {
        return mapper.selectById(internalId);
    }

    public WalletTransaction selectByWalletTxnRef(String walletTxnRef) {
        var query = Wrappers.<WalletTransaction>lambdaQuery()
                .eq(WalletTransaction::getWalletTxnRef, walletTxnRef);
        return mapper.selectOne(query);
    }

    public WalletTransaction selectByUpstreamRequestRef(
            ServiceId initiator,
            String upstreamRequestRef) {
        return selectByUpstreamRequestRef(initiator, upstreamRequestRef, null);
    }

    public WalletTransaction selectByUpstreamRequestRef(
            ServiceId initiator,
            String upstreamRequestRef,
            LambdaQueryWrapper<WalletTransaction> queryWrapper) {
        if (initiator == null || Strings.isEmpty(upstreamRequestRef)) {
            throw WalletExceptionFactory.invalidValue(String.format(
                    "initiator or upstreamRequestRef is null, initiator:%s, upstreamRequestRef:%s",
                    initiator, upstreamRequestRef));
        }
        LambdaQueryWrapper<WalletTransaction> query = Objects.requireNonNullElseGet(queryWrapper, LambdaQueryWrapper::new);
        query = query.eq(WalletTransaction::getInitiator, initiator)
                .eq(WalletTransaction::getUpstreamRequestRef, upstreamRequestRef);
        return mapper.selectOne(query);
    }

    // restrict the update field, only update status
    public int updateTransactionStatus(WalletTransaction txn, Long id, TransactionStatus newStatus) {
        LambdaUpdateWrapper<WalletTransaction> update = new LambdaUpdateWrapper<>();
        update.eq(WalletTransaction::getInternalId, id)
                .eq(WalletTransaction::getTxnStatus, txn.getTxnStatus());
        WalletTransaction updateTxn = new WalletTransaction();
        updateTxn.setTxnStatus(newStatus);

        int affected = mapper.update(updateTxn, update);
        // update txn info to align with db
        txn.setTxnStatus(updateTxn.getTxnStatus());
        return affected;
    }
}
