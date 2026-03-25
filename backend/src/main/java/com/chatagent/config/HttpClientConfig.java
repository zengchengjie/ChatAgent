package com.chatagent.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

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

    @Bean
    public HttpClient dashScopeStreamHttpClient(DashScopeProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }
}
