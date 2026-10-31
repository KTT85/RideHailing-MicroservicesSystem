# Bằng chứng kiểm thử Payment — 16/10/2026

Nguồn: ảnh và kết quả người dùng cung cấp trong phiên test local. Ảnh được sao
chép nguyên bản; không sửa nội dung kết quả. Các thay đổi code đang ở working tree,
chưa gắn bằng chứng với một commit bàn giao. Chưa test tích hợp Trip/Driver/Notification.

## Kết quả có ảnh

| Ca | Bằng chứng | Điều ảnh xác nhận |
|---|---|---|
| PAY/REFUND/CASH | screenshots/01-mysql-success-operations.png | Một PAY và REFUND thành công của chuyến TRANSFER; một CONFIRM_CASH thành công của chuyến CASH |
| Message lỗi vào DLQ | screenshots/02-dlq-rejected.png | RabbitMQ ghi x-death, rejected từ payment.commands vào payment.dlq |
| PAY bị từ chối | screenshots/03-pay-declined.png | FAILED, paidAt null, một PAY FAILED |
| Retry lỗi tạm thời | screenshots/04-retry-pending.png, 05-retry-success.png | Cùng payment/operation từ PENDING attempt=2 đến SUCCESS attempt=3 |
| Restart tác vụ | screenshots/06-restart-final-state.png | Trạng thái cuối SUCCESS, một PAY attempt=2; việc dừng/restart do người dùng thực hiện theo hướng dẫn, ảnh riêng không chứng minh thời điểm dừng |
| Queue giữ lệnh | screenshots/07-queue-ready-consumer-off.png, 08-queued-command-pending.png | Khi tắt: Ready=1, Consumers=0, Persistent=1; sau bật: payment đã được tạo và chờ retry. Kết quả cuối người dùng báo đã đạt, chưa có ảnh cuối riêng |
| PAY hết retry | screenshots/09-pay-manual-review.png | PENDING, chưa thu tiền; PAY MANUAL_REVIEW, attempt=3 |
| REFUND hết retry | screenshots/10-refund-manual-review.png | REFUND_FAILED, refundedAmount=0; PAY SUCCESS, REFUND MANUAL_REVIEW attempt=3 |
| Outbox phục hồi route | screenshots/11-outbox-pending.png, 12-outbox-published.png | Sau mất route: payment SUCCESS/outbox PENDING; sau khôi phục: PUBLISHED attempt=3, không còn lỗi |

Không suy ra các bằng chứng chưa được chụp: eventId giữ nguyên, tổng tiền chính xác
trên toàn DB, hoặc hai luồng thực sự đồng thời chỉ từ một ảnh kết quả cuối.

## Hiệu năng và đồng thời

- `performance-results.json`: số liệu chép từ hai ảnh terminal của người dùng.
  10/10 và 100/100 hoàn thành; lần 100 đạt 30.81 payment/s, publish p95=15.82 ms.
  Script đo cả publish/xử lý/polling; không phải công suất tối đa hay tải toàn hệ thống.
- Ảnh terminal hiệu năng không sao chép vào repo vì có hiển thị service credential
  local. JSON chỉ giữ số liệu, không giữ token/mật khẩu.
- `automated-test-summary.json`: trích số lượng test và kết quả từ report đang có,
  không sao chép environment/system properties. Có 2 test đạt, không lỗi, dùng H2
  theo cấu hình mặc định; chưa có bằng chứng chạy concurrency trên MySQL thật.

## Chờ bổ sung / tích hợp

- Hai ảnh/response bảo mật: thiếu JWT -> 401, sai Passenger -> 403. Người dùng nói
  đã xong bảo mật nhưng chưa cung cấp ảnh cụ thể cho bộ bằng chứng này.
- Log vòng đời process và ảnh cuối cho từng ca restart nếu cần nộp bằng chứng đầy đủ.
- Concurrency trên database MySQL test riêng.
- Saga hủy/release/refund, late events và quyền phát command ở Trip; thông báo.
- Kịch bản hai khách tranh Driver và demo lỗi toàn hệ thống theo DS03.

Chưa gửi bộ này cho thành viên nào. Có thể dùng `../../CONTRACT.md` để thống nhất
tên event/payload trước khi các thành viên viết service; không cần chờ họ code xong.
