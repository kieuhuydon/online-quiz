# Online Quiz cho Trung tâm đào tạo (Java Client/Server)

> **Tài liệu này là "nguồn sự thật" cho cả nhóm và cho AI agent.**
> Mọi code phải bám theo cấu trúc, quy tắc và thiết kế trong file này.
> Nếu cần đi lệch, hãy sửa README trước (hoặc hỏi nhóm), rồi mới sửa code.

---

## 0. Quy tắc cho AI agent (đọc trước khi code)

1. **Chỉ làm đúng milestone/task được giao** (xem mục 19). Không tự thêm tính năng ngoài phạm vi (mục 2.3).
2. **Tuân thủ phụ thuộc một chiều giữa các tầng** (mục 4.2). Vi phạm = sai thiết kế.
3. **Server là nguồn sự thật**: deadline, chấm điểm, trạng thái Attempt, quyền truy cập đều do server quyết định. Không bao giờ tin dữ liệu từ client (điểm, thời gian, role).
4. **Không gửi đáp án đúng xuống client học viên.** Dùng DTO riêng cho học viên (không có trường `correctOption`).
5. **Mọi SQL dùng `PreparedStatement`.** Không nối chuỗi SQL. Mật khẩu chỉ lưu dạng hash (BCrypt).
6. **Không block JavaFX Application Thread.** Mọi gọi mạng đi qua `QuizApi` (trả `CompletableFuture`) và cập nhật UI bằng `FxAsync`/`Platform.runLater`.
7. **Không hard-code** port, pool size, DB url, thời gian grace… → đặt trong `server.properties` / `client.properties`.
8. **Mỗi tính năng đi kèm test** (unit hoặc integration). Chạy `mvn clean verify` trước khi báo hoàn thành.
9. **Không bịa số liệu thực nghiệm / kết quả test.** Chỉ ghi kết quả đã chạy thật (mục 16–17).
10. **Không để lại code chết, `System.out.println` debug, `printStackTrace()`.** Dùng SLF4J.
11. Tuân thủ Coding Conventions của môn học (mục 20). Đặt tên rõ nghĩa, method ngắn, không lặp code.
12. Khi chưa chắc về nghiệp vụ, tra mục 3 (quyết định đã chốt) và mục 12 (quy tắc nghiệp vụ); nếu vẫn mơ hồ thì **hỏi**, đừng đoán.

---

## 1. Thông tin dự án

| Mục | Nội dung |
|---|---|
| Tên dự án | Online Quiz cho Trung tâm đào tạo |
| Nhóm | `GroupXX` |
| Môn học | Java Software Development – Năm học 2026–2027 |
| Chủ đề | Topic 3 – Java Client/Server Application (Online Quiz) |
| Giảng viên | _(điền)_ |

| MSSV | Họ tên | Trách nhiệm chính |
|---|---|---|
| _(Điền)_ | **Việt** | **Authentication + User + Database & UI tương ứng** (Làm đầu tiên - Nền tảng hệ thống) |
| _(Điền)_ | **Võ** | **Question Bank & UI tương ứng** (Làm sau Việt) |
| _(Điền)_ | **Bắc** | **Quiz + Assignment & UI tương ứng** (Làm sau Võ) |
| _(Điền)_ | **Đôn** | **Student Exam & UI tương ứng** (Làm sau Bắc) |
| _(Điền)_ | **Nam** | **Anti-Cheat + Monitoring & UI tương ứng** (Làm sau Đôn) |

---

## 2. Tổng quan

### 2.1. Vấn đề
Trung tâm đào tạo (bối cảnh: trung tâm tiếng Anh) cần tạo ngân hàng câu hỏi tái sử dụng, tổ chức bài kiểm tra theo lớp, cho nhiều học viên làm bài đồng thời có giới hạn thời gian, tự động chấm điểm và xem lại kết quả.

### 2.2. Mục tiêu
- Giáo viên/Quản trị viên quản lý câu hỏi, bài kiểm tra, lớp, giao bài, xem kết quả và thống kê.
- Học viên tham gia bài kiểm tra (bằng mã PIN), làm bài có giới hạn thời gian, xem kết quả/lịch sử.
- Chấm điểm và lưu kết quả tập trung ở server.
- Nhiều client đồng thời qua TCP Socket + Thread Pool.
- Anti-Cheat cơ bản: ghi nhận sự kiện bất thường theo Attempt, cảnh báo/xử lý theo chính sách.
- Có kiểm thử và thực nghiệm hiệu năng bằng số liệu thật.

### 2.3. Phạm vi
**Trong phạm vi:** client JavaFX; TCP Socket; nhiều client; 3 role (Student/Teacher/Admin); ngân hàng câu hỏi trắc nghiệm 4 đáp án; tạo bài kiểm tra + giao cho lớp; Attempt có giới hạn thời gian; chấm điểm server; JDBC + PostgreSQL; lịch sử + thống kê cơ bản; test + benchmark; Anti-Cheat cơ bản.

**Ngoài phạm vi:** LMS đầy đủ, video/học phí/điểm danh/chat, tạo câu hỏi bằng AI, chấm code kiểu LeetCode, chấm tự luận/nhận diện giọng nói, social, client mobile/web.

---

## 3. Các quyết định thiết kế đã chốt

> Các mục "cần chốt" trong đặc tả (mục 24) được chốt mặc định như sau. Đổi quyết định → sửa bảng này.

| Vấn đề | Quyết định |
|---|---|
| Database | **PostgreSQL** (đặc tả có chỗ ghi MySQL, chỗ ghi PostgreSQL → chọn PostgreSQL; MVP + kiến trúc ghi PostgreSQL) |
| Java / Build | Java 21, Maven multi-module |
| UI client | JavaFX 21 (FXML + Controller) |
| Protocol | **Serialized DTO** (`Request`/`Response` qua `ObjectOutputStream`), có `ObjectInputFilter` whitelist |
| Quiz PIN | **Có trong MVP** (luồng học viên cần PIN; PIN sinh khi giáo viên mở bài) |
| Quản lý lớp | Mức tối thiểu: tạo lớp, thêm/xóa học viên, giao bài cho lớp |
| Tài khoản | Admin cấp sẵn, **không** tự đăng ký |
| Cấp/khóa tài khoản | API quản trị chỉ tạo TEACHER/STUDENT; không tạo ADMIN hoặc khóa tài khoản ADMIN |
| Chuẩn hóa thông tin user | Bỏ khoảng trắng đầu/cuối username, fullName, email; giữ hoa/thường; email trống thành null; unique theo schema hiện tại |
| Chính sách mật khẩu | Không trim; tạo mới tối thiểu 8 ký tự, không toàn khoảng trắng, tối đa 72 byte UTF-8; login không áp dụng ngưỡng tối thiểu của tạo mới |
| Phiên đăng nhập | Một phiên hiện hành/user; login trên socket mới thay phiên cũ; LOGIN trên socket đã đăng nhập trả CONFLICT |
| Logout/khóa user | Logout hủy đúng token của socket và giữ socket để login lại; khóa user sau commit thu hồi phiên, mở khóa yêu cầu login lại |
| Trạng thái Quiz | `DRAFT → OPEN → CLOSED` (không quay lại) |
| Số lần làm | Mỗi học viên **1 Attempt / 1 Quiz** (`UNIQUE(quiz_id, student_id)`) |
| Điểm đạt | Mặc định **50%**, cấu hình theo từng quiz (`pass_score`) |
| Thang điểm | `score = correct / total * 10` (làm tròn 2 chữ số) |
| Reconnect | Hỗ trợ ở mức đơn giản: login lại + `START_ATTEMPT` trả về Attempt đang `IN_PROGRESS` nếu còn hạn |
| Đóng quiz khi có người đang làm | Chặn Attempt mới (BR-06); Attempt đang chạy được làm tiếp đến deadline |
| Benchmark | 1 / 5 / 10 / 20 / (tùy chọn) 50 client |
| Anti-Cheat | Xem mục 11 (sự kiện, ngưỡng mặc định 3, policy `WARN_THEN_AUTO_SUBMIT` hoặc `LOG_ONLY`) |
| Thread model server | Pool kết nối (đọc) + Worker pool cố định (xử lý) + 1 scheduler (hết giờ) |

---

## 4. Kiến trúc

### 4.1. Tổng thể

```text
 JavaFX Client(s)                         Quiz Server
┌──────────────────┐   TCP + serialized  ┌──────────────────────────────────────────┐
│ ui (FXML/Ctrl)   │   Request/Response  │ network (accept, ClientHandler)          │
│   ↓              │ ──────────────────► │   ↓ read loop (connection pool)          │
│ QuizApi (Future) │                     │ dispatch (Dispatcher → AuthGuard → Handler)│
│   ↓              │ ◄────────────────── │   ↓ submit to worker pool                │
│ ServerConnection │   Response + Push   │ service (business logic, transactions)   │
│ anticheat        │                     │   ↓                                      │
└──────────────────┘                     │ repository → jdbc → db (Hikari) → PostgreSQL
                                         │ AttemptTimeoutScheduler (hết giờ → auto-submit)
                                         └──────────────────────────────────────────┘
        quiz-common (protocol, dto, enums) dùng chung cho cả hai phía
```

### 4.2. Quy tắc phụ thuộc (bắt buộc)

```text
handler  →  service  →  repository(interface)  ←  jdbc impl  →  db
                ↓
             domain  (không phụ thuộc gì, chỉ dùng quiz.common.enums)
```

- `network` biết `dispatch`; **không** biết `service`.
- `handlers` chỉ gọi `service`; **không** viết SQL, **không** đụng `Socket`.
- `service` chỉ dùng `repository` (interface), `domain`, `security`, và service khác; **không** biết `Request/Response`/socket.
- Ngoại lệ đã chốt cho Auth/Account: service được dùng `SessionRegistry`/`UserSession` như bộ lưu trữ và dữ liệu phiên trong bộ nhớ; không dùng `ClientConnection`/`ConnectionRegistry`. Tầng network dùng `SessionLifecycle` để bind/unbind socket, không gọi service.
- `repository.jdbc` là nơi duy nhất có SQL/JDBC.
- Client: `ui` → `QuizApi` → `ServerConnection`. Controller **không** biết `Socket`/`Request`.
- `client` và `server` **không** import lẫn nhau; chỉ chia sẻ qua `quiz-common`.

