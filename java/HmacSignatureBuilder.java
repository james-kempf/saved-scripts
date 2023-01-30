import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

public class HmacSignatureBuilder {

    private static final String MAC_ALGORITHM = "HmacSHA256";

    private HmacSignatureBuilder() {
    }

    /**
     * Generate MAC key
     *
     * @param requestUrl url of request
     * @param appSecret  app secret, loaded from couchbase
     * @param apiKey     api key, loaded from couchbase
     * @param ts         timestamp
     * @param nonce      nonce
     * @param method     HTTP request method
     * @param postBody   HTTP request body
     * @return Mac string
     */
    public static String getMac(String requestUrl, String appSecret,
                                String apiKey, String ts, String nonce, String method, String postBody) throws
            InvalidKeyException, NoSuchAlgorithmException,
            URISyntaxException, MalformedURLException {

        URI uri = new URI(requestUrl);
        String resourceUri = uri.getPath();
        String query = uri.getQuery();
        if (query != null && !(query.isEmpty())) {
            resourceUri = resourceUri + "?" + query;
        }

        String host = uri.getHost().trim().toLowerCase();
        int port = (uri.getPort() == -1) ? uri.toURL().getDefaultPort() : uri.getPort();

        Mac mac = Mac.getInstance(MAC_ALGORITHM);
        SecretKeySpec secretKey = new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM);
        mac.init(secretKey);

        if (postBody == null) {
            postBody = "";
        }
        byte[] rawBodyHash = mac.doFinal(postBody.getBytes(StandardCharsets.UTF_8));
        String bodyHash = DatatypeConverter.printBase64Binary(rawBodyHash);

        String macInput = ts + "\n" + nonce + "\n" + method + "\n"
                + resourceUri + "\n" + host + "\n" + port + "\n" + bodyHash
                + "\n";

        byte[] rawMacSignature = mac.doFinal(macInput.getBytes(StandardCharsets.UTF_8));
        String macSignature = DatatypeConverter.printBase64Binary(rawMacSignature);

        return "MAC id=\"" + apiKey + "\",ts=\"" + ts
                + "\",nonce=\"" + nonce + "\",bodyhash=\"" + bodyHash
                + "\",mac=\"" + macSignature + "\"";
    }
}
