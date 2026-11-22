package com.imooc.miaoshaproject.config;

import org.junit.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ApplicationConfigurationTest {

    @Test
    public void localAliasesResolveWithoutExternalServices() throws IOException {
        Map<String, Object> variables = new HashMap<>();
        variables.put("MYSQL_USERNAME", "local-user");
        variables.put("MYSQL_PASSWORD", "local-test-password");
        variables.put("REDIS_HOST", "local-redis");
        variables.put("REDIS_PORT", "16380");
        variables.put("REDIS_PASSWORD", "redis-test-password");
        variables.put("MQ_NAMESERVER_ADDR", "local-mq:19876");
        StandardEnvironment environment = environment(variables);

        assertEquals("local-user", environment.getRequiredProperty("spring.datasource.username"));
        assertEquals("local-test-password", environment.getRequiredProperty("spring.datasource.password"));
        assertEquals("local-redis", environment.getRequiredProperty("spring.redis.host"));
        assertEquals("16380", environment.getRequiredProperty("spring.redis.port"));
        assertEquals("redis-test-password", environment.getRequiredProperty("spring.redis.password"));
        assertEquals("local-mq:19876", environment.getRequiredProperty("mq.nameserver.addr"));
    }

    @Test
    public void containerVariablesOverrideLocalAliasesAndDeploymentFileKeepsBaseline() throws IOException {
        Map<String, Object> variables = new HashMap<>();
        variables.put("MYSQL_PASSWORD", "local-test-password");
        variables.put("REDIS_HOST", "localhost");
        variables.put("SPRING_DATASOURCE_URL", "jdbc:mysql://test-mysql:3306/test");
        variables.put("SPRING_DATASOURCE_PASSWORD", "container-test-password");
        variables.put("SPRING_REDIS_HOST", "test-redis");
        variables.put("SPRING_REDIS_PORT", "6379");
        StandardEnvironment environment = environment(variables);
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("deployment", new FileSystemResource("application.yml"))) {
            environment.getPropertySources().addAfter("testVariables", source);
        }

        assertEquals("jdbc:mysql://test-mysql:3306/test", environment.getRequiredProperty("spring.datasource.url"));
        assertEquals("container-test-password", environment.getRequiredProperty("spring.datasource.password"));
        assertEquals("test-redis", environment.getRequiredProperty("spring.redis.host"));
        assertEquals("6379", environment.getRequiredProperty("spring.redis.port"));
        assertEquals("classpath:mapping/*.xml", environment.getRequiredProperty("mybatis.mapper-locations"));
        assertEquals("18091", environment.getRequiredProperty("server.port"));
        assertEquals("200", environment.getRequiredProperty("server.tomcat.max-threads"));
    }

    @Test
    public void defaultsUseLocalServicesAndDatabasePasswordMustBeProvided() throws IOException {
        StandardEnvironment environment = environment(new HashMap<String, Object>());

        assertEquals("127.0.0.1", environment.getRequiredProperty("spring.redis.host"));
        assertEquals("16379", environment.getRequiredProperty("spring.redis.port"));
        assertEquals("127.0.0.1:19876", environment.getRequiredProperty("mq.nameserver.addr"));
        try {
            environment.getRequiredProperty("spring.datasource.password");
            fail("Database password must come from external configuration");
        } catch (IllegalArgumentException expected) {
            // Missing credentials must not fall back to an embedded password.
        }
    }

    private StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources propertySources) {
                // Tests must never read or log the real process environment.
            }
        };
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("testVariables", variables));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }
}
