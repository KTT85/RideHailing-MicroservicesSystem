# KẾ HOẠCH HOÀN CHỈNH DS03 – RIDE-HAILING MICROSERVICES

**Bản thống nhất ngày 11/10/2026**

**Sơ đồ triển khai:** xem [Bộ sơ đồ đầy đủ L01–L20](so_do_luong.md). Driver từ chối ở **L09**, Driver không phản hồi quá hạn ở **L10**, khách hủy ở **L11–L13**, Saga hủy và phục hồi ở **L14**. Mỗi luồng ghi service phụ trách, API/event, trạng thái và nhánh lỗi.

**Quyết định thanh toán:** hỗ trợ `CASH` (tiền mặt cuối chuyến) và `TRANSFER` (chuyển khoản mô phỏng trước chuyến). Với TRANSFER, thanh toán thành công mới cho Driver nhận chuyến. Khách hủy trước khi bắt đầu thì hoàn 100% khoản đã thu. Không có cơ chế giữ tiền, thu khoản giữ sau chuyến hoặc hủy khoản giữ.


### Phân loại phạm vi: bắt buộc và tùy chọn

- **[BẮT BUỘC]:** yêu cầu của đề và các nghiệp vụ nhóm đã chốt: CASH/TRANSFER, refund, deadline và xử lý trạng thái. Các thành viên phải triển khai và kiểm thử.
- **[TÙY CHỌN – ĐÃ CHỌN]:** tính năng nâng cao nhóm đang dự kiến triển khai, gồm Outbox đơn giản, Docker Compose và CI; không đổi thành yêu cầu bắt buộc của đề.
- **[TÙY CHỌN – CHƯA CHỌN]:** các phần nâng cao ngoài phạm vi hiện tại; chỉ cân nhắc sau phần bắt buộc.

Cột căn cứ ghi rõ yêu cầu của đề và lựa chọn của nhóm. Nhãn BẮT BUỘC áp dụng cho phần cốt lõi và nghiệp vụ nhóm chốt; phần nâng cao vẫn là TÙY CHỌN, kể cả khi nhóm đã chọn triển khai. Hiện nhóm vẫn giữ thiết kế Outbox đơn giản; đổi nhãn không có nghĩa bỏ thiết kế này.

Đề gốc là “COURSE PROJECT SPECIFICATION – Distributed Systems”, đặc biệt mục 4, 7, 9–13. Không đánh đồng lựa chọn công nghệ/cấu trúc bảng với yêu cầu nguyên văn của thầy.

| Hạng mục | Phân loại | Căn cứ / cách đáp ứng |
|---|---|---|
| Ít nhất 4 business microservices, độc lập, giao tiếp mạng | BẮT BUỘC | 7.1–7.2; nhóm chọn 6 service |
| Dữ liệu/schema riêng, không truy cập DB service khác | BẮT BUỘC | 7.2; nhóm chọn 1 MySQL Server, 5 schema/database và tài khoản riêng |
| Đăng ký/đăng nhập, role Passenger/Driver | BẮT BUỘC | 7.3 |
| Driver cập nhật trạng thái và vị trí mô phỏng | BẮT BUỘC | 7.3 |
| Passenger tạo yêu cầu, tìm/chọn Driver | BẮT BUỘC | 7.3 |
| Driver chấp nhận VÀ từ chối chuyến | BẮT BUỘC | 7.3; phải có accept và reject |
| Driver không bị gán hai chuyến đồng thời | BẮT BUỘC | 7.3; chọn atomic update/ràng buộc DB |
| Vòng đời chuyến, tính giá, Payment mô phỏng, thông báo | BẮT BUỘC | 7.3; đề không chốt trả trước/giữ tiền/trả sau |
| REST hoặc gRPC và message queue bất đồng bộ | BẮT BUỘC | 7.3; nhóm chọn REST + RabbitMQ |
| Event ID hoặc idempotency key, retry, deduplication, correlation ID | BẮT BUỘC | 7.3 |
| DLQ hoặc nơi lưu message lỗi | BẮT BUỘC | 7.3; nhóm chọn retry queue + DLQ |
| Ít nhất một giao dịch phân tán dùng Saga hoặc bù trừ | BẮT BUỘC | 7.3; PAY lỗi → release, hủy sau PAY → release/refund |
| Xử lý duplicate/out-of-order, timeout/ACK, mô hình nhất quán và phát hiện thành phần lỗi | BẮT BUỘC | 4.2–4.4; định nghĩa cụ thể ở mục 3, 9, 10 |
| Xác thực, kiểm tra quyền/input, không lưu mật khẩu rõ, không commit secret, giới hạn message, log | BẮT BUỘC | 4.5; JWT/BCrypt là lựa chọn nhóm |
| Test tối thiểu 8 functional + 2 concurrency + 2 recovery + 2 security + 1 performance | BẮT BUỘC | 9; tổng ít nhất 15 |
| Demo tranh Driver, dừng service, retry/bù trừ, restart tiếp tục | BẮT BUỘC | 7.4; yêu cầu chung 4.4 còn cần ít nhất 2 kịch bản lỗi |
| Repository, báo cáo, bằng chứng từng thành viên, tag/release, demo | BẮT BUỘC | 10–13; mục bàn giao ở phần 15 |
| CASH + TRANSFER; reserve trước rồi mới PAY, thành công mới cho Driver nhận | BẮT BUỘC | Quyết định nghiệp vụ; không phải thứ tự thầy bắt buộc |
| Khách hủy trước bắt đầu; Driver từ chối/không nhận; hoàn 100% nếu đã thu | BẮT BUỘC | Nhóm chọn các nhánh này; refund là bắt buộc để nhánh hủy sau thu tiền không giữ tiền khách sai |
| Driver từ chối → hủy và refund thay vì tìm Driver mới | BẮT BUỘC | Giảm phạm vi V1; tìm lại tự động là option chưa chọn |
| Deadline chờ thanh toán và chờ Driver nhận | BẮT BUỘC | Tự động giải quyết tác vụ bị bỏ dở; giá trị timeout cấu hình được |
| MySQL, Java/Spring, 14 bảng, hai bảng Saga, Matching không DB | BẮT BUỘC | Cách triển khai, đề không quy định số bảng/công nghệ này |
| Transactional Outbox | TÙY CHỌN – ĐÃ CHỌN | 7.5 nâng cao; nhóm giữ để xử lý commit DB nhưng chưa gửi event |
| Docker Compose | TÙY CHỌN – ĐÃ CHỌN | 7.5 nâng cao; triển khai độc lập là bắt buộc, Docker không phải công cụ duy nhất |
| Gateway, registry discovery, circuit breaker, tracing, logging tập trung, nhiều instance, dynamic pricing, geospatial matching, dashboard | TÙY CHỌN | 7.5; correlation ID/log cơ bản vẫn bắt buộc |
| TLS | TÙY CHỌN | 4.5 khuyến khích, không ghi là bắt buộc |
| Frontend, tìm lại sau Driver từ chối, refund từng phần, phí hủy | TÙY CHỌN | Ngoài phạm vi V1 |
## 1. Mục tiêu và phạm vi

Xây dựng hệ thống mô phỏng Grab/Uber: Passenger đặt chuyến, Matching tìm tài xế, Driver thực hiện chuyến, Payment xử lý thanh toán mô phỏng và Notification lưu thông báo.

Trọng tâm là hệ thống phân tán: các service độc lập, giao tiếp qua mạng, xử lý đồng thời, nhất quán nghiệp vụ, bù trừ khi lỗi và phục hồi sau crash.

Phạm vi V1:

- Một chuyến có một Passenger và tối đa một Driver; mỗi Driver có một xe.
- Giá cố định lúc tạo chuyến, tính từ khoảng cách mô phỏng và công thức đơn giản.
- Chọn Driver AVAILABLE theo vị trí/khoảng cách mô phỏng; không cần GPS thời gian thực hoặc bản đồ.
- Passenger chọn CASH hoặc TRANSFER khi tạo chuyến; không đổi phương thức giữa chừng.
- TRANSFER: Matching reserve Driver trước, Passenger thanh toán sau; thành công mới phát thông báo chính thức và cho Driver accept/reject. Hoàn thành chuyến không thu thêm.
- CASH được Driver xác nhận đã nhận tiền khi chuyến kết thúc; Payment chỉ lưu xác nhận, không tự động thu tiền mặt.
- Cho Passenger hủy trước khi bước bắt đầu chuyến được chấp nhận. TRANSFER đã thu thì hoàn toàn phần; CASH chưa thu thì không hoàn tiền.
- Driver được từ chối ở MATCHED hoặc rút khỏi chuyến ở ACCEPTED trước khi bắt đầu. V1 hủy chuyến, release và refund nếu đã thu; không tự tìm Driver khác.
- Driver không phản hồi ở MATCHED trong thời hạn cấu hình thì hệ thống hủy, release và refund nếu đã thu. V1 không tự hủy ACCEPTED chỉ vì Driver chưa bắt đầu.
- Không hỗ trợ hủy khi STARTING, IN_PROGRESS hoặc COMPLETED; không có phí hủy, thanh toán/hoàn tiền từng phần hoặc khiếu nại sau chuyến.
- Không tích hợp ngân hàng, ví hoặc cổng thanh toán thật. Kết quả chuyển khoản/hoàn tiền do bộ mô phỏng nội bộ quyết định; không coi nút “đã chuyển” của client là bằng chứng thanh toán.

