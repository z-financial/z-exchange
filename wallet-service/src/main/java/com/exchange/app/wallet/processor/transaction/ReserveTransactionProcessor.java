package com.exchange.app.wallet.processor.transaction;

import com.exchange.app.wallet.dao.repository.WalletReservationRepository;
import com.exchange.app.wallet.po.enums.transaction.TransactionType;
import com.exchange.app.wallet.po.transaction.WalletReservation;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.app.wallet.processor.transaction.model.RequestInfo;
import com.exchange.app.wallet.processor.transaction.model.TransactionInfo;
import com.exchange.app.wallet.processor.transaction.step.BeforePostLedgerProcessor;
import com.exchange.app.wallet.processor.transaction.step.IdempPrecheckProcessor;
import com.exchange.app.wallet.result.PbErrorBuilder;
import com.exchange.app.wallet.result.WalletBoundaryErrorMapper;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.app.wallet.utils.EnumPbMappers;
import com.exchange.common.constant.GlobalServiceId;
import com.exchange.common.result.Result;
import com.exchange.common.utils.TokenHelper;
import com.exchange.proto.wallet.common.OperationTypePb;
import com.exchange.proto.wallet.wallet.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ReserveTransactionProcessor {
    final private BeforePostLedgerProcessor beforePostLedgerProcessor;
    private final IdempPrecheckProcessor idempPrecheckProcessor;
    private final WalletReservationRepository walletReservationRepository;

    public ReserveTransactionReplyPb reserve(ReserveTransactionRequestPb request) {
        Result<Void> validateResult = validate(request);
        if (validateResult.isFailed()) {
            return replyError(validateResult);
        }

        String token = TokenHelper.generateToken(GlobalServiceId.LEDGER.name());
        RequestInfo requestInfo = new RequestInfo(
                request.getWalletTxnBusinessRef(), request.getInitiator(), request.getBusinessType(),
                TransactionType.TWO_STEP, request.getLinesList(),
                ReserveTransactionRequestPb.getDescriptor().getName(), token);
        Result<String> idempResult = idempPrecheckProcessor.idempAndValidatePrecheck(requestInfo);
        if (idempResult.isFailed()) {
            return replyError(idempResult);
        } else if (!Strings.isEmpty(idempResult.getValue())) {
            return replySuccess(idempResult.getValue(), idempResult.getDetail());
        }

        Result<TransactionInfo> result = beforePostLedgerProcessor.beforePostingLedger(requestInfo, false);
        if (result.isFailed()) {
            return replyError(result);
        }

        return replySuccess(result.getValue(), result.getDetail());
    }

    private Result<Void> validate(ReserveTransactionRequestPb request) {
        for (TransactionLinePb line : request.getLinesList()) {
            if (line.getOperationType() != OperationTypePb.OperationTypePb_Reserve) {
                return Result.failure(WalletServiceErrorCode.INVALID_REQUEST_PARAMETER, "invalid operation type: " + line.getOperationType());
            }
        }
        return Result.success();
    }

    private ReserveTransactionReplyPb replySuccess(String walletTxnRef, String detail) {
        List<WalletReservation> reservations = walletReservationRepository.selectByWalletTxnRef(walletTxnRef);
        List<ReservationInfoPb> infos = new ArrayList<>();
        for (WalletReservation reservation : reservations) {
            ReservationInfoPb infoPb = ReservationInfoPb.newBuilder()
                    .setReservationRef(reservation.getWalletReservationRef())
                    .setWalletRef(reservation.getWalletRef())
                    .setAssetCode(reservation.getAssetId())
                    .setAmount(reservation.getRemaining()).build();
            infos.add(infoPb);
        }

        detail = Objects.requireNonNullElse(detail, "");
        return ReserveTransactionReplyPb.newBuilder()
                .setWalletTxnRef(walletTxnRef)
                .addAllInfos(infos)
                .setError(PbErrorBuilder.success(detail))
                .build();
    }

    private ReserveTransactionReplyPb replySuccess(TransactionInfo info, String detail) {
        detail = Objects.requireNonNullElse(detail, "");
        WalletTransaction txn = info.walletTxn;
        ReserveTransactionReplyPb.Builder builder = ReserveTransactionReplyPb.newBuilder()
                .setWalletTxnRef(txn.getWalletTxnRef())
                .setStatus(EnumPbMappers.transactionStatusPbMapper.from(txn.getTxnStatus()))
                .setError(PbErrorBuilder.success(detail));
        List<ReservationInfoPb> infos = new ArrayList<>();
        for (WalletReservation reservation : info.reservations) {
            ReservationInfoPb infoPb = ReservationInfoPb.newBuilder()
                    .setReservationRef(reservation.getWalletReservationRef())
                    .setWalletRef(reservation.getWalletRef())
                    .setAssetCode(reservation.getAssetId())
                    .setAmount(reservation.getRemaining()).build();
            infos.add(infoPb);
        }
        return builder.addAllInfos(infos).build();
    }

    private ReserveTransactionReplyPb replyError(WalletServiceErrorCode errorCode, String detail) {
        ReserveTransactionReplyPb.Builder builder = ReserveTransactionReplyPb.newBuilder();
        return builder.setError(PbErrorBuilder.build(errorCode, detail)).build();
    }

    private ReserveTransactionReplyPb replyError(Result<?> result) {
        return replyError(WalletBoundaryErrorMapper.toWalletErrorCode(result), result.getDetail());
    }

//    @AllArgsConstructor
//    static private class ReserveResult {
//        final WalletTransaction walletTransaction;
//        final List<WalletReservation> reservations;
//    }
}
