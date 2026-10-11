package com.exchange.app.wallet.dao.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exchange.app.wallet.dao.mapper.WalletReservationMapper;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletReservationRepositoryTest {
    @Mock
    private WalletReservationMapper mapper;

    private WalletReservationRepository repository;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(WalletReservationMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, WalletReservation.class);
        repository = new WalletReservationRepository(mapper);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void selectByRefsScopesReservationReferencesToInitiator() {
        when(mapper.selectList(any())).thenReturn(List.of());

        repository.selectByRefs(ServiceId.USER, List.of("reservation-ref"));

        ArgumentCaptor<Wrapper<WalletReservation>> wrapperCaptor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(mapper).selectList(wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql.contains("initiator ="));
        assertTrue(sql.contains("wallet_reservation_ref in"));
    }
}
