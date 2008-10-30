/*
 * $Id$
 */

package org.opendds.jms;

import java.util.HashMap;
import java.util.Map;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;

import DDS.DATAWRITER_QOS_DEFAULT;
import DDS.DURATION_INFINITY_NSEC;
import DDS.DURATION_INFINITY_SEC;
import DDS.DataWriterQos;
import DDS.DataWriterQosHolder;
import DDS.DomainParticipant;
import DDS.HANDLE_NIL;
import DDS.Publisher;
import OpenDDS.JMS.MessagePayload;
import OpenDDS.JMS.MessagePayloadDataWriter;

import org.opendds.jms.util.Objects;

/**
 * @author  Steven Stallion
 * @version $Revision$
 */
public class TopicMessageProducerImpl implements MessageProducer {
    private Destination destination;
    private boolean disableMessageID;
    private boolean disableMessageTimestamp;
    private int deliveryMode;
    private int priority;
    private long timeToLive;

    // DDS related stuff
    private Publisher publisher;
    private DomainParticipant participant;
    private final DataWriterPair dataWriterPair; // For identified (constructed with a non-null Destination) MessageProducer
    private final Map<Destination, DataWriterPair> dataWriterPairMap;

    private boolean closed;

    public TopicMessageProducerImpl(Destination destination, Publisher publisher, DomainParticipant participant) {
        Objects.ensureNotNull(publisher);
        Objects.ensureNotNull(participant);

        this.destination = destination;
        this.publisher = publisher;
        this.participant = participant;

        initProducer();
        if (destination != null) {
            this.dataWriterPair = DataWriterPair.fromDestination(destination, publisher, participant);
            this.dataWriterPairMap = null;
        } else {
            this.dataWriterPair = null;
            this.dataWriterPairMap = new HashMap<Destination, DataWriterPair>();
        }
    }

    private void initProducer() {
        this.disableMessageID = false;
        this.disableMessageTimestamp = false;
        this.deliveryMode = DeliveryMode.PERSISTENT;
        this.priority = 4;
        this.timeToLive = 0;
        this.closed = false;
    }

    public Destination getDestination() throws JMSException {
        return destination;
    }

    public boolean getDisableMessageID() throws JMSException {
        return disableMessageID;
    }

    public void setDisableMessageID(boolean disableMessageID) throws JMSException {
        // No-op, may be enabled in a future optimazation
    }

    public boolean getDisableMessageTimestamp() throws JMSException {
        return disableMessageTimestamp;
    }

    public void setDisableMessageTimestamp(boolean disableMessageTimestamp) throws JMSException {
        // No-op, may be enabled in a future optimization
    }

    public int getDeliveryMode() throws JMSException {
        return deliveryMode;
    }

    public void setDeliveryMode(int deliveryMode) {
        validateDeliveryMode(deliveryMode);
        this.deliveryMode = deliveryMode;
    }

    private void validateDeliveryMode(int deliveryMode) {
        if (deliveryMode != DeliveryMode.PERSISTENT && deliveryMode != DeliveryMode.NON_PERSISTENT) {
            throw new IllegalArgumentException("Illegal deliveryMode: " + deliveryMode + ".");
        }
    }

    public int getPriority() throws JMSException {
        return priority;
    }

    public void setPriority(int priority) {
        validatePriority(priority);
        this.priority = priority;
    }

    private void validatePriority(int priority) {
        if (priority < 0 || priority > 9) {
            throw new IllegalArgumentException("Illegal priority: " + priority + ".");
        }
    }

    public long getTimeToLive() throws JMSException {
        return timeToLive;
    }

    public void setTimeToLive(long timeToLive) {
        validateTimeTiLive(timeToLive);
        this.timeToLive = timeToLive;
    }

    private void validateTimeTiLive(long timeToLive) {
        if (timeToLive < 0) {
            throw new IllegalArgumentException("Illegal timeToLive: " + timeToLive + ".");
        }
    }

    public void send(Message message) throws JMSException {
        send(message, deliveryMode, priority, timeToLive);
    }