## 2. Công nghệ

| Thành phần | Công nghệ |
|---|---|
| Backend | Java 21, Spring Boot; các service thống nhất phiên bản đã cấu hình trong repository |
| Database / ORM | MySQL 8.4, Spring Data JPA |
| Message Broker | RabbitMQ |
| Authentication | Spring Security, JWT, BCrypt |
| Container | Docker, Docker Compose |
| API / automated testing | Postman, JUnit, Spring Boot Test |
| Performance | k6 |
| Source Control | GitHub |
| Frontend | Tùy chọn, làm sau phần backend |

## 3. Kiến trúc và quyền sở hữu dữ liệu

| Service | Trách nhiệm | Database |
|---|---|---|
| User | Đăng ký, đăng nhập, cấp JWT, role PASSENGER/DRIVER | user_db |
| Driver | Hồ sơ, xe, vị trí; availability, reserve, start, release | driver_db |
| Trip | Tạo chuyến, tính giá, kiểm tra quyền, quản lý vòng đời, điều phối Saga | trip_db |
| Matching | Tìm ứng viên, reserve qua Driver, phát kết quả | Không DB |
| Payment | PAY/REFUND mô phỏng cho TRANSFER; ghi nhận tiền mặt; idempotency và phục hồi | payment_db |
| Notification | Nhận event, lưu thông báo, đánh dấu đã đọc | notification_db |

Một MySQL Server chứa năm database. Mỗi service có tài khoản riêng chỉ truy cập database mình sở hữu. Không JOIN, tạo foreign key hoặc cập nhật SQL xuyên database. ID liên service là tham chiếu logic; đọc dữ liệu liên service qua API/event.

Trip sở hữu trạng thái chuyến; Driver sở hữu trạng thái tài xế; Payment sở hữu trạng thái tiền. Trip chỉ lưu bản sao `payment_status` từ kết quả Payment.

**[ĐỀ BẮT BUỘC – 4.2–4.3] Mô hình nhất quán và discovery:** mỗi service dùng transaction/khóa trong MySQL để bảo vệ dữ liệu cục bộ; bản sao dữ liệu giữa các service cập nhật theo eventual consistency. Không tuyên bố một transaction ACID xuyên năm database. Bất biến “Driver không có hai chuyến”, “không PAY/REFUND trùng” và “cancel/start không cùng thắng” phải được giữ ngay ở service sở hữu.

Service có tên duy nhất user-service, driver-service, trip-service, matching-service, payment-service, notification-service. Dùng DNS nội bộ Docker Compose và URL cấu hình để tìm service; chưa cần registry như Eureka/Consul. Dùng healthcheck, trạng thái kết nối RabbitMQ và REST timeout để phát hiện thành phần không phản hồi; không coi timeout là bằng chứng thao tác nghiệp vụ chưa thực hiện. Giới hạn V1 một instance/worker mỗi service, phân tích single point of failure của MySQL/RabbitMQ trong báo cáo.

## 4. REST và RabbitMQ

| Tình huống | Giao tiếp | Lý do |
|---|---|---|
| Đăng ký, đăng nhập, sửa hồ sơ/vị trí | REST | Phản hồi trực tiếp cho người dùng |
| Tạo/xem/nhận/từ chối/bắt đầu/hoàn thành/hủy chuyến | REST | Kiểm tra quyền và tiếp nhận thao tác ngay |
| Passenger yêu cầu chuyển khoản mô phỏng | REST tới Trip, sau đó event tới Payment | Trip kiểm tra chuyến trước khi tạo tác vụ tiền |
| Trip yêu cầu Matching | RabbitMQ | Xử lý tìm xe bất đồng bộ |
| Matching lấy ứng viên, tra reservation, reserve | REST nội bộ tới Driver | Cần kết quả ngay để quyết định ứng viên |
| Matching báo kết quả | RabbitMQ | Trip nhận kết quả bất đồng bộ |
| Trip yêu cầu PAY/REFUND, ghi nhận CASH | RabbitMQ | Payment thực hiện độc lập, trả kết quả qua event |
| Trip yêu cầu start/release tài xế | REST nội bộ | Cần xác nhận bước Saga; timeout thì lưu tiến độ và retry |
| Notification nhận kết quả nghiệp vụ | RabbitMQ | Không chặn nghiệp vụ chính |
| Tra Payment khi timeout | REST nội bộ | Xác định kết quả thực tế, không đoán thất bại |

REST tiếp nhận lệnh không bảo đảm toàn bộ quy trình liên service hoàn tất ngay. Các API bất đồng bộ trả `202 Accepted` cùng trạng thái hiện tại; client dùng API đọc để theo dõi.

## 5. Luồng nghiệp vụ

### 5.1. Đăng ký và chuẩn bị tài xế

Passenger/Driver đăng ký và đăng nhập tại User để nhận JWT. Driver tạo hồ sơ/xe, cập nhật vị trí và bật nhận chuyến. Chỉ Driver có hồ sơ, xe và vị trí hợp lệ mới được AVAILABLE.

### 5.2. Đặt chuyến và tìm tài xế – dùng chung

1. Passenger gọi `POST /trips` với điểm đón/đến, loại xe, `paymentMethod` và khóa chống tạo trùng.
2. Trip kiểm tra quyền, tính giá, lưu REQUESTED, tạo Saga đặt chuyến và lưu `TripRequested` trong outbox cùng transaction. Trả tripId, không chờ Matching.
3. Matching nhận event, tra reservation theo tripId trước. Nếu đã có thì dùng lại kết quả; không giữ tài xế mới.
4. Nếu chưa có reservation, Matching kiểm tra chuyến còn cần tìm xe, lấy ứng viên từ Driver và thử reserve. Conflict thì thử ứng viên khác; timeout thì tra lại reservation trước khi quyết định.
5. Reserve thành công, Matching phát `DriverMatched`; chỉ ACK đầu vào sau khi kết quả được RabbitMQ xác nhận.
6. Trip lưu driverId và driverUserId, kiểm tra trạng thái chuyến rồi rẽ nhánh:
   - CASH: chuyển MATCHED và phát `TripConfirmed`, không chờ trả tiền.
   - TRANSFER: chuyển PENDING_PAYMENT, chờ Passenger yêu cầu thanh toán. GET /trips hiển thị “Đã tìm được tài xế, vui lòng thanh toán”; Driver chưa nhận được lời mời chính thức và chưa được accept/reject/start.
7. Không có ứng viên: `DriverMatchFailed`; Trip chuyển CANCELLED_NO_DRIVER nếu không có reservation còn cần xử lý.

Giữ tài xế (`RESERVED`) là reservation tài nguyên Driver, không phải giữ tiền của Passenger. Khi TRANSFER thành công mới phát TripConfirmed cho Passenger và Driver. Với CASH, phát ngay sau reserve. MATCHED nghĩa là lời mời chuyến đã sẵn sàng, không có nghĩa Driver đã chấp nhận.

### 5.3. TRANSFER – thanh toán trước khi Driver nhận chuyến

```text
REQUESTED → reserve Driver → PENDING_PAYMENT
  → Passenger POST /trips/{id}/pay
  → Trip outbox: PaymentRequested
  → Payment PAY mô phỏng
      ├─ SUCCESS → PaymentSucceeded → Trip MATCHED → TripConfirmed
      └─ FAILED  → PaymentFailed → Trip CANCELLING
                   → release Driver → CANCELLED_PAYMENT_FAILED
```

Trip chỉ tạo yêu cầu PAY khi đúng Passenger, phương thức TRANSFER và chuyến PENDING_PAYMENT. Ghi trạng thái đã yêu cầu cùng outbox trong transaction, nên bấm liên tục không tạo nhiều thao tác thu tiền. Payment dùng một thao tác PAY với khóa ổn định theo tripId, không thu lại khi nhận yêu cầu lặp.

