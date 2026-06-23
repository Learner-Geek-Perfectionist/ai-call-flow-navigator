package com.ouyang.asbridge;

@FunctionalInterface
interface SourceOpener {
    void open(ResolvedOpenRequest request);
}
