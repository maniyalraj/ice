package com.netflix.ice.processor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Months;
import org.joda.time.Weeks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.ice.common.AwsUtils;
import com.netflix.ice.common.TagGroup;
import com.netflix.ice.processor.ProcessorConfig.JsonFiles;
import com.netflix.ice.processor.pricelist.PriceListService;
import com.netflix.ice.reader.InstanceMetrics;
import com.netflix.ice.tag.Product;

public class CostAndUsageData {
    protected Logger logger = LoggerFactory.getLogger(getClass());

    private Map<Product, ReadWriteData> usageDataByProduct;
    private Map<Product, ReadWriteData> costDataByProduct;
    private Map<Product, ReadWriteTagCoverageData> tagCoverage;
    private List<String> userTags;
    
	public CostAndUsageData(List<String> userTags) {
		usageDataByProduct = Maps.newHashMap();
		costDataByProduct = Maps.newHashMap();
		tagCoverage = Maps.newHashMap();
        usageDataByProduct.put(null, new ReadWriteData());
        costDataByProduct.put(null, new ReadWriteData());
		tagCoverage.put(null, new ReadWriteTagCoverageData(userTags == null ? 0 : userTags.size()));
        this.userTags = userTags;
	}
	
	public ReadWriteData getUsage(Product product) {
		return usageDataByProduct.get(product);
	}
	
	public void putUsage(Product product, ReadWriteData data) {
		usageDataByProduct.put(product,  data);
	}
	
	public ReadWriteData getCost(Product product) {
		return costDataByProduct.get(product);
	}
	
	public void putCost(Product product, ReadWriteData data) {
		costDataByProduct.put(product,  data);
	}
	
	public ReadWriteTagCoverageData getTagCoverage(Product product) {
		return tagCoverage.get(product);
	}
	
	public void putTagCoverage(Product product, ReadWriteTagCoverageData data) {
		tagCoverage.put(product,  data);
	}
	
	
	public void putAll(CostAndUsageData data) {
		// Add all the data from the supplied CostAndUsageData
		
		for (Entry<Product, ReadWriteData> entry: data.usageDataByProduct.entrySet()) {
			ReadWriteData usage = getUsage(entry.getKey());
			if (usage == null) {
				usageDataByProduct.put(entry.getKey(), entry.getValue());
			}
			else {
				usage.putAll(entry.getValue());
			}
		}
		for (Entry<Product, ReadWriteData> entry: data.costDataByProduct.entrySet()) {
			ReadWriteData cost = getCost(entry.getKey());
			if (cost == null) {
				costDataByProduct.put(entry.getKey(), entry.getValue());
			}
			else {
				cost.putAll(entry.getValue());
			}
		}		
		for (Entry<Product, ReadWriteTagCoverageData> entry: data.tagCoverage.entrySet()) {
			ReadWriteTagCoverageData tc = getTagCoverage(entry.getKey());
			if (tc == null) {
				tagCoverage.put(entry.getKey(), entry.getValue());
			}
			else {
				tc.putAll(entry.getValue());
			}
		}		
	}
	
    public void cutData(int hours) {
        for (ReadWriteData data: usageDataByProduct.values()) {
            data.cutData(hours);
        }
        for (ReadWriteData data: costDataByProduct.values()) {
            data.cutData(hours);
        }
    }
    
    /**
     * Add an entry to the tag coverage statistics for the given TagGroup
     */
    public void addTagCoverage(Product product, int index, TagGroup tagGroup, boolean[] userTagCoverage) {
    	if (!tagGroup.product.hasResourceTags()) {
    		return;
    	}
    	
    	ReadWriteTagCoverageData data = tagCoverage.get(product);
    	if (data == null) {
    		data = new ReadWriteTagCoverageData(userTags == null ? 0 : userTags.size());
    		tagCoverage.put(product, data);
    	}
    	
    	Map<TagGroup, TagCoverageMetrics> hourData = data.getData(index);    	
    	hourData.put(tagGroup, TagCoverageMetrics.add(hourData.get(tagGroup), userTagCoverage));
    }

    public void archive(long startMilli, DateTime startDate, boolean compress) throws Exception {
        logger.info("archiving tag data...");

        for (Product product: costDataByProduct.keySet()) {
            TagGroupWriter writer = new TagGroupWriter(product == null ? "all" : product.getFileName());
            writer.archive(startMilli, costDataByProduct.get(product).getTagGroups());
        }

        logger.info("archiving summary data...");

        archiveSummary(startMilli, startDate, usageDataByProduct, "usage_", compress);
        archiveSummary(startMilli, startDate, costDataByProduct, "cost_", compress);

        logger.info("archiving hourly data...");

        archiveHourly(startMilli, usageDataByProduct, "usage_", compress);
        archiveHourly(startMilli, costDataByProduct, "cost_", compress);  
        archiveHourlyTagCoverage(startMilli, compress);
    }
    
    public void archiveHourlyJson(long startMilli, JsonFiles writeJsonFiles, InstanceMetrics instanceMetrics, PriceListService priceListService) throws Exception {
        if (writeJsonFiles == JsonFiles.no)
        	return;
        
        logger.info("archiving JSON data...");
        
        DateTime monthDateTime = new DateTime(startMilli, DateTimeZone.UTC);
        DataJsonWriter writer = new DataJsonWriter("hourly_all_" + AwsUtils.monthDateFormat.print(monthDateTime) + ".json",
        		monthDateTime, userTags, writeJsonFiles, costDataByProduct, usageDataByProduct, instanceMetrics, priceListService);
        writer.archive();
    }
    
