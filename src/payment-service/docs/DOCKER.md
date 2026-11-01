# Payment trong Docker Compose chung

## Kết quả kiểm chứng 31/10/2026

Image đã build thành công và mysql, rabbitmq, payment-service đều healthy.
Smoke test qua broker thật và MySQL 8.4 trong Docker đã PASS với trip
`7f6ac999-e801-4813-84ee-b418a22b4cea`: payment REFUNDED 50000 VND,
PAY/REFUND mỗi operation SUCCESS với attempt_count=1; hai sự kiện
PaymentSucceeded/PaymentRefunded đều PUBLISHED với attempt_count=1.
Image chạy bằng user payment; HTTP health trả UP. Đây là kiểm chứng riêng Payment,
chưa phải kiểm thử tích hợp Trip/Notification.

## Chạy lại

Yêu cầu: Docker Desktop đang chạy Linux containers (WSL 2 trên Windows).
Từ thư mục gốc dự án:

```powershell
./scripts/start-payment-docker.ps1
./scripts/test-payment-docker.ps1
```

Lệnh đầu tự tạo `.env.docker-payment` với mật khẩu ngẫu nhiên, kiểm tra Compose,
build Payment bằng Java 21 và chờ MySQL, RabbitMQ, Payment healthy. Không cần cài
Java/Maven trên host để build image. Chỉ ba container này được khởi động, dùng
project `htpt-payment` và các volume riêng với MySQL local trên Windows.

File môi trường được Git ignore. Giữ file này cho các lần chạy sau: đổi mật khẩu
trong file không tự đổi mật khẩu của tài khoản trong volume MySQL đã khởi tạo.
Các biến trong file này được script ưu tiên trong lúc chạy rồi khôi phục terminal.

| Thành phần | Địa chỉ từ máy Windows |
|---|---|
| Payment health | http://localhost:8085/actuator/health |
| RabbitMQ Management | http://localhost:15672 |
| MySQL | Chỉ mở trong mạng Compose; Payment dùng mysql:3306 |

Đăng nhập RabbitMQ bằng RABBITMQ_USER/RABBITMQ_PASSWORD trong file môi trường
local. Không chia sẻ hoặc commit file đó. Nếu cổng đang có ứng dụng local dùng,
sửa PAYMENT_HTTP_PORT, RABBITMQ_AMQP_PORT, RABBITMQ_MANAGEMENT_PORT trong file rồi
chạy lại script; các cổng nội bộ container không thay đổi.

Bài smoke test tạo một trip UUID mới, gửi PAY qua RabbitMQ, chờ SUCCESS, gửi lại
cùng command, gửi REFUND, kiểm tra hai operation và số tiền hoàn 50000 VND. Test
cũng chờ các sự kiện PaymentSucceeded/PaymentRefunded được outbox phát qua broker.
Queue quan sát được xóa khi kết thúc; dữ liệu demo giữ trong payment_db của Docker
để có thể xem lại. Không truy cập payment_db của MySQL chạy trên Windows.

```powershell
# Trạng thái
./scripts/start-payment-docker.ps1 -Action Status

# Xem log (không in toàn bộ config đã thay biến vì có mật khẩu)
docker compose --project-name htpt-payment --env-file .env.docker-payment logs --tail 100 payment-service

# Dừng, giữ nguyên dữ liệu
./scripts/start-payment-docker.ps1 -Action Down
```

Để xem MySQL, mở terminal trong container mysql từ Docker Desktop rồi dùng:

```sh
MYSQL_PWD="$MYSQL_PAYMENT_PASSWORD" mysql -u payment_app payment_db
```

```sql
SELECT trip_id, status, amount, refunded_amount FROM payments;
SELECT operation_type, status, attempt_count FROM payment_operations;
SELECT event_type, status, attempt_count FROM outbox_events;
```

Container chạy bằng user payment (UID 10001), có HTTP healthcheck và chỉ khởi động
sau khi MySQL/RabbitMQ healthy. Flyway áp dụng migration Payment tự động. Docker
build bỏ qua test vì broker test chạy riêng; chạy Maven verify để kiểm thử source.

Khi ghép User/Trip/Notification, tiếp tục dùng cùng docker-compose.yml. Các service
trong Compose gọi payment-service:8080, rabbitmq:5672 và mysql:3306. User và Payment
phải dùng cùng JWT_SECRET/JWT_ISSUER; Trip dùng cùng PAYMENT_INTERNAL_TOKEN cho
API internal. Token của User chạy ngoài Docker với khóa khác sẽ không hợp lệ.
