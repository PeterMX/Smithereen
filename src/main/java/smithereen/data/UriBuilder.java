package smithereen.data;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import smithereen.Config;
import spark.utils.StringUtils;

public class UriBuilder{

	private String scheme;
	private String authority;

	// all these are percent-encoded
	private List<String> pathSegments;
	private String fragment;
	private List<KeyValuePair> query;

	public UriBuilder(String baseURI){
		this(URI.create(baseURI));
	}

	public UriBuilder(URI baseURI){
		scheme=baseURI.getScheme();
		authority=baseURI.getAuthority();
		String path=baseURI.getRawPath();
		if(path!=null && path.length()>1){
			pathSegments=new ArrayList<>(Arrays.asList(path.split("/")));
		}
		String query=baseURI.getRawQuery();
		if(StringUtils.isNotEmpty(query)){
			this.query=Arrays.stream(query.split("&")).map(s->{
				int offset=s.indexOf('=');
				if(offset==-1)
					return new KeyValuePair(s, null);
				return new KeyValuePair(s.substring(0, offset), s.substring(offset+1));
			}).collect(Collectors.toCollection(ArrayList::new));
		}
		fragment=baseURI.getRawFragment();
	}

	public UriBuilder(){

	}

	public static UriBuilder local(){
		return new UriBuilder()
				.scheme(Config.useHTTP ? "http" : "https")
				.authority(Config.domain);
	}

	public UriBuilder scheme(String scheme){
		this.scheme=scheme;
		return this;
	}

	public UriBuilder authority(String authority){
		this.authority=authority;
		return this;
	}

	public UriBuilder authority(String host, int port){
		if(port<0 || port>65536)
			throw new IllegalArgumentException("Invalid port value "+port);
		this.authority=host+":"+port;
		return this;
	}

	public UriBuilder rawPath(String path){
		pathSegments=new ArrayList<>(Arrays.asList(path.split("/")));
		return this;
	}

	public UriBuilder path(String... segments){
		if(pathSegments==null)
			pathSegments=new ArrayList<>();
		Arrays.stream(segments).map(UriBuilder::urlEncode).forEach(pathSegments::add);
		return this;
	}

	public UriBuilder appendPath(String segment){
		if(pathSegments==null)
			pathSegments=new ArrayList<>();
		pathSegments.add(urlEncode(segment));
		return this;
	}

	public UriBuilder queryParam(String key, String value){
		if(query==null)
			query=new ArrayList<>();
		query.add(new KeyValuePair(urlEncode(key), urlEncode(value)));
		return this;
	}

	public UriBuilder clearQuery(){
		query=null;
		return this;
	}

	public UriBuilder clearPath(){
		pathSegments=null;
		return this;
	}

	public UriBuilder fragment(String fragment){
		this.fragment=urlEncode(fragment);
		return this;
	}

	public URI build(){
		StringBuilder sb=new StringBuilder(scheme);
		sb.append("://");
		sb.append(authority);
		if((pathSegments!=null && !pathSegments.isEmpty()) || (query!=null && !query.isEmpty()) || StringUtils.isNotEmpty(fragment))
			sb.append('/');
		if(pathSegments!=null){
			sb.append(String.join("/", pathSegments));
		}
		if(query!=null){
			boolean first=true;
			for(KeyValuePair kvp:query){
				sb.append(first ? '?' : '&');
				first=false;
				sb.append(kvp.key);
				if(StringUtils.isNotEmpty(kvp.value)){
					sb.append('=');
					sb.append(kvp.value);
				}
			}
		}
		if(StringUtils.isNotEmpty(fragment)){
			sb.append('#');
			sb.append(fragment);
		}
		return URI.create(sb.toString());
	}

	private static String urlEncode(String in){
		if(in==null)
			return null;
		try{
			return URLEncoder.encode(in, "UTF-8");
		}catch(UnsupportedEncodingException ignore){}
		throw new RuntimeException("unreachable");
	}

	private static class KeyValuePair{
		public String key, value;

		public KeyValuePair(String key, String value){
			this.key=key;
			this.value=value;
		}
	}
}