package smithereen.activitypub.objects;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.Collections;

import smithereen.Config;
import smithereen.Utils;
import smithereen.activitypub.ContextCollector;
import smithereen.activitypub.ParserContext;
import smithereen.data.CachedRemoteImage;
import smithereen.data.NonCachedRemoteImage;
import smithereen.data.SizedImage;
import smithereen.jsonld.JLD;
import smithereen.storage.MediaCache;
import spark.utils.StringUtils;

public abstract class Actor extends ActivityPubObject{
	public String username;
	transient public PublicKey publicKey;
	transient public PrivateKey privateKey;
	public String domain;
	public URI inbox;
	public URI outbox;
	public URI sharedInbox;
	public URI followers;
	public URI following;
	public Timestamp lastUpdated;

	public String getProfileURL(String action){
		return "/"+getFullUsername()+"/"+action;
	}

	public String getProfileURL(){
		return "/"+getFullUsername();
	}

	public boolean hasAvatar(){
		return icon!=null;
	}

	public Image getBestAvatarImage(){
		Image icon=this.icon!=null ? this.icon.get(0) : null;
		if(icon==null)
			return null;
		if(icon instanceof LocalImage)
			return icon;
		if(icon.image!=null && !icon.image.isEmpty() && icon.image.get(0).width>0 && icon.image.get(0).height>0)
			return icon.image.get(0);
		return icon;
	}

	public float[] getAvatarCropRegion(){
		Image icon=this.icon!=null ? this.icon.get(0) : null;
		if(icon==null)
			return null;
		return icon.cropRegion;
	}

	public String getFullUsername(){
		return username;
	}

	public URI getFollowersURL(){
		String userURL=activityPubID.toString();
		return URI.create(userURL+"/followers");
	}

	public SizedImage getAvatar(){
		Image icon=getBestAvatarImage();
		if(icon==null)
			return null;
		if(icon instanceof LocalImage){
			return (LocalImage) icon;
		}
		MediaCache cache=MediaCache.getInstance();
		try{
			MediaCache.PhotoItem item=(MediaCache.PhotoItem) cache.get(icon.url);
			if(item!=null){
				return new CachedRemoteImage(item, getAvatarCropRegion());
			}else{
				SizedImage.Dimensions size=SizedImage.Dimensions.UNKNOWN;
				if(icon.width>0 && icon.height>0){
					size=new SizedImage.Dimensions(icon.width, icon.height);
				}
				return new NonCachedRemoteImage(getAvatarArgs(), size);
			}
		}catch(SQLException e){
			e.printStackTrace();
		}
		return null;
	}

	protected NonCachedRemoteImage.Args getAvatarArgs(){
		throw new IllegalStateException("Should never happen");
	}

	@Override
	public JSONObject asActivityPubObject(JSONObject obj, ContextCollector contextCollector){
		JSONObject root=super.asActivityPubObject(obj, contextCollector);

		String userURL=activityPubID.toString();
		root.put("preferredUsername", username);
		root.put("inbox", userURL+"/inbox");
		root.put("outbox", userURL+"/outbox");
		root.put("followers", userURL+"/followers");
		if(canFollowOtherActors())
			root.put("following", userURL+"/following");

		JSONObject endpoints=new JSONObject();
		endpoints.put("sharedInbox", Config.localURI("/activitypub/sharedInbox").toString());
		root.put("endpoints", endpoints);

		JSONObject pubkey=new JSONObject();
		pubkey.put("id", userURL+"#main-key");
		pubkey.put("owner", userURL);
		String pkey="-----BEGIN PUBLIC KEY-----\n";
		pkey+=Base64.getEncoder().encodeToString(publicKey.getEncoded());
		pkey+="\n-----END PUBLIC KEY-----\n";
		pubkey.put("publicKeyPem", pkey);
		root.put("publicKey", pubkey);

		root.put("wall", getWallURL().toString());
		contextCollector.addAlias("wall", "sm:wall");
		contextCollector.addAlias("sm", JLD.SMITHEREEN);

		contextCollector.addSchema(JLD.W3_SECURITY);

		return root;
	}

