# User + Payment trong Docker

Từ thư mục gốc, với Docker Desktop Linux Engine đang chạy:

```powershell
./scripts/start-user-docker.ps1
# Khi da co code Payment day du tren nhanh tich hop:
./scripts/start-payment-docker.ps1 -WithUser
./scripts/test-user-payment-docker.ps1
```

Lệnh start-user chỉ khởi động User cùng MySQL và RabbitMQ. Lệnh -WithUser chạy cả
User lẫn Payment. Cả hai dùng Compose chung, project `htpt-payment`, tái sử dụng
`.env.docker-payment` và volume hiện có, không
tạo khóa JWT khác giữa hai service. Nếu file môi trường chưa có, script tạo
mật khẩu và khóa ngẫu nhiên. File này được Git ignore; không commit hoặc chia sẻ.

User: http://localhost:8081/actuator/health; Payment: http://localhost:8085/actuator/health.
Đổi USER_HTTP_PORT/PAYMENT_HTTP_PORT trong file môi trường nếu cổng đang bận.
Container vẫn dùng cổng 8080; DNS MySQL/RabbitMQ lần lượt là mysql/rabbitmq.

Image User được build bằng Java 21, runtime chỉ có JRE, chạy bằng userapp UID
10001 và có HTTP healthcheck. Flyway tự tạo users trên database mới và kiểm soát
phiên bản schema. Không dùng Hibernate ddl-auto=update để tạo bảng.

Bài smoke test đăng ký hai Passenger và một Driver với email/phone ngẫu nhiên,
đăng nhập, gọi /users/me, kiểm tra sai mật khẩu và thiếu JWT đều trả 401. Sau đó
gửi PAY qua RabbitMQ với passengerId của tài khoản vừa tạo, dùng JWT User cấp để
đọc Payment: chủ tài khoản nhận SUCCESS, Passenger khác và Driver nhận 403.
Mật khẩu/JWT không in ra log; các tài khoản và payment demo được giữ trong MySQL
Docker để kiểm tra, không tác động MySQL chạy trên Windows.

```powershell
./scripts/start-user-docker.ps1 -Action Status
docker compose --project-name htpt-payment --env-file .env.docker-payment logs --tail 100 user-service

# Dừng cả project User/Payment, giữ volume dữ liệu.
./scripts/start-user-docker.ps1 -Action Down
```

## Kiểm chứng 31/10/2026

- Build User bằng Java 21 thành công; cả bốn container healthy.
- Flyway V1 tạo schema user_db và health HTTP trả UP.
- Smoke test User JWT -> Payment PASS, bao gồm quyền chủ sở hữu và 401/403.
- Trip demo: b5fbaba4-6934-4a7e-aef2-2530d971de16.
- Trước khi build image: 22 test không cần DB đạt; 2 test MySQL thật đạt trên
  database riêng user_test với Java 21 và MySQL 8.4.13.

Đây là tích hợp User/Payment với MySQL/RabbitMQ thật. Trip command vẫn do script
mô phỏng; chưa kiểm chứng luồng chuyến đi/Saga với Trip/Notification.

Nhánh feature/user-service chỉ chứa thay đổi User và cấu hình dùng chung.
Để chạy test-user-payment-docker.ps1 trên checkout mới, cần có cả phần Payment
đã triển khai từ feature/payment-service (sau khi tích hợp hai nhánh vào develop).
User có thể build/chạy độc lập bằng start-user-docker.ps1 trước khi ghép Payment.
