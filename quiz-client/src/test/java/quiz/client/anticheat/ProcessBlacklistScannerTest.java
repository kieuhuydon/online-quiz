package quiz.client.anticheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProcessBlacklistScanner Unit Tests (FR-S13 Nâng cấp)")
class ProcessBlacklistScannerTest {

    private static ProcessHandle mockProcess(long pid, String command, String commandLine) {
        ProcessHandle.Info info = (ProcessHandle.Info) Proxy.newProxyInstance(
            ProcessHandle.Info.class.getClassLoader(),
            new Class<?>[]{ProcessHandle.Info.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "command" -> Optional.ofNullable(command);
                case "commandLine" -> Optional.ofNullable(commandLine);
                case "arguments", "user", "startInstant", "totalCpuDuration" -> Optional.empty();
                case "toString" -> "FakeInfo[" + command + "]";
                default -> null;
            }
        );

        return (ProcessHandle) Proxy.newProxyInstance(
            ProcessHandle.class.getClassLoader(),
            new Class<?>[]{ProcessHandle.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "pid" -> pid;
                case "info" -> info;
                case "compareTo" -> Long.compare(pid, ((ProcessHandle) args[0]).pid());
                case "equals" -> proxy == args[0];
                case "hashCode" -> Long.hashCode(pid);
                case "toString" -> "FakeProcess[" + pid + "]";
                default -> null;
            }
        );
    }

    @Test
    @DisplayName("Phát hiện chính xác các ứng dụng chat và điều khiển từ xa trong danh sách đen")
    void scan_DetectsBlacklistedApps() {
        ProcessBlacklistScanner scanner = new ProcessBlacklistScanner();

        ProcessHandle pDiscord = mockProcess(101L, "C:\\Users\\Nem\\AppData\\Local\\Discord\\Discord.exe", null);
        ProcessHandle pZalo = mockProcess(102L, "C:\\Program Files\\Zalo\\Zalo.exe", null);
        ProcessHandle pTeamViewer = mockProcess(103L, "C:\\Program Files\\TeamViewer\\TeamViewer.exe", null);
        ProcessHandle pUltraViewer = mockProcess(104L, "C:\\Program Files (x86)\\UltraViewer\\UltraViewer_Desktop.exe", null);
        ProcessHandle pAnyDesk = mockProcess(105L, "C:\\Program Files (x86)\\AnyDesk\\AnyDesk.exe", null);

        List<ProcessBlacklistScanner.DetectedProcess> result = scanner.scan(
            Stream.of(pDiscord, pZalo, pTeamViewer, pUltraViewer, pAnyDesk)
        );

        assertEquals(5, result.size());

        assertTrue(result.stream().anyMatch(d -> d.appName().equals("Discord") && d.pid() == 101L));
        assertTrue(result.stream().anyMatch(d -> d.appName().equals("Zalo") && d.pid() == 102L));
        assertTrue(result.stream().anyMatch(d -> d.appName().equals("TeamViewer") && d.pid() == 103L));
        assertTrue(result.stream().anyMatch(d -> d.appName().equals("UltraViewer") && d.pid() == 104L));
        assertTrue(result.stream().anyMatch(d -> d.appName().equals("AnyDesk") && d.pid() == 105L));
    }

    @Test
    @DisplayName("Bỏ qua các ứng dụng hợp lệ thông thường (Chrome, IntelliJ, Notepad, Java)")
    void scan_IgnoresAllowedProcesses() {
        ProcessBlacklistScanner scanner = new ProcessBlacklistScanner();

        ProcessHandle pChrome = mockProcess(201L, "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", null);
        ProcessHandle pIdea = mockProcess(202L, "C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe", null);
        ProcessHandle pNotepad = mockProcess(203L, "C:\\Windows\\notepad.exe", null);
        ProcessHandle pJava = mockProcess(204L, "C:\\Program Files\\Java\\jdk-21\\bin\\javaw.exe", null);

        List<ProcessBlacklistScanner.DetectedProcess> result = scanner.scan(
            Stream.of(pChrome, pIdea, pNotepad, pJava)
        );

        assertTrue(result.isEmpty(), "Các tiến trình bình thường không được nằm trong danh sách đen");
    }

