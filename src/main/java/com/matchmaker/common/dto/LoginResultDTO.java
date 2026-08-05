package com.matchmaker.common.dto;

import java.io.Serializable;

public class LoginResultDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private final UserDTO user;
    private final String sessionToken;

    public LoginResultDTO(UserDTO user, String sessionToken) {
        this.user = user;
        this.sessionToken = sessionToken;
    }

    public UserDTO getUser() { return user; }
    public String getSessionToken() { return sessionToken; }
}
