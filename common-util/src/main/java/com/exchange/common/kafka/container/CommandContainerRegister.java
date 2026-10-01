package com.exchange.common.kafka.container;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class CommandContainerRegister {
    public static final String OUTBOX_PUBLISH_CALLBACK_EXECUTOR = "outboxPublishCallbackExecutor";

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> concurrentCommandKafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory,
            DefaultErrorHandler errorHandler
    ) {
        var factory = createCommandFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> concurrentRetryTopicCommandKafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory
    ) {
        return createCommandFactory(consumerFactory);
    }

    private ConcurrentKafkaListenerContainerFactory<String, byte[]> createCommandFactory(
            ConsumerFactory<String, byte[]> consumerFactory
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        ContainerProperties containerProperties = factory.getContainerProperties();
        /*
         * MANUAL deliberately amortizes offset commits across the records returned by a poll.
         * Durable processing and idempotency make poll-batch replay safe; MANUAL_IMMEDIATE
         * would reduce that replay window at the cost of potentially committing every record.
         * See design/infrastructure/kafka-outbox-reliability.md#manual-versus-manual-immediate.
         */
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL);
        containerProperties.setDeliveryAttemptHeader(true);
        return factory;
    }

    @Bean(name = OUTBOX_PUBLISH_CALLBACK_EXECUTOR)
    public ThreadPoolTaskExecutor outboxPublishCallbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(512);
        executor.setThreadNamePrefix("outbox-publish-callback-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
