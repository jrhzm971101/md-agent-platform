package com.md.agentplatform.server.tool.git;

import com.md.agentplatform.server.config.EnvironmentProperties.Gitlab;
import com.md.agentplatform.server.config.EnvironmentRegistry;
import com.md.agentplatform.server.config.GitlabProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GitLab 连接信息解析测试：地址、token 必须来自 environment，而不是全局固定配置。
 */
class AbstractGitlabToolTest {

    @Test
    void appendsApiV4WhenEnvironmentOnlyProvidesSiteUrl() throws Exception {
        AbstractGitlabTool tool = tool("cameroon-uat",
                "https://git.huojintech.com", null, "token-1");

        GitlabAccess access = tool.gitlabAccess("cameroon-uat");

        assertEquals("https://git.huojintech.com/api/v4", access.baseUrl());
        assertEquals("token-1", access.token());
    }

    @Test
    void keepsFullApiUrlAndTrimsTrailingSlash() throws Exception {
        AbstractGitlabTool tool = tool("cmr",
                "https://cmr-gitlab.mindigitaltech.com", "https://cmr-gitlab.mindigitaltech.com/api/v4/", "token-2");

        GitlabAccess access = tool.gitlabAccess("cmr");

        assertEquals("https://cmr-gitlab.mindigitaltech.com/api/v4", access.baseUrl());
    }

    @Test
    void rejectsEnvironmentWithoutToken() throws Exception {
        AbstractGitlabTool tool = tool("bad", "https://git.example.com", null, "");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> tool.gitlabAccess("bad"));

        assertTrue(error.getMessage().contains("gitlab.token"), error.getMessage());
    }

    @Test
    void checkConfigRequiresEnvironmentAndReportsMissingGroup() throws Exception {
        AbstractGitlabTool tool = tool("good", "https://git.example.com", null, "token");

        assertTrue(tool.checkConfig("git-log", "").contains("environment 必填"));
        assertTrue(tool.checkConfig("git-log", "missing").contains("gitlab 分组"));
        assertEquals(null, tool.checkConfig("git-log", "good"));
    }

    private static AbstractGitlabTool tool(String environment, String url, String apiUrl,
                                           String token) throws Exception {
        AbstractGitlabTool tool = new AbstractGitlabTool() {
        };
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        Gitlab gitlab = new Gitlab();
        gitlab.setUrl(url);
        gitlab.setApiUrl(apiUrl);
        gitlab.setToken(token);
        when(registry.gitlab(environment)).thenReturn(gitlab);
        when(registry.gitlab("missing")).thenThrow(new IllegalArgumentException("环境不存在: missing"));
        Field field = AbstractGitlabTool.class.getDeclaredField("gitlabTokenProvider");
        field.setAccessible(true);
        field.set(tool, new GitlabTokenProvider(registry, new GitlabProperties()));
        return tool;
    }
}
