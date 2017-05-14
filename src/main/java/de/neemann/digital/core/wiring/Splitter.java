package de.neemann.digital.core.wiring;

import de.neemann.digital.core.*;
import de.neemann.digital.core.element.*;
import de.neemann.digital.lang.Lang;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.StringTokenizer;

/**
 * The Splitter.
 * The highZ attribute is necessary because at the time the outputs are created the highZ flag
 * needs to be given to the {@link ObservableValue} constructor.
 * At this time I don't know if the input can became highZ. Because I need this information
 * before I can get it from the inputs, the user has to provide this information.
 *
 * @author hneemann
 */
public class Splitter implements Element {

    /**
     * Create a one to N splitter
     *
     * @param bits number of outputs
     * @return the splitter
     */
    public static Splitter createOneToN(int bits) {
        Ports in = new Ports();
        in.add(new Port(bits));
        Ports out = new Ports();
        for (int i = 0; i < bits; i++)
            out.add(new Port(1));
        return new Splitter(in, out, false);
    }

    /**
     * Create a N to one splitter
     *
     * @param bits number of inputs
     * @return the splitter
     */
    public static Splitter createNToOne(int bits) {
        Ports in = new Ports();
        for (int i = 0; i < bits; i++)
            in.add(new Port(1));
        Ports out = new Ports();
        out.add(new Port(bits));
        return new Splitter(in, out, false);
    }

    /**
     * The splitters type description
     */
    public static final ElementTypeDescription DESCRIPTION
            = new SplitterTypeDescription()
            .addAttribute(Keys.ROTATE)
            .addAttribute(Keys.INPUT_SPLIT)
            .addAttribute(Keys.OUTPUT_SPLIT)
            .addAttribute(Keys.IS_HIGH_Z)
            .setShortName("");

    private final ObservableValues outputs;
    private final Ports inPorts;
    private final Ports outPorts;
    private final boolean highZIn;
    private ObservableValues inputs;


    private static class SplitterTypeDescription extends ElementTypeDescription {
        SplitterTypeDescription() {
            super(Splitter.class);
        }

        @Override
        public PinDescriptions getInputDescription(ElementAttributes elementAttributes) throws BitsException {
            Ports p = new Ports(elementAttributes.get(Keys.INPUT_SPLIT));
            return p.getNames(PinDescription.Direction.input);
        }

    }

    /**
     * creates a new instance
     *
     * @param attributes the attributes
     * @throws BitsException BitsException
     */
    public Splitter(ElementAttributes attributes) throws BitsException {
        this(new Ports(attributes.get(Keys.INPUT_SPLIT)),
                new Ports(attributes.get(Keys.OUTPUT_SPLIT)),
                attributes.get(Keys.IS_HIGH_Z));
    }

    private Splitter(Ports inPorts, Ports outPorts, boolean highZIn) {
        this.inPorts = inPorts;
        this.outPorts = outPorts;
        this.highZIn = highZIn;
        outputs = outPorts.getOutputs(highZIn);
    }

    @Override
    public void setInputs(ObservableValues inputs) throws NodeException {
        this.inputs = inputs;

        if (inPorts.getBits() != outPorts.getBits())
            throw new BitsException(Lang.get("err_splitterBitsMismatch"), ImmutableList.combine(inputs, outputs));

        for (int i = 0; i < inputs.size(); i++) {
            Port inPort = inPorts.getPort(i);
            if (inPort.getBits() != inputs.get(i).getBits())
                throw new BitsException(Lang.get("err_splitterBitsMismatch"), inputs);
        }

        if (highZIn) {
            if (inputs.size() != 1)
                throw new NodeException(Lang.get("err_splitterAllowsOnlyOneHighZInput"), inputs);
        } else {
            for (int i = 0; i < inputs.size(); i++) {
                if (inputs.get(i).supportsHighZ())
                    throw new NodeException(Lang.get("err_splitterDoesNotSupportHighZInputs"), inputs);
            }
        }

        for (Port out : outPorts)
            fillOutput(out);
    }

