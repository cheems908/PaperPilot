package com.paperpilot.api;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.CodeSymbolMapper;
import com.paperpilot.api.mapper.ConceptCodeMappingMapper;
import com.paperpilot.api.mapper.FileMapper;
import com.paperpilot.api.mapper.GitRepositoryMapper;
import com.paperpilot.api.mapper.PaperConceptMapper;
import com.paperpilot.api.mapper.PaperMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.paperpilot.api.mapper.StageExecutionMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.EnumTypeHandler;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;

/**
 * 测试公共构建：手工组装 MyBatis-Plus SqlSessionFactory（注册全部 Mapper、
 * 枚举 name() 存取、乐观锁拦截器），与 {@code TaskStatusPersistenceTest} 同模式。
 */
public final class TestSupport {

    private TestSupport() {
    }

    public static DataSource dataSource(MySQLContainer<?> container) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(container.getJdbcUrl());
        ds.setUsername(container.getUsername());
        ds.setPassword(container.getPassword());
        return ds;
    }

    public static SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(ds);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultEnumTypeHandler(EnumTypeHandler.class);
        configuration.addMapper(ProjectMapper.class);
        configuration.addMapper(FileMapper.class);
        configuration.addMapper(AnalysisTaskMapper.class);
        configuration.addMapper(PaperMapper.class);
        configuration.addMapper(PaperConceptMapper.class);
        configuration.addMapper(GitRepositoryMapper.class);
        configuration.addMapper(CodeSymbolMapper.class);
        configuration.addMapper(ConceptCodeMappingMapper.class);
        configuration.addMapper(StageExecutionMapper.class);
        factoryBean.setConfiguration(configuration);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factoryBean.setPlugins(interceptor);

        return factoryBean.getObject();
    }
}
