package com.stolengalaxy;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;
public class EvaluationParser {
    private static final Gson gson = new Gson();
    private static final Type EVALUATION_LIST = new TypeToken<List<Evaluation>>(){}.getType();

    public static List<Evaluation> parse(JsonArray json) {
        List<Evaluation> evaluations = gson.fromJson(json, EVALUATION_LIST);
        if (evaluations == null || evaluations.isEmpty()){
            throw new IllegalArgumentException("AI reply has no evaluations");
        }
        for (Evaluation evaluation : evaluations){
            if (evaluation.score() == null || evaluation.score() < 0 || evaluation.score() > 10){
                throw new IllegalArgumentException("AI reply has invalid score " + evaluation.userId() + ": " + evaluation.score());
            }
        }
        return evaluations;
    }
}