V1 cho khoảng chờ Passenger thanh toán có cấu hình, ví dụ 5 phút. Chỉ hủy vì chưa thanh toán nếu chưa có yêu cầu PAY được tiếp nhận, được kiểm tra trong transaction. Nếu PAY đã được gửi và còn PENDING, không kết luận thất bại chỉ vì timeout; tra Payment/chờ kết quả hoặc chuyển cần kiểm tra khi vượt giới hạn phục hồi.

Payment mô phỏng thành công nghĩa là tiền đã thu. Nếu Trip đang CANCELLING khi thành công đến muộn, không chuyển MATCHED; tiếp tục Saga hoàn tiền.

### 5.4. CASH – trả tiền cuối chuyến

```text
REQUESTED → reserve Driver → MATCHED → TripConfirmed
  → ACCEPTED → STARTING → IN_PROGRESS → COMPLETED
  → Driver xác nhận đã nhận tiền
  → CashPaymentConfirmationRequested → Payment lưu CASH SUCCESS
  → PaymentSucceeded → Trip cập nhật payment_status
```

Chuyến CASH có thể COMPLETED trong khi payment_status còn PENDING nếu Driver chưa xác nhận nhận tiền. Không coi hoàn thành chuyến là đã nhận tiền; không tự thu tiền mặt bằng event.

Driver được giao chuyến gọi `POST /trips/{id}/cash-payment-confirmation` sau khi COMPLETED. Trip kiểm tra quyền/phương thức/trạng thái, lấy số tiền từ giá đã chốt và phát yêu cầu xác nhận. Payment chỉ ghi nhận xác nhận của Driver; đây không phải xác minh giao dịch ngân hàng.

Payment có thể nhận `TripCompleted` của CASH để tạo bản ghi tiền mặt PENDING. Nếu event xác nhận tiền mặt đến trước, consumer vẫn tạo/cập nhật an toàn và không bị event đến muộn hạ SUCCESS về PENDING.

### 5.5. Driver nhận, bắt đầu và hoàn thành

- Accept: chỉ Driver được gán, Trip MATCHED và chưa hết hạn phản hồi. CASH không cần đã trả; TRANSFER phải payment_status SUCCESS. Chuyển ACCEPTED.
- Reject/rút khỏi chuyến: chỉ Driver được gán, Trip MATCHED hoặc ACCEPTED và chưa STARTING. Chuyển CANCELLING với cancel_reason DRIVER_REJECTED; chạy Saga release/refund, không tự tìm lại trong V1.
- Không nhận: từ khi MATCHED, lưu driver_response_deadline_at (ví dụ 60 giây, cấu hình được). Nếu vẫn MATCHED khi hết hạn, conditional update sang CANCELLING với DRIVER_NO_RESPONSE. Accept và job timeout phải cạnh tranh trên cùng trạng thái và deadline; chỉ một thao tác thắng.
- Start: chuyển có điều kiện ACCEPTED → STARTING để giành quyền bắt đầu; Saga yêu cầu Driver RESERVED → BUSY đúng tripId. Xác nhận thành công mới IN_PROGRESS và phát `TripStarted`.
- Nếu start timeout, lưu bước chưa xong và tra/retry idempotent; không giả định Driver và Trip đã cùng cập nhật.
- Complete: chỉ Driver được gán và Trip IN_PROGRESS. Lưu COMPLETED, tác vụ release và `TripCompleted` cùng transaction. Release đưa Driver về AVAILABLE hoặc OFFLINE theo accepting_trips.
- TRANSFER: hoàn thành không tạo PAY lần nữa. CASH: chưa có xác nhận nhận tiền thì còn PENDING.
- Release lỗi không đảo ngược COMPLETED và không làm mất tác vụ release; retry đến khi được xác nhận hoặc chuyển cần kiểm tra.

### 5.6. Hủy do Passenger, Driver từ chối hoặc không nhận

**[BẮT BUỘC TRONG DỰ ÁN]** Passenger chủ chuyến được yêu cầu hủy ở REQUESTED, PENDING_PAYMENT, MATCHED hoặc ACCEPTED. Driver được giao từ chối/rút khỏi ở MATCHED hoặc ACCEPTED. Hệ thống hủy nếu Driver không phản hồi khi MATCHED hết hạn. Không hỗ trợ hủy/rút khỏi ở STARTING, IN_PROGRESS, COMPLETED.

**[ĐỀ BẮT BUỘC – 7.3]** phải có chức năng Driver reject; **cách xử lý reject thành hủy + refund là lựa chọn V1 của nhóm**, đề không bắt buộc tự tìm lại.

Cạnh tranh cancel/start, reject/accept và accept/timeout được giải quyết trên trips bằng khóa dòng hoặc atomic conditional update. Hai yêu cầu hủy đến đồng thời chỉ dùng một CANCEL_TRIP Saga, một REFUND; giữ nguyên nguyên nhân hủy đầu tiên đã được chấp nhận. Request lặp của thao tác đã thành công trả trạng thái hiện tại, không chạy tác dụng phụ mới.

```text
Passenger hủy / Driver reject / Driver không nhận quá hạn → CANCELLING
  → Xác định reservation và kết quả tiền còn dang dở
  → Release Driver nếu đã được giữ
  → CASH: không refund vì hủy trước lúc thu tiền
  → TRANSFER:
       chưa thu / PAY thất bại → không refund
       đã thu                 → PaymentRefundRequested
                                 → REFUNDED hoặc retry khi lỗi
  → Khi release và phần tiền đã được xác nhận → CANCELLED
  → TripCancelled → Notification
```

Nếu hủy khi Matching chưa trả kết quả, không kết thúc ngay rồi bỏ qua reservation có thể đang hình thành. Matching kiểm tra trạng thái trước reserve; Trip xử lý cả kết quả đến muộn và lưu tác vụ release an toàn.

Nếu PAY đang xử lý, release tài xế đã biết có thể thực hiện ngay; Saga vẫn chờ kết quả tiền. PAY thành công muộn phải refund, PAY thất bại không refund. Không phát yêu cầu refund sớm rồi bỏ qua khả năng PAY thành công sau đó.

Cùng Saga hủy được dùng cho PASSENGER_CANCELLED, DRIVER_REJECTED và DRIVER_NO_RESPONSE. Refund toàn phần, không phí hủy. Nếu refund thất bại, giữ CANCELLING, lưu lịch retry và thông báo đang xử lý. Chỉ phát `PaymentRefunded` khi tiền mô phỏng đã hoàn; không đánh dấu hủy hoàn tất khi chưa xác nhận các bước cần thiết. Vượt giới hạn retry thì MANUAL_REVIEW, không giả vờ thành công.

### 5.7. Notification

| Event | Người nhận | Nội dung |
|---|---|---|
| TripConfirmed | Passenger, Driver | CASH đã reserve hoặc TRANSFER đã PAY SUCCESS; mời Driver accept/reject |
| TripStarted | Passenger | Chuyến đã bắt đầu |
| TripCompleted | Passenger | Chuyến đã hoàn thành |
| TripCancelled | Passenger, Driver nếu đã gán | Hủy chuyến đã hoàn tất |
| PaymentSucceeded | Passenger | TRANSFER đã thanh toán hoặc CASH đã được ghi nhận |
| PaymentRefunded | Passenger | Đã hoàn tiền |
| PaymentRefundFailed | Passenger | Hoàn tiền đang cần xử lý; không thông báo đã hoàn |

Consumer kiểm tra method để dùng nội dung đúng. Event phục vụ thông báo chứa ID tài khoản người nhận, không dùng nhầm driverId hồ sơ.

## 6. Trạng thái thống nhất

### Trip

| Trạng thái | Ý nghĩa |
|---|---|
| REQUESTED | Đang tìm Driver |
| PENDING_PAYMENT | TRANSFER đã reserve Driver, chưa xác nhận thanh toán thành công |
| MATCHED | Xác nhận ghép xe: CASH hoặc TRANSFER đã SUCCESS |
| ACCEPTED | Driver đã nhận |
| STARTING | Đã chấp nhận bắt đầu, chờ Driver BUSY được xác nhận |
| IN_PROGRESS | Đang thực hiện |
| COMPLETED | Đã hoàn thành |
| CANCELLING | Đang release/xác định tiền/refund |
| CANCELLED | Hủy do Passenger, Driver reject/không nhận hoặc hết thời gian chờ chưa gửi PAY; phân biệt bằng cancel_reason |
| CANCELLED_NO_DRIVER | Không tìm được tài xế |
| CANCELLED_PAYMENT_FAILED | PAY thất bại và đã bù trừ |

### Driver

OFFLINE, AVAILABLE, RESERVED, BUSY. AVAILABLE/OFFLINE có current_trip_id NULL; RESERVED/BUSY có current_trip_id.

### Payment

