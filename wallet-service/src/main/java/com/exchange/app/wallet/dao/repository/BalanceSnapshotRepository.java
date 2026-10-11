package com.exchange.app.wallet.dao.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exchange.app.wallet.dao.mapper.BalanceSnapshotMapper;
import com.exchange.app.wallet.exception.WalletExceptionFactory;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.WalletStatus;
import com.exchange.app.wallet.po.wallet.BalanceSnapshot;
import com.exchange.common.db.manager.DbBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class BalanceSnapshotRepository extends DbBaseRepository<BalanceSnapshot, BalanceSnapshotMapper> {
    @Autowired
    public BalanceSnapshotRepository(BalanceSnapshotMapper mapper) {
        super(mapper);
    }

    public List<BalanceSnapshot> selectByWalletRefs(List<String> walletRefs) {
        var query = Wrappers.<BalanceSnapshot>lambdaQuery().in(BalanceSnapshot::getWalletRef, walletRefs);
        return mapper.selectList(query);
    }

    public BalanceSnapshot getByWalletRef(String walletRef) {
        var query = Wrappers.<BalanceSnapshot>lambdaQuery().eq(BalanceSnapshot::getWalletRef, walletRef);
        return mapper.selectOne(query);
    }

    public BalanceSnapshot getByWalletBusinessRef(ServiceId serviceId, String walletBusinessRef) {
        var query = Wrappers.<BalanceSnapshot>lambdaQuery()
                .eq(BalanceSnapshot::getServiceId, serviceId)
                .eq(BalanceSnapshot::getWalletBusinessRef, walletBusinessRef);
        return mapper.selectOne(query);
    }

    public List<BalanceSnapshot> selectInIdForUpdate(List<Long> ids) {
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }

        LambdaQueryWrapper<BalanceSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BalanceSnapshot::getId, ids)
                .orderByAsc(BalanceSnapshot::getId)
                .last("for update");
        return mapper.selectList(wrapper);
    }

    public int updateBalanceWithInOptimisticLock(BalanceSnapshot now, BalanceSnapshot old) {
        BalanceSnapshot toUpdate = new BalanceSnapshot();
        if (!Objects.equals(now.getAvailable(), old.getAvailable())) {
            toUpdate.setAvailable(now.getAvailable());
        }
        if (!Objects.equals(now.getReserved(), old.getReserved())) {
            toUpdate.setReserved(now.getReserved());
        }
        if (!Objects.equals(now.getLastWalletTxnRef(), old.getLastWalletTxnRef())) {
            toUpdate.setLastWalletTxnRef(now.getLastWalletTxnRef());
        }
        toUpdate.setVersion(old.getVersion());

        var wrapper = updateLambdaWrapper().eq(BalanceSnapshot::getId, old.getId())
                .eq(BalanceSnapshot::getWalletRef, old.getWalletRef())
                .eq(BalanceSnapshot::getAssetId, old.getAssetId())
                .eq(BalanceSnapshot::getWalletStatus, old.getWalletStatus())
                .eq(BalanceSnapshot::getAvailable, old.getAvailable())
                .eq(BalanceSnapshot::getReserved, old.getReserved());
        int affected = mapper.update(toUpdate, wrapper);
        if (affected == 1) {
            now.setVersion(toUpdate.getVersion());
        }
        return affected;
    }

    public List<BalanceSnapshot> selectIdByRefs(ServiceId serviceId, List<String> walletRefs) {
        LambdaQueryWrapper<BalanceSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper = wrapper.select(BalanceSnapshot::getId, BalanceSnapshot::getWalletRef,
                        BalanceSnapshot::getServiceId, BalanceSnapshot::getWalletBusinessRef)
                .eq(BalanceSnapshot::getServiceId, serviceId)
                .in(BalanceSnapshot::getWalletRef, walletRefs);
        return this.mapper.selectList(wrapper);
    }

    public List<BalanceSnapshot> selectByRefs(ServiceId serviceId, List<String> walletRefs) {
        LambdaQueryWrapper<BalanceSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper = wrapper.eq(BalanceSnapshot::getServiceId, serviceId)
                .in(BalanceSnapshot::getWalletRef, walletRefs);
        return this.mapper.selectList(wrapper);
    }

    public int reserveFromWalletRef(Long id, String walletRef, String assetId, long amount) {
        if (amount <= 0) {
            throw WalletExceptionFactory.invalidValue("amount<=0, amount: " + amount);
        }
        LambdaUpdateWrapper<BalanceSnapshot> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BalanceSnapshot::getId, id)
                .eq(BalanceSnapshot::getWalletRef, walletRef)
                .eq(BalanceSnapshot::getAssetId, assetId)
                .eq(BalanceSnapshot::getWalletStatus, WalletStatus.OPEN)
                .ge(BalanceSnapshot::getAvailable, amount)
                .setSql(String.format("`available` = `available` - %d", amount))
                .setSql(String.format("`reserved` = `reserved` + %d", amount));
        return this.mapper.update(wrapper);
    }

    public int releaseFromWalletRef(Long id, String walletRef, String assetId, long amount) {
        if (amount <= 0) {
            throw WalletExceptionFactory.invalidValue("amount<=0, amount: " + amount);
        }
        LambdaUpdateWrapper<BalanceSnapshot> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BalanceSnapshot::getId, id)
                .eq(BalanceSnapshot::getWalletRef, walletRef)
                .eq(BalanceSnapshot::getAssetId, assetId)
                .eq(BalanceSnapshot::getWalletStatus, WalletStatus.OPEN)
                .ge(BalanceSnapshot::getReserved, amount)
                .setSql(String.format("`available` = `available` + %d", amount))
                .setSql(String.format("`reserved` = `reserved` - %d", amount));
        return this.mapper.update(wrapper);
    }

    public int transferOutFromWalletRef(Long id, String walletRef, String assetId, long amount) {
        if (amount <= 0) {
            throw WalletExceptionFactory.invalidValue("amount<=0, amount: " + amount);
        }
        LambdaUpdateWrapper<BalanceSnapshot> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BalanceSnapshot::getId, id)
                .eq(BalanceSnapshot::getWalletRef, walletRef)
                .eq(BalanceSnapshot::getAssetId, assetId)
                .eq(BalanceSnapshot::getWalletStatus, WalletStatus.OPEN)
                .ge(BalanceSnapshot::getReserved, amount)
                .setSql(String.format("`reserved` = `reserved` - %d", amount));
        return this.mapper.update(wrapper);
    }

    public int transferInToWalletRef(Long id, String walletRef, String assetId, long amount) {
        if (amount <= 0) {
            throw WalletExceptionFactory.invalidValue("amount<=0, amount: " + amount);
        }
        LambdaUpdateWrapper<BalanceSnapshot> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BalanceSnapshot::getId, id)
                .eq(BalanceSnapshot::getWalletRef, walletRef)
                .eq(BalanceSnapshot::getAssetId, assetId)
                .eq(BalanceSnapshot::getWalletStatus, WalletStatus.OPEN)
                .setSql(String.format("`available` = `available` + %d", amount));
        return this.mapper.update(wrapper);
    }
}
