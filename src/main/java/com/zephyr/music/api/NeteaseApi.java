package com.zephyr.music.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.net.NeteaseHttpClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 网易云音乐 API 封装 - 对应参考项目 src/api/netease/*
 *
 * 接口对应 https://musicapi.mingqwq.top/docs/#/
 */
public class NeteaseApi
{
    private final NeteaseHttpClient http;

    public NeteaseApi()
    {
        this.http = NeteaseHttpClient.getInstance();
    }

    public NeteaseHttpClient getHttp()
    {
        return http;
    }

    // ============== 登录 ==============

    /** 二维码 key */
    public CompletableFuture<JsonObject> qrKey()
    {
        return http.get("/login/qr/key");
    }

    /** 生成二维码（返回 qrimg base64） */
    public CompletableFuture<JsonObject> qrCreate(String key)
    {
        Map<String, String> p = new HashMap<>();
        p.put("key", key);
        p.put("qrimg", "true");
        return http.get("/login/qr/create", p);
    }

    /** 二维码扫码状态：800 过期 801 等待 802 待确认 803 授权成功 */
    public CompletableFuture<JsonObject> qrCheck(String key)
    {
        Map<String, String> p = new HashMap<>();
        p.put("key", key);
        return http.get("/login/qr/check", p);
    }

    /** 手机号登录 */
    public CompletableFuture<JsonObject> loginCellphone(String phone, String password, String captcha, String countrycode)
    {
        Map<String, String> p = new HashMap<>();
        p.put("phone", phone);
        if (password != null && !password.isEmpty()) p.put("password", password);
        if (captcha != null && !captcha.isEmpty()) p.put("captcha", captcha);
        if (countrycode != null && !countrycode.isEmpty()) p.put("countrycode", countrycode);
        return http.post("/login/cellphone", p);
    }

    /** 邮箱登录 */
    public CompletableFuture<JsonObject> loginEmail(String email, String password)
    {
        Map<String, String> p = new HashMap<>();
        p.put("email", email);
        p.put("password", password);
        return http.post("/login", p);
    }

    /** 发送手机验证码 */
    public CompletableFuture<JsonObject> captchaSent(String phone, String ctcode)
    {
        Map<String, String> p = new HashMap<>();
        p.put("phone", phone);
        if (ctcode != null && !ctcode.isEmpty()) p.put("ctcode", ctcode);
        return http.get("/captcha/sent", p);
    }

    /** 校验验证码 */
    public CompletableFuture<JsonObject> captchaVerify(String phone, String captcha, String ctcode)
    {
        Map<String, String> p = new HashMap<>();
        p.put("phone", phone);
        p.put("captcha", captcha);
        if (ctcode != null && !ctcode.isEmpty()) p.put("ctcode", ctcode);
        return http.get("/captcha/verify", p);
    }

    /** 登录状态 */
    public CompletableFuture<JsonObject> loginStatus()
    {
        return http.get("/login/status");
    }

    /** 退出登录 */
    public CompletableFuture<JsonObject> logout()
    {
        return http.get("/logout");
    }

    /** 国家码列表 */
    public CompletableFuture<JsonObject> countriesCodeList()
    {
        return http.get("/countries/code/list");
    }

    // ============== 用户 ==============

    /** 账号信息 */
    public CompletableFuture<JsonObject> userAccount()
    {
        return http.get("/user/account");
    }

    /** 用户歌单 */
    public CompletableFuture<JsonObject> userPlaylist(long uid, int limit)
    {
        Map<String, String> p = new HashMap<>();
        p.put("uid", String.valueOf(uid));
        p.put("limit", String.valueOf(limit));
        return http.get("/user/playlist", p);
    }

    // ============== 歌单 ==============

    /** 歌单详情 */
    public CompletableFuture<JsonObject> playlistDetail(long id)
    {
        Map<String, String> p = new HashMap<>();
        p.put("id", String.valueOf(id));
        return http.get("/playlist/detail", p);
    }

