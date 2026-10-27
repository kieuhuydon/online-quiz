package quiz.client.anticheat;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Quét danh sách tiến trình đang chạy trên hệ thống sử dụng Java ProcessHandle API (FR-S13 nâng cấp).
 * Phát hiện các phần mềm chat hoặc điều khiển máy tính từ xa bị cấm (Zalo, Discord, TeamViewer, UltraViewer, AnyDesk...).
 */
public class ProcessBlacklistScanner {

    public record BlacklistRule(String appName, String category, Set<String> keywords) {
        public BlacklistRule {
            Objects.requireNonNull(appName, "appName cannot be null");
            Objects.requireNonNull(category, "category cannot be null");
            Objects.requireNonNull(keywords, "keywords cannot be null");
        }

        public boolean matches(String processIdentifier) {
            if (processIdentifier == null || processIdentifier.isBlank()) {
                return false;
            }
            String lower = processIdentifier.toLowerCase(Locale.ROOT);
            for (String kw : keywords) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }

    public record DetectedProcess(long pid, String appName, String category, String processIdentifier) {
        public DetectedProcess {
            Objects.requireNonNull(appName, "appName cannot be null");
            Objects.requireNonNull(category, "category cannot be null");
        }
    }

    public static final List<BlacklistRule> DEFAULT_RULES = List.of(
        new BlacklistRule("Zalo", "Chat / Nhắn tin", Set.of("zalo")),
        new BlacklistRule("Discord", "Chat / Voice / Chia sẻ màn hình", Set.of("discord")),
        new BlacklistRule("TeamViewer", "Điều khiển từ xa", Set.of("teamviewer")),
        new BlacklistRule("UltraViewer", "Điều khiển từ xa", Set.of("ultraviewer")),
        new BlacklistRule("AnyDesk", "Điều khiển từ xa", Set.of("anydesk")),
        new BlacklistRule("Telegram", "Chat / Nhắn tin", Set.of("telegram")),
        new BlacklistRule("Skype", "Chat / Voice", Set.of("skype"))
    );

    private final List<BlacklistRule> rules;

    public ProcessBlacklistScanner() {
        this(DEFAULT_RULES);
    }

    public ProcessBlacklistScanner(List<BlacklistRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules cannot be null"));
    }

    /**
     * Quét nhanh toàn bộ tiến trình hệ thống bằng ProcessHandle.allProcesses().
     *
     * @return danh sách các tiến trình vi phạm phát hiện được
     */
    public List<DetectedProcess> scan() {
        return scan(ProcessHandle.allProcesses());
    }

    /**
     * Quét trên một Stream ProcessHandle (hỗ trợ kiểm thử và quét tùy chỉnh).
     */
    public List<DetectedProcess> scan(Stream<ProcessHandle> processStream) {
        Objects.requireNonNull(processStream, "processStream cannot be null");
        long currentPid = ProcessHandle.current().pid();
        List<DetectedProcess> violations = new ArrayList<>();

        processStream.forEach(ph -> {
            try {
                if (ph.pid() == currentPid) {
                    return;
                }
                ProcessHandle.Info info = ph.info();
                String rawName = extractProcessIdentifier(info);
                if (rawName == null || rawName.isBlank()) {
                    return;
                }
                for (BlacklistRule rule : rules) {
                    if (rule.matches(rawName)) {
                        violations.add(new DetectedProcess(ph.pid(), rule.appName(), rule.category(), rawName));
                        break;
                    }
                }
            } catch (Exception ignored) {
                // Bỏ qua các tiến trình không có quyền truy cập hoặc đã kết thúc
            }
        });

        return violations;
    }

    /**
     * Quét và trả về chuỗi chi tiết vi phạm nếu có tiến trình cấm đang chạy.
     */
    public Optional<String> scanAndFormat() {
        List<DetectedProcess> violations = scan();
        if (violations.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(formatViolationDetail(violations));
    }

    /**
     * Trích xuất tên tệp thực thi hoặc định danh từ ProcessHandle.Info.
     */
    public static String extractProcessIdentifier(ProcessHandle.Info info) {
        if (info == null) return null;
        if (info.command().isPresent()) {
            return extractFileName(info.command().get());
        }
        if (info.commandLine().isPresent()) {
            return extractFileNameFromCommandLine(info.commandLine().get());
        }
        return null;
    }

    private static String extractFileName(String path) {
        if (path == null || path.isBlank()) return "";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    private static String extractFileNameFromCommandLine(String cmdLine) {
        if (cmdLine == null || cmdLine.isBlank()) return "";
        String trimmed = cmdLine.trim();
        String firstToken = trimmed;
        if (trimmed.startsWith("\"")) {
            int closeQuote = trimmed.indexOf('"', 1);
            if (closeQuote > 0) {
                firstToken = trimmed.substring(1, closeQuote);
            }
        } else {
            int space = trimmed.indexOf(' ');
            if (space > 0) {
                firstToken = trimmed.substring(0, space);
            }
        }
        return extractFileName(firstToken);
    }

    /**
     * Định dạng chi tiết vi phạm để lưu vào cơ sở dữ liệu và gửi cảnh báo.
     * Gom nhóm theo ứng dụng và rút gọn PID, đảm bảo không vượt quá giới hạn VARCHAR(255).
     */
    public static String formatViolationDetail(List<DetectedProcess> violations) {
        if (violations == null || violations.isEmpty()) {
            return "";
        }
        Map<String, List<Long>> grouped = violations.stream()
            .collect(Collectors.groupingBy(
                DetectedProcess::appName,
                LinkedHashMap::new,
                Collectors.mapping(DetectedProcess::pid, Collectors.toList())
            ));

        StringBuilder sb = new StringBuilder("Phát hiện ứng dụng cấm: ");
        boolean first = true;
        for (var entry : grouped.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.getKey()).append(" (PID: ");
            List<Long> pids = entry.getValue();
            if (pids.size() <= 3) {
                sb.append(pids.stream().map(String::valueOf).collect(Collectors.joining(",")));
            } else {
                sb.append(pids.get(0)).append(",... +").append(pids.size() - 1);
            }
            sb.append(")");
        }
        String detail = sb.toString();
        if (detail.length() > 250) {
            detail = detail.substring(0, 247) + "...";
        }
        return detail;
    }
}

