package com.bawi.beam;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyLoggingJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(MyLoggingJob.class);

    static class MyFunction implements SerializableFunction<String, Void> {
        @Override
        public Void apply(String word) {
            LOGGER.info(word);
            return null;
        }
    }

    public static void main(String[] args) {
        args = PipelineUtils.updateArgsAndAutodetectRunner(args);
        PipelineOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().create();
        Pipeline pipeline = Pipeline.create(options);

        pipeline
                .apply("Create", Create.of("hello", "jdd", "conf"))
                .apply("Log", MapElements.into(TypeDescriptors.voids()).via(new MyFunction()));

        pipeline.run();
    }
}

