package lk.sunshine.smartcare.features.report;

import java.io.IOException;
import java.util.Map;

public interface ReportExporter {
    String format();
    String contentType();
    byte[] export(Map<String,Object> report) throws IOException;
}
