package com.exchange.app.wallet.processor;

import com.exchange.app.wallet.client.AccountServiceClient;
import com.exchange.app.wallet.dao.repository.BalanceSnapshotRepository;
import com.exchange.app.wallet.dao.repository.WalletAccountMappingRepository;
import com.exchange.app.wallet.dao.repository.WalletRepository;
import com.exchange.app.wallet.dao.store.BalanceSnapshotStore;
import com.exchange.app.wallet.po.enums.OwnerType;
import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.po.enums.WalletStatus;
import com.exchange.app.wallet.po.wallet.BalanceSnapshot;
import com.exchange.app.wallet.po.wallet.Wallet;
import com.exchange.app.wallet.po.wallet.WalletAccountMapping;
import com.exchange.app.wallet.result.PbErrorBuilder;
import com.exchange.app.wallet.result.WalletBoundaryErrorMapper;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.app.wallet.utils.*;
import com.exchange.common.db.utils.DbTxnExecutor;
import com.exchange.common.idgen.InternalIdGenerator;
import com.exchange.common.result.Result;
import com.exchange.proto.ledger.account.CreateAccountReplyPb;
import com.exchange.proto.ledger.account.CreateAccountRequestPb;
import com.exchange.proto.ledger.common.AccountCategoryPb;
import com.exchange.proto.ledger.common.NormalSidePb;
import com.exchange.proto.ledger.common.ServiceIdPb;
import com.exchange.proto.wallet.wallet.CreateWalletReplyPb;
import com.exchange.proto.wallet.wallet.CreateWalletRequestPb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CreateWalletProcessor {
    final private DbTxnExecutor dbTxnExecutor;
    final private BalanceSnapshotRepository balanceSnapshotManager;
    final private WalletAccountMappingRepository walletAccountMappingManager;

    final private WalletRepository walletManager;
    final private BalanceSnapshotStore balanceSnapshotStore;

    final private AccountServiceClient accountServiceClient;
    final private InternalIdGenerator internalIdGenerator;

    public CreateWalletReplyPb createWallet(CreateWalletRequestPb req) {
        ServiceId serviceId = EnumPbMappers.serviceIdPbMapper.to(req.getServiceId());
        OwnerType ownerType = EnumPbMappers.ownerTypePbMapper.to(req.getOwnerType());
        Result<Void> result = validateReq(req, serviceId, ownerType);
        if (!result.isSuccess()) {
            return replyError(result);
        }

        Wallet walletInDb = walletManager.selectByReferenceId(serviceId, req.getReferenceId());
        if (walletInDb != null) {
            if (!matchesRequest(walletInDb, req, serviceId, ownerType)) {
                return replyError(WalletServiceErrorCode.WALLET_INFO_MISMATCH,
                        "wallet reference already exists with different attributes");
            }
            if (walletInDb.getWalletStatus().hasInitiated()) {
                balanceSnapshotStore.postInsert(walletInDb.getWalletId(), walletInDb.getServiceId(), walletInDb.getReferenceId(), walletInDb.getOwnerType());
                return replySuccess("wallet has been created");
            }
        } else {
            Result<Wallet> walletResult = createWalletInDb(req, serviceId, ownerType);
            if (!walletResult.isSuccess()) {
                return replyError(walletResult);
            }
            walletInDb = walletResult.getValue();
        }

        result = createAccount(walletInDb);
        if (!result.isSuccess()) {
            return replyError(result);
        }
        Result<BalanceSnapshot> enableWalletResult = enableWallet(walletInDb);
        if (!enableWalletResult.isSuccess()) {
            return replyError(enableWalletResult);
        }
        BalanceSnapshot snapshot = enableWalletResult.getValue();
        balanceSnapshotStore.postInsert(snapshot);

        return replySuccess("success");
    }

    private Result<Void> validateReq(CreateWalletRequestPb req, ServiceId serviceId, OwnerType ownerType) {
        if (serviceId == null || serviceId == ServiceId.UNKNOWN) {
            return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid_service_id: " + req.getServiceId());
        }
        if (ownerType == null || ownerType == OwnerType.UNKNOWN) {
            return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid_owner_type: " + req.getOwnerType());
        }
        return Result.success();
    }

    private Result<Wallet> createWalletInDb(CreateWalletRequestPb req, ServiceId serviceId, OwnerType ownerType) {
        // Allocate before database work; the managed generator must never extend a business transaction.
        String walletId = Long.toString(internalIdGenerator.nextId());
        Wallet wallet = Wallet.create(walletId, serviceId, req.getReferenceId(), req.getAssetId(), WalletStatus.INIT, ownerType, req.getOwnerId());
        if (walletManager.insertIgnore(wallet) == 0) {
            // A concurrent creator may have won the business-key race. Its persisted ID is authoritative.
            Wallet winner = walletManager.selectByReferenceId(serviceId, req.getReferenceId());
            if (winner != null && matchesRequest(winner, req, serviceId, ownerType)) {
                return Result.success(winner);
            }
            if (winner != null) {
                return Result.failure(WalletServiceErrorCode.WALLET_INFO_MISMATCH,
                        "wallet reference already exists with different attributes");
            }
            return Result.failure(WalletServiceErrorCode.WALLET_DUPLICATED, wallet.toString());
        }
        return Result.success(wallet);
    }

    private boolean matchesRequest(
            Wallet wallet,
            CreateWalletRequestPb req,
            ServiceId serviceId,
            OwnerType ownerType) {
        return wallet.getServiceId() == serviceId
                && Objects.equals(wallet.getReferenceId(), req.getReferenceId())
                && Objects.equals(wallet.getAssetId(), req.getAssetId())
                && wallet.getOwnerType() == ownerType
                && Objects.equals(wallet.getOwnerId(), req.getOwnerId());
    }

    private Result<Void> createAccount(Wallet wallet) {
        CreateAccountRequestPb req = CreateAccountRequestPb.newBuilder()
                .setServiceId(ServiceIdPb.ServiceIdPb_Wallet)
                .setReferenceId(wallet.getWalletId())
                .setAssetId(wallet.getAssetId())
                .setCategory(AccountCategoryPb.AccountCategory_Liability)
                .setNormalSide(NormalSidePb.NormalSidePb_Credit)
                .setOwnerType(LedgerServiceHelper.ownerTypeToLedgerPb(wallet.getOwnerType()))
                .setOwnerId(wallet.getOwnerId())
                .build();
        Result<CreateAccountReplyPb> result = accountServiceClient.createAccount(req);
        if (result.isFailed()) {
            return Result.failure(result);
        }
        return Result.success();
    }

    // return fail for duplicated error code, another thread could be operating the same wallet
    private Result<BalanceSnapshot> enableWallet(Wallet wallet) {
        WalletAccountMapping mapping = WalletAccountMapping.create(wallet.getWalletId(), wallet.getWalletId());
        BalanceSnapshot snapshot = BalanceSnapshot.create(wallet.getWalletId(), wallet.getServiceId(), wallet.getReferenceId(), wallet.getAssetId(), WalletStatus.OPEN, wallet.getOwnerType(), wallet.getOwnerId(), 0L, 0L, 0L);

        Result<Void> result = dbTxnExecutor.executeWithDefault(() -> {
            if (walletManager.updateWalletStatus(wallet.getWalletId(), WalletStatus.INIT, WalletStatus.OPEN) == 0) {
                return Result.failure(WalletServiceErrorCode.WALLET_UPDATE_FAILED, "unable_to_change_status: " + wallet);
            }
            if (walletAccountMappingManager.insertIgnore(mapping) == 0) {
                return Result.failure(WalletServiceErrorCode.WALLET_ACCOUNT_MAPPING_DUPLICATED, "unexpected duplicate: " + mapping);
            }
            if (balanceSnapshotManager.insertIgnore(snapshot) == 0) {
                return Result.failure(WalletServiceErrorCode.BALANCE_SNAPSHOT_DUPLICATED, "unexpected duplicate: " + snapshot);
            }
            return Result.success();
        });
        if (!result.isSuccess()) {
            return Result.failure(result);
        }
        return Result.success(snapshot);
    }

    private CreateWalletReplyPb replySuccess(String detail) {
        CreateWalletReplyPb.Builder builder = CreateWalletReplyPb.newBuilder();
        return builder.setError(PbErrorBuilder.success(detail)).build();
    }

    private CreateWalletReplyPb replyError(Result<?> result) {
        WalletServiceErrorCode errorCode = WalletBoundaryErrorMapper.toWalletErrorCode(result);
        CreateWalletReplyPb.Builder builder = CreateWalletReplyPb.newBuilder();
        return builder.setError(PbErrorBuilder.build(errorCode, result.getDetail())).build();
    }

    private CreateWalletReplyPb replyError(WalletServiceErrorCode errorCode, String detail) {
        return CreateWalletReplyPb.newBuilder()
                .setError(PbErrorBuilder.build(errorCode, detail))
                .build();
    }
}
