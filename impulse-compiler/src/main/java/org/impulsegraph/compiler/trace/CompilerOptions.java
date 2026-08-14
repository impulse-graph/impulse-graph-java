package org.impulsegraph.compiler.trace;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration options and parameter bindings for the ImpScheme compiler pipeline.
 */
public record CompilerOptions(
        boolean enableTracing,
        boolean enableStage1Optimization,
        boolean enableStage2Optimization,
        boolean enableConstantFolding,
        boolean enableDirectionSelection,
        boolean enableFilterPushdown,
        boolean enableExperimental2HopFusion,
        PassTraceListener traceListener,
        Map<String, Object> parameters
) {
    public static final CompilerOptions DEFAULT = new CompilerOptions(
            Boolean.getBoolean("impulse.compiler.trace"),
            true,
            true,
            true,
            true,
            true,
            org.impulsegraph.api.config.OptimizerConfig.ENABLE_EXPERIMENTAL_2HOP_FUSION,
            PassTraceListener.SYSTEM_OUT,
            Map.of()
    );

    public CompilerOptions(
            boolean enableTracing,
            boolean enableStage1Optimization,
            boolean enableStage2Optimization,
            boolean enableConstantFolding,
            boolean enableDirectionSelection,
            boolean enableFilterPushdown,
            PassTraceListener traceListener) {
        this(enableTracing, enableStage1Optimization, enableStage2Optimization, enableConstantFolding, enableDirectionSelection, enableFilterPushdown, org.impulsegraph.api.config.OptimizerConfig.ENABLE_EXPERIMENTAL_2HOP_FUSION, traceListener, Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enableTracing = Boolean.getBoolean("impulse.compiler.trace");
        private boolean enableStage1Optimization = true;
        private boolean enableStage2Optimization = true;
        private boolean enableConstantFolding = true;
        private boolean enableDirectionSelection = true;
        private boolean enableFilterPushdown = true;
        private boolean enableExperimental2HopFusion = org.impulsegraph.api.config.OptimizerConfig.ENABLE_EXPERIMENTAL_2HOP_FUSION;
        private PassTraceListener traceListener = PassTraceListener.SYSTEM_OUT;
        private final Map<String, Object> parameters = new HashMap<>();

        public Builder withTracing(boolean enable) {
            this.enableTracing = enable;
            return this;
        }

        public Builder withExperimental2HopFusion(boolean enable) {
            this.enableExperimental2HopFusion = enable;
            return this;
        }

        public Builder withStage1Optimization(boolean enable) {
            this.enableStage1Optimization = enable;
            return this;
        }

        public Builder withStage2Optimization(boolean enable) {
            this.enableStage2Optimization = enable;
            return this;
        }

        public Builder withConstantFolding(boolean enable) {
            this.enableConstantFolding = enable;
            return this;
        }

        public Builder withDirectionSelection(boolean enable) {
            this.enableDirectionSelection = enable;
            return this;
        }

        public Builder withFilterPushdown(boolean enable) {
            this.enableFilterPushdown = enable;
            return this;
        }

        public Builder withTraceListener(PassTraceListener listener) {
            this.traceListener = listener != null ? listener : PassTraceListener.SYSTEM_OUT;
            return this;
        }

        public Builder withParameter(String name, Object value) {
            if (name != null) {
                this.parameters.put(name, value);
            }
            return this;
        }

        public Builder withParameters(Map<String, Object> map) {
            if (map != null) {
                this.parameters.putAll(map);
            }
            return this;
        }

        public CompilerOptions build() {
            return new CompilerOptions(
                    enableTracing,
                    enableStage1Optimization,
                    enableStage2Optimization,
                    enableConstantFolding,
                    enableDirectionSelection,
                    enableFilterPushdown,
                    enableExperimental2HopFusion,
                    traceListener,
                    Collections.unmodifiableMap(new HashMap<>(parameters))
            );
        }
    }
}