---

## 5. Cấu trúc project

> Chỉ tạo package/file khi bắt đầu viết class đầu tiên trong đó. Không commit thư mục rỗng.

```text
online-quiz/
├── pom.xml                       # parent: quản lý version, modules
├── README.md
├── database/
│   ├── schema.sql                # DDL PostgreSQL (mục 8)
│   └── seed.sql                  # admin, vài teacher/student/lớp/câu hỏi mẫu
├── docs/                         # D01–D12 (usecase, ERD, protocol, anticheat...)
├── report/                       # GroupXX_OnlineQuiz_Report.docx
│
├── quiz-common/
│   ├── pom.xml
│   └── src/
│       ├── main/java/quiz/common/
│       │   ├── protocol/
│       │   │   ├── Request.java
│       │   │   ├── Response.java
│       │   │   ├── MessageType.java
│       │   │   ├── ErrorCode.java
│       │   │   └── MessageCodec.java
│       │   ├── dto/              # record ... implements Serializable
│       │   └── enums/            # Role, QuizStatus, AttemptStatus, Difficulty,
│       │                         # AntiCheatEventType, AntiCheatPolicyType, SubmitReason
│       └── test/java/quiz/common/
│
├── quiz-server/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/quiz/server/
│       │   │   ├── ServerMain.java
│       │   │   ├── config/       # ServerConfig (đọc server.properties)
│       │   │   ├── network/      # QuizServer, ClientHandler, ClientConnection,
│       │   │   │                 # ConnectionRegistry, SessionRegistry, UserSession
│       │   │   ├── dispatch/
│       │   │   │   ├── RequestDispatcher.java
│       │   │   │   ├── CommandHandler.java
│       │   │   │   ├── AuthGuard.java
│       │   │   │   ├── DtoMapper.java
│       │   │   │   └── handlers/
│       │   │   │       ├── LoginHandler.java
│       │   │   │       ├── AccountHandler.java
│       │   │   │       ├── ClassHandler.java      # (bổ sung) lớp + thành viên
│       │   │   │       ├── QuestionHandler.java
│       │   │   │       ├── QuizHandler.java       # quiz + giao bài + PIN
│       │   │   │       ├── AttemptHandler.java
│       │   │   │       ├── ResultHandler.java
│       │   │   │       └── AntiCheatHandler.java
│       │   │   ├── service/      # AuthService, AccountService, ClassService,
│       │   │   │                 # QuestionService, QuizService, AttemptService,
│       │   │   │                 # GradingService, ResultService,
│       │   │   │                 # AttemptTimeoutScheduler
│       │   │   │   └── anticheat/   # AntiCheatService, AntiCheatPolicy (interface),
│       │   │   │                    # WarnThenAutoSubmitPolicy, LogOnlyPolicy
│       │   │   ├── repository/   # interface: User/Class/Question/Quiz/Attempt/
│       │   │   │   │             #            Result/AntiCheatEvent Repository
│       │   │   │   └── jdbc/     # Jdbc*Repository implements ...
│       │   │   ├── db/           # DataSourceProvider (HikariCP), TransactionManager
│       │   │   ├── domain/       # User, Classroom, Question, Quiz, Attempt,
│       │   │   │                 # AnswerRecord, ExamResult, AntiCheatEvent
│       │   │   ├── security/     # PasswordHasher (BCrypt), TokenGenerator
│       │   │   └── exception/    # QuizException + Validation/NotFound/Forbidden/
│       │   │                     # Unauthorized/Conflict/Expired...
│       │   └── resources/
│       │       ├── server.properties
│       │       └── logback.xml
│       └── test/java/quiz/server/
│           ├── unit/             # Grading, AntiCheatPolicy, validation (mock repo)
│           ├── integration/      # service + JDBC + DB test thật
│           └── network/          # server thật + client giả (socket)
│
├── quiz-client/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/quiz/client/
│       │   │   ├── ClientMain.java
│       │   │   ├── net/          # ServerConnection, QuizApi, PushListener
│       │   │   ├── session/      # ClientSession (user, token, attempt hiện tại)
│       │   │   ├── ui/
│       │   │   │   ├── SceneNavigator.java
│       │   │   │   ├── login/
│       │   │   │   ├── admin/account/
│       │   │   │   ├── teacher/{question,quiz,assignment,result}/
│       │   │   │   ├── student/{dashboard,exam,result}/
│       │   │   │   └── component/   # TimerLabel, QuestionPane, ...
│       │   │   ├── anticheat/    # AntiCheatMonitor, AntiCheatReporter
│       │   │   └── util/         # FxAsync, ClientConfig
│       │   └── resources/
│       │       ├── client.properties
│       │       ├── fxml/
│       │       └── css/
│       └── test/java/quiz/client/
│
└── quiz-benchmark/
    ├── pom.xml
    └── src/
        ├── main/java/quiz/benchmark/LoadTestRunner.java
        └── test/java/quiz/benchmark/
```

Mỗi màn hình client = 1 package `ui/...` gồm `XxxController` + file `fxml/xxx.fxml` (+ `XxxViewModel` nếu cần).

---

## 6. quiz-common: protocol, DTO, enums

### 6.1. Envelope

```java
// Tất cả implements Serializable
public record Request(long requestId, MessageType type, String token, Serializable payload) {}

public record Response(long requestId,          // trùng requestId của Request; 0 nếu là PUSH
                       boolean success,
                       ErrorCode errorCode,     // null nếu success
                       String message,          // thông báo cho người dùng
                       MessageType pushType,    // null nếu là response thường; != null nếu là PUSH
                       Serializable payload) {}
```

- `requestId` do client tăng dần (`AtomicLong`); client ghép response theo `requestId`.
- **PUSH** (server chủ động gửi): `requestId = 0`, `pushType != null`.
- Payload là DTO (record `implements Serializable`) hoặc `null`.

### 6.2. MessageCodec
- Mỗi message là một frame: `int length` + `length` byte chứa một Request/Response serialize độc lập, tối đa 1 MiB. Dùng `MessageCodec.write/read` trên ObjectOutputStream/ObjectInputStream; không gọi `writeObject/readObject` trực tiếp trên stream mạng.
- `write` serialize vào buffer có giới hạn trước khi ghi frame, rồi reset/flush stream ngoài. `read` kiểm tra length trước khi cấp phát và deserialize bằng stream riêng cho từng frame.
- Filter nội bộ chỉ cho phép DTO/enums/protocol của common và các lớp giá trị/collection cần thiết; giới hạn depth 32, references 10.000, array 10.000. Stream ngoài từ chối serialized object, chỉ nhận primitive block data.
- Giới hạn tính riêng cho từng message, không cộng dồn suốt phiên. Sai frame: đóng kết nối tại tầng network. Timeout handshake/read và validation quyền/payload nghiệp vụ thuộc giai đoạn tiếp theo.
- Wire format này thay thế format writeObject trực tiếp trước đây; hai đầu phải dùng cùng phiên bản quiz-common. Test bao gồm frame sai, whitelist, giới hạn tài nguyên và nhiều message liên tiếp.

### 6.3. ErrorCode
`INVALID_REQUEST, VALIDATION_ERROR, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, INVALID_CREDENTIALS, ACCOUNT_DISABLED, QUIZ_NOT_OPEN, QUIZ_CLOSED, WRONG_PIN, NOT_ASSIGNED, ATTEMPT_ALREADY_SUBMITTED, ATTEMPT_EXPIRED, ATTEMPT_NOT_FOUND, QUESTION_IN_USE, SERVER_BUSY, DATABASE_ERROR, INTERNAL_ERROR`

### 6.4. Enums
`Role {STUDENT, TEACHER, ADMIN}` · `QuizStatus {DRAFT, OPEN, CLOSED}` · `AttemptStatus {IN_PROGRESS, SUBMITTED}` · `Difficulty {EASY, MEDIUM, HARD}` · `SubmitReason {MANUAL, TIMEOUT, ANTICHEAT}` · `AntiCheatPolicyType {LOG_ONLY, WARN_THEN_AUTO_SUBMIT}` · `AntiCheatEventType {FOCUS_LOST, COPY_ATTEMPT, PASTE_ATTEMPT, CUT_ATTEMPT, CONTEXT_MENU_ATTEMPT}`

### 6.5. DTO (record, `Serializable`) – gợi ý tối thiểu
`LoginRequestDto, LoginResultDto(token, userId, fullName, role)`, `AccountDto, CreateAccountDto`, `ClassDto, ClassMemberDto`, `QuestionDto` (đầy đủ, **cho giáo viên**, có `correctOption`), `StudentQuestionDto` (**không** có `correctOption`), `QuizDto, QuizSummaryDto, QuizInfoDto, QuizQuestionsDto`, `AssignQuizDto`, `StartAttemptRequestDto(quizId, pin)`, `AttemptStateDto(attemptId, quizId, remainingSeconds, questions, savedAnswers)`, `SaveAnswerDto(attemptId, questionId, selectedOption)`, `SubmitAttemptDto(attemptId, finalAnswers)`, `ResultDto(attemptId, quizTitle, score, correct, total, passed, submitReason, submittedAt)`, `ResultFilterDto(quizId, keyword, sortBy, ascending)`, `QuizStatisticsDto(avg, passRate, max, min, count)`, `AntiCheatEventDto, ReportAntiCheatDto(attemptId, type, detail, clientTime)`, `AntiCheatWarningDto(violationCount, maxViolations, message)`.

---

## 7. Danh mục message (handler · quyền · payload)

