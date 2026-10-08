# SPEC-202610-001：内置 Parallel Search MCP（轻量 SDD）

状态：待评审

## 目标与范围

通过已有内置 MCP 注册器提供可选的 Parallel 网页搜索与抓取服务。
仅变更 lightbot-tool，不改变模块边界、数据库结构、接口或 Agent 默认选择。
不增加付费 REST/SDK 接口，不新增模型或自动启用服务。

## 设计

- 注册名 `parallel-search`，地址 `https://search.parallel.ai/mcp`，传输为 Streamable HTTP。
- 新记录保持 `DISABLED`；启动注册不连接远端。已有同名记录不覆盖配置。
- 内置定义增加可选 HTTP 请求头，Parallel 使用 `User-Agent: LightBot/2.1.0`。
- 复用 McpClientService 的工具发现、SyncMcpToolCallback、30 秒请求超时和缓存清理。
- 匿名端点无需 Parallel API Key；免费额度有速率限制，模型推理费用独立。

## 验收标准

- AC-1：缺失时注册禁用的 Parallel 记录，写入正确 URL、传输和 User-Agent；其他内置记录不变。
- AC-2：重复启动保留已有同名记录的状态、地址、请求头和禁用工具配置。
- AC-3：通过 McpClientService 加载注册配置，匿名发现工具并用回调执行 web_search / web_fetch，得到有效内容。
- AC-4：请求包含配置的 User-Agent；禁用状态和禁用工具仍生效。

## 任务与验证

1. 为内置定义增加可选 headers，添加 Parallel 定义。
2. 增加注册与实际客户端调用回归测试，以及显式启用的匿名远端测试。
3. 在干净 Maven 环境运行测试并记录匿名工具返回的具体结果。
4. 按下方用户步骤验证；保持启动注册无网络请求。

## 使用

启动服务后，在「扩展 / MCP」中找到 `parallel-search`，启用并测试连接，
刷新工具列表。使用已有 MCP 工具选择界面选择 `web_search` 或 `web_fetch`。
请求头预设为 `{"User-Agent":"LightBot/2.1.0"}`，不要添加 Authorization 或 API Key。
服务使用匿名免费额度，限流时稍后重试；工具说明与额度以
[官方文档](https://docs.parallel.ai/integrations/mcp/search-mcp)为准。

自动回归测试：`mvn -pl lightbot-tool -am test -Dtest=ParallelSearchMcpTest -Dsurefire.failIfNoSpecifiedTests=false`。
显式远端验证：同一命令增加 `-Dlightbot.parallel.live=true`；会向 Parallel 发送搜索与网页抓取请求。
测试验证工具回调，不宣称覆盖模型自主选择工具的完整 Agent 对话。

## 回滚

回退新增注册定义、可选请求头字段和测试；如已创建数据库记录，管理员可禁用或删除该条目。
