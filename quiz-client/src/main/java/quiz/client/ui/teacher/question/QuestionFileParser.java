package quiz.client.ui.teacher.question;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import quiz.client.api.ClientValidation;
import quiz.common.dto.CreateQuestionDto;
import quiz.common.enums.Difficulty;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bộ phân tích và trích xuất câu hỏi trắc nghiệm từ nhiều định dạng tệp:
 * DOCX (Word), PDF, TXT, CSV.
 *
 * Hỗ trợ nhận diện thông minh nội dung câu hỏi, 4 đáp án A-D, đáp án đúng,
 * và tự động phân loại chủ đề bài học tiếng Anh (Các thì, Câu điều kiện, Câu bị động,...)
 * cùng với độ khó phù hợp.
 */
public final class QuestionFileParser {

    private static final Pattern CSV_SPLIT = Pattern.compile(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

    // Pattern nhận diện tiêu đề câu hỏi: Câu 1, Câu 1:, Câu 1., Question 1:, Q1., Bài 1:, 1., 1/, 1:
    private static final Pattern QUESTION_START = Pattern.compile(
        "^(?:câu\\s*\\d+|question\\s*\\d+|câu\\s+hỏi\\s*\\d+|q\\s*\\d+|bài\\s*\\d+|\\d+)[\\.\\:\\)\\-\\s]\\s*(.*)$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Pattern nhận diện các lựa chọn: A. A) [A] A: ...
    private static final Pattern OPTION_A = Pattern.compile("^(?:[\\(\\[]?A[\\.\\]\\):\\-]\\s*|A\\.\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION_B = Pattern.compile("^(?:[\\(\\[]?B[\\.\\]\\):\\-]\\s*|B\\.\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION_C = Pattern.compile("^(?:[\\(\\[]?C[\\.\\]\\):\\-]\\s*|C\\.\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION_D = Pattern.compile("^(?:[\\(\\[]?D[\\.\\]\\):\\-]\\s*|D\\.\\s*)(.*)$", Pattern.CASE_INSENSITIVE);

    // Pattern 4 đáp án nằm cùng trên 1 dòng: A. xxx B. yyy C. zzz D. ttt
    private static final Pattern INLINE_OPTIONS = Pattern.compile(
        "(?:^|\\s+)[\\(\\[]?A[\\.\\]\\):\\-]\\s*(.+?)\\s+[\\(\\[]?B[\\.\\]\\):\\-]\\s*(.+?)\\s+[\\(\\[]?C[\\.\\]\\):\\-]\\s*(.+?)\\s+[\\(\\[]?D[\\.\\]\\):\\-]\\s*(.+)$",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern 2 đáp án nằm trên 1 dòng: A. xxx B. yyy hoặc C. xxx D. yyy
    private static final Pattern INLINE_PAIR_AB = Pattern.compile(
        "^[\\(\\[]?A[\\.\\]\\):\\-]\\s*(.+?)\\s+[\\(\\[]?B[\\.\\]\\):\\-]\\s*(.+)$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INLINE_PAIR_CD = Pattern.compile(
        "^[\\(\\[]?C[\\.\\]\\):\\-]\\s*(.+?)\\s+[\\(\\[]?D[\\.\\]\\):\\-]\\s*(.+)$",
        Pattern.CASE_INSENSITIVE
    );

    // Pattern nhận diện dòng đáp án đúng: Đáp án: A, Answer: B, Key: C, ĐA: D...
    private static final Pattern ANSWER_LINE = Pattern.compile(
        "^(?:đáp\\s*án|answer|key|đáp\\s*án\\s*đúng|chọn|correct\\s*answer|đa)\\s*[\\:\\=\\-\\.]?\\s*([A-D])\\b.*$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Pattern nhận diện dòng chủ đề tường minh
    private static final Pattern EXPLICIT_TOPIC = Pattern.compile(
        "^(?:chủ\\s*đề|topic|bài\\s*học|chuyên\\s*đề|lesson|unit)\\s*[\\:\\=\\-]\\s*(.+)$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Pattern nhận diện dòng độ khó tường minh
    private static final Pattern EXPLICIT_DIFF = Pattern.compile(
        "^(?:độ\\s*khó|difficulty|mức\\s*độ)\\s*[\\:\\=\\-]\\s*(.+)$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Từ điển nhận diện chủ đề bài học tiếng Anh thông minh
    private static final Map<String, Pattern[]> ENGLISH_TOPICS = new LinkedHashMap<>();

    static {
        // 1. Câu điều kiện
        ENGLISH_TOPICS.put("Câu điều kiện (Conditionals)", new Pattern[]{
            Pattern.compile("\\bif\\s+(?:I|you|he|she|it|we|they|there|would|had|were|did)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:unless|as\\s+long\\s+as|provided\\s+that|in\\s+case|wish|if\\s+only)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:câu\\s*điều\\s*kiện|conditional|loại\\s*[123]|type\\s*[123])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\bwould\\s+have\\s+[a-z]+ed\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bhad\\s+[a-z]+ed\\b.*\\bwould\\b", Pattern.CASE_INSENSITIVE)
        });

        // 2. Câu bị động (Passive Voice)
        ENGLISH_TOPICS.put("Câu bị động (Passive Voice)", new Pattern[]{
            Pattern.compile("(?:bị\\s*động|passive\\s*voice)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:is|are|was|were|been|being)\\s+(?:built|written|made|done|taken|given|discovered|invented|caused|held|cleaned)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:is|are|was|were)\\s+[a-z]+ed\\s+by\\b", Pattern.CASE_INSENSITIVE)
        });

        // 3. Câu gián tiếp / Tường thuật
        ENGLISH_TOPICS.put("Câu gián tiếp (Reported Speech)", new Pattern[]{
            Pattern.compile("(?:gián\\s*tiếp|tường\\s*thuật|reported\\s*speech|indirect\\s*speech)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:said\\s+that|told\\s+me|asked\\s+if|asked\\s+whether|wondered\\s+if)\\b", Pattern.CASE_INSENSITIVE)
        });

        // 4. Mệnh đề quan hệ
        ENGLISH_TOPICS.put("Mệnh đề quan hệ (Relative Clauses)", new Pattern[]{
            Pattern.compile("(?:mệnh\\s*đề\\s*quan\\s*hệ|relative\\s*clause)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:who|whom|whose|which|that|where|when|why)\\b.*\\?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bThe\\s+[a-z]+\\s+(?:who|whom|which|whose|that)\\b", Pattern.CASE_INSENSITIVE)
        });

        // 5. Câu hỏi đuôi
        ENGLISH_TOPICS.put("Câu hỏi đuôi (Tag Questions)", new Pattern[]{
            Pattern.compile("(?:câu\\s*hỏi\\s*đuôi|tag\\s*question)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile(",\\s*(?:isn't|aren't|wasn't|weren't|don't|doesn't|didn't|won't|can't|haven't|hasn't|do|does|did|is|are|was|were|will|can)\\s+(?:it|he|she|they|you|we|I)\\s*\\?.*$", Pattern.CASE_INSENSITIVE)
        });

        // 6. Động từ khuyết thiếu
        ENGLISH_TOPICS.put("Động từ khuyết thiếu (Modal Verbs)", new Pattern[]{
            Pattern.compile("(?:động\\s*từ\\s*khuyết\\s*thiếu|modal\\s*verbs?)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:must|mustn't|should|shouldn't|ought\\s+to|can't\\s+have|may\\s+have|might\\s+have)\\b", Pattern.CASE_INSENSITIVE)
        });

        // 7. Câu so sánh
        ENGLISH_TOPICS.put("Câu so sánh (Comparisons)", new Pattern[]{
            Pattern.compile("(?:so\\s*sánh|comparative|superlative|comparison)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:more\\s+[a-z]+\\s+than|as\\s+[a-z]+\\s+as|the\\s+most\\s+[a-z]+|the\\s+best|the\\s+worst)\\b", Pattern.CASE_INSENSITIVE)
        });

        // 8. Thì hoàn thành (Hiện tại / Quá khứ hoàn thành)
        ENGLISH_TOPICS.put("Thì hoàn thành (Perfect Tenses)", new Pattern[]{
            Pattern.compile("(?:hoàn\\s*thành|perfect\\s*tense|present\\s*perfect|past\\s*perfect)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:since|for\\s+\\d+|already|yet|just|ever|never|so\\s+far|up\\s+to\\s+now|by\\s+the\\s+time)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:have|has|had)\\s+(?:been|never|ever|already|just|[a-z]+ed|[a-z]+en|left|gone|done|seen|written|taken)\\b", Pattern.CASE_INSENSITIVE)
        });

        // 9. Thì quá khứ (Quá khứ đơn & tiếp diễn)
        ENGLISH_TOPICS.put("Thì quá khứ (Past Tenses)", new Pattern[]{
            Pattern.compile("(?:quá\\s*khứ|past\\s*simple|past\\s*continuous|past\\s*tense)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:yesterday|ago|last\\s+night|last\\s+week|last\\s+year|in\\s+19\\d\\d|in\\s+20[01]\\d)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwhile\\s+(?:I|he|she|they|we)\\s+was\\b", Pattern.CASE_INSENSITIVE)
        });

        // 10. Thì tương lai (Future Tenses)
        ENGLISH_TOPICS.put("Thì tương lai (Future Tenses)", new Pattern[]{
            Pattern.compile("(?:tương\\s*lai|future\\s*tense|future\\s*simple)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:tomorrow|next\\s+week|next\\s+month|next\\s+year|in\\s+the\\s+future)\\b", Pattern.CASE_INSENSITIVE)
        });

        // 11. Thì hiện tại (Hiện tại đơn & tiếp diễn)
        ENGLISH_TOPICS.put("Thì hiện tại (Present Tenses)", new Pattern[]{
            Pattern.compile("(?:hiện\\s*tại|present\\s*simple|present\\s*continuous|present\\s*tense)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:always|usually|often|sometimes|every\\s+day|at\\s+the\\s+moment|now|right\\s+now)\\b", Pattern.CASE_INSENSITIVE)
        });

        // 12. Danh động từ & Động từ nguyên mẫu
        ENGLISH_TOPICS.put("Danh động từ & Động từ nguyên mẫu (Gerund & Infinitive)", new Pattern[]{
            Pattern.compile("(?:gerund|infinitive|danh\\s*động\\s*từ|nguyên\\s*mẫu)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:enjoy|avoid|mind|look\\s+forward\\s+to|suggest|decide\\s+to|hope\\s+to|refuse\\s+to)\\b", Pattern.CASE_INSENSITIVE)
        });

        // 13. Giới từ & Cụm động từ
        ENGLISH_TOPICS.put("Giới từ & Cụm động từ (Prepositions & Phrasal Verbs)", new Pattern[]{
            Pattern.compile("(?:giới\\s*từ|preposition|phrasal\\s*verb|cụm\\s*động\\s*từ)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("\\b(?:look\\s+after|give\\s+up|turn\\s+on|turn\\s+off|interested\\s+in|fond\\s+of|good\\s+at)\\b", Pattern.CASE_INSENSITIVE)
        });

        // 14. Từ vựng & Đọc hiểu
        ENGLISH_TOPICS.put("Từ vựng (Vocabulary)", new Pattern[]{
            Pattern.compile("(?:từ\\s*vựng|vocabulary|synonym|antonym|closest\\s+in\\s+meaning|opposite\\s+in\\s+meaning)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
        });
    }

    private QuestionFileParser() {}

    /**
     * Phân tích tệp bất kỳ (.docx, .pdf, .txt, .csv) thành danh sách CreateQuestionDto.
     */
    public static List<CreateQuestionDto> parse(File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Tệp không tồn tại.");
        }
        String fileName = file.getName().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".docx")) {
            return parseDocx(file);
        } else if (fileName.endsWith(".pdf")) {
            return parsePdf(file);
        } else if (fileName.endsWith(".csv")) {
            return parseCsv(file);
        } else {
            // Mặc định xử lý dạng văn bản (txt và các tệp text khác)
            return parsePlainText(file);
        }
    }

    // ==========================================
    // 1. Phân tích tệp DOCX (Word)
    // ==========================================
    private static List<CreateQuestionDto> parseDocx(File file) throws Exception {
        List<String> rawLines = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis)) {

            for (IBodyElement elem : doc.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    StringBuilder paraText = new StringBuilder();
                    for (org.apache.poi.xwpf.usermodel.XWPFRun run : para.getRuns()) {
                        String runText = run.getText(0);
                        if (runText == null) continue;
                        // Kiểm tra nếu run có thuộc tính gạch chân (Underline) nhưng text chỉ là khoảng trắng (Word thường tạo chỗ trống bằng underline spaces)
                        if (run.getUnderline() != null && run.getUnderline() != org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE) {
                            if (runText.isBlank()) {
                                paraText.append(" ___ ");
                                continue;
                            }
                        }
                        paraText.append(runText);
                    }
                    String text = paraText.length() > 0 ? paraText.toString() : para.getText();
                    if (text != null && !text.isBlank()) {
                        rawLines.add(text.trim());
                    }
                } else if (elem instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String ct = cell.getText();
                            if (ct != null && !ct.isBlank()) {
                                cells.add(ct.trim());
                            }
                        }
                        if (!cells.isEmpty()) {
                            rawLines.add(String.join(" | ", cells));
                        }
                    }
                }
            }
        }
        return parseLinesToQuestions(rawLines);
    }

    // ==========================================
    // 2. Phân tích tệp PDF
    // ==========================================
    private static List<CreateQuestionDto> parsePdf(File file) throws Exception {
        List<String> rawLines = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String fullText = stripper.getText(document);
            if (fullText != null) {
                String[] lines = fullText.split("\\r?\\n");
                for (String l : lines) {
                    String trimmed = l.trim();
                    if (!trimmed.isEmpty()) {
                        rawLines.add(trimmed);
                    }
                }
            }
        }
        return parseLinesToQuestions(rawLines);
    }

    // ==========================================
    // 3. Phân tích tệp TXT
    // ==========================================
    private static List<CreateQuestionDto> parsePlainText(File file) throws Exception {
        List<String> rawLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    rawLines.add(trimmed);
                } else {
                    rawLines.add(""); // Giữ lại dòng trống để làm ranh giới giữa các khối câu hỏi nếu cần
                }
            }
        }
        return parseLinesToQuestions(rawLines);
    }

    // ==========================================
    // 4. Phân tích tệp CSV
    // ==========================================
    private static List<CreateQuestionDto> parseCsv(File file) throws Exception {
        List<CreateQuestionDto> results = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = CSV_SPLIT.split(line);
                if (parts.length >= 6) {
                    String content = cleanCsvField(parts[0]);
                    if (content.equalsIgnoreCase("content") || content.equalsIgnoreCase("nội dung")
                        || content.equalsIgnoreCase("câu hỏi") || content.equalsIgnoreCase("question")) {
                        continue; // Bỏ qua dòng tiêu đề CSV
                    }
                    String a = cleanCsvField(parts[1]);
                    String b = cleanCsvField(parts[2]);
                    String c = cleanCsvField(parts[3]);
                    String d = cleanCsvField(parts[4]);
                    String correctStr = cleanCsvField(parts[5]).toUpperCase(Locale.ROOT);
                    char correct = !correctStr.isEmpty() ? correctStr.charAt(0) : 'A';
                    if (correct < 'A' || correct > 'D') correct = 'A';

                    content = normalizeBlanks(content);

                    String topic = parts.length > 6 ? cleanCsvField(parts[6]) : null;
                    if (topic == null || topic.isBlank() || topic.equalsIgnoreCase("Tổng hợp")) {
                        topic = detectEnglishTopic(content + " " + a + " " + b + " " + c + " " + d);
                    }

                    Difficulty diff = Difficulty.MEDIUM;
                    if (parts.length > 7) {
                        try {
                            diff = Difficulty.valueOf(cleanCsvField(parts[7]).toUpperCase(Locale.ROOT));
                        } catch (Exception ignored) {}
                    } else {
                        diff = detectDifficulty(content, a, b, c, d);
                    }

                    try {
                        results.add(ClientValidation.question(new CreateQuestionDto(content, a, b, c, d, correct, topic, diff)));
                    } catch (Exception ignored) {}
                }
            }
        }
        return results;
    }

    private static String cleanCsvField(String field) {
        if (field == null) return "";
        field = field.trim();
        if (field.startsWith("\"") && field.endsWith("\"") && field.length() >= 2) {
            field = field.substring(1, field.length() - 1).replace("\"\"", "\"");
        }
        return field.trim();
    }

    // ==========================================
    // Bộ phân giải văn bản tự do thành danh sách câu hỏi
    // ==========================================
    private static List<CreateQuestionDto> parseLinesToQuestions(List<String> lines) {
        List<CreateQuestionDto> results = new ArrayList<>();

        QuestionBlock current = new QuestionBlock();
        String activeGlobalTopic = null;
        Difficulty activeGlobalDiff = null;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (line.isEmpty()) {
                // Kết thúc khối câu hỏi nếu đã có đủ 4 lựa chọn
                if (current.isComplete()) {
                    commitQuestion(results, current, activeGlobalTopic, activeGlobalDiff);
                    current = new QuestionBlock();
                }
                continue;
            }

            if (line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            // Kiểm tra chỉ định chủ đề toàn cục hoặc từng khối
            Matcher topicMatch = EXPLICIT_TOPIC.matcher(line);
            if (topicMatch.matches()) {
                String t = topicMatch.group(1).trim();
                if (current.isEmpty()) {
                    activeGlobalTopic = t;
                } else {
                    current.explicitTopic = t;
                }
                continue;
            }

            // Kiểm tra chỉ định độ khó toàn cục hoặc từng khối
            Matcher diffMatch = EXPLICIT_DIFF.matcher(line);
            if (diffMatch.matches()) {
                String d = diffMatch.group(1).trim().toUpperCase(Locale.ROOT);
                Difficulty parsedDiff = parseDifficulty(d);
                if (current.isEmpty()) {
                    activeGlobalDiff = parsedDiff;
                } else {
                    current.explicitDifficulty = parsedDiff;
                }
                continue;
            }

            // Kiểm tra dòng đáp án đúng (Key / Answer / Đáp án)
            Matcher ansMatch = ANSWER_LINE.matcher(line);
            if (ansMatch.matches()) {
                current.correctOption = ansMatch.group(1).toUpperCase(Locale.ROOT).charAt(0);
                continue;
            }

            // Kiểm tra dạng 4 đáp án nằm cùng trên 1 dòng
            Matcher inline4 = INLINE_OPTIONS.matcher(line);
            if (inline4.matches()) {
                current.optA = cleanOptionText(inline4.group(1));
                current.optB = cleanOptionText(inline4.group(2));
                current.optC = cleanOptionText(inline4.group(3));
                current.optD = cleanOptionText(inline4.group(4));
                continue;
            }

            // Kiểm tra dạng 2 đáp án A & B trên 1 dòng
            Matcher pairAB = INLINE_PAIR_AB.matcher(line);
            if (pairAB.matches()) {
                current.optA = cleanOptionText(pairAB.group(1));
                current.optB = cleanOptionText(pairAB.group(2));
                continue;
            }

            // Kiểm tra dạng 2 đáp án C & D trên 1 dòng
            Matcher pairCD = INLINE_PAIR_CD.matcher(line);
            if (pairCD.matches()) {
                current.optC = cleanOptionText(pairCD.group(1));
                current.optD = cleanOptionText(pairCD.group(2));
                continue;
            }

            // Kiểm tra lựa chọn đơn lẻ A, B, C, D
            Matcher matchA = OPTION_A.matcher(line);
            if (matchA.matches()) {
                current.optA = cleanOptionText(matchA.group(1));
                continue;
            }
            Matcher matchB = OPTION_B.matcher(line);
            if (matchB.matches()) {
                current.optB = cleanOptionText(matchB.group(1));
                continue;
            }
            Matcher matchC = OPTION_C.matcher(line);
            if (matchC.matches()) {
                current.optC = cleanOptionText(matchC.group(1));
                continue;
            }
            Matcher matchD = OPTION_D.matcher(line);
            if (matchD.matches()) {
                current.optD = cleanOptionText(matchD.group(1));
                continue;
            }

            // Kiểm tra xem dòng này có phải bắt đầu câu hỏi mới không
            Matcher qStart = QUESTION_START.matcher(line);
            if (qStart.matches()) {
                // Nếu câu hỏi trước đó đã hoàn thành hoặc có nội dung, chốt câu cũ
                if (current.isComplete()) {
                    commitQuestion(results, current, activeGlobalTopic, activeGlobalDiff);
                    current = new QuestionBlock();
                } else if (current.content != null && current.hasAnyOption()) {
                    // Câu cũ chưa đủ option nhưng đã xuất hiện câu mới -> chốt nếu có thể
                    commitQuestion(results, current, activeGlobalTopic, activeGlobalDiff);
                    current = new QuestionBlock();
                }

                current.content = qStart.group(1).trim();
                if (current.content.isEmpty()) {
                    // Nếu dòng chỉ ghi "Câu 1:" mà nội dung nằm ở dòng tiếp theo
                    if (i + 1 < lines.size()) {
                        current.content = lines.get(++i).trim();
                    }
                }
                continue;
            }

            // Nếu chưa rơi vào trường hợp nào trên:
            if (current.optD != null) {
                // Đã có đến D mà có thêm dòng mới (có thể là giải thích hoặc đáp án)
                if (line.toUpperCase(Locale.ROOT).contains("ĐÁP ÁN") || line.toUpperCase(Locale.ROOT).contains("ANSWER")) {
                    for (char c : new char[]{'A', 'B', 'C', 'D'}) {
                        if (line.toUpperCase(Locale.ROOT).contains(" " + c) || line.toUpperCase(Locale.ROOT).endsWith(":" + c)) {
                            current.correctOption = c;
                            break;
                        }
                    }
                }
            } else if (current.optA != null) {
                // Đang đọc giữa các option, dòng này có thể là nối tiếp của option vừa đọc
                if (current.optC != null) current.optC += " " + line;
                else if (current.optB != null) current.optB += " " + line;
                else current.optA += " " + line;
            } else if (current.content != null) {
                // Nối tiếp nội dung câu hỏi
                current.content += " " + line;
            } else {
                // Bắt đầu một câu hỏi không có tiền tố "Câu X"
                current.content = line;
            }
        }

        // Chốt câu hỏi cuối cùng
        if (current.content != null) {
            commitQuestion(results, current, activeGlobalTopic, activeGlobalDiff);
        }

        return results;
    }

    /**
     * Chuẩn hóa và tự động bổ sung vị trí điền khuyết (dấu "___") nếu câu hỏi là dạng điền từ vào chỗ trống
     * nhưng trong văn bản gốc bị thiếu hoặc bị biến đổi ký tự (ví dụ: ......, ------, [...], (....), khoảng trắng thừa).
     */
    public static String normalizeBlanks(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        String res = content.trim();

        // 1. Chuẩn hóa các dạng gạch chân, dấu chấm, dấu ngoặc trống thành "___":
        // Các ký tự gạch chân unicode (em dash, en dash, fullwidth underscore, v.v.):
        res = res.replace('\uFF3F', '_')
                 .replace('\u2013', '-')
                 .replace('\u2014', '-')
                 .replace('\u2026', '.');

        // Các dạng gạch dưới dài/ngắn: _, __, ____, ...
        res = res.replaceAll("_{1,}", "___");

        // Các dạng dấu chấm liên tiếp: .., ..., .....
        res = res.replaceAll("\\.{2,}", "___");

        // Các dạng gạch ngang liên tiếp: --, ---, ----
        res = res.replaceAll("-{2,}", "___");

        // Các dạng dấu chấm giữa dòng dạng tiếng Anh: ···, •••, …
        res = res.replaceAll("[·•]{2,}", "___");

        // Các dạng ngoặc trống hoặc ngoặc chứa chấm/gạch: ( ), [ ], ( ... ), [ ... ], ( ___ ), [ ___ ], (...)
        res = res.replaceAll("\\(\\s*(?:\\.*|_{1,}|-{1,}|\\s*)\\)", "___");
        res = res.replaceAll("\\[\\s*(?:\\.*|_{1,}|-{1,}|\\s*)\\]", "___");
        res = res.replaceAll("\\(\\s*___+\\s*\\)", "___");
        res = res.replaceAll("\\[\\s*___+\\s*\\]", "___");

        // 2. Nếu đã có ít nhất một "___", chuẩn hóa khoảng cách xung quanh
        if (res.contains("___")) {
            // Đảm bảo có dấu cách hợp lý trước và sau ___
            res = res.replaceAll("\\s*___+\\s*", " ___ ");
            return res.replaceAll("\\s{2,}", " ").trim();
        }

        // 3. Nếu là câu hỏi có 2 khoảng trắng liên tiếp trở lên (PDF/DOCX thường xóa underscore thành 2-3 dấu cách)
        if (res.matches(".*[a-zA-Z0-9,;]\\s{2,}[a-zA-Z0-9].*")) {
            res = res.replaceAll("(?<=[a-zA-Z0-9,;])\\s{2,}(?=[a-zA-Z0-9])", " ___ ");
            return res.replaceAll("\\s{2,}", " ").trim();
        }

        // 4. Nếu câu hỏi không có bất kỳ dấu blank nào:
        // TH 4a: Câu kết thúc lửng mà không có dấu chấm câu, hoặc kết thúc bởi từ nối/giới từ/động từ
        // Ví dụ: "If he had invited me, I would" hoặc "The book was written by"
        if (!res.endsWith("?") && !res.contains("?")) {
            if (res.endsWith(".")) {
                // Kiểm tra xem câu có kết thúc kiểu viết tắt hoặc bị thiếu từ cuối trước dấu chấm không
                // Ví dụ: "He decided to ."
                if (res.matches(".*\\b(?:to|in|on|at|for|with|by|of|about|from|into|through|than|as|be|been|being)\\s*\\.$")) {
                    res = res.substring(0, res.length() - 1).trim() + " ___ .";
                }
            } else if (res.matches(".*\\b(?:is|are|was|were|to|at|in|on|of|for|with|about|by|will|would|can|could|should|must|than|enjoys|likes|loves|decided|wants|started|keeps|have|has|had)\\s*$")) {
                // Câu bị ngắt lửng ở giới từ hoặc trợ động từ
                res = res + " ___ .";
            } else if (!res.endsWith("!")) {
                // Nếu câu không có dấu kết thúc và không chứa "___", thêm " ___ " vào cuối
                res = res + " ___ .";
            }
        }

        return res.replaceAll("\\s{2,}", " ").trim();
    }

    private static void commitQuestion(List<CreateQuestionDto> list, QuestionBlock b,
                                       String globalTopic, Difficulty globalDiff) {
        if (b.content == null || b.content.isBlank()) {
            return;
        }

        // Chuẩn hóa vị trí khuyết ___ trong câu hỏi
        String finalContent = normalizeBlanks(b.content);

        // Đảm bảo đủ 4 đáp án (nếu thiếu có thể điền placeholder hợp lệ)
        String a = b.optA != null && !b.optA.isBlank() ? b.optA : "Phương án A";
        String bText = b.optB != null && !b.optB.isBlank() ? b.optB : "Phương án B";
        String c = b.optC != null && !b.optC.isBlank() ? b.optC : "Phương án C";
        String d = b.optD != null && !b.optD.isBlank() ? b.optD : "Phương án D";

        char correct = (b.correctOption >= 'A' && b.correctOption <= 'D') ? b.correctOption : 'A';

        // Xác định chủ đề: ưu tiên chỉ định trực tiếp -> chỉ định toàn cục -> phân tích nội dung tiếng Anh
        String topic = b.explicitTopic;
        if (topic == null || topic.isBlank()) {
            topic = globalTopic;
        }
        if (topic == null || topic.isBlank() || topic.equalsIgnoreCase("Tổng hợp")) {
            topic = detectEnglishTopic(finalContent + " " + a + " " + bText + " " + c + " " + d);
        }

        // Xác định độ khó: ưu tiên chỉ định trực tiếp -> toàn cục -> tự động đánh giá
        Difficulty diff = b.explicitDifficulty;
        if (diff == null) {
            diff = globalDiff;
        }
        if (diff == null) {
            diff = detectDifficulty(finalContent, a, bText, c, d);
        }

        try {
            CreateQuestionDto validated = ClientValidation.question(
                new CreateQuestionDto(finalContent, a, bText, c, d, correct, topic, diff)
            );
            list.add(validated);
        } catch (Exception ignored) {
            // Nếu độ dài vượt quá giới hạn (ví dụ content > 1000), có thể cắt bớt để đảm bảo không bị drop
            try {
                String safeContent = finalContent.length() > 950 ? finalContent.substring(0, 950) + "…" : finalContent;
                String safeA = a.length() > 250 ? a.substring(0, 250) : a;
                String safeB = bText.length() > 250 ? bText.substring(0, 250) : bText;
                String safeC = c.length() > 250 ? c.substring(0, 250) : c;
                String safeD = d.length() > 250 ? d.substring(0, 250) : d;
                String safeTopic = topic.length() > 95 ? topic.substring(0, 95) : topic;
                list.add(ClientValidation.question(
                    new CreateQuestionDto(safeContent, safeA, safeB, safeC, safeD, correct, safeTopic, diff)
                ));
            } catch (Exception ignored2) {}
        }
    }

    /**
     * Tự động nhận diện chủ đề ngữ pháp bài học tiếng Anh dựa trên từ vựng, cấu trúc câu hỏi.
     */
    public static String detectEnglishTopic(String text) {
        if (text == null || text.isBlank()) {
            return "Ngữ pháp tiếng Anh";
        }

        for (Map.Entry<String, Pattern[]> entry : ENGLISH_TOPICS.entrySet()) {
            for (Pattern p : entry.getValue()) {
                if (p.matcher(text).find()) {
                    return entry.getKey();
                }
            }
        }

        return "Ngữ pháp tiếng Anh tổng hợp";
    }

    /**
     * Tự động suy luận độ khó của câu hỏi dựa trên chiều dài câu và cấu trúc phức tạp.
     */
    public static Difficulty detectDifficulty(String content, String a, String b, String c, String d) {
        int totalLen = (content != null ? content.length() : 0)
            + (a != null ? a.length() : 0)
            + (b != null ? b.length() : 0)
            + (c != null ? c.length() : 0)
            + (d != null ? d.length() : 0);

        if (totalLen < 120) {
            return Difficulty.EASY;
        } else if (totalLen < 280) {
            return Difficulty.MEDIUM;
        } else {
            return Difficulty.HARD;
        }
    }

    private static Difficulty parseDifficulty(String str) {
        if (str == null) return Difficulty.MEDIUM;
        String s = str.trim().toUpperCase(Locale.ROOT);
        if (s.contains("DỄ") || s.contains("EASY") || s.equals("1")) return Difficulty.EASY;
        if (s.contains("KHÓ") || s.contains("HARD") || s.equals("3")) return Difficulty.HARD;
        return Difficulty.MEDIUM;
    }

    private static String cleanOptionText(String opt) {
        if (opt == null) return "";
        return opt.trim().replaceAll("^[\\.\\:\\-\\)]\\s*", "");
    }

    private static class QuestionBlock {
        String content;
        String optA;
        String optB;
        String optC;
        String optD;
        char correctOption = 'A';
        String explicitTopic;
        Difficulty explicitDifficulty;

        boolean isEmpty() {
            return content == null && optA == null && optB == null && optC == null && optD == null;
        }

        boolean hasAnyOption() {
            return optA != null || optB != null || optC != null || optD != null;
        }

        boolean isComplete() {
            return content != null && optA != null && optB != null && optC != null && optD != null;
        }
    }
}
