package smithereen.jsonld;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.List;

import smithereen.data.ForeignUser;

import static smithereen.jsonld.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

public class LinkedDataSignaturesTests{

	private ForeignUser actor;

	public LinkedDataSignaturesTests() throws Exception{
		actor=(ForeignUser) ForeignUser.parse(JLDProcessor.convertToLocalContext((JSONObject)readResourceAsJSON("/ld-signature/mastodon_actor.json")));
	}

	@Test
	@DisplayName("C14N only: activity object")
	void testCanonicalizationActivity(){
		JSONObject in=(JSONObject) readResourceAsJSON("/ld-signature/00a-unsigned_payload.json");
		String out=URDNA2015.canonicalize(in, null);
		List<String> expect=readResourceAsLines("/ld-signature/02a-normalized_activity.rdf");
		assertLinesMatch(expect, Arrays.asList(out.split("\n")));
	}

	@Test
	@DisplayName("C14N only: signature options object")
	void testCanonicalizationOptions(){
		JSONObject in=(JSONObject) readResourceAsJSON("/ld-signature/00b-options_payload.json");
		String out=URDNA2015.canonicalize(in, null);
		List<String> expect=readResourceAsLines("/ld-signature/02a-normalized_options.rdf");
		assertLinesMatch(expect, Arrays.asList(out.split("\n")));
	}

	@Test
	@DisplayName("Signature verification: Create activity (Mastodon)")
	void testVerificationValidCreate(){
		JSONObject in=(JSONObject)readResourceAsJSON("/ld-signature/mastodon_signed_create.json");
		assertTrue(LinkedDataSignatures.verify(in, actor.publicKey));
	}

	@Test
	@DisplayName("Signature verification: Delete activity (Mastodon)")
	void testVerificationValidDelete(){
		JSONObject in=(JSONObject)readResourceAsJSON("/ld-signature/mastodon_signed_delete.json");
		assertTrue(LinkedDataSignatures.verify(in, actor.publicKey));
	}

	@Test
	@DisplayName("Signature verification fails on a modified object")
	void testVerificationTampered(){
		JSONObject in=(JSONObject)readResourceAsJSON("/ld-signature/mastodon_signed_create_tampered.json");
		assertFalse(LinkedDataSignatures.verify(in, actor.publicKey));
	}

	@Test
	@DisplayName("Sign and verify")
	void testSignAndVerify() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException{
		DataInputStream stream=new DataInputStream(getClass().getResourceAsStream("/ld-signature/public_key.der"));
		byte[] buf=new byte[stream.available()];
		stream.readFully(buf);
		stream.close();
		PublicKey publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(buf));
		stream=new DataInputStream(getClass().getResourceAsStream("/ld-signature/private_key.der"));
		buf=new byte[stream.available()];
		stream.readFully(buf);
		stream.close();
		PrivateKey privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(buf));

		JSONObject in=(JSONObject) readResourceAsJSON("/ld-signature/00a-unsigned_payload.json");
		LinkedDataSignatures.sign(in, privateKey, "https://example.com/user#main-key");

		assertTrue(in.has("signature"));
		assertTrue(LinkedDataSignatures.verify(in, publicKey));
	}
}
