package com.zephyr.music.client.gui.screen;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.NeteaseApi;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.api.NeteaseUser;
import com.zephyr.music.net.NeteaseHttpClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

/**
 * 网易云登录界面 - 支持扫码、手机验证码、邮箱三种方式
 */
public class LoginScreen extends Screen
{
    private enum LoginMode { QR, PHONE, EMAIL }

    private LoginMode mode = LoginMode.QR;
    private String statusMessage = "请选择登录方式";
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
    private int captchaCooldown = 0;
    private long lastCaptchaSent = 0;

    public LoginScreen()
    {
        super(Component.literal("Zephyr Music · 网易云登录"));
    }

    @Override
    protected void init()
    {
        int cx = this.width / 2;

        // 顶部标签切换按钮
        addRenderableWidget(Button.builder(Component.literal("扫码登录"), b -> switchMode(LoginMode.QR))
                .bounds(cx - 200, 30, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("手机登录"), b -> switchMode(LoginMode.PHONE))
                .bounds(cx - 70, 30, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("邮箱登录"), b -> switchMode(LoginMode.EMAIL))
                .bounds(cx + 60, 30, 120, 20).build());

        // 表单字段（不同模式共用）
        int fy = 90;
        countryCodeField = new EditBox(this.font, cx - 40, fy, 50, 18, Component.literal("86"));
        countryCodeField.setValue("86");
        countryCodeField.setMaxLength(5);
        phoneField = new EditBox(this.font, cx + 15, fy, 180, 18, Component.literal("手机号"));
        phoneField.setMaxLength(20);
        phoneField.setHint(Component.literal("手机号"));

        passwordField = new EditBox(this.font, cx - 80, fy + 30, 180, 18, Component.literal("密码（可选）"));
        passwordField.setMaxLength(64);
        passwordField.setHint(Component.literal("密码（可选）"));
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

        // 关闭按钮
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(this.width - 80, this.height - 30, 60, 20).build());

