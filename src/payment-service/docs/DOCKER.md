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
./scripts/ds03-demo.ps1 -Stage Up
```

Lệnh này chạy toàn bộ hệ thống trong project `ds03-demo`. Script tạo `.env`
từ mẫu nếu cần. Giữ file này cho các lần chạy sau: đổi mật khẩu trong file
không tự đổi mật khẩu trong volume MySQL đã khởi tạo.


| Thành phần | Địa chỉ từ máy Windows |
|---|---|
| Payment health | http://localhost:8085/actuator/health |
| RabbitMQ Management | http://localhost:15672 |
| MySQL | Chỉ mở trong mạng Compose; Payment dùng mysql:3306 |

Đăng nhập RabbitMQ bằng RABBITMQ_USER/RABBITMQ_PASSWORD trong file môi trường
local. Không chia sẻ hoặc commit file đó. Nếu cổng đang có ứng dụng local dùng,
truyền -PaymentPort cho ds03-demo.ps1 hoặc đổi RABBITMQ_AMQP_PORT và
RABBITMQ_MANAGEMENT_PORT trong .env rồi chạy lại.

Để kiểm tra thanh toán, hoàn tiền và JWT của User bằng Postman, xem
[hướng dẫn kiểm thử](TESTING.md#local-postman-harness).

```powershell
docker compose -p ds03-demo ps
docker compose -p ds03-demo logs --tail 100 payment-service
docker compose -p ds03-demo down
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
