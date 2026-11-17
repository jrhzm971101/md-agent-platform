package com.md.agentplatform.server.tool.git;

import com.md.agentplatform.server.config.EnvironmentProperties.Gitlab;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 动态 token 兜底测试：静态优先、session 换取、缓存、失效重登、OAuth Bearer 与并发首登。
 */
class GitlabTokenProviderTest {

    @Test
    void usesStaticTokenWithoutLoginRequest() {
        Gitlab gitlab = gitlab();
        gitlab.setToken("pat-1");
        Fixture fixture = fixture(gitlab);

        GitlabAccess access = fixture.provider().access("env");

        assertEquals("https://git.example.com/api/v4", access.baseUrl());
        assertEquals("pat-1", access.token());
        assertEquals(GitlabAccess.AuthScheme.PRIVATE_TOKEN, access.scheme());
        assertTrue(!access.dynamic());
        fixture.server().verify();
    }

    @Test
    void fallsBackToSessionAndCachesToken() {
        Fixture fixture = fixture(gitlab());
        fixture.server().expect(requestTo("https://git.example.com/api/v4/session"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"login\":\"dev_read\",\"password\":\"secret\"}"))
                .andRespond(withSuccess(
                        "{\"private_token\":\"session-1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        GitlabAccess first = fixture.provider().access("env");
        GitlabAccess second = fixture.provider().access("env");

        assertEquals("session-1", first.token());
        assertTrue(first.dynamic());
        assertEquals(GitlabAccess.AuthScheme.PRIVATE_TOKEN, first.scheme());
        assertEquals(first.token(), second.token());
        fixture.server().verify();
    }

    @Test
    void autoFallsBackToOAuthWhenSessionEndpointUnavailable() {
        Fixture fixture = fixture(gitlab());
        fixture.server().expect(requestTo("https://git.example.com/api/v4/session"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        fixture.server().expect(requestTo("https://git.example.com/oauth/token"))
                .andExpect(content().string(containsString("grant_type=password")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"oauth-fallback\",\"refresh_token\":\"refresh-1\",\"expires_in\":1}",
                        MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo("https://git.example.com/oauth/token"))
                .andExpect(content().string(containsString("grant_type=refresh_token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"oauth-refreshed\",\"refresh_token\":\"refresh-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        GitlabAccess first = fixture.provider().access("env");
        GitlabAccess second = fixture.provider().access("env");

        assertEquals("oauth-fallback", first.token());
        assertEquals(GitlabAccess.AuthScheme.BEARER, first.scheme());
        assertEquals("oauth-refreshed", second.token());
        fixture.server().verify();
    }

    @Test
    void invalidateForcesNewSessionLogin() {
        Fixture fixture = fixture(gitlab());
        fixture.server().expect(requestTo("https://git.example.com/api/v4/session"))
                .andRespond(withSuccess(
                        "{\"private_token\":\"session-1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo("https://git.example.com/api/v4/session"))
                .andRespond(withSuccess(
                        "{\"private_token\":\"session-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertEquals("session-1", fixture.provider().access("env").token());
        fixture.provider().invalidate("env");
        assertEquals("session-2", fixture.provider().access("env").token());
        fixture.server().verify();
    }

    @Test
    void usesOAuthBearerAndRefreshesExpiredToken() {
        Gitlab gitlab = gitlab();
        gitlab.setAuthMode("oauth");
        Fixture fixture = fixture(gitlab);
        fixture.server().expect(requestTo("https://git.example.com/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("grant_type=password")))
                .andExpect(content().string(containsString("username=dev_read")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"old-token\",\"refresh_token\":\"refresh-1\",\"expires_in\":1}",
                        MediaType.APPLICATION_JSON));
        fixture.server().expect(requestTo("https://git.example.com/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("grant_type=refresh_token")))
                .andExpect(content().string(containsString("refresh_token=refresh-1")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"new-token\",\"refresh_token\":\"refresh-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        GitlabAccess first = fixture.provider().access("env");
        GitlabAccess second = fixture.provider().access("env");

        assertEquals("old-token", first.token());
        assertEquals(GitlabAccess.AuthScheme.BEARER, first.scheme());
        assertEquals("new-token", second.token());
        fixture.server().verify();
    }

    @Test
    void concurrentFirstAccessOnlyLogsInOnce() throws Exception {
        Fixture fixture = fixture(gitlab());
        fixture.server().expect(requestTo("https://git.example.com/api/v4/session"))
                .andRespond(withSuccess(
                        "{\"private_token\":\"session-1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return fixture.provider().access("env").token();
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertEquals("session-1", result.get());
            }
        } finally {
            pool.shutdownNow();
        }
        fixture.server().verify();
    }

    private static Gitlab gitlab() {
        Gitlab gitlab = new Gitlab();
        gitlab.setUrl("https://git.example.com");
        gitlab.setUsername("dev_read");
        gitlab.setPassword("secret");
        return gitlab;
    }

    private static Fixture fixture(Gitlab gitlab) {
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        when(registry.gitlab("env")).thenReturn(gitlab);
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        return new Fixture(new GitlabTokenProvider(registry, restTemplate), server);
    }

    private record Fixture(GitlabTokenProvider provider, MockRestServiceServer server) {
    }
}
