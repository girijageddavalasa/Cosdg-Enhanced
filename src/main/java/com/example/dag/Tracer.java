package com.example.dag;

import java.util.*;

public class Tracer {
    public static List<String> hits = new ArrayList<>();
    
    public static void hit(String id) {
        hits.add(id);
    }

    public static void clear() {
        hits.clear();
    }
}
