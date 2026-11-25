package lk.sunshine.smartcare.features.report;

import java.util.*;

/** Shared projection for browser previews, stored reports, and exports. */
public final class ReportPresentation {
    private static final List<String> SUMS=List.of("appointments","cancellations","no_shows","gross_revenue","refunded_revenue","net_revenue","total_slots","used_slots");
    private static final Map<String,String> TOTAL_KEYS=Map.of("appointments","bookings","gross_revenue","gross_simulated_revenue","refunded_revenue","refunded_simulated_amount","net_revenue","simulated_revenue","growth","growth_percent");
    private ReportPresentation() {}
    public static void apply(Map<String,Object> report,Map<String,Object> config,List<Map<String,Object>> details,List<Map<String,Object>> previous) {
        String group=String.valueOf(config.getOrDefault("groupBy","doctor"));
        Map<String,Map<String,Object>> groups=group(details,group),old=group(previous,group);
        List<Map<String,Object>> rows=new ArrayList<>(groups.values());
        for(var entry:groups.entrySet()) {
            Map<String,Object> row=entry.getValue();double prior=number(old.getOrDefault(entry.getKey(),Map.of()),"net_revenue"),current=number(row,"net_revenue");
            row.put("growth",prior==0?(current==0?0.0:100.0):round((current-prior)*100/prior));
            row.put("channel_fees",round(number(row,"gross_revenue")*0.1));
            row.put("utilization",number(row,"total_slots")==0?0:round(number(row,"used_slots")*100/number(row,"total_slots")));
        }
        String sort=String.valueOf(config.getOrDefault("sortField","net_revenue"));
        Comparator<Map<String,Object>> order=Comparator.comparingDouble(row->number(row,sort));
        if(!"ASC".equals(config.getOrDefault("sortDirection","DESC")))order=order.reversed();
        rows.sort(order.thenComparing(row->String.valueOf(row.get("label"))));
        List<String> columns=new ArrayList<>(strings(config.get("columns"),List.of(group,"appointments","net_revenue","growth")));
        if(!columns.contains(group))columns.add(0,group);
        Map<String,Object> metrics=new LinkedHashMap<>();
        for(String metric:strings(config.get("metrics"),List.of("net_revenue","appointments","growth"))) {
            if("utilization".equals(metric)) {
                double total=rows.stream().mapToDouble(row->number(row,"total_slots")).sum(),used=rows.stream().mapToDouble(row->number(row,"used_slots")).sum();metrics.put(metric,total==0?0:round(100*used/total));
            } else metrics.put(metric,report.getOrDefault(TOTAL_KEYS.getOrDefault(metric,metric),0));
        }
        report.put("configuration",config);report.put("rows",rows);report.put("display_columns",columns);report.put("summary_metrics",metrics);report.put("group_by",group);
    }
    private static Map<String,Map<String,Object>> group(List<Map<String,Object>> source,String group) {
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(Map<String,Object> item:source) {
            String key=String.valueOf(item.getOrDefault(group+"_id",item.get(group)));
            Map<String,Object> row=result.computeIfAbsent(key,k->{Map<String,Object> value=new LinkedHashMap<>();value.put("key",group+":"+k);value.put("label",item.get(group));return value;});
            for(String dimension:List.of("doctor","branch","specialty","department")) {
                String value=Objects.toString(item.get(dimension),"");if(!row.containsKey(dimension))row.put(dimension,value);else if(!Objects.equals(row.get(dimension),value))row.put(dimension,"Multiple");
            }
            for(String field:SUMS)row.put(field,number(row,field)+number(item,field));
        }
        return result;
    }
    public static List<String> strings(Object value,List<String> fallback){return value instanceof List<?> list?list.stream().map(String::valueOf).toList():fallback;}
    public static double number(Map<String,Object> row,String key){return row.get(key) instanceof Number n?n.doubleValue():0;}
    private static double round(double value){return Math.round(value*100.0)/100.0;}
}
