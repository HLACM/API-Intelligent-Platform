package com.czq.apithirdpartyservices.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * 声明了要加载类路径下的 email.properties 文件，指定了要绑定的配置属性的前缀为 msm，
 * 实现了 InitializingBean 接口，在 Bean 初始化完成后会执行 afterPropertiesSet方法
 */
@PropertySource("classpath:email.properties")
@ConfigurationProperties(prefix = "msm")
@Configuration
public class QQEmailConfig implements InitializingBean {
    @Value("${msm.email}")
    private String email;

    @Value("${msm.host}")
    private String host;

    @Value("${msm.port}")
    private String port;

    @Value("${msm.password}")
    private String password;

    public static String EMAIL;
    public static String HOST;
    public static String PORT;
    public static String PASSWORD;

    /**
     * bean初始化完成之后执行该方法
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        EMAIL = email;
        HOST = host;
        PORT = port;
        PASSWORD = password;
    }

}