package Petroleum.Polyphony;

import Petroleum.Graph.AbstractNode;
import Petroleum.Graph.Node;
import Petroleum.Graph.NodeContext;
import Petroleum.Graph.OneShot;
import Petroleum.Graph.Port;
import Petroleum.Instruments.InstrumentNode;
import Petroleum.Patch.Patch;
import Petroleum.Sinks.AudioSink;
import Petroleum.Sinks.RecordingEnd;
import Petroleum.Sinks.VirtualLineOut;
import Petroleum.Sources.ConstantNode;
import Petroleum.Sources.MicInputNode;
import Petroleum.Sources.MidiInputNode;
import Petroleum.Tracker.MidiPlayerNode;
import Petroleum.Tracker.PianoRollNode;
import Petroleum.Tracker.TrackerNode;

import java.util.ArrayList;
import java.util.List;

public class PolyNode extends AbstractNode {
    public static final String SEPARATOR = "/";

    private final int voiceCount;
    private final int inCount;
    private final int outCount;
    private volatile Node[] voices;
    private final float[][][] voiceInputs;
    private final float[][][] voiceOutputs;
    private String stamp;

    public static PolyNode create(List<Node> voices, String stamp) {
        return voices.get(0) instanceof OneShot
                ? new OneShotPolyNode(voices, stamp)
                : new PolyNode(voices, stamp);
    }

    public static boolean supports(Node node) {
        return !(node instanceof PolyNode || node instanceof AudioSink || node instanceof VirtualLineOut
                || node instanceof RecordingEnd || node instanceof ConstantNode || node instanceof MidiInputNode
                || node instanceof MicInputNode || node instanceof TrackerNode || node instanceof PianoRollNode
                || node instanceof MidiPlayerNode || node instanceof InstrumentNode)
                && !node.getInputPorts().isEmpty();
    }

    public static Node unwrap(Node node) {
        return node instanceof PolyNode poly ? poly.template() : node;
    }

    public static int voicesOf(Node node) {
        return node instanceof PolyNode poly ? poly.voiceCount : 1;
    }

    PolyNode(List<Node> voices, String stamp) {
        super(inputsOf(voices), outputsOf(voices));
        Node template = voices.get(0);
        if (voices.size() < 2) {
            throw new IllegalArgumentException("A polyphonic node needs at least two voices");
        }
        if (!supports(template)) {
            throw new IllegalArgumentException(template.getName() + " cannot be made polyphonic");
        }
        this.voiceCount = voices.size();
        this.inCount = template.getInputPorts().size();
        this.outCount = template.getOutputPorts().size();
        this.voices = voices.toArray(new Node[0]);
        this.stamp = stamp;
        this.voiceInputs = new float[voiceCount][inCount][Patch.DEFAULT_BLOCK_CAPACITY];
        this.voiceOutputs = new float[voiceCount][outCount][];
    }

    private static List<Port> inputsOf(List<Node> voices) {
        List<Port> template = voices.get(0).getInputPorts();
        List<Port> ports = new ArrayList<>(template);
        for (int v = 1; v <= voices.size(); v++) {
            for (Port port : template) {
                ports.add(new Port(port.name() + SEPARATOR + v, Float.NaN));
            }
        }
        return ports;
    }

    private static List<String> outputsOf(List<Node> voices) {
        List<String> template = voices.get(0).getOutputPorts();
        List<String> ports = new ArrayList<>(template);
        for (int v = 1; v <= voices.size(); v++) {
            for (String port : template) {
                ports.add(port + SEPARATOR + v);
            }
        }
        return ports;
    }

    public Node template() {
        return voices[0];
    }

    public int voiceCount() {
        return voiceCount;
    }

    public int sharedInputCount() {
        return inCount;
    }

    public String stamp() {
        return stamp;
    }

    public void replaceVoices(List<Node> copies, String stamp) {
        if (copies.size() != voiceCount - 1) {
            throw new IllegalArgumentException("Expected " + (voiceCount - 1) + " voice copies");
        }
        Node[] next = new Node[voiceCount];
        next[0] = voices[0];
        for (int v = 1; v < voiceCount; v++) {
            next[v] = copies.get(v - 1);
        }
        this.voices = next;
        this.stamp = stamp;
    }

    Node[] voices() {
        return voices;
    }

    @Override
    public void process(float[][] inputs, float[][] outputs, int frameCount, NodeContext context) {
        Node[] current = voices;
        for (int v = 0; v < voiceCount; v++) {
            float[][] in = voiceInputs[v];
            int base = inCount * (v + 1);
            for (int p = 0; p < inCount; p++) {
                float[] shared = inputs[p];
                float[] own = inputs[base + p];
                float[] dest = in[p];
                for (int s = 0; s < frameCount; s++) {
                    float x = own[s];
                    dest[s] = x != x ? shared[s] : x;
                }
            }
            float[][] out = voiceOutputs[v];
            int outBase = outCount * (v + 1);
            for (int o = 0; o < outCount; o++) {
                out[o] = outputs[outBase + o];
            }
            current[v].process(in, out, frameCount, context);
        }
        for (int o = 0; o < outCount; o++) {
            float[] sum = outputs[o];
            System.arraycopy(voiceOutputs[0][o], 0, sum, 0, frameCount);
            for (int v = 1; v < voiceCount; v++) {
                float[] voice = voiceOutputs[v][o];
                for (int s = 0; s < frameCount; s++) {
                    sum[s] += voice[s];
                }
            }
        }
    }

    @Override
    public String getName() {
        return voices[0].getName() + " (" + voiceCount + " voices)";
    }
}
