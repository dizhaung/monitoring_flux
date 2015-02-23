package com.octo.monitoring_flux.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.octo.monitoring_flux.shared.MonitoringMessagesKeys;
import com.octo.monitoring_flux.shared.MonitoringMessenger;
import com.octo.monitoring_flux.shared.MonitoringUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base structure to write a backend application to process messages.
 */
public abstract class ApplicationBase {

    /**
     * Monitoring features
     */
    private final MonitoringMessenger monitoringMessenger;

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Backend.class);

    /**
     * Object reader to deserialize json messages.
     */
    private final ObjectReader mapReader = new ObjectMapper().reader(Map.class);

    /**
     * Value of the redis key to read from.
     */
    private final String redisKey;

    /**
     * Thread pool in charge of processing the message.
     */
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    protected ApplicationBase() {
        Properties applicationProperties = new Properties();
        try {
            applicationProperties.load(getClass().getResourceAsStream("/backend.properties"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        monitoringMessenger = new MonitoringMessenger(
                applicationProperties.getProperty("app.name"),
                applicationProperties.getProperty("app.name") + "." + ManagementFactory.getRuntimeMXBean().getName(),
                Integer.parseInt(applicationProperties.getProperty("zeromq.port"))
        );
        LOGGER.info("Initializing");
        Jedis jedis = new Jedis("localhost", Integer.parseInt(applicationProperties.getProperty("redis.port")));
        LOGGER.debug(jedis.ping());
        LOGGER.info("Initialized");
        redisKey = applicationProperties.getProperty("redis.key");

        while (true) {
            List<String> bundledMessage = jedis.blpop(0, redisKey);
            LOGGER.info("Received a message");
            processMessage(bundledMessage.get(1));
        }
    }

    /**
     * Process a message arrived in the queue.
     *
     * @param message the raw message content.
     */
    private void processMessage(String message) {
        LOGGER.info(message);
        Date receivedMessageTimestamp = MonitoringUtilities.getCurrentTimestamp();
        String receivedMessageTimestampAsString = MonitoringUtilities.formatDateAsRfc339(receivedMessageTimestamp);
        Map<String, Object> parsedMessage;
        try {
            parsedMessage = mapReader.readValue(message);
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
            e.printStackTrace();
            return;
        }
        Map messageHeader = (Map) parsedMessage.get("header");

        final String correlationId = (messageHeader.containsKey(MonitoringMessagesKeys.MONITORING_MESSAGE_CORRELATION_ID)) ? MonitoringUtilities.createCorrelationId() : (String) messageHeader.get(MonitoringMessagesKeys.MONITORING_MESSAGE_CORRELATION_ID);

        Map<String, ?> body = (Map<String, ?>) parsedMessage.get("body");

        monitoringMessenger.sendMonitoringMessage(
                correlationId,
                redisKey,
                "Received message",
                receivedMessageTimestampAsString,
                receivedMessageTimestampAsString,
                null,
                null,
                body,
                messageHeader,
                null,
                null
        );
        executorService.submit(() -> {
            LOGGER.info("Begin processing");
            String beginProcessingTimestampAsString = MonitoringUtilities.formatDateAsRfc339(MonitoringUtilities.getCurrentTimestamp());
            Map<String, Object> moreInfo = new HashMap<>();
            moreInfo.put("begin_processing_timestamp", beginProcessingTimestampAsString);
            monitoringMessenger.sendMonitoringMessage(
                    correlationId,
                    redisKey,
                    "Start processing",
                    beginProcessingTimestampAsString,
                    receivedMessageTimestampAsString,
                    null,
                    null,
                    body,
                    messageHeader,
                    null,
                    null
            );
            Map<String, Object> result = new HashMap<>();

            try {
                Map<?, ?> resultContent = processMessage(body);
                result.put("Status", "OK");
                result.put("Content", resultContent);
            } catch (Exception e) {
                result.put("Status", "KO");
                result.put("Content", e.getMessage());
                LOGGER.error(e.getMessage());
                e.printStackTrace();
            }

            LOGGER.info("End processing");
            Date endProcessingTimestamp = MonitoringUtilities.getCurrentTimestamp();
            String endProcessingTimestampAsString = MonitoringUtilities.formatDateAsRfc339(endProcessingTimestamp);

            monitoringMessenger.sendMonitoringMessage(
                    correlationId,
                    redisKey,
                    "End processing",
                    endProcessingTimestampAsString,
                    receivedMessageTimestampAsString,
                    endProcessingTimestampAsString,
                    ((double) (endProcessingTimestamp.getTime() - receivedMessageTimestamp.getTime())) / 1000,
                    body,
                    messageHeader,
                    result,
                    moreInfo
            );
            return null;
        });
    }

    /**
     * Implemented by application, called when a message is to be processed, must be multi-threaded.
     *
     * @param message a single message.
     * @return the processing result
     * @throws Exception when something goes wrong.
     */
    protected abstract Map<?, ?> processMessage(Map<String, ?> message) throws Exception;

}
