package com.exchange.app.wallet.dao.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.app.wallet.dao.mapper.WalletActionMapper;
import com.exchange.app.wallet.exception.WalletExceptionFactory;
import com.exchange.app.wallet.po.transaction.WalletAction;
import com.exchange.common.db.manager.DbBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class WalletActionRepository extends DbBaseRepository<WalletAction, WalletActionMapper> {
    @Autowired
    public WalletActionRepository(WalletActionMapper mapper) {
        super(mapper);
    }

    public int batchInsert(List<WalletAction> list) {
        if (list.isEmpty()) {
            throw WalletExceptionFactory.invalidValue("wallet action batch insert list must not be empty");
        }
        return mapper.batchInsert(list);
    }

    public List<WalletAction> selectByWalletTxnRef(String walletTxnRef, LambdaQueryWrapper<WalletAction> queryWrapper) {
        queryWrapper = Objects.requireNonNullElseGet(queryWrapper, LambdaQueryWrapper::new);
        queryWrapper.eq(WalletAction::getWalletTxnRef, walletTxnRef);
        return mapper.selectList(queryWrapper);
    }
}
