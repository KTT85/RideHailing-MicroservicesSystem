# RabbitMQ local cho DS03 — Windows 64-bit

Đã thiết lập **RabbitMQ 4.3.6 + Erlang/OTP 28.5 bản ZIP portable** tại `.local/`.
Không cần cài thêm bản EXE hoặc Docker để chạy cấu hình này. Đây là broker dùng
chung cho các service, không phải thư viện nằm trong Java.

## Vị trí và cấu hình

| Thành phần | Vị trí / địa chỉ |
|---|---|
| Runtime Erlang | `.local/erlang/` |
| Runtime RabbitMQ | `.local/rabbitmq/rabbitmq_server-4.3.6/` |
| Dữ liệu, log, cookie | `.local/rabbitmq-data/` |
| Config và plugin | `deploy/rabbitmq/` |
| Lệnh quản lý | `scripts/rabbitmq/` |
| AMQP cho Spring Boot | `127.0.0.1:5672` |
| Web Management / HTTP API | `http://localhost:15672` |
| Tài khoản local | `guest` / `guest` |
| Vhost ứng dụng | `/` |
| Vhost test độc lập | `payment-test` |

Broker chỉ lắng nghe loopback. Cổng 25672 phục vụ nội bộ Erlang, không dùng làm
spring.rabbitmq.port. Không mở firewall hoặc chia sẻ tài khoản guest ra Internet.
Runtime/dữ liệu được Git ignore; config và script được lưu trong source.

## Sau khi mở máy hoặc checkout mới

Mở PowerShell ở `D:\HTPT`:

```powershell
# Trên checkout mới: tải và xác minh SHA256 các gói chính thức.
.\scripts\rabbitmq\setup.ps1

# Chạy broker ở nền, bật sẵn Management và khai báo topology Payment.
.\scripts\rabbitmq\start.ps1

# Xem node, cổng, queue và số consumer.
.\scripts\rabbitmq\status.ps1

# Dừng broker khi kết thúc buổi làm việc.
.\scripts\rabbitmq\stop.ps1
```

`setup.ps1` không cần chạy lại nếu runtime đã có. Broker không phải Windows
Service nên không tự khởi động khi bật máy; dùng start.ps1 hoặc lệnh chạy Payment
bên dưới. start.ps1 kiểm tra instance đang chạy để tránh chạy trùng.

## Chạy Payment và kết nối luôn vào broker

```powershell
.\scripts\start-payment-local.ps1
```

Lệnh trên bật RabbitMQ nếu cần, đặt AMQP host/port/credentials local rồi chạy
Payment ở `http://localhost:8085`. Script hỏi mật khẩu MySQL của payment_app.
JWT được đọc chung từ `.local/jwt-secret.dpapi`, mã hóa bằng Windows DPAPI và
không commit. Chạy User bằng `./scripts/start-user-local.ps1` trong terminal khác
để dùng cùng khóa. Không cần copy/paste khóa hoặc tạo lại mỗi lần chạy. Nếu đổi
sang khóa local này, restart cả hai service và đăng nhập lại để lấy token mới.

Trong MySQL, Payment cần account riêng sở hữu payment_db, ví dụ chạy bằng admin
trong Workbench nếu chưa có (thay placeholder bằng mật khẩu tự chọn):

```sql
CREATE USER IF NOT EXISTS 'payment_app'@'localhost'
    IDENTIFIED BY '<MAT_KHAU_TU_CHON>';
GRANT ALL PRIVILEGES ON payment_db.* TO 'payment_app'@'localhost';
```

Không cần xóa hoặc tạo lại bốn bảng đã tạo. Flyway của Payment thêm cột kỹ thuật
correlation_id và bảng flyway_schema_history khi chạy lần đầu.

Payment sẽ nhận command từ `payment.commands`. Sau khi service khởi động, mục
**Queues and Streams → payment.commands → Consumers** phải có consumer. Khi
Payment dừng, Consumers = 0 là đúng; RabbitMQ vẫn giữ command trong queue để xử
lý khi Payment khởi động lại. Chỉ mở cổng broker không có nghĩa Payment đã chạy.

## Thao tác trong trình duyệt và Postman

1. Mở `http://localhost:15672`, đăng nhập guest/guest, chọn vhost `/`.
2. **Connections**: kiểm tra kết nối AMQP của Payment khi service đang chạy.
3. **Queues and Streams**: có payment.commands, payment.retry, payment.dlq.
4. **Exchanges → ride.events**: topic exchange cho command/result. Routing key
   chính là eventType; ví dụ PaymentRequested hoặc PaymentRefundRequested.
5. Import `src/payment-service/tests/Payment.local.postman_collection.json`.
6. Đặt loginIdentifier/loginPassword của Passenger trong biến local của Postman;
   rabbitUser=guest, rabbitPassword=guest. Chạy User ở cổng 8081 để login.
7. Chạy lần lượt Login → Health → Create demo result queue → Bind demo queue.
8. Chạy PAY, lặp PAY và GET payment đến SUCCESS; chạy REFUND, GET đến REFUNDED.
9. Chạy CASH completion, GET thấy PENDING; CASH confirmation, GET thấy SUCCESS.
10. Quan sát kết quả ở payment.demo.results; message lỗi ở payment.dlq.

PAY/REFUND trong collection là bộ mô phỏng Trip để test riêng Payment. Trong luồng
hoàn chỉnh, Passenger gọi API của Trip; Trip kiểm tra quyền/trạng thái rồi publish.
Không coi nút publish của Management là API thanh toán cho khách hàng.

## Kiểm thử listener với RabbitMQ thật, không cần mật khẩu MySQL

```powershell
.\scripts\rabbitmq\test-payment.ps1
```

Script tạo vhost payment-test và chạy Payment trong test context với RabbitMQ
thật, H2 thay cho MySQL. Bao phủ PAY/REFUND trùng, CASH đảo thứ tự, DLQ, listener
restart và workload 50 PAY. Test context dừng sau khi xong; không phải Payment
server chạy lâu dài ở 8085. Chạy start-payment-local.ps1 để dùng MySQL thật.

## Nguồn tải chính thức

- [RabbitMQ Windows ZIP 4.3.6](https://github.com/rabbitmq/rabbitmq-server/releases/download/v4.3.6/rabbitmq-server-windows-4.3.6.zip)
- [Erlang Windows ZIP 28.5](https://github.com/erlang/otp/releases/download/OTP-28.5/otp_win64_28.5.zip)
- [Hướng dẫn cài thủ công Windows](https://www.rabbitmq.com/docs/install-windows-manual)
- [Bảng tương thích Erlang/RabbitMQ](https://www.rabbitmq.com/docs/which-erlang)

Phiên bản đã được ghim trong setup.ps1 và kiểm tra SHA256 trước giải nén. Không
cần dùng bộ cài EXE song song với portable; hai broker không thể cùng chiếm các
cổng 5672/15672. Muốn chuyển sang Windows Service, dừng portable trước và làm theo
hướng dẫn chính thức, không xóa dữ liệu broker để giải quyết lỗi kết nối.
