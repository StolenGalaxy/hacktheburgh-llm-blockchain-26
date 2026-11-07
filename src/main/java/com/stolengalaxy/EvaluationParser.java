package com.stolengalaxy;
import com.google.gson.*;

public class EvaluationParser {
    Gson gson = new Gson();

    public Evaluation parse(String json) {
        return gson.fromJson(json, Evaluation.class);
    }
}