| MessageType | Role được phép | Handler | Mô tả |
|---|---|---|---|
| `LOGIN` | (chưa login) | LoginHandler | Xác thực, trả token + role |
| `LOGOUT` | mọi role | LoginHandler | Hủy session |
| `CREATE_ACCOUNT`, `LIST_ACCOUNTS`, `SET_ACCOUNT_ACTIVE` | ADMIN | AccountHandler | FR-AD01 |
| `CREATE_CLASS`, `LIST_CLASSES`, `ADD_CLASS_MEMBER`, `REMOVE_CLASS_MEMBER`, `LIST_CLASS_MEMBERS` | TEACHER, ADMIN | ClassHandler | FR-A07 |
| `CREATE_QUESTION`, `UPDATE_QUESTION`, `GET_QUESTION`, `LIST_QUESTIONS`, `DEACTIVATE_QUESTION` | TEACHER, ADMIN | QuestionHandler | FR-A02/A03, BR-05 |
| `CREATE_QUIZ`, `UPDATE_QUIZ`, `GET_QUIZ`, `LIST_QUIZZES`, `SET_QUIZ_QUESTIONS` | TEACHER, ADMIN | QuizHandler | FR-A04/A05/A06 (chỉ sửa khi DRAFT) |
| `OPEN_QUIZ` (sinh PIN), `CLOSE_QUIZ`, `ASSIGN_QUIZ` | TEACHER, ADMIN | QuizHandler | FR-A08/A09 |
| `LIST_ASSIGNED_QUIZZES`, `GET_QUIZ_INFO`, `VERIFY_QUIZ_PIN` | STUDENT | QuizHandler | FR-S02/S03/S04 |
| `START_ATTEMPT`, `SAVE_ANSWER`, `SUBMIT_ATTEMPT`, `GET_ATTEMPT_STATE` | STUDENT | AttemptHandler | FR-S05..S10, reconnect |
| `GET_MY_RESULT`, `LIST_MY_HISTORY` | STUDENT | ResultHandler | FR-S11/S12 |
| `LIST_QUIZ_RESULTS`, `GET_QUIZ_STATISTICS`, `LIST_ATTEMPT_EVENTS` | TEACHER, ADMIN | ResultHandler | FR-A10/A11, UC-08 |
| `REPORT_ANTICHEAT_EVENT` | STUDENT | AntiCheatHandler | FR-S13/S14, FR-T09 |
| **PUSH** `PUSH_ANTICHEAT_WARNING`, `PUSH_FORCE_SUBMITTED` | server → student | (AntiCheatService / Scheduler) | Cảnh báo; Attempt bị tự nộp |

Quy tắc: nếu `type` không có handler → `INVALID_REQUEST`. `AuthGuard` kiểm tra token hợp lệ và role **trước** khi gọi handler (mỗi handler khai báo role cho từng `MessageType`).

---

## 8. Database (PostgreSQL) – `database/schema.sql`

DDL thực thi trong `database/schema.sql` bổ sung CHECK theo các miền dữ liệu đã chốt (điểm, phần trăm, thời hạn, policy/event/action), cùng vị trí câu hỏi dương và không trùng trong quiz. Script là khởi tạo nguyên tử trên DB mới; không xóa hay tự nâng cấp dữ liệu cũ. Các bảng và quan hệ nghiệp vụ giữ theo thiết kế dưới đây.

```sql
CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  username      VARCHAR(50)  NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  full_name     VARCHAR(100) NOT NULL,
  email         VARCHAR(100) UNIQUE,
  role          VARCHAR(10)  NOT NULL CHECK (role IN ('STUDENT','TEACHER','ADMIN')),
  active        BOOLEAN      NOT NULL DEFAULT TRUE,
  created_by    BIGINT       REFERENCES users(id),
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE classes (
  id          BIGSERIAL PRIMARY KEY,
  name        VARCHAR(100) NOT NULL UNIQUE,
  description VARCHAR(255),
  teacher_id  BIGINT       NOT NULL REFERENCES users(id),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE class_members (
  class_id    BIGINT      NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
  student_id  BIGINT      NOT NULL REFERENCES users(id),
  enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (class_id, student_id)
);

CREATE TABLE questions (
  id             BIGSERIAL PRIMARY KEY,
  content        TEXT NOT NULL,
  option_a       TEXT NOT NULL,
  option_b       TEXT NOT NULL,
  option_c       TEXT NOT NULL,
  option_d       TEXT NOT NULL,
  correct_option CHAR(1) NOT NULL CHECK (correct_option IN ('A','B','C','D')),
  topic          VARCHAR(100) NOT NULL,
  difficulty     VARCHAR(10)  NOT NULL CHECK (difficulty IN ('EASY','MEDIUM','HARD')),
  active         BOOLEAN NOT NULL DEFAULT TRUE,          -- soft-delete (BR-05)
  created_by     BIGINT NOT NULL REFERENCES users(id),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE quizzes (
  id               BIGSERIAL PRIMARY KEY,
  title            VARCHAR(200) NOT NULL,
  description      VARCHAR(500),
  category         VARCHAR(100),
  duration_minutes INT NOT NULL CHECK (duration_minutes > 0),
  pass_score       NUMERIC(5,2) NOT NULL DEFAULT 50,      -- % để đạt
  status           VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','OPEN','CLOSED')),
  pin              VARCHAR(10),                           -- mã mời / Quiz PIN (sinh khi OPEN)
  anticheat_policy VARCHAR(30) NOT NULL DEFAULT 'WARN_THEN_AUTO_SUBMIT',
  max_violations   INT NOT NULL DEFAULT 3 CHECK (max_violations > 0),
  created_by       BIGINT NOT NULL REFERENCES users(id),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  opened_at        TIMESTAMPTZ,
  closed_at        TIMESTAMPTZ
);

CREATE TABLE quiz_questions (
  quiz_id     BIGINT NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
  question_id BIGINT NOT NULL REFERENCES questions(id),
  position    INT NOT NULL,
  PRIMARY KEY (quiz_id, question_id)
);

CREATE TABLE quiz_assignments (
  id          BIGSERIAL PRIMARY KEY,
  quiz_id     BIGINT NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
  class_id    BIGINT NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (quiz_id, class_id)
);

CREATE TABLE attempts (
  id           BIGSERIAL PRIMARY KEY,
  quiz_id      BIGINT NOT NULL REFERENCES quizzes(id),
  student_id   BIGINT NOT NULL REFERENCES users(id),
  status       VARCHAR(12) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','SUBMITTED')),
  started_at   TIMESTAMPTZ NOT NULL,
  deadline_at  TIMESTAMPTZ NOT NULL,                      -- do SERVER tính
  submitted_at TIMESTAMPTZ,
  UNIQUE (quiz_id, student_id)                            -- BR-09, 1 attempt/quiz/student
);

CREATE TABLE attempt_answers (
  attempt_id      BIGINT NOT NULL REFERENCES attempts(id) ON DELETE CASCADE,
  question_id     BIGINT NOT NULL REFERENCES questions(id),
  selected_option CHAR(1) CHECK (selected_option IN ('A','B','C','D')), -- NULL nếu chưa chọn
  is_correct      BOOLEAN,                                                -- server chấm khi nộp bài
  answered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (attempt_id, question_id)
);

CREATE TABLE results (
  attempt_id      BIGINT PRIMARY KEY REFERENCES attempts(id),
  score           NUMERIC(5,2) NOT NULL,                  -- thang 10
  correct_count   INT NOT NULL,
  total_questions INT NOT NULL,
  passed          BOOLEAN NOT NULL,
  submit_reason   VARCHAR(10) NOT NULL CHECK (submit_reason IN ('MANUAL','TIMEOUT','ANTICHEAT')),
  graded_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE anticheat_events (
  id                    BIGSERIAL PRIMARY KEY,
  attempt_id            BIGINT NOT NULL REFERENCES attempts(id) ON DELETE CASCADE,
  event_type            VARCHAR(30) NOT NULL,
  detail                VARCHAR(255),
  client_time           TIMESTAMPTZ,
  server_time           TIMESTAMPTZ NOT NULL DEFAULT now(),
  violation_count_after INT NOT NULL,
  action_taken          VARCHAR(20) NOT NULL              -- NONE | WARNED | AUTO_SUBMITTED
);

CREATE INDEX idx_attempts_quiz     ON attempts(quiz_id);
CREATE INDEX idx_attempts_student  ON attempts(student_id);
CREATE INDEX idx_ac_events_attempt ON anticheat_events(attempt_id);
CREATE INDEX idx_questions_filter  ON questions(topic, difficulty) WHERE active;
```

`seed.sql`: 1 admin, 1–2 teacher, ≥ 50 student (phục vụ benchmark), 2–3 lớp, ≥ 30 câu hỏi. Mật khẩu seed phải là **hash BCrypt**, không phải plain text.

---

## 9. Thiết kế server

### 9.1. Thread model & network
- `QuizServer`: 1 thread `accept()` trong vòng lặp; mỗi socket được bọc trong `ClientHandler` và chạy trên **`connectionExecutor`** (giới hạn bởi `server.max.connections`).
- `ClientHandler.run()`: vòng lặp đọc `Request` → đẩy xử lý sang **`workerPool`** (`ThreadPoolExecutor` cố định, hàng đợi có giới hạn). Hàng đợi đầy → trả `SERVER_BUSY` (không treo).
- `ClientConnection`: bọc `ObjectOutputStream`; **`send()` phải `synchronized`** vì response (worker thread) và PUSH (scheduler/worker khác) có thể ghi cùng lúc.
- `ConnectionRegistry`: `ConcurrentHashMap<Long userId, ClientConnection>` để push cảnh báo/force-submit.
- `SessionRegistry`: `ConcurrentHashMap<String token, UserSession(userId, role, username)>`; token sinh bằng `SecureRandom` (≥ 128 bit). Hủy khi logout/ngắt kết nối.
- Đóng kết nối: luôn dùng try-with-resources; client ngắt đột ngột không được làm sập server hay rò thread.
- Shutdown gọn: `ServerMain` đăng ký shutdown hook (đóng `ServerSocket`, `shutdown()` các executor, đóng pool DB).

### 9.2. Dispatch
```java
public interface CommandHandler {
    Set<MessageType> supportedTypes();
    Set<Role> allowedRoles(MessageType type);      // rỗng = không cần đăng nhập (LOGIN)
    Response handle(Request request, UserSession session);
}
```
- `RequestDispatcher` giữ map handler bất biến (đăng ký khi khởi động), bắt `Exception` để chuẩn hóa lỗi request; không nuốt `VirtualMachineError`/`ThreadDeath` rồi tiếp tục. Handler trùng type, policy null/rỗng cho lệnh khác LOGIN hoặc đăng ký PUSH là lỗi khởi động.
  `QuizException` → `Response(success=false, errorCode, message)`; SQLException hoặc cause SQL → DATABASE_ERROR; lỗi khác → INTERNAL_ERROR. DATABASE_ERROR/INTERNAL_ERROR luôn dùng thông báo chung. Log chỉ requestId/type/userId, lớp exception và stack frame đã loại thông báo/cause chứa giá trị nhạy cảm; không log Request/DTO/password/hash/token. (FR-T07)
