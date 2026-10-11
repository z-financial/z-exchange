package com.exchange.app.wallet.processor.transaction;

import com.exchange.app.wallet.dao.repository.*;
import com.exchange.app.wallet.po.enums.transaction.*;
import com.exchange.app.wallet.po.transaction.WalletTransaction;
import com.exchange.app.wallet.processor.transaction.model.RequestInfo;
import com.exchange.app.wallet.processor.transaction.model.TransactionInfo;
import com.exchange.app.wallet.processor.transaction.step.BeforePostLedgerProcessor;
import com.exchange.app.wallet.processor.transaction.step.IdempPrecheckProcessor;
import com.exchange.app.wallet.result.PbErrorBuilder;
import com.exchange.app.wallet.result.WalletBoundaryErrorMapper;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.app.wallet.utils.*;
import com.exchange.common.constant.GlobalServiceId;
import com.exchange.common.result.Result;
import com.exchange.common.utils.TokenHelper;
import com.exchange.proto.wallet.wallet.AtomicTransactionReplyPb;
import com.exchange.proto.wallet.wallet.AtomicTransactionRequestPb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AtomicTransactionProcessor {
    private final WalletTransactionRepository walletTransactionManager;

    private final BeforePostLedgerProcessor beforePostLedgerProcessor;
    private final IdempPrecheckProcessor idempPrecheckProcessor;

    public AtomicTransactionReplyPb executeAtomic(AtomicTransactionRequestPb request) {
        String token = TokenHelper.generateToken(GlobalServiceId.LEDGER.name());

        RequestInfo requestInfo = new RequestInfo(request.getReferenceId(), request.getInitiator(), request.getIdempotencyKey(), request.getBusinessType(), TransactionType.ATOMIC, request.getLinesList(), AtomicTransactionRequestPb.getDescriptor().getName(), token);
        Result<String> idempResult = idempPrecheckProcessor.idempAndValidatePrecheck(requestInfo);
        if (idempResult.isFailed()) {
            return replyError(idempResult);
        } else if (!Strings.isEmpty(idempResult.getValue())) {
            return replySuccess(idempResult.getValue(), idempResult.getDetail());
        }

        Result<TransactionInfo> result = beforePostLedgerProcessor.beforePostingLedger(requestInfo, true);

        if (result.isFailed()) {
            return replyError(result);
        }
        return replySuccess(result.getValue().walletTxn, result.getDetail());
    }

    private AtomicTransactionReplyPb replySuccess(String walletTxnRef, String detail) {
        detail = Objects.requireNonNullElse(detail, "");
        return AtomicTransactionReplyPb.newBuilder()
                .setWalletTxnRef(walletTxnRef)
                .setError(PbErrorBuilder.success(detail))
                .build();
    }

    private AtomicTransactionReplyPb replySuccess(WalletTransaction txn, String detail) {
        detail = Objects.requireNonNullElse(detail, "");
        return AtomicTransactionReplyPb.newBuilder()
                .setWalletTxnRef(txn.getWalletTxnRef())
                .setStatus(EnumPbMappers.transactionStatusPbMapper.from(txn.getTxnStatus()))
                .setError(PbErrorBuilder.success(detail))
                .build();
    }

    private AtomicTransactionReplyPb replyError(WalletServiceErrorCode errorCode, String detail) {
        AtomicTransactionReplyPb.Builder builder = AtomicTransactionReplyPb.newBuilder();
        return builder.setError(PbErrorBuilder.build(errorCode, detail)).build();
    }

    private AtomicTransactionReplyPb replyError(Result<?> result) {
        return replyError(WalletBoundaryErrorMapper.toWalletErrorCode(result), result.getDetail());
    }
}