| Trạng thái | Ý nghĩa |
|---|---|
| PENDING | TRANSFER đang chờ/xử lý PAY hoặc CASH chưa xác nhận nhận tiền |
| SUCCESS | TRANSFER đã thu tiền hoặc CASH đã được Driver xác nhận |
| FAILED | PAY mô phỏng thất bại cuối cùng |
| REFUND_PENDING | TRANSFER đang hoàn tiền |
| REFUNDED | Đã hoàn toàn bộ tiền |
| REFUND_FAILED | Hoàn tiền lỗi, cần retry/kiểm tra |

Trip có thêm payment_status NOT_REQUESTED khi chưa tạo yêu cầu/bản ghi tiền. Trip status và payment_status độc lập. Retry refund có thể đưa REFUND_FAILED về REFUND_PENDING; không hạ PAY thành công về thất bại vì event cũ.

## 7. Database – 14 bảng

Chi tiết chuẩn cho migration/entity: [Thiết kế database DS03](docs/THIET_KE_DATABASE_DS03.md), gồm kiểu, NULL/default, PK/UNIQUE/FK/index, trạng thái và transaction. V1 giữ 5 database, 14 bảng; đây là đặc tả cần triển khai, chưa tạo bảng nghiệp vụ trên MySQL.

### Outbox đơn giản – tùy chọn đã chọn

Thiết kế đã có đúng **hai bảng `outbox_events`**, một trong `trip_db` và một trong `payment_db`. Đây là phần cần triển khai; code hiện tại mới là skeleton, chưa có entity/bảng Outbox, publisher worker hay consumer nghiệp vụ.

| Service | Cách xử lý event trong V1 |
|---|---|
| Trip – TV3 | Có Outbox: lưu thay đổi Trip/Saga và event cùng transaction |
| Payment – TV1 | Có Outbox: lưu PAY/REFUND/CONFIRM_CASH và event kết quả cùng transaction |
| Matching – TV2 | Không DB/Outbox; dùng publisher confirms; ACK yêu cầu sau khi gửi kết quả được xác nhận; redelivery tra lại reservation |
| Driver – TV2 | Không Outbox; reserve/start/release qua REST, atomic và idempotent |
| User – TV1 | Không Outbox khi chỉ đăng ký/đăng nhập, chưa phát event nghiệp vụ |
| Notification – TV3 | Không Outbox khi chỉ nhận event, lưu thông báo và phục vụ REST; vẫn dedup và ACK sau commit |

**Cách làm worker:** mỗi Trip/Payment chỉ chạy một instance và một vòng gửi tuần tự không chồng lấn. Scheduler đọc một batch nhỏ các event PENDING/RETRY_PENDING đã đến `next_retry_at`, gửi từng event qua RabbitMQ, chờ publisher confirm và kiểm tra message không bị trả về do không có route, rồi cập nhật PUBLISHED. Không giữ transaction cập nhật nghiệp vụ trong lúc chờ broker.

**Khi lỗi:** tăng `attempt_count`, lưu `last_error`, đặt lịch retry có backoff. Hết giới hạn thì FAILED, log/cảnh báo và giữ bản ghi để xử lý/gửi lại có kiểm soát; không xóa và không giả vờ đã gửi. Publisher retry và consumer retry/DLQ là hai phần khác nhau.

**Khi restart:** event chưa PUBLISHED vẫn được worker đọc tiếp. Không cần trạng thái SENDING/claim/lease trong bản một worker này. Nếu broker nhận rồi worker crash trước khi ghi PUBLISHED, event có thể gửi lại với cùng `event_id`; consumer bắt buộc chống trùng. Publisher confirm không chứng minh consumer đã hoàn tất nghiệp vụ.

**Chưa làm trong V1:** nhiều publisher/instance, distributed lock, lease, CDC/Debezium hoặc cơ chế dọn/lưu trữ Outbox nâng cao. Muốn mở rộng phải bổ sung cơ chế tranh việc; không chạy thêm worker ngoài phạm vi một instance đã chốt.

**Việc cần làm:** TV1 triển khai Outbox Payment; TV3 triển khai Outbox Trip; cả hai thống nhất envelope/eventId/correlationId và thử hai điểm crash: sau commit trước publish, sau broker confirm trước PUBLISHED. TV2 hoàn thiện confirm/ACK/recovery Matching. Không PAY/REFUND thêm khi event bị giao lại.

Quy ước ID CHAR(36), tiền DECIMAL(15,2), tọa độ DECIMAL(10,7), thời gian DATETIME(6) UTC. Bảng dưới là bộ trường V1; nhóm chốt kiểu/null/index trong ERD trước khi tạo migration.

| Database | Bảng | Trường chính |
|---|---|---|
| user_db | users | id, name, email, password_hash, role, status, created_at |
| driver_db | drivers | id, user_id, status, accepting_trips, current_trip_id, latitude, longitude |
| driver_db | vehicles | id, driver_id, license_plate, vehicle_type |
| trip_db | trips | id, passenger_id, driver_id, driver_user_id, vehicle_type, pickup_address/latitude/longitude, destination_address/latitude/longitude, fare, currency, payment_method, status, payment_status, request_key, correlation_id, cancel_reason, cancel_requested_at, driver_response_deadline_at, started_at, completed_at, cancelled_at, created_at, updated_at |
| trip_db | saga_instances | id, trip_id, saga_type, status |
| trip_db | saga_steps | id, saga_id, step_name, step_order, status, idempotency_key, attempt_count, next_retry_at, deadline_at, last_error |
| payment_db | payments | id, trip_id, passenger_id, method, amount, currency, status, refunded_amount, paid_at, refunded_at, created_at, updated_at |
| payment_db | payment_operations | id, payment_id, operation_type, idempotency_key, amount, status, attempt_count, next_retry_at, last_error, created_at, completed_at |
| notification_db | notifications | id, user_id, trip_id, type, content, is_read, event_id, created_at |
| trip_db, payment_db | outbox_events (mỗi DB một bảng) | event_id, aggregate_id, event_type, correlation_id, payload, status, attempt_count, next_retry_at, last_error, created_at |
| trip_db, payment_db, notification_db | processed_events (mỗi DB một bảng) | consumer_name, event_id, processed_at |

payment_method/method là CASH hoặc TRANSFER. payment_operations có PAY và REFUND cho TRANSFER, CONFIRM_CASH cho CASH. REFUND chỉ hợp lệ sau PAY SUCCESS, số tiền bằng toàn bộ amount; không refund CASH trong phạm vi hủy trước chuyến.

Ràng buộc:

- UNIQUE users.email; drivers.user_id; drivers.current_trip_id; vehicles.driver_id; vehicles.license_plate.
- UNIQUE trips(passenger_id, request_key); payments.trip_id; payment_operations.idempotency_key; payment_operations(payment_id, operation_type).
- UNIQUE saga_instances(trip_id, saga_type); saga_steps(saga_id, step_name); notifications(event_id, user_id, type).
- PK processed_events(consumer_name, event_id); FK chỉ trong từng DB: vehicles → drivers, saga_instances → trips, saga_steps → saga_instances, payment_operations → payments.
- Index theo passenger/driver và ngày tạo Trip; trạng thái Driver; trạng thái và lịch retry của Saga/Payment operation/outbox; user và is_read của Notification.

V1 một instance/worker nền mỗi service để giảm trường claim/lease. Worker phải có quy tắc phục hồi bước đang chạy khi restart, và thao tác bên nhận vẫn idempotent. Nếu chạy nhiều instance, bổ sung cơ chế claim/lease hoặc khóa worker trước khi mở rộng.

cancel_reason dùng PASSENGER_CANCELLED, DRIVER_REJECTED, DRIVER_NO_RESPONSE, PAYMENT_TIMEOUT, PAYMENT_FAILED hoặc NO_DRIVER. driver_response_deadline_at chỉ cần khi MATCHED đang chờ Driver phản hồi. Bộ 14 bảng là thiết kế nhóm, không phải số bảng thầy yêu cầu. Hai bảng outbox thuộc option đã chọn; bỏ chúng chỉ khi có phương án đáng tin cậy khác và cập nhật lại demo/phạm vi. Không có trip_status_history trong V1. Log chuyển trạng thái kèm tripId/correlationId, giữ các mốc quan trọng trong trips. Không có matching_db.

## 8. API