- `AuthGuard.check(request, handler)`: token tồn tại? role thuộc `allowedRoles`? Sai → `UNAUTHENTICATED`/`FORBIDDEN`.
- `DtoMapper`: chuyển domain → DTO (có hai bản câu hỏi: đầy đủ và `StudentQuestionDto`).
- Contract task 4.3: LOGIN public, token phải null; LOGOUT cần session của mọi role; CREATE_ACCOUNT/LIST_ACCOUNTS/SET_ACCOUNT_ACTIVE chỉ ADMIN. Guard gọi AuthService.authenticate để lấy identity/role hiện tại. Request ID phải dương; lệnh chưa triển khai, PUSH từ client hoặc payload sai hình dạng là INVALID_REQUEST, giá trị DTO sai là VALIDATION_ERROR. Response thông thường giữ requestId, pushType null; null Request nội bộ dùng ID 0 cho error, network không gửi response nếu chưa có Request hợp lệ để định danh.
- Dispatcher kiểm tra response round-trip qua codec (kích thước, allowlist, depth/reference/array), không tăng giới hạn/cắt danh sách khi LIST_ACCOUNTS quá lớn; trả INTERNAL_ERROR. Chỉ triển khai mapper Auth/Account ở task này; socket binding/LOGIN trên socket đã login thuộc task 4.4.
- Task 4.3 đã triển khai: `CommandHandler`, `AuthGuard`, `RequestDispatcher`, `DtoMapper`, `LoginHandler`, `AccountHandler`. Giới hạn codec áp dụng cả thông báo lỗi nghiệp vụ; error quá lớn cũng thành INTERNAL_ERROR ngắn. Diagnostic lỗi tối đa 4 lớp exception/cause, mỗi lớp 12 stack frame; bỏ toàn bộ message/Throwable thô. `mvnw.cmd -B -ntp -Pdb-it verify` đã pass 283 unit/mock + 25 PostgreSQL integration test, gồm 69 unit dispatch/handler/mapper và 5 RequestDispatcherIT mới. Integration đi qua serialized Request/Response → dispatcher → service → PostgreSQL, chưa dùng socket thật.

### 9.3. Service – trách nhiệm & quy tắc chính

**Domain User (task 4.1.1):** entity bất biến, không Serializable, chỉ chứa passwordHash, không chứa mật khẩu thô. Factory `User.createNew(...)` dùng `id=0`, `createdAt=null`, `active=true`; `User.restore(...)` yêu cầu ID dương và timestamp khác null, giữ trạng thái active đã lưu. `createdBy` null cho bootstrap hoặc ID dương. Username tối đa 50 ký tự Unicode, fullName/email tối đa 100, hash không rỗng và tối đa 255; email trống thành null. Lỗi invariant là `IllegalArgumentException`; domain không phụ thuộc exception/service/JDBC. `toString()` không chứa hash. Validation định dạng email và băm/kiểm tra BCrypt thuộc service/security.

**UserRepository (task 4.1.2):** interface trả `Optional<User>` khi tìm theo username/ID, `boolean` khi kiểm tra tồn tại username/email, `User` đã được DB cấp ID/timestamp khi `save`, `List<User>` theo ID tăng dần khi `findAll`, và `boolean` khi `updateActiveStatus`. `save` chỉ INSERT user mới (ID 0, chưa có timestamp); không upsert user đã lưu. Email null không trùng. Cập nhật đúng trạng thái hiện tại vẫn trả true nếu ID tồn tại, ID không có trả false. Có overload nhận `Connection` cho `findById`, `save`, `updateActiveStatus` để dùng trong transaction của service: không mở connection thứ hai, không đóng connection caller, không đổi autoCommit/commit/rollback. API thông thường quản lý connection riêng. Repository không thực thi phân quyền; service kiểm tra actor. Lỗi tham số trả `IllegalArgumentException`; lỗi unique username/email trả `ConflictException`, lỗi DB khác trả `QuizException(DATABASE_ERROR)` với thông báo chung và cause. JDBC implementation thuộc task 4.1.3.

**JdbcUserRepository (task 4.1.3 đã triển khai):** inject `DataSource`, dùng `PreparedStatement` và `INSERT ... RETURNING`, map `TIMESTAMPTZ` qua `OffsetDateTime` về `Instant`, giữ nullable email/createdBy. SQLSTATE `23505` cùng tên constraint `users_username_key`/`users_email_key` của schema hiện tại được chuyển thành CONFLICT; lỗi unique khác (kể cả users_pkey), FK, kết nối/query/mapping trả DATABASE_ERROR. Không parse thông báo SQL hoặc đưa chi tiết DB vào thông báo lỗi. Statement/ResultSet được đóng, connection do caller cung cấp và transaction của nó được giữ nguyên. Kiểm chứng task 4.1.4 ngày 10/10/2026: `mvnw.cmd -B -ntp -Pdb-it clean verify` thành công, 149 unit/mock test + 14 PostgreSQL integration test, 0 failures/errors/skipped. Đã kiểm tra UNIQUE, FK, Unicode, timestamp theo timezone, ownership connection, commit/rollback và hai INSERT trùng đồng thời. Xem mục 15.5 để cấu hình DB test.

**Contract services task 4.2:** `AuthService.login(username, password)` trả `AuthResult(token, User)` nội bộ, không Serializable và không lộ token/hash qua toString; handler sau này map sang LoginResultDto. `authenticate(token)` kiểm tra phiên hiện hành và đọc lại user active/role từ DB; `logout(token)` nội bộ idempotent và chỉ xóa đúng token. `AccountService.createAccount(dto, adminId)`, `listAccounts(adminId)`, `setAccountActive(userId, active, adminId)` đều tự kiểm tra actor trong DB; actor thiếu/inactive trả UNAUTHENTICATED, sai role trả FORBIDDEN. Create/set kiểm tra lại actor trong transaction ghi, không SQL/socket trong service. Mật khẩu tạo mới tối thiểu 8 Unicode code point và tối đa 72 byte UTF-8; login không áp dụng ngưỡng 8. Email chỉ kiểm tra định dạng cơ bản local@domain.suffix, không xác minh mailbox. `SessionCoordinator` dùng số khóa cố định theo userId; cùng một instance dùng chung cho Auth/Account để login, xác thực, logout và khóa user phối hợp. BCrypt ở ngoài critical section/transaction ghi; khóa target giữ qua commit và thu hồi phiên. Bind/cleanup socket sẽ triển khai ở task 4.4.

**Task 4.2 đã triển khai:** constructor `AuthService(UserRepository, SessionCoordinator)` và `AccountService(UserRepository, TransactionManager, SessionCoordinator)`; coordinator giữ SessionRegistry và 128 khóa cố định, không tạo map khóa tăng theo số user. `withCurrentSession(token, callback)` kiểm tra token trước/sau callback trong cùng khóa; `withUserLock(userId, callback)` dùng cho đọc lại active trước login và commit/thu hồi phiên khi khóa account. Callback không thực hiện socket I/O hoặc lấy khóa của user khác. Bootstrap phải chia sẻ một coordinator giữa các service; network sau này phối hợp bind qua tầng dispatch để giữ phụ thuộc network → dispatch. Kiểm chứng 10/10/2026: `mvnw.cmd -B -ntp -Pdb-it clean verify` → BUILD SUCCESS, 214 unit/mock + 20 PostgreSQL integration test (gồm 65 unit service/concurrency và 6 AuthAccountServiceIT mới), 0 failures/errors/skipped. Các service chưa được nối vào socket/dispatcher/UI.

| Service | Trách nhiệm | Lưu ý |
|---|---|---|
| `AuthService` | login, kiểm tra `active`, so khớp BCrypt | Sai user/mật khẩu cùng một thông báo `INVALID_CREDENTIALS` |
| `AccountService` | tạo tài khoản, bật/tắt | Chỉ ADMIN; validate username/role |
| `ClassService` | lớp + thành viên | Chỉ học viên (role STUDENT) mới thêm được |
| `QuestionService` | CRUD câu hỏi, lọc theo topic/độ khó | Không xóa cứng nếu đã dùng trong quiz → `active=false` (BR-05) |
| `QuizService` | CRUD quiz, chọn câu hỏi, `open`/`close`/`assign`, sinh PIN | Chỉ sửa khi `DRAFT`; mở được khi có ≥ 1 câu hỏi; chuyển trạng thái đúng `DRAFT→OPEN→CLOSED` |
| `AttemptService` | `start`, `saveAnswer`, `submit`, `expire`, `getState` | Xem 9.4 |
| `GradingService` | chấm điểm thuần (hàm, không I/O) | Dễ unit test; chấm theo đáp án lưu ở DB |
| `ResultService` | lịch sử, danh sách kết quả (tìm/sắp xếp), thống kê | Học viên chỉ xem kết quả của mình (UC-07) |
| `AttemptTimeoutScheduler` | lên lịch auto-submit tại `deadline_at` | Xem 9.4 |
| `AntiCheatService` | ghi sự kiện, đếm vi phạm, áp policy | Xem mục 11 |

### 9.4. Attempt: vòng đời, hết giờ, chống nộp trùng

**`start(studentId, quizId, pin)`** (một transaction):
1. Quiz phải `OPEN` (BR-04/06), `pin` đúng (`WRONG_PIN`), học viên thuộc lớp được giao (`NOT_ASSIGNED`).
2. Nếu đã có Attempt `IN_PROGRESS` còn hạn → **trả lại Attempt đó** (reconnect). Nếu đã `SUBMITTED` → `ATTEMPT_ALREADY_SUBMITTED`.
3. Nếu chưa có: tạo Attempt với `started_at = now`, `deadline_at = now + duration` (đồng hồ **server**, dùng `java.time.Clock` được inject để test), lên lịch timeout.
4. Trả `AttemptStateDto(remainingSeconds, StudentQuestionDto[], savedAnswers)`.

**`saveAnswer`**: kiểm tra Attempt thuộc học viên, `IN_PROGRESS`, `now <= deadline_at + graceSeconds` (ngược lại `ATTEMPT_EXPIRED`); upsert vào `attempt_answers`; trả `remainingSeconds` để client đồng bộ đồng hồ.