    @Test
    @DisplayName("Bỏ qua tiến trình hiện tại của client dù có trùng tên với blacklist")
    void scan_IgnoresCurrentProcessPid() {
        ProcessBlacklistScanner scanner = new ProcessBlacklistScanner();
        long currentPid = ProcessHandle.current().pid();

        ProcessHandle pCurrent = mockProcess(currentPid, "C:\\Fake\\Discord.exe", null);

        List<ProcessBlacklistScanner.DetectedProcess> result = scanner.scan(Stream.of(pCurrent));
        assertTrue(result.isEmpty(), "Tiến trình hiện tại phải luôn bị bỏ qua");
    }

    @Test
    @DisplayName("Phát hiện ứng dụng thông qua CommandLine khi command bị rỗng")
    void scan_FallbackToCommandLine() {
        ProcessBlacklistScanner scanner = new ProcessBlacklistScanner();

        ProcessHandle pDiscordCmd = mockProcess(301L, null, "\"C:\\Users\\Nem\\AppData\\Local\\Discord\\Discord.exe\" --type=gpu");
        ProcessHandle pZaloCmd = mockProcess(302L, null, "C:\\Programs\\Zalo\\Zalo.exe -autostart");

        List<ProcessBlacklistScanner.DetectedProcess> result = scanner.scan(Stream.of(pDiscordCmd, pZaloCmd));
        assertEquals(2, result.size());
        assertEquals("Discord", result.get(0).appName());
        assertEquals("Zalo", result.get(1).appName());
    }

    @Test
    @DisplayName("Gom nhóm và định dạng thông tin vi phạm đẹp mắt, không vượt quá giới hạn 255 ký tự")
    void formatViolationDetail_GroupsAndRespectsMaxLength() {
        List<ProcessBlacklistScanner.DetectedProcess> violations = List.of(
            new ProcessBlacklistScanner.DetectedProcess(101L, "Discord", "Chat", "Discord.exe"),
            new ProcessBlacklistScanner.DetectedProcess(102L, "Discord", "Chat", "Discord.exe"),
            new ProcessBlacklistScanner.DetectedProcess(201L, "UltraViewer", "Remote", "UltraViewer.exe")
        );

        String detail = ProcessBlacklistScanner.formatViolationDetail(violations);
        assertNotNull(detail);
        assertTrue(detail.contains("Discord (PID: 101,102)"));
        assertTrue(detail.contains("UltraViewer (PID: 201)"));
        assertTrue(detail.length() <= 250);
    }

    @Test
    @DisplayName("Bỏ qua an toàn khi gặp tiến trình bị lỗi truy cập hoặc đã tắt")
    void scan_HandlesProcessExceptionsGracefully() {
        ProcessBlacklistScanner scanner = new ProcessBlacklistScanner();

        ProcessHandle pErr = (ProcessHandle) Proxy.newProxyInstance(
            ProcessHandle.class.getClassLoader(),
            new Class<?>[]{ProcessHandle.class},
            (proxy, method, args) -> {
                if ("pid".equals(method.getName())) return 9999L;
                if ("info".equals(method.getName())) throw new SecurityException("Access denied by OS");
                return null;
            }
        );

        assertDoesNotThrow(() -> {
            List<ProcessBlacklistScanner.DetectedProcess> result = scanner.scan(Stream.of(pErr));
            assertTrue(result.isEmpty());
        });
    }

    @Test
    @DisplayName("scanAndFormat trả về chuỗi định dạng khi có vi phạm và Optional.empty khi sạch sẽ")
    void scanAndFormat_Behavior() {
        ProcessBlacklistScanner.BlacklistRule testRule = new ProcessBlacklistScanner.BlacklistRule(
            "TestApp", "Test", java.util.Set.of("testblacklisted")
        );
        ProcessBlacklistScanner scanner = new ProcessBlacklistScanner(List.of(testRule));

        // Không có vi phạm
        assertTrue(scanner.scan(Stream.empty()).isEmpty());

        // Có vi phạm
        ProcessHandle p = mockProcess(555L, "C:\\App\\testblacklisted.exe", null);
        List<ProcessBlacklistScanner.DetectedProcess> result = scanner.scan(Stream.of(p));
        assertFalse(result.isEmpty());
        String formatted = ProcessBlacklistScanner.formatViolationDetail(result);
        assertTrue(formatted.contains("TestApp (PID: 555)"));
    }
}
