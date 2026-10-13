# User Service — phần 1: Model và DTO

Theo README gốc và Docker Compose, client gọi User Service qua `http://localhost:8081`; container lắng nghe cổng `8080`. Chạy Java trực tiếp cần đặt `SERVER_PORT=8081`.

Phần này chỉ thêm model và DTO, chưa triển khai endpoint:

- `POST /auth/register`: RegisterRequest → UserResponse.
- `POST /auth/login`: LoginRequest → LoginResponse; expiresIn tính bằng giây, tokenType dự kiến là Bearer.
- `GET /users/me`: UserResponse.

Model khớp bảng users đã tạo: UUID CHAR(36), email/phone unique, password_hash, role, status và created_at DATETIME(6). Password hash được truyền vào constructor sau khi service mã hóa BCrypt; không truyền mật khẩu gốc. Không trả entity trực tiếp từ controller.

Theo quyết định mới của Kiên, thêm phone bắt buộc và role ADMIN. Đăng ký công khai chỉ cho PASSENGER/DRIVER; ADMIN tạo riêng. RegisterRequest nhận số Việt Nam dạng 0 + 9 chữ số hoặc +84 + 9 chữ số. Service ở phần sau sẽ chuẩn hóa email/phone và kiểm tra giới hạn BCrypt 72 byte UTF-8 (Size chỉ đếm ký tự).

createdAt trong DB dùng giờ Việt Nam; response kèm offset +07:00. JDBC đặt session MySQL bằng offset +07:00 (mã hóa dấu + thành %2B trong URL), không yêu cầu cài bảng múi giờ MySQL. Hibernate dùng Asia/Ho_Chi_Minh do Java xử lý. Tài liệu nhóm ban đầu chưa cập nhật phone/ADMIN/giờ Việt Nam; cần thông báo các thay đổi contract cho nhóm.

Phần tiếp theo: repository, cấu hình database và BCrypt, service/controller đăng ký, xử lý lỗi và kiểm thử. Thư mục event/messaging chưa cần dùng cho đăng ký.

## Kết nối MySQL trên máy cá nhân

1. Trong Workbench, mở kết nối DS03 (root), chạy nội dung `scripts/create-local-db-user.sql` sau khi thay mật khẩu mẫu ngay trong tab SQL. Không lưu mật khẩu thật vào file trong Git. Nếu user_app đã tồn tại, CREATE USER không đổi mật khẩu; dùng mật khẩu hiện có hoặc chủ động ALTER USER qua Workbench.
2. Trong terminal PowerShell ở thư mục gốc dự án:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:MYSQL_USERNAME = 'user_app'
$dbCredential = Get-Credential -UserName 'user_app' -Message 'Nhập mật khẩu MySQL user_app'
$env:MYSQL_PASSWORD = $dbCredential.GetNetworkCredential().Password
mvn -f src/user-service/pom.xml spring-boot:run
```

Host mặc định localhost, port MySQL 3306, database user_db. Profile local dùng HTTP 8081 và validate schema có sẵn, không tự tạo/sửa bảng. Không cần RabbitMQ để kiểm tra health ở giai đoạn này. Maven không tự đọc file .env; các biến trên chỉ áp dụng cho terminal hiện tại và process con.

3. Mở terminal khác để kiểm tra:

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
```

Khi trả status UP thì health check database thành công. Các API auth chưa được viết ở phần này. Dừng bằng Ctrl+C và xóa mật khẩu khỏi môi trường terminal khi xong:

```powershell
Remove-Item Env:MYSQL_PASSWORD
$dbCredential = $null
```

Docker vẫn dùng cấu hình gốc (cổng trong container 8080, host mysql và tài khoản từ Compose), không bật profile local trong Docker.
