# User Service

Chạy container cùng Payment và kiểm tra JWT liên service: [DOCKER.md](DOCKER.md).

Service tài khoản của hệ thống DS03. Cung cấp đăng ký Passenger/Driver, đăng nhập bằng email hoặc số điện thoại, cấp JWT và lấy thông tin tài khoản hiện tại.

## Công nghệ và phạm vi

- Java 21, Spring Boot 4.1.1, Spring MVC, Spring Data JPA.
- MySQL với database riêng `user_db`; không truy cập database của service khác.
- BCrypt qua jBCrypt để lưu và kiểm tra mật khẩu.
- Spring Security và JWT ký HS256; không dùng HTTP session để ghi nhớ đăng nhập.
- Thời gian trong bảng `users` lưu theo UTC; response kèm offset UTC (`Z` hoặc `+00:00`). JDBC đặt session MySQL và Hibernate dùng UTC. JWT tiếp tục dùng Instant, không đổi cách tính thời hạn.

Service không quản lý xe, chuyến đi hoặc thanh toán. Service sở hữu API nghiệp vụ phải tự xác minh JWT và kiểm tra role, quyền sở hữu tài nguyên.

## API

Base URL khi chạy local hoặc truy cập từ máy host qua Compose: `http://localhost:8081`.

| Method | Endpoint | Quyền truy cập | Kết quả thành công |
|---|---|---|---|
| POST | `/auth/register` | Công khai | 201, thông tin tài khoản |
| POST | `/auth/login` | Công khai | 200, JWT và thông tin tài khoản |
| GET | `/users/me` | Bearer JWT hợp lệ, role được hỗ trợ | 200, tài khoản của người gọi |
| GET | `/actuator/health` | Công khai | 200 khi health là UP |

Các request không khớp quy tắc trong SecurityConfig mặc định bị chặn. API công khai nên gọi với Authorization = No Auth, không gửi token cũ hoặc không hợp lệ.

### Đăng ký

POST `/auth/register`, Content-Type: `application/json`.

```json
{
  "name": "Demo User",
  "email": "user@example.com",
  "phone": "0912345678",
  "password": "<MAT_KHAU_TU_CHON>",
  "role": "PASSENGER"
}
```

Thay placeholder bằng mật khẩu tự chọn trước khi gửi. Quy tắc dữ liệu:

- `name`: bắt buộc, không chỉ có khoảng trắng, tối đa 100 ký tự; bỏ khoảng trắng đầu/cuối khi lưu.
- `email`: bắt buộc, đúng định dạng, tối đa 254 ký tự; chuẩn hóa chữ thường khi lưu.
- `phone`: đúng 10 chữ số, bắt đầu bằng `0`; giữ nguyên định dạng khi lưu.
- `password`: không rỗng, từ 8 đến 72 ký tự và không vượt 72 byte UTF-8; không trim, lưu bằng BCrypt với salt ngẫu nhiên.
- `role`: chỉ cho đăng ký `PASSENGER` hoặc `DRIVER`.
- Email và phone không được trùng tài khoản đã có; database bảo vệ bằng UNIQUE.
- UUID và thời gian tạo do ứng dụng sinh, status mặc định `ACTIVE`.

Thành công trả UserResponse gồm `id`, `name`, `email`, `phone`, `role`, `status`, `createdAt`. Không trả password/password_hash và không cấp JWT ở bước đăng ký.

### Đăng nhập bằng email hoặc số điện thoại

POST `/auth/login`, Content-Type: `application/json`.

Bằng email:

```json
{
  "identifier": "user@example.com",
  "password": "<MAT_KHAU_CUA_TAI_KHOAN>"
}
```

Bằng số điện thoại:

```json
{
  "identifier": "0912345678",
  "password": "<MAT_KHAU_CUA_TAI_KHOAN>"
}
```

`identifier` bắt buộc, tối đa 254 ký tự: email dạng `user@example.com` hoặc phone như quy tắc đăng ký. Không nhận phone có mã quốc gia. Trường login là `identifier`, không phải `email`/`phone`. Email được chuyển chữ thường trước khi tìm kiếm; password được giữ nguyên.

Service tìm tài khoản theo email/phone, kiểm tra BCrypt và status ACTIVE, sau đó cấp JWT. Không tìm thấy tài khoản thì từ chối ngay. Đăng nhập không áp dụng min 8 ký tự để hỗ trợ tài khoản đã tồn tại, nhưng vẫn yêu cầu password không rỗng, tối đa 72 ký tự và 72 byte UTF-8.

Response 200 gồm:

