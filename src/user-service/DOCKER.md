# User + Payment trong Docker

Bật Docker Desktop với Linux Engine. Từ thư mục gốc, khởi động hệ thống dùng chung:

```powershell
./scripts/ds03-demo.ps1 -Stage Up
```

Script dùng project `ds03-demo` và tạo `.env` từ mẫu nếu cần. User và Payment
dùng chung khóa JWT và issuer từ cấu hình Compose. Giữ `.env` trên máy.

User: http://localhost:8081/actuator/health; Payment: http://localhost:8085/actuator/health.
Nếu cần đổi cổng User hoặc Payment, truyền `-UserPort` hoặc `-PaymentPort` khi chạy
`ds03-demo.ps1`.

Image User được build bằng Java 21, runtime chỉ có JRE, chạy bằng userapp UID
10001 và có HTTP healthcheck. Flyway tự tạo bảng users trên database mới và kiểm
soát phiên bản schema.

```powershell
docker compose -p ds03-demo ps
docker compose -p ds03-demo logs --tail 100 user-service
docker compose -p ds03-demo down
```

Để kiểm tra luồng User JWT và Payment bằng Postman, xem
[hướng dẫn Payment](../payment-service/docs/TESTING.md#local-postman-harness).

## Kiểm chứng 31/10/2026

- Build User bằng Java 21 thành công; cả bốn container healthy.
- Flyway V1 tạo schema user_db và health HTTP trả UP.
- Smoke test User JWT -> Payment PASS, bao gồm quyền chủ sở hữu và 401/403.
- Trip demo: b5fbaba4-6934-4a7e-aef2-2530d971de16.
- Trước khi build image: 22 test không cần DB đạt; 2 test MySQL thật đạt trên
  database riêng user_test với Java 21 và MySQL 8.4.13.

Đây là tích hợp User/Payment với MySQL/RabbitMQ thật. Trip command vẫn do script
mô phỏng; chưa kiểm chứng luồng chuyến đi/Saga với Trip/Notification.
