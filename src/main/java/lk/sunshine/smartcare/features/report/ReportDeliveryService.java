package lk.sunshine.smartcare.features.report;

import java.nio.file.Path;
import java.util.List;

public interface ReportDeliveryService {
    void deliver(String channel,List<String> recipients,Path file,String reportName);
}
