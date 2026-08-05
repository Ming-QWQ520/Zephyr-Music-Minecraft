package com.zephyr.music.client.gui.screen;

import com.google.gson.JsonObject;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.NeteaseApi;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.api.NeteaseUser;
import com.zephyr.music.net.NeteaseHttpClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

/**
 * 网易云登录界面 - 支持扫码、手机验证码、邮箱三种方式
 * 登录成功后自动跳转到歌单浏览器
 */
public class LoginScreen extends Screen
{
    private enum LoginMode { QR, PHONE, EMAIL }

    private LoginMode mode = LoginMode.QR;
    private String statusMessage = "正在生成二维码…";
    private int statusColor = 0xFFFFFFFF;

    // QR 模式
    private String qrKey;
    private BufferedImage qrImage;
    private Timer qrTimer;
    private int qrPollCount = 0;

    // 手机/邮箱
    private EditBox phoneField;
    private EditBox passwordField;
    private EditBox captchaField;
    private EditBox countryCodeField;
    private EditBox emailField;
    private EditBox emailPasswordField;

    private Button sendCaptchaBtn;
    private Button loginBtn;
    private Button regenerateQrBtn;
    private long lastCaptchaSent = 0;

    public LoginScreen()
    {
        super(Component.literal("Zephyr Music · 网易云登录"));
    }

