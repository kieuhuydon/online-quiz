# Anti-Cheat & Teacher Live Monitoring

## Kiến trúc

JavaFX → QuizApi / TCP → MonitoringHandler → MonitoringService → MonitoringDAO → PostgreSQL.
Không đặt mật khẩu DB hay JDBC trong client. `ExamSession` là snapshot bất biến
được truyền qua protocol. `session_id` chính là `attempts.id`, một phiên cho một lượt thi.

- `MonitoringDAO`: PreparedStatement, dùng cùng Connection với giao dịch xử phạt/nộp bài.
- `MonitoringService`: phân quyền, kiểm tra mức phạt, khóa dòng attempt và lưu audit.
- `StudentAntiCheatService`: đồng bộ trạng thái qua API trên executor nền, mọi callback UI qua Platform.runLater.
- `ExamAntiCheatController` + `ProcessScannerService`: phát hiện mất focus và tiến trình cấm, chống ghi trùng cùng app khi app vẫn đang chạy.
- `TeacherMonitorController`: giao diện JavaFX tạo bằng code, TableView, màu dòng, form xác nhận, lịch sử; mở từ nút **Giám sát phòng thi** ở trang giáo viên.

Chu kỳ mặc định 3 giây, cấu hình `monitoring.interval.ms=3000` trong client.properties
hoặc file cấu hình client bên ngoài. Mỗi màn hình chỉ có tối đa một yêu cầu tải đang chờ.
Đóng màn hình/dừng phiên sẽ dọn timer và executor. Đây là cập nhật định kỳ gần thời gian thực;
độ trễ phụ thuộc thêm mạng và thời gian xử lý server.

## Database và chạy ứng dụng

DB mới: dùng `database/schema.sql` như hướng dẫn README.

DB có sẵn: dừng server, sao lưu rồi chạy migration đúng một lần:

```powershell
psql -h localhost -U <db-user> -d <db-name> -v ON_ERROR_STOP=1 -f database/migrations/001_exam_monitoring.sql
```

Migration tạo `exam_sessions`, `monitoring_penalties` và chuyển thông tin các attempt/sự kiện
đang có vào phiên giám sát. Không chạy lại toàn bộ schema.sql lên database cũ.
Migration không được tự động áp dụng khi server khởi động.

```powershell
.\mvnw.cmd clean verify
.\run-server.bat
.\run-client.bat
```

Mở thêm client để đăng nhập giáo viên và sinh viên riêng. Server và mọi client cần cùng bản build vì có DTO/lệnh mới.

Bản sửa ngày 01/11/2026 không yêu cầu migration mới nếu đã chạy `001_exam_monitoring.sql`.
Không chạy lại migration hoặc schema để cập nhật bản sửa này. Cột `deducted_time_seconds` và
các audit cũ được giữ trong DB để bảo toàn dữ liệu lịch sử, nhưng không còn được đọc/ghi để
xử phạt hay truyền xuống client. `PenaltyType` chỉ còn `DEDUCT_SCORE` và `TERMINATE`.
Lịch sử hình thức đã ngừng vẫn đọc được; thời hạn đã lưu của các lượt thi cũ không được sửa hồi tố.

## Quy tắc xử lý

- IP được chọn trên luồng nền từ IPv4 của card mạng đang bật qua `NetworkInterface`, ưu tiên
  card dùng để kết nối server; loại loopback, link-local và các adapter ảo/VPN phổ biến khi có
  card vật lý phù hợp. Khi server chạy localhost, vẫn ưu tiên IPv4 LAN thật của laptop.
  Nếu không có IPv4 vật lý (offline/VM/IPv6-only), dùng địa chỉ thật của socket; localhost có
  thể hiển thị loopback hợp lệ trong trường hợp này. Không tạo IP giả khi không xác định được.
  IP gửi trong `StartAttemptRequestDto` và `ExamSessionRequestDto` mỗi chu kỳ giám sát.
  Server kiểm tra IP dạng literal, kiểm tra chủ sở hữu rồi upsert vào `exam_sessions`;
  chuỗi trống không ghi đè IP đã có. Phiên đang thi từng thiếu IP sẽ được bổ sung ở lần đồng bộ kế tiếp.
  Đây là IP client báo cáo, không dùng để xác thực hoặc kết luận gian lận.
