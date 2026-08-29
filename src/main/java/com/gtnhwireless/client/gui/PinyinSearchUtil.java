package com.gtnhwireless.client.gui;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import java.nio.charset.Charset;

/**
 * 拼音首字母搜索工具。
 * <p>
 * 将中文频道名称转换为拼音首字母缩写，支持输入拼音首字母搜索中文名称。
 * <p>
 * 原理：将待查字符编码为 GB2312，利用 GB2312 一级汉字按拼音排序的特性，
 * 通过查表获取每个汉字的首字母。
 * <p>
 * 覆盖范围：GB2312 一级汉字（约 3755 个最常用汉字），覆盖日常使用 99% 以上。
 * GB2312 二级汉字（按部首排列）无法通过此方法获取拼音首字母，返回原字符小写。
 */
@SideOnly(Side.CLIENT)
public final class PinyinSearchUtil {

    // GB2312 编码范围表：{ startCode, endCode, pinyinInitial }
    // 其中 code = (highByte << 8) | lowByte，highByte/lowByte 为 GB2312 编码的无符号值
    // GB2312 一级汉字（区码 16-55）按拼音排序，每个拼音首字母对应若干连续区域
    private static final int[][] GB2312_RANGES = {
            {0xB0A1, 0xB0C4, 'A'},
            {0xB0C5, 0xB2C0, 'B'},
            {0xB2C1, 0xB4ED, 'C'},
            {0xB4EE, 0xB6E9, 'D'},
            {0xB6EA, 0xB7A1, 'E'},
            {0xB7A2, 0xB8C0, 'F'},
            {0xB8C1, 0xB9FD, 'G'},
            {0xB9FE, 0xBBF6, 'H'},
            {0xBBF7, 0xBFA5, 'J'},
            {0xBFA6, 0xC0AB, 'K'},
            {0xC0AC, 0xC2E7, 'L'},
            {0xC2E8, 0xC4C2, 'M'},
            {0xC4C3, 0xC5B5, 'N'},
            {0xC5B6, 0xC5BD, 'O'},
            {0xC5BE, 0xC6D9, 'P'},
            {0xC6DA, 0xC8BA, 'Q'},
            {0xC8BB, 0xC8F5, 'R'},
            {0xC8F6, 0xCBF0, 'S'},
            {0xCBF1, 0xCDD9, 'T'},
            {0xCDDA, 0xCEF3, 'W'},
            {0xCEF4, 0xD188, 'X'},
            {0xD189, 0xD4D0, 'Y'},
            {0xD4D1, 0xD7F9, 'Z'},
    };

    private static final String GB2312_CHARSET = "GB2312";
    private static final Charset GB2312;

    static {
        Charset cs = null;
        try {
            cs = Charset.forName(GB2312_CHARSET);
        } catch (Exception ignored) {
        }
        GB2312 = cs;
    }

    private PinyinSearchUtil() {
    }

    /**
     * 将输入文本转换为拼音首字母（小写）。
     * <p>
     * 例如："我的频道" → "wdpd"；"AE2网络" → "ae2wl"
     * 非中文/英文字符保留原样。英文字母转为小写。
     */
    public static String toPinyinInitials(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            sb.append(getFirstLetter(ch));
        }
        return sb.toString();
    }

    /**
     * 判断 query 是否匹配 target。
     * <p>
     * 匹配规则（均不区分大小写）：
     * 1. query 是 target 的子串（普通搜索）
     * 2. query 是 target 的拼音首字母缩写（如 "wdpd" 匹配 "我的频道"）
     */
    public static boolean matches(String query, String target) {
        if (query == null || query.isEmpty()) return true;
        if (target == null || target.isEmpty()) return false;

        String q = query.toLowerCase();
        String t = target.toLowerCase();

        // 1. 直接子串匹配
        if (t.contains(q)) return true;

        // 2. 拼音首字母匹配
        String initials = toPinyinInitials(target).toLowerCase();
        return initials.contains(q);
    }

    /**
     * 获取单个字符的拼音首字母。
     * <p>
     * 中文汉字返回大写 A-Z（对应拼音首字母）；英文字母返回小写；其他返回原字符小写。
     */
    private static char getFirstLetter(char ch) {
        // 英文字母直接转小写
        if (ch >= 'A' && ch <= 'Z') return (char) (ch + 32);
        if (ch >= 'a' && ch <= 'z') return ch;

        // 非 CJK 统一表意文字范围（快速跳过非汉字）
        if (ch < 0x4E00 || ch > 0x9FA5) return Character.toLowerCase(ch);

        // 使用 GB2312 编码查拼音首字母
        if (GB2312 != null) {
            try {
                byte[] bytes = String.valueOf(ch).getBytes(GB2312);
                if (bytes.length == 2) {
                    int code = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
                    for (int[] range : GB2312_RANGES) {
                        if (code >= range[0] && code <= range[1]) {
                            return (char) range[2];
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 回退：无法获取拼音首字母
        return Character.toLowerCase(ch);
    }
}