package com.czq.apiclientsdk;

import com.czq.apiclientsdk.client.NameApiClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 核心接口调用的自动配置
 * 可以读取配置文件中的czq.api.xxx的属性（ak,sk）
 */
@Configuration
@ConfigurationProperties("czq.api")
@ComponentScan
@Data
public class HeartApiClientAutoConfiguration {

    private String accessKey;
    private String secretKey;


    @Bean
    public NameApiClient czqClient(){
        return new NameApiClient(accessKey,secretKey);
    }
}
