package lk.sunshine.smartcare.features.report;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class XlsxReportExporter implements ReportExporter {
    public String format() { return "xlsx"; }
    public String contentType() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
    public byte[] export(Map<String,Object> report) throws IOException {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("SmartCare report");int row=0;
            if(report.get("rows") instanceof List<?> rows) {
                for(String key:List.of("report_type","from","to","group_by")){Row r=sheet.createRow(row++);r.createCell(0).setCellValue(key);r.createCell(1).setCellValue(String.valueOf(report.get(key)));}
                if(report.get("summary_metrics") instanceof Map<?,?> metrics)for(var entry:metrics.entrySet()){Row r=sheet.createRow(row++);r.createCell(0).setCellValue(String.valueOf(entry.getKey()));cell(r,1,entry.getValue());}
                List<String> columns=ReportPresentation.strings(report.get("display_columns"),List.of());
                Row header=sheet.createRow(++row);row++;for(int i=0;i<columns.size();i++)header.createCell(i).setCellValue(columns.get(i));
                for(Object item:rows){Map<?,?> values=(Map<?,?>)item;Row r=sheet.createRow(row++);for(int i=0;i<columns.size();i++)cell(r,i,values.get(columns.get(i)));}
                for(int i=0;i<Math.max(2,columns.size());i++){sheet.autoSizeColumn(i);sheet.setColumnWidth(i,Math.min(sheet.getColumnWidth(i),16000));}
                book.write(out);return out.toByteArray();
            }
            String type=String.valueOf(report.get("report_type"));
            List<String> fields=switch(type) {
                case "gross_revenue" -> List.of("report_type","from","to","branch_id","doctor_id","gross_simulated_revenue","refunded_simulated_amount","simulated_revenue","demo_payment_count");
                case "refund_cancellation" -> List.of("report_type","from","to","branch_id","doctor_id","bookings","cancellations","cancellation_rate_percent","demo_payment_count","refund_count","refund_rate_percent","refunded_simulated_amount");
                case "doctor_revenue" -> List.of("report_type","from","to","branch_id","doctor_id","bookings","gross_simulated_revenue","refunded_simulated_amount","simulated_revenue");
                default -> List.of("report_type","from","to","branch_id","doctor_id","bookings","cancellations","recent_cancelled_count","no_shows","booked_estimated_fees","completed_estimated_fees","simulated_revenue","average_wait_minutes");
            };
            for(String key:fields) { Row r=sheet.createRow(row++);r.createCell(0).setCellValue(key);r.createCell(1).setCellValue(String.valueOf(report.get(key))); }
            if("refund_cancellation".equals(type)) {
                row++;Row header=sheet.createRow(row++);
                String[] keys={"appointment_id","patient_name","doctor_name","branch_name","booked_on","starts_at","cancelled_on","payment_status","refunded_simulated_amount"};
                for(int i=0;i<keys.length;i++)header.createCell(i).setCellValue(keys[i]);
                for(Object item:(List<?>)report.get("cancelled_appointments")) { Map<?,?> appointment=(Map<?,?>)item;Row r=sheet.createRow(row++);for(int i=0;i<keys.length;i++)r.createCell(i).setCellValue(String.valueOf(appointment.get(keys[i]))); }
            } else if("doctor_revenue".equals(type) || "summary".equals(type)) {
                row++;Row header=sheet.createRow(row++);
                String[] keys="doctor_revenue".equals(type)?new String[]{"doctor_name","branch_name","bookings","gross_simulated_revenue","refunded_simulated_amount","simulated_revenue"}:new String[]{"doctor_name","branch_name","total_slots","used_slots","simulated_revenue"};
                for(int i=0;i<keys.length;i++)header.createCell(i).setCellValue(keys[i]);
                for(Object item:(List<?>)report.get("doctor_revenue".equals(type)?"doctor_revenue":"doctors")) { Map<?,?> doctor=(Map<?,?>)item;Row r=sheet.createRow(row++);for(int i=0;i<keys.length;i++)r.createCell(i).setCellValue(String.valueOf(doctor.get(keys[i]))); }
            }
            if("summary".equals(type)) {
                row++;Row header=sheet.createRow(row++);
                String[] keys={"appointment_id","patient_name","doctor_name","branch_name","booked_on","starts_at","cancelled_on","payment_status","refunded_simulated_amount"};
                for(int i=0;i<keys.length;i++)header.createCell(i).setCellValue(keys[i]);
                for(Object item:(List<?>)report.get("cancelled_appointments")) { Map<?,?> appointment=(Map<?,?>)item;Row r=sheet.createRow(row++);for(int i=0;i<keys.length;i++)r.createCell(i).setCellValue(String.valueOf(appointment.get(keys[i]))); }
            }
            sheet.autoSizeColumn(0);sheet.autoSizeColumn(1);book.write(out);return out.toByteArray();
        }
    }
    private void cell(Row row,int index,Object value){var cell=row.createCell(index);if(value instanceof Number n)cell.setCellValue(n.doubleValue());else cell.setCellValue(value==null?"":value.toString());}
}
