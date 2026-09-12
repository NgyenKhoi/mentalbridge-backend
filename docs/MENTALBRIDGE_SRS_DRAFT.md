# MentalBridge historical BR/MSG proposal

> **Historical proposal — not an active source of truth.** This draft predates
> the active Identity Story 902 challenge-link password-recovery contract. Its
> six-digit OTP and 60-second resend proposals are not current runtime
> requirements. Current behavior is governed by approved policies, versioned
> OpenAPI contracts, executable migrations, and owning-service implementation.
> This file remains only to preserve earlier analysis.

## Business Rules

| ID | Rule Definition |
|---|---|
| BR-01 | Email tài khoản phải duy nhất và được chuẩn hóa trước khi so sánh. |
| BR-02 | Mật khẩu phải theo chính sách được phê duyệt; chỉ lưu password hash mạnh. |
| BR-03 | Guest phải đồng ý Điều khoản và Chính sách bảo mật trước khi đăng ký. |
| BR-04 | Tài khoản bị khóa/vô hiệu hóa không được đăng nhập hoặc refresh session. |
| BR-05 | Specialist chỉ hoạt động công khai sau khi hồ sơ được Admin duyệt. |
| BR-06 | Assessment chỉ là công cụ sàng lọc; không hiển thị như chẩn đoán. |
| BR-07 | PHQ-9 gồm 9 câu, mỗi câu 0–3; kết quả hoàn thành không được sửa. |
| BR-08 | Tín hiệu tự làm hại phải kích hoạt quy trình an toàn được phê duyệt, không phụ thuộc duy nhất tổng điểm. |
| BR-09 | Journal mặc định riêng tư. Chia sẻ phải dựa trên consent cụ thể. |
| BR-10 | Consent phải xác định specialist, scope, mục đích, thời hạn và có thể thu hồi. |
| BR-11 | Specialist không được truy cập dữ liệu ngoài scope consent hiện hành. |
| BR-12 | Một availability slot không được gắn với hai appointment đang giữ/chấp nhận. |
| BR-13 | Appointment chỉ được xác nhận sau khi đáp ứng điều kiện slot và payment/credit tương ứng. |
| BR-14 | Chỉ các bên của appointment/conversation mới được xem tin nhắn. |
| BR-15 | Tin nhắn rỗng không được gửi; file phải được kiểm tra loại, kích thước và mã độc. |
| BR-16 | Entitlement gói chỉ cập nhật sau callback thanh toán hợp lệ và idempotent. |
| BR-17 | Admin không được chỉnh sửa transaction gốc; mọi refund/điều chỉnh phải tạo bản ghi riêng. |
| BR-18 | Payout chỉ tính từ phiên đủ điều kiện và phải có kỳ đối soát. |
| BR-19 | Hành động admin nhạy cảm phải ghi actor, thời gian, mục tiêu, lý do và kết quả. |
| BR-20 | Dữ liệu sức khỏe không được hiển thị trong báo cáo tổng hợp nếu có nguy cơ tái định danh. |
| BR-21 | Hotline/nguồn hỗ trợ phải được xác minh chủ sở hữu, phạm vi phục vụ và thời gian hoạt động trước khi xuất bản. |
| BR-22 | Xóa tài khoản không được xóa trái quy định các chứng từ cần lưu; phần giữ lại phải tối thiểu và có căn cứ. |
| BR-23 | Dữ liệu mock không được xuất hiện trong môi trường production như dữ liệu thật. |
| BR-24 | Các quyền Free/Plus/Premium phải được kiểm tra ở server, không dựa vào `localStorage`. |
| BR-25 | Vai trò và quyền hiệu lực phải lấy từ phân quyền đáng tin cậy phía server; giá trị role do trình duyệt gửi không được dùng để nâng quyền. |
| BR-26 | Phản hồi đăng nhập thất bại không được tiết lộ email/tài khoản có tồn tại hay không. |
| BR-27 | Các lần thử xác thực thất bại, gồm đăng nhập, OTP và kiểm tra mật khẩu hiện tại, phải bị giới hạn tần suất và được ghi nhận phục vụ giám sát bảo mật. |
| BR-28 | Session xác thực phải an toàn, có thời hạn, có thể thu hồi và phải bị vô hiệu hóa phía server khi người dùng đăng xuất. |
| BR-29 | Google OAuth chỉ được liên kết bằng danh tính đã được nhà cung cấp xác minh; toàn bộ OAuth claim bắt buộc phải được kiểm tra trước khi tạo session. |
| BR-30 | Nếu tài khoản hoặc vai trò yêu cầu 2FA, người dùng phải hoàn thành 2FA trước khi được cấp quyền truy cập xác thực. |
| BR-31 | Workspace Specialist và Admin phải được bảo vệ bằng authorization phía server trên cả route và API, không chỉ bằng điều hướng frontend. |
| BR-32 | Sign Out phải có tính idempotent: dù session đã hết hạn hoặc không tồn tại, kết quả cuối vẫn là trạng thái chưa xác thực và không làm lộ chi tiết session. |
| BR-33 | Change Password chỉ được thực hiện khi mật khẩu hiện tại khớp với password hash đang lưu của tài khoản. |
| BR-34 | Mật khẩu mới phải khác mật khẩu hiện tại và đồng thời đáp ứng chính sách mật khẩu của BR-02. |
| BR-35 | Sau khi đổi hoặc đặt lại mật khẩu thành công, password hash phải được cập nhật nguyên tử; các session hiện có phải bị thu hồi hoặc xử lý theo chính sách session đã phê duyệt. |
| BR-36 | Phản hồi yêu cầu Forgot Password phải giống nhau dù email có tồn tại hay không để ngăn dò tìm tài khoản. |
| BR-37 | Mã khôi phục phải gồm 6 chữ số, có thời hạn, chỉ dùng một lần, gắn với đúng yêu cầu/tài khoản và bị giới hạn số lần xác minh. |
| BR-38 | Chỉ được gửi lại mã sau thời gian chờ 60 giây; mã mới được phát hành phải vô hiệu hóa mã khôi phục còn hiệu lực trước đó của cùng yêu cầu. |
| BR-39 | Reset Password chỉ được thực hiện bằng reset authorization hợp lệ, chưa hết hạn, dùng một lần và được cấp sau khi xác minh mã khôi phục thành công; authorization phải bị tiêu thụ sau khi reset. |
| BR-40 | Chỉ người dùng đã xác thực mới được xem hồ sơ đầy đủ của chính mình; định danh hồ sơ phải được xác định từ session đáng tin cậy phía server, không từ `userId` do client tự khai báo. |
| BR-41 | Mỗi assessment phải sử dụng một phiên bản questionnaire đang được server phê duyệt; server phải kiểm tra tính đầy đủ và miền giá trị của đáp án, tự tính điểm theo scoring version tương ứng, và không chấp nhận điểm do client tính làm dữ liệu có thẩm quyền. |
| BR-42 | Chỉ User đã xác thực mới được xem kết quả assessment đầy đủ của chính mình; assessment result ID chỉ là mã chọn tài nguyên và quyền sở hữu phải được kiểm tra bằng định danh User từ session đáng tin cậy phía server. |
| BR-43 | So sánh điểm assessment chỉ được thực hiện giữa các kết quả của cùng User, cùng instrument và các questionnaire/scoring version tương thích; khi không có kết quả trước phù hợp phải hiển thị không có dữ liệu so sánh, không được coi là chênh lệch bằng 0. |
| BR-44 | Chỉ User đã xác thực mới được xem lịch sử assessment của chính mình; phạm vi truy vấn lịch sử phải được xác định bằng User ID từ session đáng tin cậy phía server và không được dựa trên `userId` do client tự khai báo. |
| BR-45 | Hội thoại tư vấn với Specialist phải gắn duy nhất với appointment `IN_APP_CHAT` đã được xác nhận; chỉ các participant được server xác thực mới được join và nhắn tin trong khoảng thời gian appointment có thẩm quyền, còn lịch sử sau phiên chỉ được xem nếu chính sách lưu giữ cho phép. |
| BR-46 | `IN_APP_VIDEO` phải bị vô hiệu hóa cho đến khi call/signalling/provider/security contract và ADR liên quan được phê duyệt; sau khi được bật, chỉ participant được server xác thực của appointment video đã xác nhận mới được yêu cầu quyền vào phòng trong khoảng thời gian được phép. |

