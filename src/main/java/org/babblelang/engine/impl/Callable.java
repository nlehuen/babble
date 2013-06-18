package org.babblelang.engine.impl;

import java.util.Map;

public interface Callable {
    public Object call(Map<String, Object> parameters);
}
