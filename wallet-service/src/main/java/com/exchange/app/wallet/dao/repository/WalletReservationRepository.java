package com.exchange.app.wallet.dao.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.exchange.app.wallet.dao.mapper.WalletReservationMapper;
import com.exchange.app.wallet.exception.WalletExceptionFactory;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import com.exchange.common.db.manager.DbBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
public class WalletReservationRepository extends DbBaseRepository<WalletReservation, WalletReservationMapper> {
    @Autowired
    public WalletReservationRepository(WalletReservationMapper mapper) {
        super(mapper);
    }

    public List<WalletReservation> selectByWalletTxnRef(String walletTxnRef) {
        return selectByWalletTxnRef(walletTxnRef, null);
    }

    public List<WalletReservation> selectByWalletTxnRef(String walletTxnRef, LambdaQueryWrapper<WalletReservation> queryWrapper) {
        queryWrapper = Objects.requireNonNullElseGet(queryWrapper, LambdaQueryWrapper::new);
        queryWrapper = queryWrapper.eq(WalletReservation::getReserveWalletTxnRef, walletTxnRef);
        return mapper.selectList(queryWrapper);
    }

    // Reservation references are owned by an initiator. Keep the lookup aligned with the
    // unique key so a caller can never resolve another initiator's reservation by collision.
    public List<WalletReservation> selectByRefs(ServiceId initiator, List<String> refs) {
        if (refs.isEmpty()) {
            return new ArrayList<>();
        }

        var wrapper = queryLambdaWrapper()
                .eq(WalletReservation::getInitiator, initiator)
                .in(WalletReservation::getWalletReservationRef, refs);
        return mapper.selectList(wrapper);
    }

    public List<WalletReservation> selectInIdForUpdate(List<Long> ids) {
        LambdaUpdateWrapper<WalletReservation> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper = updateWrapper.in(WalletReservation::getInternalId, ids).last("for update");
        return mapper.selectList(updateWrapper);
    }

    // update with optimistic lock of amount and status
    // update with all fields of now
    public int updateWithInOptimisticLock(WalletReservation now, WalletReservation old) {
        var wrapper = updateLambdaWrapper().eq(WalletReservation::getInternalId, now.getInternalId())
                .eq(WalletReservation::getReservationStatus, old.getReservationStatus())
                .eq(WalletReservation::getRemaining, old.getRemaining())
                .eq(WalletReservation::getPendingSettle, old.getPendingSettle())
                .eq(WalletReservation::getConsumed, old.getConsumed())
                .eq(WalletReservation::getReleased, old.getReleased());
        return mapper.update(now, wrapper);
    }

    public int batchInsert(List<WalletReservation> list) {
        if (list.isEmpty()) {
            throw WalletExceptionFactory.invalidValue("wallet reservation batch insert list must not be empty");
        }
        return mapper.batchInsert(list);
    }
}
