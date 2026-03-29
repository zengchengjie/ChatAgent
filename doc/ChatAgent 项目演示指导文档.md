# ChatAgent 项目演示指导文档

## 一、项目功能概览

ChatAgent 是一个基于 LangChain4j 框架开发的 AI Agent 对话系统，支持工具调用、流式响应、知识库检索、长期记忆等功能。

## 二、演示场景与步骤

### 1. 会话管理功能

**演示步骤**：
1. 打开应用首页，查看左侧会话列表
2. 点击"+"按钮创建新会话
3. 点击会话右侧的菜单按钮，选择"重命名"
4. 输入新名称，点击保存
5. 点击"导出"按钮，下载会话数据
6. 尝试删除一个会话

**展示能力**：
- 会话的创建、重命名、导出、删除
- 响应式设计（在不同屏幕尺寸下的表现）
- 会话列表的搜索功能

**核心代码**：
- 前端：`frontend/src/views/ChatView.vue`
- 后端：`backend/src/main/java/com/chatagent/chat/SessionController.java`

### 2. 工具调用功能

**演示步骤**：
1. 在输入框中输入"计算 123*456"
2. 点击发送按钮
3. 观察右侧"过程"面板，查看工具调用过程
4. 输入"北京天气"，观察工具调用

**展示能力**：
- 工具调用的自动触发
- 工具执行过程的可视化
- 工具结果的处理和展示

**核心代码**：
- 前端：`frontend/src/views/ChatView.vue`（工具调用可视化）
- 后端：
  - `backend/src/main/java/com/chatagent/tools/ToolRegistry.java`
  - `backend/src/main/java/com/chatagent/agent/engine/Langchain4jAgentEngine.java`

### 3. RAG 知识库检索

**演示步骤**：
1. 点击顶部的"知识库管理"按钮
2. 查看已上传的知识库文档
3. 返回聊天界面，输入"项目中 SSE 事件有哪些"
4. 观察右侧"过程"面板，查看知识库检索过程
5. 查看助手的回答，是否基于检索到的知识

**展示能力**：
- 知识库的管理和维护
- 基于语义的知识库检索
- 检索结果的展示和利用

**核心代码**：
- 前端：`frontend/src/views/AdminKnowledgeView.vue`
- 后端：
  - `backend/src/main/java/com/chatagent/knowledge/KnowledgeService.java`
  - `backend/src/main/java/com/chatagent/tools/SearchKnowledgeTool.java`

### 4. 长期记忆功能

**演示步骤**：
1. 进行多轮对话，讨论一个主题
2. 关闭浏览器，重新打开应用
3. 继续之前的对话，观察助手是否记得之前的内容
4. 切换到另一个会话，再切换回来，观察连续性

**展示能力**：
- 对话摘要的自动生成
- 长期记忆的存储和检索
- 跨会话的记忆保持

**核心代码**：
- 后端：
  - `backend/src/main/java/com/chatagent/memory/ChatSummaryService.java`
  - `backend/src/main/java/com/chatagent/memory/UserMemoryService.java`

### 5. 流式响应功能

**演示步骤**：
1. 输入一个需要思考的复杂问题
2. 观察助手的回答是否逐字显示
3. 观察右侧"过程"面板的实时更新

**展示能力**：
- 流式响应的实时性
- 过程可视化的同步更新
- 良好的用户体验

**核心代码**：
- 前端：`frontend/src/utils/sse.ts`
- 后端：`backend/src/main/java/com/chatagent/agent/AgentController.java`

### 6. 模型切换功能

**演示步骤**：
1. 在创建新会话时，选择不同的模型
2. 观察不同模型的响应差异

**展示能力**：
- 多模型支持
- 模型配置的灵活性

**核心代码**：
- 前端：`frontend/src/views/ChatView.vue`
- 后端：`backend/src/main/java/com/chatagent/llm/DashScopeClient.java`

## 三、界面丰富性评估

### 当前界面功能

1. **响应式设计**：
   - 适配桌面、平板和移动设备
   - 侧边栏和工具面板的动态显示/隐藏

2. **用户体验**：
   - 工具调用可视化
   - 会话管理（创建、重命名、导出、删除）
   - 流式响应
   - Markdown 渲染
   - 深色主题

3. **功能完整性**：
   - 会话管理
   - 工具调用
   - RAG 检索
   - 长期记忆
   - 多模型支持

### 界面丰富性建议

当前界面已经相当完善，能够满足基本的功能演示需求。如果需要进一步丰富，可以考虑：

1. **个性化设置**：
   - 用户头像和个人资料
   - 主题自定义（更多颜色方案）

2. **高级功能**：
   - 消息编辑和撤回
   - 会话标签和分类
   - 快捷工具按钮

3. **交互优化**：
   - 拖拽排序会话
   - 消息复制和分享
   - 键盘快捷键

4. **可视化增强**：
   - 工具调用结果的更丰富展示
   - 知识库检索结果的可视化
   - 对话统计和分析

## 四、演示准备工作

1. **环境准备**：
   - 启动后端服务：`mvn spring-boot:run`
   - 启动前端服务：`npm run dev`
   - 配置 DashScope API Key

2. **知识库准备**：
   - 确保知识库中有足够的文档
   - 包含 SSE 事件、工具调用等相关文档

3. **测试账号**：
   - 准备测试账号，确保登录功能正常

4. **演示脚本**：
   - 准备一个演示脚本，按照上述场景顺序进行
   - 准备一些典型问题，覆盖所有功能点

## 五、核心代码位置

| 功能模块 | 核心代码位置 | 说明 |
|---------|------------|------|
| Agent 引擎 | `Langchain4jAgentEngine.java` | Agent 主循环、工具调用逻辑 |
| 工具管理 | `ToolRegistry.java` | 工具注册和执行 |
| 知识库 | `KnowledgeService.java` | 知识库检索和管理 |
| 长期记忆 | `ChatSummaryService.java` | 对话摘要和记忆管理 |
| 前端界面 | `ChatView.vue` | 主聊天界面、工具调用可视化 |
| 会话管理 | `SessionController.java` | 会话的 CRUD 操作 |
| 模型调用 | `DashScopeClient.java` | LLM 模型调用 |
| 流式响应 | `AgentController.java` | SSE 流式响应 |

## 六、演示注意事项

1. **网络连接**：确保网络连接稳定，特别是调用 DashScope API 时
2. **API Key**：确保 DashScope API Key 有效
3. **知识库**：确保知识库中有足够的文档，以便演示 RAG 功能
4. **浏览器**：推荐使用 Chrome 或 Firefox 最新版本
5. **设备**：准备不同尺寸的设备，展示响应式设计
6. **时间控制**：每个演示场景控制在 2-3 分钟，确保整体演示流畅

## 七、总结

ChatAgent 项目已经实现了完整的 Agent 功能，包括工具调用、RAG 检索、长期记忆、流式响应等核心特性。通过本演示指导文档，您可以全面展示项目的能力和技术亮点，为面试或演示提供有力支持。

界面设计已经相当完善，能够满足基本的功能演示需求。如果需要进一步丰富，可以参考上述建议进行优化。