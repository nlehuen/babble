package org.babblelang.runtime;

/**
 * Marker interface implemented by {@link Faces} proxy invocation handlers so {@link
 * Values#fromJava} can unwrap Java-facing Babble proxies without statically referencing {@link
 * Faces}.
 */
public interface FaceProxy {
    Object instance();
}
