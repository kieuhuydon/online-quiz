# 📋 Kế Hoạch Chi Tiết: Authentication, User, Database & UI

> **Người thực hiện:** Việt (Trưởng nhóm / Nền tảng kiến trúc)  
> **Dự án:** Online Quiz cho Trung tâm đào tạo (Java Client/Server)  
> **Nhánh phát triển:** `feature/auth-user-db`  
> **Mục tiêu:** Xây dựng toàn bộ hạ tầng chung (Build, Database, Protocol) và hoàn thiện trọn vẹn mô-đun Xác thực (Authentication), Quản lý người dùng (User Management) cả phía Server lẫn Client (JavaFX) để bàn giao cho các thành viên tiếp theo.

---

## 🧭 Pipeline Phối Hợp Nhóm

```text
[ Việt: Auth + User + DB + UI ] (ĐẦU TIÊN)
               │
               ▼
   [ Võ: Question Bank + UI ]
               │
               ▼
   [ Bắc: Quiz + Assignment + UI ]
               │
               ▼
   [ Đôn: Student Exam + UI ]
               │
               ▼
   [ Nam: Anti-Cheat + Monitoring + UI ]
```

---

## 📊 Bảng Tiến Độ Tổng Quan

| Giai đoạn | Mô tả | Số task | Trạng thái |
|---|---|:---:|:---:|
| **Giai đoạn 1** | Build System (Maven Multi-Module) & Database Setup | 3 | **HOÀN THÀNH (100%)** ✅ |
| **Giai đoạn 2** | `quiz-common` (Protocol Envelope, Enums, DTOs, Codec) | 4 | **HOÀN THÀNH (100%)** ✅ |
| **Giai đoạn 3** | `quiz-server` Hạ tầng kỹ thuật (DB Pool, Security, Session) | 3 | **HOÀN THÀNH (100%)** ✅ |
| **Giai đoạn 4** | `quiz-server` Nghiệp vụ Auth & User (Repo, Service, Dispatcher) | 4 | **TIẾP THEO** ⏳ |
| **Giai đoạn 5** | `quiz-client` Socket Client & JavaFX UI (Login, Admin Account) | 4 | **CHỜ THỰC HIỆN** ⏳ |
| **Giai đoạn 6** | Kiểm thử tích hợp (E2E Socket Test) & Bàn giao nền tảng | 3 | **CHỜ THỰC HIỆN** ⏳ |

---

## 📝 Chi Tiết Từng Giai Đoạn & Task List

### 🧱 Giai đoạn 1: Build System & Database Setup (ĐÃ XONG ✅)
* [x] **Task 1.1: Thiết lập Maven Multi-Module (`pom.xml` & Maven Wrapper)**
  - Quản lý phiên bản tập trung: Java 21, PostgreSQL JDBC 42.7.14, HikariCP 5.1.0, jBCrypt 0.4, JavaFX 21.0.5, SLF4J 2.0.16, Logback 1.6.5, JUnit 5.11.3, Mockito 5.14.2.
  - Cấu hình 4 module: `quiz-common`, `quiz-server`, `quiz-client`, `quiz-benchmark`.
  - Tích hợp Maven Wrapper (`mvnw`, `mvnw.cmd`).
* [x] **Task 1.2: DDL PostgreSQL Schema (`database/schema.sql`)**
  - Khởi tạo trọn vẹn 11 bảng: `users`, `classes`, `class_members`, `questions`, `quizzes`, `quiz_questions`, `quiz_assignments`, `attempts`, `attempt_answers`, `results`, `anticheat_events`.
  - Khóa chính, khóa ngoại `ON DELETE CASCADE`, ràng buộc `CHECK`, `UNIQUE` và các Index tối ưu truy vấn.
* [x] **Task 1.3: Dữ liệu mẫu ban đầu (`database/seed.sql`)**
  - 1 Admin (`admin`), 2 Teacher (`teacher1`, `teacher2`), 50 Học viên (`student01` -> `student50`).
  - Mật khẩu băm chuẩn BCrypt cho `password123`: `$2a$10$RMj7Oqje6a.XQorzyUTXuuXnV/EKY/.yYf4YaxIMOz67D/tFVZ.0y`.
  - 3 lớp học mẫu, 30 câu hỏi mẫu phân bổ theo topic & độ khó, 2 đề thi mẫu.

---

