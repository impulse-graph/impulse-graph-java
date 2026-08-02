package org.impulsegraph.api;

/**
 * Terminal collector return types for {@link ImpulseGraphQuery}.
 */
public enum ReturnType {
    ROARING_BITSET,
    DENSE_BITSET,
    NODE_ARRAY,
    COUNT,
    EXISTS,
    SINGLE_NODE
}
