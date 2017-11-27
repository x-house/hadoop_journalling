package com.wyu.transformer.mr.nm;

import com.google.common.collect.Lists;
import com.wyu.commom.DateEnum;
import com.wyu.commom.EventLogConstants;
import com.wyu.commom.GlobalConstants;
import com.wyu.transformer.model.dim.base.DateDimension;
import com.wyu.transformer.model.dim.base.StatsUserDimension;
import com.wyu.transformer.model.value.map.TimeOutputValue;
import com.wyu.transformer.model.value.reduce.MapWritableValue;
import com.wyu.transformer.mr.TransformerOutputFormat;
import com.wyu.util.JdbcManager;
import com.wyu.util.TimeUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.MultipleColumnPrefixFilter;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ken
 * @date 2017/11/27
 */
public class NewMemberRunner implements Tool {

    private static final Logger logger = Logger.getLogger(NewMemberRunner.class);
    private Configuration conf = new Configuration();

    public static void main(String[] args) {
        try {
            ToolRunner.run(new NewMemberRunner(),args);
        } catch (Exception e) {
            logger.error("运行new_member出现异常!"+ e);
            throw  new RuntimeException(e);
        }
    }
    @Override
    public int run(String[] args) throws Exception {
        Configuration conf = this.getConf();

        /*处理参数*/
        this.processArgs(conf, args);

        Job job = Job.getInstance(conf, "(new_member");

        job.setJarByClass(NewMemberRunner.class);
        /*本地运行*/
        TableMapReduceUtil.initTableMapperJob(initScan(job), NewMemberMapper.class, StatsUserDimension.class, TimeOutputValue.class, job, false);
        /*集群运行*/
//        TableMapReduceUtil.initTableMapperJob(initScan(job),ActiveMemberMapper.class, StatsUserDimension.class, TimeOutputValue.class,job);

        job.setReducerClass(NewMemberReducer.class);
        job.setOutputKeyClass(StatsUserDimension.class);
        job.setOutputValueClass(MapWritableValue.class);
        job.setOutputFormatClass(TransformerOutputFormat.class);
        // 开始毫秒数
        long startTime = System.currentTimeMillis();
        try {
           if(job.waitForCompletion(true)) {
        	   this.calculateTotalMembers(conf);
        	   return 0;
           }else {
        	   return -1;
           }
        } finally {
            // 结束的毫秒数
            long endTime = System.currentTimeMillis();
            logger.info("Job<" + job.getJobName() + ">是否执行成功:" + job.isSuccessful() + "; 开始时间:" + startTime + "; 结束时间:" + endTime + "; 用时:" + (endTime - startTime) + "ms");
        }
    }

    @Override
    public Configuration getConf() {
        return this.conf;
    }

