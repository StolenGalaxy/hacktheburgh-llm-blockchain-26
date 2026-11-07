package com.stolengalaxy;

import com.google.gson.Gson;
import com.stolengalaxy.web.WebRoutes;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JsonMapper;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;

public class App {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));
        Gson gson = new Gson();

        Javalin app = Javalin.create(config -> {
            // Javalin defaults to Jackson, which isn't on the classpath; use Gson for ctx.json(...)
            config.jsonMapper(new JsonMapper() {
                @NotNull
                @Override
                public String toJsonString(@NotNull Object obj, @NotNull Type type) {
                    return gson.toJson(obj, type);
                }

                @NotNull
                @Override
                public <T> T fromJsonString(@NotNull String json, @NotNull Type targetType) {
                    return gson.fromJson(json, targetType);
                }
            });

            // Serves src/main/resources/public/* at the root, e.g. /login.html
            // (index.html is served automatically at "/")
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });
        });

        WebRoutes.register(app);
        app.start(port);
    }
}
