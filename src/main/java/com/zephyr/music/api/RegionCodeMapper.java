package com.zephyr.music.api;

import java.util.HashMap;
import java.util.Map;

/**
 * 网易云省份/城市代码映射表
 */
public class RegionCodeMapper
{
    private static final Map<Integer, String> PROVINCE_MAP = new HashMap<>();
    private static final Map<Integer, String> CITY_MAP = new HashMap<>();

    static
    {
        PROVINCE_MAP.put(110000, "北京市");
        PROVINCE_MAP.put(120000, "天津市");
        PROVINCE_MAP.put(130000, "河北省");
        PROVINCE_MAP.put(140000, "山西省");
        PROVINCE_MAP.put(150000, "内蒙古自治区");
        PROVINCE_MAP.put(210000, "辽宁省");
        PROVINCE_MAP.put(220000, "吉林省");
        PROVINCE_MAP.put(230000, "黑龙江省");
        PROVINCE_MAP.put(310000, "上海市");
        PROVINCE_MAP.put(320000, "江苏省");
        PROVINCE_MAP.put(330000, "浙江省");
        PROVINCE_MAP.put(340000, "安徽省");
        PROVINCE_MAP.put(350000, "福建省");
        PROVINCE_MAP.put(360000, "江西省");
        PROVINCE_MAP.put(370000, "山东省");
        PROVINCE_MAP.put(410000, "河南省");
        PROVINCE_MAP.put(420000, "湖北省");
        PROVINCE_MAP.put(430000, "湖南省");
        PROVINCE_MAP.put(440000, "广东省");
        PROVINCE_MAP.put(450000, "广西壮族自治区");
        PROVINCE_MAP.put(460000, "海南省");
        PROVINCE_MAP.put(500000, "重庆市");
        PROVINCE_MAP.put(510000, "四川省");
        PROVINCE_MAP.put(520000, "贵州省");
        PROVINCE_MAP.put(530000, "云南省");
        PROVINCE_MAP.put(540000, "西藏自治区");
        PROVINCE_MAP.put(610000, "陕西省");
        PROVINCE_MAP.put(620000, "甘肃省");
        PROVINCE_MAP.put(630000, "青海省");
        PROVINCE_MAP.put(640000, "宁夏回族自治区");
        PROVINCE_MAP.put(650000, "新疆维吾尔自治区");
        PROVINCE_MAP.put(710000, "台湾省");
        PROVINCE_MAP.put(810000, "香港特别行政区");
        PROVINCE_MAP.put(820000, "澳门特别行政区");

        // 主要城市
        CITY_MAP.put(110100, "北京市");
        CITY_MAP.put(310100, "上海市");
        CITY_MAP.put(120100, "天津市");
        CITY_MAP.put(500100, "重庆市");
        CITY_MAP.put(440100, "广州市");
        CITY_MAP.put(440300, "深圳市");
        CITY_MAP.put(440600, "佛山市");
        CITY_MAP.put(441900, "东莞市");
        CITY_MAP.put(320100, "南京市");
        CITY_MAP.put(320500, "苏州市");
        CITY_MAP.put(330100, "杭州市");
        CITY_MAP.put(330200, "宁波市");
        CITY_MAP.put(510100, "成都市");
        CITY_MAP.put(420100, "武汉市");
        CITY_MAP.put(430100, "长沙市");
        CITY_MAP.put(370100, "济南市");
        CITY_MAP.put(370200, "青岛市");
        CITY_MAP.put(410100, "郑州市");
        CITY_MAP.put(130100, "石家庄市");
        CITY_MAP.put(350100, "福州市");
        CITY_MAP.put(350200, "厦门市");
        CITY_MAP.put(210100, "沈阳市");
        CITY_MAP.put(210200, "大连市");
        CITY_MAP.put(230100, "哈尔滨市");
        CITY_MAP.put(220100, "长春市");
        CITY_MAP.put(340100, "合肥市");
        CITY_MAP.put(360100, "南昌市");
        CITY_MAP.put(450100, "南宁市");
        CITY_MAP.put(530100, "昆明市");
        CITY_MAP.put(610100, "西安市");
        CITY_MAP.put(620100, "兰州市");
        CITY_MAP.put(520100, "贵阳市");
        CITY_MAP.put(140100, "太原市");
        CITY_MAP.put(460100, "海口市");
        CITY_MAP.put(150100, "呼和浩特市");
        CITY_MAP.put(650100, "乌鲁木齐市");
        CITY_MAP.put(640100, "银川市");
        CITY_MAP.put(630100, "西宁市");
        CITY_MAP.put(540100, "拉萨市");
    }

    public static String getProvinceName(int provinceCode)
    {
        if (provinceCode == 0) return "";
        int normalized = (provinceCode / 10000) * 10000;
        String name = PROVINCE_MAP.get(normalized);
        if (name != null) return name;
        name = PROVINCE_MAP.get(provinceCode);
        return name != null ? name : "";
    }

    public static String getCityName(int cityCode)
    {
        if (cityCode == 0) return "";
        String name = CITY_MAP.get(cityCode);
        if (name != null) return name;
        int normalized = (cityCode / 100) * 100;
        name = CITY_MAP.get(normalized);
        return name != null ? name : "";
    }

    public static String formatLocation(int province, int city)
    {
        String provinceName = getProvinceName(province);
        String cityName = getCityName(city);
        if (provinceName.isEmpty() && cityName.isEmpty()) return "未知";
        if (provinceName.isEmpty()) return cityName;
        if (cityName.isEmpty()) return provinceName;
        if (provinceName.equals(cityName)) return provinceName;
        return provinceName + " " + cityName;
    }
}
