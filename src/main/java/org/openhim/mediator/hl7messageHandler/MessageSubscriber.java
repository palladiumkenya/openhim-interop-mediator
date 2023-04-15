package org.openhim.mediator.hl7messageHandler;

public class MessageSubscriber {

    public MessageSubscriber(String serverUrl, String messageType) {
        this.messageType = messageType;
        this.serverUrl = serverUrl;
    }

    private String  messageType;

    private String serverUrl;

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }
}
