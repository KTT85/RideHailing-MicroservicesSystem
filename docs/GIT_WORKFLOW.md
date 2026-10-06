# Quy trình Git cho nhóm 3 người

`main` giữ bản ổn định để nộp/demo. `develop` tích hợp cả nhóm. Sáu nhánh feature tương ứng sáu service, mỗi thành viên phụ trách hai nhánh. Mỗi service gửi PR riêng và cập nhật develop thường xuyên; không đợi hết dự án mới thử tích hợp.

```text
main ← PR phát hành từ develop sau kiểm thử toàn hệ thống
          ↑
develop ← PR từ feature/user-service         (TV1)
        ← PR từ feature/payment-service      (TV1)
        ← PR từ feature/driver-service       (TV2)
        ← PR từ feature/matching-service     (TV2)
        ← PR từ feature/trip-service         (TV3)
        ← PR từ feature/notification-service (TV3)
```

## Khởi tạo trên GitHub (người quản lý repository)

Trong thư mục D:\HTPT, tạo commit nền và các nhánh local trước (chỉ chạy một lần khi chưa có commit/nhánh):

```powershell
git add .
git commit -m "chore: scaffold six microservices and team workflow"
git branch develop
git branch feature/user-service
git branch feature/payment-service
git branch feature/driver-service
git branch feature/matching-service
git branch feature/trip-service
git branch feature/notification-service
```

Sau khi skeleton đã commit và các nhánh local đã tạo:

```powershell
git push -u origin main
git push -u origin develop
git push -u origin feature/user-service
git push -u origin feature/payment-service
git push -u origin feature/driver-service
git push -u origin feature/matching-service
git push -u origin feature/trip-service
git push -u origin feature/notification-service
```

Trong GitHub Settings → Rules/Branches, đặt quy tắc cho main và develop: yêu cầu Pull Request, ít nhất 1 người khác review, checks `build` và `compose` thành công, giải quyết hội thoại, cấm force push/xóa nhánh. Chọn checks sau khi workflow chạy lần đầu. Quy tắc này phải được cấu hình trên GitHub; file local không tự bật bảo vệ.

## Bắt đầu (ví dụ TV2)

```powershell
git clone https://github.com/KTT85/RideHailing-MicroservicesSystem.git
cd RideHailing-MicroservicesSystem
git switch feature/driver-service
```

## Cập nhật trước khi code và trước khi gửi PR

Working tree phải sạch; commit phần đang làm trước khi merge.

```powershell
git fetch origin
git merge origin/develop
```

Nếu conflict: đọc cả hai phía, trao đổi người sở hữu file, sửa file rồi `git add <file>` và `git commit`. Có thể `git merge --abort` để quay về trước lần merge. Không dùng reset --hard để giải quyết conflict.

## Hoàn thành một phần nhỏ

```powershell
mvn -B -f src/driver-service/pom.xml verify
git add src/driver-service
git commit -m "feat(driver): add atomic reservation"
git push -u origin feature/driver-service
```

Mở PR: base `develop`, compare nhánh service. Ghi rõ API/event thay đổi, cách test, phần chưa xong; người khác review và CI pass mới merge. Với các nhánh feature dài hạn này, chọn **Create a merge commit**, không squash/rebase merge, không xóa nhánh sau PR; sau merge fetch và merge origin/develop vào nhánh rồi tiếp tục. Nhóm cũng có thể tạo nhánh nhỏ từ develop cho từng tính năng và xóa nhánh nhỏ sau PR.

## Chuyển giữa hai service được giao

Ví dụ TV2 đang làm Driver rồi chuyển sang Matching: commit công việc Driver trước khi switch để thay đổi chưa commit không đi theo sang nhánh khác.

```powershell
git switch feature/matching-service
git fetch origin
git merge origin/develop
```

Nếu Matching cần API Driver mới, merge PR Driver vào develop trước rồi cập nhật nhánh Matching. Mỗi PR ưu tiên chỉ chứa service tương ứng và test/tài liệu liên quan. Thay đổi hạ tầng dùng chung phải trao đổi với nhóm và có PR riêng khi phù hợp.

## Tránh ảnh hưởng nhau

- Mỗi người ưu tiên sửa 2 service được giao và test tương ứng.
- pom.xml root, docker-compose.yml, CI và cấu hình chung là phần dùng chung: trao đổi cả nhóm và có review khi sửa.
- Không copy source service khác vào service mình; dùng REST/event theo contract.
- API/event phải thống nhất trước khi code; đổi contract cần liệt kê service bị ảnh hưởng.
- Không push thẳng main/develop, không commit .env/password thật, không force push nhánh dùng chung.
- Branch chỉ cách ly thay đổi trước merge; review, CI và test tích hợp mới giúp tránh hỏng bản chung.

## Đưa về main

Sau khi toàn bộ phần việc đã tích hợp develop và kiểm tra đủ happy path, OCC, Saga compensation, recovery, duplicate và DLQ: mở PR develop → main, review, CI pass và test demo rồi merge. Tag v1.0.0 sau khi nhóm chốt bản nộp.
