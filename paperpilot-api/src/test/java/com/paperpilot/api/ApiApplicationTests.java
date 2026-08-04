package com.paperpilot.api;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

/**
 * 应用上下文加载冒烟测试.
 *
 * <p>自 T1.3 起新增的服务/控制器依赖 MyBatis-Plus Mapper，而测试 resources 的
 * application.yml 排除了 DataSource 自动配置，故这里用 Testcontainers 提供
 * 真实 DataSource 与事务管理器，验证全量上下文能装配成功。
 */
@SpringBootTest
@Testcontainers
class ApiApplicationTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void contextLoads() {
    }

    @TestConfiguration
    static class DbConfig {

        @Bean
        @Primary
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(MYSQL.getJdbcUrl());
            ds.setUsername(MYSQL.getUsername());
            ds.setPassword(MYSQL.getPassword());
            return ds;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
