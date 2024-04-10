package com.czq.yuapigateway.config;

import com.czq.yuapigateway.filter.InterfaceInvokeFilter;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 配置发送到模拟接口中的请求必须先经过自定义过滤器InterfaceInvokeFilter再发送到api-interface中
 */
@Configuration
public class GatewayConfig {
    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder, InterfaceInvokeFilter filter) {
        //用路由前缀区分路由来源是前端还是接口管理平台
        return builder.routes()
                .route(r ->
                        r.path("/api/interface/**")
                        .filters(f -> f.filter(filter))
                                .uri("lb://api-interface")
                )
                .build();
    }

}