| Service | API | Caller / điều kiện |
|---|---|---|
| User | POST /auth/register, POST /auth/login, GET /users/me | Client |
| Driver | POST /drivers/profile | Driver |
| Driver | PATCH /drivers/me/availability, PATCH /drivers/me/location | Driver |
| Driver | GET /internal/drivers/available | Matching |
| Driver | GET /internal/drivers/reservations/by-trip/{tripId} | Matching/Trip |
| Driver | POST /internal/drivers/{id}/reserve | Matching; đúng tripId, tài xế rảnh |
| Driver | POST /internal/drivers/{id}/start-trip | Trip; đúng reservation |
| Driver | POST /internal/drivers/{id}/release | Trip; idempotent, đúng tripId |
| Trip | POST /trips | Passenger, paymentMethod CASH/TRANSFER, Idempotency-Key |
| Trip | GET /trips/{id} | Passenger chủ chuyến/Driver được giao |
| Trip | POST /trips/{id}/pay | Passenger chủ chuyến, TRANSFER, PENDING_PAYMENT; trả 202 |
| Trip | POST /trips/{id}/accept | Driver được giao, MATCHED, chưa quá hạn và điều kiện tiền hợp lệ |
| Trip | POST /trips/{id}/reject | Driver được giao, MATCHED hoặc ACCEPTED, chưa STARTING; trả 202 CANCELLING |
| Trip | POST /trips/{id}/start | Driver được giao; có thể trả 202 STARTING |
| Trip | POST /trips/{id}/complete | Driver được giao, IN_PROGRESS |
| Trip | POST /trips/{id}/cash-payment-confirmation | Driver được giao, CASH, COMPLETED; trả 202 |
| Trip | POST /trips/{id}/cancel | Passenger chủ chuyến, chưa STARTING; trả 202 CANCELLING |
| Trip | GET /internal/trips/{id}/matching-state | Matching kiểm tra chuyến còn cần tìm xe |
| Payment | GET /payments/by-trip/{tripId} | Người có quyền; kiểm tra chủ sở hữu |
| Payment | GET /internal/payments/by-trip/{tripId} | Trip đối soát kết quả khi timeout |
| Notification | GET /notifications/me, PATCH /notifications/{id}/read | Người nhận thông báo |

Client không được gọi refund trực tiếp để tùy ý chọn số tiền. Trip phát refund từ Saga hủy đã được chấp nhận. Các lệnh Payment lấy amount từ dữ liệu chuyến đã chốt, không tin số tiền client tự gửi.

API nội bộ có xác thực service-to-service riêng. JWT Passenger/Driver không được dùng để vượt quyền gọi API reserve/release/refund. API công khai kiểm tra cả role và quyền sở hữu. **[BẮT BUỘC – 4.5]** validate tọa độ, loại xe, phương thức tiền, ID và độ dài chuỗi; giới hạn request/event (ví dụ 64 KiB cho V1); không lưu mật khẩu rõ, không ghi secret vào log hoặc GitHub. .env.example chỉ chứa placeholder/cấu hình mẫu, secret thật lấy từ biến môi trường. JWT/BCrypt là công cụ nhóm chọn, bảo vệ quyền truy cập mới là yêu cầu bắt buộc.

## 9. Event RabbitMQ

| Event | Publisher | Consumer | Nhiệm vụ |
|---|---|---|---|
| TripRequested | Trip | Matching | Yêu cầu tìm Driver |
| DriverMatched | Matching | Trip | Reservation thành công |
| DriverMatchFailed | Matching | Trip | Không tìm được Driver |
| PaymentRequested | Trip | Payment | PAY TRANSFER trước chuyến |
| PaymentSucceeded | Payment | Trip, Notification | TRANSFER đã thu hoặc CASH đã xác nhận; payload chứa method |
| PaymentFailed | Payment | Trip | PAY thất bại cuối cùng |
| PaymentRefundRequested | Trip | Payment | Hoàn toàn phần TRANSFER đã thu |
| PaymentRefunded | Payment | Trip, Notification | Hoàn tiền thành công |
| PaymentRefundFailed | Payment | Trip, Notification | Refund lỗi/cần xử lý |
| CashPaymentConfirmationRequested | Trip | Payment | Ghi nhận Driver xác nhận tiền mặt |
| TripConfirmed | Trip | Notification | Sau reserve CASH hoặc PAY SUCCESS của TRANSFER; Driver được mời accept/reject |
| TripStarted | Trip | Notification | Chuyến bắt đầu |
| TripCompleted | Trip | Notification, Payment | Thông báo hoàn thành; Payment chỉ tạo/đối soát CASH PENDING, không thu TRANSFER lần nữa |
| TripCancelled | Trip | Notification | Hủy và các bước cần thiết đã hoàn tất |

Mỗi service consumer có queue riêng, retry queue và DLQ. Payment và Notification không tranh cùng một queue của TripCompleted.

Envelope thống nhất:

```json
{
  "eventId": "UUID",
  "eventType": "PaymentRequested",
  "correlationId": "UUID",
  "timestamp": "2026-10-11T08:00:00Z",
  "payload": {
    "tripId": "UUID",
    "passengerId": "UUID",
    "method": "TRANSFER",
    "amount": 50000,
    "currency": "VND",
    "idempotencyKey": "trip-UUID:PAY"
  }
}
```

Publish lại cùng outbox event giữ nguyên eventId. Gửi lại cùng thao tác nghiệp vụ có thể có eventId khác nhưng phải giữ idempotencyKey. Consumer vừa chống trùng event vừa kiểm tra trạng thái hợp lệ; không dùng timestamp làm cơ chế bảo đảm thứ tự.

## 10. Các yêu cầu phân tán

| Vấn đề | Cơ chế | Demo |
|---|---|---|
| Hai chuyến tranh một tài xế | Atomic Conditional Update / khóa phù hợp | Chỉ một reservation thành công |
| Hai tài xế bị giữ cho cùng chuyến | UNIQUE drivers.current_trip_id và kiểm tra reservation | Một tripId chỉ thuộc một Driver |
| Passenger bấm thanh toán liên tục | Một PAY/trip, idempotency key, UNIQUE | Chỉ một tác dụng thu tiền |
| Cancel đúng lúc start | Khóa/conditional update trên trips.status | Chỉ CANCELLING hoặc STARTING thắng |
| Event giao lại | processed_events và idempotency nghiệp vụ | Không PAY/REFUND/thông báo trùng |
| Matching crash sau reserve | Redelivery, tra reservation, publisher confirms | Dùng lại Driver cũ |
| REST timeout | Tra kết quả, retry idempotent có giới hạn | Không giả định kết quả chưa biết |
| PAY thất bại | Saga release Driver | Hủy sau khi release được xác nhận |
| Khách hủy sau PAY | Saga release và REFUND | Hoàn đúng một lần |
| Driver reject hoặc không nhận sau PAY | Cùng Saga hủy, conditional update và deadline | Release + refund; không còn Driver bị giữ |
| Refund hoặc release lỗi | saga_steps/payment_operations lưu tiến độ | Restart tiếp tục, không kết thúc giả |
| DB commit trước khi gửi event (TÙY CHỌN – ĐÃ CHỌN) | Transactional Outbox | Restart publisher vẫn gửi event |
| Consumer lỗi nhiều lần | Retry queue, DLQ | Cách ly message lỗi để kiểm tra |
| Sai role/chủ sở hữu | JWT và authorization | 401/403 |
| Khó truy lỗi | Correlation ID + log | Theo dõi xuyên service |

Reserve mẫu:

```sql
UPDATE drivers
SET status = 'RESERVED', current_trip_id = :tripId
WHERE id = :driverId
  AND status = 'AVAILABLE'
  AND current_trip_id IS NULL
  AND accepting_trips = TRUE;
```

Một dòng cập nhật là thành công. Không dòng nào thì tra reservation: nếu đã thuộc chính tripId thì yêu cầu lặp hợp lệ; nếu thuộc chuyến khác thì conflict. Vi phạm unique tripId phải được xử lý như tranh chấp, không bỏ ràng buộc.

Trip điều phối Saga, Payment tự quản lý retry PAY/REFUND và kết quả cuối cùng. Saga chỉ hoàn tất khi bước REST/event được xác nhận. Deadline hết không đồng nghĩa tiền thất bại. Khi thao tác vượt khả năng retry tự động, lưu MANUAL_REVIEW và trạng thái cần xử lý.

## 11. Phân công nhóm ba người

| Thành viên | Service | Trách nhiệm |
|---|---|---|
| TV1 | User + Payment | JWT, BCrypt, chuẩn xác thực; PAY/REFUND TRANSFER, xác nhận CASH; idempotency, retry/recovery Payment; outbox/processed events Payment |
| TV2 | Driver + Matching | Hồ sơ/xe/vị trí, reserve/start/release; concurrency; publisher confirms và recovery Matching; chủ trì Docker Compose/CI |
| TV3 | Trip + Notification | Vòng đời/giá/quyền chuyến; accept/reject, hủy do khách, timeout Driver; paymentMethod; điều phối/khôi phục Saga release-refund; thông báo; outbox/processed events; chủ trì API/event contract |

