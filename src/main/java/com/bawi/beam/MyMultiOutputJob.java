package com.bawi.beam;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.checkerframework.checker.nullness.qual.NonNull;

public class MyMultiOutputJob {
    static TupleTag<String> oddTag = new TupleTag<>("odd");
    static TupleTag<String> evenTag = new TupleTag<>("even");

    private static final Counter EVEN_NUMBERS_COUNT = Metrics.counter(MyMultiOutputJob.class.getSimpleName(), "even_numbers_count");
    private static final Counter ODD_NUMBERS_COUNT = Metrics.counter(MyMultiOutputJob.class.getSimpleName(), "odd_numbers_count");

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
        args = MyPipelineUtils.updateArgsAndAutodetectRunnerIfLocal(args,
                "--oddOutput=target/odd.txt",
                "--evenOutput=target/even.txt"
        );
        MyOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(MyOptions.class);
        Pipeline pipeline = Pipeline.create(options);

        PCollectionTuple collections = pipeline.apply(Create.of(1, -1, 2, 0, -2))
                .apply("Dispatch", new MyMultiPTransform());

        PCollection<String> even = collections.get(evenTag).setCoder(StringUtf8Coder.of());
        even.apply("Even", TextIO.write().to(options.getEvenOutput()));

        PCollection<String> odd = collections.get(oddTag).setCoder(StringUtf8Coder.of());
        odd.apply("Odd", TextIO.write().to(options.getOddOutput()));

        pipeline.run();
    }

    static class MyMultiPTransform extends PTransform<@NonNull PCollection<Integer>, @NonNull PCollectionTuple> {
        @Override
        public PCollectionTuple expand(PCollection<Integer> input) {
            return input.apply(ParDo.of(new DispatchingFn()).withOutputTags(oddTag, TupleTagList.of(evenTag)));
        }
    }

    public interface MyOptions extends PipelineOptions {
        @Validation.Required
        ValueProvider<String> getEvenOutput();
        @SuppressWarnings("unused")
        void setEvenOutput(ValueProvider<String> value);

        @Validation.Required
        ValueProvider<String> getOddOutput();
        @SuppressWarnings("unused")
        void setOddOutput(ValueProvider<String> value);
    }
}