    @Override
    public void setConf(Configuration conf) {
        conf.addResource("output-collector.xml");
        conf.addResource("transformer-env.xml");
        conf.addResource("query-mapping.xml");
        this.conf = HBaseConfiguration.create(conf);
    }

    
    /**
     * 计算总的会员
     * @param conf
     */
    private void calculateTotalMembers(Configuration conf) {
        Connection conn = null;
        PreparedStatement psmt = null;
        ResultSet rs = null;
        long date = TimeUtil.parseString2Long(conf.get(GlobalConstants.RUNNING_DATE_PARAMES));
        /*获取今天的dimension*/
        DateDimension todayDimension = DateDimension.buildDate(date, DateEnum.DAY);
        /*获取昨天的dimension*/
        DateDimension yesterdayDimension = DateDimension.buildDate(date - GlobalConstants.DAY_OF_MILLISECONDS, DateEnum.DAY);
        int yesterdayDimensionId = -1;
        int todayDimensionId = -1;

        try {
            /*获取执行时间昨天的id*/
            conn = JdbcManager.getConnection(conf, GlobalConstants.WAREHOUSE_OF_REPORT);
            psmt = conn.prepareStatement(" SELECT `id` FROM `dimension_date` WHERE `year` = ? AND `season` = ? AND `month` = ? AND `week` = ? AND `day` = ? AND `type` = ? AND `calendar` = ? ");
            int i = 0;
            psmt.setInt(++i, yesterdayDimension.getYear());
            psmt.setInt(++i, yesterdayDimension.getSeason());
            psmt.setInt(++i, yesterdayDimension.getMonth());
            psmt.setInt(++i, yesterdayDimension.getWeek());
            psmt.setInt(++i, yesterdayDimension.getDay());
            psmt.setString(++i, yesterdayDimension.getType());
            psmt.setDate(++i, new Date(yesterdayDimension.getCalendar().getTime()));
            rs = psmt.executeQuery();
            if (rs.next()) {
                yesterdayDimensionId = rs.getInt(1);

            }

            /*获取执行时间当天的id*/
            psmt = conn.prepareStatement(" SELECT `id` FROM `dimension_date` WHERE `year` = ? AND `season` = ? AND `month` = ? AND `week` = ? AND `day` = ? AND `type` = ? AND `calendar` = ? ");
            i = 0;
            psmt.setInt(++i, todayDimension.getYear());
            psmt.setInt(++i, todayDimension.getSeason());
            psmt.setInt(++i, todayDimension.getMonth());
            psmt.setInt(++i, todayDimension.getWeek());
            psmt.setInt(++i, todayDimension.getDay());
            psmt.setString(++i, todayDimension.getType());
            psmt.setDate(++i, new Date(todayDimension.getCalendar().getTime()));
            rs = psmt.executeQuery();
            if (rs.next()) {
                todayDimensionId = rs.getInt(1);

            }
              /*获取昨天的原始数据,存储格式为: dateid_platformid=totalMembers */
            Map<String, Integer> oldValueMap = new HashMap<>();
            if(yesterdayDimensionId > -1){
                psmt = conn.prepareStatement("select `platform_dimension_id`,`total_members` from `stats_user` where `date_dimension_id`=?");
                psmt.setInt(1, yesterdayDimensionId);
                rs = psmt.executeQuery();
                while (rs.next()) {
                    int platformId = rs.getInt("platform_dimension_id");
                    int totalMembers = rs.getInt("total_members");
                    oldValueMap.put("" + platformId, totalMembers);
                }
            }
            // 添加今天的总用户
            psmt = conn.prepareStatement("select `platform_dimension_id`,`new_members` from `stats_user` where `date_dimension_id`=?");
            psmt.setInt(1, todayDimensionId);
            rs = psmt.executeQuery();
            while (rs.next()) {
                int platformId = rs.getInt("platform_dimension_id");
                int newMembers = rs.getInt("new_members");
                if (oldValueMap.containsKey("" + platformId)) {
                	newMembers += oldValueMap.get("" + platformId);
                }
                oldValueMap.put("" + platformId, newMembers);
            }

            // 更新操作
            psmt = conn.prepareStatement("INSERT INTO `stats_user`(`platform_dimension_id`,`date_dimension_id`,`total_members`) VALUES(?, ?, ?) ON DUPLICATE KEY UPDATE `total_members` = ?");
            for (Map.Entry<String, Integer> entry : oldValueMap.entrySet()) {
                psmt.setInt(1, Integer.valueOf(entry.getKey()));
                psmt.setInt(2, todayDimensionId);
                psmt.setInt(3, entry.getValue());
                psmt.setInt(4, entry.getValue());
                psmt.execute();
            }

            // 开始更新stats_device_browser
            oldValueMap.clear();
            if (yesterdayDimensionId > -1) {
                psmt = conn.prepareStatement("select `platform_dimension_id`,`browser_dimension_id`,`total_members` from `stats_device_browser` where `date_dimension_id`=?");
                psmt.setInt(1, yesterdayDimensionId);
                rs = psmt.executeQuery();
                while (rs.next()) {
                    int platformId = rs.getInt("platform_dimension_id");
                    int browserId = rs.getInt("browser_dimension_id");
                    int totalMembers = rs.getInt("total_members`");
                    oldValueMap.put(platformId + "_" + browserId, totalMembers);
                }
            }

            // 添加今天的总用户
            psmt = conn.prepareStatement("select `platform_dimension_id`,`browser_dimension_id`,`new_members` from `stats_device_browser` where `date_dimension_id`=?");
            psmt.setInt(1, todayDimensionId);
            rs = psmt.executeQuery();
            while (rs.next()) {
                int platformId = rs.getInt("platform_dimension_id");
                int browserId = rs.getInt("browser_dimension_id");
                int newMembers = rs.getInt("new_members");
                String key = platformId + "_" + browserId;
                if (oldValueMap.containsKey(key)) {
                	newMembers += oldValueMap.get(key);
                }
                oldValueMap.put(key, newMembers);
            }

            // 更新操作
            psmt = conn.prepareStatement("INSERT INTO `stats_device_browser`(`platform_dimension_id`,`browser_dimension_id`,`date_dimension_id`,`total_members`) VALUES(?, ?, ?, ?) ON DUPLICATE KEY UPDATE `total_members` = ?");
            for (Map.Entry<String, Integer> entry : oldValueMap.entrySet()) {
                String[] key = entry.getKey().split("_");
                psmt.setInt(1, Integer.valueOf(key[0]));
                psmt.setInt(2, Integer.valueOf(key[1]));
                psmt.setInt(3, todayDimensionId);
                psmt.setInt(4, entry.getValue());
                psmt.setInt(5, entry.getValue());
                psmt.execute();
            }
        } catch (Exception e) {
            logger.error("执行统计总用户数发生异常!" + e);
            throw  new RuntimeException("执行统计总用户数发生异常!",e);

        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
                if (psmt != null) {
                    psmt.close();
                }
                if (conn != null) {
                    conn.close();
                }
            } catch (Exception e) {
                //nothing
            }
        }
    }
    /**
     * 处理参数
     *
     * @param conf
     * @param args
     */
    private void processArgs(Configuration conf, String[] args) {
        String date = null;
        for (int i = 0; i < args.length; i++) {
            if ("-d".equals(args[i])) {
                if (i + 1 < args.length) {
                    date = args[++i];
                    break;
                }
            }
        }

        if (StringUtils.isBlank(date) || !TimeUtil.isValidateRunningDate(date)) {
            date = TimeUtil.getYesterday();
        }

        conf.set(GlobalConstants.RUNNING_DATE_PARAMES, date);
    }

    /**
     * 初始化scan集合
     *
     * @param job
     * @return
     */
    private List<Scan> initScan(Job job) {

        Configuration conf = job.getConfiguration();
        /*获取运行时间: yyyy-MM-dd*/
        String date = conf.get(GlobalConstants.RUNNING_DATE_PARAMES);
        long startDate = TimeUtil.parseString2Long(date);
        long endDate = startDate + GlobalConstants.DAY_OF_MILLISECONDS;

        Scan scan = new Scan();

        scan.setStopRow(Bytes.toBytes(endDate + ""));
        scan.setStartRow(Bytes.toBytes(startDate + ""));

        FilterList filterList = new FilterList();
        /*过滤数据.只分析launch事件*/
        String[] columns = new String[]{
                EventLogConstants.LOG_COLUMN_NAME_MEMBER_ID,
                EventLogConstants.LOG_COLUMN_NAME_SERVER_TIME,
                EventLogConstants.LOG_COLUMN_NAME_PALTFORM,
                EventLogConstants.LOG_COLUMN_NAME_BROWSER_NAME,
                EventLogConstants.LOG_COLUMN_NAME_BROWSER_VERSION
        };
        filterList.addFilter(getColumnFilter(columns));


        scan.setFilter(filterList);
        scan.setAttribute(Scan.SCAN_ATTRIBUTES_TABLE_NAME, Bytes.toBytes(EventLogConstants.HBASE_NAME_EVENT_LOGS));
        return Lists.newArrayList(scan);
    }

    /**
     * 获取列名过滤column
     *
     * @param columns
     * @return
     */
    private Filter getColumnFilter(String[] columns) {
        int length = columns.length;
        byte[][] filter = new byte[length][];
        for (int i = 0; i < length; i++) {
            filter[i] = Bytes.toBytes(columns[i]);
        }
        return new MultipleColumnPrefixFilter(filter);
    }
}