### 📦 Giai đoạn 2: `quiz-common` – Giao thức mạng & DTOs (ĐÃ XONG ✅)
* [x] **Task 2.1: Hệ thống Enums dùng chung (`quiz.common.enums`)**
  - `Role`: `STUDENT, TEACHER, ADMIN`.
  - `QuizStatus`: `DRAFT, OPEN, CLOSED`.
  - `AttemptStatus`: `IN_PROGRESS, SUBMITTED`.
  - `Difficulty`: `EASY, MEDIUM, HARD`.
  - `SubmitReason`: `MANUAL, TIMEOUT, ANTICHEAT`.
  - `AntiCheatEventType`: `FOCUS_LOST, COPY_ATTEMPT, PASTE_ATTEMPT, CUT_ATTEMPT, CONTEXT_MENU_ATTEMPT, PROHIBITED_PROCESS`.
  - `AntiCheatPolicyType`: `LOG_ONLY, WARN_THEN_AUTO_SUBMIT`.
* [x] **Task 2.2: Giao thức Envelope (`quiz.common.protocol`)**
  - `MessageType`: 36 loại thông điệp giao tiếp giữa client và server.
  - `ErrorCode`: 19 mã lỗi chuẩn hóa cho toàn ứng dụng.
  - `Request`: Record `(long requestId, MessageType type, String token, Serializable payload)`.
  - `Response`: Record `(long requestId, boolean success, ErrorCode errorCode, String message, MessageType pushType, Serializable payload)`.
* [x] **Task 2.3: Bộ mã hóa an toàn (`MessageCodec.java`)**
  - Frame có prefix length, tối đa 1 MiB, mỗi message serialize độc lập; reset/flush stream ngoài.
  - `ObjectInputFilter` allowlist và giới hạn depth/reference/array theo message; chặn object injection vào stream ngoài. Không thay thế TLS hoặc validation nghiệp vụ.
  - Test codec gồm round-trip, dữ liệu sai/lớn/sâu và stream sống lâu; xem kết quả cập nhật bên dưới.
* [x] **Task 2.4: Trọn bộ DTOs (`quiz.common.dto`)**
  - Auth/Account: `LoginRequestDto`, `LoginResultDto`, `CreateAccountDto`, `AccountDto`, `SetAccountActiveDto`.
  - Toàn bộ DTOs khác phục vụ các phân hệ sau: `ClassDto`, `ClassMemberDto`, `QuestionDto`, `StudentQuestionDto` (không lộ đáp án), `QuizDto`, `AssignQuizDto`, `StartAttemptRequestDto`, `AttemptStateDto`, `SaveAnswerDto`, `SubmitAttemptDto`, `ResultDto`, `QuizStatisticsDto`, `ReportAntiCheatDto`, `AntiCheatWarningDto`.

---

### ⚙️ Giai đoạn 3: `quiz-server` – Hạ tầng Kỹ thuật & DB (ĐÃ XONG ✅)
* [x] **Task 3.1: Đọc Cấu hình & HikariCP Connection Pool**
  - File cấu hình: `quiz-server/src/main/resources/server.properties` (port, db url, user, pool sizes).
  - `ServerConfig.java`: Đọc và validate thông số cấu hình.
  - `DataSourceProvider.java`: Khởi tạo và quản lý HikariDataSource singleton/lifecycle.
  - `TransactionManager.java`: Quản lý giao dịch `executeInTransaction(Function<Connection, T>)` (`setAutoCommit(false)`, `commit`, `rollback`).
* [x] **Task 3.2: Bảo mật & Xử lý Ngoại lệ (Security & Exceptions)**
  - `PasswordHasher.java`: Tiện ích băm và xác thực mật khẩu BCrypt (`hashpw`, `checkpw`).
  - `TokenGenerator.java`: Sinh token phiên ngẫu nhiên an toàn bằng `SecureRandom` (≥ 128 bit).
  - Hệ thống Exception: `QuizException` (base kèm `ErrorCode`), `ValidationException`, `UnauthorizedException`, `ForbiddenException`, `NotFoundException`, `ConflictException`.
* [x] **Task 3.3: Quản lý Phiên làm việc & Kết nối Mạng (Session & Connection)**
  - `UserSession.java`: Record `(long userId, Role role, String username)`.
  - `SessionRegistry.java`: `ConcurrentHashMap<String, UserSession>` quản lý token đang hoạt động.
  - `ClientConnection.java`: Bọc Socket và ObjectOutputStream, phương thức `synchronized send(Response)` đảm bảo an toàn đa luồng.
  - `ConnectionRegistry.java`: `ConcurrentHashMap<Long, ClientConnection>` lưu kết nối socket của user online phục vụ PUSH.
  - Unit test cấu hình, database pool, transaction, bảo mật và session/kết nối; xem kết quả cập nhật bên dưới.

