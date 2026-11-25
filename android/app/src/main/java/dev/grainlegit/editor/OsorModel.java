package dev.grainlegit.editor;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtEpDevice;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import android.os.SystemClock;
import android.util.Half;
import android.util.Log;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.json.JSONObject;

/**
 * The precompiled 512 px SDXL Phase II pipeline. Graph sessions stay open between removals so only
 * the first one pays for loading them. If the NPU cannot hold every graph at once, the model falls
 * back to loading each graph for its run and closing it afterwards.
 */
final class OsorModel implements AutoCloseable {
    static final int SIDE = 512;
    static final int LATENT_SIDE = SIDE / 8;
    static final String TAG = "GrainlegitOSOR";
    private static final List<String> DENOISER = List.of(
        "01-down0", "02-down1", "03-down2-0", "04-down2-1", "05-middle",
        "06-up0-0", "07-up0-1", "08-up0-2", "09-up1", "10-output"
    );
    private static final List<String> STAGES = List.of(
        "vae-encoder", "01-down0", "02-down1", "03-down2-0", "04-down2-1", "05-middle",
        "06-up0-0", "07-up0-1", "08-up0-2", "09-up1", "10-output", "vae-decoder"
    );

    private final OrtEnvironment runtime = OrtEnvironment.getEnvironment();
    private final OrtEpDevice npu;
    private final File models;
    private final float alphaCumulative;
    private final float vaeScale;
    private final Map<String, OrtSession> sessions = new HashMap<>();
    private boolean oneAtATime = false;

    OsorModel(File models, File libraryDir) throws Exception {
        this.models = models;
        npu = Qnn.npu(libraryDir);
        byte[] bytes = Files.readAllBytes(new File(models, "pipeline.json").toPath());
        JSONObject config = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        alphaCumulative = (float) config.getDouble("alpha_cumulative");
        vaeScale = (float) config.getDouble("vae_scale");
    }

    /** Open every graph session now, so the next removal only runs them. */
    synchronized void preload(Runnable checkActive) throws Exception {
        for (String stage : STAGES) {
            if (oneAtATime) return;
            checkActive.run();
            long start = SystemClock.elapsedRealtime();
            OrtSession session = session(stage);
            if (!oneAtATime) Log.i(TAG, stage + " preload=" + (SystemClock.elapsedRealtime() - start) + "ms");
            else session.close();
        }
    }

    @Override
    public synchronized void close() {
        closeSessions();
    }

    synchronized int[] erase(int[] photo, boolean[] hole, Runnable checkActive) throws Exception {
        int area = SIDE * SIDE;
        if (photo.length != area || hole.length != area) {
            throw new IllegalArgumentException("OSOR requires a 512 × 512 image and mask");
        }
        int plane = LATENT_SIDE * LATENT_SIDE;
        int latentCount = 4 * plane;
        float[] image = new float[3 * area];
        for (int channel = 0; channel < 3; channel++) {
            int shift = 16 - 8 * channel;
            for (int i = 0; i < area; i++) image[channel * area + i] = ((photo[i] >> shift) & 255) / 127.5f - 1f;
        }
        checkActive.run();
        float[] moments = run("vae-encoder", Map.of("image", image)).get("moments");
        float[] zPhoto = new float[latentCount];
        float[] noised = new float[latentCount];
        float[] input = new float[9 * plane];
        Random random = new Random(42);
        float signal = (float) Math.sqrt(alphaCumulative);
        float noise = (float) Math.sqrt(1f - alphaCumulative);
        for (int i = 0; i < latentCount; i++) {
            double std = Math.exp(.5 * Math.max(-30, Math.min(20, moments[i + latentCount])));
            zPhoto[i] = (float) ((moments[i] + std * random.nextGaussian()) * vaeScale);
        }
        for (int i = 0; i < latentCount; i++) {
            noised[i] = (float) (zPhoto[i] * signal + random.nextGaussian() * noise);
            input[i] = noised[i];
            input[i + 5 * plane] = zPhoto[i];
        }
        // Max pooling matches the Phase II input path and preserves thin brush marks.
        for (int i = 0; i < area; i++) {
            if (hole[i]) input[latentCount + (i / SIDE / 8) * LATENT_SIDE + (i % SIDE / 8)] = 1f;
        }
        Map<String, float[]> values = new HashMap<>();
        values.put("latent", input);
        for (String name : DENOISER) {
            checkActive.run();
            values.putAll(run(name, values));
        }
        float[] result = values.get("eps_alpha");
        float[] refined = new float[latentCount];
        for (int i = 0; i < latentCount; i++) {
            float a = (float) (1.0 / (1.0 + Math.exp(-result[latentCount + i % plane])));
            float clean = (noised[i] - noise * result[i]) / signal;
            refined[i] = (clean * a + zPhoto[i] * (1f - a)) / vaeScale;
        }
        checkActive.run();
        float[] decoded = run("vae-decoder", Map.of("latent", refined)).get("image");
        int[] pixels = new int[area];
        for (int i = 0; i < area; i++) {
            int color = 0xff000000;
            for (int channel = 0; channel < 3; channel++) {
                float value = decoded[channel * area + i];
                if (!Float.isFinite(value)) throw new IllegalStateException("OSOR decoded non-finite pixels");
                int quantized = Math.round(Math.max(0, Math.min(1, (value + 1f) / 2f)) * 255f);
                color |= quantized << (16 - 8 * channel);
            }
            pixels[i] = color;
        }
        return pixels;
    }

