package com.javaee.docai.ai.aiops;

import com.javaee.docai.ai.internal.InternalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 故障检测器
 * 自动检测系统故障和异常
 * 支持自动故障处理和告警
 */
@Component
public class FaultDetector {

    private static final Logger log = LoggerFactory.getLogger(FaultDetector.class);

    @Autowired
    private MonitoringService monitoringService;

    @Autowired
    private InternalService internalService;

    private final ConcurrentHashMap<String, FaultRecord> faultRecords = new ConcurrentHashMap<>();

    /**
     * 故障记录
     */
    private static class FaultRecord {
        String type;
        String message;
        long firstOccurrence;
        long lastOccurrence;
        int count;
        String status;

        FaultRecord(String type, String message) {
            this.type = type;
            this.message = message;
            this.firstOccurrence = System.currentTimeMillis();
            this.lastOccurrence = System.currentTimeMillis();
            this.count = 1;
            this.status = "detected";
        }
    }

    /**
     * 检测故障
     * @return 故障列表
     */
    public List<Map<String, Object>> detectFaults() {
        log.info("开始故障检测");

        List<Map<String, Object>> faults = new ArrayList<>();

        faults.addAll(detectPerformanceIssues());
        faults.addAll(detectThresholdBreaches());

        log.info("故障检测完成，发现{}个故障", faults.size());
        return faults;
    }

    /**
     * 检测性能问题
     */
    private List<Map<String, Object>> detectPerformanceIssues() {
        List<Map<String, Object>> issues = new ArrayList<>();

        Map<String, Object> timerStats = monitoringService.getTimerStats("ai.request");
        // 使用Number类型安全地获取数值并转换为double
        Number avgTimeNum = (Number) timerStats.get("avg");
        double avgTime = avgTimeNum != null ? avgTimeNum.doubleValue() : 0.0;
        
        if (avgTime > 5000) {
            Map<String, Object> fault = createFault(
                "PERFORMANCE_DEGRADED",
                "AI请求平均响应时间超过5秒: " + avgTime + "ms"
            );
            issues.add(fault);
        }

        return issues;
    }

    /**
     * 检测阈值突破
     */
    private List<Map<String, Object>> detectThresholdBreaches() {
        List<Map<String, Object>> issues = new ArrayList<>();

        long errorCount = monitoringService.getCounter("ai.errors");
        long totalCount = monitoringService.getCounter("ai.requests");

        if (totalCount > 0 && (double) errorCount / totalCount > 0.1) {
            Map<String, Object> fault = createFault(
                "ERROR_RATE_HIGH",
                "错误率超过10%: " + errorCount + "/" + totalCount
            );
            issues.add(fault);
        }

        return issues;
    }

    /**
     * 创建故障记录
     */
    private Map<String, Object> createFault(String type, String message) {
        String faultId = type + ":" + System.currentTimeMillis();
        
        FaultRecord record = new FaultRecord(type, message);
        faultRecords.put(faultId, record);

        // 发送告警
        sendAlert(faultId, type, message);

        return Map.of(
            "faultId", faultId,
            "type", type,
            "message", message,
            "status", "detected",
            "timestamp", System.currentTimeMillis()
        );
    }

    /**
     * 发送告警
     */
    private void sendAlert(String faultId, String type, String message) {
        log.warn("发送告警: faultId={}, type={}, message={}", faultId, type, message);
        
        // 通过MCP发送告警通知
        Map<String, Object> alertParams = new HashMap<>();
        alertParams.put("faultId", faultId);
        alertParams.put("type", type);
        alertParams.put("message", message);
        alertParams.put("timestamp", System.currentTimeMillis());
        
        internalService.sendAlert(alertParams);
    }

    /**
     * 处理故障
     * @param faultId 故障ID
     * @return 处理结果
     */
    public Map<String, Object> resolveFault(String faultId) {
        log.info("处理故障: faultId={}", faultId);

        FaultRecord record = faultRecords.get(faultId);
        if (record == null) {
            return Map.of(
                "status", "error",
                "message", "故障不存在"
            );
        }

        record.status = "resolved";
        record.lastOccurrence = System.currentTimeMillis();

        return Map.of(
            "status", "success",
            "faultId", faultId,
            "type", record.type,
            "message", "故障已处理"
        );
    }

    /**
     * 获取所有故障记录
     * @return 故障记录列表
     */
    public List<Map<String, Object>> getAllFaults() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map.Entry<String, FaultRecord> entry : faultRecords.entrySet()) {
            FaultRecord record = entry.getValue();
            result.add(Map.of(
                "faultId", entry.getKey(),
                "type", record.type,
                "message", record.message,
                "firstOccurrence", record.firstOccurrence,
                "lastOccurrence", record.lastOccurrence,
                "count", record.count,
                "status", record.status
            ));
        }

        return result;
    }

    /**
     * 定时检测（每分钟执行一次）
     */
    @Scheduled(fixedRate = 60000)
    public void scheduledDetection() {
        detectFaults();
    }
}
