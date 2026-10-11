package com.exchange.app.wallet.processor;

import com.exchange.app.wallet.client.AccountServiceClient;
import com.exchange.app.wallet.dao.repository.BalanceSnapshotRepository;
import com.exchange.app.wallet.dao.repository.WalletAccountMappingRepository;
import com.exchange.app.wallet.dao.repository.WalletRepository;
import com.exchange.app.wallet.dao.store.BalanceSnapshotStore;
import com.exchange.app.wallet.po.enums.OwnerType;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.WalletStatus;
import com.exchange.app.wallet.po.wallet.Wallet;
import com.exchange.common.db.utils.DbTxnExecutor;
import com.exchange.common.identity.DefaultEntityRefCodec;
import com.exchange.common.identity.EntityIdentityFactory;
import com.exchange.common.identity.EntityRefType;
import com.exchange.proto.wallet.common.OwnerTypePb;
import com.exchange.proto.wallet.common.ServiceIdPb;
import com.exchange.proto.wallet.wallet.CreateWalletReplyPb;
import com.exchange.proto.wallet.wallet.CreateWalletRequestPb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateWalletProcessorTest {
    @Test
    void replayReturnsPersistedWalletRefWithoutAllocatingAnotherIdentity() {
        WalletRepository walletRepository = mock(WalletRepository.class);
        BalanceSnapshotStore snapshotStore = mock(BalanceSnapshotStore.class);
        EntityIdentityFactory identityFactory = mock(EntityIdentityFactory.class);
        DefaultEntityRefCodec codec = new DefaultEntityRefCodec();
        String walletRef = codec.encode(EntityRefType.WALLET, 41L);
        Wallet persisted = Wallet.create(41L, walletRef, ServiceId.USER, "customer-wallet-1",
                "USD", WalletStatus.OPEN, OwnerType.USER, "customer-1");
        when(walletRepository.selectByReferenceId(ServiceId.USER, "customer-wallet-1"))
                .thenReturn(persisted);

        CreateWalletProcessor processor = new CreateWalletProcessor(
                mock(DbTxnExecutor.class),
                mock(BalanceSnapshotRepository.class),
                mock(WalletAccountMappingRepository.class),
                walletRepository,
                snapshotStore,
                mock(AccountServiceClient.class),
                identityFactory,
                codec
        );
        CreateWalletRequestPb request = CreateWalletRequestPb.newBuilder()
                .setServiceId(ServiceIdPb.ServiceIdPb_User)
                .setReferenceId("customer-wallet-1")
                .setAssetId("USD")
                .setOwnerType(OwnerTypePb.OwnerTypePb_User)
                .setOwnerId("customer-1")
                .build();

        CreateWalletReplyPb reply = processor.createWallet(request);

        assertEquals(walletRef, reply.getWalletRef());
        verify(identityFactory, never()).next(any());
        verify(snapshotStore).postInsert(walletRef, ServiceId.USER, "customer-wallet-1", OwnerType.USER);
    }
}