Mỗi người triển khai test, consumer retry/DLQ và chống trùng trong service mình. TV3 không chịu triển khai RabbitMQ hoặc độ tin cậy thay toàn bộ nhóm. Cả nhóm chốt contract và cùng kiểm thử các luồng liên service. Mỗi thành viên xem bảng phân loại ở đầu tài liệu: yêu cầu đề phải làm; nghiệp vụ BẮT BUỘC TRONG DỰ ÁN cũng phải làm trong V1; phần nâng cao vẫn ghi TÙY CHỌN, còn option chưa chọn không được tự thêm làm lệch contract.

## 12. Kiểm thử

**[BẮT BUỘC – 9]** ít nhất 8 functional, 2 concurrency, 2 fault/recovery, 2 security/invalid-data và 1 performance: tổng ít nhất 15. Hai recovery phải là hai kịch bản lỗi khác nhau có kiểm chứng phục hồi. Performance phải có kết quả đo, không chỉ script.

| Nhóm | Tối thiểu | Bằng chứng |
|---|---:|---|
| Functional | 8 | Test case đầu vào/kết quả mong đợi và thực tế; bao phủ User, Driver, Trip, accept/reject, Payment và Notification |
| Concurrency | 2 | Hai Passenger tranh Driver; cancel/start đồng thời, kiểm tra trạng thái DB cuối |
| Fault/recovery | 2 | Kill Matching sau reserve; kill Trip giữa release/refund rồi restart tiếp tục |
| Security/invalid-data | 2 | Sai quyền và thiếu JWT/input sai; không tính hai request thành công là security test |
| Performance | 1 | k6 hoặc công cụ tương đương; lưu số request, latency, throughput, tỷ lệ lỗi |

**[BẮT BUỘC TRONG DỰ ÁN – bổ sung]** các case PAY/REFUND, CASH, Driver không nhận và deadline phải được kiểm tra đúng dù số test vượt 15. **[TÙY CHỌN – ĐÃ CHỌN]** outbox phải có case commit/publish crash riêng.

- Functional: register/login; profile; tạo chuyến; matching; accept/reject/start/complete; TRANSFER; CASH; hủy/refund.
- Concurrency: hai chuyến tranh Driver; hai Driver tranh một chuyến; PAY bấm lặp; cancel/start; accept/reject và accept/timeout cùng lúc.
- Recovery: kill Matching sau reserve; restart Trip khi release/refund dang dở; restart Payment giữa PAY/REFUND; outbox publish bị gián đoạn.
- Event: PaymentSucceeded đến sau yêu cầu hủy; kết quả Matching đến muộn; Cash confirmation/TripCompleted đảo thứ tự; event trùng và message vào DLQ.
- Security: thiếu JWT, sai role, Passenger xem/hủy chuyến người khác, Driver nhận chuyến không được giao, client gọi API nội bộ.
- Payment: refund đúng amount và đúng một lần; không refund khi chưa PAY; TRANSFER complete không tạo PAY mới; CASH MATCHED không cần SUCCESS.
- Driver: reject sau PAY và không phản hồi quá hạn đều release/refund; CASH reject chỉ release; refund lỗi giữ CANCELLING; retry reject/cancel không hoàn hai lần.
- Performance: k6 tạo nhiều chuyến và kiểm tra không trùng reservation/tác dụng thanh toán.

## 13. Demo cuối kỳ: yêu cầu của thầy và việc từng thành viên phải làm

### 13.1. Phạm vi bắt buộc theo đề

- **Mục 7.4:** phải thể hiện đủ 9 nội dung trong bảng dưới. Có thể nối các nội dung thành một chuỗi demo, không phải 9 chương trình riêng.
- **Mục 7.2:** ít nhất 4 business microservices; nhóm chọn 6 service. Mục 11 nói tối thiểu 3 thành phần khi demo không thay thế yêu cầu 4 business microservices của DS03.
- **Mục 4.4:** phải chứng minh ít nhất **2 kịch bản lỗi khác nhau có xử lý và phục hồi**. Nhóm chọn Matching crash sau reserve và Trip crash giữa release/refund. Chỉ dừng rồi khởi động lại cùng service hai lần không tự thành hai kịch bản khác nhau.
- **Mục 11:** buổi demo phải có giao tiếp mạng, ít nhất một tình huống đồng thời, ít nhất một lỗi được gây ra trực tiếp, log/trace giải thích và cả 3 thành viên tham gia. Không chỉ chiếu ảnh kết quả có sẵn.
- Đề không yêu cầu đúng công cụ Postman, Docker Compose, RabbitMQ hay tên bảng Saga của nhóm. Nhóm dùng các công cụ này để thực hiện yêu cầu; không gọi dashboard/frontend là bắt buộc.

### 13.2. Chín nội dung demo DS03 bắt buộc

| Mã | Yêu cầu  | Việc cần làm trước demo | Thao tác khi demo và kết quả phải thấy | Phụ trách |
|---|---|---|---|---|
| D01 | Khởi động các service độc lập | Cấu hình 6 process/container, port riêng, URL/DNS nội bộ, tài khoản DB riêng; chuẩn bị script startup và health check | Cho thấy 6 service đang chạy; gọi health từng service. Dừng một service không làm mọi service khác tắt. Chỉ rõ HTTP REST và RabbitMQ là giao tiếp qua mạng, không truy cập DB của nhau | TV2 chủ trì hạ tầng; TV1/TV3 bảo đảm service mình chạy độc lập |
| D02 | Driver chuyển sang sẵn sàng | User có tài khoản DRIVER; Driver có hồ sơ, xe, vị trí; API availability có kiểm tra trạng thái | REST cập nhật vị trí và bật nhận chuyến; Driver chuyển AVAILABLE và xuất hiện trong danh sách ứng viên | TV1 tài khoản/JWT; TV2 thao tác Driver |
| D03 | Passenger yêu cầu chuyến | Tài khoản PASSENGER; API tạo chuyến, kiểm tra quyền/input, tính fare, request key; outbox TripRequested | REST POST /trips trả tripId; Trip REQUESTED; log/outbox/RabbitMQ thể hiện TripRequested với correlationId | TV3 chủ trì; TV1 chuẩn bị JWT |
| D04 | Matching chọn tài xế | Consumer TripRequested; lọc Driver phù hợp; REST reserve atomic/idempotent; event kết quả và publisher confirms | Log Matching nhận event, gọi REST Driver reserve; đúng một Driver RESERVED cho chuyến; RabbitMQ DriverMatched đến Trip | TV2; TV3 xử lý kết quả |
| D05 | Driver nhận và hoàn thành chuyến | API accept/start/complete, quyền Driver được giao, trạng thái hợp lệ; Payment mô phỏng và Notification theo mục 7.3; release bền vững | Với TRANSFER của nhóm: PAY SUCCESS → MATCHED → Driver accept → ACCEPTED → start → IN_PROGRESS → complete → COMPLETED. Driver được release; Payment không thu lần hai; client đọc thông báo qua REST | TV3 vòng đời/thông báo; TV1 PAY; TV2 Driver start/release |
| D06 | Hai Passenger cùng yêu cầu một Driver | Script bắn 2 request đồng thời có barrier; 2 Passenger; chỉ 1 Driver AVAILABLE, các Driver khác OFFLINE; khóa/key riêng cho hai chuyến | Hai POST /trips xuất phát gần đồng thời. Chỉ một chuyến giữ Driver; chuyến còn lại không có xe hoặc chờ theo policy. Không có thời điểm Driver được gán đồng thời hai chuyến | TV2 chủ trì atomic/concurrency; TV3 tạo/đối soát chuyến; TV1 tài khoản |
| D07 | Dừng một service giữa luồng nghiệp vụ | Fault injection có điểm dừng xác định và dữ liệu dang dở đã lưu; không chỉ kill service đang rảnh. Chuẩn bị kịch bản F01/F02 dưới đây | Dừng Matching sau reserve, hoặc Trip khi Saga còn dang dở; cho thấy service down, trạng thái trung gian và message/task chưa hoàn tất | TV2 F01; TV3 F02; TV1 đối soát tiền |
| D08 | Chứng minh retry, xử lý queue hoặc bù trừ | Retry có giới hạn/backoff; ACK đúng thời điểm; lưu Saga/operation; release/refund idempotent; có DLQ hoặc error storage theo mục 7.3 | Cho thấy retry attempt hoặc message chưa ACK/requeue; nếu hủy thì release/refund đúng. Không coi timeout là đã thất bại tiền; không đánh dấu CANCELLED khi còn thiếu xác nhận | Mỗi người chịu cơ chế trong service mình; TV3 điều phối bằng chứng |
| D09 | Khởi động lại service và tiếp tục xử lý message | Worker recovery đọc DB; consumer nhận lại message; không cần sửa DB bằng tay hoặc đặt chuyến mới để giải cứu chuyến cũ | Restart service đã dừng; xử lý tiếp đúng tripId/key; message được ACK sau xử lý; Saga/operation hoàn tất; Driver và tiền về trạng thái đúng | TV2/TV3 restart và giải thích; TV1 chứng minh không PAY/REFUND trùng |

