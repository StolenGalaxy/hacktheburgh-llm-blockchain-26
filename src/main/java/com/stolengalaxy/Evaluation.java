package com.stolengalaxy;
import com.google.gson.*;
import com.google.gson.annotations.SerializedName;

import java.util.List;

public record Evaluation(
        @SerializedName("user_id") int userId,
        List<String> evaluations){
}

