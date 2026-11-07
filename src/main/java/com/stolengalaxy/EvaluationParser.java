package com.stolengalaxy;
import com.google.gson.*;
import java.util.List;
public record Eval(
        @serializedName("user_id") int userId,
        List<String> evaluations){}

public class EvaluationParser {
    Gson gson = new Gson();

    public Eval parse(String json) {
        return gson.fromJson(json, Eval.class);
    }
}
