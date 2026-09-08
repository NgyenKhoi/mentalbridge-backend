package com.mentalbridge.identity.registration;

import org.springframework.web.util.HtmlUtils;

final class PasswordRecoveryEmailTemplate {

	private PasswordRecoveryEmailTemplate() {
	}

	static VerificationEmailTemplate.Content create(String recoveryUrl) {
		var escapedUrl = HtmlUtils.htmlEscape(recoveryUrl);
		var html = """
				<!doctype html>
				<html lang="vi">
				<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
				<body style="font-family:Arial,sans-serif;color:#1B2A22;background:#F1F4EB;padding:32px">
				  <main style="max-width:600px;margin:auto;background:#FFFFFF;padding:32px;border-radius:18px">
				    <h1 style="font-family:Georgia,serif">Đặt lại mật khẩu MentalBridge</h1>
				    <p>Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.</p>
				    <p><a href="{{recoveryUrl}}" style="display:inline-block;padding:14px 24px;border-radius:999px;background:#1E4A43;color:#FFFFFF;text-decoration:none">Đặt lại mật khẩu</a></p>
				    <p>Liên kết bảo mật này chỉ dùng một lần và hết hạn sau 15 phút. Nếu bạn không yêu cầu thay đổi, hãy bỏ qua email này.</p>
				  </main>
				</body>
				</html>
				""".replace("{{recoveryUrl}}", escapedUrl);
		var text = """
				Đặt lại mật khẩu MentalBridge

				Sử dụng liên kết bảo mật dùng một lần dưới đây trong vòng 15 phút:
				%s

				Nếu bạn không yêu cầu thay đổi, hãy bỏ qua email này.
				""".formatted(recoveryUrl);
		return new VerificationEmailTemplate.Content("Đặt lại mật khẩu MentalBridge", html, text);
	}
}
