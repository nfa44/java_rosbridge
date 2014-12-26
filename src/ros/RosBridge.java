package ros;


import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

/**
 *
 * A socket for connecting to ros bridge that accepts subscribe and publish commands.
 * Subscribing to a topic using the {@link #subsribe(String, String, RosListenDelegate)}} method
 * requires a provided {@link ros.RosListenDelegate} to be provided
 * which will be informed every time this socket receives a message from a publish
 * to the subscribed topic.
 * <br/>
 * Publishing is also supported with the {@link #publish(String, String, Object)} method.
 * <br/>
 * To create and connect to rosbridge, use the {@link #createConnection(String)} method.
 * Note that rosbridge by default uses port 9090. An example URI to provide as a parameter is: ws://localhost:9090
 * @author James MacGlashan.
 */
@WebSocket(maxTextMessageSize = 64 * 1024)
public class RosBridge {

	protected final CountDownLatch closeLatch;
	protected Session session;

	protected Map<String, RosListenDelegate> listeners = new HashMap<String, RosListenDelegate>();
	protected Set<String> publishedTopics = new HashSet<String>();

	protected boolean hasConnected = false;


	/**
	 * Creates a connection to the rosbridge websocket server located at rosBridgeURI.
	 * Note that it is recommend that you call the {@link #waitForConnection()} method
	 * before publishing or subcribing.
	 * @param rosBridgeURI the URI to the rosbridge websocket server. Note that rosbridge by default uses port 9090. An example URI is: ws://localhost:9090
	 * @return the RosBride socket that is connected to the indicated server.
	 */
	public static RosBridge createConnection(String rosBridgeURI){

		WebSocketClient client = new WebSocketClient();
		final RosBridge socket = new RosBridge();
		try {
			client.start();
			URI echoUri = new URI(rosBridgeURI);
			ClientUpgradeRequest request = new ClientUpgradeRequest();
			client.connect(socket, echoUri, request);
			System.out.printf("Connecting to : %s%n", echoUri);

		} catch (Throwable t) {
			t.printStackTrace();
		}

		return socket;

	}


	public RosBridge(){
		this.closeLatch = new CountDownLatch(1);
	}


	/**
	 * Blocks execution until a connection to the ros bridge server is established.
	 * Blocking polls every 500ms in a separate thread to check if the connection
	 * is established.
	 */
	public void waitForConnection(){
		Thread waitThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while(!RosBridge.this.hasConnected){
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		});

		waitThread.start();
		try {
			waitThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}


	/**
	 * Indicates whether the connection has been made
	 * @return a boolean indicating whether the connection has been made
	 */
	public boolean hasConnected(){
		return this.hasConnected;
	}


	/**
	 * Use this to close the connection
	 * @param duration the time until closing
	 * @param unit
	 * @return the result of the {@link java.util.concurrent.CountDownLatch#await()} method.
	 * @throws InterruptedException
	 */
	public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException {
		return this.closeLatch.await(duration, unit);
	}

	@OnWebSocketClose
	public void onClose(int statusCode, String reason) {
		System.out.printf("Connection closed: %d - %s%n", statusCode, reason);
		this.session = null;
		this.closeLatch.countDown();
	}

	@OnWebSocketConnect
	public void onConnect(Session session) {
		System.out.printf("Got connect for ros: %s%n", session);
		this.session = session;
		this.hasConnected = true;

	}

	@OnWebSocketMessage
	public void onMessage(String msg) {
		//System.out.printf("Got msg: %s%n", msg);
		JsonFactory jsonFactory = new JsonFactory();
		Map<String, Object> messageData = new HashMap<String, Object>();
		try {
			ObjectMapper objectMapper = new ObjectMapper(jsonFactory);
			TypeReference<Map<String, Object>> listTypeRef =
					new TypeReference<Map<String, Object>>() {};
			messageData = objectMapper.readValue(msg, listTypeRef);
		} catch (JsonParseException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

		String op = (String)messageData.get("op");
		if(op != null){
			if(op.equals("publish")){
				String topic = (String)messageData.get("topic");
				RosListenDelegate delegate = this.listeners.get(topic);
				if(delegate != null){
					delegate.receive(messageData, msg);
				}
			}
		}
	}


	/**
	 * Subscribes to a ros topic. New publish results will be reported to the provided delegate
	 * @param topic the to subscribe to
	 * @param type the message type of the topic
	 * @param delegate the delegate that receives updates to the topic
	 */
	public void subsribe(String topic, String type, RosListenDelegate delegate){
		//already have a subscription, so just update delegate
		if(this.listeners.containsKey(topic)){
			this.listeners.put(topic, delegate);
			return;
		}

		//otherwise setup the subscription and delegate
		this.listeners.put(topic, delegate);

		String subMsg = "{" +
				"\"op\": \"subscribe\",\n" +
				"\"topic\": \"" + topic + "\",\n" +
				"\"type\": \"" + type + "\"\n" +
				"}";

		Future<Void> fut;
		try{
			fut = session.getRemote().sendStringByFuture(subMsg);
			fut.get(2, TimeUnit.SECONDS);
		}catch (Throwable t){
			System.out.println("Error in setting up subscription to " + topic + " with message type: " + type);
			t.printStackTrace();
		}

	}


	/**
	 * Publishes to a topic. If the topic has not already been advertised on ros, it will automatically do so.
	 * @param topic the topic to publish to
	 * @param type the message type of the topic
	 * @param msg in general should should be a {@link java.util.Map}, specifying the message type data
	 */
	public void publish(String topic, String type, Object msg){

		if(!this.publishedTopics.contains(topic)){

			//then start advertising first
			String adMsg = "{" +
					"\"op\": \"advertise\",\n" +
					"\"topic\": \"" + topic + "\",\n" +
					"\"type\": \"" + type + "\"\n" +
					"}";

			Future<Void> fut;
			try{
				fut = session.getRemote().sendStringByFuture(adMsg);
				fut.get(2, TimeUnit.SECONDS);
			}catch (Throwable t){
				System.out.println("Error in setting up advertisement to " + topic + " with message type: " + type);
				t.printStackTrace();
			}

		}

		this.publishedTopics.add(topic);

		Map<String, Object> jsonMsg = new HashMap<String, java.lang.Object>();
		jsonMsg.put("op", "publish");
		jsonMsg.put("topic", topic);
		jsonMsg.put("type", type);
		jsonMsg.put("msg", msg);

		JsonFactory jsonFactory = new JsonFactory();
		StringWriter writer = new StringWriter();
		JsonGenerator jsonGenerator;
		ObjectMapper objectMapper = new ObjectMapper();

		try {
			jsonGenerator = jsonFactory.createGenerator(writer);
			objectMapper.writeValue(jsonGenerator, jsonMsg);
		} catch(Exception e){
			System.out.println("Error");
		}

		String jsonMsgString = writer.toString();
		Future<Void> fut;
		try{
			fut = session.getRemote().sendStringByFuture(jsonMsgString);
			fut.get(2, TimeUnit.SECONDS);
		}catch (Throwable t){
			System.out.println("Error publishing to " + topic + " with message type: " + type);
			t.printStackTrace();
		}



	}

}