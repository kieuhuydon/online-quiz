# Rà soát bảo mật và độ ổn định — 09/10/2026

Project: Online Quiz, Java 21 / Maven / PostgreSQL.

> **Cập nhật sau review (09/10/2026):** đã sửa trong phạm vi giai đoạn 1–3: A1–A8, cấu hình DB password, lỗi pool/transaction, đóng socket, token trống và tracking WORK_PLAN. Kiểm chứng Java: 58 test pass; schema/seed mới được rà soát tĩnh, chưa chạy trên PostgreSQL cô lập. Nội dung bên dưới giữ làm bằng chứng **trước khi sửa**; các mục nghiệp vụ/giao diện/E2E và tích hợp TTL/TLS/rate limit vẫn thuộc giai đoạn sau. Xem `docs/WORK_PLAN.md` và README để dùng codec/config/schema mới. Database hiện hữu chưa được thay đổi.

**Kết luận:** phần hạ tầng hiện build được, nhưng chưa có ứng dụng chạy hoàn chỉnh. Có lỗi đã tái hiện trong code hiện hữu, cùng một số yêu cầu bảo mật cần hoàn thiện trước khi mở kết nối cho người dùng. Đây là review, chưa sửa mã nguồn ứng dụng.

## Phạm vi và kết quả kiểm tra

- Đọc mã nguồn hiện có của 4 module, các test, POM, cấu hình, Maven Wrapper, schema/seed, README và tiến độ trong `docs/WORK_PLAN.md`.
- Chạy `.\mvnw.cmd -B -ntp clean verify`: **BUILD SUCCESS**, **40 test, 0 failures, 0 errors, 0 skipped**; common 5, server 35. Client và benchmark chưa có test.
- Chạy probe Java riêng trên classpath vừa build để kiểm tra các tình huống biên; kết quả được chép bên dưới. Probe chỉ chạy cục bộ, không kết nối database, không gây hết bộ nhớ và không tấn công dịch vụ đang chạy.
- Đối chiếu phiên bản pgJDBC và Logback với thông báo bảo mật chính thức. Đây không phải một lần quét CVE đầy đủ cho mọi dependency/transitive dependency.
- Chưa chạy kiểm thử PostgreSQL thật, E2E socket hay benchmark: lớp khởi động, server accept loop, repository/service và client thực tế chưa được triển khai. Test DataSource hiện đặt `initializationFailTimeout=-1`, nên pool khởi tạo thành công không chứng minh kết nối PostgreSQL hoạt động.
- Không thực thi `schema.sql`/`seed.sql`, không thay đổi database hoặc thông tin đăng nhập.

P1 = cần ưu tiên trước khi đưa hệ thống vào sử dụng; P2 = sửa trong giai đoạn hoàn thiện hạ tầng; P3 = cải thiện nhỏ. Mức ưu tiên không đồng nghĩa đã tồn tại đường khai thác từ xa trong ứng dụng hiện tại.

## A. Lỗi và rủi ro trong phần đã có

### A1 — P1: Bộ giải mã chưa giới hạn tài nguyên

Vị trí: `quiz-common/src/main/java/quiz/common/protocol/MessageCodec.java:27`, `:56`, `:66`.

Whitelist cho phép cả package `java.lang.*`, `java.util.*`; không có `maxdepth`, `maxrefs`, `maxarray`, `maxbytes` hoặc giới hạn frame. Kiểm tra thực tế cho thấy Request chứa password dài **1.048.576 ký tự** vẫn deserialize thành công. Probe metadata mô phỏng mảng 1 tỷ phần tử trả `UNDECIDED`, không bị từ chối bởi giới hạn tài nguyên; probe không cấp phát mảng này.

Khi nối codec vào luồng mạng, dữ liệu quá lớn/sâu có thể gây cạn RAM, CPU hoặc stack trước bước xác thực. Whitelist chặn được `java.io.File` trong probe, nhưng không đủ để bảo đảm an toàn hoàn toàn như comment hiện tại khẳng định. Chưa chứng minh được RCE và không kết luận có RCE.

**Đề xuất:** giới hạn kích thước frame trước deserialize; allowlist theo các lớp thực sự cần; giới hạn array/depth/references; validate envelope/type/payload và độ dài từng trường. Không chỉ dựa vào filter để giới hạn String. Với stream sống lâu, cần thiết kế rõ giới hạn theo message và theo connection để không vô tình ngắt phiên hợp lệ do bộ đếm tích lũy. Thêm test dữ liệu sai lớp, dữ liệu lớn/sâu và nhiều message liên tiếp.