- `accessToken`: JWT vừa được cấp.
- `tokenType`: `Bearer`.
- `expiresIn`: thời gian hiệu lực tính bằng giây, mặc định 3600.
- `user`: UserResponse, không chứa password/hash.

Không tìm thấy email/phone, sai mật khẩu hoặc tài khoản DISABLED đều trả 401 với cùng thông báo.

### Thông tin tài khoản hiện tại

GET `/users/me`, không cần request body:

```http
Authorization: Bearer <accessToken>
```

Trong Postman chọn Authorization → Bearer Token, dán giá trị accessToken, không thêm dấu ngoặc kép hoặc tiền tố Bearer vào ô Token.

API lấy userId từ subject của JWT đã xác minh, không lấy từ query/body của client. Sau đó đọc tài khoản trong DB và kiểm tra lại ACTIVE. Tài khoản bị xóa hoặc DISABLED bị từ chối dù token còn hạn.

### Mã phản hồi lỗi

| HTTP status | Trường hợp |
|---|---|
| 400 | Validation/JSON sai; đăng ký vượt giới hạn 72 byte UTF-8 |
| 401 | Thông tin đăng nhập không hợp lệ, tài khoản không khả dụng; token thiếu/sai/hết hạn; password login vượt 72 byte nhưng qua validation ký tự |
| 403 | Token hợp lệ nhưng không đủ quyền theo quy tắc API |
| 409 | Email/phone trùng hoặc vi phạm ràng buộc dữ liệu khi đăng ký |
| 413 | Tổng request body vượt giới hạn cấu hình, mặc định 65536 byte (64 KiB) |

Lỗi ứng dụng được GlobalExceptionHandler chuyển thành ProblemDetail. Lỗi tại filter Spring Security có thể không có JSON body; không yêu cầu mọi lỗi có cùng cấu trúc. Response không công khai SQL, password hoặc hash.

## Chuẩn bị database

Cần Maven 3.9 trở lên và MySQL chạy được. Phiên bản MySQL nhóm chọn cho Compose là 8.4.

Chuẩn bị `user_db` với bảng `users` có các cột:

| Cột | Kiểu / ràng buộc |
|---|---|
| id | CHAR(36), PRIMARY KEY |
| name | VARCHAR(100), NOT NULL |
| email | VARCHAR(254), NOT NULL, UNIQUE |
| phone | VARCHAR(20), NOT NULL, UNIQUE |
| password_hash | VARCHAR(255), NOT NULL |
| role | VARCHAR(16), NOT NULL |
| status | VARCHAR(16), NOT NULL, DEFAULT 'ACTIVE' |
| created_at | DATETIME(6), NOT NULL |

Dùng InnoDB và utf8mb4. Tạo tài khoản `user_app` có quyền SELECT, INSERT, UPDATE, DELETE chỉ trên `user_db`. Root dành cho quản trị/khởi tạo. Không lưu mật khẩu thật trong SQL được commit.

Local dùng `ddl-auto: validate`: thiếu/sai bảng có thể khiến ứng dụng không khởi động. Cấu hình mặc định dùng `ddl-auto: none`: không tự tạo/sửa bảng. Hiện không có migration tự tạo bảng users; người triển khai cần chuẩn bị bảng trước khi dùng API.

## Chạy local bằng PowerShell

Lệnh ngắn tại thư mục gốc repository:

```powershell
& ./src/user-service/scripts/run-local.ps1
```

Script bật profile local, dùng cổng 8081 nếu chưa đặt SERVER_PORT, hỏi mật khẩu MySQL nếu terminal chưa có MYSQL_PASSWORD và tạo khóa JWT nếu chưa có JWT_SECRET. Không ghi secret vào file. Các lần chạy lại trong cùng terminal giữ nguyên cấu hình. Nếu máy chặn script PowerShell, dùng các lệnh thủ công bên dưới thay vì thay đổi chính sách bảo mật toàn máy.

Tại thư mục gốc repository:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:SERVER_PORT = '8081'
$env:MYSQL_USERNAME = 'user_app'
$dbCredential = Get-Credential -UserName 'user_app' -Message 'Nhap mat khau MySQL'
$env:MYSQL_PASSWORD = $dbCredential.GetNetworkCredential().Password
if (-not $env:JWT_SECRET) {
    $jwtBytes = New-Object byte[] 32
    $jwtRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $jwtRandom.GetBytes($jwtBytes) } finally { $jwtRandom.Dispose() }
    $env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
}

mvn -f src/user-service/pom.xml spring-boot:run
```

Giữ terminal chạy. Ở terminal khác:

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
```

Mong đợi `status: UP`. Profile local tắt RabbitMQ health check; không cần broker cho các API User hiện tại. Maven không tự nạp `.env`; chạy trực tiếp cần biến môi trường trong chính terminal chạy Maven.

