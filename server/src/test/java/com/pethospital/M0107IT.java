package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pethospital.testing.ConcurrentTestBarrier;
import com.pethospital.testing.FailureInjector;
import com.pethospital.testing.JdbcTransactions;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(TestTags.MYSQL_IT)
class M0107IT {
    private static MySqlTestContainer mysql;
    private static DataSource dataSource;

    @BeforeAll
    static void startIsolatedMySql() {
        mysql = MySqlTestContainer.start();
        dataSource = mysql.dataSource();
    }

    @AfterAll
    static void stopIsolatedMySql() {
        if (mysql != null) {
            mysql.close();
        }
    }

    @BeforeEach
    void resetProbeTables() throws Exception {
        executeSql("""
            CREATE TABLE IF NOT EXISTS m0107_tx_probe (
                id BIGINT PRIMARY KEY,
                note VARCHAR(64) NOT NULL
            ) ENGINE=InnoDB
            """);
        executeSql("""
            CREATE TABLE IF NOT EXISTS m0107_task_probe (
                id BIGINT PRIMARY KEY,
                status VARCHAR(16) NOT NULL,
                worker_token VARCHAR(64),
                generation_no INT NOT NULL,
                lease_until DATETIME(3)
            ) ENGINE=InnoDB
            """);
        executeSql("TRUNCATE TABLE m0107_tx_probe");
        executeSql("TRUNCATE TABLE m0107_task_probe");
    }

    @Test
    void startsPinnedIsolatedMySql84() throws Exception {
        assertThat(queryString("SELECT VERSION()"))
            .as("Testcontainers must run the pinned MySQL image, not H2")
            .startsWith("8.4.13");
        assertThat(queryString("SELECT DATABASE()"))
            .isEqualTo(mysql.databaseName())
            .isEqualTo("pet_hospital_it");
    }

    @Test
    void injectedFailureRollsBackTheWholeTransaction() throws Exception {
        FailureInjector injector = FailureInjector.failAt("after-first-write");

        assertThatThrownBy(() -> JdbcTransactions.execute(dataSource, connection -> {
            insertTxProbe(connection, 1, "must-roll-back");
            injector.checkpoint("after-first-write");
            insertTxProbe(connection, 2, "never-written");
            return null;
        }))
            .isInstanceOf(FailureInjector.InjectedFailureException.class)
            .hasMessageContaining("after-first-write");

        assertThat(queryLong("SELECT COUNT(*) FROM m0107_tx_probe")).isZero();
    }

    @Test
    void concurrentBarrierMakesOnlyOneWorkerClaimTheQueuedTask() throws Exception {
        executeSql("INSERT INTO m0107_task_probe(id,status,generation_no) VALUES (1,'QUEUED',0)");
        ConcurrentTestBarrier barrier = new ConcurrentTestBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> claims = new ArrayList<>();
        try {
            claims.add(executor.submit(() -> claim("worker-one", barrier)));
            claims.add(executor.submit(() -> claim("worker-two", barrier)));
            barrier.awaitAllReady(Duration.ofSeconds(10));
            barrier.release();

            List<Integer> results = List.of(
                claims.get(0).get(15, TimeUnit.SECONDS),
                claims.get(1).get(15, TimeUnit.SECONDS)
            );
            assertThat(results).containsExactlyInAnyOrder(1, 0);
            assertThat(queryLong("SELECT COUNT(*) FROM m0107_task_probe WHERE status='RUNNING'"))
                .isEqualTo(1);
            assertThat(queryString("SELECT worker_token FROM m0107_task_probe WHERE id=1"))
                .isIn("worker-one", "worker-two");
        } finally {
            barrier.release();
            executor.shutdownNow();
        }
    }

    @Test
    void workerTokenAndGenerationFenceRejectStaleCompletion() throws Exception {
        executeSql("""
            INSERT INTO m0107_task_probe(id,status,worker_token,generation_no,lease_until)
            VALUES (1,'RUNNING','current-worker',2,UTC_TIMESTAMP(3) + INTERVAL 30 SECOND)
            """);

        assertThat(completeTask("stale-worker", 1)).isZero();
        assertThat(completeTask("current-worker", 1)).isZero();
        assertThat(completeTask("current-worker", 2)).isEqualTo(1);
        assertThat(queryString("SELECT status FROM m0107_task_probe WHERE id=1")).isEqualTo("READY");
    }

    private static int claim(String workerToken, ConcurrentTestBarrier barrier) throws Exception {
        return JdbcTransactions.execute(dataSource, connection -> {
            barrier.readyAndAwaitRelease(Duration.ofSeconds(10));
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE m0107_task_probe
                SET status='RUNNING', worker_token=?, lease_until=UTC_TIMESTAMP(3) + INTERVAL 30 SECOND
                WHERE id=1 AND status='QUEUED'
                """)) {
                statement.setString(1, workerToken);
                return statement.executeUpdate();
            }
        });
    }

    private static int completeTask(String workerToken, int generationNo) throws Exception {
        return JdbcTransactions.execute(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE m0107_task_probe
                SET status='READY', lease_until=NULL
                WHERE id=1 AND status='RUNNING' AND worker_token=? AND generation_no=?
                """)) {
                statement.setString(1, workerToken);
                statement.setInt(2, generationNo);
                return statement.executeUpdate();
            }
        });
    }

    private static void insertTxProbe(Connection connection, long id, String note) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO m0107_tx_probe(id,note) VALUES (?,?)"
        )) {
            statement.setLong(1, id);
            statement.setString(2, note);
            statement.executeUpdate();
        }
    }

    private static void executeSql(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryString(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static long queryLong(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
