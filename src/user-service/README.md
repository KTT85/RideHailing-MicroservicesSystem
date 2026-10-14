# User Service

Service quản lý tài khoản, đăng ký và đăng nhập cho hệ thống DS03.

## Trạng thái triển khai

Đã triển khai:

- Entity ánh xạ bảng `user_db.users` và DTO.
- Kết nối MySQL, lưu thời gian theo giờ Việt Nam (UTC+7).
- API đăng ký PASSENGER/DRIVER.
- Chuẩn hóa email, kiểm tra số điện thoại và lưu mật khẩu BCrypt.
- Validation, xử lý lỗi và kiểm thử nghiệp vụ đăng ký.

Đã thêm đăng nhập, cấp JWT và bảo vệ `GET /users/me` bằng Spring Security. Phân quyền thao tác chuyến/xe thuộc service sở hữu API đó; các service cần thống nhất contract JWT bên dưới.

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
# Tạo khóa cho phiên local; không in hoặc commit khóa.
if (-not $env:JWT_SECRET) {
    $jwtBytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtBytes)
    $env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
}
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
| `JWT_SECRET` | Base64 của ít nhất 32 byte ngẫu nhiên; bắt buộc |
| `JWT_ISSUER` | `ds03-user-service` |
| `JWT_EXPIRES_IN_SECONDS` | `3600` (1 giờ) |

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

Trước khi chạy Compose, tạo JWT_SECRET như ví dụ local và giữ trong môi trường, hoặc lưu vào `.env` đã được Git ignore. `.env.example` để trống giá trị này. CI tự tạo khóa tạm khi chạy. Thay khóa làm các JWT đã cấp bằng khóa cũ không còn hợp lệ.

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

Bộ test đăng ký được gộp thành 12 hàm: 4 DTO, 4 Service, 2 Controller và 2 MySQL. Thêm 6 hàm LoginSecurityTest kiểm tra đăng nhập và JWT với filter Spring Security thật. Chạy mặc định 16 test và bỏ qua 2 test MySQL; bật USER_DB_TEST để chạy đủ 18.

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

RegistrationDatabaseTest kiểm tra lưu dữ liệu thật và hai luồng cùng đăng ký một email (chỉ một thành công). Test tạo email ngẫu nhiên, xóa đúng tài khoản test sau khi chạy, không sửa tài khoản mẫu. Không bật trên DB production. MockMvc mô phỏng HTTP trong JVM; không phải test mạng ngoài process. Test JWT tự tạo khóa ngẫu nhiên, không cần secret thật.

## Đăng nhập và thông tin tài khoản

POST `http://localhost:8081/auth/login`:

```json
{"identifier":"user@example.com","password":"<MAT_KHAU_CUA_TAI_KHOAN>"}
```

Trường identifier nhận email hoặc số điện thoại 10 chữ số bắt đầu bằng 0. Ví dụ đăng nhập bằng số: `{"identifier":"0912345678","password":"<MAT_KHAU_CUA_TAI_KHOAN>"}`. Request cũ dùng trường email cần đổi sang identifier; API đăng ký vẫn giữ email và phone riêng.

Thành công trả 200 với accessToken, tokenType `Bearer`, expiresIn (giây) và user không chứa password/hash. Không tìm thấy email/phone, sai mật khẩu hoặc tài khoản DISABLED trả 401 với cùng thông báo. Định dạng identifier sai trả 400. Login không áp dụng min 8 ký tự để tài khoản mẫu cũ vẫn đăng nhập được; đăng ký mới vẫn yêu cầu min 8. Không tự cấp token khi register.

GET `http://localhost:8081/users/me`, header `Authorization: Bearer <accessToken>`. Postman: Authorization → Bearer Token → dán token từ login. API chỉ trả tài khoản theo subject của token, không nhận userId của người khác từ client; kiểm tra lại ACTIVE trong DB. Thiếu/sai/hết hạn token trả 401; token có role ngoài danh sách hỗ trợ trả 403.

Contract JWT: chữ ký HS256, issuer ds03-user-service, subject = userId, claims userId/role, iat/exp/jti. Payload không chứa mật khẩu/email/phone. JWT được ký, không mã hóa. Các service cần cùng cấu hình khóa/issuer, kiểm tra chữ ký/thời hạn và kiểm soát quyền cùng quyền sở hữu tài nguyên tại API của mình. Không có refresh token, logout/revocation hay đồng bộ trạng thái DISABLED sang service khác; token tại service khác có thể còn hiệu lực đến exp. HS256 phù hợp V1 với các service tin cậy; service giữ khóa xác minh cũng có khả năng ký token.

Các API bảo mật khác mặc định bị chặn; /auth/register, /auth/login và GET /actuator/health được phép gọi công khai. Không dùng session cookie. Nguồn triển khai: [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
