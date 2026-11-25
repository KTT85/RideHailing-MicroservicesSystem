# DS03 – Ride-Hailing Microservices

Dự án đặt xe gồm sáu microservice và một web demo để chạy các ca kiểm thử JWT, ghép tài xế, thanh toán, Saga hoàn tiền và phục hồi qua RabbitMQ.

## Chạy dự án

Cần Docker Desktop có Compose và Node.js 20+ để chạy demo. Từ thư mục gốc của repo, chạy trong PowerShell:

```powershell
& ./scripts/ds03-demo.ps1 -Stage Up -EnableFailpoints
```

Script tạo `.env` từ mẫu và sinh các token cần thiết nếu chưa có. `-EnableFailpoints` bật hai ca F01/F02. Lần đầu tải dependency và build có thể lâu.

Mở terminal thứ hai tại thư mục gốc, rồi chạy:

```powershell
node demo/web/local-demo.cjs
```

Nếu Node báo `EADDRINUSE` ở cổng `4175`, mở [Trạm xe](http://127.0.0.1:4175/#live) để kiểm tra bản đang chạy trước khi mở thêm tiến trình.

Mở [Trạm xe](http://127.0.0.1:4175/#live), bấm **Kiểm tra lại** và đợi **6/6 dịch vụ sẵn sàng**. Chọn điểm đón và điểm trả cho các ca đặt xe. Mỗi lần chạy, bảng **Kết quả demo** hiển thị phản hồi API của ca đó và đường dẫn hàng đợi quan sát nếu ca dùng RabbitMQ. Ca hiệu năng cần `k6` trong PATH hoặc biến `DS03_K6_BIN` trỏ đến tệp thực thi.

Thành viên chạy web từ bản clone trên máy của mình. Web dùng Node.js để gọi các service Docker cục bộ. [RabbitMQ Management](http://127.0.0.1:15672/#/queues) dùng tài khoản trong `.env`. MySQL chỉ mở trong mạng nội bộ của Compose.

| Service | Port | Database | Owner |
|---|---|---|---|
| user | 8081 | user_db | TV1 |
| driver | 8082 | driver_db | TV2 |
| trip | 8083 (8080 trong container) | trip_db | TV3 |
| matching | 8084 | không có | TV2 |
| payment | 8085 | payment_db | TV1 |
| notification | 8086 | notification_db | TV3 |

Build/test các module Java cần Java 21+ và Maven 3.9+. Chạy `mvn -B verify` hoặc kiểm tra riêng Trip với `mvn -B -f src/trip-service/pom.xml verify`.

Tắt stack bằng `docker compose -p ds03-demo down`. Lệnh này giữ dữ liệu trong volume. Xem log Trip bằng `docker compose -p ds03-demo logs -f trip-service`.

## Phân công

| Thành viên | Thư mục sở hữu | Nhánh |
|---|---|---|
| TV1 | src/user-service | feature/user-service |
| TV1 | src/payment-service | feature/payment-service |
| TV2 | src/driver-service | feature/driver-service |
| TV2 | src/matching-service | feature/matching-service |
| TV3 | src/trip-service | feature/trip-service |
| TV3 | src/notification-service | feature/notification-service |

Sáu service dùng Java 21 và Spring Boot. Trip dùng Spring Data JPA với MySQL. Matching gọi Driver qua REST, còn các luồng bất đồng bộ dùng RabbitMQ. Năm service có lưu dữ liệu dùng chung một máy chủ MySQL 8.4, mỗi service có database và tài khoản riêng. Matching không dùng database.

Trip chốt giá khi tạo chuyến theo khoảng cách đường chim bay (Haversine) giữa hai tọa độ. Giá mô phỏng là 8.000 VND/km cho xe máy và 15.000 VND/km cho ô tô, làm tròn đến đồng và tối thiểu 1 VND. Payment dùng số tiền đã lưu của chuyến để thu hoặc hoàn tiền.

## Cấu hình MySQL

Docker Compose tự khởi tạo `user_db`, `driver_db`, `trip_db`, `payment_db`, `notification_db` và năm tài khoản tương ứng qua [script khởi tạo](docker/mysql/init/01-create-databases.sh). Root chỉ dùng cho quản trị/khởi tạo; các service dùng `user_app`, `driver_app`, `trip_app`, `payment_app`, `notification_app`.

Bảng nghiệp vụ được tạo bằng script khởi tạo hoặc migration của từng service. Trip tạo các bảng còn thiếu khi khởi động và dùng lại dữ liệu trong `trip_db`. Các service chỉ truy cập database được cấp cho mình.

Khi chạy Java ngoài Docker, cung cấp `MYSQL_HOST`, `MYSQL_PORT` (mặc định `3306`), `MYSQL_USERNAME`, `MYSQL_PASSWORD` cùng biến RabbitMQ. MySQL phải truy cập được từ máy chạy Java; Compose mặc định chỉ cho các container kết nối MySQL qua mạng nội bộ. JDBC trong cấu hình local tắt TLS và cho phép lấy public key; môi trường triển khai thật cần cấu hình TLS phù hợp.

Script khởi tạo chỉ chạy khi volume MySQL còn mới. Nếu đổi mật khẩu trong `.env` sau lần chạy đầu, cần cập nhật tài khoản MySQL tương ứng bằng `ALTER USER`.

## Làm việc nhóm

Đọc [hướng dẫn Git flow](docs/GIT_WORKFLOW.md) trước khi code. Thống nhất API và payload event với nhóm trước khi nối các service.
CI kiểm tra build, kết nối database của năm tài khoản, chặn truy cập chéo database và health của sáu service. Khi triển khai nghiệp vụ, bổ sung test trong từng service và test tích hợp cho OCC, Saga, recovery, idempotency và DLQ.

Phiên bản nền: Java 21, Spring Boot 4.1.1. Tài liệu chính thức: https://docs.spring.io/spring-boot/ . Không tự nâng version riêng từng service.
