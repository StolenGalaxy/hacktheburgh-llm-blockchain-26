package com.stolengalaxy;
import com.google.gson.*;
import java.util.List;
import java.util.HashSet;
public class EvaluationParser {
    Gson gson = new Gson();

    private record Response(List<Evaluation> evaluations) {}

    public List<Evaluation> parse(JsonObject json) {
        Response response = gson.fromJson(json, Response.class);
        if (response == null){
            throw new IllegalArgumentException("AI reply has no evaluations");
        }
        for (Evaluation evaluation : response.evaluations()){
            if (evaluation.score() == null || evaluation.score() < 0 || evaluation.score() > 10){
                throw new IllegalArgumentException("AI reply has invalid score " + evaluation.userId() + ": " + evaluation.score());
            }
        }
        return response.evaluations();
    }
}
