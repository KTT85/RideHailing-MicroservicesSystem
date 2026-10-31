# Payment Service — DS03

Thanh toán mô phỏng của hệ thống đặt xe. Java 21+, Spring Boot 4.1.1,
Spring Data JPA, MySQL, RabbitMQ, Spring Security JWT.

## Đã triển khai

- TRANSFER PAY trước chuyến; hoàn 100% qua REFUND; xác nhận CASH cuối chuyến.
- Một payment/trip, một operation/type; event dedup + idempotency key + khóa dòng.
- Outbox cùng transaction với kết quả tiền; publisher confirm và mandatory return.
- Retry hữu hạn, backoff, queue retry/DLQ; worker đọc tác vụ đã lưu sau restart.
- API đọc có JWT/quyền sở hữu; API đối soát cho Trip bằng service token.
- API ADMIN tiếp tục tác vụ/outbox MANUAL_REVIEW, không thay số tiền.
- UTC xuyên JDBC/Hibernate/API; correlation ID được lưu bền vững.

Trip kiểm tra quyền và trạng thái chuyến trước khi phát lệnh. Payment không trực
tiếp truy cập DB Trip/User/Driver. Không kết nối ngân hàng hoặc cổng thanh toán thật.

## Chạy local như User service, chưa cần Docker

RabbitMQ đã có bộ chạy portable riêng tại root. Xem
[hướng dẫn RabbitMQ](../../deploy/rabbitmq/README.md). Lệnh gom khởi động broker và
Payment: `./scripts/start-payment-local.ps1` từ root dự án.

Lệnh gom tự nạp khóa JWT local được mã hóa; chạy User trong terminal khác bằng
`./scripts/start-user-local.ps1` để dùng chung khóa. Không cần truyền khóa qua
clipboard. Các lệnh trực tiếp trong thư mục service bên dưới vẫn yêu cầu đặt
JWT_SECRET nếu chưa nạp khóa.

1. MySQL local đang chạy, database `payment_db` đã tạo. Trong Workbench dùng tài
   khoản quản trị tạo account riêng nếu chưa có (thay placeholder trước khi chạy):

```sql
CREATE USER IF NOT EXISTS 'payment_app'@'localhost'
    IDENTIFIED BY '<MAT_KHAU_TU_CHON>';
GRANT ALL PRIVILEGES ON payment_db.* TO 'payment_app'@'localhost';
```

`CREATE USER IF NOT EXISTS` không đổi mật khẩu account đã tồn tại. Không dùng
account User service hoặc root để chạy Payment.

2. Chạy `./scripts/rabbitmq/start.ps1` từ root: broker portable ở
   `127.0.0.1:5672`, Management `http://localhost:15672`, login local guest/guest.
   Không cần tải thêm EXE hoặc Docker. Trên checkout mới chạy setup.ps1 trước.

3. Trong PowerShell tại root dự án, chạy:

```powershell
./src/payment-service/scripts/start-local.ps1
```

Script hỏi mật khẩu MySQL và JWT_SECRET bằng nhập ẩn. JWT_SECRET là chuỗi Base64
đang dùng ở User service, không tạo khóa khác nếu muốn dùng token User đã cấp.
Các bí mật chỉ giữ trong environment của terminal, không ghi vào repo. Script tạo
service token ngẫu nhiên nếu chưa có; giữ cùng giá trị cho Trip và giữa các lần
restart. Nếu tự chạy Maven, cần đặt MYSQL_PASSWORD, JWT_SECRET,
PAYMENT_INTERNAL_TOKEN và SPRING_PROFILES_ACTIVE=local. Mặc định local RabbitMQ
dùng guest/guest trên loopback; có thể đặt RABBITMQ_USER/RABBITMQ_PASSWORD riêng.

4. Postman gọi `GET http://localhost:8085/actuator/health`.
5. Import `tests/Payment.local.postman_collection.json`, làm theo
   [hướng dẫn test](docs/TESTING.md). Collection login qua User ở cổng 8081,
   dùng RabbitMQ Management mô phỏng lệnh của Trip và đọc kết quả Payment.

