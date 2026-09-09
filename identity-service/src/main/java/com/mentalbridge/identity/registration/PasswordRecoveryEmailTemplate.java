package com.mentalbridge.identity.registration;

import org.springframework.web.util.HtmlUtils;

final class PasswordRecoveryEmailTemplate {
	private static final String SUBJECT = "Đặt lại mật khẩu MentalBridge";

	private static final String HTML = """
			<!doctype html>
			<html lang="vi">
			<head>
			  <meta charset="utf-8">
			  <meta name="viewport" content="width=device-width, initial-scale=1">
			  <title>Đặt lại mật khẩu MentalBridge</title>
			</head>
			<body style="margin:0;padding:0;background-color:#F1F4EB;color:#1B2A22;">
			  <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">
			    Sử dụng liên kết bảo mật để đặt lại mật khẩu MentalBridge của bạn.
			  </div>
			  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="width:100%;background-color:#F1F4EB;">
			    <tr>
			      <td align="center" style="padding:36px 16px;">
			        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="width:100%;max-width:600px;overflow:hidden;border:1px solid #D9E2D6;border-radius:22px;background-color:#FFFFFF;box-shadow:0 20px 50px rgba(30,74,67,0.12);">
			          <tr>
			            <td style="padding:24px 32px;border-bottom:1px solid #D9E2D6;background-color:#E9EFE3;">
			              <table role="presentation" cellspacing="0" cellpadding="0" border="0">
			                <tr>
			                  <td width="44" height="44" align="center" valign="middle" style="width:44px;height:44px;border-radius:50%;background-color:#1E4A43;color:#FFFFFF;font-family:Georgia,serif;font-size:22px;font-weight:700;">M</td>
			                  <td style="padding-left:12px;font-family:Arial,'Helvetica Neue',sans-serif;">
			                    <div style="color:#1E4A43;font-family:Georgia,serif;font-size:21px;font-weight:700;line-height:1.2;">MentalBridge</div>
			                    <div style="padding-top:3px;color:#52604F;font-size:12px;line-height:1.4;">Cây cầu đến sự cân bằng</div>
			                  </td>
			                </tr>
			              </table>
			            </td>
			          </tr>
			          <tr>
			            <td style="padding:38px 32px 34px;font-family:Arial,'Helvetica Neue',sans-serif;">
			              <div style="margin-bottom:12px;color:#3D7A6E;font-size:12px;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;">Khôi phục tài khoản</div>
			              <h1 style="margin:0 0 16px;color:#1B2A22;font-family:Georgia,serif;font-size:30px;font-weight:500;line-height:1.25;">Đặt lại mật khẩu</h1>
			              <p style="margin:0 0 26px;color:#52604F;font-size:16px;line-height:1.7;">Chúng tôi đã nhận được yêu cầu đặt lại mật khẩu. Chọn nút bên dưới để tạo mật khẩu mới cho tài khoản MentalBridge của bạn.</p>
			              <table role="presentation" cellspacing="0" cellpadding="0" border="0" style="margin:0 0 26px;">
			                <tr>
			                  <td align="center" style="border-radius:999px;background-color:#1E4A43;">
			                    <a href="{{recoveryUrl}}" role="button" style="display:inline-block;padding:14px 28px;color:#FFFFFF;font-family:Arial,'Helvetica Neue',sans-serif;font-size:15px;font-weight:700;line-height:1;text-decoration:none;">Đặt lại mật khẩu</a>
			                  </td>
			                </tr>
			              </table>
			              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="width:100%;margin-bottom:24px;border-radius:12px;background-color:#F5E6C6;">
			                <tr>
			                  <td style="padding:14px 16px;color:#6D532B;font-size:13px;line-height:1.6;">
			                    <strong style="color:#1E4A43;">Liên kết có hiệu lực trong 15 phút.</strong><br>
			                    Liên kết chỉ dùng một lần. Nếu bạn không yêu cầu thay đổi, hãy bỏ qua email này.
			                  </td>
			                </tr>
			              </table>
			              <p style="margin:0 0 8px;color:#8A9585;font-size:12px;line-height:1.6;">Nếu nút phía trên không hoạt động, hãy sao chép liên kết này vào trình duyệt:</p>
			              <p style="margin:0;overflow-wrap:anywhere;word-break:break-all;font-size:12px;line-height:1.6;"><a href="{{recoveryUrl}}" style="color:#3D7A6E;text-decoration:underline;">{{recoveryUrl}}</a></p>
			            </td>
			          </tr>
			          <tr>
			            <td align="center" style="padding:20px 32px;border-top:1px solid #D9E2D6;background-color:#F8FAF5;color:#8A9585;font-family:Arial,'Helvetica Neue',sans-serif;font-size:12px;line-height:1.6;">
			              Email tự động từ MentalBridge. Vui lòng không trả lời email này.<br>
			              An toàn · Riêng tư · Đồng hành
			            </td>
			          </tr>
			        </table>
			      </td>
			    </tr>
			  </table>
			</body>
			</html>
			""";

	private PasswordRecoveryEmailTemplate() {
	}

	static VerificationEmailTemplate.Content create(String recoveryUrl) {
		var escapedUrl = HtmlUtils.htmlEscape(recoveryUrl);
		var html = HTML.replace("{{recoveryUrl}}", escapedUrl);
		var text = """
				Đặt lại mật khẩu MentalBridge

				Chúng tôi đã nhận được yêu cầu đặt lại mật khẩu. Sử dụng liên kết bảo mật dùng một lần dưới đây trong vòng 15 phút:
				%s

				Nếu bạn không yêu cầu thay đổi, hãy bỏ qua email này.

				MentalBridge - Cây cầu đến sự cân bằng
				""".formatted(recoveryUrl);
		return new VerificationEmailTemplate.Content(SUBJECT, html, text);
	}
}