    /** 歌单全部歌曲 */
    public CompletableFuture<JsonObject> playlistTrackAll(long id, int limit, int offset)
    {
        Map<String, String> p = new HashMap<>();
        p.put("id", String.valueOf(id));
        p.put("limit", String.valueOf(limit));
        p.put("offset", String.valueOf(offset));
        return http.get("/playlist/track/all", p);
    }

    /** 每日推荐 */
    public CompletableFuture<JsonObject> recommendSongs()
    {
        return http.get("/recommend/songs");
    }

    /** 推荐歌单（无需登录） */
    public CompletableFuture<JsonObject> personalized(int limit)
    {
        Map<String, String> p = new HashMap<>();
        p.put("limit", String.valueOf(limit));
        return http.get("/personalized", p);
    }

    // ============== 歌曲 ==============

    /** 歌曲详情 */
    public CompletableFuture<JsonObject> songDetail(List<Long> ids)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++)
        {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        Map<String, String> p = new HashMap<>();
        p.put("ids", sb.toString());
        return http.get("/song/detail", p);
    }

    /** 歌曲URL v1（指定音质） */
    public CompletableFuture<JsonObject> songUrlV1(long id, String level)
    {
        Map<String, String> p = new HashMap<>();
        p.put("id", String.valueOf(id));
        p.put("level", level == null ? "exhigh" : level);
        if ("dolby".equals(level)) p.put("os", "pc");
        return http.get("/song/url/v1", p);
    }

    /** 歌曲URL（标准） */
    public CompletableFuture<JsonObject> songUrl(long id)
    {
        Map<String, String> p = new HashMap<>();
        p.put("id", String.valueOf(id));
        return http.get("/song/url", p);
    }

    // ============== 歌词 ==============

    /** 标准 LRC 歌词 */
    public CompletableFuture<JsonObject> lyric(long id)
    {
        Map<String, String> p = new HashMap<>();
        p.put("id", String.valueOf(id));
        return http.get("/lyric", p);
    }

    /** 逐字歌词 yrc + 标准 lrc */
    public CompletableFuture<JsonObject> lyricNew(long id)
    {
        Map<String, String> p = new HashMap<>();
        p.put("id", String.valueOf(id));
        return http.get("/lyric/new", p);
    }

    /** 打卡 */
    public CompletableFuture<JsonObject> scrobble(long id, long sourceid, int time)
    {
        Map<String, String> p = new HashMap<>();
        p.put("id", String.valueOf(id));
        p.put("sourceid", String.valueOf(sourceid));
        p.put("time", String.valueOf(time));
        return http.get("/scrobble", p);
    }

    // ============== 搜索 ==============

    public CompletableFuture<JsonObject> search(String keyword, int limit, int offset)
    {
        Map<String, String> p = new HashMap<>();
        p.put("keywords", keyword);
        p.put("limit", String.valueOf(limit));
        p.put("offset", String.valueOf(offset));
        p.put("type", "1"); // 1=single 10=album 100=artist 1000=playlist
        return http.get("/search", p);
    }

    // ============== 解析工具 ==============

    /**
     * 从 loginStatus 响应中提取用户信息
     */
    public static NeteaseUser parseUser(JsonObject loginStatusResp)
    {
        try
        {
            JsonObject data = loginStatusResp.has("data") && loginStatusResp.get("data").isJsonObject()
                    ? loginStatusResp.getAsJsonObject("data") : loginStatusResp;
            JsonObject profile = data.has("profile") && data.get("profile").isJsonObject()
                    ? data.getAsJsonObject("profile") : null;
            if (profile == null || profile.isJsonNull()) return null;
            NeteaseUser u = new NeteaseUser();
            u.userId = profile.has("userId") ? profile.get("userId").getAsLong() : 0;
            u.nickname = profile.has("nickname") ? profile.get("nickname").getAsString() : "网易云用户";
            u.avatarUrl = profile.has("avatarUrl") && !profile.get("avatarUrl").isJsonNull()
                    ? profile.get("avatarUrl").getAsString() : "";
            u.signature = profile.has("signature") && !profile.get("signature").isJsonNull()
                    ? profile.get("signature").getAsString() : "";
            return u;
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.warn("[Zephyr] parseUser failed: {}", e.getMessage());
            return null;
        }
    }

    /** 从 qrCheck 响应中提取 cookie */
    public static String extractCookie(JsonObject qrCheckResp)
    {
        if (qrCheckResp == null) return null;
        if (qrCheckResp.has("cookie") && !qrCheckResp.get("cookie").isJsonNull())
        {
            return qrCheckResp.get("cookie").getAsString();
        }
        return null;
    }

    /**
     * 从 songUrlV1 响应中获取 url
     */
    public static String extractSongUrl(JsonObject resp)
    {
        try
        {
            JsonElement dataEl = resp.get("data");
            if (dataEl == null || !dataEl.isJsonArray()) return null;
            JsonArray arr = dataEl.getAsJsonArray();
            if (arr.isEmpty()) return null;
            JsonObject first = arr.get(0).getAsJsonObject();
            if (first.has("url") && !first.get("url").isJsonNull())
            {
                return first.get("url").getAsString();
            }
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.warn("[Zephyr] extractSongUrl failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 解析 LRC 歌词为 LyricLine 列表
     * 格式: [mm:ss.xx]歌词文本
     */
    public static List<LyricLine> parseLrc(String lrcText)
    {
        List<LyricLine> lines = new ArrayList<>();
        if (lrcText == null || lrcText.isEmpty()) return lines;
        for (String raw : lrcText.split("\n"))
        {
            // 一行可能有多个时间戳 [00:00.00][00:30.00]歌词
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\]").matcher(raw);
            String text = raw.replaceAll("\\[\\d+:\\d+(?:\\.\\d+)?\\]", "").trim();
            while (m.find())
            {
                try
                {
                    long min = Long.parseLong(m.group(1));
                    long sec = Long.parseLong(m.group(2));
                    String msStr = m.group(3);
                    long ms = msStr == null ? 0 : (msStr.length() == 2 ? Long.parseLong(msStr) * 10 : Long.parseLong(msStr));
                    double time = min * 60 + sec + ms / 1000.0;
                    if (!text.isEmpty())
                    {
                        lines.add(new LyricLine(time, text));
                    }
                }
                catch (NumberFormatException ignored) {}
            }
        }
        lines.sort(java.util.Comparator.comparingDouble(l -> l.time));
        return lines;
    }

    /**
     * 解析 yrc 逐字歌词为按行的 LyricLine 列表
     * 支持三种行格式（按行自动识别）：
     * 1. 旧格式: [行开始ms,行总时长ms](字开始ms,字时长ms,0)字(字开始ms,字时长ms,0)字...
     * 2. 新格式（JSON 整行）: {"t":开始ms,"c":[{"tx":"字","li":"图片URL"}]}
     * 3. 普通 lrc: [mm:ss.xx]歌词
     */
    public static List<LyricLine> parseYrc(String yrcText)
    {
        List<LyricLine> lines = new ArrayList<>();
        if (yrcText == null || yrcText.isEmpty()) return lines;

        java.util.regex.Pattern lineHead = java.util.regex.Pattern.compile("^\\[(\\d+),(\\d+)\\]");
        java.util.regex.Pattern wordPat = java.util.regex.Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^\\(\\[]*)");

        for (String raw : yrcText.split("\n"))
        {
            raw = raw.trim();
            if (raw.isEmpty()) continue;

            // 按行检测格式
            if (raw.startsWith("{"))
            {
                // JSON 格式
                LyricLine l = parseYrcJsonLine(raw);
                if (l != null) lines.add(l);
                continue;
            }

            // 旧格式 [ms,ms](ms,ms,0)字...
            java.util.regex.Matcher m = lineHead.matcher(raw);
            if (!m.find()) continue;
            try
            {
                long startMs = Long.parseLong(m.group(1));
                double lineTime = startMs / 1000.0;
                String rest = raw.substring(m.end());
                List<LyricWord> words = new ArrayList<>();
                StringBuilder sb = new StringBuilder();
                java.util.regex.Matcher wm = wordPat.matcher(rest);
                while (wm.find())
                {
                    try
                    {
                        long wStartMs = Long.parseLong(wm.group(1));
                        long wDurMs = Long.parseLong(wm.group(2));
                        String wText = wm.group(3);
                        if (wText == null) wText = "";
                        words.add(new LyricWord(wStartMs / 1000.0, wDurMs / 1000.0, wText));
                        sb.append(wText);
                    }
                    catch (NumberFormatException ignored) {}
                }
                String lineText = sb.toString().trim();
                if (!lineText.isEmpty() && !words.isEmpty())
                {
                    lines.add(new LyricLine(lineTime, lineText, words));
                }
            }
            catch (NumberFormatException ignored) {}
        }
        lines.sort(java.util.Comparator.comparingDouble(l -> l.time));
        return lines;
    }

    /** 解析单行新版 JSON 格式 yrc: {"t":行开始ms,"c":[{"tx":"字"},...]} */
    private static LyricLine parseYrcJsonLine(String raw)
    {
        try
        {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            if (!obj.has("t")) return null;
            long lineStartMs = obj.get("t").getAsLong();
            double lineTime = lineStartMs / 1000.0;

            List<LyricWord> words = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            if (obj.has("c") && obj.get("c").isJsonArray())
            {
                com.google.gson.JsonArray arr = obj.getAsJsonArray("c");
                List<double[]> starts = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++)
                {
                    com.google.gson.JsonObject w = arr.get(i).getAsJsonObject();
                    String tx = w.has("tx") && !w.get("tx").isJsonNull() ? w.get("tx").getAsString() : "";
                    double start = lineStartMs;
                    if (w.has("ts") && !w.get("ts").isJsonNull())
                    {
                        start = w.get("ts").getAsDouble();
                    }
                    double end = w.has("te") && !w.get("te").isJsonNull() ? w.get("te").getAsDouble() : -1;
                    starts.add(new double[]{start, end});
                    texts.add(tx);
                    sb.append(tx);
                }
                for (int i = 0; i < texts.size(); i++)
                {
                    double startMs = starts.get(i)[0];
                    double endMs = starts.get(i)[1];
                    if (endMs < 0)
                    {
                        if (i + 1 < texts.size())
                        {
                            endMs = starts.get(i + 1)[0];
                        }
                        else
                        {
                            endMs = startMs + 300;
                        }
                    }
                    double durSec = Math.max(0.05, (endMs - startMs) / 1000.0);
                    words.add(new LyricWord(startMs / 1000.0, durSec, texts.get(i)));
                }
            }
            String lineText = sb.toString().trim();
            if (lineText.isEmpty() || words.isEmpty()) return null;
            return new LyricLine(lineTime, lineText, words);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 从 playlistTrackAll / songDetail 响应中解析歌曲列表
     */
    public static List<NeteaseSong> parseSongs(JsonObject resp, String arrayKey)
    {
        List<NeteaseSong> list = new ArrayList<>();
        try
        {
            JsonArray arr = null;
            if (resp.has(arrayKey) && resp.get(arrayKey).isJsonArray())
            {
                arr = resp.getAsJsonArray(arrayKey);
            }
            else if (resp.has("data") && resp.get("data").isJsonObject())
            {
                JsonObject d = resp.getAsJsonObject("data");
                if (d.has(arrayKey) && d.get(arrayKey).isJsonArray())
                {
                    arr = d.getAsJsonArray(arrayKey);
                }
            }
            if (arr == null) return list;
            for (JsonElement el : arr)
            {
                if (!el.isJsonObject()) continue;
                NeteaseSong s = parseSong(el.getAsJsonObject());
                if (s != null) list.add(s);
            }
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.warn("[Zephyr] parseSongs failed: {}", e.getMessage());
        }
        return list;
    }

    public static NeteaseSong parseSong(JsonObject obj)
    {
        try
        {
            NeteaseSong s = new NeteaseSong();
            s.id = obj.has("id") ? obj.get("id").getAsLong() : 0;
            s.name = obj.has("name") && !obj.get("name").isJsonNull()
                    ? obj.get("name").getAsString() : "";
            // al.name (专辑) 或 album.name
            if (obj.has("al") && obj.get("al").isJsonObject())
            {
                JsonObject al = obj.getAsJsonObject("al");
                s.album = al.has("name") && !al.get("name").isJsonNull()
                        ? al.get("name").getAsString() : "";
                if (al.has("picUrl") && !al.get("picUrl").isJsonNull())
                {
                    s.picUrl = al.get("picUrl").getAsString();
                }
            }
            else if (obj.has("album") && obj.get("album").isJsonObject())
            {
                JsonObject al = obj.getAsJsonObject("album");
                s.album = al.has("name") && !al.get("name").isJsonNull()
                        ? al.get("name").getAsString() : "";
                if (al.has("picUrl") && !al.get("picUrl").isJsonNull())
                {
                    s.picUrl = al.get("picUrl").getAsString();
                }
            }
            // ar[] / artists[]
            String artistKey = obj.has("ar") ? "ar" : "artists";
            if (obj.has(artistKey) && obj.get(artistKey).isJsonArray())
            {
                JsonArray arr = obj.getAsJsonArray(artistKey);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.size(); i++)
                {
                    JsonObject a = arr.get(i).getAsJsonObject();
                    if (i > 0) sb.append("/");
                    sb.append(a.has("name") && !a.get("name").isJsonNull()
                            ? a.get("name").getAsString() : "");
                }
                s.artist = sb.toString();
            }
            s.duration = obj.has("dt") ? obj.get("dt").getAsLong()
                    : obj.has("duration") ? obj.get("duration").getAsLong() : 0;
            return s;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static List<NeteasePlaylist> parsePlaylists(JsonObject resp)
    {
        List<NeteasePlaylist> list = new ArrayList<>();
        try
        {
            JsonArray arr = null;
            if (resp.has("playlist") && resp.get("playlist").isJsonArray())
            {
                arr = resp.getAsJsonArray("playlist");
            }
            else if (resp.has("recommend") && resp.get("recommend").isJsonArray())
            {
                arr = resp.getAsJsonArray("recommend");
            }
            else if (resp.has("result") && resp.get("result").isJsonArray())
            {
                arr = resp.getAsJsonArray("result");
            }
            else if (resp.has("data") && resp.get("data").isJsonArray())
            {
                arr = resp.getAsJsonArray("data");
            }
            if (arr == null) return list;
            for (JsonElement el : arr)
            {
                if (!el.isJsonObject()) continue;
                NeteasePlaylist p = parsePlaylist(el.getAsJsonObject());
                if (p != null) list.add(p);
            }
        }
        catch (Exception ignored) {}
        return list;
    }

    public static NeteasePlaylist parsePlaylist(JsonObject obj)
    {
        try
        {
            NeteasePlaylist p = new NeteasePlaylist();
            p.id = obj.has("id") ? obj.get("id").getAsLong() : 0;
            p.name = obj.has("name") && !obj.get("name").isJsonNull()
                    ? obj.get("name").getAsString() : "";
            if (obj.has("coverImgUrl") && !obj.get("coverImgUrl").isJsonNull())
                p.coverImgUrl = obj.get("coverImgUrl").getAsString();
            else if (obj.has("picUrl") && !obj.get("picUrl").isJsonNull())
                p.coverImgUrl = obj.get("picUrl").getAsString();
            p.trackCount = obj.has("trackCount") ? obj.get("trackCount").getAsInt() : 0;
            p.playCount = obj.has("playCount") && !obj.get("playCount").isJsonNull()
                    ? obj.get("playCount").getAsLong() : 0;
            if (obj.has("creator") && obj.get("creator").isJsonObject())
            {
                JsonObject c = obj.getAsJsonObject("creator");
                p.creatorName = c.has("nickname") && !c.get("nickname").isJsonNull()
                        ? c.get("nickname").getAsString() : "";
            }
            return p;
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