### A2 — P1 về vận hành: Script schema xóa toàn bộ dữ liệu khi chạy lại

Vị trí: `database/schema.sql:5`–`:15`; hướng dẫn setup trong `README.md:612`.

Script mở đầu bằng 11 lệnh `DROP TABLE ... CASCADE`. Chạy trên database đã có dữ liệu sẽ xóa tài khoản, đề thi, bài làm và kết quả. Script không được bao trong một transaction; một lỗi giữa chừng cũng có thể để database ở trạng thái dở dang. Phù hợp để reset database demo, không phù hợp làm migration hoặc cập nhật môi trường đang sử dụng.

**Đề xuất:** tách reset demo thành script có tên rõ ràng; dùng migration có phiên bản cho schema thật; thêm hướng dẫn backup/restore và tách database dev/test/prod. Không chạy script reset ở startup.

### A3 — P2: Lỗi gửi trên kết nối cũ xóa nhầm kết nối mới

Vị trí: `quiz-server/src/main/java/quiz/server/network/ConnectionRegistry.java:75`–`:84`, `:92`–`:101`.

`sendToUser()` lấy connection A; trong lúc gửi, user reconnect và registry chuyển sang B. Nếu A ném `IOException`, `remove(userId)` xóa B. Cùng mẫu lỗi xuất hiện trong `broadcast()`.

**Đã tái hiện bằng interleaving xác định:** thay mapping bằng connection mới ngay trong lần gửi cũ, rồi ném IOException; mapping mới bị mất. Sau đó PUSH cảnh báo/hết giờ sẽ không tìm được kết nối mới dù nó vẫn tồn tại.

**Đề xuất:** xóa có điều kiện `userConnections.remove(userId, conn)`, chỉ cleanup connection bị lỗi; thêm regression test cho reconnect trong lúc send/broadcast thất bại. Xác định riêng chính sách xử lý connection bị thay thế khi đăng nhập nhiều nơi.

### A4 — P2: Mật khẩu dài bị BCrypt cắt ngầm

Vị trí: `quiz-server/src/main/java/quiz/server/security/PasswordHasher.java:23`–`:27`.

Không giới hạn độ dài UTF-8 trước BCrypt. Đã tái hiện: hash của `"x".repeat(72) + "A"` chấp nhận `"x".repeat(72) + "B"`. Người dùng có thể tưởng phần đuôi giúp tăng bảo mật nhưng thực tế không được kiểm tra. Đây không phải khả năng đăng nhập bằng mật khẩu bất kỳ: vẫn cần khớp 72 byte đầu.

**Đề xuất:** nếu tiếp tục dùng BCrypt, giới hạn tối đa 72 **byte UTF-8**, kiểm tra đồng nhất ở tạo/đổi mật khẩu và đăng nhập; báo validation rõ ràng, không tự truncate. Test thêm ký tự tiếng Việt/emoji. Nếu cần hỗ trợ mật khẩu dài hơn, chọn cơ chế hash phù hợp và có kế hoạch tương thích dữ liệu cũ.

### A5 — P2: Hash hỏng có thể làm kiểm tra đăng nhập ném lỗi

Vị trí: `quiz-server/src/main/java/quiz/server/security/PasswordHasher.java:37`–`:46`.

Code chỉ bắt `IllegalArgumentException`, trong khi jBCrypt ném `StringIndexOutOfBoundsException` cho hash `""`, `"$"`, `"$2a$"`. Đã tái hiện cả ba. Giá trị DB bị hỏng hoặc import không đúng có thể biến một lần xác thực thành lỗi hệ thống, trái với mục đích trả `false` cho hash không hợp lệ.

**Đề xuất:** validate định dạng/độ dài/cost được hỗ trợ trước khi gọi thư viện; xử lý các lỗi parse đã biết thành thất bại xác thực và log chẩn đoán không chứa password/hash. Thêm test hash bị cắt ngắn.

### A6 — P2: Dependency có bản vá bảo mật chưa được áp dụng

Vị trí: `pom.xml:28`, `:32`; xác nhận classpath test thực sự dùng pgJDBC **42.7.4**, logback-core/classic **1.5.12**.

