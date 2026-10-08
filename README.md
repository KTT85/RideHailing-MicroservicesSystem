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

Mỗi service là Maven project độc lập, không phụ thuộc mã Java của service khác. Matching gọi Driver qua REST; các luồng bất đồng bộ dùng RabbitMQ. Hạ tầng dữ liệu dùng một MySQL 8.4 Server chứa năm database riêng; mỗi service có tài khoản chỉ được truy cập database mình sở hữu. ORM sử dụng Spring Data JPA. Matching không dùng database.

## Chạy dự án

Cần Java 21+, Maven 3.9+, Docker Desktop có Compose.

```powershell
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
Invoke-RestMethod http://localhost:8081/actuator/health
```

Lần đầu tải dependency và build có thể lâu. MySQL không mở port ra máy host. RabbitMQ Management: http://localhost:15672, tài khoản trong `.env`. Mật khẩu mẫu chỉ dành cho môi trường bài tập trên máy cá nhân.

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

## Cấu hình MySQL

Docker Compose tự khởi tạo `user_db`, `driver_db`, `trip_db`, `payment_db`, `notification_db` và năm tài khoản tương ứng qua [script khởi tạo](docker/mysql/init/01-create-databases.sh). Root chỉ dùng cho quản trị/khởi tạo; các service dùng `user_app`, `driver_app`, `trip_app`, `payment_app`, `notification_app`.

Mỗi service cấu hình `spring.datasource` và `spring.jpa`. Hibernate đặt `ddl-auto: none`, chưa tạo bảng nghiệp vụ trong skeleton; schema sẽ được bổ sung theo thiết kế đã thống nhất. Không JOIN hoặc tạo foreign key xuyên database.

Khi chạy Java ngoài Docker, cung cấp `MYSQL_HOST`, `MYSQL_PORT` (mặc định `3306`), `MYSQL_USERNAME`, `MYSQL_PASSWORD` cùng biến RabbitMQ. MySQL phải truy cập được từ máy chạy Java; Compose mặc định chỉ cho các container kết nối MySQL qua mạng nội bộ. JDBC trong cấu hình local tắt TLS và cho phép lấy public key; môi trường triển khai thật cần cấu hình TLS phù hợp.

Script khởi tạo chỉ chạy khi volume MySQL còn mới. Thay mật khẩu trong `.env` sau lần chạy đầu không tự cập nhật tài khoản đã tồn tại; cần dùng `ALTER USER` bằng tài khoản quản trị. `docker compose down` không xóa dữ liệu. Các volume dữ liệu từ cấu hình hạ tầng cũ cũng không bị tự động xóa; skeleton chưa có dữ liệu nghiệp vụ cần chuyển đổi.

## Làm việc nhóm

Đọc [hướng dẫn Git flow](docs/GIT_WORKFLOW.md) trước khi code. Thống nhất API và payload event với nhóm trước khi nối các service.
CI kiểm tra build, kết nối database của năm tài khoản, chặn truy cập chéo database và health của sáu service. Khi triển khai nghiệp vụ, bổ sung test trong từng service và test tích hợp cho OCC, Saga, recovery, idempotency và DLQ.

Phiên bản nền: Java 21, Spring Boot 4.1.1. Tài liệu chính thức: https://docs.spring.io/spring-boot/ . Không tự nâng version riêng từng service.
