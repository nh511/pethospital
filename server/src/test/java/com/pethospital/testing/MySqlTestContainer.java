package com.pethospital.testing;

import com.mysql.cj.jdbc.MysqlDataSource;
import javax.sql.DataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class MySqlTestContainer implements AutoCloseable {
    public static final String IMAGE = "mysql:8.4.13";

    private final MySQLContainer<?> container;

    private MySqlTestContainer(MySQLContainer<?> container) {
        this.container = container;
    }

    public static MySqlTestContainer start() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new IllegalStateException(
                "Database integration tests require a reachable Docker engine; MySQL tests cannot use H2 or be skipped."
            );
        }
        MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse(IMAGE))
            .withDatabaseName("pet_hospital_it")
            .withUsername("pet_it")
            .withPassword("pet_it_password");
        container.start();
        return new MySqlTestContainer(container);
    }

    public DataSource dataSource() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }

    public String databaseName() {
        return container.getDatabaseName();
    }

    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    public String username() {
        return container.getUsername();
    }

    public String password() {
        return container.getPassword();
    }

    @Override
    public void close() {
        container.stop();
    }
}
