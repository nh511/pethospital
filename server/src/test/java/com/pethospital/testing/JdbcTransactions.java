package com.pethospital.testing;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

public final class JdbcTransactions {
    @FunctionalInterface
    public interface Work<T> {
        T execute(Connection connection) throws Exception;
    }

    private JdbcTransactions() {
    }

    public static <T> T execute(DataSource dataSource, Work<T> work) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Throwable failure) {
                rollback(connection, failure);
                if (failure instanceof Exception exception) {
                    throw exception;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(failure);
            }
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
