package me.PabloDevelopment.bootloader.bootstrap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class Downloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(Downloader.class);

    public static File file(String urlText, String fileName) throws IOException {
        File file = new File(fileName);

        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();

        URL link = new URL(urlText);

        Request.Builder downloadRequest = new Request.Builder().url(link).get();

        //Add headers if your Jenkins is restricted to public.
        if(!Bootstrap.config.getString("Jenkins.downloadToken", "").isEmpty()){
            downloadRequest.header("Authorization" ,"Basic %s".formatted(Bootstrap.config.getString("Jenkins.downloadToken")));
        }

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .writeTimeout(10, TimeUnit.MINUTES)
                .connectTimeout(10, TimeUnit.MINUTES)
                .callTimeout(5, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES).build();

        Response response = okHttpClient.newCall(downloadRequest.build()).execute();
        if (!response.isSuccessful()) {
            LOGGER.error("Hubo un error en la respuesta del servidor: código %s".formatted(response.code()));
            return null;
        }

        assert response.body() != null;
        try (BufferedSource bufferedSource = response.body().source()) {
            BufferedSink bufferedSink = Okio.buffer(Okio.sink(file));
            bufferedSink.writeAll(bufferedSource);
            bufferedSink.close();
        }

        return file;
    }
}
