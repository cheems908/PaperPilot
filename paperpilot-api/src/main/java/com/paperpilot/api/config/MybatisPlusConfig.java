package com.paperpilot.api.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置.
 *
 * <p>{@link OptimisticLockerInnerInterceptor} 启用 {@code @Version} 乐观锁：
 * UPDATE 自动拼接 {@code SET version = version + 1 WHERE ... AND version = ?}，
 * 配合 Java 侧 {@code TaskStateMachine} 实现"状态更新必须检查旧状态"。
 *
 * <p>说明：MyBatis-Plus 3.5.9 将 JSqlParser 相关拦截器（如分页）拆分到
 * {@code mybatis-plus-jsqlparser} 模块，MVP 暂不需要，故未引入。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
