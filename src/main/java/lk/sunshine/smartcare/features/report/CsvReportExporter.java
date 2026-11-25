package lk.sunshine.smartcare.features.report;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CsvReportExporter implements ReportExporter {
    public String format(){return "csv";}
    public String contentType(){return "text/csv;charset=UTF-8";}

    public byte[] export(Map<String,Object> report){
        List<String> lines=new ArrayList<>();lines.add("Field,Value");
        if(report.get("rows") instanceof List<?> rows) {
            for(String field:List.of("report_type","from","to","group_by"))lines.add(csv(field)+","+csv(report.get(field)));
            if(report.get("summary_metrics") instanceof Map<?,?> metrics)metrics.forEach((key,value)->lines.add(csv(key)+","+csv(value)));
            List<String> columns=ReportPresentation.strings(report.get("display_columns"),List.of());
            lines.add("");lines.add(String.join(",",columns.stream().map(this::csv).toList()));
            for(Object item:rows){Map<?,?> row=(Map<?,?>)item;lines.add(String.join(",",columns.stream().map(key->csv(row.get(key))).toList()));}
            return ("\uFEFF"+String.join("\r\n",lines)+"\r\n").getBytes(StandardCharsets.UTF_8);
        }
        for(String key:List.of("report_type","from","to","bookings","cancellations","no_shows","gross_simulated_revenue","refunded_simulated_amount","simulated_revenue","channel_fees","growth_percent"))
            if(report.containsKey(key))lines.add(csv(key)+","+csv(report.get(key)));
        Object rows=report.get("doctor_revenue");if(!(rows instanceof List<?>))rows=report.get("doctors");
        if(rows instanceof List<?> list&&!list.isEmpty()&&list.get(0) instanceof Map<?,?> first){lines.add("");List<String> headers=first.keySet().stream().map(String::valueOf).toList();lines.add(String.join(",",headers.stream().map(this::csv).toList()));for(Object item:list){Map<?,?> row=(Map<?,?>)item;lines.add(String.join(",",headers.stream().map(key->csv(row.get(key))).toList()));}}
        return ("\uFEFF"+String.join("\r\n",lines)+"\r\n").getBytes(StandardCharsets.UTF_8);
    }
    private String csv(Object value){String text=String.valueOf(value==null?"":value);return "\""+text.replace("\"","\"\"")+"\"";}
}
