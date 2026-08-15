package com.matchmaker.server.game.checkers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Move {

    private final List<Square> path;

    public Move(List<Square> path) {
        this.path = path;
    }

    public List<Square> getPath() {
        return path;
    }

    public static Move fromJson(String json) {
        JSONObject obj = new JSONObject(json);
        JSONArray pathArray = obj.getJSONArray("path");
        List<Square> path = new ArrayList<>();
        for (int i = 0; i < pathArray.length(); i++) {
            path.add(Square.fromAlgebraic(pathArray.getString(i)));
        }
        return new Move(path);
    }

    public String toJson() {
        JSONArray pathArray = new JSONArray();
        for (Square square : path) {
            pathArray.put(square.toAlgebraic());
        }
        JSONObject obj = new JSONObject();
        obj.put("path", pathArray);
        return obj.toString();
    }
}
