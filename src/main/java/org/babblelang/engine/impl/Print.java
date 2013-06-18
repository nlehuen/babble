package org.babblelang.engine.impl;

import java.util.Map;

public class Print implements Callable {
    @Override
    public Object call(Map<String, Object> parameters) {
        for (Object a : parameters.entrySet()) {
            System.out.print(a.toString());
        }
        return null;
    }
}
