package com.chatagent.chat;

import com.chatagent.chat.dto.CreateSessionRequest;
import com.chatagent.chat.dto.MessageResponse;
import com.chatagent.chat.dto.SessionResponse;
import com.chatagent.common.ApiException;
import com.chatagent.security.JwtPrincipal;
import com.chatagent.security.SecurityUtils;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话控制器：提供会话管理和消息查询的 HTTP 接口。
 * 
 * <p>
 * 功能：
 * <ul>
 *   <li>创建会话：POST /api/sessions - 创建新的对话会话</li>
 *   <li>查询会话列表：GET /api/sessions - 查询用户的所有会话</li>
 *   <li>查询消息列表：GET /api/sessions/{sessionId}/messages - 查询会话的消息</li>
 * </ul>
 * 
 * <p>
 * 安全控制：
 * <ul>
 *   <li>JWT 认证：所有接口需要有效的 JWT Token</li>
 *   <li>会话归属校验：确保用户只能访问自己的会话</li>
 *   <li>参数校验：使用 @Valid 校验请求参数</li>
 * </ul>
 * 
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 创建会话
 * POST /api/sessions
 * {
 *   "title": "新对话"
 * }
 * 
 * // 查询会话列表
 * GET /api/sessions
 * 
 * // 查询消息列表
 * GET /api/sessions/{sessionId}/messages
 * }</pre>
 */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ChatService chatService;

    /**
     * 创建新会话。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>校验 JWT 认证</li>
     *   <li>获取用户 ID</li>
     *   <li>调用 ChatService.createSession() 创建会话</li>
     *   <li>返回会话信息</li>
     * </ol>
     * 
     * @param req 创建会话请求（可选标题）
     * @return 创建的会话信息
     */
    @PostMapping
    public SessionResponse create(@Valid @RequestBody(required = false) CreateSessionRequest req) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        String title = req != null ? req.getTitle() : null;
        String model = req != null ? req.getModel() : null;
        return chatService.createSession(p.userId(), title, model);
    }

    /**
     * 查询用户的所有会话列表。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>校验 JWT 认证</li>
     *   <li>获取用户 ID</li>
     *   <li>调用 ChatService.listSessions() 查询会话列表</li>
     *   <li>按更新时间倒序返回</li>
     * </ol>
     * 
     * @return 会话列表
     */
    @GetMapping
    public List<SessionResponse> list() {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        return chatService.listSessions(p.userId());
    }

    /**
     * 查询指定会话的消息列表。
     * 
     * <p>
     * 执行流程：
     * <ol>
     *   <li>校验 JWT 认证</li>
     *   <li>校验会话归属</li>
     *   <li>调用 ChatService.listMessages() 查询消息列表</li>
     *   <li>按创建时间正序返回</li>
     * </ol>
     * 
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    @GetMapping("/{sessionId}/messages")
    public List<MessageResponse> messages(@PathVariable String sessionId) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        return chatService.listMessages(p.userId(), sessionId);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        chatService.deleteSession(p.userId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{sessionId}")
    public SessionResponse updateTitle(@PathVariable String sessionId, @RequestBody CreateSessionRequest req) {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        String title = req != null ? req.getTitle() : null;
        return chatService.updateSessionTitle(p.userId(), sessionId, title);
    }
}
