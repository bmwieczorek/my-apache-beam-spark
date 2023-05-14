package com.bawi.beam;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


public class MyMultiOutputLoggingJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(MyMultiOutputLoggingJob.class.getSimpleName());
    static TupleTag<String> oddTag = new TupleTag<>("odd");
    static TupleTag<String> evenTag = new TupleTag<>("even");

    private static final Counter EVEN_NUMBERS_COUNT = Metrics.counter(MyMultiOutputLoggingJob.class.getSimpleName(), "even_numbers_count");
    private static final Counter ODD_NUMBERS_COUNT = Metrics.counter(MyMultiOutputLoggingJob.class.getSimpleName(), "odd_numbers_count");

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

    private static class DispatchingFn extends DoFn<Integer, String> {
        @ProcessElement
        public void process(@Element Integer element, MultiOutputReceiver multiOutputReceiver) {
            if (element % 2 == 0) {
                EVEN_NUMBERS_COUNT.inc();
                multiOutputReceiver.get(evenTag).output(String.valueOf(element));
            }
            if (element % 2 == 1) {
                ODD_NUMBERS_COUNT.inc();
                multiOutputReceiver.get(oddTag).output(String.valueOf(element));
            }
        }
    }

    public static void main(String[] args) {
        args = MyPipelineUtils.updateArgsAndAutodetectRunnerIfLocal(args
                // Spark runner only options (activate spark-runner maven profile)
                , "--storageLevel=MEMORY_AND_DISK_SER"
                , "--cacheDisabled=true"
        );
        PipelineOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(PipelineOptions.class);
        Pipeline pipeline = Pipeline.create(options);

        PCollectionTuple collections = pipeline.apply(Create.of(1,2, 4, 3, 5))
                .apply("LogAll", ParDo.of(new LoggingFn<>("LogAllLabel")))
                .apply("Dispatch", new MyMultiPTransform());

        PCollection<String> even = collections.get(evenTag).setCoder(StringUtf8Coder.of());
        even.apply("LogEven", ParDo.of(new LoggingFn<>("LogEvenLabel")));

        PCollection<String> odd = collections.get(oddTag).setCoder(StringUtf8Coder.of());
        odd.apply("LogOdd", ParDo.of(new LoggingFn<>("LogOddLabel")));

        PipelineResult pipelineResult = pipeline.run();
        pipelineResult.waitUntilFinish();
        MetricQueryResults metricQueryResults = pipelineResult.metrics().allMetrics();
        LOGGER.info("MyMultiOutputLoggingJob: " + getCounters(metricQueryResults));
    }

    static class MyMultiPTransform extends PTransform<@NonNull PCollection<Integer>, @NonNull PCollectionTuple> {
        @Override
        public PCollectionTuple expand(PCollection<Integer> input) {
            return input.apply(ParDo.of(new DispatchingFn()).withOutputTags(oddTag, TupleTagList.of(evenTag)));
        }
    }

    private static List<String> getCounters(MetricQueryResults metricQueryResults) {
        return StreamSupport.stream(metricQueryResults.getCounters().spliterator(), false)
                .map(c -> c.getName().getName() + "=" + c.getAttempted())
                .collect(Collectors.toList());
    }
}
