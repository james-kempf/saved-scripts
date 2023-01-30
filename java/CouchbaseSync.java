import com.couchbase.client.core.env.TimeoutConfig;
import com.couchbase.client.core.error.AmbiguousTimeoutException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.json.JsonObject;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class CouchbaseSync {

	public static void main(String[] args) {
		ClusterEnvironment env = ClusterEnvironment.builder()
				.timeoutConfig(TimeoutConfig.kvTimeout(Duration.ofMinutes(10))).build();
		Cluster cluster0 = Cluster.connect("LOCAL", "USERNAME", "PASSWORD");
		Cluster cluster1 = Cluster.connect("DEV",
				ClusterOptions.clusterOptions("USERNAME", "PASSWORD").environment(env));
		Collection collection0 = cluster0.bucket("BUCKET").defaultCollection();
		int pageSize = 10000;
		int pageNumber = 1;
		boolean completed = false;
		String queryFormat = "SELECT `BUCKET`.* FROM `BUCKET` WHERE document_type IS NOT MISSING LIMIT {0} OFFSET {1}";
		AtomicInteger totalCount = new AtomicInteger((pageNumber - 1) * pageSize);
		System.out.println("Start");
		String pageCountQuery = MessageFormat
				.format("SELECT CEIL(COUNT(*)/{0}) as count FROM `BUCKET` WHERE document_type IS NOT MISSING",
						String.valueOf(pageSize).replace(",", ""));
		System.out.println(pageCountQuery);
		int totalPages = cluster1.query(pageCountQuery).rowsAsObject().get(0).getInt("count");
		while (!completed) {
			System.out.println("Get page (" + pageNumber++ + "/" + totalPages + ")");
			String query = MessageFormat.format(queryFormat, pageSize, totalCount.get()).replace(",", "");
			System.out.println(query);
			List<JsonObject> result = cluster1.query(query).rowsAsObject();
			completed = result.size() < pageSize;
			if (!result.isEmpty()) {
				System.out.println("Upserting " + result.size());
				result.forEach(jsonObject -> {
					totalCount.getAndIncrement();
					String key = jsonObject.getString("guid");
					if (key != null) {
						try {
							collection0.upsert(key, jsonObject);
						} catch (AmbiguousTimeoutException e) {
							try {
								Thread.sleep(1000);
								collection0.upsert(key, jsonObject);
							} catch (InterruptedException ex) {
								ex.printStackTrace();
							}
						}
					}
				});
			}
			try {
				Thread.sleep(pageSize);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		System.out.println("Total Count " + totalCount);
		String queryCheck = "SELECT COUNT(`BUCKET`) as count FROM `BUCKET` WHERE document_type IS NOT MISSING";
		AtomicInteger e0Count = new AtomicInteger();
		AtomicInteger e1Count = new AtomicInteger();
		cluster0.query(queryCheck).rowsAsObject().forEach(jsonObject -> {
			e0Count.set(jsonObject.getInt("count"));
		});
		cluster1.query(queryCheck).rowsAsObject().forEach(jsonObject -> {
			e1Count.set(jsonObject.getInt("count"));
		});
		cluster0.disconnect();
		cluster1.disconnect();
		System.out.println("E0 Count = " + e0Count.get());
		System.out.println("E1 Count = " + e1Count.get());
		System.out.println("Counts equals = " + (e0Count.get() == e1Count.get()));
		System.out.println("Complete");
	}
}
