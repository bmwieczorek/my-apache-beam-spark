package com.bawi.beam;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MyMultiOutputPCollectionReuseLoggingJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(MyMultiOutputPCollectionReuseLoggingJob.class.getSimpleName());

    private static class LoggingFn<T> extends DoFn<T, T> {
        private final String label;

        private LoggingFn(String label) {
            this.label = label;
        }

        @ProcessElement
        public void process(@Element T element, OutputReceiver<T> outputReceiver) {
            LOGGER.info("[" + label + "] processing {}", element);
            outputReceiver.output(element);
        }

        @Override
        public String toString() {
            return this.getClass().getSimpleName() + "(" + label + ")";
        }
    }

    public static void main(String[] args) {
        args = MyPipelineUtils.updateArgsAndAutodetectRunnerIfLocal(args
                // Spark runner only options (activate spark-runner maven profile)
                , "--cacheDisabled=false"
//                , "--cacheDisabled=true"
        );
        PipelineOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(PipelineOptions.class);
        Pipeline pipeline = Pipeline.create(options);

        PCollection<Integer> all = pipeline.apply(Create.of(1, 2, 4, 3, 5))
                .apply("LogAll", ParDo.of(new LoggingFn<>("LogAllLabel")));

        all
            .apply("FilterEven", Filter.by(e -> e % 2 == 0))
            .apply("LogEven", ParDo.of(new LoggingFn<>("LogEvenLabel")));

        all
            .apply("FilterEven", Filter.by(e -> e % 2 == 1))
            .apply("LogOdd", ParDo.of(new LoggingFn<>("LogOddLabel")));

        PipelineResult pipelineResult = pipeline.run();
        pipelineResult.waitUntilFinish();
    }
}
