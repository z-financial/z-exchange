package com.exchange.app.ledger.dao.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exchange.app.ledger.dao.mapper.AccountMapper;
import com.exchange.app.ledger.po.account.Account;
import com.exchange.app.ledger.po.enums.ServiceId;
import com.exchange.common.db.manager.DbBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class AccountRepository extends DbBaseRepository<Account, AccountMapper> {
    @Autowired
    public AccountRepository(AccountMapper mapper) {
        super(mapper);
    }

    public List<Account> getAccountIdInRef(List<String> refs) {
        LambdaQueryWrapper<Account> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(Account::getAccountId, Account::getReferenceId).in(Account::getReferenceId, refs);
        return mapper.selectList(wrapper);
    }

    public Account getByServiceAndReference(ServiceId serviceId, String referenceId) {
        return mapper.selectOne(queryLambdaWrapper()
                .eq(Account::getServiceId, serviceId)
                .eq(Account::getReferenceId, referenceId));
    }
}
