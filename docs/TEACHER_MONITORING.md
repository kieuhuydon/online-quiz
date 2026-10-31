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

## Quy tắc xử lý

- IP lấy qua `InetAddress.getLocalHost().getHostAddress()` trên luồng nền khi bắt đầu/tiếp tục bài.
  Nếu không xác định được, lưu NULL và hiển thị “Chưa xác định”. Đây là IP client báo cáo,
  có thể là IP LAN/loopback; không dùng để xác thực hoặc kết luận gian lận.
- Mất focus/copy/paste: WARNING. Phát hiện process cấm: CHEATING.
  CHEATING là dấu hiệu để giáo viên xem bằng chứng; trạng thái này được giữ đến khi nộp bài.
  `violation_details` chứa sự kiện gần nhất; toàn bộ lịch sử nằm ở `anticheat_events`.
- LOG_ONLY vẫn ghi nhận/cập nhật giám sát; không tự nộp do đạt ngưỡng.
  WARN_THEN_AUTO_SUBMIT tiếp tục hoạt động, chấm bài và log trong cùng giao dịch.
- Trừ điểm: số dương, tối đa 2 chữ số thập phân, tổng không quá 10.
  Điểm cuối = max(0, điểm chấm − tổng phạt); xét đạt theo điểm cuối.
- Trừ thời gian: giây nguyên dương. Server giảm `deadline_at`, client đồng bộ thời gian còn lại
  từ server, tránh trừ lặp khi tải lại/reconnect. Nếu hết thời gian thì server chấm ngay (TIMEOUT).
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
6. Trừ 1 điểm và 300 giây, nhập lý do: sinh viên thấy thông báo, đồng hồ thay đổi; lịch sử có cả hai lệnh.
7. Làm đúng 4/5 câu rồi nộp: điểm cuối 7/10 sau khi trừ 1 điểm.
8. Dùng quiz/tài khoản khác để thử Kết thúc: sinh viên bị khóa, có kết quả và trạng thái TERMINATED.
9. Thử trừ quá thời gian còn lại: bài được nộp theo TIMEOUT.
10. Mở thêm sinh viên B: phạt A không làm thay đổi B. Đóng/mở màn hình vẫn thấy lịch sử.

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
rollback, kết thúc khi client offline, trừ hết giờ và migration/backfill:

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

- `mvnw.cmd clean verify`: 490 unit tests đạt, không lỗi hoặc bỏ qua.
- `mvnw.cmd -Pui-it verify`: 490 unit tests và 53 JavaFX integration tests đạt.
  Trong đó có 2 test mới dùng socket thật: giáo viên gửi đúng lệnh phạt cho dòng đã chọn;
  sinh viên nhận trạng thái trên FX thread và bỏ qua phản hồi đến sau khi service đã đóng.
- 6 test PostgreSQL trong MonitoringIT đã được viết và biên dịch, chưa chạy vì môi trường
  chưa có db-test-local.properties hoặc biến môi trường QUIZ_TEST_DB_*.
- Chưa áp dụng migration lên database của ứng dụng. Cần chạy migration trước khi dùng tính năng.
