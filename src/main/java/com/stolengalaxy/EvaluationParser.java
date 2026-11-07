package com.stolengalaxy;
import com.google.gson.*;
import java.util.List;
public class EvaluationParser {
    Gson gson = new Gson();

    private record Response(List<Evaluation> evaluations) {}

    public List<Evaluation> parse(JsonObject json) {
        Response response = gson.fromJson(json, Response.class);
        return response.evaluations();
    }
}
