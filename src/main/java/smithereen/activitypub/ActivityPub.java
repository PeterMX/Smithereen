package smithereen.activitypub;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import smithereen.Config;
import smithereen.DisallowLocalhostInterceptor;
import smithereen.LruCache;
import smithereen.ObjectNotFoundException;
import smithereen.activitypub.objects.Activity;
import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.data.NodeInfo;
import smithereen.data.User;
import smithereen.jsonld.JLD;
import smithereen.jsonld.JLDProcessor;
import smithereen.jsonld.LinkedDataSignatures;
import spark.utils.StringUtils;

public class ActivityPub{

	public static final URI AS_PUBLIC=URI.create(JLD.ACTIVITY_STREAMS+"#Public");

	private static OkHttpClient httpClient;
	private static LruCache<String, String> domainRedirects=new LruCache<>(100);

	static{
		httpClient=new OkHttpClient.Builder()
				.addNetworkInterceptor(new DisallowLocalhostInterceptor())
				.build();
	}

	public static ActivityPubObject fetchRemoteObject(String url) throws IOException, JSONException{
		Request req=new Request.Builder()
				.url(url)
				.header("Accept", "application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\"")
				.build();
		Call call=httpClient.newCall(req);
		Response resp=call.execute();
		try(ResponseBody body=resp.body()){
			if(!resp.isSuccessful()){
				System.out.println(resp);
				return null;
			}

			String r=body.string();
			try{
				JSONObject converted=JLDProcessor.convertToLocalContext(new JSONObject(r));
//				System.out.println(converted.toString(4));
				return ActivityPubObject.parse(converted);
			}catch(Exception x){
				throw new JSONException(x);
			}
		}
	}

	public static void postActivity(URI inboxUrl, Activity activity, User user) throws IOException{
		//System.out.println("Sending activity: "+activity);
		String path=inboxUrl.getPath();
		String host=inboxUrl.getHost();
		if(inboxUrl.getPort()!=-1)
			host+=":"+inboxUrl.getPort();
		SimpleDateFormat dateFormat=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		String date=dateFormat.format(new Date());
		String strToSign="(request-target): post "+path+"\nhost: "+host+"\ndate: "+date;

		Signature sig;
		byte[] signature;
		try{
			sig=Signature.getInstance("SHA256withRSA");
			sig.initSign(user.privateKey);
			sig.update(strToSign.getBytes(StandardCharsets.UTF_8));
			signature=sig.sign();
		}catch(Exception x){
			x.printStackTrace();
			throw new RuntimeException(x);
		}

		String keyID=user.activityPubID+"#main-key";
		String sigHeader="keyId=\""+keyID+"\",headers=\"(request-target) host date\",signature=\""+Base64.getEncoder().encodeToString(signature)+"\"";

		JSONObject body=activity.asRootActivityPubObject();
		LinkedDataSignatures.sign(body, user.privateKey, keyID);
		System.out.println("Sending activity: "+body);

		Request req=new Request.Builder()
				.url(inboxUrl.toString())
				.header("Signature", sigHeader)
				.header("Date", date)
				.post(RequestBody.create(MediaType.parse("application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\""), body.toString()))
				.build();
		Response resp=httpClient.newCall(req).execute();
		System.out.println(resp.toString());
		try(ResponseBody rb=resp.body()){
			if(!resp.isSuccessful())
				System.out.println(rb.string());
		}
	}

	public static NodeInfo fetchNodeInfo(String host) throws IOException{
		Request req=new Request.Builder()
				.url("http"+(Config.useHTTP ? "" : "s")+"://"+host+"/.well-known/nodeinfo")
				.build();
		Response resp=httpClient.newCall(req).execute();
		if(resp.code()==404){
			return new NodeInfo(null, host);
		}else if(!resp.isSuccessful()){
			throw new IOException("Failed to fetch nodeinfo for "+host);
		}
		String href=null;
		try(ResponseBody body=resp.body()){
			JSONObject l=new JSONObject(body.string());
			for(Object o : l.getJSONArray("links")){
				JSONObject link=(JSONObject) o;
				if("http://nodeinfo.diaspora.software/ns/schema/2.0".equals(link.optString("rel"))){
					href=link.getString("href");
					break;
				}
			}
		}catch(JSONException x){}
		if(href==null){
			return new NodeInfo(null, host);
		}
		req=new Request.Builder()
				.url(href)
				.build();
		resp=httpClient.newCall(req).execute();
		if(!resp.isSuccessful()){
			return new NodeInfo(null, host);
		}
		try(ResponseBody body=resp.body()){
			JSONObject ni=new JSONObject(body.string());
			return new NodeInfo(ni, host);
		}
	}