        applyMode();
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
        // QR 模式不需要表单
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
        this.statusMessage = "请按下方按钮开始";
        this.statusColor = 0xFFFFFFFF;
        applyMode();
        if (newMode == LoginMode.QR)
        {
            startQrLogin();
        }
    }

    private void startQrLogin()
    {
        statusMessage = "正在生成二维码…";
        statusColor = 0xFFFFFFFF;
        NeteaseHttpClient.getInstance().clearCookie();
        NeteaseApi api = NeteaseSession.getInstance().getApi();
        api.qrKey().thenCompose(resp -> {
            if (resp.has("data") && resp.getAsJsonObject("data").has("unikey"))
            {
                qrKey = resp.getAsJsonObject("data").get("unikey").getAsString();
                ZephyrMusic.LOGGER.info("[Zephyr] QR key: {}", qrKey);
                return api.qrCreate(qrKey);
            }
            else if (resp.has("unikey"))
            {
                qrKey = resp.get("unikey").getAsString();
                return api.qrCreate(qrKey);
            }
            return CompletableFuture.completedFuture(new JsonObject());
        }).thenAccept(resp -> {
            String qrimg = "";
            if (resp.has("data") && resp.getAsJsonObject("data").has("qrimg"))
            {
                qrimg = resp.getAsJsonObject("data").get("qrimg").getAsString();
            }
            else if (resp.has("qrimg"))
            {
                qrimg = resp.get("qrimg").getAsString();
            }
            if (qrimg.startsWith("data:image"))
            {
                qrimg = qrimg.substring(qrimg.indexOf(",") + 1);
            }
            try
            {
                byte[] bytes = Base64.getDecoder().decode(qrimg);
                qrImage = ImageIO.read(new ByteArrayInputStream(bytes));
                statusMessage = "请使用网易云 App 扫码登录";
                statusColor = 0xFF1DB954;
                startQrPolling();
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.error("[Zephyr] decode QR image failed", e);
                statusMessage = "二维码解析失败：" + e.getMessage();
                statusColor = 0xFFFF5555;
            }
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
                    String message = resp.has("message") ? resp.get("message").getAsString() : "";
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
                        statusMessage = "登录成功！";
                        statusColor = 0xFF1DB954;
                        String cookie = NeteaseApi.extractCookie(resp);
                        if (cookie != null && !cookie.isEmpty())
                        {
                            NeteaseHttpClient.getInstance().setCookie(cookie);
                            NeteaseSession.getInstance().refreshUser().thenAccept(ok -> {
                                if (ok)
                                {
                                    ZephyrMusic.LOGGER.info("[Zephyr] Login success via QR");
                                }
                            });
                        }
                        if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; }
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
                        statusMessage = "发送失败: " + (resp.has("message") ? resp.get("message").getAsString() : code);
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
            NeteaseSession.getInstance().getApi().loginCellphone(phone, password, captcha, ct)
                    .thenAccept(resp -> handleLoginResult(resp));
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
            NeteaseSession.getInstance().getApi().loginEmail(email, password)
                    .thenAccept(resp -> handleLoginResult(resp));
        }
    }

    private void handleLoginResult(JsonObject resp)
    {
        int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
        if (code == 200 || code == 803)
        {
            boolean ok = NeteaseSession.getInstance().handleLoginResponse(resp);
            if (ok)
            {
                statusMessage = "登录成功！";
                statusColor = 0xFF1DB954;
                NeteaseSession.getInstance().refreshUser().thenAccept(u -> {
                    if (u && NeteaseSession.getInstance().getCurrentUser() != null)
                    {
                        statusMessage = "欢迎, " + NeteaseSession.getInstance().getCurrentUser().nickname;
                    }
                });
            }
            else
            {
                statusMessage = "登录响应异常";
                statusColor = 0xFFFF5555;
            }
        }
        else
        {
            String msg = resp.has("message") ? resp.get("message").getAsString()
                    : resp.has("msg") ? resp.get("msg").getAsString() : ("错误码 " + code);
            statusMessage = "登录失败: " + msg;
            statusColor = 0xFFFF5555;
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
        else
        {
            sendCaptchaBtn.active = !phoneField.getValue().trim().isEmpty();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.getTitle(), cx, 10, 0xFFFFFFFF);

        // 状态消息
        g.drawCenteredString(this.font, Component.literal(statusMessage), cx, 55, statusColor);

        if (mode == LoginMode.QR)
        {
            if (qrImage != null)
            {
                int sz = 160;
                int qx = cx - sz / 2;
                int qy = 90;
                // 用动态纹理绘制二维码
                renderQrImage(g, qrImage, qx, qy, sz);
                g.drawCenteredString(this.font, Component.literal("或点击重新生成"), cx, qy + sz + 8, 0xFFAAAAAA);
            }
            else
            {
                // 显示重新生成按钮
                addRenderableWidget(Button.builder(Component.literal("生成二维码"), b -> startQrLogin())
                        .bounds(cx - 60, 100, 120, 20).build());
            }
        }
        else if (mode == LoginMode.PHONE)
        {
            // 标签
            g.drawString(this.font, Component.literal("+"), cx - 50, 95, 0xFFCCCCCC, false);
            g.drawString(this.font, Component.literal("国家码"), cx - 65, 80, 0xFFAAAAAA, false);
            g.drawString(this.font, Component.literal("验证码或密码二选一"), cx - 80, 145, 0xFFAAAAAA, false);
        }
        else if (mode == LoginMode.EMAIL)
        {
            g.drawString(this.font, Component.literal("使用网易云邮箱密码登录"), cx - 90, 80, 0xFFAAAAAA, false);
        }
    }

    private void renderQrImage(GuiGraphics g, BufferedImage img, int x, int y, int size)
    {
        try
        {
            int w = img.getWidth();
            int h = img.getHeight();
            int pixelSize = size / Math.max(w, h);
            int actualW = w * pixelSize;
            int actualH = h * pixelSize;
            int startX = x + (size - actualW) / 2;
            int startY = y + (size - actualH) / 2;

            // 白色背景
            g.fill(startX - 4, startY - 4, startX + actualW + 4, startY + actualH + 4, 0xFFFFFFFF);
            // 绘制每个像素
            for (int py = 0; py < h; py++)
            {
                for (int px = 0; px < w; px++)
                {
                    int argb = img.getRGB(px, py);
                    int r = (argb >> 16) & 0xFF;
                    int gg = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // 黑色像素绘制
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
