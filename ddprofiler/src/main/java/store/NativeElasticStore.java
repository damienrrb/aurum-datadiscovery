package store;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;

import core.WorkerTaskResult;
import core.config.ProfilerConfig;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import io.searchbox.client.config.HttpClientConfig;
import io.searchbox.core.Index;
import io.searchbox.indices.CreateIndex;
import io.searchbox.indices.mapping.PutMapping;

import static org.elasticsearch.common.xcontent.XContentFactory.*;

public class NativeElasticStore implements Store {

	// Although native client, we still use Jest for put mappings, which happens only once
	private JestClient client;
	private JestClientFactory factory = new JestClientFactory();
	private String serverUrl;
	
	private Client nativeClient;
	
	public NativeElasticStore(ProfilerConfig pc) { 
		String storeServer = pc.getString(ProfilerConfig.STORE_SERVER);
		int storePort = pc.getInt(ProfilerConfig.STORE_PORT);
		this.serverUrl = "http://"+storeServer+":"+storePort;
	}
	
	@Override
	public void initStore() {
		this.silenceJestLogger();
		factory.setHttpClientConfig(new HttpClientConfig
                .Builder(serverUrl)
                .multiThreaded(true)
                .build());
		client = factory.getObject();
		
		// Create the native client
//		Node eNode = NodeBuilder.nodeBuilder()
//				.local(true)
//				.settings(Settings.settingsBuilder().put("path.home", "/Users/ra-mit/Downloads/elasticsearch-2.3.3"))
//				.client(true)
//				.build();
//		nativeClient = eNode.client();
		
		try {
			//nativeClient = TransportClient.builder().settings(settings).build().addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("127.0.0.1"), 9300));
			nativeClient = TransportClient.builder().build().addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName("127.0.0.1"), 9300));
		} 
		catch (UnknownHostException e1) {
			e1.printStackTrace();
		}
		
		// Create the appropriate mappings for the indices
		PutMapping textMapping = new PutMapping.Builder(
				"text",
				"column",
				"{ \"properties\" : "
				+ "{ \"id\" :   {\"type\" : \"integer\","
				+ 				"\"store\" : \"yes\","
				+ 				"\"index\" : \"not_analyzed\"},"
				+ "\"sourceName\" :   {\"type\" : \"string\","
				+ 				"\"index\" : \"not_analyzed\"}, "
				+ "\"columnName\" :   {\"type\" : \"string\","
				+ 				"\"index\" : \"not_analyzed\", "
				+				"\"ignore_above\" : 512 },"
				+ "\"text\" : {\"type\" : \"string\", "
				+ 				"\"store\" : \"no\"," // space saving?
				+ 				"\"index\" : \"analyzed\","
				+				"\"analyzer\" : \"english\","
				+ 				"\"term_vector\" : \"yes\"}"
				+ "}"
				+ " "
				+ "}"
		).build();
		System.out.println(textMapping.toString());
		
		PutMapping profileMapping = new PutMapping.Builder(
				"profile",
				"column",
				"{ \"properties\" : "
				+ "{ "
				+ "\"id\" : {\"type\" : \"integer\", \"index\" : \"not_analyzed\"},"
				+ "\"sourceName\" : {\"type\" : \"string\", \"index\" : \"not_analyzed\"},"
				//+ "\"columnName\" : {\"type\" : \"string\", \"index\" : \"analyzed\"},"
				+ "\"columnName\" : {\"type\" : \"string\", "
				+ 		"\"index\" : \"analyzed\", "
				+ 		"\"analyzer\" : \"english\"},"
				+ "\"dataType\" : {\"type\" : \"string\", \"index\" : \"not_analyzed\"},"
				+ "\"totalValues\" : {\"type\" : \"integer\", \"index\" : \"not_analyzed\"},"
				+ "\"uniqueValues\" : {\"type\" : \"integer\", \"index\" : \"not_analyzed\"},"
				+ "\"entities\" : {\"type\" : \"string\", \"index\" : \"analyzed\"}," // array
				+ "\"minValue\" : {\"type\" : \"float\", \"index\" : \"not_analyzed\"},"
				+ "\"maxValue\" : {\"type\" : \"float\", \"index\" : \"not_analyzed\"},"
				+ "\"avgValue\" : {\"type\" : \"float\", \"index\" : \"not_analyzed\"},"
				+ "\"median\" : {\"type\" : \"long\", \"index\" : \"not_analyzed\"},"
				+ "\"iqr\" : {\"type\" : \"long\", \"index\" : \"not_analyzed\"}"
				+ "} }"
		).build();
		
		// Make sure the necessary elastic indexes exist and apply the mappings
		try {
			client.execute(new CreateIndex.Builder("text").build());
			client.execute(new CreateIndex.Builder("profile").build());
			client.execute(textMapping);
			client.execute(profileMapping);
		} 
		catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public boolean indexData(int id, String sourceName, String columnName, List<String> values) {
		String strId = Integer.toString(id);
		String v = concatValues(values);
		
		XContentBuilder builder = null;
		try {
			builder = jsonBuilder()
					.startObject()
						.field("id", strId)
						.field("sourceName", sourceName)
						.field("columnName", columnName)
						// TODO: is it more efficient if we build an array here instead
						.field("text", v)
					.endObject();
		} 
		catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		IndexResponse response = nativeClient.prepareIndex("text", "column")
		        .setSource(builder)
		        .get();
		
		return true;
	}

	@Override
	public boolean storeDocument(WorkerTaskResult wtr) {
		String strId = Integer.toString(wtr.getId());
		
		XContentBuilder builder = null;
		try {
			builder = jsonBuilder()
					.startObject()
						.field("id", wtr.getId())
						.field("sourceName", wtr.getSourceName())
						.field("columnName", wtr.getColumnName())
						.field("dataType", wtr.getDataType())
						.field("totalValues", wtr.getTotalValues())
						.field("uniqueValues", wtr.getUniqueValues())
						.field("entities", wtr.getEntities().toString())
						.field("minValue", wtr.getEntities().toString())
						.field("maxValue", wtr.getMaxValue())
						.field("avgValue", wtr.getAvgValue())
						.field("median", wtr.getMedian())
						.field("iqr", wtr.getIQR())
					.endObject();
		} 
		catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		IndexResponse response = nativeClient.prepareIndex("profile", "column", strId)
		        .setSource(builder)
		        .get();
		
		return true;
	}

	@Override
	public void tearDownStore() {
		client.shutdownClient();
		nativeClient.close();
		factory = null;
		client = null;
	}
	
	//TODO: do we need this?
	private String concatValues(List<String> values) {
		StringBuilder sb = new StringBuilder();
		String separator = " ";
		for(String s : values) {
			sb.append(s);
			sb.append(separator);
		}
		return sb.toString();
	}
	
	private void silenceJestLogger() {
		final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("io.searchbox.client");
		final org.slf4j.Logger logger2 = org.slf4j.LoggerFactory.getLogger("io.searchbox.action");
		if (!(logger instanceof ch.qos.logback.classic.Logger)) {
		    return;
		}
		if (!(logger2 instanceof ch.qos.logback.classic.Logger)) {
		    return;
		}
		ch.qos.logback.classic.Logger logbackLogger = (ch.qos.logback.classic.Logger) logger;
		ch.qos.logback.classic.Logger logbackLogger2 = (ch.qos.logback.classic.Logger) logger2;
		logbackLogger.setLevel(ch.qos.logback.classic.Level.INFO);
		logbackLogger2.setLevel(ch.qos.logback.classic.Level.INFO);
	}

}
