package com.exchange.app.wallet.dao.repository;

import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.WalletStatus;
import com.exchange.app.wallet.dao.mapper.WalletMapper;
import com.exchange.app.wallet.po.wallet.Wallet;
import com.exchange.common.db.manager.DbBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WalletRepository extends DbBaseRepository<Wallet, WalletMapper> {
    @Autowired
    public WalletRepository(WalletMapper mapper) {
        super(mapper);
    }

    public Wallet selectByWalletRef(String walletRef) {
        return mapper.selectOne(queryLambdaWrapper()
                .eq(Wallet::getWalletRef, walletRef));
    }

    public Wallet selectByReferenceId(ServiceId serviceId, String referenceId) {
        return mapper.selectOne(queryLambdaWrapper()
                .eq(Wallet::getServiceId, serviceId)
                .eq(Wallet::getReferenceId, referenceId));
    }

    public int updateWalletStatus(String walletRef, WalletStatus oldStatus, WalletStatus newStatus) {
        return mapper.update(updateLambdaWrapper()
                .eq(Wallet::getWalletRef, walletRef)
                .eq(Wallet::getWalletStatus, oldStatus)
                .set(Wallet::getWalletStatus, newStatus));
    }
}
