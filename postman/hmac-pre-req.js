var uuid = require('uuid');
var moment = require("moment");

var apiKey = pm.environment.get("apiKey");
var apiSecret = pm.environment.get("apiSecret");

pm.environment.values.substitute(pm.request.url, null, false);

var host = pm.request.url.host[0];
for (var i = 1; i < pm.request.url.host.length; i++) {
    host = host + "." + pm.request.url.host[i];
}
if (pm.request.url.protocol !== undefined) {
    host = pm.request.url.protocol + "://" + host;
}
var port = 443;
if (host.includes("http://")) {
    host = host.replace("http://", "");
    port = 80;
} else if (host.includes("https://")) {
    host = host.replace("https://", "");
    port = 443;
}
var uri = pm.request.url.toString()
    .replace(host, "")
    .replace("https://", "")
    .replace("http://", "");
if (host.includes(":")) {
    var split = host.split(":");
    host = split[0];
    port = split[1];
}
var method = pm.request.method.toString();
var body = pm.request.body;
if (body === undefined) {
    body = "";
} else {
    body = body.toString();
}
var ts = moment(new Date().toUTCString()).valueOf() / 1000;
var nonce = uuid.v4();

// console.log("apiKey = " + apiKey);
// console.log("apiSecret = " + apiSecret);
// console.log("host = " + host);
// console.log("port = " + port);
// console.log("uri = " + uri);
// console.log("body = " + body);
// console.log("method = " + method);
// console.log("ts = " + ts);
// console.log("nonce = " + nonce);

// Hash Body
var rawBodyHash = CryptoJS.HmacSHA256(body, apiSecret);
var bodyHash = CryptoJS.enc.Base64.stringify(rawBodyHash);

// Hash MAC
var macInput = ts + "\n" + nonce + "\n" + method + "\n" + uri + "\n" + host + "\n" + port + "\n" + bodyHash + "\n";
var rawMacHash = CryptoJS.HmacSHA256(macInput, apiSecret);
var mac = CryptoJS.enc.Base64.stringify(rawMacHash);

// Create Header
var hmacKey = "MAC id=\"" + apiKey + "\",ts=\"" + ts
    + "\",nonce=\"" + nonce + "\",bodyhash=\"" + bodyHash
    + "\",mac=\"" + mac + "\"";

// console.log(hmacKey);

postman.setEnvironmentVariable("hmacKey", hmacKey);