    /**
     * The open session for [stage]. When opening one fails while others are open, the NPU is out of
     * room: close them all and keep loading one graph at a time from then on.
     */
    private OrtSession session(String stage) throws Exception {
        OrtSession cached = sessions.get(stage);
        if (cached != null) return cached;
        OrtSession session;
        try {
            session = open(stage);
        } catch (Exception error) {
            if (sessions.isEmpty()) throw error;
            Log.w(TAG, "Could not keep " + stage + " loaded with " + sessions.size()
                + " other graphs; loading one graph at a time", error);
            closeSessions();
            oneAtATime = true;
            return open(stage);
        }
        if (!oneAtATime) sessions.put(stage, session);
        return session;
    }

    private OrtSession open(String stage) throws Exception {
        File wrapper = new File(models, stage + ".onnx");
        if (!wrapper.isFile()) throw new IllegalStateException("Missing compiled graph " + stage);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4);
            options.addExecutionProvider(List.of(npu), Map.of("backend_type", "htp", "htp_performance_mode", "burst"));
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            return runtime.createSession(wrapper.getAbsolutePath(), options);
        }
    }

    private void closeSessions() {
        for (OrtSession session : sessions.values()) {
            try {
                session.close();
            } catch (Exception error) {
                Log.w(TAG, "Could not close an OSOR graph", error);
            }
        }
        sessions.clear();
    }

    private Map<String, float[]> run(String stage, Map<String, float[]> values) throws Exception {
        long start = SystemClock.elapsedRealtime();
        OrtSession session = session(stage);
        try {
            long loadedAt = SystemClock.elapsedRealtime();
            Map<String, OnnxTensor> tensors = new HashMap<>();
            try {
                for (var entry : session.getInputInfo().entrySet()) {
                    String name = entry.getKey();
                    float[] data = values.get(name);
                    if (data == null) throw new IllegalStateException(stage + " has no input " + name);
                    tensors.put(name, tensor(data, (TensorInfo) entry.getValue().getInfo()));
                }
                Map<String, float[]> outputs = new HashMap<>();
                try (OrtSession.Result inference = session.run(tensors)) {
                    for (var entry : inference) {
                        FloatBuffer buffer = ((OnnxTensor) entry.getValue()).getFloatBuffer();
                        float[] copy = new float[buffer.remaining()];
                        buffer.get(copy);
                        outputs.put(entry.getKey(), copy);
                    }
                }
                long finished = SystemClock.elapsedRealtime();
                Log.i(TAG, stage + " load=" + (loadedAt - start) + "ms run=" + (finished - loadedAt) + "ms");
                return outputs;
            } finally {
                tensors.values().forEach(OnnxTensor::close);
            }
        } finally {
            if (oneAtATime) session.close();
        }
    }

    private OnnxTensor tensor(float[] input, TensorInfo info) throws Exception {
        if (info.type == OnnxJavaType.FLOAT) {
            return OnnxTensor.createTensor(runtime, FloatBuffer.wrap(input), info.getShape());
        }
        if (info.type != OnnxJavaType.FLOAT16) throw new IllegalArgumentException("Unsupported input " + info.type);
        var halves = ByteBuffer.allocateDirect(2 * input.length).order(ByteOrder.nativeOrder()).asShortBuffer();
        for (float value : input) halves.put(Half.toHalf(value));
        halves.rewind();
        return OnnxTensor.createTensor(runtime, halves, info.getShape(), OnnxJavaType.FLOAT16);
    }
}