- Mất focus/copy/paste: WARNING. Phát hiện process cấm: CHEATING.
  CHEATING là dấu hiệu để giáo viên xem bằng chứng; trạng thái này được giữ đến khi nộp bài.
  `violation_details` chứa sự kiện gần nhất; toàn bộ lịch sử nằm ở `anticheat_events`.
- LOG_ONLY vẫn ghi nhận/cập nhật giám sát; không tự nộp do đạt ngưỡng.
  WARN_THEN_AUTO_SUBMIT tiếp tục hoạt động, chấm bài và log trong cùng giao dịch.
- Trừ điểm: số dương, tối đa 2 chữ số thập phân, tổng không quá 10.
  Điểm cuối = max(0, điểm chấm − tổng phạt); xét đạt theo điểm cuối.
- Đồng hồ đếm ngược chuẩn tiếp tục đồng bộ theo `deadline_at`. Xử phạt không thay đổi thời hạn;
  bài hết giờ tự nộp theo TIMEOUT như trước.
- Kết thúc: server lưu is_force_stopped và TERMINATED, chấm các đáp án đã lưu (ANTICHEAT).
  Client khóa thao tác khi nhận trạng thái và gọi nộp bài idempotent để lấy kết quả.
  Việc kết thúc có hiệu lực ngay trên server, kể cả sinh viên offline.
- Chỉ giáo viên tạo quiz hoặc ADMIN được giám sát/xử phạt. STUDENT chỉ đọc phiên của mình.
- Chỉ phạt bài đang làm và còn giờ. Mức phạt, lý do, thời gian, giáo viên lưu trong monitoring_penalties.
  operation_id duy nhất ngăn áp dụng trùng cùng yêu cầu. Không tự thử lại lệnh phạt sau timeout;
  UI yêu cầu tải lại và kiểm tra lịch sử trước khi gửi thao tác mới.
- Khóa attempt bằng SELECT FOR UPDATE để đáp án, nộp bài và xử phạt không ghi đè nhau.
  Lỗi ghi audit/cập nhật/chấm điểm sẽ rollback toàn bộ giao dịch.

## Test một laptop

1. Chạy server + hai client, đăng nhập giáo viên và sinh viên khác nhau.
2. Tạo quiz 10 phút, 5 câu, giao đúng lớp; chọn LOG_ONLY khi thử phạt thủ công.
3. Sinh viên bắt đầu; giáo viên chọn quiz → Giám sát phòng thi.
4. Alt+Tab sang giáo viên: dòng sinh viên chuyển vàng, tăng số sự kiện.
5. Sau khi bài bắt đầu, mở một app trong blacklist: trong khoảng một chu kỳ quét cộng một
   chu kỳ tải bảng, thấy dòng đỏ và tên app. Giữ app mở không tăng đếm liên tục.
6. Kiểm tra không còn nút/ô nhập/cột phạt thời gian. Trừ 1 điểm, nhập lý do: sinh viên thấy
   thông báo điểm phạt, đồng hồ tiếp tục đếm bình thường; lịch sử có lệnh trừ điểm.
7. Làm đúng 4/5 câu rồi nộp: điểm cuối 7/10 sau khi trừ 1 điểm.
8. Dùng quiz/tài khoản khác để thử Kết thúc: sinh viên bị khóa, có kết quả và trạng thái TERMINATED.
9. Dùng bài 1 phút và đợi hết giờ: bài được nộp theo TIMEOUT.
10. Mở thêm sinh viên B: phạt A không làm thay đổi B. Đóng/mở màn hình vẫn thấy lịch sử.
11. Hover/chọn dòng vàng và đỏ, chuyển focus bằng Tab: chữ luôn tối, dòng chọn có viền rõ.
    Thử cả màn hình Giám sát và Nhật ký chống gian lận ở kết quả lớp.
12. Đối chiếu cột IP với IPv4 Wi-Fi/Ethernet trong `ipconfig`; không chọn IP vEthernet/WSL.
    Ngắt/kết nối mạng rồi tiếp tục bài: địa chỉ mới cập nhật sau một chu kỳ đồng bộ.

