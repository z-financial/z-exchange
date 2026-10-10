package com.exchange.common.identity;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the exact Java/MyBatis/MySQL numeric contract used by owner rows. */
@Testcontainers(disabledWithoutDocker = true)
class InternalIdMybatisTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33");

    private static HikariDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(2);
        dataSource = new HikariDataSource(config);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE internal_id_probe (
                        internal_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
                        entity_ref CHAR(22) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
                        CONSTRAINT chk_internal_id_positive_long
                            CHECK (internal_id BETWEEN 1 AND 9223372036854775807)
                    )
                    """);
        }

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        sqlSessionFactory = factoryBean.getObject();
        sqlSessionFactory.getConfiguration().addMapper(InternalIdProbeMapper.class);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void roundTripsEntireSupportedPositiveLongRangeWithoutSignOrPrecisionChange() {
        EntityRefCodec codec = new DefaultEntityRefCodec();
        long[] values = {1L, 42L, 123_456_789_012_345L, Long.MAX_VALUE};

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            InternalIdProbeMapper mapper = session.getMapper(InternalIdProbeMapper.class);
            for (long value : values) {
                String reference = codec.encode(EntityRefType.WALLET, value);
                assertEquals(1, mapper.insert(value, reference));
                assertEquals(Long.valueOf(value), mapper.selectInternalId(reference));
            }
        }
    }

    interface InternalIdProbeMapper {
        @Insert("INSERT INTO internal_id_probe (internal_id, entity_ref) VALUES (#{internalId}, #{entityRef})")
        int insert(@Param("internalId") long internalId, @Param("entityRef") String entityRef);

        @Select("SELECT internal_id FROM internal_id_probe WHERE entity_ref = #{entityRef}")
        Long selectInternalId(@Param("entityRef") String entityRef);
    }
}