Dừng bằng Ctrl+C. Trong cùng terminal, các lần chạy sau chỉ cần lệnh Maven. Để kết thúc phiên và xóa secret khỏi môi trường:

```powershell
Remove-Item Env:MYSQL_PASSWORD, Env:JWT_SECRET -ErrorAction SilentlyContinue
$dbCredential = $null
$jwtBytes = $null
```

### Biến môi trường

| Biến | Mặc định / ý nghĩa |
|---|---|
| SPRING_PROFILES_ACTIVE | Dùng `local` khi chạy Java ngoài Docker |
| SERVER_PORT | Local: 8081; cấu hình gốc trong container: 8080 |
| MYSQL_HOST | localhost |
| MYSQL_PORT | 3306 |
| MYSQL_USERNAME | user_app |
| MYSQL_PASSWORD | Bắt buộc, không có mật khẩu mặc định trong ứng dụng |
| JWT_SECRET | Bắt buộc: Base64 của ít nhất 32 byte ngẫu nhiên |
| MAX_REQUEST_BODY_BYTES | 65536 byte (64 KiB), giới hạn toàn bộ request body |
| JWT_ISSUER | ds03-user-service |
| JWT_EXPIRES_IN_SECONDS | 3600, phải lớn hơn 0 |

Cấu hình gốc dùng thêm RABBITMQ_HOST/PORT/USER/PASSWORD; Compose truyền các giá trị này. Local có giá trị dự phòng cho tài khoản broker và tắt health broker.

## Docker Compose và CI

Xem hướng dẫn chung tại [README gốc](../../README.md). Copy `.env.example` thành `.env` nếu chưa có và cấu hình các biến cần thiết. JWT_SECRET phải được cung cấp qua môi trường hoặc `.env` đã được Git ignore; giá trị trong `.env.example` để trống. Có thể tạo khóa bằng đoạn PowerShell ở trên mà không bật profile local trong container.

- Host gọi cổng 8081, container lắng nghe 8080.
- User Service kết nối MySQL qua DNS nội bộ `mysql`, không qua MySQL local trên host.
- Bảng users trong MySQL local không tự xuất hiện trong MySQL Docker. Flyway tự áp dụng `db/migration/V1__users.sql` để tạo bảng trên database mới; Hibernate không tự tạo bảng.
- Với database đã có bảng, Flyway baseline ở version 0 rồi áp dụng V1. `CREATE TABLE IF NOT EXISTS` giữ nguyên bảng và dữ liệu cũ; bảng hiện có phải có đủ cột và UNIQUE email/phone như entity. Migration này không tự sửa schema cũ hoặc chuyển thời gian cũ sang UTC.
- CI tạo khóa JWT ngẫu nhiên tạm thời cho job Compose; test tự tạo khóa riêng.

Không commit `.env`, khóa JWT, mật khẩu thật hoặc token còn hiệu lực. Thay JWT_SECRET làm token ký bằng khóa cũ không còn xác minh được.

## JWT và tích hợp

JWT dùng chữ ký HS256, gồm `iss`, `sub` (userId), `userId`, `role`, `iat`, `exp`, `jti`. Mỗi lần đăng nhập thành công cấp token mới. Payload không chứa password, email hoặc phone; JWT được ký, không mã hóa nội dung.

Các service tích hợp cần thống nhất JWT_SECRET và JWT_ISSUER, xác minh chữ ký/thời hạn, đọc role và kiểm tra quyền sở hữu tài nguyên. Với HS256, service có khóa xác minh cũng có khả năng ký token; chỉ chia sẻ khóa trong môi trường service tin cậy.

Hiện chưa có refresh token, logout/thu hồi token hoặc đồng bộ trạng thái khóa tài khoản sang service khác. Token cũ có thể cùng tồn tại đến khi hết hạn. Việc kiểm tra DISABLED trong /users/me không tự vô hiệu hóa token tại các service khác. Decoder có dung sai đồng hồ mặc định nên kiểm thử hết hạn nên dùng bộ test tự động.

