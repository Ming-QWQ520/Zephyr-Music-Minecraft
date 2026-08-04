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

    @Override
    public String toString()
    {
        return "NeteaseUser{" + userId + ", " + nickname + "}";
    }
}
