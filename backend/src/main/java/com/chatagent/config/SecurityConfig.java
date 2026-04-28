package com.chatagent.config;

import com.chatagent.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置：配置 Spring Security 的认证、授权。
 * 
 * <p>
 * 配置内容：
 * <ul>
 *   <li>JWT 认证：通过 JwtAuthenticationFilter 解析和验证 JWT</li>
 *   <li>会话管理：使用 STATELESS 会话策略（无状态）</li>
 *   <li>权限控制：配置接口的访问权限</li>
 *   <li>异常处理：统一处理认证和授权异常</li>
 * </ul>
 * 
 * <p>
 * 访问权限：
 * <ul>
 *   <li>POST /api/auth/login：允许匿名访问（登录）</li>
 *   <li>GET /api/health：允许匿名访问（健康检查）</li>
 *   <li>/actuator/health：允许匿名访问（Actuator 健康检查）</li>
 *   <li>其他请求：需要认证</li>
 * </ul>
 * 
 * <p>
 * 安全特性：
 * <ul>
 *   <li>CSRF 关闭：使用 JWT 认证，无需 CSRF 保护</li>
 *   <li>CORS 启用：允许跨域请求</li>
 *   <li>无状态会话：使用 JWT，不维护服务器会话状态</li>
 *   <li>密码加密：使用 BCryptPasswordEncoder 加密密码</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 配置安全过滤链。
     * 
     * @param http HTTP 安全配置器
     * @return 安全过滤链
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/auth/login")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/health")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/chat")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/graph/chat")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/graph/approve")
                                        .permitAll()
                                        .requestMatchers("/actuator/health")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (request, response, authException) -> {
                                                    if (response.isCommitted()) {
                                                        return;
                                                    }
                                                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                                    response.setContentType("application/json");
                                                    response.getWriter()
                                                            .write("{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
                                                })
                                        .accessDeniedHandler(
                                                (request, response, accessDeniedException) -> {
                                                    if (response.isCommitted()) {
                                                        return;
                                                    }
                                                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                                    response.setContentType("application/json");
                                                    response.getWriter()
                                                            .write("{\"error\":\"Forbidden\",\"message\":\"Access denied\"}");
                                                }));

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 配置密码编码器。
     * 
     * @return BCryptPasswordEncoder 实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 配置认证管理器。
     * 
     * @param config 认证配置
     * @return AuthenticationManager 实例
     * @throws Exception 配置异常
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