    private void archiveHourly(long startMilli, Map<Product, ReadWriteData> dataMap, String prefix, boolean compress) throws Exception {
        DateTime monthDateTime = new DateTime(startMilli, DateTimeZone.UTC);
        for (Product product: dataMap.keySet()) {
            String prodName = product == null ? "all" : product.getFileName();
            DataWriter writer = new DataWriter(prefix + "hourly_" + prodName + "_" + AwsUtils.monthDateFormat.print(monthDateTime), dataMap.get(product), compress, false);
            writer.archive();
        }
    }

    private void archiveHourlyTagCoverage(long startMilli, boolean compress) throws Exception {
    	logger.info("archiving tag coverage data... ");
        DateTime monthDateTime = new DateTime(startMilli, DateTimeZone.UTC);
        for (Product product: tagCoverage.keySet()) {
            String prodName = product == null ? "all" : product.getFileName();
	        DataWriter writer = new DataWriter("coverage_hourly_" + prodName + "_" + AwsUtils.monthDateFormat.print(monthDateTime), tagCoverage.get(product), compress, false);
	        writer.archive();
        }
    }

    private void addValue(List<Map<TagGroup, Double>> list, int index, TagGroup tagGroup, double v) {
        Map<TagGroup, Double> map = ReadWriteData.getCreateData(list, index);
        Double existedV = map.get(tagGroup);
        map.put(tagGroup, existedV == null ? v : existedV + v);
    }


    private void archiveSummary(long startMilli, DateTime startDate, Map<Product, ReadWriteData> dataMap, String prefix, boolean compress) throws Exception {

        DateTime monthDateTime = new DateTime(startMilli, DateTimeZone.UTC);

        for (Product product: dataMap.keySet()) {

            String prodName = product == null ? "all" : product.getFileName();
            ReadWriteData data = dataMap.get(product);
            Collection<TagGroup> tagGroups = data.getTagGroups();

            // init daily, weekly and monthly
            List<Map<TagGroup, Double>> daily = Lists.newArrayList();
            List<Map<TagGroup, Double>> weekly = Lists.newArrayList();
            List<Map<TagGroup, Double>> monthly = Lists.newArrayList();

            // get last month data
            ReadWriteData lastMonthData = new ReadWriteData();
            DataWriter writer = new DataWriter(prefix + "hourly_" + prodName + "_" + AwsUtils.monthDateFormat.print(monthDateTime.minusMonths(1)), lastMonthData, compress, true);

            // aggregate to daily, weekly and monthly
            int dayOfWeek = monthDateTime.getDayOfWeek();
            int daysFromLastMonth = dayOfWeek - 1;
            int lastMonthNumHours = monthDateTime.minusMonths(1).dayOfMonth().getMaximumValue() * 24;
            for (int hour = 0 - daysFromLastMonth * 24; hour < data.getNum(); hour++) {
                if (hour < 0) {
                    // handle data from last month, add to weekly
                    Map<TagGroup, Double> prevData = lastMonthData.getData(lastMonthNumHours + hour);
                    for (TagGroup tagGroup: tagGroups) {
                        Double v = prevData.get(tagGroup);
                        if (v != null && v != 0) {
                            addValue(weekly, 0, tagGroup, v);
                        }
                    }
                }
                else {
                    // this month, add to weekly, monthly and daily
                    Map<TagGroup, Double> map = data.getData(hour);

                    for (TagGroup tagGroup: tagGroups) {
                        Double v = map.get(tagGroup);
                        if (v != null && v != 0) {
                            addValue(monthly, 0, tagGroup, v);
                            addValue(daily, hour/24, tagGroup, v);
                            addValue(weekly, (hour + daysFromLastMonth*24) / 24/7, tagGroup, v);
                        }
                    }
                }
            }
            
            // archive daily
            int year = monthDateTime.getYear();
            ReadWriteData dailyData = new ReadWriteData();
            writer = new DataWriter(prefix + "daily_" + prodName + "_" + year, dailyData, compress, true);
            dailyData.setData(daily, monthDateTime.getDayOfYear() -1, false);
            writer.archive();

            // archive monthly
            ReadWriteData monthlyData = new ReadWriteData();
            int numMonths = Months.monthsBetween(startDate, monthDateTime).getMonths();            
            writer = new DataWriter(prefix + "monthly_" + prodName, monthlyData, compress, true);
            monthlyData.setData(monthly, numMonths, false);            
            writer.archive();

            // archive weekly
            DateTime weekStart = monthDateTime.withDayOfWeek(1);
            int index;
            if (!weekStart.isAfter(startDate))
                index = 0;
            else
                index = Weeks.weeksBetween(startDate, weekStart).getWeeks() + (startDate.dayOfWeek() == weekStart.dayOfWeek() ? 0 : 1);
            ReadWriteData weeklyData = new ReadWriteData();
            writer = new DataWriter(prefix + "weekly_" + prodName, weeklyData, compress, true);
            weeklyData.setData(weekly, index, true);
            writer.archive();
        }
    }
}
