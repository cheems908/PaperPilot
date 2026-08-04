package com.paperpilot.api;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.paperpilot.api.domain.entity.AnalysisTask;
import com.paperpilot.api.domain.entity.Project;
import com.paperpilot.api.domain.enums.TaskStatus;
import com.paperpilot.api.mapper.AnalysisTaskMapper;
import com.paperpilot.api.mapper.ProjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.EnumTypeHandler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端验证 MyBatis-Plus 在真实 MySQL 上的关键行为：
 * 枚举按 name() 存取、乐观锁（version）实现"状态更新必须检查旧状态".
 */
@Testcontainers
class TaskStatusPersistenceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void enumRoundTripAndOptimisticLockRejectsStaleUpdate() throws Exception {
        DataSource ds = dataSource();
        Flyway.configure().dataSource(ds).load().migrate();

        SqlSessionFactory factory = buildSqlSessionFactory(ds);

        try (SqlSession session = factory.openSession(true)) {
            ProjectMapper projectMapper = session.getMapper(ProjectMapper.class);
            AnalysisTaskMapper taskMapper = session.getMapper(AnalysisTaskMapper.class);

            // 建 project（analysis_task 外键依赖）
            Project project = new Project();
            project.setName("roundtrip");
            projectMapper.insert(project);

            // 1) 枚举 name() 存取
            AnalysisTask task = new AnalysisTask();
            task.setProjectId(project.getId());
            task.setRequestKey("req-roundtrip");
            task.setStatus(TaskStatus.PENDING);
            taskMapper.insert(task);

            AnalysisTask loaded = taskMapper.selectById(task.getId());
            assertThat(loaded.getStatus()).isEqualTo(TaskStatus.PENDING);
            Integer v0 = loaded.getVersion();
            assertThat(v0).isNotNull();

            // 2) 乐观锁：正常更新 version+1
            loaded.setStatus(TaskStatus.QUEUED);
            int rows = taskMapper.updateById(loaded);
            assertThat(rows).isEqualTo(1);
            assertThat(loaded.getVersion()).isEqualTo(v0 + 1);

            // 3) 乐观锁：携带旧 version 的并发更新必须失败（0 行受影响）
            AnalysisTask stale = new AnalysisTask();
            stale.setId(task.getId());
            stale.setRequestKey("req-roundtrip");
            stale.setStatus(TaskStatus.RUNNING);
            stale.setVersion(v0); // 旧版本号
            int staleRows = taskMapper.updateById(stale);
            assertThat(staleRows).isZero();

            // 4) 状态仍是合法迁移后的 QUEUED，未被脏写
            AnalysisTask finalState = taskMapper.selectById(task.getId());
            assertThat(finalState.getStatus()).isEqualTo(TaskStatus.QUEUED);
            assertThat(finalState.getVersion()).isEqualTo(v0 + 1);
        }
    }

    private DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        return ds;
    }

    private SqlSessionFactory buildSqlSessionFactory(DataSource ds) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(ds);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultEnumTypeHandler(EnumTypeHandler.class);
        configuration.addMapper(ProjectMapper.class);
        configuration.addMapper(AnalysisTaskMapper.class);
        factoryBean.setConfiguration(configuration);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factoryBean.setPlugins(interceptor);

        return factoryBean.getObject();
    }
}