    public void send(Message message,
                     int deliveryMode,
                     int priority,
                     long timeToLive) throws JMSException {
        if (dataWriterPair == null) {
            throw new UnsupportedOperationException("This MessageProducer is created without a Destination.");
        }
        if (closed) {
            throw new JMSException("This MessageProducer is closed.");
        }
        final MessagePayloadDataWriter dataWriter = dataWriterPair.getDataWriter(deliveryMode);

        validateMessage(message);
        validateDeliveryMode(deliveryMode);
        validatePriority(priority);
        validateTimeTiLive(timeToLive);
        populateMessageHeader(destination, message, deliveryMode, priority, timeToLive);
        manipulateDataWriterQoS(dataWriter, timeToLive);
        writeDataWithDataWriter(message, dataWriter);
    }

    public void send(Destination destination, Message message) throws JMSException {
        send(destination, message, deliveryMode, priority, timeToLive);
    }

    public void send(Destination destination,
                     Message message,
                     int deliveryMode,
                     int priority,
                     long timeToLive) throws JMSException {

        if (dataWriterPair != null) {
            throw new UnsupportedOperationException("This MessageProducer is created with a Destination.");
        }
        if (closed) {
            throw new JMSException("This MessageProducer is closed.");
        }
        DataWriterPair dataWriterPair = getOrCreateDataWriterPair(destination);
        final MessagePayloadDataWriter dataWriter = dataWriterPair.getDataWriter(deliveryMode);

        validateMessage(message);
        validateDeliveryMode(deliveryMode);
        validatePriority(priority);
        validateTimeTiLive(timeToLive);
        populateMessageHeader(destination, message, deliveryMode, priority, timeToLive);
        manipulateDataWriterQoS(dataWriter, timeToLive);
        writeDataWithDataWriter(message, dataWriter);

        dataWriterPair.destroy();
    }

    private DataWriterPair getOrCreateDataWriterPair(Destination destination) {
        DataWriterPair dataWriterPair = dataWriterPairMap.get(destination);
        if (dataWriterPair == null) {
            dataWriterPair = DataWriterPair.fromDestination(destination, publisher, participant);
            dataWriterPairMap.put(destination, dataWriterPair);
        }
        return dataWriterPair;
    }

    private void validateMessage(Message message) {
        // TODO place holder, to be elaborated
        Objects.ensureNotNull(message);
    }

    private void populateMessageHeader(Destination destination, Message message, int deliveryMode, int priority, long timeToLive) throws JMSException {
        final String id = MessageID.createMessageID().toString();
        final long timestamp = System.currentTimeMillis();
        final long expiration = (timeToLive == 0L) ? 0L : (timestamp + timeToLive);

        message.setJMSMessageID(id);
        message.setJMSDestination(destination);
        message.setJMSDeliveryMode(deliveryMode);
        message.setJMSPriority(priority);
        message.setJMSTimestamp(timestamp);
        message.setJMSExpiration(expiration);
    }

    private void manipulateDataWriterQoS(MessagePayloadDataWriter dataWriter, long timeToLive) {
        DataWriterQosHolder qosHolder = new DataWriterQosHolder(DATAWRITER_QOS_DEFAULT.get());
        dataWriter.get_qos(qosHolder);
        final DataWriterQos qos = qosHolder.value;
        if (timeToLive == 0L) {
            qos.lifespan.duration.sec = DURATION_INFINITY_SEC.value;
            qos.lifespan.duration.nanosec = DURATION_INFINITY_NSEC.value;
        } else {
            qos.lifespan.duration.sec = (int) timeToLive/1000;
            qos.lifespan.duration.nanosec = ((int) (timeToLive % 1000)) * 1000000;
        }
        dataWriter.set_qos(qos);
    }

    private void writeDataWithDataWriter(Message message, MessagePayloadDataWriter dataWriter) {
        AbstractMessageImpl messageImpl = (AbstractMessageImpl) message;
        final MessagePayload payload = messageImpl.getPayload();
        dataWriter.write(payload, HANDLE_NIL.value);
    }


    public void close() throws JMSException {
        if (closed) return;
        if (dataWriterPair != null) {
            dataWriterPair.destroy();
        } else {
            for (DataWriterPair pair : dataWriterPairMap.values()) {
                pair.destroy();
            }
        }
        closed = true;
    }
}
