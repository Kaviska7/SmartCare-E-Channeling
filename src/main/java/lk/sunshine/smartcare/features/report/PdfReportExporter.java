package lk.sunshine.smartcare.features.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class PdfReportExporter implements ReportExporter {
    private record PrintLine(int size,String text) {}
    public String format() { return "pdf"; }
    public String contentType() { return "application/pdf"; }

    public byte[] export(Map<String,Object> report) throws IOException {
        String type=String.valueOf(report.get("report_type"));
        List<PrintLine> lines=new ArrayList<>();
        Map<?,?> configuration=report.get("configuration") instanceof Map<?,?> value?value:Map.of();
        String heading=String.valueOf(configuration.containsKey("headerTitle")?configuration.get("headerTitle"):"SmartCare "+type.replace('_',' ')+" report");
        String subtitle=String.valueOf(configuration.containsKey("subtitle")?configuration.get("subtitle"):"");
        lines.add(new PrintLine(16,heading));
        if(!subtitle.isBlank())lines.add(new PrintLine(10,subtitle));
        lines.add(new PrintLine(10,"SIMULATED revenue - no real payments processed"));
        lines.add(new PrintLine(10,("refund_cancellation".equals(type)?"Booking dates: ":"Visit dates: ")+report.get("from")+" to "+report.get("to")));
        lines.add(new PrintLine(10,"Branch ID: "+(report.get("branch_id")==null?"All":report.get("branch_id"))+" | Doctor ID: "+(report.get("doctor_id")==null?"All":report.get("doctor_id"))));
        if(report.get("rows") instanceof List<?> rows) {
            lines.add(new PrintLine(11,"Grouped by: "+report.get("group_by")));
            if(report.get("summary_metrics") instanceof Map<?,?> metrics)metrics.forEach((key,value)->lines.add(new PrintLine(11,key+": "+value)));
            List<String> columns=ReportPresentation.strings(report.get("display_columns"),List.of());
            for(Object item:rows) {
                Map<?,?> row=(Map<?,?>)item;lines.add(new PrintLine(12,String.valueOf(row.get("label"))));
                for(String column:columns)lines.add(new PrintLine(9,column.replace('_',' ')+": "+row.get(column)));
            }
        } else {
        List<String> keys=switch(type) {
            case "gross_revenue" -> List.of("gross_simulated_revenue","refunded_simulated_amount","simulated_revenue","demo_payment_count");
            case "refund_cancellation" -> List.of("bookings","cancellations","cancellation_rate_percent","demo_payment_count","refund_count","refund_rate_percent","refunded_simulated_amount");
            case "doctor_revenue" -> List.of("bookings","gross_simulated_revenue","refunded_simulated_amount","simulated_revenue");
            default -> List.of("bookings","cancellations","recent_cancelled_count","no_shows","booked_estimated_fees","completed_estimated_fees","simulated_revenue","average_wait_minutes");
        };
        for(String key:keys)lines.add(new PrintLine(11,key.replace('_',' ')+": "+report.get(key)));
        if("refund_cancellation".equals(type)) {
            List<?> cancelled=(List<?>)report.get("cancelled_appointments");
            lines.add(new PrintLine(12,"Cancelled appointments ("+cancelled.size()+")"));
            for(Object row:cancelled) {
                Map<?,?> a=(Map<?,?>)row;
                lines.add(new PrintLine(9,"#"+a.get("appointment_id")+" "+a.get("patient_name")+" | "+a.get("doctor_name")+" | "+a.get("branch_name")));
                lines.add(new PrintLine(9,"Booked "+a.get("booked_on")+" | Visit "+a.get("starts_at")+" | Cancelled "+a.get("cancelled_on")+" | Refund "+a.get("refunded_simulated_amount")));
            }
        } else if("doctor_revenue".equals(type)) {
            lines.add(new PrintLine(12,"Doctor revenue by branch"));
            for(Object row:(List<?>)report.get("doctor_revenue")) { Map<?,?> d=(Map<?,?>)row;lines.add(new PrintLine(9,d.get("doctor_name")+" | "+d.get("branch_name")+" | gross "+d.get("gross_simulated_revenue")+" | refund "+d.get("refunded_simulated_amount")+" | net "+d.get("simulated_revenue"))); }
        } else if("summary".equals(type)) {
            lines.add(new PrintLine(12,"Doctor utilization"));
            for(Object row:(List<?>)report.get("doctors")) { Map<?,?> d=(Map<?,?>)row;lines.add(new PrintLine(9,d.get("doctor_name")+" | slots "+d.get("total_slots")+" | used "+d.get("used_slots")+" | simulated LKR "+d.get("simulated_revenue"))); }
            lines.add(new PrintLine(12,"Cancelled bookings by booking date"));
            for(Object row:(List<?>)report.get("cancelled_appointments")) { Map<?,?> a=(Map<?,?>)row;lines.add(new PrintLine(9,"#"+a.get("appointment_id")+" "+a.get("patient_name")+" | "+a.get("doctor_name")+" | "+a.get("branch_name")));lines.add(new PrintLine(9,"Booked "+a.get("booked_on")+" | Visit "+a.get("starts_at")+" | Cancelled "+a.get("cancelled_on")+" | Refund "+a.get("refunded_simulated_amount"))); }
        }
        }
        try(PDDocument doc=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            PDType1Font font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPageContentStream stream=null;int y=790;
            try {
                for(PrintLine item:lines) {
                    if(stream==null || y<55) { if(stream!=null)stream.close();doc.addPage(new PDPage(PDRectangle.A4));stream=new PDPageContentStream(doc,doc.getPage(doc.getNumberOfPages()-1));y=790; }
                    stream.beginText();stream.setFont(font,item.size());stream.newLineAtOffset(40,y);
                    stream.showText(item.text().replaceAll("[^\\x20-\\x7E]","?"));stream.endText();y-=item.size()+9;
                }
            } finally { if(stream!=null)stream.close(); }
            doc.save(out);return out.toByteArray();
        }
    }
}

