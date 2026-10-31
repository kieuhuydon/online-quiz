package quiz.client.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quiz.common.enums.Role;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Screen Enum Unit Tests")
class ScreenTest {

    @Test
    @DisplayName("Screen.home(Role) và Screen.roleLabel(Role) trả về đúng màn hình và nhãn theo vai trò")
    void homeAndRoleLabelMapEveryRole() {
        assertEquals(Screen.LOGIN, Screen.home(null));
        assertEquals(Screen.ADMIN_ACCOUNTS, Screen.home(Role.ADMIN));
        assertEquals(Screen.TEACHER_DASHBOARD, Screen.home(Role.TEACHER));
        assertEquals(Screen.STUDENT_DASHBOARD, Screen.home(Role.STUDENT));

        assertEquals("Quản trị viên", Screen.roleLabel(Role.ADMIN));
        assertEquals("Giáo viên", Screen.roleLabel(Role.TEACHER));
        assertEquals("Học viên", Screen.roleLabel(Role.STUDENT));

        for (Screen screen : Screen.values()) {
            assertNotNull(screen.resource());
            assertTrue(screen.resource().endsWith(".fxml"));
            assertNotNull(screen.title());
            assertFalse(screen.title().isBlank());
        }
    }
}
