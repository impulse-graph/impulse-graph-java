#!/bin/bash
cd /Users/jesse/impulse/impulse-graph-java
mvn clean test-compile -pl impulse-vm
cd impulse-vm
java --add-modules jdk.incubator.vector --enable-preview --enable-native-access=ALL-UNNAMED -cp target/classes:target/test-classes:../impulse-api/target/classes:../impulse-spec/target/classes:../impulse-core/target/classes org.impulsegraph.vm.StreamShaderBenchmark
