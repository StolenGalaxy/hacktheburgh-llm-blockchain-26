package com.stolengalaxy;

import com.google.gson.Gson;
import com.stolengalaxy.db.Database;
import com.stolengalaxy.smartcontract.ProjectContracts;
import com.stolengalaxy.web.WebRoutes;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JsonMapper;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));
        Gson gson = new Gson();

        // Local anvil chain; its state is kept in CHAIN_DIR (default ./chain) so pools survive restarts.
        // Delete that folder together with app.db to start completely fresh.
        ProjectContracts contracts = ProjectContracts.start(Path.of(System.getenv().getOrDefault("CHAIN_DIR", "chain")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            contracts.close(); // stops anvil cleanly so it saves its latest state
            Database.close();
        }, "shutdown"));

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

        WebRoutes.register(app, contracts);
        app.start(port);
    }
}
