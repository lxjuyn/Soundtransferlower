import java.io.*;
import java.net.*;
import javax.net.ssl.*;

public class Downloader {
    public static void main(String[] args) throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

        String url = "https://github.com/concentus/Concentus/archive/refs/heads/main.zip";
        System.out.println("Downloading from: " + url);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);

        int code = conn.getResponseCode();
        System.out.println("Response code: " + code);

        if (code == 200) {
            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(args[0]);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.close();
            in.close();
            System.out.println("Downloaded successfully!");
        } else {
            System.out.println("Failed with code: " + code);
        }
    }
}
