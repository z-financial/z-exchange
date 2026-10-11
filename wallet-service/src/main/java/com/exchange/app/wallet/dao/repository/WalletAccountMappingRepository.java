package com.exchange.app.wallet.dao.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.app.wallet.dao.mapper.WalletAccountMappingMapper;
import com.exchange.app.wallet.po.wallet.WalletAccountMapping;
import com.exchange.common.db.manager.DbBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class WalletAccountMappingRepository extends DbBaseRepository<WalletAccountMapping, WalletAccountMappingMapper> {
    @Autowired
    public WalletAccountMappingRepository(WalletAccountMappingMapper mapper) {
        super(mapper);
    }

    public List<WalletAccountMapping> selectInWalletRefs(List<String> walletRefs) {
        LambdaQueryWrapper<WalletAccountMapping> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WalletAccountMapping::getWalletRef, walletRefs);
        return mapper.selectList(queryWrapper);
    }
}