D05 dùng thứ tự TRANSFER do nhóm chọn để đáp ứng ride lifecycle + simulated payment. Đề yêu cầu tính giá, thanh toán mô phỏng và accept/reject ở 7.3, nhưng không quy định phải trả trước, giữ tiền hay trả sau. Driver reject cần được triển khai và kiểm thử; reject không phải một dòng demo riêng được liệt kê ở 7.4.

### 13.3. Hai kịch bản lỗi/phục hồi nhóm phải chuẩn bị

#### F01. Matching crash sau khi giữ Driver, trước khi gửi kết quả

**Mục đích:** chứng minh giữ tài xế không bị mất dấu sau crash; không reserve thêm Driver khi RabbitMQ giao lại yêu cầu.

1. Seed 1 Passenger và 1 Driver AVAILABLE; tạo chuyến T01.
2. TV2 đặt hook/failpoint bật bằng cấu hình demo để dừng Matching **sau khi Driver xác nhận RESERVED**, trước publish DriverMatched/ACK. Hook phải bật/tắt được; không để hành vi crash mặc định trong ứng dụng.
3. Cho thấy Driver.current_trip_id=T01, Trip còn chờ Matching, message chưa ACK hoặc được broker đưa lại vào queue khi consumer mất kết nối.
4. Restart Matching; consumer nhận lại TripRequested với cùng eventId/tripId.
5. Matching gọi REST GET reservation theo T01, dùng lại Driver đã giữ, gửi DriverMatched; chờ publisher confirm rồi ACK.
6. Trip nhận kết quả và tiếp tục. Kiểm chứng chỉ một reservation, không gán thêm Driver; hoàn tất chuyến hoặc hủy theo luồng hợp lệ.

**Việc cần làm:** TV2 viết hook, recovery Matching, script stop/restart và test F01; TV3 ghi log nhận kết quả đến muộn/chống trùng và trạng thái Trip. Lưu eventId, correlationId, tripId và log trước/sau restart. Tham chiếu sơ đồ L03/L19.

#### F02. Trip crash khi hủy chuyến đã trả tiền, Saga chưa hoàn tất

**Mục đích:** chứng minh tiến độ bù trừ lưu bền vững, release/refund không bị bỏ quên và không thực hiện tác dụng tiền hai lần.

1. Tạo TRANSFER T02, reserve Driver và PAY SUCCESS; chưa start.
2. Passenger hủy; Trip lưu CANCELLING và CANCEL_TRIP Saga.
3. TV3 dùng hook để dừng Trip **sau khi release được xác nhận nhưng trước khi bước REFUND hoàn tất**; tiến độ đang có phải nằm trong DB.
4. Cho thấy Driver đã AVAILABLE/OFFLINE, Trip còn CANCELLING; Saga chưa hoàn tất, tiền chưa được xác nhận REFUNDED. Không sửa DB bằng tay.
5. Restart Trip; worker đọc Saga và tiếp tục bước còn thiếu, gửi PaymentRefundRequested với cùng key=tripId:REFUND.
6. Payment hoàn 100% đúng một lần và phát PaymentRefunded. Trip ghi nhận kết quả → CANCELLED → TripCancelled. Nếu event/yêu cầu bị gửi lại thì vẫn không hoàn thêm.

**Việc cần làm:** TV3 lưu tiến độ Saga, worker recovery, hook và test F02; TV1 xử lý REFUND idempotent, outbox kết quả, test yêu cầu lặp; TV2 release đúng tripId và xác nhận repeat an toàn. Tham chiếu L12–L14/L20.

**Tiêu chí chung:** mỗi kịch bản có bước gây lỗi, quan sát lúc lỗi, restart và kiểm tra trạng thái cuối. Chuẩn bị cả F01 và F02 để đáp ứng mục 4.4; ít nhất một kịch bản phải gây lỗi trực tiếp trong buổi trình bày theo mục 11. Nếu thầy yêu cầu chạy cả hai, script phải sẵn sàng.

### 13.4. Các cơ chế bắt buộc phải có bằng chứng

| Nội dung | Việc cần làm / bằng chứng | Người phụ trách |
|---|---|---|
| REST và message queue | Hiển thị request/response REST reserve và log RabbitMQ TripRequested/DriverMatched/PaymentSucceeded; nêu bên gửi, bên nhận | TV2 + TV3; TV1 event Payment |
| Event ID/idempotency và deduplication | Gửi lại cùng event hoặc PAY/REFUND key; kiểm tra chỉ một tác dụng nghiệp vụ, không chỉ một dòng log | Mỗi người trong service mình; TV1 tiền |
| Retry | Cho thấy attempt_count, next_retry_at/backoff và giới hạn; khi lỗi chưa giả vờ thành công | Mỗi người trong service mình |
| DLQ hoặc kho message lỗi | Chủ động tạo message lỗi theo test, cho thấy lỗi được lưu/đưa DLQ sau giới hạn và có quy trình xem/xử lý lại | Mỗi người consumer mình; TV3 chủ trì minh họa |
| Correlation ID và log | Cùng correlationId theo chuyến qua Trip/Matching/Driver/Payment/Notification; có service, timestamp, event và kết quả; không log token/mật khẩu | Cả nhóm |
| Saga hoặc compensation | F02 hoặc PAY thất bại: release/refund đúng, ghi tiến độ và chờ xác nhận, không mất tác vụ khi restart | TV3 + TV1 + TV2 |
| Bảo mật và phân quyền | Thiếu JWT, sai role/chủ sở hữu bị từ chối; BCrypt; bí mật qua env; validation và giới hạn request/message | TV1 chuẩn chung; TV2/TV3 bảo vệ API mình |
| Data isolation / discovery | Cho thấy account MySQL chỉ truy cập schema sở hữu; giao tiếp liên service qua API/event; giải thích DNS/URL nội bộ và timeout phát hiện service down | TV2 hạ tầng; mỗi người kiểm tra service mình |

Có thể dùng test/log đã lưu để trình bày chi tiết các cơ chế nếu thời gian không đủ chạy từng case, nhưng không thay thế concurrency và live failure bắt buộc tại mục 11. CI xanh hoặc ảnh queue một mình không chứng minh toàn bộ nghiệp vụ đúng.

### 13.5. Các luồng bắt buộc trong dự án

| Luồng của nhóm | Việc cần làm | Phụ trách / sơ đồ |
|---|---|---|
| CASH | Không chờ PAY trước accept; complete chưa tự thành đã thu; Driver xác nhận tiền mặt qua REST và event | TV1 + TV3; L07 |
| PAY thất bại | PaymentFailed → release → trạng thái hủy; không refund khoản chưa thu | TV1 + TV3 + TV2; L05 |
| Passenger hủy sau PAY | Tranh trạng thái với start; release và refund 100%; CANCELLED sau xác nhận | TV3 + TV1 + TV2; L11–L14 |
| Driver từ chối | Implement reject vì 7.3 yêu cầu; policy hủy/refund là nhóm chọn; kiểm tra quyền Driver, trạng thái và idempotency | TV3 + TV2 + TV1; L09 |
| Driver không nhận quá hạn | Scheduler Trip, deadline DB, tranh trạng thái với accept; release/refund; không dùng timer chỉ trong RAM | TV3 + TV2 + TV1; L10 |
| Bấm PAY/REFUND lặp | Khóa/key bền vững; tiền chỉ thay đổi một lần | TV1 + TV3; L04/L14/L17 |
| Cancel/start đồng thời | Chỉ một chuyển trạng thái thắng; STARTING thì không nhận hủy | TV3 + TV2; L15/L17 |
| Outbox | Commit nghiệp vụ và event cùng transaction; publisher retry, không mất event sau crash | TV1/TV3; L18/L20; option nâng cao đã chọn |

Các luồng này phải có test trong V1 đã chốt. Chuẩn bị collection/script demo bổ sung để chạy khi được hỏi; không tự bỏ chỉ vì không nằm trong 9 dòng 7.4.

### 13.6. Checklist bàn giao demo cho từng thành viên

**TV1 – User + Payment**