	public static boolean isPublic(URI uri){
		return uri.equals(AS_PUBLIC) || ("as".equals(uri.getScheme()) && "Public".equals(uri.getSchemeSpecificPart()));
	}

	private static URI doWebfingerRequest(String username, String domain, String uriTemplate) throws IOException{
		String resource="acct:"+username+"@"+domain;
		String url;
		if(StringUtils.isEmpty(uriTemplate)){
			url="https://"+domain+"/.well-known/webfinger?resource="+resource;
		}else{
			url=uriTemplate.replace("{uri}", resource);
		}
		Request req=new Request.Builder()
				.url(url)
				.build();
		Response resp=httpClient.newCall(req).execute();
		try(ResponseBody body=resp.body()){
			if(resp.isSuccessful()){
				JSONObject o=new JSONObject(body.string());
				if(!o.getString("subject").equalsIgnoreCase(resource))
					throw new IOException("Invalid response");
				JSONArray links=o.getJSONArray("links");
				for(Object _l:links){
					JSONObject l=(JSONObject)_l;
					if(l.has("rel") && l.has("type") && l.has("href")){
						if("self".equals(l.getString("rel")) && "application/activity+json".equals(l.getString("type"))){
							return new URI(l.getString("href"));
						}
					}
				}
				throw new IOException("Link not found");
			}else if(resp.code()==404){
				throw new ObjectNotFoundException("User "+username+"@"+domain+" does not exist");
			}else{
				throw new IOException("Failed to resolve username "+username+"@"+domain);
			}
		}catch(JSONException|URISyntaxException x){
			throw new IOException("Response parse failed", x);
		}
	}

	public static URI resolveUsername(String username, String domain) throws IOException{
		String redirect;
		synchronized(ActivityPub.class){
			redirect=domainRedirects.get(domain);
		}
		try{
			URI uri=doWebfingerRequest(username, domain, redirect);
			if(redirect==null){
				synchronized(ActivityPub.class){
					// Cache an empty string indicating that this domain doesn't have a redirect.
					// This is to avoid a useless host-meta request when a nonexistent username is looked up on that instance.
					domainRedirects.put(domain, "");
				}
			}
			return uri;
		}catch(ObjectNotFoundException x){
			if(redirect==null){
				Request req=new Request.Builder()
						.url("https://"+domain+"/.well-known/host-meta")
						.header("Accept", "application/xrd+xml")
						.build();
				Response resp=httpClient.newCall(req).execute();
				try(ResponseBody body=resp.body()){
					if(resp.isSuccessful()){
						DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
						DocumentBuilder builder=factory.newDocumentBuilder();
						Document doc=builder.parse(body.byteStream());
						NodeList nodes=doc.getElementsByTagName("Link");
						for(int i=0; i<nodes.getLength(); i++){
							Node node=nodes.item(i);
							NamedNodeMap attrs=node.getAttributes();
							if(attrs!=null){
								Node _rel=attrs.getNamedItem("rel");
								Node _type=attrs.getNamedItem("type");
								Node _template=attrs.getNamedItem("template");
								if(_rel!=null && _type!=null && _template!=null){
									String rel=_rel.getNodeValue();
									String type=_type.getNodeValue();
									String template=_template.getNodeValue();
									if("lrdd".equals(rel) && "application/xrd+xml".equals(type)){
										if((template.startsWith("https://") || (Config.useHTTP && template.startsWith("http://"))) && template.contains("{uri}")){
											synchronized(ActivityPub.class){
												if(("https://"+domain+"/.well-known/webfinger?resource={uri}").equals(template)){
													// this isn't a real redirect
													domainRedirects.put(domain, "");
													// don't repeat the request, we already know that username doesn't exist (but the webfinger endpoint does)
													throw new ObjectNotFoundException(x);
												}else{
													System.out.println("Found domain redirect: "+domain+" -> "+template);
													domainRedirects.put(domain, template);
												}
											}
											return doWebfingerRequest(username, domain, template);
										}else{
											throw new ObjectNotFoundException("Malformed URI template '"+template+"' in host-meta domain redirect", x);
										}
									}
								}
							}
						}
					}
				}catch(ParserConfigurationException|SAXException e){
					throw new ObjectNotFoundException("Webfinger returned 404 and host-meta can't be parsed", e);
				}
			}
			throw new ObjectNotFoundException(x);
		}
	}
}