**`submit(attemptId, reason)`** — chống nộp trùng (NFR-03, BR-01) bằng cập nhật có điều kiện, **không** chỉ dựa vào `synchronized`:
```sql
UPDATE attempts SET status='SUBMITTED', submitted_at=? WHERE id=? AND status='IN_PROGRESS'
```
- `executeUpdate() == 1` → (trong cùng transaction) chấm từ `attempt_answers` → ghi `results` → commit.
- `== 0` → đã nộp: submit thủ công trả `ATTEMPT_ALREADY_SUBMITTED` (kèm kết quả cũ); scheduler thì bỏ qua êm.
- Submit thủ công quá `deadline_at + grace` → coi như `TIMEOUT`, **chỉ chấm các câu đã lưu trong hạn**, bỏ `finalAnswers` của client.
- Điểm do server tính, client không gửi điểm (BR-08).

**`AttemptTimeoutScheduler`**: `ScheduledExecutorService` + `ConcurrentHashMap<attemptId, ScheduledFuture<?>>`. Hủy future khi đã submit. **Khi server khởi động**: nạp mọi Attempt `IN_PROGRESS` → hết hạn thì submit ngay (`TIMEOUT`), còn hạn thì lên lịch lại. Sau auto-submit, push `PUSH_FORCE_SUBMITTED` (kèm `ResultDto`) nếu học viên đang online.

### 9.5. Transaction & DB
- `DataSourceProvider`: HikariCP; `maximumPoolSize` đọc từ config.
- `TransactionManager.executeInTransaction(Function<Connection, T>)`: `setAutoCommit(false)` → commit / rollback khi lỗi → trả connection về pool. Repository nhận `Connection` (hoặc dùng connection của transaction hiện tại), **không** tự mở connection mới giữa transaction.
- Các thao tác nhiều bước phải trong transaction: `start`, `submit` (update + chấm + insert result), `SET_QUIZ_QUESTIONS`, ghi sự kiện anti-cheat + quyết định policy.

### 9.6. Bảo mật
- BCrypt cho mật khẩu (NFR-04); không log mật khẩu/token.
- Mọi thao tác bảo vệ đi qua `AuthGuard`; ngoài ra service kiểm tra **quyền sở hữu** (học viên chỉ đụng Attempt của mình; giáo viên xem dữ liệu hợp lệ).
- Validate đầu vào ở service (độ dài, đáp án ∈ A–D, thời lượng > 0, …), trả `VALIDATION_ERROR`.

---

## 10. Thiết kế client (JavaFX)

### 10.1. Mạng
- `ServerConnection`: giữ socket; **1 reader thread**; `ConcurrentHashMap<Long, CompletableFuture<Response>> pending`. Gửi request → tạo future theo `requestId` → reader nhận response thì `complete`. Response có `pushType != null` → chuyển cho `PushListener`. Mất kết nối → fail toàn bộ future bằng `ConnectionLostException`. Có timeout cho mỗi request (cấu hình).
- `QuizApi`: mỗi method tương ứng 1 `MessageType`, trả `CompletableFuture<T>` (đã giải DTO/ném lỗi nghiệp vụ theo `ErrorCode`). Không phải gọi `Request`/`Response` trực tiếp ở controller.
- `ClientSession`: user, token, role, attempt hiện tại.

### 10.2. UI
- `SceneNavigator` chuyển màn theo role sau login: STUDENT → `student/dashboard`; TEACHER → `teacher/*`; ADMIN → như TEACHER + `admin/account`.
- Controller chỉ: đọc input, gọi `QuizApi`, cập nhật UI qua `FxAsync.run(...)`. Hiển thị trạng thái loading, validate input, hộp thoại lỗi rõ ràng (NFR-06).
- **Màn hình làm bài (`student/exam`)**: danh sách điều hướng câu (đã/chưa trả lời), chọn đáp án → gọi `SAVE_ANSWER` ngay; `TimerLabel` dùng `remainingSeconds` từ server + `System.nanoTime()` (đơn điệu, không phụ thuộc đồng hồ máy), **đồng bộ lại** mỗi lần `SAVE_ANSWER` trả về; hết giờ → gọi `SUBMIT_ATTEMPT` (server vẫn tự nộp nếu client không gọi). Nhận `PUSH_FORCE_SUBMITTED` → khóa UI và chuyển sang màn kết quả.

---

## 11. Anti-Cheat

### 11.1. Phân vai
- **Client** (`AntiCheatMonitor`) chỉ **phát hiện và báo**; **server** (`AntiCheatService`) **ghi nhận, đếm và quyết định**.

| Sự kiện | Cách phát hiện ở client |
|---|---|
| `FOCUS_LOST` | `stage.focusedProperty()` chuyển `false` khi đang làm bài (bao gồm `iconified`) |
| `COPY_ATTEMPT` / `CUT_ATTEMPT` / `PASTE_ATTEMPT` | `Scene.addEventFilter(KeyEvent.KEY_PRESSED, …)` bắt Ctrl/Cmd + C/X/V (và Ctrl+Insert, Shift+Insert), `consume()` sự kiện |
| `CONTEXT_MENU_ATTEMPT` | `ContextMenuEvent` filter, `consume()` |

Nội dung câu hỏi hiển thị bằng `Label`/`Text` không chọn được; `TextInputControl` (nếu có) tắt context menu.

### 11.2. Server
`REPORT_ANTICHEAT_EVENT(attemptId, type, detail, clientTime)` →
1. Kiểm tra Attempt thuộc học viên và đang `IN_PROGRESS` (nếu đã nộp thì bỏ qua êm, không lỗi).
2. Trong 1 transaction: khóa dòng Attempt (`SELECT … FOR UPDATE`), đếm vi phạm hiện có + 1, gọi `AntiCheatPolicy.decide(count, quiz.maxViolations)`.
3. Ghi `anticheat_events` (có `violation_count_after`, `action_taken`).
4. Thực thi action: `WARNED` → push `PUSH_ANTICHEAT_WARNING`; `AUTO_SUBMITTED` → `AttemptService.submit(id, ANTICHEAT)` rồi push `PUSH_FORCE_SUBMITTED`.

| Policy | Hành vi |
|---|---|
| `LOG_ONLY` | Chỉ ghi nhận, không cảnh báo/không tự nộp (`NONE`) |
| `WARN_THEN_AUTO_SUBMIT` (mặc định) | `count < max` → `WARNED`; `count >= max` → `AUTO_SUBMITTED` |

`AntiCheatPolicy` là interface (Strategy) để thêm policy mới không sửa `AntiCheatService`.

### 11.3. Ràng buộc
- Lỗi ghi sự kiện **không được** làm hỏng chấm điểm/nộp bài (NFR-09): bắt và log, trả phản hồi nhẹ.
- Sự kiện chỉ là bằng chứng; **không** kết luận gian lận từ một sự kiện đơn lẻ (BR-13). Giáo viên xem lịch sử qua `LIST_ATTEMPT_EVENTS`.
- Giới hạn cần nêu trong report: không chặn được chụp màn hình, máy ảo, thiết bị thứ hai, hay tắt client; chỉ phát hiện được thao tác trong ứng dụng.

---

## 12. Quy tắc nghiệp vụ → nơi thực thi

| BR | Nội dung | Thực thi ở |
|---|---|---|
| BR-01, BR-09 | Tối đa 1 lần nộp cuối; Attempt thuộc 1 học viên + 1 quiz | `UNIQUE(quiz_id, student_id)`, `UPDATE … WHERE status='IN_PROGRESS'` |
| BR-02, BR-03 | Server quyết định hạn; client không đổi được | `AttemptService` (đồng hồ server, `deadline_at`) |
| BR-04, BR-06 | Chỉ bắt đầu khi OPEN; CLOSED không nhận Attempt mới | `AttemptService.start` |
| BR-05 | Câu hỏi đã dùng không xóa cứng | `QuestionService` (soft-delete) |
| BR-07 | Chỉ Teacher/Admin sửa câu hỏi & quiz | `AuthGuard` + `QuizService`/`QuestionService` |
| BR-08 | Điểm tính từ đáp án server | `GradingService` |
| BR-10 | Reconnect chỉ khi Attempt còn hợp lệ | `AttemptService.start/getState` |
| BR-11, BR-12, BR-13 | Ghi nhận sự kiện; theo policy; chỉ là bằng chứng | `AntiCheatService` + policy |

---

## 13. Java Design (OOP · SOLID · Patterns)

| Nội dung | Áp dụng |
|---|---|
| Encapsulation | Domain object có validate trong constructor/method; DTO là `record` bất biến |
| Abstraction / Interface | `Repository`, `CommandHandler`, `AntiCheatPolicy`, `Clock` |
| Polymorphism | `CommandHandler` nhiều implementation; policy anti-cheat thay thế được |
| Composition | Service nhận dependency qua constructor (không `new` chéo tầng) |
| SRP | Mỗi handler/service một mảng; `GradingService` chỉ chấm điểm |
| OCP | Thêm `MessageType` = thêm handler, không sửa `RequestDispatcher` |
| DIP | Service phụ thuộc interface repository; wiring ở `ServerMain` |
| Command | `CommandHandler` + `Map<MessageType, CommandHandler>` |
| Repository | `repository/` + `jdbc/` |
| Strategy | `AntiCheatPolicy` |
| Observer / callback | `PushListener` (client) |
| Exception | Hệ `QuizException` có `ErrorCode`; dispatcher chuyển thành `Response` |

Wiring thủ công (không dùng framework DI): `ServerMain` tạo config → DataSource → repositories → services → handlers → dispatcher → server.

---

## 14. Cấu hình

`quiz-server/src/main/resources/server.properties`
```properties
server.port=5555
server.max.connections=100
server.worker.pool.size=8
server.worker.queue.capacity=200
server.request.grace.seconds=3
server.handshake.timeout.ms=5000
server.read.timeout.ms=300000
server.shutdown.timeout.ms=10000

db.url=jdbc:postgresql://localhost:5432/quizdb
db.user=quiz
# Set DB_PASSWORD externally; blank or placeholder passwords are rejected.
db.password=
db.pool.max.size=10
```

