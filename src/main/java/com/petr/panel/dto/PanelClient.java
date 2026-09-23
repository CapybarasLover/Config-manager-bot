package com.petr.panel.dto;

public class PanelClient {
    private final long tgId;
    private final String configName;
    private final String vlessLink;
    private final String subLink;
    private final String xhttpLink;
    private final String realityLink;

    public PanelClient(long tgId, String configName, String vlessLink, String subLink, String xhttpLink, String realityLink) {
        this.tgId = tgId;
        this.configName = configName;
        this.vlessLink = vlessLink;
        this.subLink = subLink;
        this.xhttpLink = xhttpLink;
        this.realityLink = realityLink;
    }

    public long getTgId() {
        return tgId;
    }

    public String getConfigName() {
        return configName;
    }

    public String getVlessLink() {
        return vlessLink;
    }

    public String getSubLink() {
        return subLink;
    }

    public String getXhttpLink() {
        return xhttpLink;
    }

    public String getRealityLink() {
        return realityLink;
    }
}