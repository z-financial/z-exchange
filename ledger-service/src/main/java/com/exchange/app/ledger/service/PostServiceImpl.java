package com.exchange.app.ledger.service;

import com.exchange.app.ledger.metrics.LedgerBusinessMetrics;
import com.exchange.app.ledger.metrics.LedgerMetricTagValues;
import com.exchange.app.ledger.processor.post.GetLedgerTxnProcessor;
import com.exchange.app.ledger.result.LedgerBoundaryErrorMapper;
import com.exchange.app.ledger.result.LedgerServiceErrorCode;
import com.exchange.app.ledger.processor.post.PostLedgerProcessor;
import com.exchange.app.ledger.result.PbErrorBuilder;
import com.exchange.app.ledger.result.PostTransactionResult;
import com.exchange.app.ledger.result.PostTransactionResultConverter;
import com.exchange.app.ledger.utils.PbConverter;
import com.exchange.common.result.Result;
import com.exchange.proto.ledger.post.*;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@GrpcService
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class PostServiceImpl extends PostServiceGrpc.PostServiceImplBase {
    final private PostLedgerProcessor postLedgerProcessor;
    private final GetLedgerTxnProcessor getLedgerTxnProcessor;
    private final LedgerBusinessMetrics ledgerBusinessMetrics;

    @Override
    public void getTxnByLedgerTxnRef(
            GetTxnByLedgerTxnRefRequestPb request,
            StreamObserver<GetTxnReplyPb> responseObserver) {
        handleTransactionRequest(
                GetLedgerTxnProcessor.LookupType.LEDGER_TXN_REF, request.getLedgerTxnRef(),
                request.getIncludeEntries(), responseObserver);
    }

    @Override
    public void getTxnBySourceTxnRef(
            GetTxnBySourceTxnRefRequestPb request,
            StreamObserver<GetTxnReplyPb> responseObserver) {
        handleTransactionRequest(
                GetLedgerTxnProcessor.LookupType.SOURCE_TXN_REF, request.getSourceTxnRef(),
                request.getIncludeEntries(), responseObserver);
    }

    private void handleTransactionRequest(GetLedgerTxnProcessor.LookupType lookupType, String lookupValue,
                                          boolean includeEntries, StreamObserver<GetTxnReplyPb> responseObserver) {
        try {
            Result<GetLedgerTxnProcessor.LedgerTxnInfo> result = ledgerBusinessMetrics.recordLedgerTxnQuery(
                    operationFor(lookupType),
                    LedgerMetricTagValues.Ingress.GRPC,
                    () -> getLedgerTxnProcessor.getLedgerTxn(lookupType, lookupValue, includeEntries)
            );
            if (result.isSuccess()) {
                GetLedgerTxnProcessor.LedgerTxnInfo info = result.getValue();
                GetTxnReplyPb.Builder replyBuilder = GetTxnReplyPb.newBuilder()
                        .setTransaction(PbConverter.convertToLedgerTransactionPb(info.txn));
                if (includeEntries) {
                    replyBuilder.addAllEntries(PbConverter.convertToLedgerEntryPbs(info.entries));
                }
                responseObserver.onNext(replyBuilder.build());
            } else {
                LedgerServiceErrorCode errorCode = LedgerBoundaryErrorMapper.toLedgerErrorCode(result);
                responseObserver.onNext(GetTxnReplyPb.newBuilder()
                        .setError(PbErrorBuilder.build(errorCode, result.getDetail()))
                        .build());
            }
        } catch (Exception e) {
            log.error("Error processing transaction request", e);
            responseObserver.onNext(GetTxnReplyPb.newBuilder()
                    .setError(PbErrorBuilder.build(LedgerServiceErrorCode.SERVER_ERROR))
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void postTransaction(PostTransactionRequestPb request, StreamObserver<PostTransactionReplyPb> responseObserver) {
        PostTransactionResult result;
        try {
            result = ledgerBusinessMetrics.recordPostTransaction(
                    LedgerMetricTagValues.Ingress.GRPC,
                    () -> postLedgerProcessor.postTransaction(request)
            );
        } catch (Exception e) {
            log.error("uncaught exception", e);
            result = PostTransactionResult.failure(LedgerServiceErrorCode.SERVER_ERROR, "");
        }
        responseObserver.onNext(PostTransactionResultConverter.toProto(result));
        responseObserver.onCompleted();
    }

    private String operationFor(GetLedgerTxnProcessor.LookupType lookupType) {
        if (lookupType == GetLedgerTxnProcessor.LookupType.LEDGER_TXN_REF) {
            return LedgerMetricTagValues.Operations.GET_LEDGER_TXN_BY_LEDGER_REF;
        }
        if (lookupType == GetLedgerTxnProcessor.LookupType.SOURCE_TXN_REF) {
            return LedgerMetricTagValues.Operations.GET_LEDGER_TXN_BY_SOURCE_REF;
        }
        return LedgerMetricTagValues.UNKNOWN;
    }
}