`quiz-client/src/main/resources/client.properties`
```properties
server.host=localhost
server.port=5555
request.timeout.seconds=10
```
Có thể ghi đè bằng biến môi trường/system property (đặc biệt `db.password`; **không commit mật khẩu thật**). Thứ tự ưu tiên: environment → system property → file. Phải cấp `DB_PASSWORD` hoặc `db.password` hợp lệ; không còn mật khẩu fallback. `ServerMain` hỗ trợ `--config <path>` để đọc file `*-local.properties` không được commit. Ba timeout mới phải > 0, hỗ trợ `SERVER_HANDSHAKE_TIMEOUT_MS`, `SERVER_READ_TIMEOUT_MS`, `SERVER_SHUTDOWN_TIMEOUT_MS`.

Handshake/read dùng `Socket.setSoTimeout`: giới hạn thời gian chờ mỗi lần đọc, không phải write timeout, TTL phiên hay deadline toàn bộ frame; client nhỏ giọt có thể kéo dài tổng thời gian đọc. Shutdown ngừng accept/submit, dành nửa ngân sách chờ worker rồi đóng mọi socket (kể cả chưa login hoặc đang ghi), cancel task còn chờ và dọn registry. Đóng socket giải phóng writer bị client đọc chậm. Sau đó bootstrap đóng pool DB. Nếu hết ngân sách, worker bị interrupt nhưng JDBC có thể chưa dừng; chưa bảo đảm trần thời gian shutdown toàn hệ thống khi driver/query bỏ qua interrupt. TLS, TTL, rate limit và deadline chống nhỏ giọt còn thuộc backlog.

---

## 15. Yêu cầu, cài đặt & chạy

**Phần mềm:** JDK 21, Maven 3.9+, PostgreSQL 15+, (IDE tùy chọn). Thư viện chính: JavaFX 21, PostgreSQL JDBC, HikariCP, BCrypt, SLF4J + Logback, JUnit 5, Mockito.

### 15.1. Cài PostgreSQL trên Windows