### Bản sửa hạ tầng 09/10/2026 — chỉ giai đoạn 1–3

- Schema bỏ DROP, thêm transaction và CHECK theo quy tắc đã chốt; seed demo yêu cầu opt-in, schema trống và sequence chưa dùng.
- Cập nhật dependency có bản vá; bắt buộc cấp DB password riêng, validation pool/timeout/số lỗi phải fail fast.
- Codec chuyển sang frame có giới hạn theo message; hai đầu dùng chung quiz-common mới. Chữ ký API write/read giữ nguyên, wire format thay đổi.
- Sửa race reconnect ở send/broadcast, đóng socket không flush writer đang kẹt, từ chối token trống.
- BCrypt giới hạn 72 byte UTF-8, hash hỏng/cost ngoài 04–14 trả false; PIN 10 chữ số dùng long.
- Pool thay mới thất bại giữ pool cũ, bỏ cấu hình cache sai driver và log JDBC URL; rollback thất bại abort connection, lỗi DB trả thông báo chung và giữ cause.
- `mvnw.cmd -B -ntp clean verify`: 58 test pass (12 common + 46 server), không failure/error/skipped. Chưa triển khai E2E, service/handler, bootstrap hay UI của giai đoạn 4–6.
- Schema/seed đã rà soát tĩnh; chưa thực thi trên PostgreSQL kiểm thử vì chưa có môi trường DB cô lập và Docker daemon không hoạt động. Không chạy script lên database hiện hữu.
- TTL session, TLS/rate limit, writer queue và validation nghiệp vụ vẫn để giai đoạn tích hợp tương ứng; không thay đổi phân công của thành viên khác.

---

### 🛡️ Giai đoạn 4: `quiz-server` – Nghiệp vụ Auth, User & Dispatcher
* [ ] **Task 4.1: Domain Model & Data Access (Repository)**
  - `User.java`: Entity người dùng, validate tính hợp lệ ngay trong constructor.
  - `UserRepository.java`: Interface định nghĩa các thao tác CRUD người dùng:
    - `findByUsername(String username)`
    - `findById(long id)`
    - `existsByUsername(String username)`
    - `existsByEmail(String email)`
    - `save(User user)`
    - `findAll()`
    - `updateActiveStatus(long id, boolean active)`
  - `JdbcUserRepository.java`: Hiện thực JDBC bằng `PreparedStatement`, tuyệt đối không nối chuỗi SQL.
* [ ] **Task 4.2: Application Services**
  - `AuthService.java`:
    - `login(String username, String password)`: Kiểm tra tài khoản tồn tại, kiểm tra `active`, so khớp BCrypt hash, sinh token và lưu `SessionRegistry`.
    - `logout(String token)`: Xóa token khỏi `SessionRegistry`.
  - `AccountService.java`:
    - `createAccount(CreateAccountDto dto, long adminId)`: Kiểm tra trùng username/email, băm mật khẩu, lưu vào CSDL.
    - `listAccounts()`: Trả về danh sách tài khoản cho Admin.
    - `setAccountActive(long userId, boolean active)`: Bật/tắt trạng thái hoạt động tài khoản.
* [ ] **Task 4.3: Request Dispatcher & Command Handlers**
  - `CommandHandler.java`: Interface cho các handler mạng:
    - `Set<MessageType> supportedTypes()`
    - `Set<Role> allowedRoles(MessageType type)`
    - `Response handle(Request request, UserSession session)`
  - `AuthGuard.java`: Kiểm tra token hợp lệ và quyền hạn role trước khi chuyển tới handler.
  - `RequestDispatcher.java`: Định tuyến `MessageType` tới `CommandHandler`, bao bọc try-catch toàn diện chuyển đổi Exception thành Response chuẩn.
  - `LoginHandler.java`: Tiếp nhận và xử lý lệnh `LOGIN`, `LOGOUT`.
  - `AccountHandler.java`: Tiếp nhận và xử lý lệnh `CREATE_ACCOUNT`, `LIST_ACCOUNTS`, `SET_ACCOUNT_ACTIVE`.