Mỗi sinh viên chỉ có một attempt/quiz, dùng quiz riêng cho các ca nộp/kết thúc.
Các client trên cùng laptop có thể báo cùng IP; đây là kết quả hợp lệ.
Không cần chạy app cấm thật để kiểm thử tự động: scanner tests dùng tiến trình giả lập.

## Kiểm thử tự động

`clean verify` chạy unit tests, bao gồm quyền, mức phạt, thời gian, điểm cuối và codec.
Các bài PostgreSQL cần database test riêng, không dùng DB ứng dụng.
Tạo `db-test-local.properties` (đã được gitignore):

```properties
db.url=jdbc:postgresql://localhost:5432/quizdb_test
db.user=quiz_test
db.password=<test-password>
```

Role quiz_test cần quyền tạo schema trong quizdb_test. MonitoringIT tạo schema ngẫu nhiên
riêng và chỉ xóa schema đó sau test. Kiểm thử gửi trùng đồng thời, phạt/nộp đồng thời,
rollback, kết thúc khi client offline, hết giờ tự nhiên, sửa IP thiếu/đổi IP và migration/backfill:

```powershell
.\mvnw.cmd -pl quiz-server -am -Pdb-it "-Dit.test=MonitoringIT" "-Dfailsafe.failIfNoSpecifiedTests=false" verify
```

JavaFX UI tests (cần desktop):

```powershell
.\mvnw.cmd -pl quiz-client -am -Pui-it "-Dit.test=TeacherMonitorUiIT" "-Dfailsafe.failIfNoSpecifiedTests=false" verify
```

Giới hạn: phát hiện ở cấp ứng dụng và dữ liệu do client báo cáo; client bị sửa đổi có thể
ngừng báo cáo. Không phát hiện thiết bị thứ hai hay chứng minh gian lận chỉ qua IP.

## Kết quả kiểm tra ngày 31/10/2026

Các số liệu dưới đây là của bản cũ, trước bản sửa 01/11/2026.

- `mvnw.cmd clean verify`: 490 unit tests đạt, không lỗi hoặc bỏ qua.
- `mvnw.cmd -Pui-it verify`: 490 unit tests và 53 JavaFX integration tests đạt.
  Trong đó có 2 test mới dùng socket thật: giáo viên gửi đúng lệnh phạt cho dòng đã chọn;
  sinh viên nhận trạng thái trên FX thread và bỏ qua phản hồi đến sau khi service đã đóng.
- 6 test PostgreSQL trong MonitoringIT đã được viết và biên dịch, chưa chạy vì môi trường
  chưa có db-test-local.properties hoặc biến môi trường QUIZ_TEST_DB_*.
- Chưa áp dụng migration lên database của ứng dụng. Cần chạy migration trước khi dùng tính năng.

## Nguyên nhân và phạm vi bản sửa 01/11/2026

- `QuizApi` có gửi IP nhưng `AttemptHandler` gọi overload 3 tham số, bỏ `ipAddress`;
  overload này chuyển `null` vào `AttemptService`, nên DB không nhận IP. Handler nay truyền đủ tham số.
  Cách cũ lấy IP từ hostname có thể trả về loopback/adapter không phù hợp hoặc bỏ qua lỗi thành null;
  nay dùng `StudentIpResolver`, địa chỉ socket và cập nhật lại trong heartbeat.
- Row factory cũ chỉ đặt nền màu bằng inline style. JavaFX vẫn dùng màu chữ trắng khi chọn.
  `ViolationTableStyle` + `monitoring.css` dùng pseudo-class cho dòng cảnh báo/vi phạm, đặt màu
  chữ `#111827` cho cell ở trạng thái thường, hover, selected, focused và xóa trạng thái khi tái sử dụng dòng.
- Đã xóa xử phạt thời gian trong DTO trạng thái, enum lệnh, DAO, service, giao diện giáo viên,
  đồng bộ sinh viên và thông báo. Trừ điểm/kết thúc bài vẫn có audit và phân quyền.
