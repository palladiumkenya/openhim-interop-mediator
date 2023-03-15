package org.openhim.mediator.emrInterop.util;

import java.io.Serializable;
import java.time.LocalDateTime;

public class OAuthToken implements Serializable {
    private String accessToken;

    private String expiresIn;

    private String tokenType;

    private String scope;

    private LocalDateTime lastTimeGenerated;

    public OAuthToken() {
    }

    public void setLastTimeGenerated(LocalDateTime lastTimeGenerated) {
        this.lastTimeGenerated = lastTimeGenerated;
    }

    public LocalDateTime getLastTimeGenerated() {
        return lastTimeGenerated;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(String expiresIn) {
        this.expiresIn = expiresIn;
    }
    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    @Override
    public String toString() {
        return "{" +
                "accessToken='" + accessToken + '\'' +
                ", expiresIn='" + expiresIn + '\'' +
                ", tokenType='" + tokenType + '\'' +
                ", scope='" + scope + '\'' +
                ", lastTimeGenerated=" + lastTimeGenerated +
                '}';
    }
}
