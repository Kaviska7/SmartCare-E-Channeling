package lk.sunshine.smartcare.features.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
public class LoggingReportDeliveryService implements ReportDeliveryService {
    private static final Logger log=LoggerFactory.getLogger(LoggingReportDeliveryService.class);

    @Override public void deliver(String channel,List<String> recipients,Path file,String reportName) {
        log.info("Simulated {} delivery of report '{}' ({}) to {} recipient(s)",channel,reportName,file.getFileName(),recipients.size());
    }
}