- [ ] Seed hai Passenger và Driver, login/JWT, BCrypt; test sai quyền/thiếu token.
- [ ] PAY SUCCESS/FAILED mô phỏng điều khiển được; không tích hợp ngân hàng thật.
- [ ] PAY/REFUND/CONFIRM_CASH idempotent; retry/recovery; outbox/consumer dedup; DLQ hoặc error storage.
- [ ] Chuẩn bị REST collection/script thanh toán và truy vấn kết quả; bằng chứng tổng thu/hoàn đúng một lần cho F02.
- [ ] Trong demo giải thích JWT, PAY trước chuyến, key chống trùng và refund/recovery của Payment.

**TV2 – Driver + Matching**

- [ ] Seed Driver/vehicle/location; script chỉ để một Driver AVAILABLE cho D06.
- [ ] REST reserve/start/release atomic và idempotent; không tác động reservation thuộc chuyến mới.
- [ ] Script concurrency có barrier, kiểm tra DB cuối; Matching confirms/ACK và recovery F01.
- [ ] Startup/health/stop/restart scripts; hook F01; hướng dẫn DNS/URL, schema và quyền DB.
- [ ] Trong demo giải thích reserve, tranh Driver, communication REST và phục hồi Matching.

**TV3 – Trip + Notification**

- [ ] Fare và vòng đời Trip; APIs create/accept/reject/start/complete/cancel; kiểm tra quyền/deadline.
- [ ] Saga đặt/start/hủy/release; progress DB, recovery F02; xử lý event đến muộn/trùng.
- [ ] Deadline Driver không phản hồi; Notification và REST đọc/đánh dấu đã đọc.
- [ ] Hook F02, collection vòng đời, truy vấn Saga/outbox; ghép kịch bản D01–D09 và log correlationId.
- [ ] Trong demo giải thích state machine, các điểm REST/RabbitMQ, Saga và nhất quán cuối cùng.

**Cả nhóm:** mỗi người viết test service mình, đóng góp Git thật, hiểu sơ đồ tổng thể và chạy thử toàn bộ kịch bản trên checkout sạch. Người chủ trì không có nghĩa làm thay phần của hai service khác.

### 13.7. Bộ tài liệu/script và cách chạy buổi demo

Các tên file dưới đây là **công việc cần tạo**, không khẳng định hiện đã có/chạy được:

- `scripts/demo/seed-demo.ps1`: seed hoặc gọi API tạo dữ liệu demo, không dùng truy cập DB service khác từ ứng dụng.
- `scripts/demo/start-and-health.ps1`: khởi động và kiểm tra từng service.
- `scripts/demo/concurrent-bookings.ps1`: hai Passenger gửi đồng thời, thu response và kiểm tra invariants.
- `scripts/demo/fault-matching.ps1`, `scripts/demo/fault-trip-saga.ps1`: kích hoạt đúng hook, dừng/restart và thu log; hoàn trả cấu hình hook sau demo.
- `tests/demo/DS03.postman_collection.json` hoặc collection/công cụ tương đương: request lifecycle/payment/notification/security, biến tripId và token; không commit token thật.
- `docs/demo/DEMO_CHECKLIST.md`: thứ tự chạy, dữ liệu đầu vào, trạng thái mong đợi, ai thao tác/giải thích và cách reset an toàn dữ liệu demo.
- `docs/demo/evidence/`: log và kết quả test F01/F02, concurrency, dedup/DLQ; không chứa bí mật. Mỗi kết quả gắn phiên bản code/tag đã chạy.

**Chạy chính trong khoảng 7–10 phút (đề gợi ý):** D01/D02 → D03/D04/D05 → D06 → D07/D08/D09 bằng F01 hoặc F02. Chuẩn bị cả hai fault scenarios và các case bổ sung để chạy khi được hỏi. Cả ba người trực tiếp thao tác hoặc giải thích phần phụ trách; không để một người trình bày thay toàn nhóm.

**Kiểm thử nộp theo mục 9:** tối thiểu 8 functional + 2 concurrency/conflict + 2 fault/recovery + 2 security/invalid-data + 1 performance = 15. Lưu expected/actual, trạng thái cuối, test output và số liệu performance. Không cần chạy đủ 15 bài test trong 7–10 phút demo, nhưng phải thực hiện và có kết quả để đối chiếu.

**Điều kiện sẵn sàng demo:** không sửa dữ liệu thủ công để làm giả recovery; không diễn concurrency bằng hai request tuần tự; không coi restart được process là đã phục hồi nghiệp vụ; không gọi request/event được gửi là giao dịch đã thành công.

## 14. Phạm vi option và bàn giao bắt buộc

### Phần đề bắt buộc

REST/message queue, idempotency hoặc event ID, deduplication, retry, correlation ID, DLQ hoặc kho message lỗi, ít nhất một Saga/bù trừ, concurrency, hai kịch bản lỗi/phục hồi và bảo mật cơ bản. Chọn cách lưu Saga/event trong bảng là giải pháp nhóm, không phải đề bắt buộc tên bảng.

### Tính năng nâng cao tùy chọn – đã chọn

- Transactional Outbox: đề liệt kê ở 7.5 nâng cao, nhóm chọn để bảo vệ commit/publish. Có thể triển khai sau các chức năng bắt buộc nhưng không bỏ âm thầm vì contract hiện tại sử dụng outbox.
- Docker Compose: công cụ triển khai nhóm đã chọn; đề chỉ bắt buộc service độc lập và giao tiếp mạng, không bắt buộc Docker.
- CI: nhóm chọn để tự động build/khởi động/kiểm tra quyền DB; không được coi CI xanh thay cho test nghiệp vụ.

### Tính năng nâng cao tùy chọn – chưa chọn

Gateway, Eureka/Consul hoặc discovery registry, circuit breaker, distributed tracing, logging tập trung, multiple-instance scaling, dynamic pricing, geospatial matching, dashboard/frontend, tự tìm Driver mới sau reject, phí hủy, hoàn tiền một phần, khiếu nại sau chuyến, TLS triển khai. DNS/URL service, log/correlation ID và phát hiện lỗi cơ bản vẫn phải có dù không dùng công cụ nâng cao.

### Bàn giao 

Repository có README.md, src/, deploy/, tests/, scripts/, docs/, .env.example. deploy/ chứa cấu hình triển khai hoặc hướng dẫn tương ứng; tests/ có test và kịch bản; scripts/ có startup/workload/fault injection. Tài liệu hóa cách chạy lại từ đầu, kiến trúc, phân công và demo. Không xóa/đổi danh tính đóng góp của thành viên; mỗi người cần lịch sử commit chứng minh công việc thực tế.

Nộp docs/report.pdf; slides PDF nếu có. Báo cáo 20–30 trang là độ dài đề khuyến nghị, không phải số trang bắt buộc. Có tag/release cuối (ví dụ v1.0.0); repo private thì cấp quyền thầy. Trích dẫn nguồn thuật toán/code/hình và công cụ đã sử dụng; nhóm phải kiểm chứng, hiểu và chịu trách nhiệm cho sản phẩm nếu có AI hỗ trợ.

Hạn nộp báo cáo/source: 22/11/2026. Demo/vấn đáp: 23/11 và 30/11/2026 theo đề. Phần trình bày giới thiệu 3–5 phút, demo 7–10 phút, vấn đáp khoảng 5 phút là thời lượng gợi ý. Demo có ít nhất ba thành phần độc lập, giao tiếp mạng, concurrency, lỗi trực tiếp và log; cả ba thành viên tham gia.

## 15. Nguyên tắc chốt khi code

- CASH trả cuối chuyến; TRANSFER reserve Driver trước → chờ khách PAY → SUCCESS mới mời Driver accept/reject. Chọn phương thức lúc tạo chuyến.
- Không có giữ tiền hoặc thu khoản giữ sau chuyến. TRANSFER hoàn thành không thu lần nữa.
- Hủy do khách, Driver từ chối/rút khỏi hoặc không nhận quá hạn trước bắt đầu: release Driver; TRANSFER đã thu thì REFUND 100%, CASH chưa thu thì không refund. Không tìm lại sau Driver reject trong V1.
- Không hủy/start đồng thời thành công; không xử lý event đến muộn như thể chuyến vẫn đang đặt bình thường.
- Trip/Driver/Payment chỉ thay đổi dữ liệu mình sở hữu. Saga dùng lệnh REST/event để phối hợp.
- Tác vụ retry giữ khóa nghiệp vụ ổn định; consumer chống trùng và kiểm tra chuyển trạng thái.
- Lưu dữ liệu và event cùng transaction outbox; ACK sau khi tác vụ/kết quả cần thiết đã được lưu hoặc xác nhận an toàn.
- Không kết luận tiền thất bại chỉ vì timeout; không thông báo refund thành công trước xác nhận.
- Không kết thúc Saga nếu release/refund còn chưa được xác nhận; phải phục hồi được sau restart.
- Giá cố định, không phí hủy, không refund từng phần, không khiếu nại sau chuyến trong V1.

