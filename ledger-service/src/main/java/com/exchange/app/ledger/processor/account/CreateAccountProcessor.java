package com.exchange.app.ledger.processor.account;

import com.exchange.app.ledger.dao.repository.AccountRepository;
import com.exchange.app.ledger.dao.repository.AssetRepository;
import com.exchange.app.ledger.po.account.Account;
import com.exchange.app.ledger.po.asset.Asset;
import com.exchange.app.ledger.result.LedgerBoundaryErrorMapper;
import com.exchange.app.ledger.result.LedgerServiceErrorCode;
import com.exchange.app.ledger.result.PbErrorBuilder;
import com.exchange.common.result.IResult;
import com.exchange.common.result.Result;
import com.exchange.proto.ledger.account.CreateAccountReplyPb;
import com.exchange.proto.ledger.account.CreateAccountRequestPb;
import com.exchange.app.ledger.utils.EnumMappers;
import com.exchange.app.ledger.utils.ValidateHelper;
import com.exchange.app.ledger.po.enums.*;
import com.exchange.common.identity.EntityIdentityFactory;
import com.exchange.common.identity.EntityRefCodec;
import com.exchange.common.identity.EntityRefType;
import com.exchange.common.identity.GeneratedEntityIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CreateAccountProcessor {
    final private AccountRepository accountManager;
    final private AssetRepository assetRepository;
    final private EntityIdentityFactory entityIdentityFactory;
    final private EntityRefCodec entityRefCodec;

    public CreateAccountReplyPb createAccount(CreateAccountRequestPb req) {

        ServiceId sourceServiceId = EnumMappers.serviceIdPbMapper.to(req.getSourceServiceId());
        AccountCategory category = EnumMappers.accountCategoryPbMapper.to(req.getCategory());
        NormalSide normalSide = EnumMappers.normalSidePbMapper.to(req.getNormalSide());
        OwnerType ownerType = EnumMappers.ownerTypePbMapper.to(req.getOwnerType());

        Result<Void> result = validateReq(req, sourceServiceId, category, normalSide, ownerType);
        if (!result.isSuccess()) {
            return replyError(result);
        }

        Account accountInDb = accountManager.getBySourceServiceAndAccountRef(
                sourceServiceId, req.getSourceAccountRef());
        if (accountInDb != null) {
            return matchesRequest(accountInDb, req, sourceServiceId, category, normalSide, ownerType)
                    ? replySuccess(accountInDb.getLedgerAccountRef(), "account already exists")
                    : replyError(LedgerServiceErrorCode.ACCOUNT_INFO_MISMATCH,
                            "account reference already exists with different attributes");
        }

        Asset asset = assetRepository.getByAssetId(req.getAssetId());
        if (asset == null) {
            return replyError(LedgerServiceErrorCode.ASSET_NOT_FOUND, req.getAssetId());
        }

        // Account identity is allocated only for a new owner row and before persistence.
        GeneratedEntityIdentity identity = entityIdentityFactory.next(EntityRefType.LEDGER_ACCOUNT);
        Account account = Account.create(identity.internalId(), identity.reference(), sourceServiceId,
                req.getSourceAccountRef(), category, normalSide, req.getOwnerId(), ownerType,
                req.getAssetId(), AccountStatus.OPEN);
        if (accountManager.insertIgnore(account) == 0) {
            // A concurrent request may have inserted the same business reference first.
            Account winner = accountManager.getBySourceServiceAndAccountRef(
                    sourceServiceId, req.getSourceAccountRef());
            if (winner != null && matchesRequest(winner, req, sourceServiceId, category, normalSide, ownerType)) {
                return replySuccess(winner.getLedgerAccountRef(), "account already exists");
            }
            if (winner != null) {
                return replyError(LedgerServiceErrorCode.ACCOUNT_INFO_MISMATCH,
                        "account reference already exists with different attributes");
            }
            return replyError(LedgerServiceErrorCode.SERVER_ERROR, "account ID already exists");
        }
        return replySuccess(account.getLedgerAccountRef(), "success");
    }

    private boolean matchesRequest(
            Account account,
            CreateAccountRequestPb req,
            ServiceId sourceServiceId,
            AccountCategory category,
            NormalSide normalSide,
            OwnerType ownerType) {
        return account.getSourceServiceId() == sourceServiceId
                && Objects.equals(account.getSourceAccountRef(), req.getSourceAccountRef())
                && account.getCategory() == category
                && account.getNormalSide() == normalSide
                && Objects.equals(account.getOwnerId(), req.getOwnerId())
                && account.getOwnerType() == ownerType
                && Objects.equals(account.getAssetId(), req.getAssetId());
    }

    private Result<Void> validateReq(CreateAccountRequestPb req, ServiceId sourceServiceId, AccountCategory category, NormalSide normalSide, OwnerType ownerType) {
        if (sourceServiceId == null || sourceServiceId == ServiceId.UNKNOWN) {
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER,
                    "invalid_source_service_id: " + req.getSourceServiceId());
        }
        if (category == null || category == AccountCategory.UNKNOWN) {
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid_category: " + req.getCategory());
        }
        if (normalSide == null || normalSide == NormalSide.UNKNOWN) {
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid_normal_side: " + req.getNormalSide());
        }
        if (ownerType == null || ownerType == OwnerType.UNKNOWN) {
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid_owner_type: " + req.getOwnerType());
        }

        // TODO: Select the accepted reference type from sourceServiceId when more upstreams are supported.
        Result<Void> referenceValidation =
                entityRefCodec.validate(req.getSourceAccountRef(), EntityRefType.WALLET);
        if (referenceValidation.isFailed()) {
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER,
                    referenceValidation.getDetail());
        }

        if (!ValidateHelper.validateNormalSideAndCategory(normalSide, category)) {
            return Result.failure(LedgerServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid_normal_side_category_pair: " + req.getNormalSide());
        }

        return Result.success();
    }

    private CreateAccountReplyPb replySuccess(String ledgerAccountRef, String detail) {
        return CreateAccountReplyPb.newBuilder()
                .setLedgerAccountRef(ledgerAccountRef)
                .setError(PbErrorBuilder.success(detail))
                .build();
    }

    private CreateAccountReplyPb replyError(LedgerServiceErrorCode errorCode, String detail) {
        CreateAccountReplyPb.Builder builder = CreateAccountReplyPb.newBuilder();
        return builder.setError(PbErrorBuilder.build(errorCode, detail)).build();
    }

    private CreateAccountReplyPb replyError(IResult<?> result) {
        Objects.requireNonNull(result);
        LedgerServiceErrorCode errorCode = LedgerBoundaryErrorMapper.toLedgerErrorCode(result);
        return CreateAccountReplyPb.newBuilder()
                .setError(PbErrorBuilder.build(errorCode, result.getDetail()))
                .build();
    }
}
