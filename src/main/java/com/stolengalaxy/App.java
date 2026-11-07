package com.stolengalaxy;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

public class App {
    public static void main(String[] args) {


        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7070"));

        Javalin app = Javalin.create(config -> {
            // Serves src/main/resources/public/* at the root, e.g. /login.html
            // (index.html is served automatically at "/")
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });
        });

        app.get("/login", ctx -> ctx.redirect("/login.html"));
        app.get("/signup", ctx -> ctx.redirect("/signup.html"));
        app.get("/workspace", ctx -> {

            ctx.redirect("/workspace.html");
        });

        app.post("/login", ctx -> ctx.redirect("/workspace.html"));
        app.post("/signup", ctx -> ctx.redirect("/workspace.html"));
        app.get("/logout", ctx -> ctx.redirect("/login.html"));

        app.get("/api/health", ctx -> ctx.contentType("application/json").result("{\"status\":\"ok\"}"));

        app.start(port);
    }
}
