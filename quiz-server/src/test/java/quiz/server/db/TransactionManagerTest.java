package quiz.server.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.protocol.ErrorCode;
import quiz.server.exception.ConflictException;
import quiz.server.exception.QuizException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("TransactionManager Unit Tests")
class TransactionManagerTest {

    private DataSource mockDataSource;
    private Connection mockConnection;
    private TransactionManager transactionManager;

    @BeforeEach
    void setUp() throws SQLException {
        mockDataSource = mock(DataSource.class);
        mockConnection = mock(Connection.class);
        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.getAutoCommit()).thenReturn(true);

        transactionManager = new TransactionManager(mockDataSource);
    }

    @Test
    @DisplayName("Should commit transaction and restore autoCommit on success with Function")
    void testCommitOnSuccess() throws SQLException {
        String result = transactionManager.executeInTransaction(conn -> {
            assertSame(mockConnection, conn);
            return "SUCCESS";
        });

        assertEquals("SUCCESS", result);
        verify(mockConnection).setAutoCommit(false);
        verify(mockConnection).commit();
        verify(mockConnection, never()).rollback();
        verify(mockConnection).setAutoCommit(true);
        verify(mockConnection).close();
    }

    @Test
    @DisplayName("Should rollback transaction when exception occurs in transaction callback")
    void testRollbackOnException() throws SQLException {
        QuizException thrown = assertThrows(ConflictException.class, () -> {
            transactionManager.executeInTransaction(conn -> {
                throw new ConflictException("Duplicate key error");
            });
        });

        assertEquals(ErrorCode.CONFLICT, thrown.getErrorCode());
        assertEquals("Duplicate key error", thrown.getMessage());

        verify(mockConnection).setAutoCommit(false);
        verify(mockConnection, never()).commit();
        verify(mockConnection).rollback();
        verify(mockConnection).setAutoCommit(true);
        verify(mockConnection).close();
    }

    @Test
    @DisplayName("Should execute void transaction callback properly with runInTransaction")
    void testVoidTransactionCallback() throws SQLException {
        boolean[] executed = {false};

        transactionManager.runInTransaction(conn -> {
            executed[0] = true;
        });

        assertTrue(executed[0]);
        verify(mockConnection).commit();
        verify(mockConnection).close();
    }

    @Test
    @DisplayName("Should wrap checked exception in QuizException with DATABASE_ERROR when using executeWithException")
    void testWrapCheckedException() throws SQLException {
        QuizException thrown = assertThrows(QuizException.class, () -> {
            transactionManager.executeWithException(conn -> {
                throw new SQLException("Deadlock detected");
            });
        });

        assertEquals(ErrorCode.DATABASE_ERROR, thrown.getErrorCode());
        verify(mockConnection).rollback();
        verify(mockConnection).close();
    }
}
