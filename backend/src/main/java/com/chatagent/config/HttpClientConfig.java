package com.chatagent.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP 客户端配置：为 DashScope API 调用配置超时和连接参数。
 * 
 * <p>
 * 配置内容：
 * <ul>
 *   <li>dashScopeRestClient：用于同步调用的 RestClient</li>
 *   <li>dashScopeStreamHttpClient：用于流式调用的 HttpClient</li>
 * </ul>
 * 
 * <p>
 * 超时配置：
 * <ul>
 *   <li>connectTimeoutMs：连接超时（默认从配置读取）</li>
 *   <li>readTimeoutMs：读取超时（默认从配置读取）</li>
 * </ul>
 */
@Configuration
public class HttpClientConfig {

    /**
     * 配置 DashScope 同步调用的 RestClient。
     * 
     * @param props DashScope 配置属性
     * @return 配置好超时参数的 RestClient
     */
    @Bean
    public RestClient dashScopeRestClient(DashScopeProperties props) {
        HttpClient jdk =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                        .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(jdk);
        rf.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(props.getBaseUrl().replaceAll("/$", ""))
                .requestFactory(rf)
                .build();
    }

    /**
     * 配置 DashScope 流式调用的 HttpClient。
     * 
     * @param props DashScope 配置属性
     * @return 配置好连接超时的 HttpClient
     */
    @Bean
    public HttpClient dashScopeStreamHttpClient(DashScopeProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }
}
