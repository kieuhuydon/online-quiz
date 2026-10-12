package quiz.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quiz.common.protocol.ErrorCode;
import quiz.server.exception.QuizException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Chạy một thao tác trong giao dịch; commit khi thành công và rollback khi thất bại.
 * Kết nối được trả về pool sau khi khôi phục trạng thái auto-commit ban đầu.
 */
public class TransactionManager {
    private static final Logger log = LoggerFactory.getLogger(TransactionManager.class);

    private final DataSource dataSource;

    public TransactionManager() {
        this.dataSource = null; // Will use DataSourceProvider lazily
    }

    public TransactionManager(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "DataSource cannot be null");
    }

    private DataSource resolveDataSource() {
        if (dataSource != null) {
            return dataSource;
        }
        return DataSourceProvider.getDataSource();
    }

    /**
     * Functional interface hỗ trợ ném ngoại lệ Checked Exception trong khối giao dịch.
     */
    @FunctionalInterface
    public interface TransactionCallback<T> {
        T doInTransaction(Connection connection) throws Exception;
    }

    /**
     * Functional interface cho các thao tác không trả về kết quả (void) và có thể ném Checked Exception.
     */
    @FunctionalInterface
    public interface TransactionVoidCallback {
        void doInTransaction(Connection connection) throws Exception;
    }

    /**
     * Thực thi hàm có trả về kết quả trong một Transaction.
     *
     * @param action Function nhận Connection và trả về kiểu T
     * @param <T>    Kiểu dữ liệu trả về
     * @return Kết quả sau khi commit thành công
     */
    public <T> T executeInTransaction(Function<Connection, T> action) {
        Objects.requireNonNull(action, "Transaction action cannot be null");
        return execute(action::apply);
    }

    /**
     * Thực thi hành động không có kết quả trả về trong một Transaction.
     *
     * @param action Consumer nhận Connection
     */
    public void runInTransaction(Consumer<Connection> action) {
        Objects.requireNonNull(action, "Transaction action cannot be null");
        execute(connection -> {
            action.accept(connection);
            return null;
        });
    }

    /**
     * Thực thi giao dịch có trả về kết quả và cho phép ném Checked Exception (như SQLException).
     */
    public <T> T executeWithException(TransactionCallback<T> action) {
        Objects.requireNonNull(action, "Transaction action cannot be null");
        return execute(action);
    }

    /**
     * Thực thi giao dịch không có kết quả trả về và cho phép ném Checked Exception (như SQLException).
     */
    public void runWithException(TransactionVoidCallback action) {
        Objects.requireNonNull(action, "Transaction action cannot be null");
        execute(connection -> {
            action.doInTransaction(connection);
            return null;
        });
    }

    private <T> T execute(TransactionCallback<T> action) {
        Connection connection = null;
        DataSource transactionDataSource = resolveDataSource();
        boolean originalAutoCommit = true;
        boolean transactionStarted = false;
        boolean transactionEnded = false;

        try {
            connection = transactionDataSource.getConnection();
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;

            T result = action.doInTransaction(connection);

            connection.commit();
            transactionEnded = true;
            return result;
        } catch (Throwable failure) {
            if (connection != null && transactionStarted) {
                try {
                    connection.rollback();
                    transactionEnded = true;
                    log.debug("Transaction rolled back successfully");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    log.error("Failed to rollback transaction", rollbackFailure);
                }
            }

            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            throw new QuizException(ErrorCode.DATABASE_ERROR, "Database operation failed", failure);
        } finally {
            if (connection != null) {
                try {
                    if (transactionEnded) {
                        connection.setAutoCommit(originalAutoCommit);
                    } else {
                        // A failed rollback/setup must not be followed by an implicit commit.
                        connection.abort(Runnable::run);
                    }
                } catch (SQLException ex) {
                    log.warn("Failed to reset connection autoCommit to {}", originalAutoCommit, ex);
                }
                try {
                    connection.close();
                } catch (SQLException ex) {
                    log.warn("Failed to close connection after transaction", ex);
                }
            }
        }
    }
}
