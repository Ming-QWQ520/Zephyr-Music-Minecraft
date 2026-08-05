package com.zephyr.music.api;

/**
 * 网易云用户信息
 */
public class NeteaseUser
{
    public long userId;
    public String nickname = "";
    public String avatarUrl = "";
    public String signature = "";
    public long createTime;
    public int gender;
    public int city;
    public int province;
    public int vipType;
    public long listenSongs;
    public int level;
    public String backgroundUrl = "";

    @Override
    public String toString()
    {
        return "NeteaseUser{" + userId + ", " + nickname + "}";
    }
}
