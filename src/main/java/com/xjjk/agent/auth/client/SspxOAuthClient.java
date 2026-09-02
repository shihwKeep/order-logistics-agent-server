package com.xjjk.agent.auth.client;

import com.xjjk.agent.auth.client.dto.SspxTokenResponse;

public interface SspxOAuthClient {

    SspxTokenResponse passwordGrant(String username, String password);

    SspxTokenResponse refreshGrant(String refreshToken);
}