- pgJDBC 42.7.4 nằm trong phạm vi ảnh hưởng CVE-2025-49146 và CVE-2026-54291 liên quan `channelBinding=require`. URL mặc định hiện không bật tùy chọn này, nên không kết luận cấu hình mặc định đang bị khai thác. Phiên bản này cũng nằm trong phạm vi CVE-2026-107315, chỉ phát sinh với các API truyền dữ liệu có độ dài khai báo sai; chưa có repository để xác nhận đường gọi. [Thông báo chính thức pgJDBC](https://jdbc.postgresql.org/security/).
- Logback 1.5.12 có trước bản vá CVE-2024-12798/CVE-2024-12801. Các vấn đề này liên quan cấu hình độc hại; chưa có bằng chứng một client mạng có thể sửa cấu hình và classpath đã kiểm tra không có Janino. [Release notes của Logback](https://logback.qos.ch/news-archive.html).

**Đề xuất:** nâng pgJDBC và Logback lên bản ổn định đã vá, kiểm tra tương thích rồi chạy lại test. Tại ngày rà soát, trang chính thức công bố [pgJDBC 42.7.14](https://jdbc.postgresql.org/) và [Logback 1.6.5](https://logback.qos.ch/news.html). Thêm kiểm tra dependency định kỳ trong CI; không chỉ cập nhật đến bản vá CVE cũ đầu tiên.

### A7 — P2: Cấu hình pool không hợp lệ lọt qua validation

Vị trí: `quiz-server/src/main/java/quiz/server/config/ServerConfig.java:129`–`:165`.

Đã tạo thành công config có `minIdle=10`, `maxPoolSize=2`, idle timeout và connection timeout đều bằng `-1`. Các timeout không được validate; minIdle không được so với maxPoolSize. Hikari sẽ từ chối hoặc điều chỉnh một số giá trị ở bước sau. Chuỗi số sai còn được thay bằng default, dễ che lỗi triển khai.

**Đề xuất:** validate quan hệ min/max và miền giá trị timeout theo Hikari; báo lỗi cấu hình rõ ràng ngay lúc nạp. Với cấu hình bắt buộc, fail fast thay vì âm thầm dùng default.

### A8 — P3: PIN 10 chữ số không phủ đủ miền giá trị

Vị trí: `quiz-server/src/main/java/quiz/server/security/TokenGenerator.java:55`–`:62`.

API cho phép 10 chữ số nhưng ép `Math.pow(10, 10)` sang `int`. Biên trên hiệu dụng thành **2.147.483.646**, thay vì 9.999.999.999. PIN vẫn có 10 chữ số, nhưng miền ngẫu nhiên bị thu hẹp. PIN mặc định 6 chữ số không bị lỗi này.

**Đề xuất:** dùng `long` và giới hạn ngẫu nhiên tương ứng, hoặc sinh từng chữ số bằng SecureRandom; test biên 4/9/10 chữ số.

## B. Phần chưa hoàn thiện và yêu cầu trước triển khai

### B1 — Chưa có ứng dụng chạy end-to-end

Có **13 file Java 0 byte**: `ServerMain`, `ClientMain`, `LoadTestRunner`, `AuthGuard`, `RequestDispatcher`, `CommandHandler` và 7 handler. Probe không tìm thấy cả ba entrypoint trong output vừa build. Các tầng service/repository, accept loop, scheduler và giao diện chưa có implementation.

Điều này phù hợp với `docs/WORK_PLAN.md` ghi giai đoạn 4–6 chưa thực hiện. Không gọi đây là lỗ hổng bypass phân quyền đang hoạt động: hiện chưa có endpoint để kiểm thử bypass. Tuy nhiên, không thể dùng kết quả build xanh để xác nhận hệ thống đã chạy hoặc an toàn.

**Ưu tiên:** hoàn thiện một luồng thật login → kiểm tra quyền → thao tác DB → logout; thêm smoke test khởi động server/client trước khi mở rộng nghiệp vụ.

### B2 — Tài khoản demo và secret mặc định

`database/seed.sql:3`–`:10` tạo admin đang active với mật khẩu demo công khai; test hiện xác nhận hash khớp mật khẩu đó. `ServerConfig.java:105` và `server.properties:15` có fallback DB password `change-me`.

Đây là dữ liệu demo, không phải bằng chứng secret thật đã bị lộ. Nhưng dùng nguyên seed/config trên môi trường thật sẽ để lại tài khoản dễ đoán. Tách seed dev/test; bootstrap admin bằng secret riêng và đổi mật khẩu lần đầu; từ chối placeholder trong profile triển khai. Không ghi credentials vào JDBC URL vì `DataSourceProvider.java:48` đang log nguyên URL.

### B3 — Transport, session, chống flood và PUSH

- Chưa có wiring TLS; `ClientConnection` nhận Socket/stream từ ngoài nên bản thân lớp này không chứng minh kết nối plaintext hay TLS. Trước khi dùng qua mạng không tin cậy, cần TLS có xác thực server; Java serialization không mã hóa password/token. Với DB từ xa, cấu hình TLS kiểm tra chứng chỉ/hostname.
- `SessionRegistry` chỉ tra Map; chưa có TTL, idle timeout hoặc cleanup tự động. Bổ sung expiry, giới hạn phiên, hủy phiên khi khóa tài khoản/đổi quyền; gắn cleanup vào vòng đời connection và kiểm thử race với reconnect.
- Thêm rate limit login/PIN, giới hạn kết nối chưa xác thực và request mỗi connection; timeout handshake/read; hàng đợi worker có giới hạn và phản hồi SERVER_BUSY.
- `ClientConnection.send()` ghi đồng bộ; `ConnectionRegistry.broadcast()` gửi tuần tự. Khi client ngừng đọc, một lần ghi có thể chặn thread gửi. Đưa PUSH qua hàng đợi có giới hạn và writer riêng, đóng connection chậm theo chính sách. `Socket.setSoTimeout` chỉ xử lý read, không phải write timeout.
- `ClientConnection.close():60` flush trước khi đóng socket, không đồng bộ với `send()`. Cần thiết kế đóng socket để có thể ngắt lần write đang kẹt; tránh flush cạnh tranh trên cùng ObjectOutputStream. Chưa chạy thử slow-reader qua socket thật.

### B4 — Phân quyền dữ liệu và tính đúng đắn bài thi

- Mỗi request phải kiểm tra role và quyền sở hữu tài nguyên tại service, không tin `userId`, `attemptId`, `quizId` hoặc role từ payload.
- `QuizDto` chứa PIN; tạo DTO riêng cho học viên trước khi triển khai LIST_ASSIGNED_QUIZZES/GET_QUIZ_INFO. Chưa có handler nên chưa chứng minh PIN đang bị lộ.
- `StudentQuestionDto` đã tách đáp án đúng, đây là lựa chọn tốt; giữ nguyên nguyên tắc này trong mapper/handler.
- `attempt_answers` chỉ FK đến `questions`, chưa bảo đảm question thuộc quiz của attempt. Bắt buộc kiểm tra membership trong service; cân nhắc ràng buộc DB phù hợp.
- Bổ sung CHECK cho `pass_score` 0–100, `score` 0–10, `0 <= correct_count <= total_questions`, tổng câu > 0, deadline sau started_at; ràng buộc position dương/không trùng trong quiz; enum policy/event/action.
- Câu hỏi chưa có version/snapshot. Cần cấm sửa nội dung/đáp án khi đã dùng trong bài thi hoặc lưu snapshot để việc sửa ngân hàng không thay đổi bài đang làm/lịch sử.
- SAVE_ANSWER và SUBMIT/TIMEOUT cần cùng cơ chế khóa/transaction để không ghi đáp án sau khi chấm; duplicate submit phải idempotent. Khôi phục lịch timeout từ DB sau restart, dùng Clock inject được để test.
- Làm rõ contract SUBMIT: README mô tả `finalAnswers`, DTO hiện chỉ có `attemptId`. Nếu giữ mô hình autosave, client phải biết lần lưu cuối đã được server xác nhận trước khi nộp.
- Anti-cheat client chỉ là tín hiệu hỗ trợ, có thể bị client tùy biến bỏ qua; không dùng thay thế kiểm tra quyền/deadline/chấm điểm ở server.

### B5 — Database, transaction, tài liệu và kiểm thử

- `DataSourceProvider.java:44`–`:46` dùng `cachePrepStmts`, `prepStmtCacheSize`, `prepStmtCacheSqlLimit`, không phải các tùy chọn cache của pgJDBC. Bỏ các cấu hình gây hiểu nhầm hoặc dùng `preparedStatementCacheQueries`, `preparedStatementCacheSizeMiB`, `prepareThreshold`. [Tài liệu driver](https://jdbc.postgresql.org/documentation/use/).
- `TransactionManager.java:133` đưa `t.getMessage()` vào QuizException; README định chuyển message này xuống client. Khi triển khai dispatcher, che SQL/schema/chi tiết DB và chỉ log nội bộ.
- Nếu rollback thất bại, `TransactionManager.java:137` vẫn khôi phục autoCommit. Cần có trạng thái kết thúc transaction rõ ràng, loại connection lỗi khỏi pool và test failure path; không khôi phục theo cách có thể commit transaction chưa rollback xong. Đây là rủi ro đọc từ code, chưa tái hiện trên PostgreSQL thật.
- `schema.sql`/seed chưa có test chạy trên DB sạch; seed phụ thuộc ID cố định nên không thể áp dụng an toàn lên DB có sẵn dữ liệu. Dùng lookup theo khóa tự nhiên hoặc RETURNING khi cần seed tái sử dụng.
- 40 test hiện chủ yếu là unit/happy path; codec chưa có test từ chối lớp/dữ liệu lớn, network dùng mock/byte array, chưa có transaction/authorization/concurrency test với DB thật. README nói verify chạy integration/network nhưng chưa có suite đó hay Failsafe cho các lớp đặt tên `*IT`.
- Thêm CI chạy unit + smoke + integration DB cô lập; ưu tiên test hồi quy các lỗi A1/A3/A4/A5 và test ownership/race SAVE/SUBMIT khi có service.
- `.gitignore:17` bỏ qua toàn bộ `docs/*`, nên WORK_PLAN và tài liệu thiết kế hiện không được Git theo dõi. Thu hẹp ignore nếu muốn chia sẻ tiến độ/tài liệu trong nhóm.
- Maven Wrapper tải qua HTTPS nhưng chưa ghim `distributionSha256Sum`. Có thể thêm checksum chính thức để kiểm tra tính toàn vẹn bản phân phối.

## Bằng chứng probe cục bộ

Nguồn tạm: `target/audit/AuditProbe.java`; output: `target/audit/probe-results.txt`. Hai file thuộc thư mục build, sẽ bị xóa khi clean; các kết quả quan trọng được giữ trong báo cáo này.

```text
ENTRYPOINT quiz.server.ServerMain MISSING
ENTRYPOINT quiz.client.ClientMain MISSING
ENTRYPOINT quiz.benchmark.LoadTestRunner MISSING
FILTER_SYNTHETIC_HUGE_ARRAY UNDECIDED
DECODED_PASSWORD_LENGTH 1048576
DISALLOWED_FILE_REJECTED true
COLLECTION_DTO_ROUNDTRIP true
REPLACEMENT_SURVIVES_OLD_SEND_FAILURE false
MALFORMED_HASH_LENGTH 0 THROWS StringIndexOutOfBoundsException
MALFORMED_HASH_LENGTH 1 THROWS StringIndexOutOfBoundsException
MALFORMED_HASH_LENGTH 4 THROWS StringIndexOutOfBoundsException
DIFFERENT_PASSWORD_AFTER_BYTE_72_ACCEPTED true
INVALID_POOL_CONFIG_ACCEPTED min=10 max=2 timeout=-1
PIN10_EFFECTIVE_MAX 2147483646
```

## Thứ tự xử lý đề xuất

1. Sửa giới hạn codec, race reconnect và kiểm tra mật khẩu; bổ sung regression test. Tách script reset/seed demo khỏi quy trình triển khai.
2. Nâng dependency, sửa validation cấu hình và cấu hình pool; bổ sung chính sách transport/session/timeout.
3. Hoàn thiện vertical slice auth/account có phân quyền và kiểm thử qua socket + PostgreSQL thật.
4. Triển khai nghiệp vụ thi với ownership, snapshot/immutability câu hỏi, transaction và kiểm thử đồng thời; sau đó mới benchmark.

Các điểm tốt đã có: SecureRandom sinh token mặc định 192 bit; BCrypt có salt; DTO câu hỏi học viên không chứa đáp án; send được khóa để tránh hai writer ghi message đồng thời; registry dùng ConcurrentHashMap; unique constraint một attempt/student/quiz và một result/attempt; dependency tập trung trong parent POM.