    @Override
    protected void init()
    {
        int cx = this.width / 2;
        int topY = Math.max(30, (this.height - 320) / 2);

        // 顶部标签切换按钮
        addRenderableWidget(Button.builder(Component.literal("扫码登录"), b -> switchMode(LoginMode.QR))
                .bounds(cx - 165, topY, 100, 18).build());
        addRenderableWidget(Button.builder(Component.literal("手机登录"), b -> switchMode(LoginMode.PHONE))
                .bounds(cx - 55, topY, 100, 18).build());
        addRenderableWidget(Button.builder(Component.literal("邮箱登录"), b -> switchMode(LoginMode.EMAIL))
                .bounds(cx + 55, topY, 100, 18).build());

        // QR 模式：重新生成按钮（初始化时不可见）
        regenerateQrBtn = Button.builder(Component.literal("重新生成二维码"), b -> startQrLogin())
                .bounds(cx - 75, topY + 220, 150, 18).build();

        // 表单字段（不同模式共用）
        int fy = topY + 40;
        countryCodeField = new EditBox(this.font, cx - 40, fy, 50, 18, Component.literal("86"));
        countryCodeField.setValue("86");
        countryCodeField.setMaxLength(5);
        phoneField = new EditBox(this.font, cx + 15, fy, 180, 18, Component.literal("手机号"));
        phoneField.setMaxLength(20);
        phoneField.setHint(Component.literal("手机号"));

        passwordField = new EditBox(this.font, cx - 80, fy + 30, 180, 18, Component.literal("密码（可选）"));
        passwordField.setMaxLength(64);
        passwordField.setHint(Component.literal("密码（可选，二选一）"));
        passwordField.setFormatter((s, i) -> net.minecraft.util.FormattedCharSequence.forward(
                "*".repeat(s.length()), net.minecraft.network.chat.Style.EMPTY));

        captchaField = new EditBox(this.font, cx - 80, fy + 60, 120, 18, Component.literal("验证码"));
        captchaField.setMaxLength(8);
        captchaField.setHint(Component.literal("验证码"));

        sendCaptchaBtn = Button.builder(Component.literal("发送验证码"), b -> sendCaptcha())
                .bounds(cx + 45, fy + 58, 80, 20).build();
        sendCaptchaBtn.active = false;

        emailField = new EditBox(this.font, cx - 80, fy, 180, 18, Component.literal("邮箱"));
        emailField.setMaxLength(64);
        emailField.setHint(Component.literal("邮箱地址"));

        emailPasswordField = new EditBox(this.font, cx - 80, fy + 30, 180, 18, Component.literal("密码"));
        emailPasswordField.setMaxLength(64);
        emailPasswordField.setHint(Component.literal("密码"));
        emailPasswordField.setFormatter((s, i) -> net.minecraft.util.FormattedCharSequence.forward(
                "*".repeat(s.length()), net.minecraft.network.chat.Style.EMPTY));

        loginBtn = Button.builder(Component.literal("登 录"), b -> doLogin())
                .bounds(cx - 60, fy + 100, 120, 20).build();

        addRenderableWidget(Button.builder(Component.literal("⚙"), b -> minecraft.setScreen(new SettingsScreen()))
                .bounds(this.width - 116, 6, 18, 16).build());

        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose())
                .bounds(this.width - 22, 6, 14, 16).build());

        applyMode();
        // 进入 QR 模式时立即生成
        if (mode == LoginMode.QR && qrKey == null)
        {
            startQrLogin();
        }
    }

    private void applyMode()
    {
        removeWidget(countryCodeField);
        removeWidget(phoneField);
        removeWidget(passwordField);
        removeWidget(captchaField);
        removeWidget(sendCaptchaBtn);
        removeWidget(emailField);
        removeWidget(emailPasswordField);
        removeWidget(loginBtn);
        removeWidget(regenerateQrBtn);

        if (mode == LoginMode.PHONE)
        {
            addRenderableWidget(countryCodeField);
            addRenderableWidget(phoneField);
            addRenderableWidget(passwordField);
            addRenderableWidget(captchaField);
            addRenderableWidget(sendCaptchaBtn);
            addRenderableWidget(loginBtn);
        }
        else if (mode == LoginMode.EMAIL)
        {
            addRenderableWidget(emailField);
            addRenderableWidget(emailPasswordField);
            addRenderableWidget(loginBtn);
        }
        else // QR
        {
            addRenderableWidget(regenerateQrBtn);
        }
    }

    private void switchMode(LoginMode newMode)
    {
        if (this.mode == newMode) return;
        if (qrTimer != null)
        {
            qrTimer.cancel();
            qrTimer = null;
        }
        this.mode = newMode;
        this.qrImage = null;
        this.qrKey = null;
        applyMode();
        if (newMode == LoginMode.QR)
        {
            startQrLogin();
        }
        else
        {
            statusMessage = "请填写信息并点击登录";
            statusColor = 0xFFFFFFFF;
        }
    }

    private void startQrLogin()
    {
        statusMessage = "正在生成二维码…";
        statusColor = 0xFFFFFFFF;
        qrImage = null;
        qrKey = null;
        NeteaseHttpClient.getInstance().clearCookie();
        NeteaseApi api = NeteaseSession.getInstance().getApi();
        api.qrKey().thenCompose(resp -> {
            String key = null;
            if (resp.has("data") && resp.getAsJsonObject("data").has("unikey")
                    && !resp.getAsJsonObject("data").get("unikey").isJsonNull())
            {
                key = resp.getAsJsonObject("data").get("unikey").getAsString();
            }
            else if (resp.has("unikey") && !resp.get("unikey").isJsonNull())
            {
                key = resp.get("unikey").getAsString();
            }
            if (key == null || key.isEmpty())
            {
                ZephyrMusic.LOGGER.warn("[Zephyr] qrKey response: {}", resp);
                statusMessage = "无法获取二维码 key";
                statusColor = 0xFFFF5555;
                return CompletableFuture.completedFuture(new JsonObject());
            }
            qrKey = key;
            ZephyrMusic.LOGGER.info("[Zephyr] QR key: {}", qrKey);
            return api.qrCreate(qrKey);
        }).thenAccept(resp -> {
            ZephyrMusic.LOGGER.info("[Zephyr] qrCreate response keys: {}", resp.keySet());
            String qrimg = "";
            if (resp.has("data") && resp.getAsJsonObject("data").has("qrimg")
                    && !resp.getAsJsonObject("data").get("qrimg").isJsonNull())
            {
                qrimg = resp.getAsJsonObject("data").get("qrimg").getAsString();
            }
            else if (resp.has("qrimg") && !resp.get("qrimg").isJsonNull())
            {
                qrimg = resp.get("qrimg").getAsString();
            }
            // 也尝试 qrcode 字段
            if (qrimg.isEmpty() && resp.has("qrcode") && !resp.get("qrcode").isJsonNull())
            {
                qrimg = resp.get("qrcode").getAsString();
                // qrcode 通常是 URL，不是 base64
                ZephyrMusic.LOGGER.info("[Zephyr] qrcode URL: {}", qrimg);
            }
            // 去掉 data:image/png;base64, 前缀
            if (qrimg.startsWith("data:image"))
            {
                int comma = qrimg.indexOf(",");
                if (comma > 0)
                {
                    qrimg = qrimg.substring(comma + 1);
                }
            }
            // 检查是否真的是 base64
            if (!qrimg.isEmpty())
            {
                try
                {
                    byte[] bytes = Base64.getDecoder().decode(qrimg);
                    ZephyrMusic.LOGGER.info("[Zephyr] Decoded QR image: {} bytes", bytes.length);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (img != null)
                    {
                        qrImage = img;
                        ZephyrMusic.LOGGER.info("[Zephyr] QR image loaded: {}x{}", img.getWidth(), img.getHeight());
                        statusMessage = "请使用网易云 App 扫码登录";
                        statusColor = 0xFF1DB954;
                        startQrPolling();
                    }
                    else
                    {
                        statusMessage = "二维码图像解析失败";
                        statusColor = 0xFFFF5555;
                        ZephyrMusic.LOGGER.error("[Zephyr] ImageIO.read returned null");
                    }
                }
                catch (Exception e)
                {
                    statusMessage = "二维码解析失败: " + e.getMessage();
                    statusColor = 0xFFFF5555;
                    ZephyrMusic.LOGGER.error("[Zephyr] decode QR image failed", e);
                }
            }
            else
            {
                statusMessage = "API 未返回二维码图像";
                statusColor = 0xFFFF5555;
                ZephyrMusic.LOGGER.warn("[Zephyr] qrimg is empty, full resp: {}", resp);
            }
        }).exceptionally(e -> {
            ZephyrMusic.LOGGER.error("[Zephyr] startQrLogin failed", e);
            statusMessage = "二维码生成失败: " + e.getMessage();
            statusColor = 0xFFFF5555;
            return null;
        });
    }

    private void startQrPolling()
    {
        if (qrTimer != null) qrTimer.cancel();
        qrTimer = new Timer("ZephyrMusic-QRPoll", true);
        qrPollCount = 0;
        qrTimer.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                if (qrKey == null || qrTimer == null) return;
                qrPollCount++;
                if (qrPollCount > 60)
                {
                    qrTimer.cancel();
                    qrTimer = null;
                    statusMessage = "二维码已过期，请重新生成";
                    statusColor = 0xFFFF5555;
                    qrImage = null;
                    return;
                }
                NeteaseSession.getInstance().getApi().qrCheck(qrKey).thenAccept(resp -> {
                    int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                    String message = resp.has("message") && !resp.get("message").isJsonNull()
                            ? resp.get("message").getAsString() : "";
                    ZephyrMusic.LOGGER.info("[Zephyr] QR check code={}, msg={}", code, message);
                    if (code == 800)
                    {
                        statusMessage = "二维码已过期，请重新生成";
                        statusColor = 0xFFFF5555;
                        if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; }
                        qrImage = null;
                    }
                    else if (code == 801)
                    {
                        statusMessage = "等待扫码…";
                        statusColor = 0xFFFFFFFF;
                    }
                    else if (code == 802)
                    {
                        statusMessage = "待确认: " + message;
                        statusColor = 0xFFFFFF55;
                    }
                    else if (code == 803)
                    {
                        // ★ 先立即取消 timer，防止再次轮询返回 800 误判失败
                        if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; }

                        statusMessage = "登录成功！正在跳转…";
                        statusColor = 0xFF1DB954;
                        String cookie = NeteaseApi.extractCookie(resp);
                        ZephyrMusic.LOGGER.info("[Zephyr] QR 803 received, cookie length: {}",
                                cookie == null ? 0 : cookie.length());
                        if (cookie != null && !cookie.isEmpty())
                        {
                            NeteaseHttpClient.getInstance().setCookie(cookie);
                            ZephyrMusic.LOGGER.info("[Zephyr] Cookie saved via QR login");
                            // ★ 直接跳转，不等待 refreshUser（避免 profile=null 导致闪烁"登录失败"）
                            // refreshUser 在后台异步执行，获取到真实昵称后自动更新
                            NeteaseSession.getInstance().refreshUser();
                            Minecraft.getInstance().execute(() -> {
                                Minecraft.getInstance().setScreen(new PlaylistBrowserScreen());
                            });
                        }
                        else
                        {
                            ZephyrMusic.LOGGER.error("[Zephyr] QR 803 but no cookie in response: {}", resp);
                            statusMessage = "登录响应缺少 cookie，请重试";
                            statusColor = 0xFFFF5555;
                        }
                    }
                });
            }
        }, 1000, 1500);
    }

    private void sendCaptcha()
    {
        String phone = phoneField.getValue().trim();
        if (phone.isEmpty())
        {
            statusMessage = "请输入手机号";
            statusColor = 0xFFFF5555;
            return;
        }
        String ct = countryCodeField.getValue().trim();
        NeteaseSession.getInstance().getApi().captchaSent(phone, ct)
                .thenAccept(resp -> {
                    int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                    if (code == 200)
                    {
                        statusMessage = "验证码已发送";
                        statusColor = 0xFF1DB954;
                        lastCaptchaSent = System.currentTimeMillis();
                    }
                    else
                    {
                        statusMessage = "发送失败: " + (resp.has("message") && !resp.get("message").isJsonNull()
                                ? resp.get("message").getAsString() : "code " + code);
                        statusColor = 0xFFFF5555;
                    }
                });
    }

    private void doLogin()
    {
        if (mode == LoginMode.PHONE)
        {
            String phone = phoneField.getValue().trim();
            String ct = countryCodeField.getValue().trim();
            String captcha = captchaField.getValue().trim();
            String password = passwordField.getValue();
            if (phone.isEmpty())
            {
                statusMessage = "请输入手机号";
                statusColor = 0xFFFF5555;
                return;
            }
            if (captcha.isEmpty() && password.isEmpty())
            {
                statusMessage = "请输入验证码或密码";
                statusColor = 0xFFFF5555;
                return;
            }
            statusMessage = "正在登录…";
            statusColor = 0xFFFFFFFF;
            loginBtn.active = false;
            NeteaseSession.getInstance().getApi().loginCellphone(phone, password, captcha, ct)
                    .thenAccept(this::handleLoginResult);
        }
        else if (mode == LoginMode.EMAIL)
        {
            String email = emailField.getValue().trim();
            String password = emailPasswordField.getValue();
            if (email.isEmpty() || password.isEmpty())
            {
                statusMessage = "请输入邮箱和密码";
                statusColor = 0xFFFF5555;
                return;
            }
            statusMessage = "正在登录…";
            statusColor = 0xFFFFFFFF;
            loginBtn.active = false;
            NeteaseSession.getInstance().getApi().loginEmail(email, password)
                    .thenAccept(this::handleLoginResult);
        }
    }

    private void handleLoginResult(JsonObject resp)
    {
        int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
        ZephyrMusic.LOGGER.info("[Zephyr] login result code={}, keys={}", code, resp.keySet());
        if (code == 200 || code == 803)
        {
            boolean ok = NeteaseSession.getInstance().handleLoginResponse(resp);
            if (ok)
            {
                statusMessage = "登录成功！正在跳转…";
                statusColor = 0xFF1DB954;
                NeteaseSession.getInstance().refreshUser().thenAccept(u -> {
                    ZephyrMusic.LOGGER.info("[Zephyr] refreshUser after login: {}", u);
                    if (u && NeteaseSession.getInstance().getCurrentUser() != null)
                    {
                        statusMessage = "欢迎, " + NeteaseSession.getInstance().getCurrentUser().nickname;
                    }
                    // 自动跳转
                    Minecraft.getInstance().execute(() -> {
                        Minecraft.getInstance().setScreen(new PlaylistBrowserScreen());
                    });
                });
            }
            else
            {
                statusMessage = "登录响应异常，请重试";
                statusColor = 0xFFFF5555;
                loginBtn.active = true;
            }
        }
        else
        {
            String msg = resp.has("message") && !resp.get("message").isJsonNull()
                    ? resp.get("message").getAsString()
                    : (resp.has("msg") && !resp.get("msg").isJsonNull() ? resp.get("msg").getAsString() : ("错误码 " + code));
            statusMessage = "登录失败: " + msg;
            statusColor = 0xFFFF5555;
            loginBtn.active = true;
        }
    }

    @Override
    public void tick()
    {
        super.tick();
        // 处理验证码冷却显示
        if (lastCaptchaSent > 0)
        {
            long elapsed = System.currentTimeMillis() - lastCaptchaSent;
            int remain = 60 - (int) (elapsed / 1000);
            if (remain > 0)
            {
                sendCaptchaBtn.setMessage(Component.literal(remain + "s"));
                sendCaptchaBtn.active = false;
            }
            else
            {
                sendCaptchaBtn.setMessage(Component.literal("发送验证码"));
                sendCaptchaBtn.active = !phoneField.getValue().trim().isEmpty();
            }
        }
        else if (mode == LoginMode.PHONE)
        {
            sendCaptchaBtn.active = !phoneField.getValue().trim().isEmpty();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        // 不绘制默认泥土背景
        // renderBackground(g);

        int cx = this.width / 2;
        int topY = Math.max(30, (this.height - 320) / 2);

        g.drawCenteredString(this.font, this.getTitle(), cx, 8, 0xFFFFFFFF);

        // 状态消息
        g.drawCenteredString(this.font, Component.literal(statusMessage),
                cx, topY + 24, statusColor);

        if (mode == LoginMode.QR)
        {
            if (qrImage != null)
            {
                // 根据屏幕可用空间计算二维码大小
                int maxW = Math.min(220, this.width - 100);
                int maxH = Math.min(220, this.height - topY - 280);
                int sz = Math.min(maxW, maxH);
                if (sz < 100) sz = 100;
                int qx = cx - sz / 2;
                int qy = topY + 50;
                renderQrImage(g, qrImage, qx, qy, sz);
                g.drawCenteredString(this.font, Component.literal("请使用网易云 App 扫码登录"),
                        cx, qy + sz + 6, 0xFFCCCCCC);
            }
            else
            {
                g.drawCenteredString(this.font, Component.literal("[ 正在生成二维码 ]"),
                        cx, topY + 110, 0xFFAAAAAA);
            }
        }
        else if (mode == LoginMode.PHONE)
        {
            g.drawString(this.font, Component.literal("+"), cx - 50, topY + 45, 0xFFCCCCCC, false);
            g.drawString(this.font, Component.literal("验证码或密码二选一"),
                    cx - 80, topY + 116, 0xFF888888, false);
        }
        else if (mode == LoginMode.EMAIL)
        {
            g.drawString(this.font, Component.literal("使用网易云邮箱密码登录"),
                    cx - 80, topY + 30, 0xFF888888, false);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderQrImage(GuiGraphics g, BufferedImage img, int x, int y, int size)
    {
        try
        {
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0) return;
            int pixelSize = Math.max(1, size / Math.max(w, h));
            int actualW = w * pixelSize;
            int actualH = h * pixelSize;
            int startX = x + (size - actualW) / 2;
            int startY = y + (size - actualH) / 2;

            // 白色背景（带边距）
            int pad = Math.max(2, pixelSize * 2);
            g.fill(startX - pad, startY - pad, startX + actualW + pad, startY + actualH + pad, 0xFFFFFFFF);

            // 绘制每个像素（只绘制深色像素以减少 fill 调用数）
            for (int py = 0; py < h; py++)
            {
                for (int px = 0; px < w; px++)
                {
                    int argb = img.getRGB(px, py);
                    int r = (argb >> 16) & 0xFF;
                    int gg = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int lum = (r + gg + b) / 3;
                    if (lum < 128)
                    {
                        g.fill(startX + px * pixelSize, startY + py * pixelSize,
                                startX + px * pixelSize + pixelSize, startY + py * pixelSize + pixelSize,
                                0xFF000000);
                    }
                }
            }
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.error("[Zephyr] renderQrImage failed", e);
            g.drawString(this.font, Component.literal("[QR 显示失败]"), x, y, 0xFFFF5555, false);
        }
    }

    @Override
    public void onClose()
    {
        if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
