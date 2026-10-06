# DS03 – Ride-Hailing Microservices

Khung dự án bài tập lớn cho 3 thành viên. Đây là skeleton chạy độc lập của 6 service; nghiệp vụ JWT, matching, OCC, Saga, idempotency và retry/DLQ chưa được triển khai.

## Phân công

| Thành viên | Thư mục sở hữu | Nhánh |
|---|---|---|
| TV1 | src/user-service | feature/user-service |
| TV1 | src/payment-service | feature/payment-service |
| TV2 | src/driver-service | feature/driver-service |
| TV2 | src/matching-service | feature/matching-service |
| TV3 | src/trip-service | feature/trip-service |
| TV3 | src/notification-service | feature/notification-service |

Mỗi service là Maven project độc lập, không phụ thuộc mã Java của service khác. Matching gọi Driver qua REST; các luồng bất đồng bộ dùng RabbitMQ. Mỗi service có MongoDB riêng; Matching chưa dùng database.

## Chạy dự án

Cần Java 21+, Maven 3.9+, Docker Desktop có Compose.

```powershell
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
Invoke-RestMethod http://localhost:8081/actuator/health
```

Lần đầu tải dependency và build có thể lâu. MongoDB không mở port ra máy host. RabbitMQ Management: http://localhost:15672, tài khoản trong `.env`. Cấu hình này chỉ dành cho môi trường bài tập trên máy cá nhân.

| Service | Port | Database | Owner |
|---|---|---|---|
| user | 8081 | user_db | TV1 |
| driver | 8082 | driver_db | TV2 |
| trip | 8083 | trip_db | TV3 |
| matching | 8084 | không có | TV2 |
| payment | 8085 | payment_db | TV1 |
| notification | 8086 | notification_db | TV3 |

Build/test toàn bộ: `mvn -B verify`. Build riêng: `mvn -B -f src/driver-service/pom.xml verify`.
Tắt: `docker compose down` (giữ dữ liệu trong volume). Xem logs: `docker compose logs -f trip-service`.

## Làm việc nhóm

Đọc [hướng dẫn Git flow](docs/GIT_WORKFLOW.md) trước khi code. Thống nhất API và payload event với nhóm trước khi nối các service.
CI kiểm tra build và khởi động các service. Khi triển khai nghiệp vụ, bổ sung test trong từng service và test tích hợp cho OCC, Saga, recovery, idempotency và DLQ.

Phiên bản nền: Java 21, Spring Boot 4.1.1. Tài liệu chính thức: https://docs.spring.io/spring-boot/ . Không tự nâng version riêng từng service.