Nguồn tham khảo: [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

## Kiểm thử

Đã đối chiếu code User với `origin/develop`: code nghiệp vụ trước bước chuẩn bị
Docker khớp bản đã merge. Phần bổ sung mới là Flyway/migration tạo schema trên
database mới và truyền JWT_ISSUER/SERVER_PORT rõ ràng trong Compose; chưa cần
commit lại toàn bộ code User từ nhánh Payment.

Kiểm chứng ngày 31/10/2026: `mvn verify` đạt 22 test mặc định (2 test database
được bỏ qua ở lượt này). Chạy riêng `RegistrationDatabaseTest` với Java 21 trong
Maven container kết nối MySQL Docker 8.4.13, database riêng `user_test`, đạt thêm
2/2 test. Flyway áp dụng V1 trên database trống, Hibernate validate schema thành
công; test đăng ký đồng thời cùng email xác nhận chỉ một tài khoản được tạo.
Sau bước chuẩn bị, đã build/chạy image User bằng Java 21 và kiểm tra JWT liên
service qua HTTP thật: đăng nhập và /users/me đạt; JWT User gọi Payment đúng quyền,
Passenger khác và Driver bị từ chối. Xem [kết quả Docker](DOCKER.md).
Mật khẩu test được lưu trong `.local/` được Git ignore.

Chạy test không cần MySQL/broker hoặc khóa JWT thật:

```powershell
mvn -f src/user-service/pom.xml test
```

Bộ test hiện có 24 hàm: mặc định 22 chạy, 2 test MySQL được bỏ qua.

| Test | Nội dung |
|---|---|
| UserResponseTest | Thời gian tạo tài khoản và response dùng UTC |
| RequestSizeLimitFilterTest | Body đúng biên, quá giới hạn, không có Content-Length và số byte UTF-8 |
| RegisterRequestTest | Trường bắt buộc, định dạng, độ dài, role đăng ký, che password khi toString |
| UserServiceTest | BCrypt, email chuẩn hóa, phone giữ nguyên, chống trùng, giới hạn UTF-8 và role |
| AuthControllerTest | Phản hồi đăng ký 201/400/409, validation, không lộ password/SQL |
| LoginSecurityTest | Email/phone login, sai thông tin, tài khoản khóa, JWT, /users/me, 401/403 |
| RegistrationDatabaseTest | Lưu MySQL thật; đăng ký đồng thời cùng email chỉ một thành công |

Để chạy đủ 24 test, chuẩn bị MySQL và MYSQL_USERNAME/MYSQL_PASSWORD như hướng dẫn local, rồi bật:

```powershell
$env:USER_DB_TEST = 'true'
try {
    mvn -f src/user-service/pom.xml test
} finally {
    Remove-Item Env:USER_DB_TEST -ErrorAction SilentlyContinue
}
```

Test database dùng email ngẫu nhiên, tự xóa đúng bản ghi test; không sửa tài khoản mẫu. Chỉ bật trên DB phát triển/test. MockMvc dùng HTTP giả lập trong JVM với filter Security thật; không thay thế toàn bộ kiểm thử mạng và liên service.

## Cấu trúc mã nguồn

| Thư mục | Trách nhiệm |
|---|---|
| config | Khóa/encoder/decoder JWT và quy tắc Spring Security |
| controller | Nhận request, trả response |
| dto | Request/response và validation |
| model | Entity User và enum nội bộ |
| repository | Truy cập users bằng Spring Data JPA |
| service | Đăng ký, đăng nhập, tạo JWT và đọc tài khoản hiện tại |
| exception | Xử lý lỗi ứng dụng |

## Chuyển dữ liệu cũ sang UTC

Cấu hình UTC không tự chuyển giá trị DATETIME đã lưu. Bản ghi tạo trước thay đổi này đang theo UTC+7 cần trừ 7 giờ đúng một lần, giữ nguyên thời điểm thực tế. Trước khi chuyển cần dừng service, sao lưu dữ liệu và xác định chính xác tập bản ghi cũ; không cập nhật toàn bộ bảng nếu đã có bản ghi UTC. Không tự chạy chuyển đổi khi ứng dụng khởi động. Dữ liệu cũ chưa được chuyển bởi thay đổi code này. Khi thao tác SQL thủ công, đặt session time_zone = '+00:00' trước khi ghi thời gian.

## Giới hạn request body

RequestSizeLimitFilter chạy trước xác thực JWT trong SecurityFilterChain. Mặc định chấp nhận tối đa 65536 byte, vượt giới hạn trả HTTP 413 với application/problem+json trước khi đến Controller/DB. Filter kiểm tra cả Content-Length lẫn dữ liệu thực đọc được (không quá limit + 1 byte), nên vẫn chặn body không có Content-Length. Request hợp lệ được chuyển tiếp nguyên nội dung cho Spring MVC. Giới hạn tính theo byte, không phải ký tự, không giới hạn kích thước header. Các API hiện dùng đọc body đồng bộ; wrapper không hỗ trợ non-blocking Servlet reads. MAX_REQUEST_BODY_BYTES có thể cấu hình qua môi trường khi chạy local. Mức 64 KiB là lựa chọn V1 của nhóm, không phải con số bắt buộc trong đề.
