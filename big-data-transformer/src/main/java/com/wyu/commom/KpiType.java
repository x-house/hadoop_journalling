package com.wyu.commom;

/**
 * 统计kpi的枚举
 * @author ken
 */
public enum KpiType {
    //统计新用户的枚举
    NEW_INSTALL_USER("new_install_user"),
    //统计浏览器维度的新用户kpi枚举
    BROWSER_NEW_INSTALL_USER("browser_new_install_user"),
    //统计活跃用户的枚举
    ACTIVE_USER("active_user"),
    //统计浏览器维度的活跃用户kpi枚举
    BROWSER_ACTIVE_USER("browser_active_user");



    public final String name;

    KpiType(String name) {
        this.name = name;
    }
    

    /**
     * 根据kpiType的名称字符串值，获取对应的kpitype枚举对象
     * 
     * @param name
     * @return
     */
    public static KpiType valueOfName(String name) {
        for (KpiType type : values()) {
            if (type.name.equals(name)) {
                return type;
            }
        }
        throw new RuntimeException("指定的name不属于该KpiType枚举类：" + name);
    }
}