## Application Messages

| Code | Loại | Ngữ cảnh | Nội dung đề xuất |
|---|---|---|---|
| MSG-001 | Inline | Thiếu trường | Vui lòng điền đầy đủ các trường bắt buộc. |
| MSG-002 | Inline | Email sai | Địa chỉ email không hợp lệ. |
| MSG-003 | Inline | Email trùng | Email này đã được sử dụng. |
| MSG-004 | Inline | Mật khẩu yếu | Mật khẩu chưa đáp ứng yêu cầu bảo mật. |
| MSG-005 | Inline | Xác nhận mật khẩu | Mật khẩu xác nhận không khớp. |
| MSG-006 | Inline | Chưa đồng ý chính sách | Bạn cần đồng ý Điều khoản và Chính sách bảo mật. |
| MSG-007 | Inline | Đăng nhập sai | Email hoặc mật khẩu không chính xác. |
| MSG-008 | Inline | Tài khoản khóa | Tài khoản đang bị hạn chế. Vui lòng liên hệ hỗ trợ. |
| MSG-009 | Toast | Đăng nhập | Đăng nhập thành công. |
| MSG-010 | Toast/step notice | Forgot Password | Nếu email tồn tại, mã xác nhận sẽ được gửi. |
| MSG-011 | Inline | OTP | Mã xác minh không hợp lệ hoặc đã hết hạn. |
| MSG-012 | Inline | Assessment thiếu đáp án | Vui lòng trả lời câu hỏi hiện tại. |
| MSG-013 | Toast | Lưu journal | Nhật ký đã được lưu. |
| MSG-014 | Inline | Journal thiếu mood | Hãy chọn cảm xúc phù hợp nhất. |
| MSG-015 | Inline | Journal thiếu nội dung | Hãy viết một vài dòng trước khi lưu. |
| MSG-016 | Empty state | Chuyên gia | Không tìm thấy chuyên gia phù hợp với bộ lọc. |
| MSG-017 | Inline | Slot hết chỗ | Khung giờ này vừa được đặt. Vui lòng chọn thời gian khác. |
| MSG-018 | Toast | Đặt lịch | Yêu cầu đặt lịch đã được gửi. |
| MSG-019 | Toast | Hủy/đổi lịch | Yêu cầu của bạn đã được ghi nhận. |
| MSG-020 | Inline | Tin nhắn rỗng | Tin nhắn không được để trống. |
| MSG-021 | Inline | Mất kết nối chat | Chưa thể gửi tin. Chúng tôi sẽ thử lại khi có kết nối. |
| MSG-022 | Toast | Thanh toán | Thanh toán thành công. Gói đã được cập nhật. |
| MSG-023 | Inline | Thanh toán lỗi | Thanh toán chưa thành công. Vui lòng thử lại hoặc chọn phương thức khác. |
| MSG-024 | Toast | Consent | Quyền chia sẻ dữ liệu đã được cập nhật. |
| MSG-025 | Confirm | Thu hồi consent | Thu hồi quyền truy cập của chuyên gia này? |
| MSG-026 | Confirm | Xóa dữ liệu | Gửi yêu cầu xóa dữ liệu và tài khoản? |
| MSG-027 | Toast | Tạo lịch trống | Lịch trống mới đã được tạo. |
| MSG-028 | Inline | Thời gian không hợp lệ | Giờ kết thúc phải sau giờ bắt đầu. |
| MSG-029 | Toast | Admin action | Thao tác quản trị đã được cập nhật. |
| MSG-030 | Generic | Lỗi hệ thống | Đã có lỗi xảy ra. Vui lòng thử lại sau. |
| MSG-031 | Toast/redirect notice | Đăng xuất | Bạn đã đăng xuất an toàn. |
| MSG-032 | Inline/banner | Mật khẩu hiện tại sai | Mật khẩu hiện tại không đúng. Vui lòng thử lại. |
| MSG-033 | Inline | Mật khẩu mới trùng mật khẩu hiện tại | Mật khẩu mới phải khác mật khẩu hiện tại. |
| MSG-034 | Success state | Đổi hoặc đặt lại mật khẩu | Mật khẩu đã được cập nhật thành công. |
| MSG-035 | Inline/banner | Gửi hoặc gửi lại mã thất bại | Không thể gửi mã xác nhận. Vui lòng thử lại. |
| MSG-036 | Inline/redirect notice | Reset authorization không hợp lệ | Phiên đặt lại mật khẩu không hợp lệ hoặc đã hết hạn. Vui lòng yêu cầu mã xác nhận mới. |
| MSG-037 | Inline/redirect notice | Assessment definition đã thay đổi hoặc hết hiệu lực | Bài đánh giá đã được cập nhật. Vui lòng bắt đầu lại. |
| MSG-038 | Empty state | Lịch sử assessment trống | Bạn chưa có kết quả đánh giá nào. |
| MSG-039 | Inline/banner | Chat chưa hoặc không còn khả dụng | Bạn chỉ có thể nhắn tin trong thời gian của lịch hẹn tư vấn đã xác nhận. |
| MSG-040 | Inline/banner | Video call chưa được bật | Tính năng gọi video hiện chưa khả dụng. Vui lòng sử dụng kênh tư vấn được hỗ trợ. |
| MSG-041 | Inline | Thiết bị gọi video chưa sẵn sàng | Không thể truy cập camera hoặc micro. Vui lòng kiểm tra thiết bị và quyền truy cập. |
| MSG-042 | Inline/banner | Ngoài thời gian vào phòng video | Bạn chỉ có thể vào phòng trong thời gian của lịch hẹn video đã xác nhận. |
| MSG-043 | Inline | Kết nối video chưa sẵn sàng | Kết nối mạng chưa ổn định. Vui lòng kiểm tra lại trước khi vào phòng. |