	@Override
	protected ActivityPubObject parseActivityPubObject(JSONObject obj, ParserContext parserContext) throws Exception{
		super.parseActivityPubObject(obj, parserContext);

		username=obj.optString("preferredUsername", null);
		if(username==null){
			username=Utils.getLastPathSegment(activityPubID);
		}
		domain=activityPubID.getHost();
		if(activityPubID.getPort()!=-1)
			domain+=":"+activityPubID.getPort();
		if(url==null)
			url=activityPubID;

		JSONObject pkey=obj.getJSONObject("publicKey");
		URI keyOwner=tryParseURL(pkey.getString("owner"));
		if(!keyOwner.equals(activityPubID))
			throw new IllegalArgumentException("Key owner ("+keyOwner+") is not equal to user ID ("+activityPubID+")");
		String pkeyEncoded=pkey.getString("publicKeyPem");
		pkeyEncoded=pkeyEncoded.replaceAll("-----(BEGIN|END) (RSA )?PUBLIC KEY-----", "").replace("\n", "").trim();
		byte[] key=Base64.getDecoder().decode(pkeyEncoded);
		try{
			X509EncodedKeySpec spec=new X509EncodedKeySpec(key);
			publicKey=KeyFactory.getInstance("RSA").generatePublic(spec);
		}catch(InvalidKeySpecException x){
			// a simpler RSA key format, used at least by Misskey
			// FWIW, Misskey user objects also contain a key "isCat" which I ignore
			RSAPublicKeySpec spec=decodeSimpleRSAKey(key);
			publicKey=KeyFactory.getInstance("RSA").generatePublic(spec);
		}

		inbox=tryParseURL(obj.getString("inbox"));
		outbox=tryParseURL(obj.optString("outbox", null));
		followers=tryParseURL(obj.optString("followers", null));
		following=tryParseURL(obj.optString("following", null));
		if(obj.has("endpoints")){
			JSONObject endpoints=obj.getJSONObject("endpoints");
			sharedInbox=tryParseURL(endpoints.optString("sharedInbox", null));
		}
		if(sharedInbox==null)
			sharedInbox=inbox;

		if(summary!=null)
			summary=Utils.sanitizeHTML(summary);

		return this;
	}

	public abstract int getLocalID();
	public abstract URI getWallURL();
	public abstract String getTypeAndIdForURL();

	private static RSAPublicKeySpec decodeSimpleRSAKey(byte[] key) throws IOException{
		ByteArrayInputStream in=new ByteArrayInputStream(key);
		int id=in.read();
		if(id!=0x30)
			throw new IOException("Must start with SEQUENCE");
		int seqLen=readDerLength(in);
		id=in.read();
		if(id!=2)
			throw new IOException("SEQUENCE must be followed by INTEGER");
		int modLen=readDerLength(in);
		byte[] modBytes=new byte[modLen];
		in.read(modBytes);
		id=in.read();
		if(id!=2)
			throw new IOException("SEQUENCE must be followed by INTEGER");
		int expLen=readDerLength(in);
		byte[] expBytes=new byte[expLen];
		in.read(expBytes);
		return new RSAPublicKeySpec(new BigInteger(modBytes), new BigInteger(expBytes));
	}

	private static int readDerLength(InputStream in) throws IOException{
		int length=in.read();
		if((length & 0x80)!=0){
			int additionalBytes=length & 0x7F;
			if(additionalBytes>4)
				throw new IOException("Invalid length value");
			length=0;
			for(int i=0;i<additionalBytes;i++){
				length=length<<8;
				length|=in.read() & 0xFF;
			}
		}
		return length;
	}

	protected void fillFromResultSet(ResultSet res) throws SQLException{
		byte[] key=res.getBytes("public_key");
		try{
			X509EncodedKeySpec spec=new X509EncodedKeySpec(key);
			publicKey=KeyFactory.getInstance("RSA").generatePublic(spec);
		}catch(Exception ignore){}
		key=res.getBytes("private_key");
		if(key!=null){
			try{
				PKCS8EncodedKeySpec spec=new PKCS8EncodedKeySpec(key);
				privateKey=KeyFactory.getInstance("RSA").generatePrivate(spec);
			}catch(Exception ignore){}
		}

		String _ava=res.getString("avatar");
		if(_ava!=null){
			if(_ava.startsWith("{")){
				try{
					icon=Collections.singletonList((Image)ActivityPubObject.parse(new JSONObject(_ava), ParserContext.LOCAL));
				}catch(Exception ignore){}
			}
		}

		username=res.getString("username");
	}

	public boolean hasWall(){
		return getWallURL()!=null;
	}

	public void ensureLocal(){
		if(StringUtils.isNotEmpty(domain))
			throw new IllegalArgumentException("Local actor is required here");
	}

	public void ensureRemote(){
		if(StringUtils.isEmpty(domain))
			throw new IllegalArgumentException("Remote actor is required here");
	}

	protected boolean canFollowOtherActors(){
		return true;
	}
}
