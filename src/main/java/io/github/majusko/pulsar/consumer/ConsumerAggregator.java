package io.github.majusko.pulsar.consumer;

import io.github.majusko.pulsar.PulsarMessage;
import io.github.majusko.pulsar.collector.ConsumerCollector;
import io.github.majusko.pulsar.collector.ConsumerHolder;
import io.github.majusko.pulsar.error.FailedMessage;
import io.github.majusko.pulsar.error.exception.ConsumerInitException;
import io.github.majusko.pulsar.properties.ConsumerProperties;
import io.github.majusko.pulsar.utils.SchemaUtils;
import io.github.majusko.pulsar.utils.TopicUrlService;
import org.apache.pulsar.client.api.*;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.util.StringValueResolver;
import reactor.core.Disposable;
import reactor.core.publisher.EmitterProcessor;

import javax.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@DependsOn({"pulsarClient", "consumerCollector"})
public class ConsumerAggregator implements EmbeddedValueResolverAware {

    private final EmitterProcessor<FailedMessage> exceptionEmitter = EmitterProcessor.create();
    private final ConsumerCollector consumerCollector;
    private final PulsarClient pulsarClient;
    private final ConsumerProperties consumerProperties;
    private final TopicUrlService topicUrlService;

    private StringValueResolver stringValueResolver;
    private List<Consumer> consumers;

    public ConsumerAggregator(ConsumerCollector consumerCollector, PulsarClient pulsarClient,
                              ConsumerProperties consumerProperties, TopicUrlService topicUrlService) {
        this.consumerCollector = consumerCollector;
        this.pulsarClient = pulsarClient;
        this.consumerProperties = consumerProperties;
        this.topicUrlService = topicUrlService;
    }

    @PostConstruct
    private void init() {
        consumers = consumerCollector.getConsumers().entrySet().stream()
            .map(holder -> subscribe(holder.getKey(), holder.getValue()))
            .collect(Collectors.toList());
    }

    private Consumer<?> subscribe(String name, ConsumerHolder holder) {
        try {
            final ConsumerBuilder<?> consumerBuilder = pulsarClient
                .newConsumer(SchemaUtils.getSchema(holder.getAnnotation().serialization(),
                    holder.getAnnotation().clazz()))
                .consumerName("consumer-" + name)
                .subscriptionName("subscription-" + name)
                .topic(topicUrlService
                    .buildTopicUrl(stringValueResolver
                        .resolveStringValue(holder.getAnnotation().topic())))
                .subscriptionType(holder.getAnnotation().subscriptionType())
                .messageListener((consumer, msg) -> {
                    try {
                        final Method method = holder.getHandler();
                        method.setAccessible(true);

                        if (holder.isWrapped()) {
                            method.invoke(holder.getBean(), wrapMessage(msg));
                        } else {
                            method.invoke(holder.getBean(), msg.getValue());
                        }

                        consumer.acknowledge(msg);
                    } catch (Exception e) {
                        consumer.negativeAcknowledge(msg);
                        exceptionEmitter.onNext(new FailedMessage(e, consumer, msg));
                    }
                });

            if (consumerProperties.getAckTimeoutMs() > 0) {
                consumerBuilder.ackTimeout(consumerProperties.getAckTimeoutMs(), TimeUnit.MILLISECONDS);
            }

            buildDeadLetterPolicy(holder, consumerBuilder);

            return consumerBuilder.subscribe();
        } catch (PulsarClientException e) {
            throw new ConsumerInitException("Failed to init consumer.", e);
        }
    }

    public void buildDeadLetterPolicy(ConsumerHolder holder, ConsumerBuilder<?> consumerBuilder) {
        DeadLetterPolicy.DeadLetterPolicyBuilder deadLetterBuilder = null;

        if (consumerProperties.getDeadLetterPolicyMaxRedeliverCount() >= 0) {
            deadLetterBuilder =
                DeadLetterPolicy.builder().maxRedeliverCount(consumerProperties.getDeadLetterPolicyMaxRedeliverCount());
        }

        if (holder.getAnnotation().maxRedeliverCount() >= 0) {
            deadLetterBuilder =
                DeadLetterPolicy.builder().maxRedeliverCount(holder.getAnnotation().maxRedeliverCount());
        }

        if (deadLetterBuilder != null && !holder.getAnnotation().deadLetterTopic().isEmpty()) {
            deadLetterBuilder.deadLetterTopic(topicUrlService.buildTopicUrl(holder.getAnnotation().deadLetterTopic()));
        }

        if (deadLetterBuilder != null) {
            consumerBuilder.deadLetterPolicy(deadLetterBuilder.build());
        }
    }

    public <T> PulsarMessage<T> wrapMessage(Message<T> message) {
        final PulsarMessage<T> pulsarMessage = new PulsarMessage<T>();

        pulsarMessage.setValue(message.getValue());
        pulsarMessage.setMessageId(message.getMessageId());
        pulsarMessage.setSequenceId(message.getSequenceId());
        pulsarMessage.setProperties(message.getProperties());
        pulsarMessage.setTopicName(message.getTopicName());
        pulsarMessage.setKey(message.getKey());
        pulsarMessage.setEventTime(message.getEventTime());
        pulsarMessage.setPublishTime(message.getPublishTime());
        pulsarMessage.setProducerName(message.getProducerName());

        return pulsarMessage;
    }

    public List<Consumer> getConsumers() {
        return consumers;
    }

    public Disposable onError(java.util.function.Consumer<? super FailedMessage> consumer) {
        return exceptionEmitter.subscribe(consumer);
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver stringValueResolver) {
        this.stringValueResolver = stringValueResolver;
    }
}