Tải bản PostgreSQL 18 ổn định từ [trang download Windows chính thức](https://www.postgresql.org/download/windows/), mở installer và chọn:

- **PostgreSQL Server**, **Command Line Tools**, **pgAdmin 4**.
- Port **5432**; nếu dùng port khác, thay port trong tất cả lệnh và JDBC URL dưới đây.
- Đặt mật khẩu riêng cho role quản trị **postgres**, ghi nhớ để tạo role/database.
- Chọn Data Directory mới, ví dụ `C:\PostgreSQLData\quiz18`. Nếu máy còn thư mục dữ liệu từ lần cài trước, giữ lại và dùng thư mục mới cho hướng dẫn này.
- Stack Builder có thể bỏ qua; project chỉ cần PostgreSQL và JDBC dependency đã có trong Maven.

Xem [hướng dẫn installer của EDB](https://www.enterprisedb.com/docs/supported-open-source/postgresql/installing/windows/) nếu cần chi tiết.

Mở PowerShell **tại thư mục gốc project**, nơi có `pom.xml`, `mvnw.cmd` và thư mục `database`. Các lệnh bên dưới chạy trong cùng cửa sổ PowerShell. Nếu cài phiên bản/vị trí khác, sửa `$pgBin`:

```powershell
$pgBin = 'C:\Program Files\PostgreSQL\18\bin'
$env:PGCLIENTENCODING = 'UTF8'

& "$pgBin\psql.exe" --version
& "$pgBin\pg_isready.exe" -h 127.0.0.1 -p 5432
```

`pg_isready` cần báo **accepting connections**. Nếu không, kiểm tra dịch vụ PostgreSQL trong Windows Services và port đã chọn khi cài.

### 15.2. Tạo role và database phát triển/kiểm thử

| Mục đích | Database | Role kết nối |
|---|---|---|
| Phát triển, dữ liệu demo | `quizdb` | `quiz` |
| Integration test riêng | `quizdb_test` | `quiz_test` |

Kết nối quản trị; nhập mật khẩu **postgres** đã đặt trong installer:

```powershell
& "$pgBin\psql.exe" -h 127.0.0.1 -p 5432 -U postgres -d postgres -W
```

Tại prompt `postgres=#`, nhập **từng lệnh** sau. `\password` sẽ yêu cầu nhập mật khẩu mới hai lần; mật khẩu không hiện trên terminal. Đây là các lệnh trong **psql**, không phải lệnh PowerShell:

```sql
CREATE ROLE quiz LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password quiz
CREATE DATABASE quizdb OWNER quiz ENCODING 'UTF8' TEMPLATE template0;

CREATE ROLE quiz_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
\password quiz_test
CREATE DATABASE quizdb_test OWNER quiz_test ENCODING 'UTF8' TEMPLATE template0;
\q
```

Chạy CREATE DATABASE riêng, không bao trong BEGIN/COMMIT. Nếu role/database đã tồn tại, kiểm tra cấu hình hiện hữu trước khi tiếp tục; không xóa database để chạy lại hướng dẫn. [Tài liệu CREATE DATABASE](https://www.postgresql.org/docs/18/sql-createdatabase.html), [psql và `\password`](https://www.postgresql.org/docs/18/app-psql.html).

### 15.3. Khởi tạo schema và kiểm tra bảng

Trở lại PowerShell, chạy **một lần trên mỗi database mới**. Mỗi lệnh dùng mật khẩu của role trong tham số `-U`:

```powershell
& "$pgBin\psql.exe" -h 127.0.0.1 -p 5432 -U quiz -d quizdb -W -v ON_ERROR_STOP=1 -f database/schema.sql

& "$pgBin\psql.exe" -h 127.0.0.1 -p 5432 -U quiz_test -d quizdb_test -W -v ON_ERROR_STOP=1 -f database/schema.sql
```

Cả hai lệnh cần kết thúc bằng **COMMIT**, không có ERROR. Sau đó kiểm tra:

```powershell
& "$pgBin\psql.exe" -h 127.0.0.1 -p 5432 -U quiz -d quizdb -W -c "\dt"

& "$pgBin\psql.exe" -h 127.0.0.1 -p 5432 -U quiz_test -d quizdb_test -W -c "\dt"
```

Kết quả dự kiến là **11 bảng**, gồm users, classes, class_members, questions, quizzes, quiz_questions, quiz_assignments, attempts, attempt_answers, results, anticheat_events.

Schema có transaction, không DROP bảng và báo lỗi nếu bảng đã tồn tại; không dùng script này làm migration. Lỗi trước COMMIT khiến các bảng vừa tạo bị rollback khi kết nối đóng. Chỉ chạy seed sau khi schema thành công.

### 15.4. Seed demo — chỉ cho database phát triển

Trên `quizdb` vừa tạo schema và còn trống:

```powershell
& "$pgBin\psql.exe" -h 127.0.0.1 -p 5432 -U quiz -d quizdb -W -v ON_ERROR_STOP=1 -c "SET quiz.allow_demo_seed = 'on'" -f database/seed.sql
```

Seed cần opt-in, bảng users trống và các sequence liên quan chưa dùng. Kết quả phải kết thúc bằng COMMIT. Tài khoản ứng dụng mẫu: `admin`, `teacher1`, `teacher2`, `student01`–`student50`, cùng mật khẩu **password123**. Đây là tài khoản Online Quiz, khác với các role PostgreSQL `postgres`/`quiz`/`quiz_test`; chỉ dùng cho demo, không dùng triển khai thật.

Giữ `quizdb_test` **không có seed demo** để integration test tạo fixture riêng.

### 15.5. Cấu hình JDBC và mật khẩu local

Mặc định `server.properties` dùng `jdbc:postgresql://localhost:5432/quizdb`, role `quiz`, password để trống và bắt buộc cấp từ bên ngoài. Để chuẩn bị cấu hình phát triển, đặt trong PowerShell:

```powershell
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/quizdb'
$env:DB_USER = 'quiz'
$taskDbSecret = Read-Host 'Mật khẩu PostgreSQL của role quiz' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $taskDbSecret).Password
Remove-Variable taskDbSecret
```

ServerConfig đọc các biến này; password không được ghi vào README hoặc file properties đã track. Cũng có thể dùng `ServerConfig.load(Path)` với file `*-local.properties` đã được gitignore. Biến môi trường trên chỉ có trong phiên PowerShell hiện tại và tiến trình khởi chạy từ phiên đó. `PGCLIENTENCODING` dành cho psql, không phải cấu hình JDBC.

**Cấu hình PostgreSQL integration test (`db-it`):** tạo file UTF-8 `db-test-local.properties` tại thư mục gốc project, điền mật khẩu của role `quiz_test` tại máy. File khớp `*-local.properties` trong gitignore và không được commit:

```properties
db.url=jdbc:postgresql://127.0.0.1:5432/quizdb_test
db.user=quiz_test
db.password=
```

Nếu mật khẩu chứa dấu backslash, viết `\\` cho một backslash trong file properties. Có thể thay cấu hình file bằng `QUIZ_TEST_DB_URL`, `QUIZ_TEST_DB_USER`, `QUIZ_TEST_DB_PASSWORD`; các biến test này ưu tiên hơn file. Test không dùng `DB_URL`/`DB_USER`/`DB_PASSWORD` của ứng dụng làm fallback. Có thể chọn file khác bằng tham số `"-Dquiz.test.config=<đường dẫn file>"`.

Profile chỉ chấp nhận JDBC URL localhost/127.0.0.1 với port hợp lệ, database **quizdb_test** và role **quiz_test**; từ chối URL có query parameter. Test kiểm tra lại database/role kết nối thực tế và đủ 11 bảng trước khi tạo fixture. Thiếu cấu hình/mật khẩu, DB không kết nối được hoặc thiếu schema sẽ làm build fail, không skip. Test dọn đúng username UUID của fixture sau mỗi ca; không truncate bảng, không reset sequence, không chạy seed. Các lần INSERT kể cả rollback có thể làm tăng sequence ID; không dùng DB test này để seed demo yêu cầu sequence chưa dùng.

### 15.6. Xử lý lỗi setup thường gặp

| Lỗi | Cách xử lý |
|---|---|
| `fe_sendauth: no password supplied` | Nhập mật khẩu khi hiện Password; ký tự không hiện là bình thường. Không nhấn Enter khi chưa nhập |
| Chuyển encoding WIN1252 sang UTF8 thất bại | Đặt `$env:PGCLIENTENCODING = 'UTF8'` rồi chạy lại schema. Schema/seed đã khai báo `SET client_encoding TO 'UTF8'` đầu file, trước comment tiếng Việt |
| `relation "users" does not exist` khi seed | Schema chưa chạy thành công hoặc đang kết nối sai DB; chạy schema trước và kiểm tra `\dt` |
| `Did not find any tables` | Kiểm tra đúng database/role, xem ERROR từ bước schema. Nếu schema bị lỗi giữa chừng, transaction đã rollback |
| `relation ... already exists` | Schema đã có hoặc DB không mới; không chạy lại script khởi tạo, kiểm tra bảng trước |
| `connection refused` / `no response` | Kiểm tra dịch vụ PostgreSQL đã chạy, host và port đúng |

Các file SQL của project là UTF-8. [Tài liệu PostgreSQL về client encoding](https://www.postgresql.org/docs/18/multibyte.html).

### 15.7. Build và chạy

Build bằng Maven Wrapper của project:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Chạy thêm PostgreSQL integration test sau khi chuẩn bị mục 15.2–15.3 và file cấu hình test ở mục 15.5:

```powershell
.\mvnw.cmd -B -ntp -Pdb-it clean verify
```

Surefire chạy `*Test` mặc định; profile `db-it` dùng Failsafe chạy `*IT` ở integration-test/verify. Cả hai dùng classpath trực tiếp (`useManifestOnlyJar=false`) để tránh lỗi manifest classpath khác ổ đĩa trên Windows. Report unit nằm ở `quiz-server/target/surefire-reports`, report DB ở `quiz-server/target/failsafe-reports`.

**Chạy server (PowerShell tại thư mục project)**

Tạo `server-local.properties` bằng cách sao chép `quiz-server/src/main/resources/server.properties`, điền `db.password` của role `quiz` trên máy. File `*-local.properties` được Git ignore. Giữ `db.url` trỏ tới `quizdb` khi chạy ứng dụng; không dùng cấu hình test cho dữ liệu ứng dụng. Environment có ưu tiên cao hơn file, nên kiểm tra các biến `DB_*`/`SERVER_*` nếu cấu hình thực tế khác file.

```powershell
# Install common/server để exec:java phân giải dependency giữa các module.
.\mvnw.cmd -B -ntp -pl quiz-server -am install '-DskipTests'
.\mvnw.cmd -B -ntp -pl quiz-server exec:java '-Dexec.args=--config server-local.properties'
```

Server kiểm tra truy cập bảng `users` trước khi mở port; không tự chạy schema hoặc seed. Sai cấu hình, thiếu bảng/quyền DB hoặc port đã dùng sẽ làm startup thất bại và đóng pool. Dùng Ctrl+C để shutdown. Port thực tế phải 1–65535; port 0 chỉ dùng khi inject `QuizServer` trong test.

Mỗi socket tạo/flush ObjectOutputStream trước khi đọc header; dùng `MessageCodec.write/read` cho frame. Socket chỉ xử lý một request tại một thời điểm, giữ thứ tự request; socket khác vẫn được worker pool phục vụ. Vượt giới hạn connection thì socket mới đóng mà không gửi response giả. Worker pool cố định, queue hữu hạn; queue đầy trả SERVER_BUSY với đúng requestId. LOGIN thành công bind token vào socket và đóng socket phiên cũ; LOGIN lần nữa trên socket còn binding trả CONFLICT. Token từ socket khác bị từ chối. LOGOUT bỏ binding nhưng giữ socket để LOGIN lại; disconnect chỉ thu hồi token thuộc socket đó.

**Client/benchmark (entrypoint chưa triển khai)**
```bash
# Client (mở nhiều cửa sổ để thử nhiều học viên)
mvn -pl quiz-client javafx:run
# Benchmark (server phải đang chạy)
mvn -pl quiz-benchmark exec:java -Dexec.mainClass=quiz.benchmark.LoadTestRunner -Dexec.args="--clients 10 --output results.csv"
```
Tài khoản mẫu: xem mục 15.4 và `database/seed.sql`.

**Test hiện tại:** `.\mvnw.cmd -B -ntp -Pdb-it verify` đã pass **295 unit/mock/socket + 28 PostgreSQL integration = 323 tests**, 0 failures/errors/skipped. `mvn test` / `mvn verify` chỉ chạy 295 test mặc định; profile `db-it` bổ sung repository/transaction/Auth/Account/dispatcher và `ServerBootstrapIT` đi qua bootstrap → TCP → services → PostgreSQL thật trên `quizdb_test`. Test socket với dịch vụ giả được tách khỏi integration DB; chưa có E2E UI JavaFX đầy đủ. Server entrypoint đã triển khai ở task 4.4; client/benchmark còn thuộc task sau.

---

## 16. Kế hoạch kiểm thử

| Loại | Phạm vi | Vị trí |
|---|---|---|
| Unit | `GradingService`, validation, `AntiCheatPolicy`, `AuthService` (mock repo), `MessageCodec` | `quiz-server/src/test/.../unit`, `quiz-common/src/test` |
| Integration | Service + JDBC + DB thật (rollback/dọn dữ liệu mỗi test) | `.../integration` |
| System/Network | Server thật + client socket: login, vòng đời quiz, nộp bài, hết giờ | `.../network` |
| Anti-Cheat | Gửi sự kiện → ghi DB → cảnh báo → auto-submit theo ngưỡng; lỗi ghi sự kiện không ảnh hưởng chấm điểm | unit + network |
| Concurrency | Nhiều client cùng connect/nộp; **nộp trùng đồng thời** (thủ công + scheduler) chỉ ra 1 kết quả | network |
| Performance | Xem mục 17 | `quiz-benchmark` |

**Bảng test case** (điền **Actual/Status** bằng kết quả chạy thật; chưa chạy thì để `Not run`)

| ID | Test case | Input | Expected | Actual | Status |
|---|---|---|---|---|---|
| TC-AUTH-01 | Đăng nhập đúng | user/pass hợp lệ | success + token + role | | Not run |
| TC-AUTH-02 | Sai mật khẩu | pass sai | `INVALID_CREDENTIALS` | | Not run |
| TC-AUTH-03 | Học viên gọi API giáo viên | token STUDENT + `CREATE_QUIZ` | `FORBIDDEN` | | Not run |
| TC-Q-01 | Xóa câu hỏi đã dùng | `DEACTIVATE_QUESTION` | `active=false`, không xóa dòng | | Not run |
| TC-QZ-01 | Mở quiz không có câu hỏi | `OPEN_QUIZ` | `VALIDATION_ERROR` | | Not run |
| TC-QZ-02 | Sửa quiz đã OPEN | `UPDATE_QUIZ` | `CONFLICT` | | Not run |
| TC-AT-01 | Start sai PIN | pin sai | `WRONG_PIN` | | Not run |
| TC-AT-02 | Start khi quiz CLOSED | — | `QUIZ_CLOSED` | | Not run |
| TC-AT-03 | Học viên không thuộc lớp được giao | — | `NOT_ASSIGNED` | | Not run |
| TC-AT-04 | Start lại khi đang làm dở | — | trả Attempt cũ, `remainingSeconds` giảm | | Not run |
| TC-AT-05 | Nộp bài hợp lệ | đáp án | score đúng theo `GradingService` | | Not run |
| TC-AT-06 | Nộp lần 2 | — | `ATTEMPT_ALREADY_SUBMITTED` | | Not run |
| TC-AT-07 | Hết giờ không nộp | đợi quá deadline | auto-submit `TIMEOUT`, chỉ chấm câu đã lưu | | Not run |
| TC-AT-08 | Lưu đáp án sau deadline+grace | — | `ATTEMPT_EXPIRED` | | Not run |
| TC-CC-01 | Nộp trùng đồng thời (N luồng) | N × `SUBMIT` | đúng 1 result trong DB | | Not run |
| TC-AC-01 | Mất focus lần 1 | `FOCUS_LOST` | ghi DB + `PUSH_ANTICHEAT_WARNING` | | Not run |
| TC-AC-02 | Vi phạm đạt ngưỡng | `max_violations` lần | auto-submit `ANTICHEAT` | | Not run |
| TC-AC-03 | Policy `LOG_ONLY` | nhiều sự kiện | chỉ ghi, không auto-submit | | Not run |
| TC-NET-01 | Client ngắt đột ngột | đóng socket | server không lỗi, giải phóng tài nguyên | | Not run |
| TC-NET-02 | Request không hợp lệ | type lạ / payload sai | `INVALID_REQUEST`, kết nối vẫn sống | | Not run |

---

## 17. Đánh giá thực nghiệm

**Kịch bản `LoadTestRunner` (mỗi client ảo):** login → list quiz → start attempt → lưu K đáp án → submit → ghi nhận thời gian từng request.

**Biến thay đổi:** số client {1, 5, 10, 20, 50 (nếu máy cho phép)}; `server.worker.pool.size` {ví dụ 2, 4, 8, 16}; số câu hỏi/quiz.

**Chỉ số:** thời gian phản hồi trung bình/p95, throughput (req/s), tỷ lệ thành công/lỗi, CPU/RAM (ghi rõ cách đo), số kết nối đồng thời.

**Xuất CSV** (`results.csv`): `clients, workerPool, requests, avgMs, p95Ms, throughput, successRate, errors`.

### Kết quả
_Chưa có số liệu. Điền bảng/biểu đồ từ file CSV đã chạy thật, kèm cấu hình máy (CPU, RAM, OS, JDK) và ngày chạy. **Không ước lượng hay bịa số.**_

---

## 18. Novelty & Đóng góp (điền trong quá trình làm)

Gợi ý các đóng góp kỹ thuật thật sự của dự án (xác nhận lại khi đã làm xong):
- Chống nộp trùng bằng **cập nhật có điều kiện + transaction**, kiểm chứng bằng concurrency test.
- Cơ chế **hết giờ phía server** (`AttemptTimeoutScheduler`, phục hồi sau khi server restart).
- **Protocol + Command dispatch** có phân quyền (`AuthGuard`) và PUSH hai chiều trên cùng một kết nối; `ObjectInputFilter` whitelist.
- **Anti-Cheat theo Strategy**: client phát hiện, server quyết định, lưu bằng chứng theo Attempt.
- **Thread model hai tầng** (connection pool + worker pool) và thực nghiệm ảnh hưởng của pool size.

_(Tùy chọn nếu còn thời gian: `@RequireRole` + Dynamic Proxy cho kiểm tra quyền ở service.)_

Baseline: hệ thống thi trắc nghiệm client/server cơ bản (1 thread/1 client, chấm điểm ở client hoặc không có timeout phía server). Điền so sánh định lượng sau khi có số liệu.

---

## 19. Phân công công việc & Lộ trình thực hiện

### 19.1. Phân công trách nhiệm thành viên (Theo JSD_Final)

> **Thứ tự thực hiện (Workflow Pipeline):**
> **Việt (Auth, User, DB)** ➔ **Võ (Question Bank)** ➔ **Bắc (Quiz & Assignment)** ➔ **Đôn (Student Exam)** ➔ **Nam (Anti-Cheat & Monitoring)**

| Thành viên | Trách nhiệm chính | Requirement ID | Nhiệm vụ cụ thể | Thứ tự thực hiện |
|---|---|---|---|---|
| **Việt** | **Authentication + User + Database & UI tương ứng** | FR-A01, FR-S02, FR-AD01, NFR-04, NFR-05 | • Khởi tạo Database Schema (`schema.sql`) & dữ liệu mẫu (`seed.sql`) cho cả nhóm.<br>• Xây dựng `quiz-common` (protocol: `Request`, `Response`, `MessageType`, `ErrorCode`, `MessageCodec`, enums, DTOs cơ bản).<br>• Phía Server: DataSource HikariCP, `TransactionManager`, BCrypt `PasswordHasher`, `TokenGenerator`, Exception hierarchy, `UserSession`, `SessionRegistry`, `AuthService`, `AccountService`, `AuthGuard`, `RequestDispatcher`, `LoginHandler`, `AccountHandler`.<br>• Phía Client: Socket TCP client nền tảng (`ServerConnection`, `QuizApi`), `ClientSession`, `SceneNavigator`, UI Đăng nhập & UI Admin cấp/quản lý tài khoản. | **1 (Làm đầu tiên - Nền tảng)** |
| **Võ** | **Question Bank & UI tương ứng** | FR-A02, FR-A03, FR-A05, FR-T05 | • Quản lý ngân hàng câu hỏi (Tạo, xem, sửa).<br>• Phân loại topic/category, gán độ khó (Easy/Medium/Hard).<br>• Chọn câu hỏi đưa vào bài test.<br>• Phía Server: `QuestionRepository`, `QuestionService`, `QuestionHandler`.<br>• Phía Client: UI quản lý câu hỏi cho Teacher. | **2 (Sau Việt)** |
| **Bắc** | **Quiz & Assignment & UI tương ứng** | FR-A04, FR-A05, FR-A06, FR-A07, FR-A08, FR-A09, FR-S02, FR-S04 | • Tạo, sửa, mở/đóng Quiz (`DRAFT` ➔ `OPEN` ➔ `CLOSED`), sinh mã PIN.<br>• Thiết lập thời lượng (`duration_minutes`), chọn câu hỏi vào Quiz.<br>• Quản lý lớp & giao Quiz cho lớp.<br>• Phía Server: `ClassRepository`, `QuizRepository`, `ClassService`, `QuizService`, `ClassHandler`, `QuizHandler`.<br>• Phía Client: UI tạo Quiz, quản lý lớp, giao bài kiểm tra. | **3 (Sau Võ)** |
| **Đôn** | **Student Exam & UI tương ứng** | FR-S02, FR-S03, FR-S04, FR-S05, FR-S06, FR-S07, FR-S08, FR-S09, FR-S10, FR-S11, FR-S12, FR-T04, NFR-03 | • Học viên xem quiz được giao, nhập PIN tham gia thi.<br>• Server tạo Attempt, quản lý thời gian đếm ngược chính xác.<br>• Chọn câu trả lời, lưu đáp án từng câu, nộp bài thủ công / tự động hết giờ.<br>• Server chấm điểm tự động (`GradingService`), chống nộp trùng final submit.<br>• Xem kết quả, xem lịch sử làm bài.<br>• Phía Server: `AttemptService`, `GradingService`, `ResultService`, `AttemptTimeoutScheduler`.<br>• Phía Client: UI làm bài thi (timer, chuyển câu, nộp), UI kết quả & lịch sử. | **4 (Sau Bắc)** |
| **Nam** | **Anti-Cheat & Monitoring & UI tương ứng** | FR-S13, FR-S14, FR-T09, BR-12, BR-13, NFR-09 | • Phía Client: Monitor hành vi (rời cửa sổ thi / mất focus, phím tắt Copy/Paste/Cut, chuột phải context menu).<br>• Báo cáo vi phạm về Server (`REPORT_ANTICHEAT_EVENT`).<br>• Phía Server: Lưu log vi phạm theo Attempt, đếm vi phạm, kiểm tra policy (`WARN_THEN_AUTO_SUBMIT` / `LOG_ONLY`).<br>• Cảnh báo học viên (`PUSH_ANTICHEAT_WARNING`) hoặc tự động nộp bài khi đạt ngưỡng vi phạm.<br>• UI giám sát sự kiện vi phạm cho giáo viên. | **5 (Sau Đôn)** |

### 19.2. Lộ trình Milestones & Definition of Done

| Milestone | Nội dung | Done khi |
|---|---|---|
| M1 – Nền tảng | Parent pom, 4 module build được; `common` (Request/Response/MessageType/ErrorCode/MessageCodec); `schema.sql`; `QuizServer` + `ClientHandler` + `RequestDispatcher` (echo/ping); client kết nối được | `mvn clean verify` xanh; test codec; client gửi `LOGIN` giả và nhận response |
| M2 – Auth & Câu hỏi | Auth, `SessionRegistry`, `AuthGuard`, DB pool + `TransactionManager`, CRUD câu hỏi, Account (Admin) | Test unit + integration đạt; sai quyền bị chặn |
| M3 – Quiz & Attempt | Quiz, lớp, giao bài, PIN, `start/saveAnswer/submit`, `GradingService`, `AttemptTimeoutScheduler`, Result/History/Statistics | Concurrency test nộp trùng đạt; hết giờ tự nộp |
| M4 – Client UI | Login, teacher (question/quiz/assignment/result), student (dashboard/exam/result), admin account; timer | Chạy trọn luồng 12.1 và 12.2 trên UI |
| M5 – Anti-Cheat & hoàn thiện | Monitor + service + policy, push cảnh báo/force-submit, xem sự kiện (teacher), reconnect | Test AC đạt; reconnect hoạt động |
| M6 – Đánh giá & nộp | Benchmark, bảng test thật, refactor, README/report, Compilatio | Checklist mục 22 hoàn tất |

Mỗi milestone kết thúc bằng: build xanh, test liên quan chạy xong, README được cập nhật nếu thiết kế thay đổi.

---

## 20. Quy ước code & Git

### 20.1. Coding Conventions
- Package `quiz.<module>.<layer>`; class `PascalCase`, method/biến `camelCase`, hằng `UPPER_SNAKE_CASE`.
- Method ≤ ~40 dòng; tránh lặp code; ưu tiên `record`, `Optional`, stream khi rõ nghĩa; **không** nuốt exception (`catch` rỗng).
- Log bằng SLF4J (`info` sự kiện chính, `warn` lỗi người dùng, `error` lỗi hệ thống). Không log mật khẩu/token.
- Javadoc cho class/method công khai quan trọng (service, protocol).

### 20.2. Quy ước Git & Branching
- **Nhánh `develop` / `main`:** ổn định, chỉ merge code đã pass build và test.
- **Quy ước đặt tên branch:** `<type>/<feature-name>`
  - Ví dụ: `feature/auth-login`, `feature/student-exam`, `feature/question-bank`, `fix/quiz-submission`.
- **Quy ước commit:** `<type>: <mô tả ngắn>`
  - Ví dụ: `feat: implement login`, `feat: add schema.sql`, `fix: fix login validation`, `test: add auth unit test`.
- **Workflow:** Làm việc trên branch tính năng riêng ➔ commit nhỏ, rõ ràng ➔ tạo Pull Request / merge qua review; đảm bảo mọi thành viên đều có commit thực tế.

---

## 21. Hạn chế & Hướng phát triển

**Hạn chế (cập nhật khi hoàn thành):** Anti-Cheat chỉ ở mức ứng dụng (không chống chụp màn hình/máy ảo); chỉ một loại câu hỏi; 1 Attempt/học viên/quiz; protocol dùng Java serialization (gắn với Java, đã giảm rủi ro bằng filter); quản lý lớp tối giản.

**Hướng phát triển:** nhiều loại câu hỏi, cache câu hỏi, NIO/Producer-Consumer cho push, Anti-Cheat nâng cao (nhiều hành vi, chính sách linh hoạt), nhiều lần làm bài, TLS cho kết nối.

---

## 22. Tài liệu tham khảo & Checklist nộp bài

**Tham khảo:** _(điền: tài liệu Java/JavaFX chính thức, HikariCP, PostgreSQL JDBC, bài giảng, sách… — trích dẫn đầy đủ trong report)_

**Báo cáo:** `report/GroupXX_OnlineQuiz_Report.docx` (nộp Compilatio, similarity ≤ 20%).

**Cấu trúc nộp LMS:**
```text
project/
├── source/      # toàn bộ cây online-quiz/ (kèm database/)
├── report/      # báo cáo .docx
└── README.md
```

**Checklist cuối**
- [ ] Source code đầy đủ, build được (`mvn clean verify`)
- [ ] Chạy được: server + nhiều client
- [ ] README đủ hướng dẫn cài/chạy/test
- [ ] `schema.sql` + `seed.sql` + file cấu hình có trong source
- [ ] Test case đã chạy, có bằng chứng (Actual/Status thật)
- [ ] Kết quả thực nghiệm thật (CSV + biểu đồ), không ước lượng
- [ ] Novelty/Contribution nêu rõ
- [ ] Report hoàn chỉnh, đúng với source code
- [ ] Đã kiểm tra Compilatio, similarity ≤ 20%
- [ ] Không dùng code không rõ nguồn; mọi thành viên giải thích được code của mình
- [ ] Đã nộp qua LMS (không dùng Drive/USB)
