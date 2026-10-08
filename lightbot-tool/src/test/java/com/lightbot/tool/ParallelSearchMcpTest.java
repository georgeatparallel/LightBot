package com.lightbot.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightbot.entity.McpServer;
import com.lightbot.enums.CommonStatus;
import com.lightbot.enums.McpTransportType;
import com.lightbot.event.CacheInvalidationBroadcaster;
import com.lightbot.service.McpServerService;
import com.lightbot.service.impl.McpClientServiceImpl;
import com.lightbot.tool.registrar.BuiltinMcpRegistrar;
import com.lightbot.util.RedisUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 从注册记录到实际 MCP 传输及工具回调的回归测试，不依赖数据库或模型密钥。 */
class ParallelSearchMcpTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 从真实注册器取得配置，同时校验匿名默认值。
     * @return 已注册、尚未启用的 Parallel 记录
     * @throws Exception 请求头 JSON 无法解析时抛出
     */
    private McpServer registeredParallel() throws Exception {
        McpServerService store = mock(McpServerService.class);
        new BuiltinMcpRegistrar(store, mapper).registerBuiltInMcpServers();
        ArgumentCaptor<McpServer> saved = ArgumentCaptor.forClass(McpServer.class);
        verify(store, times(3)).save(saved.capture());
        List<McpServer> servers = saved.getAllValues();
        assertTrue(servers.stream().allMatch(s -> s.getStatus() == CommonStatus.DISABLED));
        assertNull(servers.get(0).getHeaders());
        assertEquals("https://remote.mcpservers.org/sequentialthinking/mcp", servers.get(0).getHost());
        assertNull(servers.get(1).getHeaders());
        assertEquals("@antv/mcp-server-chart", mapper.readTree(servers.get(1).getDeployConfig()).get("packageName").asText());
        McpServer parallel = servers.stream().filter(s -> "parallel-search".equals(s.getName())).findFirst().orElseThrow();
        assertEquals("https://search.parallel.ai/mcp", parallel.getHost());
        assertEquals(McpTransportType.STREAMABLE_HTTP, parallel.getTransport());
        assertEquals(Map.of("User-Agent", "LightBot/2.1.0"), mapper.readValue(parallel.getHeaders(), Map.class));
        assertEquals(1, parallel.getIsBuiltin());
        parallel.setId(42L);
        return parallel;
    }

    /**
     * 隔离数据库与 Redis，保留实际 MCP 客户端实现。
     * @param server 注册器生成的测试记录
     * @return 使用该记录的客户端服务
     */
    private McpClientServiceImpl clientService(McpServer server) {
        McpServerService store = mock(McpServerService.class);
        when(store.getById(42L)).thenReturn(server);
        return new McpClientServiceImpl(store, mock(RedisUtil.class), mapper,
                mock(CacheInvalidationBroadcaster.class));
    }

    /** 验证内置注册不会自动启用或要求鉴权。 */
    @Test
    void test_registerParallel_shouldBeDisabledAndAnonymous() throws Exception {
        registeredParallel();
    }

    /** 验证已有记录不会被内置默认配置覆盖。 */
    @Test
    void test_repeatRegistration_shouldPreserveSavedConfiguration() {
        McpServerService store = mock(McpServerService.class);
        McpServer existing = new McpServer();
        existing.setIsBuiltin(1);
        existing.setStatus(CommonStatus.ACTIVE);
        existing.setHost("https://custom.example/mcp");
        existing.setHeaders("{\"User-Agent\":\"custom\"}");
        existing.setDisabledTools("[\"web_fetch\"]");
        when(store.getOne(any())).thenReturn(existing);
        new BuiltinMcpRegistrar(store, mapper).registerBuiltInMcpServers();
        verify(store, never()).save(any());
        verify(store, never()).updateById(any(McpServer.class));
        assertEquals(CommonStatus.ACTIVE, existing.getStatus());
        assertEquals("https://custom.example/mcp", existing.getHost());
        assertEquals("{\"User-Agent\":\"custom\"}", existing.getHeaders());
        assertEquals("[\"web_fetch\"]", existing.getDisabledTools());
    }

    /** 验证实际 HTTP 请求、工具参数及启用/禁用行为。 */
    @Test
    void test_actualCaller_shouldSendHeadersAndRespectDisabledTools() throws Exception {
        List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        List<String> userAgents = new CopyOnWriteArrayList<>();
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), Map.class);
            requests.add(request);
            if (!request.containsKey("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            Object result;
            switch ((String) request.get("method")) {
                case "initialize" -> result = Map.of("protocolVersion", "2025-03-26",
                        "capabilities", Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo", Map.of("name", "parallel-fixture", "version", "1"));
                case "tools/list" -> result = Map.of("tools", List.of(
                        tool("web_search"), tool("web_fetch")));
                case "tools/call" -> result = Map.of("content", List.of(Map.of("type", "text",
                        "text", "Spring Boot documentation: https://docs.spring.io/spring-boot/")), "isError", false);
                default -> throw new IllegalStateException("Unexpected method: " + request.get("method"));
            }
            byte[] response = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        fixture.start();
        McpServer server = registeredParallel();
        server.setHost("http://127.0.0.1:" + fixture.getAddress().getPort() + "/mcp");
        McpClientServiceImpl client = clientService(server);
        try {
            assertTrue(client.getToolCallbacks(42L).isEmpty());
            assertTrue(requests.isEmpty(), "Disabled server must not connect");
            server.setStatus(CommonStatus.ACTIVE);
            client.clearCache(42L);
            server.setDisabledTools("[\"web_fetch\"]");
            List<ToolCallback> callbacks = client.getToolCallbacks(42L);
            assertEquals(List.of("web_search"), callbacks.stream().map(c -> c.getToolDefinition().name()).toList());
            String output = callbacks.get(0).call("{\"objective\":\"Find Spring Boot documentation\"}");
            assertTrue(output.contains("https://docs.spring.io/spring-boot/"));
            assertTrue(requests.stream().anyMatch(r -> "tools/list".equals(r.get("method"))));
            Map<String, Object> call = requests.stream().filter(r -> "tools/call".equals(r.get("method"))).findFirst().orElseThrow();
            Map<?, ?> params = (Map<?, ?>) call.get("params");
            assertEquals("web_search", params.get("name"));
            assertEquals(Map.of("objective", "Find Spring Boot documentation"), params.get("arguments"));
            assertTrue(paths.stream().allMatch("/mcp"::equals), paths.toString());
            assertFalse(userAgents.isEmpty());
            assertTrue(userAgents.stream().allMatch("LightBot/2.1.0"::equals), userAgents.toString());
        } finally {
            client.closeAll();
            fixture.stop(0);
        }
    }

    /**
     * 构造本地 MCP 服务的最小工具 Schema。
     * @param name 工具名称
     * @return MCP tools/list 中的一项工具定义
     */
    private Map<String, Object> tool(String name) {
        return Map.of("name", name, "description", name, "inputSchema", Map.of(
                "type", "object", "properties", Map.of("objective", Map.of("type", "string"))));
    }

    /** 验证注册配置经真实客户端匿名执行远端搜索和抓取。 */
    @Test
    @EnabledIfSystemProperty(named = "lightbot.parallel.live", matches = "true")
    void test_anonymousRegisteredServer_shouldExecuteSearchAndFetch() throws Exception {
        McpServer server = registeredParallel();
        server.setStatus(CommonStatus.ACTIVE);
        McpClientServiceImpl client = clientService(server);
        try {
            List<ToolCallback> callbacks = client.getToolCallbacks(42L);
            String sessionId = UUID.randomUUID().toString();
            List<String> outputs = new ArrayList<>();
            for (String name : List.of("web_search", "web_fetch")) {
                ToolCallback callback = callbacks.stream().filter(c -> name.equals(c.getToolDefinition().name())).findFirst().orElseThrow();
                Map<String, Object> arguments = name.equals("web_search")
                        ? Map.of("objective", "Find the official Spring Boot documentation", "search_queries", List.of("Spring Boot official documentation"), "session_id", sessionId)
                        : Map.of("urls", List.of("https://docs.spring.io/spring-boot/"), "objective", "What is Spring Boot?", "session_id", sessionId);
                String output = callback.call(mapper.writeValueAsString(arguments));
                String text = mapper.readTree(output).get(0).path("text").asText();
                var results = mapper.readTree(text).path("results");
                assertTrue(results.isArray() && !results.isEmpty(), name + " must return results: " + output);
                assertTrue(results.get(0).path("url").asText().contains("spring.io"));
                assertFalse(results.get(0).path("excerpts").isEmpty(), name + " must return source excerpts");
                outputs.add(output);
                System.out.println(name + " output: " + output.substring(0, Math.min(1000, output.length())));
            }
            assertEquals(2, outputs.size());
        } finally {
            client.closeAll();
        }
    }
}
