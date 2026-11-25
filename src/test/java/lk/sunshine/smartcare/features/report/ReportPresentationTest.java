package lk.sunshine.smartcare.features.report;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReportPresentationTest {
    private Map<String,Object> row(long doctor,long branch,String name,double net,double gross) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("doctor_id",doctor);row.put("doctor","Doctor "+doctor);row.put("branch_id",branch);row.put("branch",name);
        row.put("specialty_id",1L);row.put("specialty","Cardiology");row.put("department","Outpatient");
        row.put("appointments",1);row.put("net_revenue",net);row.put("gross_revenue",gross);row.put("total_slots",2);row.put("used_slots",1);return row;
    }
    private Map<String,Object> report() {
        Map<String,Object> report=new LinkedHashMap<>();report.put("report_type","summary");report.put("from","2026-09-01");report.put("to","2026-09-30");
        report.put("bookings",3);report.put("simulated_revenue",300);report.put("gross_simulated_revenue",400);
        ReportPresentation.apply(report,Map.of("groupBy","branch","metrics",List.of("appointments","gross_revenue"),"columns",List.of("branch","net_revenue")),
            List.of(row(1,1,"Central",100,100),row(2,1,"Central",200,200),row(1,2,"North",0,100)),List.of(row(1,1,"Central",150,150)));
        return report;
    }
    @Test void groupingSumsAcrossDoctorsAndUsesPriorGroupForGrowth() {
        Map<String,Object> report=report();List<?> rows=(List<?>)report.get("rows");assertThat(rows).hasSize(2);
        Map<?,?> central=(Map<?,?>)rows.get(0);assertThat(central.get("label")).isEqualTo("Central");
        assertThat(central.get("net_revenue")).isEqualTo(300.0);assertThat(central.get("appointments")).isEqualTo(2.0);
        assertThat(central.get("growth")).isEqualTo(100.0);assertThat(central.get("utilization")).isEqualTo(50.0);
        assertThat(((Map<?,?>)report.get("summary_metrics")).keySet().stream().map(Object::toString).toList()).containsExactly("appointments","gross_revenue");
        assertThat(((Map<?,?>)rows.get(1)).get("net_revenue")).isEqualTo(0.0);
    }
    @Test void sortingAscendingAndGroupingByDoctorCombinesBranches() {
        Map<String,Object> report=new LinkedHashMap<>();
        ReportPresentation.apply(report,Map.of("groupBy","doctor","sortDirection","ASC"),List.of(row(1,1,"Central",20,20),row(1,2,"North",30,30),row(2,1,"Central",10,10)),List.of());
        List<?> rows=(List<?>)report.get("rows");assertThat(rows).hasSize(2);assertThat(((Map<?,?>)rows.get(0)).get("label")).isEqualTo("Doctor 2");assertThat(((Map<?,?>)rows.get(1)).get("net_revenue")).isEqualTo(50.0);
    }
    @Test void allExportFormatsContainSelectedGroupedData() throws Exception {
        Map<String,Object> report=report();
        String csv=new String(new CsvReportExporter().export(report),StandardCharsets.UTF_8);
        assertThat(csv).contains("\"branch\",\"net_revenue\"","\"Central\",\"300.0\"").doesNotContain("Doctor 1");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new XlsxReportExporter().export(report)))) {
            var sheet=book.getSheetAt(0);boolean found=false;
            for(var row:sheet)if(row.getCell(0)!=null&&"Central".equals(row.getCell(0).toString())){assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(300.0);found=true;}
            assertThat(found).isTrue();
        }
        try(var pdf=Loader.loadPDF(new PdfReportExporter().export(report))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("Grouped by: branch","Central","net revenue: 300.0").doesNotContain("Doctor 1");
        }
    }
}
