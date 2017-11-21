package com.wyu.etl.util;

import com.wyu.commom.EventLogConstants;
import com.wyu.etl.util.IPSeekerExt.RegionInfo;
import com.wyu.etl.util.UserAnentUtil.UserAgentInfo;
import com.wyu.util.TimeUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 处理日志数据的具体工具类
 *
 * @author ken
 */
public class LoggerUtil {

    private static final Logger logger = Logger.getLogger(LoggerUtil.class);
    private static IPSeekerExt ipSeekerExt = new IPSeekerExt();

    /**
     * 处理日志数据logText，返回处理结果map集合
     * 如果logText没有指定数据格式，那么直接返回空集合
     *
     * @param logText
     * @return
     */
    public static Map<String, String> handleLog(String logText) {
        Map<String, String> clientInfo = new HashMap<>(16);
        if (StringUtils.isNotBlank(logText)) {
            String[] splits = logText.trim().split(EventLogConstants.LOG_SEPARTIOR);
            if (splits.length == 4) {
                /*
                  日志格式为：ip^A服务器时间^Ahost^A请求参数
                 */
                clientInfo.put(EventLogConstants.LOG_COLUMN_NAME_IP, splits[0].trim());
                /* 设置服务器时间*/
                clientInfo.put(EventLogConstants.LOG_COLUMN_NAME_SERVER_TIME, String.valueOf(TimeUtil.parseNginxServerTime2Long(splits[1].trim())));

                int index = splits[3].indexOf("?");
                if (index > -1) {
                    /*获取请求参数*/
                    String requestBody = splits[3].substring(index + 1);

                    /*以下是处理请求参数*/
                    handleRequestBody(requestBody, clientInfo);

                    /*处理UserAgent*/
                    handleUserAgent(clientInfo);

                    /*处理ip地址*/
                    handleIp(clientInfo);

                } else {
                    /*数据格式异常*/
                    clientInfo.clear();
                }

            }
        }
        return clientInfo;

    }

    /**
     * 处理ip地址
     *
     * @param clientInfo
     */
    private static void handleIp(Map<String,String> clientInfo) {
        if (clientInfo.containsKey(EventLogConstants.LOG_COLUMN_NAME_IP)) {
            String ip = clientInfo.get(EventLogConstants.LOG_COLUMN_NAME_IP);
            RegionInfo info = ipSeekerExt.analyticIp(ip);
            if (info != null) {
                clientInfo.put(EventLogConstants.LOG_COLUMN_NAME_COUNTRY, info.getCountry());
                clientInfo.put(EventLogConstants.LOG_COLUMN_NAME_PROVINCE, info.getProvince());
                clientInfo.put(EventLogConstants.LOG_COLUMN_NAME_CITY, info.getCity());
            }
        }
    }

    /**
     * 处理浏览器相关信息的方法
     * @param clientInfo
     */
    private static void handleUserAgent(Map<String ,String> clientInfo){
        if(clientInfo.containsKey(EventLogConstants.LOG_COLUMN_NAME_USER_AGENT)){
            UserAgentInfo info = UserAnentUtil.analyticUserAgent(clientInfo.get(EventLogConstants.LOG_COLUMN_NAME_USER_AGENT));
            if(info != null){
                clientInfo.put(EventLogConstants.LOG_COLUMN_NAME_OS_NAME,info.getOsName());
                clientInfo.put(EventLogConstants.LOG_COLUMN_NAME_OS_VERSION,info.getOsVersion());
                clientInfo.put(EventLogConstants.LOG_COLUMN_NAME_BROWSER_NAME,info.getBrowserName());
                clientInfo.put(EventLogConstants.LOG_COLUMN_NAME_BROWSER_VERSION,info.getBrowserVersion());
            }
        }
    }

    /**
     * 处理请求参数方法
     *
     * @param requestBody
     */
    private static void handleRequestBody(String requestBody, Map<String, String> clientInfo) {
        if (StringUtils.isNotBlank(requestBody)) {
            String[] requestParams = requestBody.split("&");
            for (String param : requestParams) {
                if (StringUtils.isNotBlank(param)) {
                    int index = param.indexOf("=");
                    if (index < 0) {
                        logger.warn("没法进行解析参数：" + param + ",请求参数为：" + requestBody);
                        continue;
                    }
                    String key, value;
                    try {
                        key = param.substring(0, index);
                        value = URLDecoder.decode(param.substring(index + 1), "utf-8");
                    } catch (Exception e) {
                        logger.error("解码异常");
                        continue;
                    }
                    if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)){
                        clientInfo.put(key,value);
                    }
                }
            }
        }
    }

}