* [ ] **Task 4.4: Vòng lặp Server Socket & Khởi động (Bootstrap)**
  - `ClientHandler.java`: Luồng đọc `Request` từ socket của từng client và đẩy vào worker pool để xử lý.
  - `QuizServer.java`: Quản lý ServerSocket, luồng accept và connection pool.
  - `ServerMain.java`: Điểm khởi chạy chính: kết nối CSDL, khởi tạo các bean/service/handler, đăng ký shutdown hook đóng tài nguyên an toàn.

---

### 💻 Giai đoạn 5: `quiz-client` – Socket Client & JavaFX UI
* [ ] **Task 5.1: Hạ tầng Mạng Client & API Bất đồng bộ**
  - `client.properties`: Cấu hình server host, port, timeout.
  - `ClientConfig.java`: Đọc file properties cấu hình.
  - `ServerConnection.java`: Quản lý kết nối socket, 1 reader thread nền đọc `Response`, ghép nối theo `requestId` qua `CompletableFuture`.
  - `QuizApi.java`: API bất đồng bộ trả về `CompletableFuture`:
    - `CompletableFuture<LoginResultDto> login(String username, String password)`
    - `CompletableFuture<Void> logout()`
    - `CompletableFuture<AccountDto> createAccount(...)`
    - `CompletableFuture<List<AccountDto>> listAccounts()`
    - `CompletableFuture<Void> setAccountActive(long userId, boolean active)`
  - `ClientSession.java`: Lưu thông tin đăng nhập hiện tại (user, token, role).
  - `FxAsync.java`: Tiện ích cập nhật giao diện thông qua `Platform.runLater()`.
* [ ] **Task 5.2: Điều hướng Màn hình (`SceneNavigator`)**
  - `SceneNavigator.java`: Quản lý Stage chính, chuyển màn hình theo Role sau khi đăng nhập:
    - `ADMIN` ➔ Màn hình Quản lý tài khoản (`admin/account`)
    - `TEACHER` ➔ Màn hình Dashboard Giáo viên (`teacher/dashboard`)
    - `STUDENT` ➔ Màn hình Dashboard Học viên (`student/dashboard`)
* [ ] **Task 5.3: Giao diện Đăng nhập (`ui/login`)**
  - `login.fxml`: Giao diện FXML hiện đại (trường tài khoản, mật khẩu, nút đăng nhập, nhãn thông báo lỗi, trạng thái loading).
  - `LoginController.java`: Bắt sự kiện, validate form, gọi `QuizApi.login()`, chuyển màn hình qua `SceneNavigator`.
* [ ] **Task 5.4: Giao diện Quản lý Tài khoản Admin (`ui/admin/account`)**
  - `account.fxml`: Bảng danh sách tài khoản (TableView), form thêm tài khoản mới, nút khóa/mở khóa tài khoản.
  - `AccountController.java`: Tải danh sách tài khoản, tạo tài khoản mới cho Teacher/Student, cập nhật trạng thái.

---

### 🧪 Giai đoạn 6: Kiểm thử & Bàn giao Hệ thống
* [ ] **Task 6.1: Unit Test Server**
  - `PasswordHasherTest`: Test mã hóa và kiểm tra khớp hash BCrypt.
  - `AuthServiceTest`: Test đăng nhập thành công, sai mật khẩu, tài khoản bị khóa.
  - `AuthGuardTest`: Test chặn token giả, chặn sai quyền hạn vai trò.
* [ ] **Task 6.2: Integration Test (End-to-End Socket)**
  - `LoginIntegrationTest`: Mở server socket thật, client gửi request `LOGIN` thật qua socket ➔ nhận Response thành công kèm token.
* [ ] **Task 6.3: Bàn giao nền tảng & Push Git**
  - Kiểm tra toàn bộ mã nguồn tuân thủ coding conventions (`mvn clean verify`).
  - Push nhánh `feature/auth-user-db` lên GitHub để các thành viên Võ, Bắc, Đôn, Nam pull về phát triển tiếp.

---

## 📌 Quy Ước Code & Git
* **Quy ước đặt tên branch:** `<type>/<feature-name>` (ví dụ: `feature/auth-user-db`, `feature/question-bank`, `feature/student-exam`).
* **Quy ước commit message:** `<type>: <mô tả ngắn>`
  - `feat`: Thêm tính năng mới
  - `fix`: Sửa lỗi
  - `refactor`: Tái cấu trúc mã nguồn
  - `test`: Thêm hoặc sửa test case
  - `docs`: Cập nhật tài liệu
