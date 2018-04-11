package com.testingbot.tunnel.proxy;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

public class ProxyAuth extends Authenticator {
    private final PasswordAuthentication auth;

    public ProxyAuth(String user, String password) {
        auth = new PasswordAuthentication(user, password == null ? new char[]{} : password.toCharArray());
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return auth;
    }
}