package com.exchange.app.wallet.kafka.config;

import com.exchange.app.wallet.kafka.consumer.WalletLedgerReplyMessageHandler;
import com.exchange.app.wallet.result.WalletServiceErrorCode;
import com.exchange.common.kafka.listener.exception.DlqException;
import com.exchange.common.kafka.listener.failure.ListenerFailure;
import com.exchange.common.kafka.listener.handler.MessageHandler;
import com.exchange.proto.common.event.EventEnvelopePb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WalletListenerConfigTest {
    private final WalletListenerConfig config = new WalletListenerConfig();

    @Test
    void ackOnlyFailurePolicyRoutesFailuresToDlt() {
        // AckOnly must not use its default failure behavior, which would acknowledge lost work.
        MessageHandler handler = config.walletAckOnlyMessageHandler(mock(WalletLedgerReplyMessageHandler.class));
        ListenerFailure failure = ListenerFailure.nonRetryable(
                WalletServiceErrorCode.WALLET_TRANSACTION_UPDATE_FAILED,
                "state conflict"
        );

        DlqException thrown = assertThrows(
                DlqException.class,
                () -> handler.handleFailure(envelope(), failure)
        );

        assertEquals(WalletServiceErrorCode.WALLET_TRANSACTION_UPDATE_FAILED, thrown.getErrorCode());
    }

    @Test
    void retryAttemptsMustBePositive() {
        WalletListenerRetryProperties properties = new WalletListenerRetryProperties();
        properties.setAttempts(0);

        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    private EventEnvelopePb envelope() {
        return EventEnvelopePb.newBuilder()
                .setEventRef("reply-event-1")
                .setCommandRef("wallet-txn-1")
                .setEventType("POST_LEDGER_REPLY")
                .build();
    }
}
