package com.exchange.app.wallet.service;

import com.exchange.app.wallet.po.enums.ServiceId;
import com.exchange.app.wallet.metrics.WalletBusinessMetrics;
import com.exchange.app.wallet.metrics.WalletMetricTagValues;
import com.exchange.app.wallet.processor.transaction.ApplyReservationTransactionProcessor;
import com.exchange.app.wallet.processor.transaction.AtomicTransactionProcessor;
import com.exchange.app.wallet.processor.CreateWalletProcessor;
import com.exchange.app.wallet.processor.balance.GetBalanceSnapshotProcessor;
import com.exchange.app.wallet.processor.transaction.ReserveTransactionProcessor;
import com.exchange.app.wallet.result.WalletBoundaryErrorMapper;
import com.exchange.app.wallet.result.PbErrorBuilder;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.app.wallet.utils.EnumPbMappers;
import com.exchange.app.wallet.utils.PbConverter;
import com.exchange.common.result.Result;
import com.exchange.proto.wallet.wallet.*;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@GrpcService
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class WalletServiceImpl extends WalletServiceGrpc.WalletServiceImplBase {
    final private CreateWalletProcessor createWalletProcessor;
    final private AtomicTransactionProcessor atomicTransactionProcessor;
    final private ReserveTransactionProcessor reserveTransactionProcessor;
    private final ApplyReservationTransactionProcessor applyReservationTransactionProcessor;
    private final GetBalanceSnapshotProcessor getBalanceSnapshotProcessor;
    private final WalletBusinessMetrics walletBusinessMetrics;

    @Override
    public void createWallet(CreateWalletRequestPb request, StreamObserver<CreateWalletReplyPb> responseObserver) {
        CreateWalletReplyPb reply;
        try {
            reply = walletBusinessMetrics.recordProtobufRequest(
                    WalletMetricTagValues.Operations.CREATE_WALLET,
                    WalletMetricTagValues.Ingress.GRPC,
                    () -> createWalletProcessor.createWallet(request),
                    CreateWalletReplyPb::getError
            );
        } catch (Exception e) {
            log.error("uncaught exception", e);
            reply = CreateWalletReplyPb.newBuilder().setError(PbErrorBuilder.build(WalletServiceErrorCode.SERVER_ERROR)).build();
        }
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    // A request may contain at most one transaction line for each Wallet reference.
    @Override
    public void atomicTransaction(AtomicTransactionRequestPb request, StreamObserver<AtomicTransactionReplyPb> responseObserver) {
        AtomicTransactionReplyPb reply;
        try {
            reply = walletBusinessMetrics.recordProtobufRequest(
                    WalletMetricTagValues.Operations.ATOMIC_TRANSACTION,
                    WalletMetricTagValues.Ingress.GRPC,
                    () -> atomicTransactionProcessor.executeAtomic(request),
                    AtomicTransactionReplyPb::getError
            );
        } catch (Exception e) {
            log.error("uncaught exception", e);
            reply = AtomicTransactionReplyPb.newBuilder().setError(PbErrorBuilder.build(WalletServiceErrorCode.SERVER_ERROR)).build();
        }
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    @Override
    public void reserveTransaction(ReserveTransactionRequestPb request, StreamObserver<ReserveTransactionReplyPb> responseObserver) {
        ReserveTransactionReplyPb reply;
        try {
            reply = walletBusinessMetrics.recordProtobufRequest(
                    WalletMetricTagValues.Operations.RESERVE_TRANSACTION,
                    WalletMetricTagValues.Ingress.GRPC,
                    () -> reserveTransactionProcessor.reserve(request),
                    ReserveTransactionReplyPb::getError
            );
        } catch (Exception e) {
            log.error("uncaught exception", e);
            reply = ReserveTransactionReplyPb.newBuilder().setError(PbErrorBuilder.build(WalletServiceErrorCode.SERVER_ERROR)).build();
        }
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    @Override
    public void applyReserveTransaction(ApplyReservationTransactionRequestPb request, StreamObserver<ApplyReservationTransactionReplyPb> responseObserver) {
        ApplyReservationTransactionReplyPb reply;
        try {
            reply = walletBusinessMetrics.recordProtobufRequest(
                    WalletMetricTagValues.Operations.APPLY_RESERVATION_TRANSACTION,
                    WalletMetricTagValues.Ingress.GRPC,
                    () -> applyReservationTransactionProcessor.apply(request),
                    ApplyReservationTransactionReplyPb::getError
            );
        } catch (Exception e) {
            log.error("uncaught exception", e);
            reply = ApplyReservationTransactionReplyPb.newBuilder().setError(PbErrorBuilder.build(WalletServiceErrorCode.SERVER_ERROR)).build();
        }
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }

    @Override
    public void getSnapshotByWalletRef(GetSnapshotByWalletRefRequestPb request, StreamObserver<GetSnapshotReplyPb> responseObserver) {
        handleSnapshotRequest(GetBalanceSnapshotProcessor.LookupType.WALLET_REF, null,
                request.getWalletRef(), responseObserver);
    }

    @Override
    public void getSnapshotByBusinessRef(GetSnapshotByBusinessRefRequestPb request, StreamObserver<GetSnapshotReplyPb> responseObserver) {
        ServiceId serviceId = EnumPbMappers.serviceIdPbMapper.to(request.getServiceId());
        handleSnapshotRequest(GetBalanceSnapshotProcessor.LookupType.BUSINESS_REF, serviceId,
                request.getWalletBusinessRef(), responseObserver);
    }

    private void handleSnapshotRequest(GetBalanceSnapshotProcessor.LookupType lookupType,
                                       ServiceId serviceId,
                                       String lookupValue,
                                       StreamObserver<GetSnapshotReplyPb> responseObserver) {
        try {
            Result<com.exchange.app.wallet.po.wallet.BalanceSnapshot> result = walletBusinessMetrics.recordResult(
                    operationFor(lookupType),
                    WalletMetricTagValues.Ingress.GRPC,
                    () -> getBalanceSnapshotProcessor.getBalanceSnapshot(lookupType, serviceId, lookupValue)
            );
            if (result.isSuccess()) {
                responseObserver.onNext(GetSnapshotReplyPb.newBuilder()
                        .setTransaction(PbConverter.convertToBalanceSnapshotPb(result.getValue()))
                        .build());
            } else {
                WalletServiceErrorCode errorCode = WalletBoundaryErrorMapper.toWalletErrorCode(result);
                responseObserver.onNext(GetSnapshotReplyPb.newBuilder()
                        .setError(PbErrorBuilder.build(errorCode, result.getDetail()))
                        .build());
            }
        } catch (Exception e) {
            log.error("error processing balance snapshot request", e);
            responseObserver.onNext(GetSnapshotReplyPb.newBuilder()
                    .setError(PbErrorBuilder.build(WalletServiceErrorCode.SERVER_ERROR))
                    .build());
        }
        responseObserver.onCompleted();
    }

    private String operationFor(GetBalanceSnapshotProcessor.LookupType lookupType) {
        return lookupType == GetBalanceSnapshotProcessor.LookupType.WALLET_REF
                ? WalletMetricTagValues.Operations.GET_SNAPSHOT_BY_WALLET_REF
                : WalletMetricTagValues.Operations.GET_SNAPSHOT_BY_BUSINESS_REF;
    }
}
