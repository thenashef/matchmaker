package com.matchmaker.common.net;

public final class LoopbackHosts {

    private LoopbackHosts() {
    }

    public static void pinToLoopback() {
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        System.setProperty("activemq.idgenerator.hostname", "127.0.0.1");
    }
}