    private void fillOutput(Port out) throws NodeException {
        for (Port in : inPorts) {
            if (in.getPos() + in.getBits() <= out.getPos() || out.getPos() + out.getBits() <= in.getPos())
                continue; // this input is not needed to fill the output!!!

            // out is filled completely by the actual single input value!
            if (out.getPos() >= in.getPos()
                    && out.getPos() + out.getBits() <= in.getPos() + in.getBits()) {

                final int bitPos = out.getPos() - in.getPos();
                final ObservableValue inValue = inputs.get(in.number);
                final ObservableValue outValue = outputs.get(out.number);
                if (highZIn)
                    inValue.addObserverToValue(() -> {
                        if (inValue.isHighZ())
                            outValue.set(0, true);
                        else
                            outValue.set(inValue.getValue() >> bitPos, false);
                    });
                else
                    inValue.addObserverToValue(() -> outValue.setValue(inValue.getValue() >> bitPos));
                break; // done!! out is completely filled!
            }

            // Highz is not allowed a this point so throw an exception!
            // Can not happen because of input checking in setInputs method!
            // so no translation is necessary
            if (highZIn) throw new NodeException("invalid splitter input configuration!");

            // complete in value needs to be copied to a part of the output
            if (out.getPos() <= in.getPos() && in.getPos() + in.getBits() <= out.getPos() + out.getBits()) {
                final int bitPos = in.getPos() - out.getPos();
                final long mask = ~(((1L << in.bits) - 1) << bitPos);
                final ObservableValue inValue = inputs.get(in.number);
                final ObservableValue outValue = outputs.get(out.number);
                inputs.get(in.number).addObserverToValue(() -> {
                    long in1 = inValue.getValue();
                    long out1 = outValue.getValue();
                    outValue.setValue((out1 & mask) | (in1 << bitPos));
                });
                continue; // done with this input, its completely copied to the output!
            }

            // If this point is reached, a part of the input needs to be copied to a part of the output!

            // upper part of input needs to be copied to the lower part of the output
            if (in.getPos() < out.getPos()) {
                final int bitsToCopy = in.getPos() + in.getBits() - out.getPos();
                final long mask = ~((1L << bitsToCopy) - 1);
                final int shift = out.getPos() - in.getPos();
                final ObservableValue inValue = inputs.get(in.number);
                final ObservableValue outValue = outputs.get(out.number);
                inputs.get(in.number).addObserverToValue(() -> {
                    long in12 = inValue.getValue();
                    long out12 = outValue.getValue();
                    outValue.setValue((out12 & mask) | (in12 >> shift));
                });
                continue;
            }

            // lower part of input needs to be copied to the upper part of the output
            final int bitsToCopy = out.getPos() + out.getBits() - in.getPos();
            final int shift = in.getPos() - out.getPos();
            final long mask = ~(((1L << bitsToCopy) - 1) << shift);
            final ObservableValue inValue = inputs.get(in.number);
            final ObservableValue outValue = outputs.get(out.number);
            inputs.get(in.number).addObserverToValue(() -> {
                long in13 = inValue.getValue();
                long out13 = outValue.getValue();
                outValue.setValue((out13 & mask) | (in13 << shift));
            });

        }
    }

    @Override
    public ObservableValues getOutputs() {
        return outputs;
    }

    @Override
    public void registerNodes(Model model) {
        // a splitter has no nodes, it works without a delay
    }

    @Override
    public void init(Model m) {
        for (ObservableValue v : inputs)
            v.fireHasChanged();
    }

    static final class Ports implements Iterable<Port> {
        private final ArrayList<Port> ports;
        private int bits = 0;

        Ports() {
            ports = new ArrayList<>();
        }

        Ports(String definition) throws BitsException {
            this();
            StringTokenizer st = new StringTokenizer(definition, ",", false);
            while (st.hasMoreTokens()) {
                try {
                    String strVal = st.nextToken().trim();
                    int pos = strVal.indexOf('*');
                    if (pos < 0)
                        add(new Port(Integer.decode(strVal)));
                    else {
                        int b = Integer.decode(strVal.substring(0, pos).trim());
                        int count = Integer.decode(strVal.substring(pos + 1).trim());
                        for (int i = 0; i < count; i++)
                            add(new Port(b));
                    }
                } catch (RuntimeException e) {
                    throw new BitsException(Lang.get("err_spitterDefSyntaxError", definition), null);
                }
            }
            if (ports.isEmpty())
                add(new Port(1));
        }

        private void add(Port port) {
            port.setPos(bits, ports.size());
            ports.add(port);
            bits += port.bits;
        }

        public int getBits() {
            return bits;
        }

        public PinDescriptions getNames(PinDescription.Direction dir) {
            PinInfo[] name = new PinInfo[ports.size()];
            for (int i = 0; i < name.length; i++) {
                final Port port = ports.get(i);
                if (port.getBits() == 1)
                    name[i] = new PinInfo(port.getName(), Lang.get("elem_Splitter_pin_in_one", port.getName()), dir);
                else
                    name[i] = new PinInfo(port.getName(), Lang.get("elem_Splitter_pin_in", port.getName()), dir);
            }

            return new PinDescriptions(name);
        }

        public ObservableValues getOutputs(boolean isHighZ) {
            ArrayList<ObservableValue> outputs = new ArrayList<>(ports.size());
            for (Port p : ports) {
                if (p.getBits() == 1)
                    outputs.add(new ObservableValue(p.getName(), p.getBits(), isHighZ).setDescription(Lang.get("elem_Splitter_pin_out_one", p.getName())));
                else
                    outputs.add(new ObservableValue(p.getName(), p.getBits(), isHighZ).setDescription(Lang.get("elem_Splitter_pin_out", p.getName())));
            }
            return new ObservableValues(outputs);
        }

        public Port getPort(int i) {
            return ports.get(i);
        }

        @Override
        public Iterator<Port> iterator() {
            return ports.iterator();
        }
    }

    private static final class Port {
        private final int bits;
        private String name;
        private int pos;
        private int number;

        Port(int bits) {
            this.bits = bits;
        }

        public int getBits() {
            return bits;
        }

        public int getPos() {
            return pos;
        }

        public String getName() {
            return name;
        }

        public void setPos(int pos, int number) {
            this.pos = pos;
            this.number = number;
            if (bits == 1)
                name = "" + pos;
            else if (bits == 2)
                name = "" + pos + "," + (pos + 1);
            else
                name = "" + pos + "-" + (pos + bits - 1);
        }
    }

}
