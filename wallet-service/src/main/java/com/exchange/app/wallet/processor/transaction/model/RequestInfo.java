package com.exchange.app.wallet.processor.transaction.model;

import com.exchange.app.wallet.po.enums.transaction.TransactionType;
import com.exchange.common.utils.StableHashHelper;
import com.exchange.proto.wallet.common.BusinessTypePb;
import com.exchange.proto.wallet.common.ServiceIdPb;
import com.exchange.proto.wallet.wallet.TransactionLinePb;
import org.apache.logging.log4j.util.Strings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RequestInfo {
    public final String upstreamRequestRef;
    public final ServiceIdPb serviceIdPb;
    public final BusinessTypePb businessTypePb;
    public final TransactionType transactionType;
    public final List<TransactionLinePb> linePbs;
    public final String requestName;

    // token is uuid (which is random) and not included in stable hash computation
    public final String token;

    private String stableHash;

    public RequestInfo(String upstreamRequestRef, ServiceIdPb serviceIdPb,
                       BusinessTypePb businessTypePb, TransactionType transactionType,
                       List<TransactionLinePb> linePbs, String requestName, String token) {
        this.upstreamRequestRef = upstreamRequestRef;
        this.serviceIdPb = serviceIdPb;
        this.businessTypePb = businessTypePb;
        this.transactionType = transactionType;
        this.linePbs = linePbs;
        this.requestName = requestName;
        this.token = token;
    }

    public String getStableHash() {
        if (!Strings.isEmpty(stableHash)) {
            return stableHash;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(upstreamRequestRef).append("|")
                .append(serviceIdPb).append("|")
                .append(businessTypePb).append("|")
                .append(transactionType).append("|")
                .append(requestName);
        List<TransactionLinePb> sorted = new ArrayList<>(linePbs);
        // sort lines then append together. the same line list in random order will have the same hashcode
        sorted.sort(Comparator.comparing(TransactionLinePb::getWalletRef)
                .thenComparing(TransactionLinePb::getAssetCode)
                .thenComparingInt(TransactionLinePb::getOperationTypeValue)
                .thenComparingLong(TransactionLinePb::getAmount)
                .thenComparing(TransactionLinePb::getReservationRef));
        for (TransactionLinePb linePb : sorted) {
            sb.append(linePb.getWalletRef()).append("|")
                    .append(linePb.getAssetCode()).append("|")
                    .append(linePb.getOperationTypeValue()).append("|")
                    .append(linePb.getAmount()).append("|")
                    .append(linePb.getReservationRef());
        }
        stableHash = StableHashHelper.stableHash(sb.toString());
        return stableHash;
    }

    /** Redis key for the same identity enforced by the database unique constraint. */
    public String idempotencyCacheKey() {
        return serviceIdPb.getNumber() + ":" + upstreamRequestRef;
    }
}
