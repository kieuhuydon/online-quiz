package quiz.client.ui.teacher.question;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.common.dto.CreateQuestionDto;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuestionFileParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parseTxtFileWithConditionalsAndTenses() throws Exception {
        String content = """
            Câu 1: If it rains tomorrow, we will cancel the picnic.
            A. rains
            B. rain
            C. will rain
            D. rained
            Đáp án: A

            Câu 2: By the time he arrived, the train had left.
            A. leaves
            B. has left
            C. had left
            D. was leaving
            Đáp án: C
            """;
        File txtFile = tempDir.resolve("questions.txt").toFile();
        Files.writeString(txtFile.toPath(), content);

        List<CreateQuestionDto> questions = QuestionFileParser.parse(txtFile);
        assertEquals(2, questions.size());

        CreateQuestionDto q1 = questions.get(0);
        assertTrue(q1.content().contains("If it rains tomorrow"));
        assertEquals("rains", q1.optionA());
        assertEquals('A', q1.correctOption());
        assertTrue(q1.topic().contains("Câu điều kiện"));

        CreateQuestionDto q2 = questions.get(1);
        System.out.println("Q2 TOPIC: " + q2.topic());
        assertTrue(q2.content().contains("the train had left"));
        assertEquals('C', q2.correctOption());
        assertTrue(q2.topic().contains("Thì hoàn thành") || q2.topic().contains("Thì quá khứ"));
    }

    @Test
    void parseDocxFileExtractsQuestions() throws Exception {
        File docxFile = tempDir.resolve("english_test.docx").toFile();
        try (XWPFDocument doc = new XWPFDocument();
             FileOutputStream fos = new FileOutputStream(docxFile)) {

            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("Câu 1: The telephone was invented by Alexander Graham Bell.");

            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("A. invented");

            XWPFParagraph p3 = doc.createParagraph();
            p3.createRun().setText("B. was invented");

            XWPFParagraph p4 = doc.createParagraph();
            p4.createRun().setText("C. is invented");

            XWPFParagraph p5 = doc.createParagraph();
            p5.createRun().setText("D. has invented");

            XWPFParagraph p6 = doc.createParagraph();
            p6.createRun().setText("Đáp án: B");

            doc.write(fos);
        }

        List<CreateQuestionDto> questions = QuestionFileParser.parse(docxFile);
        assertEquals(1, questions.size());

        CreateQuestionDto q = questions.get(0);
        assertTrue(q.content().contains("The telephone was invented"));
        assertEquals('B', q.correctOption());
        assertTrue(q.topic().contains("bị động"));
    }

    @Test
    void parsePdfFileExtractsQuestions() throws Exception {
        File pdfFile = tempDir.resolve("test.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setLeading(14.5f);
                cs.newLineAtOffset(50, 700);

                cs.showText("Cau 1: If I were you, I would study harder.");
                cs.newLine();
                cs.showText("A. were");
                cs.newLine();
                cs.showText("B. was");
                cs.newLine();
                cs.showText("C. am");
                cs.newLine();
                cs.showText("D. be");
                cs.newLine();
                cs.showText("Dap an: A");
                cs.newLine();

                cs.endText();
            }
            doc.save(pdfFile);
        }

        List<CreateQuestionDto> questions = QuestionFileParser.parse(pdfFile);
        assertEquals(1, questions.size());

        CreateQuestionDto q = questions.get(0);
        assertTrue(q.content().contains("If I were you"));
        assertEquals('A', q.correctOption());
        assertTrue(q.topic().contains("Câu điều kiện"));
    }

    @Test
    void testTopicDetectionForVariousLessons() {
        // Tag question
        String t1 = QuestionFileParser.detectEnglishTopic("You don't like coffee, do you?");
        assertTrue(t1.contains("Câu hỏi đuôi"));

        // Modal verbs
        String t2 = QuestionFileParser.detectEnglishTopic("You must wear a helmet when riding a motorbike.");
        assertTrue(t2.contains("Động từ khuyết thiếu"));

        // Comparison
        String t3 = QuestionFileParser.detectEnglishTopic("She is more beautiful than her sister.");
        assertTrue(t3.contains("Câu so sánh"));

        // Reported speech
        String t4 = QuestionFileParser.detectEnglishTopic("He said that he was going to Paris next week.");
        assertTrue(t4.contains("Câu gián tiếp"));
    }

    @Test
    void testNormalizeBlanksDetectsVariousFormats() {
        // Dấu chấm liên tiếp
        assertEquals("She is interested in ___ learning English.",
            QuestionFileParser.normalizeBlanks("She is interested in ..... learning English."));

        // Dấu gạch dưới dài
        assertEquals("If I had known, I ___ have called you.",
            QuestionFileParser.normalizeBlanks("If I had known, I ________ have called you."));

        // Dấu ngoặc rỗng
        assertEquals("Neither of them ___ ready for the test.",
            QuestionFileParser.normalizeBlanks("Neither of them ( ... ) ready for the test."));

        // Khoảng trắng đôi trong PDF (2 dấu cách)
        assertEquals("They decided to go ___ although it was raining.",
            QuestionFileParser.normalizeBlanks("They decided to go   although it was raining."));

        // Câu kết thúc lửng ở giới từ
        assertEquals("The letter was written by ___ .",
            QuestionFileParser.normalizeBlanks("The letter was written by"));
    }

    @Test
    void parseCsvFileHandlesHeadersQuotesAndDefaults() throws Exception {
        String csv = """
            # Comment line
            Content,OptionA,OptionB,OptionC,OptionD,Correct,Topic,Difficulty
            "If I had time, I ___ go with you.",would,will,did,am,A,,HARD
            "She enjoys ___ books in her free time.",reading,to read,read,reads,a,Gerund,INVALID_DIFF
            """;
        File csvFile = tempDir.resolve("questions.csv").toFile();
        Files.writeString(csvFile.toPath(), csv);

        List<CreateQuestionDto> list = QuestionFileParser.parse(csvFile);
        assertEquals(2, list.size());

        CreateQuestionDto q1 = list.get(0);
        assertTrue(q1.content().contains("If I had time, I ___ go with you."));
        assertEquals('A', q1.correctOption());
        assertTrue(q1.topic().contains("Câu điều kiện"));
        assertEquals(quiz.common.enums.Difficulty.HARD, q1.difficulty());

        CreateQuestionDto q2 = list.get(1);
        assertEquals('A', q2.correctOption());
        assertEquals("Gerund", q2.topic());
        assertEquals(quiz.common.enums.Difficulty.MEDIUM, q2.difficulty());
    }

    @Test
    void parseInlineOptionsAndExplicitHeadersAndDetectDifficulty() throws Exception {
        String txt = """
            Chủ đề: Từ vựng chuyên ngành
            Độ khó: Khó

            Câu 1: Choose the synonym of rapid?
            A. fast   B. slow   C. late   D. weak
            Đáp án: A

            Câu 2: She ___ to school every day.
            A. go   B. goes
            C. went   D. gone
            Answer: B
            """;
        File txtFile = tempDir.resolve("inline.txt").toFile();
        Files.writeString(txtFile.toPath(), txt);

        List<CreateQuestionDto> list = QuestionFileParser.parse(txtFile);
        assertEquals(2, list.size());
        assertEquals("Từ vựng chuyên ngành", list.get(0).topic());
        assertEquals(quiz.common.enums.Difficulty.HARD, list.get(0).difficulty());
        assertEquals("fast", list.get(0).optionA());
        assertEquals("weak", list.get(0).optionD());

        assertEquals("goes", list.get(1).optionB());
        assertEquals("gone", list.get(1).optionD());
        assertEquals('B', list.get(1).correctOption());

        assertEquals(quiz.common.enums.Difficulty.EASY,
            QuestionFileParser.detectDifficulty("Short?", "A", "B", "C", "D"));
        assertEquals(quiz.common.enums.Difficulty.MEDIUM,
            QuestionFileParser.detectDifficulty("x".repeat(130), "A", "B", "C", "D"));
        assertEquals(quiz.common.enums.Difficulty.HARD,
            QuestionFileParser.detectDifficulty("x".repeat(300), "A", "B", "C", "D"));

        assertThrows(IllegalArgumentException.class, () -> QuestionFileParser.parse(null));
        assertThrows(IllegalArgumentException.class,
            () -> QuestionFileParser.parse(tempDir.resolve("missing.txt").toFile()));
    }
}