## Database đã tạo bằng tay

Flyway tự chạy khi Payment khởi động, dùng account payment_app:

- V1 tạo 4 bảng nếu chưa có, giữ dữ liệu trong các bảng đã tồn tại.
- V2 thêm `payment_operations.correlation_id` để khôi phục đúng correlation.
- `flyway_schema_history` là bảng kỹ thuật bổ sung, không phải bảng nghiệp vụ.

Database đang có bốn bảng từ SQL đã gửi được baseline ở version 0 rồi áp dụng
V1/V2. Không xóa database/volume và không chạy ALTER V2 thủ công trước Flyway.
Không sửa migration đã áp dụng; thêm migration version mới cho thay đổi sau này.

| Bảng nghiệp vụ | Vai trò |
|---|---|
| payments | Kết quả tiền của từng chuyến |
| payment_operations | PAY/REFUND/CONFIRM_CASH và tiến độ retry |
| processed_events | Chống xử lý lại event |
| outbox_events | Event chờ gửi bền vững |

## API và cấu hình

Code được chia package: config, controller, dto, exception, model, repository,
service, messaging và worker. Test nằm trong integration và messaging; main class
duy nhất nằm ở package gốc để Spring quét toàn bộ.

Chi tiết payload, routing, state machine, quyền và kết nối Trip/Notification ở
[CONTRACT.md](docs/CONTRACT.md). API chính:

```http
GET /payments/by-trip/{tripId}
Authorization: Bearer <JWT_CUA_PASSENGER_CHU_CHUYEN>
```

API trả payment status và danh sách operation/attempt/nextRetryAt. Khi chưa nhận
lệnh, 404 không đồng nghĩa đã thanh toán thất bại. Không có API client tự thu
hoặc hoàn tiền; Trip phát command sau khi kiểm tra quyền.

| Biến | Mặc định / mục đích |
|---|---|
| MYSQL_HOST / MYSQL_PORT | localhost / 3306 |
| MYSQL_USERNAME / MYSQL_PASSWORD | payment_app / bắt buộc |
| JWT_SECRET | Bắt buộc, cùng User; Base64 ít nhất 32 byte |
| JWT_ISSUER | ds03-user-service |
| PAYMENT_INTERNAL_TOKEN | Bắt buộc, ít nhất 32 ký tự |
| PAYMENT_SIMULATOR_PAY / PAYMENT_SIMULATOR_REFUND | SUCCESS; hỗ trợ FAILED hoặc TRANSIENT |
| PAYMENT_SIMULATOR_TRANSIENT_ATTEMPTS | 0; số lần đầu giả lập lỗi tạm thời mỗi PAY/REFUND |
| PAYMENT_MAX_ATTEMPTS | 5 |
| PAYMENT_RETRY_SECONDS | 5; tăng theo backoff, tối đa 3600 giây |

Sau thay đổi cấu hình simulator, restart Payment. CONFIRM_CASH chỉ ghi nhận xác
nhận nhận tiền, không đi qua giả lập ngân hàng. PAY lỗi tạm thời hết retry giữ
PENDING + MANUAL_REVIEW, phát PaymentReviewRequired; không báo thất bại giả.

## Build và giới hạn kiểm chứng

```powershell
mvn -B -ntp -f src/payment-service/pom.xml verify
```

Test mặc định dùng H2 và mô phỏng broker. [TESTING.md](docs/TESTING.md) có cách chạy
lại trên MySQL riêng, demo dừng/restart, truy vấn kiểm tra và workload performance.
Chưa thể kết luận toàn hệ thống đạt DS03 trước khi ghép Trip/Driver/Notification,
chạy live failure/Saga và lưu bằng chứng theo đề.

Docker vẫn được hỗ trợ: đặt JWT_SECRET và PAYMENT_INTERNAL_TOKEN trong `.env`
(không commit), rồi `docker compose up -d --build payment-service`. CI tạo khóa
tạm riêng; các bảng được Flyway tạo trên database của service.
