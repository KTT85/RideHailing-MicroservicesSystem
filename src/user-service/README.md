# User Service

Service quản lý tài khoản, đăng ký và đăng nhập cho hệ thống DS03.

## Trạng thái triển khai

Đã triển khai:

- Entity ánh xạ bảng `user_db.users` và DTO.
- Kết nối MySQL, lưu thời gian theo giờ Việt Nam (UTC+7).
- API đăng ký PASSENGER/DRIVER.
- Chuẩn hóa email, kiểm tra số điện thoại và lưu mật khẩu BCrypt.
- Validation, xử lý lỗi và kiểm thử nghiệp vụ đăng ký.

Đăng nhập, cấp JWT, xác thực/phân quyền và `GET /users/me` chưa triển khai.

## Yêu cầu môi trường

- Java 21 trở lên, Maven 3.9 trở lên.
- MySQL truy cập được tại `localhost:3306` hoặc địa chỉ cấu hình.
- Có database `user_db` và bảng `users` với các cột: `id`, `name`, `email`, `phone`, `password_hash`, `role`, `status`, `created_at`.
- Email và phone có ràng buộc UNIQUE; id là CHAR(36), created_at là DATETIME(6).
- Tài khoản MySQL `user_app` có quyền SELECT, INSERT, UPDATE, DELETE trên `user_db`.

Không đưa mật khẩu database hoặc khóa bí mật vào repository.

## Chạy trên máy cá nhân

Tại thư mục gốc repository, mở PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:MYSQL_USERNAME = 'user_app'
$dbCredential = Get-Credential -UserName 'user_app' -Message 'Nhập mật khẩu MySQL'
$env:MYSQL_PASSWORD = $dbCredential.GetNetworkCredential().Password
mvn -f src/user-service/pom.xml spring-boot:run
```

Các biến cấu hình:

| Biến | Mặc định / yêu cầu |
|---|---|
| `SPRING_PROFILES_ACTIVE` | Đặt `local` khi chạy trên máy cá nhân |
| `SERVER_PORT` | `8081` với profile local |
| `MYSQL_HOST` | `localhost` |
| `MYSQL_PORT` | `3306` |
| `MYSQL_USERNAME` | `user_app` |
| `MYSQL_PASSWORD` | Bắt buộc cung cấp qua môi trường |

Profile local kiểm tra cấu trúc bảng bằng `ddl-auto: validate`, không tự tạo hoặc sửa bảng. Health check RabbitMQ được tắt trong profile này vì giai đoạn đăng ký chưa dùng messaging. Maven không tự đọc `.env`.

Mở terminal khác để kiểm tra:

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
```

Kết quả mong đợi: `status` là `UP`.

Dừng ứng dụng bằng Ctrl+C, sau đó xóa mật khẩu khỏi môi trường terminal:

```powershell
Remove-Item Env:MYSQL_PASSWORD -ErrorAction SilentlyContinue
$dbCredential = $null
```

## Chạy bằng Docker Compose

Dùng hướng dẫn và `.env.example` trong README gốc repository. Không bật profile local trong Docker.

- Cổng HTTP trong container: `8080`.
- Cổng truy cập từ máy host: `8081`.
- MySQL được tìm qua DNS nội bộ `mysql`; thông tin kết nối do Compose truyền vào.
- Cấu hình mặc định `ddl-auto: none` yêu cầu chuẩn bị bảng trước khi dùng API.

## API đăng ký

`POST /auth/register`, Content-Type: `application/json`.

```json
{
  "name": "Demo User",
  "email": "user@example.com",
  "phone": "0912345678",
  "password": "<MAT_KHAU_TU_CHON>",
  "role": "PASSENGER"
}
```

Khi chạy local, URL đầy đủ là `http://localhost:8081/auth/register`. Thay giá trị placeholder mật khẩu trước khi gửi request.

Quy tắc:

- Name bắt buộc, tối đa 100 ký tự.
- Email hợp lệ, tối đa 254 ký tự; service chuẩn hóa chữ thường.
- Phone phải gồm 10 chữ số, bắt đầu bằng `0`; lưu nguyên định dạng này.
- Password từ 8 ký tự, không vượt 72 byte UTF-8; không được lưu nguyên văn.
- Role đăng ký chỉ là `PASSENGER` hoặc `DRIVER`.
- Tài khoản mới có status `ACTIVE`; UUID và created_at do ứng dụng tạo.
- Response chứa thông tin tài khoản, không chứa password/password_hash. Thời gian trả về kèm offset `+07:00`.

| HTTP status | Ý nghĩa |
|---|---|
| `201` | Đã tạo tài khoản |
| `400` | JSON hoặc dữ liệu đầu vào không hợp lệ |
| `409` | Email/phone trùng hoặc vi phạm ràng buộc dữ liệu |

Ràng buộc UNIQUE trong database bảo vệ trước các request đồng thời; kiểm tra tồn tại trong service giúp trả thông báo rõ ràng.

## Cấu trúc code

| Thư mục | Trách nhiệm |
|---|---|
| `model` | Entity User và enum trong User.java |
| `dto` | Dữ liệu request/response, validation |
| `repository` | Truy vấn và lưu tài khoản bằng Spring Data JPA |
| `service` | Nghiệp vụ đăng ký, chuẩn hóa và BCrypt |
| `controller` | HTTP endpoint |
| `exception` | Chuyển lỗi thành response ProblemDetail |

## Kiểm thử

```powershell
mvn -f src/user-service/pom.xml test
```

Các kiểm thử bao gồm:

Bộ test được gộp thành 12 hàm: 4 DTO, 4 Service, 2 Controller và 2 MySQL. Các dữ liệu tương tự được kiểm tra trong vòng lặp. Chạy mặc định có 10 test và bỏ qua 2 test MySQL; bật USER_DB_TEST để chạy đủ 12.

- DTO: mọi trường bắt buộc, định dạng và giới hạn độ dài, role đăng ký, không lộ password qua toString.
- Service: BCrypt, chuẩn hóa email, giữ nguyên phone, role, email/phone trùng, giới hạn 72 byte và lỗi ràng buộc DB.
- Controller và handler qua MockMvc: HTTP 201/400/409, validation trước khi gọi service, JSON sai và response không chứa password/chi tiết SQL.

Test MySQL thật mặc định không chạy. Khi đã đặt MYSQL_USERNAME và MYSQL_PASSWORD như hướng dẫn kết nối, bật riêng:

```powershell
$env:USER_DB_TEST = 'true'
try {
    mvn -f src/user-service/pom.xml test
} finally {
    Remove-Item Env:USER_DB_TEST -ErrorAction SilentlyContinue
}
```

RegistrationDatabaseTest kiểm tra lưu dữ liệu thật và hai luồng cùng đăng ký một email (chỉ một thành công). Test tạo email ngẫu nhiên, xóa đúng tài khoản test sau khi chạy, không sửa tài khoản mẫu. Không bật trên DB production. MockMvc mô phỏng HTTP trong JVM; không phải test mạng ngoài process. Đăng nhập/JWT chưa triển khai nên chưa có test cho các chức năng đó.
